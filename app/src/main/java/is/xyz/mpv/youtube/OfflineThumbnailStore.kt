package `is`.xyz.mpv.youtube

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Log
import `is`.xyz.mpv.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Durable artwork for saved files. Network access is limited to one fixed YouTube image URL. */
class OfflineThumbnailStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "youtube-offline-thumbnails")

    /** Returns only a private, already saved file; never starts a network request. */
    fun file(videoId: String?): File? {
        val id = videoId?.let { runCatching { OfflineLibraryPolicy.videoId(it) }.getOrNull() } ?: return null
        return File(directory, "$id.jpg").takeIf { it.isFile && it.length() in 1..MAX_BYTES.toLong() }
    }

    /** Blocking, best-effort cache fill. Call from a worker or another background thread. */
    fun cache(videoId: String): File? = synchronized(lock) {
        val id = runCatching { OfflineLibraryPolicy.videoId(videoId) }.getOrNull() ?: return@synchronized null
        file(id)?.let { return@synchronized it }
        var stage = "preparing private storage"
        try {
            checkActive()
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized skipped("Private thumbnail directory could not be created.")
            val existing = directory.listFiles()?.filter { it.isFile }
                ?: return@synchronized skipped("Private thumbnail directory could not be listed.")
            // Keep existing artwork rather than evicting files required for offline use.
            if (existing.size >= OfflineLibraryPolicy.MAX_DOWNLOADS) return@synchronized skipped("Thumbnail file count limit reached.")
            val usedBytes = existing.sumOf { it.length() }
            if (usedBytes >= MAX_CACHE_BYTES) return@synchronized skipped("Thumbnail storage limit reached.")
            stage = "fetching JPEG"
            val bytes = fetch(id)
            if (usedBytes + bytes.size > MAX_CACHE_BYTES) return@synchronized skipped("Fetched image would exceed the thumbnail storage limit.")
            stage = "decoding JPEG bounds (${bytes.size} bytes)"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) {
                return@synchronized skipped("Decoded thumbnail rejected: MIME=${bounds.outMimeType}, width=${bounds.outWidth}, height=${bounds.outHeight}, bytes=${bytes.size}.")
            }
            checkActive()
            stage = "committing JPEG (${bytes.size} bytes)"
            val storage = AtomicFile(File(directory, "$id.jpg"))
            val output = storage.startWrite()
            try {
                output.write(bytes)
                storage.finishWrite(output)
            } catch (error: Exception) {
                storage.failWrite(output)
                throw error
            }
            file(id) ?: skipped("The committed thumbnail could not be reopened.")
        } catch (error: Exception) {
            // Include unexpected decoder/storage failures here, before the worker's
            // best-effort wrapper can discard them. Never log media URLs or headers.
            skipped("Thumbnail cache failed while $stage.", error)
        } catch (error: OutOfMemoryError) {
            skipped("Thumbnail cache ran out of memory while $stage.", error)
        }
    }

    private fun fetch(videoId: String): ByteArray {
        val connection = URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "image/jpeg")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        try {
            val status = connection.responseCode
            val length = connection.contentLengthLong
            val mime = connection.contentType?.substringBefore(';')?.trim()
            requireImage(status == HttpURLConnection.HTTP_OK, "Thumbnail HTTP status=$status, MIME=$mime, declaredBytes=$length.")
            requireImage(length <= MAX_BYTES, "Thumbnail declared size exceeds the limit: $length bytes.")
            requireImage(mime.equals("image/jpeg", ignoreCase = true), "Thumbnail HTTP MIME rejected: $mime.")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive()
                    val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (remainingMs <= 0) throw IOException("Thumbnail request exceeded its 25-second deadline.")
                    connection.readTimeout = remainingMs.coerceAtMost(8000).toInt()
                    val count = input.read(buffer)
                    if (count < 0) break
                    requireImage(output.size() + count <= MAX_BYTES, "Thumbnail response exceeded the byte limit.")
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                requireImage(bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte(),
                    "Thumbnail JPEG signature rejected: ${bytes.size} bytes.")
                bytes
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw IOException("Thumbnail request cancelled.")
    }

    private fun requireImage(valid: Boolean, reason: String) {
        if (!valid) throw IOException(reason)
    }

    private fun skipped(reason: String, error: Throwable? = null): File? {
        if (BuildConfig.DEBUG) {
            val detail = if (error == null) reason else "$reason ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            val safe = detail.replace(Regex("https?://\\S+"), "[URL]")
                .map { if (it.isISOControl()) ' ' else it }.joinToString("").take(500)
            Log.w("OfflineThumbnailStore", safe)
        }
        return null
    }

    private companion object {
        val lock = Any()
        const val MAX_BYTES = 1024 * 1024
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
