package `is`.xyz.mpv.youtube

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class YouTubeSubscriptionsTest {
    private val channelId = "UCBR8-60-B28hp2BmDPdntcQ"
    private val otherChannel = "UC16niRr50-MSBwiO3YDb3RA"

    @Test fun channelLinksAcceptSupportedFormsAndNormalizeTabs() {
        assertEquals("https://www.youtube.com/@YouTube", YouTubeUrls.channel("https://m.youtube.com/@YouTube/videos?view=0").url)
        assertEquals("https://www.youtube.com/@YouTube", YouTubeUrls.channel("@YouTube").url)
        assertEquals(channelId, YouTubeUrls.channel("youtube.com/channel/$channelId/shorts").channelId)
        assertEquals("https://www.youtube.com/c/Example", YouTubeUrls.channel("https://www.youtube.com/c/Example").url)
        assertEquals("https://www.youtube.com/user/Example", YouTubeUrls.channel("https://www.youtube.com/user/Example/featured").url)
        assertNull(YouTubeUrls.channel("https://www.youtube.com/@YouTube").channelId)
    }

    @Test fun channelLinksRejectForeignHostsCredentialsAndVideoLinks() {
        listOf("https://youtube.com.evil.test/@name", "https://youtube.com@evil.test/@name", "https://user@youtube.com/@name",
            "http://127.0.0.1/@name", "https://youtube.com:443/@name", "file:///channel/$channelId", "https://youtube.com/channel/UCshort",
            "https://youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/@name/videos/extra", "https://youtube.com/@name%00")
            .forEach { input -> assertThrows(input, IllegalArgumentException::class.java) { YouTubeUrls.channel(input) } }
    }

    @Test fun videosNormalizeAndDiscardTrackingParameters() {
        val canonical = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        listOf("https://youtu.be/dQw4w9WgXcQ?si=tracking", "https://www.youtube.com/watch?feature=shared&v=dQw4w9WgXcQ&t=30",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ", "https://youtube.com/live/dQw4w9WgXcQ", "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/embed/dQw4w9WgXcQ").forEach { assertEquals(canonical, YouTubeUrls.videoUrl(it)) }
    }

    @Test fun videosRejectMalformedIdsAndUnsafeLinks() {
        listOf("https://youtu.be/short", "https://evil.test/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?v=dQw4w9WgXcQ&v=abcdefghijk",
            "https://youtube.com/watch?v=--exec%20bad", "https://youtube.com/redirect?q=https://evil.test", "https://youtube.com/playlist?list=abc",
            "https://youtube.com/watch?v=abcdefghij%00", "https://youtu.be/dQw4w9WgXcQ/extra", "ftp://youtube.com/watch?v=dQw4w9WgXcQ")
            .forEach { input -> assertFalse(input, YouTubeUrls.isVideoUrl(input)) }
    }

    @Test fun rssUsesNamespacesDecodesTextAndOrdersPublicationDates() {
        val xml = feed(entry("abcdefghijk", "2026-01-02T03:04:05+01:00", "Older &amp; clear") +
            entry("dQw4w9WgXcQ", "2026-01-02T03:04:05.123Z", "Newer"))
        val parsed = YouTubeFeedParser.parseRss(xml, channelId)
        assertEquals("Channel & title", parsed.title)
        assertEquals(listOf("dQw4w9WgXcQ", "abcdefghijk"), parsed.videos.map { it.videoId })
        assertEquals(Instant.parse("2026-01-02T03:04:05.123Z").toEpochMilli(), parsed.videos[0].publishedAt)
        assertEquals(Instant.parse("2026-01-02T02:04:05Z").toEpochMilli(), parsed.videos[1].publishedAt)
        assertEquals("Older & clear", parsed.videos[1].title)
    }

    @Test fun rssAcceptsDifferentNamespacePrefixes() {
        val xml = feed(entry("abcdefghijk", "2026-01-02T03:04:05Z"))
            .replace("xmlns=\"http://www.w3.org/2005/Atom\"", "xmlns:a=\"http://www.w3.org/2005/Atom\"")
            .replace(Regex("<(\\/?)(feed|title|entry|published)>"), "<$1a:$2>")
            .replace("<feed ", "<a:feed ").replace("yt:", "video:").replace("xmlns:yt", "xmlns:video")
        assertEquals(1, YouTubeFeedParser.parseRss(xml, channelId).videos.size)
    }

    @Test fun rssSkipsInvalidEntriesAndDeduplicatesVideos() {
        val xml = feed(entry("abcdefghijk", "2026-01-02T03:04:05Z") + entry("abcdefghijk", "2026-01-02T03:04:05Z") +
            entry("invalid", "2026-01-02T03:04:05Z") + entry("01234567890", "2026-02-30T03:04:05Z") +
            entry("dQw4w9WgXcQ", "bad-date") + entry("zyxwvutsrqp", "2026-01-02T03:04:05Z").replace(channelId, otherChannel))
        assertEquals(listOf("abcdefghijk"), YouTubeFeedParser.parseRss(xml, channelId).videos.map { it.videoId })
    }

    @Test fun rssRejectsWrongChannelOrNamespace() {
        assertThrows(IllegalArgumentException::class.java) { YouTubeFeedParser.parseRss(feed(""), otherChannel) }
        assertThrows(IllegalArgumentException::class.java) { YouTubeFeedParser.parseRss(feed("").replace("http://www.w3.org/2005/Atom", "https://evil.test"), channelId) }
    }

    @Test fun rssRejectsEntityDeclarationsAndOversizedDocuments() {
        val malicious = "<!DOCTYPE feed [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + feed("<title>&secret;</title>")
        assertThrows(IllegalArgumentException::class.java) { YouTubeFeedParser.parseRss(malicious, channelId) }
        assertThrows(IllegalArgumentException::class.java) { YouTubeFeedParser.parseRss(" ".repeat(YouTubeFeedParser.MAX_FEED_BYTES + 1), channelId) }
    }

    @Test fun channelPageReadsCanonicalAndRssMetadata() {
        assertEquals(channelId, YouTubeFeedParser.channelIdFromPage("<link href='https://www.youtube.com/channel/$channelId' rel='canonical'>"))
        assertEquals(channelId, YouTubeFeedParser.channelIdFromPage("<link type=\"application/rss+xml\" href=\"https://www.youtube.com/feeds/videos.xml?channel_id=$channelId\">"))
        assertEquals(channelId, YouTubeFeedParser.channelIdFromPage("<meta content='$channelId' itemprop='channelId'>"))
    }

    @Test fun channelPageIgnoresRecommendedChannelIds() {
        val html = "{\"externalId\":\"$otherChannel\",\"channelId\":\"$otherChannel\",\"channelMetadataRenderer\":{\"title\":\"Correct\",\"externalId\":\"$channelId\"}}"
        assertEquals(channelId, YouTubeFeedParser.channelIdFromPage(html))
        assertThrows(IllegalArgumentException::class.java) { YouTubeFeedParser.channelIdFromPage("{\"channelId\":\"$otherChannel\"}") }
    }

    @Test fun mergeKeepsFailedCacheAndSortsSuccessfulNewUploads() {
        val previous = SubscriptionSnapshot(
            listOf(YouTubeSubscription(channelId, "Old name", 123), YouTubeSubscription(otherChannel, "Other", 456)),
            listOf(video("abcdefghijk", channelId, 10), video("dQw4w9WgXcQ", otherChannel, 20)),
        )
        val results = mapOf(channelId to Result.success(ChannelFeed(channelId, "New name", listOf(video("01234567890", channelId, 30)))),
            otherChannel to Result.failure<ChannelFeed>(IOException("Offline")))
        val merged = SubscriptionFeeds.merge(previous, results, 999)
        assertEquals(listOf("01234567890", "dQw4w9WgXcQ", "abcdefghijk"), merged.videos.map { it.videoId })
        assertEquals(999, merged.subscriptions[0].lastRefreshedAt)
        assertEquals(456, merged.subscriptions[1].lastRefreshedAt)
        assertEquals("Offline", merged.subscriptions[1].lastError)
        assertEquals("New name", merged.videos.last().channelTitle)
    }

    @Test fun removePurgesOnlyThatChannelsCachedVideos() {
        val previous = SubscriptionSnapshot(listOf(YouTubeSubscription(channelId, "A"), YouTubeSubscription(otherChannel, "B")),
            listOf(video("abcdefghijk", channelId, 10), video("dQw4w9WgXcQ", otherChannel, 20)))
        val removed = SubscriptionFeeds.remove(previous, channelId)
        assertEquals(listOf(otherChannel), removed.subscriptions.map { it.channelId })
        assertEquals(listOf("dQw4w9WgXcQ"), removed.videos.map { it.videoId })
    }

    @Test fun mergeDeduplicatesUpdatesAndCapsCachePerChannel() {
        val initial = SubscriptionSnapshot(listOf(YouTubeSubscription(channelId, "A"), YouTubeSubscription(channelId, "A")))
        val videos = (0..70).map { video(it.toString().padStart(11, '0'), channelId, it.toLong()) }
        val result = SubscriptionFeeds.merge(initial, mapOf(channelId to Result.success(ChannelFeed(channelId, "A", videos + videos))), 100)
        assertEquals(1, result.subscriptions.size)
        assertEquals(50, result.videos.size)
        assertEquals(70, result.videos.first().publishedAt)
    }

    @Test fun successfulRefreshClearsErrorAndDoesNotEraseKnownDates() {
        val previous = SubscriptionSnapshot(listOf(YouTubeSubscription(channelId, "A", lastError = "Offline")), listOf(video("abcdefghijk", channelId, 123)))
        val result = SubscriptionFeeds.merge(previous,
            mapOf(channelId to Result.success(ChannelFeed(channelId, "A", listOf(video("abcdefghijk", channelId, 0))))), 100)
        assertNull(result.subscriptions.single().lastError)
        assertEquals(123, result.videos.single().publishedAt)
    }

    @Test fun unknownDatesPreserveExtractorOrder() {
        val initial = SubscriptionSnapshot(listOf(YouTubeSubscription(channelId, "A")))
        val videos = listOf(video("zyxwvutsrqp", channelId, 0), video("abcdefghijk", channelId, 0))
        assertEquals(videos.map { it.videoId }, SubscriptionFeeds.merge(initial,
            mapOf(channelId to Result.success(ChannelFeed(channelId, "A", videos))), 100).videos.map { it.videoId })
    }

    @Test fun unknownDatesInterleaveChannelsWithoutChangingTheirSourceOrder() {
        val initial = SubscriptionSnapshot(listOf(YouTubeSubscription(channelId, "A"), YouTubeSubscription(otherChannel, "B")))
        val first = listOf(video("zyxwvutsrqp", channelId, 0), video("abcdefghijk", channelId, 0), video("01234567890", channelId, 0))
        val second = listOf(video("dQw4w9WgXcQ", otherChannel, 0), video("98765432109", otherChannel, 0))
        val merged = SubscriptionFeeds.merge(initial, mapOf(
            channelId to Result.success(ChannelFeed(channelId, "A", first)),
            otherChannel to Result.success(ChannelFeed(otherChannel, "B", second)),
        ), 100)
        assertEquals(listOf("zyxwvutsrqp", "dQw4w9WgXcQ", "abcdefghijk", "98765432109", "01234567890"),
            merged.videos.map { it.videoId })
        assertTrue(merged.videos.all { it.publishedAt == 0L })
        assertEquals(first.map { it.videoId }, merged.videos.filter { it.channelId == channelId }.map { it.videoId })
        assertEquals(second.map { it.videoId }, merged.videos.filter { it.channelId == otherChannel }.map { it.videoId })
    }

    @Test fun knownDatesRemainChronologicalBeforeInterleavedUnknownUploads() {
        val initial = SubscriptionSnapshot(listOf(YouTubeSubscription(channelId, "A"), YouTubeSubscription(otherChannel, "B")))
        val first = listOf(video("abcdefghijk", channelId, 0), video("01234567890", channelId, 100))
        val second = listOf(video("zyxwvutsrqp", otherChannel, 0), video("dQw4w9WgXcQ", otherChannel, 200))
        val merged = SubscriptionFeeds.merge(initial, mapOf(
            channelId to Result.success(ChannelFeed(channelId, "A", first)),
            otherChannel to Result.success(ChannelFeed(otherChannel, "B", second)),
        ), 300)
        assertEquals(listOf("dQw4w9WgXcQ", "01234567890", "abcdefghijk", "zyxwvutsrqp"), merged.videos.map { it.videoId })
        assertEquals(listOf(200L, 100L, 0L, 0L), merged.videos.map { it.publishedAt })
    }

    private fun video(id: String, channel: String, time: Long) = YouTubeVideo(id, channel, "Video $id", "Channel", time)
    private fun feed(entries: String) = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">
        <yt:channelId>$channelId</yt:channelId><title>Channel &amp; title</title>$entries</feed>"""
    private fun entry(id: String, date: String, title: String = "Video") = """<entry><yt:videoId>$id</yt:videoId>
        <yt:channelId>$channelId</yt:channelId><title>$title</title><published>$date</published></entry>"""
}
