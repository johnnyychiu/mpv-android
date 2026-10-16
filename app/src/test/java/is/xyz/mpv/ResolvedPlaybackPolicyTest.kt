package `is`.xyz.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolvedPlaybackPolicyTest {
    private val video = "https://r1.googlevideo.com/videoplayback?id=video"

    @Test fun progressivePlaybackClearsExternalAudioAndStartsPlaying() {
        val options = ResolvedPlaybackPolicy.options(video, null, "Example", emptyMap())
        assertTrue(options.contains("audio-files=%0%"))
        assertTrue(options.contains("http-header-fields=%0%"))
        assertTrue(options.contains("pause=%2%no"))
        assertFalse(options.contains("audio-files-append"))
    }

    @Test fun adaptiveAudioIsOneLiteralUrl() {
        val audio = "https://r2.googlevideo.com/audio?key=one,two:three"
        val options = ResolvedPlaybackPolicy.options(video, audio, null, emptyMap())
        assertTrue(options.contains("audio-files-append=%"))
        assertTrue(options.contains(audio))
        assertTrue(options.endsWith(",aid=%4%auto"))
    }

    @Test fun rejectsNonYouTubeMediaAndUnsafeAuthorities() {
        listOf(
            "http://r1.googlevideo.com/video",
            "https://googlevideo.com.attacker.test/video",
            "https://attackergooglevideo.com/video",
            "https://user@r1.googlevideo.com/video",
            "https://r1.googlevideo.com:8443/video",
            "https://r1.googlevideo.com/video#fragment",
            "https://r1.googlevideo.com/\\video",
            "file:///sdcard/video.mp4",
            "https://r1.googlevideo.com/video\n"
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) {
                ResolvedPlaybackPolicy.options(url, null, null, emptyMap())
            }
        }
    }

    @Test fun validatesTheAudioUrlToo() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedPlaybackPolicy.options(video, "https://attacker.test/audio", null, emptyMap())
        }
    }

    @Test fun rejectsHeaderInjectionAndAmbiguousBackslashValues() {
        listOf(
            mapOf("User-Agent\r\nX-Evil" to "bad"),
            mapOf("User-Agent" to "value\r\nX-Evil: bad"),
            mapOf("Accept" to "value\u0000"),
            mapOf("Accept" to "trailing\\"),
            mapOf("Bad: Name" to "value")
        ).forEach { headers ->
            assertThrows(IllegalArgumentException::class.java) {
                ResolvedPlaybackPolicy.options(video, null, null, headers)
            }
        }
    }

    @Test fun escapesCommasInAHeaderWithoutAddingAnotherHeader() {
        val options = ResolvedPlaybackPolicy.options(
            video, null, null, mapOf("Accept" to "video/*, audio/*")
        )
        assertTrue(options.contains("http-header-fields=%25%Accept: video/*\\, audio/*"))
    }

    @Test fun titleCannotInjectOptionsAndLengthUsesJniEncoding() {
        val options = ResolvedPlaybackPolicy.options(video, null, "标题 😀,pause=yes", emptyMap())
        assertTrue(options.contains("force-media-title=%23%标题 😀,pause=yes,http-header-fields="))
    }

    @Test fun titleControlsAreRemoved() {
        val options = ResolvedPlaybackPolicy.options(video, null, "a\nb\u0000c", emptyMap())
        assertTrue(options.contains("force-media-title=%5%a b c,"))
    }
}
