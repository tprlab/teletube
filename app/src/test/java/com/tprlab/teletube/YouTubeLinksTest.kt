package com.tprlab.teletube

import org.drinkless.tdlib.TdApi.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test
    fun recognizesVideoFormats() {
        for (url in listOf(
            "www.youtube.com/watch?v=$id",
            "https://youtu.be/$id",
            "https://m.youtube.com/shorts/$id",
            "https://youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id",
        )) {
            assertEquals(YouTubeLink(id, "https://www.youtube.com/watch?v=$id"), YouTubeLinks.parse(url))
        }
    }

    @Test
    fun rejectsLookalikeDomainsAndNonVideoLinks() {
        for (url in listOf(
            "https://youtube.com.example.net/watch?v=$id",
            "https://example.net/?next=https://youtu.be/$id",
            "https://evil.example@youtube.com/watch?v=$id",
            "https://youtube.com/playlist?list=$id",
            "https://youtu.be/too-short",
        )) {
            assertNull(YouTubeLinks.parse(url))
        }
    }

    @Test
    fun findsHiddenCaptionLinksAndKeepsTimestampsWithoutDuplicates() {
        val text = "🎬 Watch this (https://youtu.be/$id?t=42)."
        val hidden = TextEntity(text.indexOf("Watch"), "Watch this".length, TextEntityTypeTextUrl("youtube.com/shorts/$id"))
        val message = MessagePhoto().apply { caption = FormattedText(text, arrayOf(hidden)) }

        val post = PostExtractor.extract(3, 8, 12, message).single()
        assertEquals("Watch this", post.title)
        assertEquals("https://www.youtube.com/watch?v=$id&t=42", post.url)
        assertEquals(3L, post.channelId)
        assertEquals(8L, post.messageId)
        assertEquals(12L, post.postedAt)
    }

    @Test
    fun extractsMultipleLinksAndUsesTheMatchingPreviewTitle() {
        val otherId = "aqz-KE-bpKQ"
        val url = "https://youtube.com/watch?v=$id"
        val preview = LinkPreview().apply {
            this.url = url
            title = "Preview title"
        }
        val post = MessageText(FormattedText("Two videos: $url and youtu.be/$otherId", emptyArray()), preview, null)
        val videos = PostExtractor.extract(3, 8, 12, post)
        assertEquals(listOf(id, otherId), videos.map { it.videoId })
        assertEquals("Preview title", videos.first().title)
    }
}
