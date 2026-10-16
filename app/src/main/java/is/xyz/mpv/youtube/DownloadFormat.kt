package `is`.xyz.mpv.youtube

import java.net.URI
import java.net.URLDecoder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class DownloadFormat(val extension: String, val mimeType: String) {
    MP4("mp4", "video/mp4"),
    MP3("mp3", "audio/mpeg")
}

/** Kept independent of Android so boundary checks can be exercised on the JVM. */
internal object YouTubeMediaPolicy {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val shortHosts = setOf("youtu.be", "www.youtu.be")
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun uploadsUrl(channelId: String): String {
        require(channelId.matches(Regex("UC[A-Za-z0-9_-]{22}"))) { "Use a valid YouTube channel ID." }
        return "https://www.youtube.com/playlist?list=UU${channelId.drop(2)}"
    }

    /** yt-dlp timestamps are seconds; UI/cache timestamps are milliseconds. Unknown dates stay unknown. */
    fun uploadTime(timestamp: Double?, releaseTimestamp: Double?, uploadDate: String?): Long {
        listOf(timestamp, releaseTimestamp).firstOrNull {
            it != null && it.isFinite() && it > 0 && it <= 253402300799.0
        }?.let { return (it * 1000).toLong() }
        if (uploadDate == null || !uploadDate.matches(Regex("[0-9]{8}"))) return 0
        val format = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        return format.parse(uploadDate, position)?.takeIf { position.index == 8 && it.time > 0 }?.time ?: 0
    }

    fun videoUrl(input: String): String {
        val text = input.trim()
        require(text.isNotEmpty() && text.length <= 4096 && !hasUnsafeCharacters(text)) {
            "Enter a YouTube video link."
        }
        val uri = parseUri(if (text.contains("://")) text else "https://$text")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require((scheme == "https" || scheme == "http") && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == if (scheme == "https") 443 else 80)) {
            "Use an ordinary https:// YouTube video link."
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val id = when {
            host in shortHosts -> path.removePrefix("/")
            host in youtubeHosts && path == "/watch" -> {
                val ids = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                    val pair = part.split('=', limit = 2)
                    if (decode(pair[0]) == "v") decode(pair.getOrElse(1) { "" }) else null
                }
                require(ids.size == 1) { "The YouTube link must identify one video." }
                ids.single()
            }
            host in youtubeHosts -> {
                val parts = path.split('/')
                require(parts.size == 3 && parts[1] in setOf("shorts", "live", "embed")) {
                    "Open a YouTube video link; channel and playlist links cannot be played or downloaded."
                }
                parts[2]
            }
            else -> throw IllegalArgumentException("Only youtube.com and youtu.be video links are supported.")
        }
        require(videoId.matches(id)) { "The YouTube link does not contain a valid video ID." }
        return "https://www.youtube.com/watch?v=$id"
    }

    fun streamUrl(input: String): String {
        require(input.length <= 32768 && !hasUnsafeCharacters(input)) { "YouTube returned an invalid stream URL." }
        val uri = parseUri(input)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) {
            "YouTube returned an unsupported stream. Update the YouTube tools and try again."
        }
        return input
    }

    fun checkedHeaders(headers: Map<String, String>): Map<String, String> {
        require(headers.size <= 32) { "YouTube returned too many stream headers." }
        return headers.map { (name, value) ->
            require(name.length <= 128 && headerName.matches(name) && value.length <= 8192 &&
                value.none { it.code < 32 || it.code == 127 || it == '\\' }) {
                "YouTube returned an unsupported stream header."
            }
            name to value
        }.toMap()
    }

    fun processId(input: String): String {
        require(input.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid YouTube operation ID." }
        return input
    }

    private fun hasUnsafeCharacters(value: String) = value.any {
        it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\'
    }

    private fun parseUri(value: String): URI = try {
        URI(value)
    } catch (error: Exception) {
        throw IllegalArgumentException("The YouTube link is not a valid URL.", error)
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (error: Exception) {
        throw IllegalArgumentException("The YouTube link is not a valid URL.", error)
    }
}
