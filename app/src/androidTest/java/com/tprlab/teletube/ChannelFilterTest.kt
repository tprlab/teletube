package com.tprlab.teletube

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelFilterTest {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test(timeout = 30_000)
    fun combinesChannelAndViewedFiltersWhileSyncIsActive() {
        val channels = listOf("Alpha", "Beta", "Gamma").mapIndexed { index, title ->
            ChannelRecord(index.toLong() + 1, title, available = true, archived = false)
        }
        val entries = channels.map { channel ->
            FeedEntry(
                channelId = channel.id,
                messageId = channel.id,
                videoId = "id-${channel.id}",
                postedAt = 100,
                url = "https://youtu.be/dQw4w9WgXcQ",
                title = "${channel.title} video",
                channelTitle = channel.title,
                viewed = channel.title == "Beta",
            )
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    TeleTubeTheme {
                        var selected by remember { mutableStateOf(emptyList<Long>()) }
                        var editing by remember { mutableStateOf(false) }
                        var showViewed by remember { mutableStateOf(true) }
                        if (editing) {
                            ChannelFilter(Modifier.fillMaxSize(), channels, selected) {
                                selected = it
                                editing = false
                            }
                        } else {
                            Feed(
                                modifier = Modifier.fillMaxSize(),
                                entries = entries,
                                filters = channels.filter { it.id in selected },
                                sync = SyncStatus(active = true, detail = "Syncing channels"),
                                lastSynced = 0,
                                hasSources = true,
                                showViewed = showViewed,
                                onToggleViewed = { showViewed = !showViewed },
                                onFilter = { editing = true },
                                onSources = null,
                                onReviewHidden = null,
                                onRefresh = null,
                                onPlay = {},
                                onPost = {},
                                onHideChannel = {},
                                onHideVideo = {},
                            )
                        }
                    }
                }
            }

            assertNotNull(awaitText("Gamma video"))
            tap("All channels")
            tap("Alpha")
            tap("Beta")
            tap("Apply filter (2)")
            assertNotNull(awaitText("2 channels"))
            assertNotNull(awaitText("Alpha video"))
            assertNotNull(awaitText("Beta video"))
            assertNull(findText("Gamma video"))
            assertNotNull(findText("Syncing channels"))

            assertNull(findText("Show viewed"))
            tap("Show viewed", matchDescription = true)
            awaitMissingText("Beta video")
            assertNotNull(awaitText("Alpha video"))
            assertNull(findText("Gamma video"))

            tap("2 channels")
            tap("All channels")
            tap("Show all channels")
            assertNotNull(awaitText("Gamma video"))
            assertNull(findText("Beta video"))
            tap("Show viewed", matchDescription = true)
            assertNotNull(awaitText("Beta video"))
        } finally {
            scenario.close()
        }
    }

    private fun findText(
        text: String,
        node: AccessibilityNodeInfo? = automation.rootInActiveWindow,
        matchDescription: Boolean = false,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = if (matchDescription) node.contentDescription else node.text
        if (label?.toString() == text) return node
        for (index in 0 until node.childCount) {
            findText(text, node.getChild(index), matchDescription)?.let { return it }
        }
        return null
    }

    private fun awaitText(text: String, matchDescription: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            findText(text, matchDescription = matchDescription)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        val labels = automation.rootInActiveWindow?.let(::labels).orEmpty()
        error("Couldn’t find \"$text\" on screen. Visible labels: ${labels.joinToString()}")
    }

    private fun labels(node: AccessibilityNodeInfo): List<String> = buildList {
        node.text?.toString()?.let(::add)
        node.contentDescription?.toString()?.let(::add)
        for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(labels(it)) }
    }

    private fun awaitMissingText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            if (findText(text) == null) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("\"$text\" was still visible.")
    }

    private fun tap(text: String, matchDescription: Boolean = false) {
        var target: AccessibilityNodeInfo? = awaitText(text, matchDescription)
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Couldn’t tap \"$text\".", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
