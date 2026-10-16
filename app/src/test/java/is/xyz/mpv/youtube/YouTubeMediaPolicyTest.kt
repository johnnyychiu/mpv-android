package `is`.xyz.mpv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeMediaPolicyTest {
    private val id = "BaW_jenozKc"
    private val canonical = "https://www.youtube.com/watch?v=$id"

    @Test fun normalizesSupportedVideoLinksAndRemovesPlaylistOptions() {
        listOf(
            "https://www.youtube.com/watch?v=$id&list=PL123&index=2",
            "https://youtu.be/$id?t=30",
            "youtube.com/shorts/$id",
            "https://m.youtube.com/live/$id/",
            "https://www.youtube.com/embed/$id",
            "http://music.youtube.com/watch?v=$id"
        ).forEach { assertEquals(canonical, YouTubeMediaPolicy.videoUrl(it)) }
    }

    @Test fun rejectsChannelsPlaylistsAndAmbiguousOrMalformedIds() {
        listOf(
            "https://www.youtube.com/@channel",
            "https://www.youtube.com/channel/UC123",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/watch?v=$id&v=aaaaaaaaaaa",
            "https://www.youtube.com/watch?v=short",
            "https://youtu.be/$id/extra",
            "https://www.youtube.com/shorts/%42aW_jenozKc",
            "https://www.youtube.com/watch?v=%"
        ).forEach { assertThrows(it, IllegalArgumentException::class.java) { YouTubeMediaPolicy.videoUrl(it) } }
    }

    @Test fun rejectsOptionInjectionAndLookalikeOrLocalHosts() {
        listOf(
            "--exec=bad", "file:///etc/passwd", "javascript:alert(1)",
            "https://youtube.com.evil.example/watch?v=$id",
            "https://youtube.com@evil.example/watch?v=$id",
            "https://evil.example@youtube.com/watch?v=$id",
            "https://youtube.com:444/watch?v=$id",
            "https://youtube.com\\@evil.example/watch?v=$id",
            "https://youtube.com/watch?v=$id\n--exec=bad"
        ).forEach { assertThrows(it, IllegalArgumentException::class.java) { YouTubeMediaPolicy.videoUrl(it) } }
    }

    @Test fun acceptsOnlyHttpsGoogleVideoStreams() {
        val stream = "https://rr1---sn-abcd.googlevideo.com/videoplayback?expire=123&sig=abc"
        assertEquals(stream, YouTubeMediaPolicy.streamUrl(stream))
        listOf(
            "file:///sdcard/video.mp4", "http://rr1.googlevideo.com/video", "https://localhost/video",
            "https://googlevideo.com.evil.example/video", "https://evil.example/video",
            "https://user:pass@rr1.googlevideo.com/video", "https://rr1.googlevideo.com:444/video",
            "https://rr1.googlevideo.com/video\r\nHeader:bad"
        ).forEach { assertThrows(it, IllegalArgumentException::class.java) { YouTubeMediaPolicy.streamUrl(it) } }
    }

    @Test fun rejectsHeaderAndProcessIdInjection() {
        val headers = mapOf("User-Agent" to "Test/1.0", "Referer" to "https://www.youtube.com/")
        assertEquals(headers, YouTubeMediaPolicy.checkedHeaders(headers))
        listOf(mapOf("Bad:Name" to "value"), mapOf("User-Agent" to "x\r\nHeader: value"), mapOf("User-Agent" to "x\\y"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { YouTubeMediaPolicy.checkedHeaders(it) } }
        assertEquals("playback-abc_123", YouTubeMediaPolicy.processId("playback-abc_123"))
        listOf("", "id;touch /tmp/x", "$(id)", "id with space", "a".repeat(129)).forEach {
            assertThrows(IllegalArgumentException::class.java) { YouTubeMediaPolicy.processId(it) }
        }
    }

    @Test fun downloadFormatsMatchTheirFileTypes() {
        assertEquals("mp4", DownloadFormat.MP4.extension)
        assertEquals("video/mp4", DownloadFormat.MP4.mimeType)
        assertEquals("mp3", DownloadFormat.MP3.extension)
        assertEquals("audio/mpeg", DownloadFormat.MP3.mimeType)
    }

    @Test fun uploadsUrlsAreRestrictedToCanonicalPublicChannelPlaylists() {
        assertEquals("https://www.youtube.com/playlist?list=UUsBjURrPoezykLs9EqgamOA",
            YouTubeMediaPolicy.uploadsUrl("UCsBjURrPoezykLs9EqgamOA"))
        listOf("", "@Fireship", "UCshort", "UCsBjURrPoezykLs9EqgamOA&list=bad", "--exec=bad").forEach {
            assertThrows(IllegalArgumentException::class.java) { YouTubeMediaPolicy.uploadsUrl(it) }
        }
    }

    @Test fun uploadTimesUseRealMetadataAndKeepMissingOrInvalidDatesUnknown() {
        assertEquals(1709251200123, YouTubeMediaPolicy.uploadTime(1709251200.123, 1.0, "20200101"))
        assertEquals(1709251200000, YouTubeMediaPolicy.uploadTime(null, 1709251200.0, null))
        assertEquals(1709251200000, YouTubeMediaPolicy.uploadTime(null, null, "20240301"))
        listOf(null, "", "20230229", "20241301", "20240101suffix", "tomorrow").forEach {
            assertEquals(0, YouTubeMediaPolicy.uploadTime(Double.NaN, -1.0, it))
        }
        assertEquals(0, YouTubeMediaPolicy.uploadTime(Double.POSITIVE_INFINITY, Double.MAX_VALUE, null))
    }
}
