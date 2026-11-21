package com.tprlab.teletube

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi.GetChatHistory
import org.drinkless.tdlib.TdApi.Message
import java.time.Instant
import java.time.temporal.ChronoUnit

internal class ChannelScanner(private val telegram: TelegramClient, private val library: Library) {
    private val dao = library.dao()

    suspend fun scanRecent(channelId: Long) {
        val state = dao.progressFor(channelId) ?: ScanProgress(channelId)
        val resumed = state.pendingCursor != 0L
        val complete = scanWindow(state)
        if (resumed) scanWindow(complete)
    }

    private suspend fun scanWindow(start: ScanProgress): ScanProgress {
        val channelId = start.channelId
        var state = start
        // A fixed cutoff and working cursor let an interrupted first scan resume.
        if (state.newest == 0L && state.initialCutoff == null && !state.historyComplete) {
            state = state.copy(initialCutoff = Instant.now().minus(30, ChronoUnit.DAYS).epochSecond)
            dao.saveProgress(state)
        }

        do {
            val previous = state
            state = telegram.apply(GetChatHistory(channelId, previous.pendingCursor, 0, 100, false)) { result ->
                val page = olderThan(result.messages, previous.pendingCursor)
                val cutoff = previous.initialCutoff
                val accepted = page.takeWhile { message ->
                    if (cutoff != null) message.date.toLong() >= cutoff
                    else message.id > previous.newest
                }
                val done = page.isEmpty() || accepted.size < page.size
                val head = previous.pendingHead.takeIf { it != 0L }
                    ?: page.firstOrNull()?.id ?: previous.newest
                val oldestImported = accepted.lastOrNull()?.id
                val next = if (done) {
                    previous.copy(
                        newest = maxOf(previous.newest, head),
                        pendingHead = 0,
                        pendingCursor = 0,
                        initialCutoff = null,
                        olderCursor = if (cutoff != null) oldestImported ?: previous.olderCursor else previous.olderCursor,
                        historyComplete = previous.historyComplete || (cutoff != null && page.isEmpty()),
                        lastSynced = Instant.now().epochSecond,
                    )
                } else {
                    previous.copy(
                        pendingHead = head,
                        pendingCursor = checkNotNull(oldestImported),
                        olderCursor = if (cutoff != null) oldestImported else previous.olderCursor,
                    )
                }
                savePage(next, accepted)
                next
            }
        } while (state.pendingCursor != 0L)
        return state
    }

    private suspend fun savePage(next: ScanProgress, messages: List<Message>) {
        val posts = withContext(Dispatchers.Default) {
            messages.flatMap { message ->
                PostExtractor.extract(message.chatId, message.id, message.date.toLong(), message.content)
            }
        }
        library.withTransaction {
            if (messages.isNotEmpty()) dao.removePosts(next.channelId, messages.map { it.id })
            if (posts.isNotEmpty()) dao.savePosts(posts)
            dao.saveProgress(next)
        }
    }

    private fun olderThan(messages: Array<Message>, cursor: Long): List<Message> =
        messages.filter { cursor == 0L || it.id < cursor }
}
