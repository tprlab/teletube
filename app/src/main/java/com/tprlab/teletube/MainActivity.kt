package com.tprlab.teletube

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi.AuthenticationCodeTypeFragment
import org.drinkless.tdlib.TdApi.AuthenticationCodeTypeSms
import org.drinkless.tdlib.TdApi.AuthenticationCodeTypeSmsPhrase
import org.drinkless.tdlib.TdApi.AuthenticationCodeTypeSmsWord
import org.drinkless.tdlib.TdApi.AuthenticationCodeTypeTelegramMessage
import org.drinkless.tdlib.TdApi.AuthorizationState
import org.drinkless.tdlib.TdApi.AuthorizationStateClosed
import org.drinkless.tdlib.TdApi.AuthorizationStateClosing
import org.drinkless.tdlib.TdApi.AuthorizationStateLoggingOut
import org.drinkless.tdlib.TdApi.AuthorizationStateReady
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitCode
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitEmailAddress
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitEmailCode
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitOtherDeviceConfirmation
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitPassword
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitPhoneNumber
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitPremiumPurchase
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitRegistration
import org.drinkless.tdlib.TdApi.AuthorizationStateWaitTdlibParameters

class MainActivity : ComponentActivity() {
    private val repository get() = (application as TeleTubeApplication).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val telegramLink = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val hasTelegram = packageManager.resolveActivity(telegramLink, PackageManager.MATCH_DEFAULT_ONLY) != null
        setContent {
            TeleTubeTheme {
                if (hasTelegram) {
                    TeleTube(repository)
                } else {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AlertDialog(
                            onDismissRequest = {},
                            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                            title = { Text("Telegram isn’t installed") },
                            text = { Text("Install Telegram on this device, then reopen TeleTube to get started.") },
                            confirmButton = {
                                TextButton(onClick = ::finishAndRemoveTask) { Text("OK") }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeleTube(repository: TelegramRepository) {
    val authorization by repository.authorization.collectAsStateWithLifecycle()
    val config by repository.scanScope.collectAsStateWithLifecycle()
    val channels by repository.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val browseChannels by repository.browseChannels.collectAsStateWithLifecycle()
    val hiddenChannels by repository.hiddenChannels.collectAsStateWithLifecycle(initialValue = emptyList())
    val allEntries by repository.feed.collectAsStateWithLifecycle()
    val progress by repository.progress.collectAsStateWithLifecycle(initialValue = emptyList())
    val sync by repository.sync.collectAsStateWithLifecycle()
    val busy by repository.authBusy.collectAsStateWithLifecycle()
    val authError by repository.authError.collectAsStateWithLifecycle()
    val startupError by repository.startupError.collectAsStateWithLifecycle()
    val needsCredentials by repository.needsCredentials.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("feed") }
    var channelFilterIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var showViewed by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val signedIn = authorization is AuthorizationStateReady
    val restoringSession = config != null && !needsCredentials && when (authorization) {
        null, is AuthorizationStateWaitTdlibParameters, is AuthorizationStateClosing, is AuthorizationStateClosed -> true
        else -> false
    }
    val canBrowse = signedIn || restoringSession
    val choosingSources = signedIn && (config == null || screen == "sources")
    val filtering = canBrowse && screen == "filter" && !choosingSources
    val reviewingHidden = canBrowse && screen == "hidden" && !choosingSources
    val viewingHistory = canBrowse && screen == "history" && !choosingSources
    val inFeed = canBrowse && !choosingSources && !filtering && !reviewingHidden && !viewingHistory

    val activeFilters = remember(browseChannels, channelFilterIds) {
        val selected = channelFilterIds.toSet()
        browseChannels.filter { it.id in selected }
    }
    val onRefresh: (() -> Unit)? = when {
        signedIn -> ({ repository.refresh() })
        restoringSession && startupError != null -> repository::retryConnection
        else -> null
    }

    fun setChannelHidden(id: Long, hidden: Boolean) {
        scope.launch {
            try {
                repository.setChannelHidden(id, hidden)
                if (hidden) channelFilterIds = channelFilterIds - id
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                snackbars.showSnackbar(
                    if (hidden) "Couldn’t hide the channel. Try again." else "Couldn’t unhide the channel. Try again.",
                )
            }
        }
    }

    fun setVideoHidden(video: HiddenVideo, hidden: Boolean) {
        scope.launch {
            try {
                repository.setVideoHidden(video, hidden)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                snackbars.showSnackbar(
                    if (hidden) "Couldn’t hide the video. Try again." else "Couldn’t unhide the video. Try again.",
                )
            }
        }
    }

    fun playVideo(videoId: String, url: String, title: String, channelTitle: String) {
        val playedAt = System.currentTimeMillis()
        if (!openLink(context, url)) {
            scope.launch { snackbars.showSnackbar("No app can open that video.") }
            return
        }
        scope.launch {
            try {
                repository.recordPlayback(
                    Playback(videoId = videoId, url = url, title = title, channelTitle = channelTitle, playedAt = playedAt),
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                snackbars.showSnackbar("Couldn’t save the video to history.")
            }
        }
    }

    LaunchedEffect(authorization) {
        if (authorization is AuthorizationStateWaitPhoneNumber || authorization is AuthorizationStateLoggingOut) {
            screen = "feed"
            channelFilterIds = emptyList()
            showViewed = true
            confirmSignOut = false
        }
    }
    LaunchedEffect(authError, inFeed) {
        if (inFeed) authError?.let { snackbars.showSnackbar(it) }
    }

    val canGoBack = filtering || reviewingHidden || viewingHistory || (choosingSources && config != null)
    BackHandler(enabled = canGoBack) { screen = "feed" }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (inFeed) TeleTubeMark(34.dp)
                        Text(
                            when {
                                choosingSources -> "Scan channels"
                                filtering -> "Filter by channel"
                                reviewingHidden -> "Hidden"
                                viewingHistory -> "History"
                                else -> "TeleTube"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { screen = "feed" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (inFeed && onRefresh != null) {
                        IconButton(onClick = onRefresh, enabled = !sync.active) {
                            Icon(Icons.Default.Refresh, "Refresh feed")
                        }
                    }
                    if (inFeed || (signedIn && choosingSources)) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, "More options")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (inFeed && signedIn) {
                                    DropdownMenuItem(text = { Text("Scan channels") }, onClick = {
                                        menu = false
                                        screen = "sources"
                                    })
                                    if (sync.active) {
                                        DropdownMenuItem(text = { Text("Stop scan") }, onClick = {
                                            menu = false
                                            repository.stopScan()
                                        })
                                    }
                                }
                                if (inFeed) {
                                    DropdownMenuItem(text = { Text("History") }, onClick = {
                                        menu = false
                                        screen = "history"
                                    })
                                    DropdownMenuItem(text = { Text("Hidden") }, onClick = {
                                        menu = false
                                        screen = "hidden"
                                    })
                                }
                                if (signedIn) {
                                    DropdownMenuItem(text = { Text("Sign out") }, enabled = !busy, onClick = {
                                        menu = false
                                        confirmSignOut = true
                                    })
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val container = Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight()
            when {
                !canBrowse -> SignIn(
                    container, authorization, busy, authError, startupError, needsCredentials,
                    onSubmit = repository::submitAuth,
                    onResend = repository::resendCode,
                    onRetry = repository::retryConnection,
                    onConfigure = repository::configureApp,
                    onChangeCredentials = repository::changeCredentials,
                    onExternal = { openLink(context, it) },
                )
                choosingSources -> ChooseSources(
                    container, channels, config, sync,
                    onRetry = { repository.refresh() },
                    onSave = { selections ->
                        scope.launch {
                            try {
                                repository.saveScope(selections)
                                channelFilterIds = channelFilterIds.filter(selections::includes)
                                screen = "feed"
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                snackbars.showSnackbar("Couldn’t save the channel selection. Try again.")
                            }
                        }
                    },
                )
                filtering -> ChannelFilter(container, browseChannels, channelFilterIds) {
                    channelFilterIds = it
                    screen = "feed"
                }
                reviewingHidden -> {
                    val hiddenVideos by repository.hiddenVideos.collectAsStateWithLifecycle(initialValue = emptyList())
                    HiddenItems(
                        container, hiddenChannels, hiddenVideos,
                        onUnhideChannel = { setChannelHidden(it, false) },
                        onUnhideVideo = { setVideoHidden(it, false) },
                    )
                }
                viewingHistory -> {
                    val history: List<Playback>? by repository.history.collectAsStateWithLifecycle(initialValue = null)
                    PlaybackHistory(container, history) { playVideo(it.videoId, it.url, it.title, it.channelTitle) }
                }
                else -> {
                    val shownIds = remember(activeFilters, browseChannels) {
                        activeFilters.ifEmpty { browseChannels }.map { it.id }.toSet()
                    }
                    val lastSynced = progress.filter { it.channelId in shownIds }.maxOfOrNull { it.lastSynced } ?: 0L
                    Feed(
                        container, allEntries, activeFilters,
                        if (restoringSession) SyncStatus(
                            detail = if (startupError == null) "Connecting to Telegram" else null,
                            error = startupError,
                        ) else sync,
                        lastSynced,
                        hasSources = browseChannels.isNotEmpty(),
                        showViewed = showViewed,
                        onToggleViewed = { showViewed = !showViewed },
                        onFilter = { screen = "filter" },
                        onSources = if (signedIn) ({ screen = "sources" }) else null,
                        onReviewHidden = if (hiddenChannels.isNotEmpty()) ({ screen = "hidden" }) else null,
                        onRefresh = onRefresh,
                        onPlay = { playVideo(it.videoId, it.url, it.title, it.channelTitle) },
                        onPost = { entry ->
                            scope.launch {
                                try {
                                    if (!openLink(context, repository.postLink(entry))) {
                                        snackbars.showSnackbar("No app can open that Telegram post.")
                                    }
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    snackbars.showSnackbar("That Telegram post couldn’t be opened.")
                                }
                            }
                        },
                        onHideChannel = { setChannelHidden(it, true) },
                        onHideVideo = { setVideoHidden(HiddenVideo(it.videoId, it.title), true) },
                    )
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of TeleTube?") },
            text = { Text("This clears the links and channel settings saved on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    repository.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignIn(
    modifier: Modifier,
    state: AuthorizationState?,
    busy: Boolean,
    authError: String?,
    startupError: String?,
    needsCredentials: Boolean,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onRetry: () -> Unit,
    onConfigure: (String, String) -> Unit,
    onChangeCredentials: () -> Unit,
    onExternal: (String) -> Unit,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TeleTubeMark(72.dp)
        when {
            needsCredentials -> {
                ConfigureTelegram(authError, onConfigure, onExternal)
            }
            startupError != null -> {
                SectionTitle("Couldn’t connect")
                Text(startupError, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onChangeCredentials) { Text("Change API credentials") }
            }
            state is AuthorizationStateWaitPhoneNumber -> {
                AuthForm(
                    state, "Connect Telegram", "Sign in to scan your Telegram channels for YouTube links.",
                    "Phone number", "+1 555 123 4567", KeyboardType.Phone, "Continue", busy, authError, onSubmit,
                )
                TextButton(onClick = onChangeCredentials, enabled = !busy) { Text("Change API credentials") }
            }
            state is AuthorizationStateWaitCode -> {
                val code = state.codeInfo
                val description = when (code.type) {
                    is AuthenticationCodeTypeTelegramMessage -> "Open Telegram and look for the sign-in code."
                    is AuthenticationCodeTypeSms, is AuthenticationCodeTypeSmsPhrase, is AuthenticationCodeTypeSmsWord ->
                        "Look for a text message sent to ${code.phoneNumber}."
                    is AuthenticationCodeTypeFragment -> "Open Fragment to retrieve your verification code."
                    else -> "Enter the code Telegram sent for ${code.phoneNumber}."
                }
                AuthForm(
                    state, "Enter your code", description, "Verification code", null,
                    KeyboardType.Text, "Verify", busy, authError, onSubmit,
                )
                val fragment = code.type as? AuthenticationCodeTypeFragment
                if (fragment != null) TextButton(onClick = { onExternal(fragment.url) }) { Text("Open Fragment") }
                if (code.nextType != null) TextButton(onClick = onResend, enabled = !busy) { Text("Resend code") }
            }
            state is AuthorizationStateWaitEmailAddress -> AuthForm(
                state, "Verify your email", "Telegram requires an email address to continue.",
                "Email address", null, KeyboardType.Email, "Send code", busy, authError, onSubmit,
            )
            state is AuthorizationStateWaitEmailCode -> AuthForm(
                state, "Check your email", "Telegram sent a code to ${state.codeInfo.emailAddressPattern}.",
                "Email code", null, KeyboardType.Number, "Verify", busy, authError, onSubmit,
            )
            state is AuthorizationStateWaitPassword -> AuthForm(
                state, "Two-step verification", "Enter the two-step verification password for your Telegram account.",
                "Password", null, KeyboardType.Password, "Sign in", busy, authError, onSubmit,
            )
            state is AuthorizationStateWaitRegistration || state is AuthorizationStateWaitPremiumPurchase ||
                state is AuthorizationStateWaitOtherDeviceConfirmation -> {
                SectionTitle("Continue in Telegram")
                Text("Finish setting up your account in Telegram, then return to TeleTube.")
                Button(onClick = onRetry, enabled = !busy) { Text("Restart sign-in") }
                authError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            else -> {
                SectionTitle("Connecting to Telegram")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (!needsCredentials && (state is AuthorizationStateWaitCode || state is AuthorizationStateWaitEmailAddress ||
            state is AuthorizationStateWaitEmailCode || state is AuthorizationStateWaitPassword)
        ) {
            TextButton(onClick = onRetry, enabled = !busy) { Text("Restart sign-in") }
        }
    }
}

@Composable
private fun ConfigureTelegram(error: String?, onSave: (String, String) -> Unit, onExternal: (String) -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    SectionTitle("Set up Telegram")
    Text(
        "Create an app under API development tools at my.telegram.org, then enter its API credentials here.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { onExternal("https://my.telegram.org/apps") }) { Text("Open my.telegram.org") }
    OutlinedTextField(
        value = id, onValueChange = { id = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("API ID") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    OutlinedTextField(
        value = hash, onValueChange = { hash = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("API hash") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = { onSave(id, hash) }, enabled = id.isNotBlank() && hash.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Continue to sign-in") }
}

@Composable
private fun AuthForm(
    state: AuthorizationState,
    title: String,
    description: String,
    label: String,
    placeholder: String?,
    keyboard: KeyboardType,
    action: String,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var input by remember(state.constructor) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val valid = input.isNotBlank() && !busy
    val submit = {
        if (valid) {
            focus.clearFocus()
            onSubmit(input)
        }
    }
    SectionTitle(title)
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = !busy,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        visualTransformation = if (keyboard == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    Button(onClick = submit, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (busy) "Please wait…" else action)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

private fun openLink(context: android.content.Context, url: String): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" && uri.scheme != "tg") return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
