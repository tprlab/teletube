package com.tprlab.teletube

import org.drinkless.tdlib.TdApi.*

internal object PostExtractor {
    fun extract(channelId: Long, messageId: Long, date: Long, content: MessageContent): List<VideoPost> {
        val formatted = when (content) {
            is MessageText -> content.text
            is MessageAnimation -> content.caption
            is MessageAudio -> content.caption
            is MessageDocument -> content.caption
            is MessagePhoto -> content.caption
            is MessageVideo -> content.caption
            is MessageVoiceNote -> content.caption
            is MessagePaidMedia -> content.caption
            else -> null
        } ?: return emptyList()

        val text = formatted.text.orEmpty()
        val links = linkedMapOf<String, Pair<YouTubeLink, String>>()

        fun add(url: String, label: String = "") {
            val link = YouTubeLinks.parse(url) ?: return
            val previous = links[link.videoId]
            if (previous == null) {
                links[link.videoId] = link to label
            } else {
                val playback = if ("&t=" in link.url && "&t=" !in previous.first.url) link else previous.first
                links[link.videoId] = playback to previous.second.ifBlank { label }
            }
        }

        for (entity in formatted.entities.orEmpty()) {
            val end = entity.offset.toLong() + entity.length
            if (entity.offset < 0 || entity.length <= 0 || end > text.length) continue
            val caption = text.substring(entity.offset, end.toInt())
            when (val type = entity.type) {
                is TextEntityTypeUrl -> add(caption)
                is TextEntityTypeTextUrl -> add(type.url, caption.takeUnless { YouTubeLinks.parse(it) != null }.orEmpty())
            }
        }

        val visibleUrls = YouTubeLinks.inText(text).toList()
        visibleUrls.forEach { add(it.value) }
        val preview = (content as? MessageText)?.linkPreview
        val previewVideo = preview?.url?.let(YouTubeLinks::parse)?.videoId
        val excerpt = visibleUrls.foldRight(text) { match, remaining -> remaining.removeRange(match.range) }
            .replace(Regex("\\s+"), " ").trim(' ', ':', '-', '|', '•')
        return links.values.map { (link, label) ->
            val title = when {
                link.videoId == previewVideo && !preview?.title.isNullOrBlank() -> preview?.title.orEmpty()
                label.isNotBlank() -> label
                excerpt.isNotBlank() -> excerpt
                else -> "YouTube video"
            }
            VideoPost(channelId, messageId, link.videoId, date, link.url, title.take(160))
        }
    }
}
