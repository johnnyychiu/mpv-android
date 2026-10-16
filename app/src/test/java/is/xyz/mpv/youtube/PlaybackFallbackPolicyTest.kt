package `is`.xyz.mpv.youtube

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class PlaybackFallbackPolicyTest {
    @Test fun successfulPrimaryDoesNotMakeAnotherRequest() {
        val result = PlaybackFallbackPolicy.resolve(
            primary = { "primary" }, fallback = { error("Must not retry") }, checkCancelled = {}
        )
        assertEquals("primary", result)
    }

    @Test fun recoverableFailureMakesExactlyOneFallbackAttempt() {
        var attempts = 0
        val lastError = YoutubeDLException("HTTP Error 403: Forbidden")
        val error = assertThrows(YoutubeDLException::class.java) {
            PlaybackFallbackPolicy.resolve(
                primary = { attempts++; throw YoutubeDLException("Sign in to confirm you're not a bot") },
                fallback = { attempts++; throw lastError }, checkCancelled = {}
            )
        }
        assertEquals(2, attempts)
        assertSame(lastError, error)
    }

    @Test fun cancellationBetweenAttemptsStopsTheFallback() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            PlaybackFallbackPolicy.resolve(
                primary = { attempts++; throw YoutubeDLException("HTTP Error 429") },
                fallback = { attempts++; "fallback" },
                checkCancelled = { throw CancellationException("Cancelled") }
            )
        }
        assertEquals(1, attempts)
    }

    @Test fun rejectsCancellationInvalidInputAndSecurityErrors() {
        listOf(
            CancellationException("HTTP Error 403"), InterruptedException("HTTP Error 403"),
            YoutubeDL.CanceledException(), IllegalArgumentException("No playable video stream"),
            SecurityException("HTTP Error 403"),
            IllegalStateException("HTTP Error 403", InterruptedException())
        ).forEach { assertFalse(PlaybackFallbackPolicy.canRetry(it)) }
    }

    @Test fun retriesFormatOrPublicClientFailuresButNotAuthenticationRestrictions() {
        listOf("Requested format is not available", "No playable audio stream was found",
            "YouTube returned unreadable video information", "Sign in to confirm you’re not a bot").forEach {
            assertTrue(it, PlaybackFallbackPolicy.canRetry(YoutubeDLException(it)))
        }
        listOf("Private video", "Sign in to confirm your age", "This video is members-only",
            "This video is DRM protected", "Video unavailable", "Login required", "Premium video",
            "Not available in your country", "HTTP Error 403: private video").forEach {
            assertFalse(it, PlaybackFallbackPolicy.canRetry(YoutubeDLException(it)))
        }
    }
}
