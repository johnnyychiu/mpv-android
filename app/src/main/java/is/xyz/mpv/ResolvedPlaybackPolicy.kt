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
        headers: Map<String, String>
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
        val safeTitle = title.orEmpty().take(1024).map {
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
        // MPVLib.command uses JNI GetStringUTFChars (modified UTF-8), including 6-byte
        // surrogate pairs. mpv's length-prefixed option values count those bytes.
        val byteLength = value.sumOf {
            when (it.code) {
                0 -> 2
                in 1..127 -> 1
                in 128..2047 -> 2
                else -> 3
            }
        }
        return "%$byteLength%$value"
    }
}
