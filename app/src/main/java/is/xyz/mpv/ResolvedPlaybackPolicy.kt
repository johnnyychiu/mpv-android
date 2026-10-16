package `is`.xyz.mpv

import java.net.URI
import java.util.Locale

/** The only extra mpv options accepted for a resolved YouTube stream. */
internal object ResolvedPlaybackPolicy {
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun options(
        videoUrl: String,
        audioUrl: String?,
        title: String?,
        headers: Map<String, String>,
        httpChunkSize: Long? = null
    ): String {
        requireMediaUrl(videoUrl)
        audioUrl?.let(::requireMediaUrl)
        require(headers.size <= 32) { "Too many HTTP headers" }
        val headerFields = headers.map { (name, value) ->
            require(name.length <= 128 && headerName.matches(name)) { "Invalid HTTP header name" }
            require(value.length <= 8192 && value.all { it.code in 32..126 && it != '\\' }) {
                "Invalid HTTP header value"
            }
            // mpv's string-list parser treats an escaped comma as literal text.
            "$name: $value".replace(",", "\\,")
        }.joinToString(",")
        val titleText = title.orEmpty()
        var titleEnd = minOf(titleText.length, 1024)
        if (titleEnd > 0 && titleEnd < titleText.length &&
            Character.isSurrogatePair(titleText[titleEnd - 1], titleText[titleEnd])) {
            titleEnd--
        }
        val safeTitle = titleText.take(titleEnd).map {
            if (it.isISOControl()) ' ' else it
        }.joinToString("")

        val options = linkedMapOf(
            "ytdl" to "no",
            "pause" to "no",
            "force-media-title" to safeTitle,
            "http-header-fields" to headerFields,
            // Override optional user config for this file; mpv restores it afterwards.
            "audio-files" to ""
        )
        if (httpChunkSize != null) {
            require(httpChunkSize in 1024L..(64L * 1024 * 1024)) { "Invalid HTTP request size" }
            // yt-dlp marks YouTube streams that require bounded Range requests.
            // Match mpv's own ytdl_hook for both native curl and libavformat paths.
            options["curl-max-request-size"] = httpChunkSize.toString()
            options["stream-lavf-o"] = "request_size=$httpChunkSize"
        }
        if (audioUrl != null) {
            // This suffix accepts a single URL without interpreting ':' as a list separator.
            options["audio-files-append"] = audioUrl
            options["aid"] = "auto"
        }
        return options.entries.joinToString(",") { (name, value) -> "$name=${quote(value)}" }
    }

    private fun requireMediaUrl(value: String) {
        require(value.length <= 32768 && value.none { it.isISOControl() || it == '\\' }) {
            "Invalid media URL"
        }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid media URL")
        }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) {
            "Expected a YouTube HTTPS media URL"
        }
    }

    private fun quote(value: String): String {
        // Android's GetStringUTFChars uses 4 bytes for a valid surrogate pair,
        // unlike Java modified UTF-8. This is already true in Android 7's ART
        // runtime/utf.cc. Unpaired surrogates still occupy 3 bytes, and NUL 2.
        // mpv's %length% syntax counts these native bytes, not UTF-16 code units.
        var byteLength = 0
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            byteLength += when {
                index < value.length && Character.isSurrogatePair(character, value[index]) -> {
                    index++
                    4
                }
                character.code == 0 -> 2
                character.code <= 127 -> 1
                character.code <= 2047 -> 2
                else -> 3
            }
        }
        return "%$byteLength%$value"
    }
}
