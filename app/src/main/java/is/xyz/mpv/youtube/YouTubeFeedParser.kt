package `is`.xyz.mpv.youtube

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

object YouTubeFeedParser {
    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val YOUTUBE = "http://www.youtube.com/xml/schemas/2015"
    const val MAX_FEED_BYTES = 512 * 1024
    const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    private val isoDate = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2})$")

    fun parseRss(xml: String, expectedChannelId: String): ChannelFeed {
        require(YouTubeUrls.isChannelId(expectedChannelId))
        require(xml.length <= MAX_FEED_BYTES) { "Channel feed is too large." }
        // Explicitly prohibit declarations as well as disabling external entities. Some Android
        // XML implementations do not implement every JAXP security feature.
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) {
            "XML declarations and external entities are not permitted."
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are disabled.") }
            setErrorHandler(DefaultHandler())
        }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        require(root.localName == "feed" && root.namespaceURI == ATOM) { "YouTube returned an invalid feed." }
        val channelId = root.childText(YOUTUBE, "channelId")
        require(channelId == expectedChannelId) { "YouTube returned a different channel." }
        val title = root.childText(ATOM, "title").trim().take(300).ifEmpty { channelId }
        val videos = root.children(ATOM, "entry").take(200).mapNotNull { entry ->
            val videoId = entry.childText(YOUTUBE, "videoId")
            val entryChannel = entry.childText(YOUTUBE, "channelId")
            val videoTitle = entry.childText(ATOM, "title").trim().take(500)
            val publishedAt = parseDate(entry.childText(ATOM, "published")) ?: return@mapNotNull null
            if (!YouTubeUrls.isVideoId(videoId) || entryChannel != channelId || videoTitle.isEmpty()) return@mapNotNull null
            YouTubeVideo(videoId, channelId, videoTitle, title, publishedAt)
        }.distinctBy { it.videoId }.sortedByDescending { it.publishedAt }
        return ChannelFeed(channelId, title, videos)
    }

    /** Use channel metadata, never an arbitrary related video's channelId from the page. */
    fun channelIdFromPage(html: String): String {
        require(html.length <= MAX_PAGE_BYTES) { "Channel page is too large." }
        val tags = Regex("<(?:link|meta)\\b[^>]{0,8192}>", RegexOption.IGNORE_CASE)
        val attributes = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (tag in tags.findAll(html)) {
            val attrs = attributes.findAll(tag.value).associate {
                it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[3].replace("&amp;", "&")
            }
            val id = when {
                attrs["itemprop"] == "channelId" -> attrs["content"]
                attrs["rel"] == "canonical" || attrs["property"] == "og:url" ->
                    runCatching { YouTubeUrls.channel(attrs["href"] ?: attrs["content"].orEmpty()).channelId }.getOrNull()
                attrs["type"] == "application/rss+xml" -> {
                    val href = attrs["href"].orEmpty()
                    Regex("^https://www\\.youtube\\.com/feeds/videos\\.xml\\?channel_id=(UC[A-Za-z0-9_-]{22})$")
                        .matchEntire(href)?.groupValues?.get(1)
                }
                else -> null
            }
            if (id != null && YouTubeUrls.isChannelId(id)) return id
        }
        // This metadata object belongs to the channel page; generic "externalId" searches can
        // accidentally select one of the recommended channels elsewhere in ytInitialData.
        val metadata = Regex("\"channelMetadataRenderer\"\\s*:\\s*\\{[^{}]{0,65536}?\"externalId\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{22})\"")
            .find(html)?.groupValues?.get(1)
        return metadata ?: throw IllegalArgumentException("Could not resolve this channel. Try its youtube.com/channel/UC… link, then refresh.")
    }

    internal fun parseDate(value: String): Long? {
        val match = isoDate.matchEntire(value.trim()) ?: return null
        val fraction = match.groupValues[2].padEnd(3, '0').take(3)
        val zone = match.groupValues[3].let { if (it == "Z") "+0000" else it.replace(":", "") }
        val normalized = "${match.groupValues[1]}.$fraction$zone"
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        val date = format.parse(normalized, position) ?: return null
        return date.time.takeIf { position.index == normalized.length }
    }

    private fun Element.children(namespace: String, name: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { it.localName == name && it.namespaceURI == namespace }

    private fun Element.childText(namespace: String, name: String): String =
        children(namespace, name).firstOrNull()?.textContent.orEmpty()
}
