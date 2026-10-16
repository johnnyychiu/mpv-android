package `is`.xyz.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test fun passesBoundedRequestSizeToBothNativeTransports() {
        val options = parseUtf8Options(ResolvedPlaybackPolicy.options(
            video, "https://r2.googlevideo.com/audio", "Example", emptyMap(), 10485760L
        ))
        assertEquals("10485760", options["curl-max-request-size"])
        assertEquals("request_size=10485760", options["stream-lavf-o"])
        assertEquals("https://r2.googlevideo.com/audio", options["audio-files-append"])
        val withoutLimit = parseUtf8Options(ResolvedPlaybackPolicy.options(video, null, null, emptyMap()))
        assertFalse(withoutLimit.containsKey("curl-max-request-size"))
        assertFalse(withoutLimit.containsKey("stream-lavf-o"))
    }

    @Test fun rejectsHttpRequestLimitsOutsideTheSupportedRange() {
        listOf(Long.MIN_VALUE, -1L, 0L, 1023L, 67108865L, Long.MAX_VALUE).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) {
                ResolvedPlaybackPolicy.options(video, null, null, emptyMap(), bytes)
            }
        }
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

    @Test fun unicodeTitleRoundTripsWithoutConsumingTheNextOption() {
        val title = "标题 😀,pause=yes"
        val options = ResolvedPlaybackPolicy.options(video, null, title, emptyMap())
        assertTrue(options.contains("force-media-title=%21%$title,http-header-fields="))
        val parsed = parseUtf8Options(options)
        assertEquals(title, parsed["force-media-title"])
        assertEquals("no", parsed["pause"])
        assertEquals("", parsed["http-header-fields"])
        assertEquals("", parsed["audio-files"])
        assertEquals(5, parsed.size)
    }

    @Test fun arbitraryTitleSyntaxCannotInjectOptions() {
        val title = "小薯茄 🍟😃 \"quoted\" [unclosed ],pause=yes,%0%,audio-files=x\\"
        val parsed = parseUtf8Options(ResolvedPlaybackPolicy.options(video, null, title, emptyMap()))
        assertEquals(title, parsed["force-media-title"])
        assertEquals("no", parsed["pause"])
        assertEquals("", parsed["audio-files"])
        assertEquals(5, parsed.size)
    }

    @Test fun truncatingTitleDoesNotSplitAnEmoji() {
        val title = "a".repeat(1023) + "😀 suffix"
        val parsed = parseUtf8Options(ResolvedPlaybackPolicy.options(video, null, title, emptyMap()))
        assertEquals("a".repeat(1023), parsed["force-media-title"])
        assertEquals("", parsed["http-header-fields"])
    }

    @Test fun unpairedSurrogatesUseAndroidJniThreeByteEncoding() {
        val options = ResolvedPlaybackPolicy.options(video, null, "\uD83Dx\uDE00", emptyMap())
        assertTrue(options.contains("force-media-title=%7%\uD83Dx\uDE00,http-header-fields="))
    }

    @Test fun titleControlsAreRemoved() {
        val options = ResolvedPlaybackPolicy.options(video, null, "a\nb\u0000c", emptyMap())
        assertTrue(options.contains("force-media-title=%5%a b c,"))
    }

    // Exercise mpv's byte-length framing independently using the JVM UTF-8 encoder.
    // For well-formed, non-NUL text its bytes match Android GetStringUTFChars.
    private fun parseUtf8Options(options: String): Map<String, String> {
        val bytes = options.toByteArray(Charsets.UTF_8)
        val parsed = linkedMapOf<String, String>()
        var index = 0
        while (index < bytes.size) {
            val nameStart = index
            while (index < bytes.size && bytes[index] != '='.code.toByte()) index++
            assertTrue("Missing option separator", index < bytes.size)
            val name = String(bytes, nameStart, index - nameStart, Charsets.UTF_8)
            index++
            assertEquals('%'.code.toByte(), bytes[index++])
            val lengthStart = index
            while (index < bytes.size && bytes[index] != '%'.code.toByte()) index++
            assertTrue("Missing length separator", index < bytes.size)
            val length = String(bytes, lengthStart, index - lengthStart, Charsets.US_ASCII).toInt()
            index++
            assertTrue("Option exceeds input", index + length <= bytes.size)
            assertFalse("Duplicate option", parsed.containsKey(name))
            parsed[name] = String(bytes, index, length, Charsets.UTF_8)
            index += length
            if (index < bytes.size) assertEquals(','.code.toByte(), bytes[index++])
        }
        return parsed
    }
}
