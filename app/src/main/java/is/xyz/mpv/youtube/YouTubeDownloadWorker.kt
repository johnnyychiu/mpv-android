package `is`.xyz.mpv.youtube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class YouTubeDownloadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val processId = "download-$id"
    private val cancelled = AtomicBoolean(false)
    @Volatile private var copyingInput: InputStream? = null
    @Volatile private var copyingOutput: OutputStream? = null
    private var lastProgressTime = 0L
    private val title = inputData.getString("title").orEmpty().ifBlank { "YouTube video" }.take(300)

    override fun doWork(): Result {
        val stagingRoot = File(applicationContext.cacheDir, "youtube-downloads").canonicalFile
        val staging = File(stagingRoot, id.toString()).canonicalFile
        return try {
            check(staging.parentFile == stagingRoot) { "Invalid temporary download folder." }
            ensureActive()
            val url = inputData.getString("url") ?: error("The video link is missing.")
            val format = DownloadFormat.valueOf(inputData.getString("format") ?: error("The download format is missing."))
            val destination = Uri.parse(inputData.getString("destination") ?: error("The save location is missing."))
            OfflineLibraryPolicy.destination(destination.toString())
            createNotificationChannel()
            setForegroundAsync(foregroundInfo(0, "Preparing download")).get()
            reportProgress(0, "Preparing download", force = true)
            ensureActive()
            // A previous process may have been stopped before its finally block ran.
            if (staging.exists()) staging.deleteRecursively()
            val completedFile = YouTubeEngine.download(applicationContext, url, format, staging, processId) { percent, status ->
                if (!isStopped && !cancelled.get()) reportProgress(percent, status)
            }
            ensureActive()
            reportProgress(99, "Saving to the selected location", force = true)
            copyToDocument(completedFile, destination)
            ensureActive()
            // Only publish fully copied, closed documents. This survives WorkManager
            // history pruning and remains idempotent if a completed job is retried.
            try {
                OfflineLibraryRepository(applicationContext).record(
                    OfflineDownload(id.toString(), destination.toString(), title, format, System.currentTimeMillis())
                )
            } catch (error: Exception) {
                throw IOException("The file was saved, but could not be added to the offline library: ${error.message.orEmpty()}", error)
            }
            setProgressAsync(workDataOf("percent" to 100, "status" to "Saved", "title" to title)).get()
            Result.success(workDataOf("destination" to destination.toString(), "title" to title))
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val message = if (isStopped || cancelled.get() || error is CancellationException || error is InterruptedException) {
                "Download cancelled."
            } else {
                error.message?.takeIf { it.isNotBlank() }?.take(800)
                    ?: "The download failed. Check your connection and save location, or update the YouTube tools."
            }
            Result.failure(workDataOf("error" to message, "title" to title))
        } finally {
            closeCopyStreams()
            // Never remove anything outside this worker's private UUID staging directory.
            if (staging.parentFile == stagingRoot) staging.deleteRecursively()
        }
    }

    override fun onStopped() {
        cancelled.set(true)
        YouTubeEngine.cancel(processId)
        closeCopyStreams()
        super.onStopped()
    }

    private fun copyToDocument(source: File, destination: Uri) {
        ensureActive()
        source.inputStream().use { input ->
            copyingInput = input
            ensureActive()
            val output = applicationContext.contentResolver.openOutputStream(destination, "wt")
                ?: error("The selected save location could not be opened. Choose it again and retry.")
            output.use {
                copyingOutput = output
                ensureActive()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    ensureActive()
                    output.write(buffer, 0, count)
                }
                ensureActive()
                output.flush()
            }
            copyingOutput = null
        }
        copyingInput = null
    }

    private fun closeCopyStreams() {
        runCatching { copyingInput?.close() }
        runCatching { copyingOutput?.close() }
        copyingInput = null
        copyingOutput = null
    }

    private fun ensureActive() {
        if (isStopped || cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled.")
    }

    private fun reportProgress(percent: Int, status: String, force: Boolean = false) {
        if (isStopped || cancelled.get()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProgressTime < 750) return
        lastProgressTime = now
        val progress = percent.coerceIn(0, 100)
        val safeStatus = status.take(120)
        setProgressAsync(workDataOf("percent" to progress, "status" to safeStatus, "title" to title))
        setForegroundAsync(foregroundInfo(progress, safeStatus))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "YouTube downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun foregroundInfo(percent: Int, status: String): ForegroundInfo {
        val notificationId = (id.hashCode() and 0x3fffffff) + 1
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let { intent ->
            builder.setContentIntent(PendingIntent.getActivity(applicationContext, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, builder.build())
    }

    companion object {
        const val TAG = "youtube-download"
        private const val CHANNEL_ID = "youtube-downloads"

        fun enqueue(context: Context, url: String, title: String, format: DownloadFormat, destination: Uri): UUID {
            val normalizedUrl = YouTubeMediaPolicy.videoUrl(url)
            OfflineLibraryPolicy.destination(destination.toString())
            val request = OneTimeWorkRequestBuilder<YouTubeDownloadWorker>()
                .setInputData(workDataOf("url" to normalizedUrl, "title" to title.take(300), "format" to format.name,
                    "destination" to destination.toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .addTag("youtube-created:${System.currentTimeMillis()}")
                .addTag("youtube-format:${format.name}")
                .keepResultsForAtLeast(7, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
            return request.id
        }
    }
}
