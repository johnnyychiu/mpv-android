package `is`.xyz.mpv.youtube

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import java.util.Locale
import java.util.concurrent.CancellationException

/** One supported public-client retry; never retry cancellation or access restrictions. */
internal object PlaybackFallbackPolicy {
    fun <T> resolve(
        primary: () -> T,
        fallback: () -> T,
        checkCancelled: () -> Unit
    ): T = try {
        primary()
    } catch (error: Exception) {
        checkCancelled()
        if (!canRetry(error)) throw error
        fallback()
    }

    fun canRetry(error: Exception): Boolean {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        if (causes.any { it is CancellationException || it is InterruptedException ||
                it is YoutubeDL.CanceledException || it is SecurityException || it is IllegalArgumentException }) {
            return false
        }
        if (error !is YoutubeDLException && error !is IllegalStateException) return false
        val message = causes.joinToString("\n") { it.message.orEmpty() }
            .lowercase(Locale.ROOT).replace('’', '\'')
        val restricted = listOf(
            "private video", "members-only", "members only", "membership", "premium", "purchase",
            "age-restricted", "age restricted", "confirm your age", "login required", "log in to",
            "sign in to view", "sign in to watch", "not available in your country", "copyright",
            "has been removed", "video unavailable", "drm"
        )
        if (restricted.any(message::contains)) return false
        return listOf(
            "confirm you're not a bot", "http error 403", "http error 429",
            "requested format is not available", "no video formats found", "no playable video stream",
            "no playable audio stream", "unreadable video information"
        ).any(message::contains)
    }
}
