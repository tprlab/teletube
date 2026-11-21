package com.tprlab.teletube

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi.*
import org.drinkless.tdlib.TdApi.Function
import java.time.Instant
import java.util.Locale

internal data class ScanScope(val allChannels: Boolean, val selected: Set<Long>) {
    fun includes(id: Long): Boolean = allChannels || id in selected
}

internal data class SyncStatus(val active: Boolean = false, val detail: String? = null, val error: String? = null)

internal class TelegramRepository(private val context: Context, private val library: Library) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao = library.dao()
    private val telegram = TelegramClient(scope, ::handleUpdate) {
        _sync.value = _sync.value.copy(error = "Telegram encountered an error. Try refreshing.")
    }
    private val scanner = ChannelScanner(telegram, library)
    private val chats = mutableMapOf<Long, Chat>()
    private val groups = mutableMapOf<Long, Supergroup>()
    private val edits = mutableMapOf<Pair<Long, Long>, Job>()
    private var syncJob: Job? = null
    private var blockedUntil = 0L
    private var authenticated = false
    private val scopeChanges = Mutex()

    private val credentialPrefs = context.getSharedPreferences("telegram-app", Context.MODE_PRIVATE)
    private var credentials = readCredentials(
        credentialPrefs.getInt("id", 0).toString(), credentialPrefs.getString("hash", "").orEmpty(),
    ) ?: readCredentials(BuildConfig.TELEGRAM_API_ID.toString(), BuildConfig.TELEGRAM_API_HASH)
    private val _needsCredentials = MutableStateFlow(credentials == null)
    val needsCredentials = _needsCredentials.asStateFlow()

    private val _authorization = MutableStateFlow<AuthorizationState?>(null)
    val authorization = _authorization.asStateFlow()
    private val _authBusy = MutableStateFlow(false)
    val authBusy = _authBusy.asStateFlow()
    private val _authError = MutableStateFlow<String?>(null)
    val authError = _authError.asStateFlow()
    private val _startupError = MutableStateFlow<String?>(null)
    val startupError = _startupError.asStateFlow()

    private val _scanScope = MutableStateFlow<ScanScope?>(null)
    val scanScope = _scanScope.asStateFlow()
    private val _sync = MutableStateFlow(SyncStatus())
    val sync = _sync.asStateFlow()

    val channels = dao.channels()
    val browseChannels = dao.browseChannels().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val hiddenChannels = dao.hiddenChannels()
    val hiddenVideos = dao.hiddenVideos()
    val progress = dao.progress()
    val feed = dao.feed().stateIn(scope, SharingStarted.Eagerly, null)
    val history = dao.history()

    init {
        if (credentials != null) startConnection()
    }

    fun configureApp(id: String, hash: String) {
        if (authenticated || _authBusy.value || !_needsCredentials.value) return
        val configured = readCredentials(id, hash)
        if (configured == null) {
            _authError.value = "Enter a numeric API ID and the 32-character API hash from my.telegram.org."
            return
        }
        _authError.value = null
        credentialPrefs.edit().putInt("id", configured.id).putString("hash", configured.hash).apply()
        credentials = configured
        _needsCredentials.value = false
        retryConnection()
    }

    fun changeCredentials() {
        if (!authenticated && !_authBusy.value) {
            _authError.value = null
            _needsCredentials.value = true
        }
    }

    fun retryConnection() {
        if (_needsCredentials.value || _authBusy.value) return
        _startupError.value = null
        when (_authorization.value) {
            is AuthorizationStateWaitTdlibParameters -> configureClient()
            null, is AuthorizationStateClosed -> startConnection()
            else -> runAuth(Close())
        }
    }

    private fun startConnection() {
        scope.launch {
            try {
                _scanScope.value = dao.settings().first()?.let { settings ->
                    ScanScope(settings.allChannels, dao.selected().first().toSet())
                }
                telegram.start()
            } catch (failure: LinkageError) {
                _startupError.value = "This Android build could not load Telegram’s native library."
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _startupError.value = "The Telegram session could not start."
            }
        }
    }

    fun submitAuth(input: String) {
        val clean = input.trim()
        val request = when (_authorization.value) {
            is AuthorizationStateWaitPhoneNumber -> SetAuthenticationPhoneNumber(clean, null)
            is AuthorizationStateWaitCode -> CheckAuthenticationCode(clean)
            is AuthorizationStateWaitEmailAddress -> SetAuthenticationEmailAddress(clean)
            is AuthorizationStateWaitEmailCode -> CheckAuthenticationEmailCode(EmailAddressAuthenticationCode(clean))
            is AuthorizationStateWaitPassword -> CheckAuthenticationPassword(input)
            else -> return
        }
        runAuth(request)
    }

    fun resendCode() = runAuth(ResendAuthenticationCode(ResendCodeReasonUserRequest()))

    fun signOut() {
        if (authenticated) runAuth(LogOut())
    }

    private fun runAuth(request: Function<out Object>) {
        if (_authBusy.value) return
        _authBusy.value = true
        _authError.value = null
        scope.launch {
            try {
                telegram.call(request)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _authError.value = when (failure.message) {
                    "PHONE_NUMBER_INVALID" -> "Enter your phone number with its country code."
                    "API_ID_INVALID" -> "Telegram rejected the API ID or hash. Check your API credentials."
                    "PHONE_CODE_INVALID", "EMAIL_CODE_INVALID", "CODE_INVALID" -> "That code didn’t match. Try again."
                    "PHONE_CODE_EXPIRED" -> "That code expired. Request a new one if available."
                    "PASSWORD_HASH_INVALID" -> "That password didn’t match. Try again."
                    "EMAIL_INVALID" -> "Enter a valid email address."
                    else -> friendlyError(failure, "Telegram couldn’t complete that request. Try again.")
                }
            } finally {
                _authBusy.value = false
            }
        }
    }

    suspend fun saveScope(selection: ScanScope) = scopeChanges.withLock {
        if (!authenticated) return@withLock
        syncJob?.cancelAndJoin()
        if (!authenticated) return@withLock
        library.withTransaction {
            dao.saveSettings(ScanSettings(allChannels = selection.allChannels))
            dao.clearSelection()
            dao.saveSelection(selection.selected.map(::SelectedChannel))
        }
        if (authenticated) {
            _scanScope.value = selection
            refresh()
        }
    }

    fun refresh() {
        if (!authenticated || syncJob?.isActive == true) return
        val waitSeconds = blockedUntil - Instant.now().epochSecond
        if (waitSeconds > 0) {
            _sync.value = SyncStatus(error = "Telegram is limiting requests. Try again in ${waitSeconds}s.")
            return
        }
        syncJob = scope.launch {
            var error: String? = null
            try {
                _sync.value = SyncStatus(active = true, detail = "Finding channels")
                discoverChannels()
                val selection = _scanScope.value ?: return@launch
                val sources = dao.channels().first().filter { selection.includes(it.id) }
                val failures = mutableListOf<String>()
                for ((index, channel) in sources.withIndex()) {
                    _sync.value = SyncStatus(
                        active = true,
                        detail = "Syncing ${index + 1}/${sources.size} · ${channel.title}",
                    )
                    try {
                        scanner.scanRecent(channel.id)
                    } catch (failure: TelegramError) {
                        if (failure.code in setOf(408, 420, 429)) throw failure
                        failures += channel.title
                    }
                }
                if (failures.isNotEmpty()) {
                    error = if (failures.size == 1) "Couldn’t scan ${failures.single()}. Refresh to retry."
                    else "Couldn’t scan ${failures.size} channels. Refresh to retry."
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = friendlyError(failure, "Sync failed. Your imported links are still available.")
            } finally {
                _sync.value = SyncStatus(error = error)
            }
        }
    }

    fun stopScan() {
        syncJob?.cancel()
    }

    suspend fun setChannelHidden(channelId: Long, hidden: Boolean) {
        if (hidden) dao.hideChannel(channelId) else dao.unhideChannel(channelId)
    }

    suspend fun setVideoHidden(video: HiddenVideo, hidden: Boolean) {
        if (hidden) dao.hideVideo(video.videoId, video.title) else dao.unhideVideo(video.videoId)
    }

    suspend fun recordPlayback(playback: Playback) {
        library.withTransaction {
            if (dao.hasScanSettings()) dao.savePlayback(playback)
        }
    }

    suspend fun postLink(entry: FeedEntry): String =
        telegram.call(GetMessageLink(entry.channelId, entry.messageId, 0, 0, "", false, false)).link

    private suspend fun discoverChannels() {
        for (list in listOf(ChatListMain(), ChatListArchive())) {
            while (true) {
                try {
                    telegram.call(LoadChats(list, 100))
                } catch (failure: TelegramError) {
                    if (failure.code != 404) throw failure
                    break
                }
            }
        }

        // Write the complete catalog after TDLib has finished delivering the loaded chats.
        telegram.apply(GetAuthorizationState()) {
            library.withTransaction {
                dao.hideAllChannels()
                for (chat in chats.values) saveChannel(chat)
            }
        }
    }

    private suspend fun handleUpdate(update: Object) {
        when (update) {
            is UpdateAuthorizationState -> handleAuthorization(update.authorizationState)
            is UpdateNewChat -> {
                chats[update.chat.id] = update.chat
                if (authenticated) saveChannel(update.chat)
            }
            is UpdateChatTitle -> updateChat(update.chatId) { title = update.title }
            is UpdateChatLastMessage -> updateChat(update.chatId) { positions = update.positions }
            is UpdateChatDraftMessage -> updateChat(update.chatId) { positions = update.positions }
            is UpdateChatPosition -> updateChat(update.chatId) {
                val position = update.position
                val remaining = positions.orEmpty().filter { it.list.constructor != position.list.constructor ||
                    (it.list is ChatListFolder && (it.list as ChatListFolder).chatFolderId !=
                        (position.list as? ChatListFolder)?.chatFolderId) }
                positions = (remaining + listOfNotNull(position.takeIf { it.order != 0L })).toTypedArray()
            }
            is UpdateSupergroup -> {
                groups[update.supergroup.id] = update.supergroup
                if (authenticated) {
                    chats.values.filter {
                        (it.type as? ChatTypeSupergroup)?.supergroupId == update.supergroup.id
                    }.forEach { saveChannel(it) }
                }
            }
            is UpdateNewMessage -> {
                val message = update.message
                if (message.sendingState == null && shouldIndex(message.chatId)) {
                    replacePost(message.chatId, message.id, message.date.toLong(), message.content)
                }
            }
            is UpdateMessageContent -> if (shouldIndex(update.chatId)) trackEdit(update)
            is UpdateDeleteMessages -> if (authenticated && update.isPermanent && !update.fromCache) {
                library.withTransaction {
                    update.messageIds.forEach { id ->
                        edits.remove(update.chatId to id)?.cancel()
                        dao.removePost(update.chatId, id)
                    }
                }
            }
        }
    }

    private suspend fun handleAuthorization(state: AuthorizationState) {
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> {
                _authorization.value = state
                configureClient()
            }
            is AuthorizationStateReady -> {
                authenticated = true
                _authError.value = null
                _authorization.value = state
                refresh()
            }
            is AuthorizationStateWaitPhoneNumber, is AuthorizationStateLoggingOut -> {
                authenticated = false
                _authorization.value = state
                syncJob?.cancelAndJoin()
                edits.values.toList().forEach { it.cancel() }
                edits.clear()
                _scanScope.value = null
                _sync.value = SyncStatus()
                blockedUntil = 0
                chats.clear()
                groups.clear()
                withContext(Dispatchers.IO) { library.clearAllTables() }
            }
            is AuthorizationStateClosed -> {
                authenticated = false
                _authorization.value = state
                syncJob?.cancelAndJoin()
                edits.values.toList().forEach { it.cancel() }
                edits.clear()
                chats.clear()
                groups.clear()
                startConnection()
            }
            else -> {
                if (state is AuthorizationStateClosing) {
                    authenticated = false
                    syncJob?.cancel()
                }
                _authError.value = null
                _authorization.value = state
            }
        }
    }

    private fun configureClient() {
        scope.launch {
            try {
                val app = checkNotNull(credentials)
                val key = withContext(Dispatchers.IO) { sessionKey(context) }
                val directory = context.noBackupFilesDir.resolve("telegram")
                telegram.call(
                    SetTdlibParameters(
                        false, directory.path, directory.resolve("files").path, key,
                        true, true, true, false,
                        app.id, app.hash,
                        Locale.getDefault().language.ifBlank { "en" }, Build.MODEL,
                        Build.VERSION.RELEASE, BuildConfig.VERSION_NAME,
                    ),
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _startupError.value = "Telegram couldn’t open this session. Check the API credentials and retry."
            }
        }
    }

    private suspend fun updateChat(id: Long, apply: Chat.() -> Unit) {
        val chat = chats[id] ?: return
        chat.apply()
        if (authenticated) saveChannel(chat)
    }

    private suspend fun saveChannel(chat: Chat) {
        val status = channelStatus(chat) ?: return
        dao.saveChannel(ChannelRecord(chat.id, chat.title, status.first, status.second))
    }

    private fun channelStatus(chat: Chat): Pair<Boolean, Boolean>? {
        val type = chat.type as? ChatTypeSupergroup ?: return null
        if (!type.isChannel) return null
        val main = chat.positions.orEmpty().any { it.order != 0L && it.list is ChatListMain }
        val archived = chat.positions.orEmpty().any { it.order != 0L && it.list is ChatListArchive }
        val joined = when (val member = groups[type.supergroupId]?.status) {
            is ChatMemberStatusBanned, is ChatMemberStatusLeft -> false
            is ChatMemberStatusCreator -> member.isMember
            is ChatMemberStatusRestricted -> member.isMember
            else -> true
        }
        return (joined && (main || archived)) to archived
    }

    private fun shouldIndex(id: Long): Boolean = authenticated &&
        _scanScope.value?.includes(id) == true && chats[id]?.let(::channelStatus)?.first == true

    private suspend fun trackEdit(update: UpdateMessageContent) {
        val chatId = update.chatId
        val id = update.messageId
        val key = chatId to id
        edits.remove(key)?.cancel()
        val date = dao.postDate(chatId, id)
        if (date != null) {
            replacePost(chatId, id, date, update.newContent)
            return
        }
        val state = dao.progressFor(chatId) ?: return
        if (!state.historyComplete && state.olderCursor != 0L && id < state.olderCursor) return
        edits[key] = scope.launch {
            try {
                telegram.apply(GetMessage(chatId, id)) { message ->
                    val cutoff = state.initialCutoff ?: Instant.now().epochSecond - 30L * 86_400
                    if (shouldIndex(chatId) && (state.historyComplete || state.olderCursor != 0L || message.date >= cutoff)) {
                        replacePost(chatId, id, message.date.toLong(), message.content)
                    }
                }
            } catch (failure: TelegramError) {
                if (failure.code in setOf(420, 429)) {
                    _sync.value = _sync.value.copy(error = friendlyError(failure, "An edited post couldn’t be refreshed."))
                }
            } finally {
                if (edits[key] == coroutineContext[Job]) edits.remove(key)
            }
        }
    }

    private suspend fun replacePost(chatId: Long, id: Long, date: Long, content: MessageContent) {
        val posts = PostExtractor.extract(chatId, id, date, content)
        library.withTransaction {
            dao.removePost(chatId, id)
            if (posts.isNotEmpty()) dao.savePosts(posts)
        }
    }

    private fun friendlyError(failure: Exception, fallback: String): String = when ((failure as? TelegramError)?.code) {
        408 -> "Telegram took too long to respond. Check your connection and retry."
        420, 429 -> {
            val wait = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE)
                .find(failure.message.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                ?.coerceIn(1, 86_400) ?: 60L
            blockedUntil = Instant.now().epochSecond + wait
            "Telegram is limiting requests. Try again in ${wait}s."
        }
        else -> fallback
    }
}

private data class TelegramApp(val id: Int, val hash: String)

private fun readCredentials(id: String, hash: String): TelegramApp? {
    val appId = id.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    val appHash = hash.trim().takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) } ?: return null
    return TelegramApp(appId, appHash)
}
