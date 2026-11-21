package com.tprlab.teletube

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal data class YouTubeLink(val videoId: String, val url: String)

internal object YouTubeLinks {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val urlInText = Regex("""(?i)(?<![\w./@-])(?:https?://[^\s<>"']+|(?:[\w-]+\.)*(?:youtube\.com|youtu\.be)/[^\s<>"']+)""")
    private val timestamp = Regex("""(?:\d+s?|(?:\d+h)?(?:\d+m)?\d+s|\d+h(?:\d+m)?|\d+m)""")
    private val videoHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val embedHosts = setOf("youtube-nocookie.com", "www.youtube-nocookie.com")

    fun inText(text: String): Sequence<MatchResult> = urlInText.findAll(text)

    fun parse(value: String): YouTubeLink? {
        val clean = value.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        val candidate = if ("://" in clean) clean else "https://$clean"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.userInfo != null || uri.port != -1
        ) return null

        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.path.orEmpty().trim('/').split('/')
        val parameters = query(uri.rawQuery)
        val id = when {
            host == "youtu.be" && path.size == 1 -> path[0]
            host in videoHosts && path == listOf("watch") -> parameters["v"]
            host in videoHosts && path.size == 2 && path[0] in setOf("shorts", "live", "embed") -> path[1]
            host in embedHosts && path.size == 2 && path[0] == "embed" -> path[1]
            else -> null
        }?.takeIf(videoId::matches) ?: return null

        val start = listOfNotNull(parameters["t"], parameters["start"], query(uri.rawFragment)["t"])
            .firstOrNull(timestamp::matches)
        val playback = "https://www.youtube.com/watch?v=$id" + if (start == null) "" else "&t=$start"
        return YouTubeLink(id, playback)
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { part ->
        val key = part.substringBefore('=', "")
        if (key.isEmpty()) return@mapNotNull null
        runCatching { key to URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()
    }.toMap()
}
