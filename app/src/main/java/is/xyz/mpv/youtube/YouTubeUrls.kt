package `is`.xyz.mpv.youtube

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Only public YouTube channel and individual-video links are accepted by this screen. */
object YouTubeUrls {
    data class ChannelLink(val url: String, val channelId: String?)

    private val channelHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private val videoHosts = channelHosts + "music.youtube.com"
    private val shortHosts = setOf("youtu.be", "www.youtu.be")
    private val channelIdPattern = Regex("UC[A-Za-z0-9_-]{22}")
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val channelNamePattern = Regex("[\\p{L}\\p{N}_.\\-·]{1,100}")
    private val channelTabs = setOf("videos", "shorts", "streams", "featured", "about", "playlists", "community")

    fun isChannelId(value: String) = channelIdPattern.matches(value)
    fun isVideoId(value: String) = videoIdPattern.matches(value)
    fun isVideoUrl(input: String) = runCatching { videoUrl(input) }.isSuccess

    fun channel(input: String): ChannelLink {
        val uri = parse(input)
        require(uri.host.lowercase(Locale.ROOT) in channelHosts) { "Enter a youtube.com channel link." }
        val parts = uri.path.trim('/').split('/')
        val base: String
        var channelId: String? = null
        when {
            parts[0].startsWith('@') -> {
                require(channelNamePattern.matches(parts[0].drop(1))) { "Invalid YouTube handle." }
                require(parts.size == 1 || (parts.size == 2 && parts[1] in channelTabs)) { "Enter a channel link, not a video or playlist." }
                base = "/${parts[0]}"
            }
            parts.size >= 2 && parts[0] in setOf("channel", "c", "user") -> {
                require(parts.size == 2 || (parts.size == 3 && parts[2] in channelTabs)) { "Enter a channel link, not a video or playlist." }
                if (parts[0] == "channel") {
                    require(isChannelId(parts[1])) { "Invalid YouTube channel ID." }
                    channelId = parts[1]
                } else {
                    require(channelNamePattern.matches(parts[1])) { "Invalid YouTube channel name." }
                }
                base = "/${parts[0]}/${parts[1]}"
            }
            else -> throw IllegalArgumentException("Use a YouTube @handle, /channel/, /c/, or /user/ link.")
        }
        return ChannelLink(URI("https", "www.youtube.com", base, null).toASCIIString(), channelId)
    }

    fun videoUrl(input: String): String {
        val uri = parse(input)
        val host = uri.host.lowercase(Locale.ROOT)
        val parts = uri.path.trim('/').split('/')
        val id = when {
            host in shortHosts && parts.size == 1 -> parts[0]
            host in videoHosts && parts == listOf("watch") -> {
                val ids = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                    val keyValue = pair.split('=', limit = 2)
                    if (decode(keyValue[0]) == "v" && keyValue.size == 2) decode(keyValue[1]) else null
                }
                require(ids.size == 1) { "A video link must contain one video ID." }
                ids.single()
            }
            host in videoHosts && parts.size == 2 && parts[0] in setOf("shorts", "live", "embed") -> parts[1]
            else -> throw IllegalArgumentException("Enter a YouTube watch, Shorts, live, or youtu.be video link.")
        }
        require(isVideoId(id)) { "Invalid YouTube video ID." }
        return "https://www.youtube.com/watch?v=$id"
    }

    fun channelUrl(channelId: String): String {
        require(isChannelId(channelId))
        return "https://www.youtube.com/channel/$channelId"
    }

    fun feedUrl(channelId: String): String {
        require(isChannelId(channelId))
        return "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
    }

    internal fun isPublicYouTubeUri(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase(Locale.ROOT) in channelHosts &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)

    private fun parse(input: String): URI {
        val value = input.trim()
        require(value.length in 1..4096 && value.none { it.isISOControl() || it.isWhitespace() }) { "Enter a valid YouTube link." }
        val withScheme = when {
            value.startsWith('@') -> "https://www.youtube.com/$value"
            !value.contains("://") -> "https://$value"
            else -> value
        }
        val uri = try { URI(withScheme) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid YouTube link.") }
        require(uri.scheme.lowercase(Locale.ROOT) in setOf("http", "https") && uri.host != null &&
            uri.rawUserInfo == null && uri.port == -1) { "Enter a public YouTube HTTPS link without credentials or a port." }
        require(!uri.isOpaque && uri.path != null) { "Enter a valid YouTube link." }
        return uri
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid URL encoding.")
    }
}
