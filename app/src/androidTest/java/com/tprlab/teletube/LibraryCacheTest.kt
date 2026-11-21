package com.tprlab.teletube

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryCacheTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test(timeout = 20_000)
    fun hidingAVideoSurvivesUpgradeAndRescanAndCanBeUndone() = runBlocking {
        val name = "library-hidden-video-${UUID.randomUUID()}.db"
        val id = "dQw4w9WgXcQ"
        try {
            createFixture(name, 3)
            withLibrary(name) { library ->
                val dao = library.dao()
                assertEquals(listOf(20L, 10L), dao.feed().first().map { it.messageId })
                assertEquals(emptyList<HiddenVideo>(), dao.hiddenVideos().first())
                dao.hideVideo(id, "First")
                dao.hideVideo(id, "Duplicate")
                dao.savePosts(listOf(VideoPost(2, 30, id, 300, "https://youtu.be/$id", "Reposted")))

                assertEquals(listOf(20L), dao.feed().first().map { it.messageId })
                assertEquals(listOf(1L, 2L), dao.browseChannels().first().map { it.id })
                assertEquals(listOf(id), dao.history().first().map { it.videoId })
            }
            withLibrary(name) { library ->
                val dao = library.dao()
                assertEquals(listOf(HiddenVideo(id, "First")), dao.hiddenVideos().first())
                assertEquals(listOf(20L), dao.feed().first().map { it.messageId })
                dao.unhideVideo(id)
                assertEquals(emptyList<HiddenVideo>(), dao.hiddenVideos().first())
                assertEquals(listOf(30L, 20L, 10L), dao.feed().first().map { it.messageId })
                assertEquals(listOf(true, false, true), dao.feed().first().map { it.viewed })
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 20_000)
    fun playbackHistorySurvivesUpgradeAndMarksEveryPostForTheSameVideo() = runBlocking {
        val name = "library-history-${UUID.randomUUID()}.db"
        val first = Playback(
            videoId = "dQw4w9WgXcQ", url = "https://youtu.be/dQw4w9WgXcQ",
            title = "First", channelTitle = "First channel", playedAt = 10_000,
        )
        try {
            createFixture(name, 1)
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { versionTwo ->
                versionTwo.execSQL("CREATE TABLE HiddenChannel (channelId INTEGER NOT NULL PRIMARY KEY)")
                versionTwo.execSQL(
                    "UPDATE room_master_table SET identity_hash = '5b3f3c33b5024d95975d52994d264651' WHERE id = 42",
                )
                versionTwo.version = 2
            }
            withLibrary(name) { library ->
                val dao = library.dao()
                assertEquals(listOf(false, false), dao.feed().first().map { it.viewed })
                assertEquals(emptyList<Playback>(), dao.history().first())
                dao.savePosts(listOf(VideoPost(2, 30, first.videoId, 300, first.url, "Same video")))
                dao.savePlayback(first.copy(title = "Replay", playedAt = 30_000))
                dao.savePlayback(first)
                dao.savePlayback(first.copy(title = "Latest replay", playedAt = 30_000))
                assertEquals(listOf(true, false, true), dao.feed().first().map { it.viewed })
                dao.hideChannel(1)
                dao.removePost(1, 10)
            }
            withLibrary(name) { library ->
                val dao = library.dao()
                assertEquals(
                    listOf("Latest replay", "Replay", "First"),
                    dao.history().first().map { it.title },
                )
                assertEquals(listOf(true, false), dao.feed().first().map { it.viewed })
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 20_000)
    fun hiddenChannelsSurviveUpgradeAndReopenWithoutChangingScanSources() = runBlocking {
        val name = "library-upgrade-${UUID.randomUUID()}.db"
        try {
            createFixture(name, 1)

            withLibrary(name) { library ->
                val dao = library.dao()
                assertEquals(listOf(20L, 10L), dao.feed().first().map { it.messageId })
                assertEquals(emptyList<ChannelRecord>(), dao.hiddenChannels().first())
                assertEquals(10L, dao.progressFor(1)?.newest)
                dao.hideChannel(1)
                dao.hideChannel(1)
                dao.savePosts(listOf(VideoPost(1, 30, "abcdefghijk", 300, "https://youtu.be/abcdefghijk", "New")))
                assertEquals(listOf(1L, 2L), dao.channels().first().map { it.id })
                assertEquals(listOf(1L, 2L), dao.selected().first().sorted())
                assertEquals(listOf(2L), dao.browseChannels().first().map { it.id })
                assertEquals(listOf(20L), dao.feed().first().map { it.messageId })
            }

            withLibrary(name) { library ->
                val dao = library.dao()
                assertEquals(listOf("First channel"), dao.hiddenChannels().first().map { it.title })
                assertEquals(listOf(20L), dao.feed().first().map { it.messageId })
                dao.unhideChannel(1)
                assertEquals(emptyList<ChannelRecord>(), dao.hiddenChannels().first())
                assertEquals(listOf(1L, 2L), dao.browseChannels().first().map { it.id })
                assertEquals(listOf(30L, 20L, 10L), dao.feed().first().map { it.messageId })
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 20_000)
    fun cachedFeedAndChannelChoicesSurviveReopenAndStayReadableDuringScan() = runBlocking {
        val name = "library-cache-${UUID.randomUUID()}.db"
        val firstPost = VideoPost(1, 10, "video-one", 100, "https://youtu.be/video-one", "First")
        val secondPost = VideoPost(2, 20, "video-two", 200, "https://youtu.be/video-two", "Second")
        val scannedPost = VideoPost(1, 30, "video-three", 300, "https://youtu.be/video-three", "New")

        try {
            withLibrary(name) { library ->
                val dao = library.dao()
                library.withTransaction {
                    dao.saveSettings(ScanSettings(allChannels = false))
                    dao.saveSelection(listOf(SelectedChannel(1), SelectedChannel(2)))
                    dao.saveChannel(ChannelRecord(1, "First channel", available = true, archived = false))
                    dao.saveChannel(ChannelRecord(2, "Second channel", available = true, archived = true))
                    dao.savePosts(listOf(firstPost, secondPost))
                    dao.saveProgress(ScanProgress(1, newest = 10, lastSynced = 150))
                }
            }

            withLibrary(name) { library ->
                val dao = library.dao()
                assertEquals(listOf(20L, 10L), dao.feed().first().map { it.messageId })
                assertEquals(listOf(1L, 2L), dao.browseChannels().first().map { it.id })
                assertEquals(10L, dao.progressFor(1)?.newest)

                // Catalog updates can make a channel unavailable for another scan.
                dao.saveChannel(ChannelRecord(2, "Second channel", available = false, archived = true))
                assertEquals(listOf(1L), dao.channels().first().map { it.id })
                assertEquals(listOf(1L, 2L), dao.browseChannels().first().map { it.id })

                val feed = MutableStateFlow<List<FeedEntry>>(emptyList())
                val observer = launch { dao.feed().collect { feed.value = it } }
                try {
                    val cached = withTimeout(5_000) { feed.first { it.isNotEmpty() } }
                    val writing = CompletableDeferred<Unit>()
                    val finishPage = CompletableDeferred<Unit>()
                    val scan = async(Dispatchers.IO) {
                        library.withTransaction {
                            dao.savePosts(listOf(scannedPost))
                            dao.saveProgress(ScanProgress(1, newest = 30, lastSynced = 350))
                            writing.complete(Unit)
                            finishPage.await()
                        }
                    }
                    try {
                        withTimeout(5_000) { writing.await() }
                        assertEquals(listOf(20L, 10L), cached.map { it.messageId })
                        assertEquals(listOf(10L), feed.value.filter { it.channelId == 1L }.map { it.messageId })
                        assertEquals(listOf(20L), feed.value.filter { it.channelId == 2L }.map { it.messageId })
                        val count = withTimeout(5_000) {
                            withContext(Dispatchers.IO) {
                                library.query("SELECT COUNT(*) FROM VideoPost", null).use { cursor ->
                                    cursor.moveToFirst()
                                    cursor.getInt(0)
                                }
                            }
                        }
                        assertEquals(2, count)
                    } finally {
                        finishPage.complete(Unit)
                        scan.await()
                    }
                    assertEquals(
                        listOf(30L, 20L, 10L),
                        withTimeout(5_000) { feed.first { it.size == 3 } }.map { it.messageId },
                    )
                } finally {
                    observer.cancelAndJoin()
                }

                library.withTransaction {
                    dao.clearSelection()
                    dao.saveSelection(listOf(SelectedChannel(2)))
                }
                assertEquals(listOf(2L), dao.browseChannels().first().map { it.id })
                assertEquals(listOf(20L), dao.feed().first().map { it.messageId })
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createFixture(name: String, version: Int) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            instrumentation.context.assets.open("library-v$version.sql").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { database.execSQL(it.trimEnd(';')) }
            }
            database.version = version
        }
    }

    private suspend fun withLibrary(name: String, block: suspend (Library) -> Unit) {
        val library = Library.open(context, name)
        try {
            block(library)
        } finally {
            library.close()
        }
    }
}
