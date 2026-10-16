package `is`.xyz.mpv.youtube

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

data class ResolvedVideo(
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val httpHeaders: Map<String, String> = emptyMap()
)

/** All operations except cancel are blocking and must run off the main thread. */
object YouTubeEngine {
    @Volatile private var initialized = false
    private val initializationLock = Any()
    private val runtimeLock = ReentrantReadWriteLock()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val earlyCancellations = LinkedHashSet<String>()
    private val cancellationExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "youtube-cancellation").apply { isDaemon = true }
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                initialized = true
            } catch (error: Exception) {
                throw IllegalStateException("The YouTube tools could not start. Restart the app and try again.", error)
            }
        }
    }

    fun resolvePlayback(context: Context, url: String, processId: String): ResolvedVideo =
        withSession(processId) { session ->
            val normalizedUrl = YouTubeMediaPolicy.videoUrl(url)
            initialize(context)
            session.checkCancelled()
            val info = readInfo(normalizedUrl, "bestvideo+bestaudio/best", session, checkFormats = true)
            val type = info.optString("_type")
            require(type.isEmpty() || type == "video") { "This link does not identify a single video." }
            val requestedFormats = info.optJSONArray("requested_formats")
            val streams = if (requestedFormats != null && requestedFormats.length() > 0) {
                (0 until requestedFormats.length()).map { requestedFormats.getJSONObject(it) }
            } else listOf(info)
            val video = streams.firstOrNull { hasCodec(it, "vcodec") }
                ?: throw IllegalStateException("No playable video stream was found. Update the YouTube tools and try again.")
            val audio = if (hasCodec(video, "acodec")) null else
                streams.firstOrNull { hasCodec(it, "acodec") && !hasCodec(it, "vcodec") }
                    ?: throw IllegalStateException("No playable audio stream was found. Update the YouTube tools and try again.")
            val headers = linkedMapOf<String, String>()
            readHeaders(info, headers)
            readHeaders(video, headers)
            audio?.let { readHeaders(it, headers) }
            session.checkCancelled()
            ResolvedVideo(
                title = info.optString("title").ifBlank { "YouTube video" }.take(300),
                videoUrl = playbackUrl(video),
                audioUrl = audio?.let(::playbackUrl),
                httpHeaders = YouTubeMediaPolicy.checkedHeaders(headers)
            )
        }

    /** Public uploads playlist, used when YouTube's RSS feed is unavailable. */
    fun recentUploads(context: Context, channelId: String, processId: String): ChannelFeed =
        withSession(processId) { session ->
            val url = YouTubeMediaPolicy.uploadsUrl(channelId)
            initialize(context)
            session.checkCancelled()
            val request = baseRequest(url, singleVideo = false)
                .addOption("--flat-playlist")
                .addOption("--playlist-end", 15)
                .addOption("--dump-single-json")
                .addOption("--skip-download")
                .addOption("--extractor-args", "youtubetab:approximate_date")
            val response = YoutubeDL.getInstance().execute(request, processId = session.id, callback = null)
            session.checkCancelled()
            val info = try {
                JSONObject(response.out)
            } catch (error: Exception) {
                throw IllegalStateException("YouTube returned unreadable channel information. Update the YouTube tools and try again.", error)
            }
            parseRecentUploads(channelId, info)
        }

    fun update(context: Context): String {
        val updateLock = runtimeLock.writeLock()
        check(updateLock.tryLock()) { "Wait for active YouTube playback loading and downloads to finish before updating the tools." }
        return try {
            initialize(context)
            when (YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)) {
                YoutubeDL.UpdateStatus.DONE -> "YouTube tools updated."
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "YouTube tools are already up to date."
                null -> throw IllegalStateException("The YouTube tools update did not finish. Try again.")
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not update the YouTube tools. ${readableError(error)}", error)
        } finally {
            updateLock.unlock()
        }
    }

    fun cancel(processId: String) {
        YouTubeMediaPolicy.processId(processId)
        synchronized(earlyCancellations) {
            earlyCancellations.add(processId)
            // IDs are unique per operation. Bound markers for operations cancelled before they start.
            if (earlyCancellations.size > 128) earlyCancellations.remove(earlyCancellations.first())
            sessions[processId]?.cancelled?.set(true)
        }
        destroyProcess(processId)
    }

    fun download(
        context: Context,
        url: String,
        format: DownloadFormat,
        directory: File,
        processId: String,
        onProgress: (Int, String) -> Unit
    ): File = withSession(processId) { session ->
        val normalizedUrl = YouTubeMediaPolicy.videoUrl(url)
        val id = normalizedUrl.substringAfter("v=")
        val staging = directory.canonicalFile
        require((staging.isDirectory || staging.mkdirs()) && staging.listFiles()?.isEmpty() == true) {
            "Could not create an empty temporary download folder."
        }
        initialize(context)
        session.checkCancelled()
        onProgress(0, "Reading video information")
        val selection = when (format) {
            DownloadFormat.MP4 -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo+bestaudio/best"
            DownloadFormat.MP3 -> "bestaudio/best"
        }
        val info = readInfo(normalizedUrl, selection, session)
        val duration = info.optDouble("duration", Double.NaN)
        require(!info.optBoolean("is_live") && info.optString("live_status") !in
            setOf("is_live", "is_upcoming", "post_live") && duration.isFinite() && duration > 0) {
            "Download a finished video. Live streams, upcoming videos, and streams still being processed cannot be downloaded."
        }
        val request = baseRequest(normalizedUrl)
            .addOption("--format", selection)
            .addOption("--output", File(staging, "%(id)s.%(ext)s").absolutePath)
            .addOption("--restrict-filenames")
            .addOption("--no-overwrites")
            .addOption("--no-keep-video")
            .addOption("--abort-on-unavailable-fragments")
            .addOption("--match-filters", "!is_live & duration > 0")
            .addOption("--newline")
        when (format) {
            DownloadFormat.MP4 -> request.addOption("--merge-output-format", "mp4").addOption("--recode-video", "mp4")
            DownloadFormat.MP3 -> request.addOption("--extract-audio").addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "0")
        }
        session.checkCancelled()
        YoutubeDL.getInstance().execute(request, processId = session.id) { progress, _, line ->
            if (!session.cancelled.get()) {
                val percent = if (progress.isFinite()) progress.toInt().coerceIn(0, 99) else 0
                val status = if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
                    line.startsWith("[VideoConvertor]") || line.startsWith("[VideoRemuxer]")) {
                    "Preparing ${format.name} file"
                } else "Downloading ${format.name}"
                // Library callbacks run on its stream reader. Do not let UI/progress failures kill that thread.
                runCatching { onProgress(percent, status) }
            }
        }
        session.checkCancelled()
        val output = File(staging, "$id.${format.extension}").canonicalFile
        val candidates = staging.listFiles().orEmpty().filter { it.isFile && it.extension == format.extension }
        check(output.parentFile == staging && candidates.size == 1 && candidates.single().canonicalFile == output &&
            output.isFile && output.length() > 0) {
            "The download did not produce a complete ${format.name} file. Update the YouTube tools and try again."
        }
        if (format == DownloadFormat.MP4) {
            val header = ByteArray(12)
            val bytesRead = output.inputStream().use { it.read(header) }
            check(bytesRead >= 8 && header.copyOfRange(4, 8).contentEquals(byteArrayOf(102, 116, 121, 112))) {
                "The downloaded file is not an MP4 container."
            }
        }
        onProgress(99, "Download complete; saving file")
        output
    }

    private fun baseRequest(url: String, singleVideo: Boolean = true) = YoutubeDLRequest(url)
        .apply { if (singleVideo) addOption("--no-playlist") }
        .addOption("--ignore-config")
        .addOption("--socket-timeout", 20)
        .addOption("--retries", 3)
        .addOption("--fragment-retries", 3)
        .addOption("--extractor-retries", 2)
        .addOption("--file-access-retries", 2)
        .addOption("--no-mtime")

    private fun parseRecentUploads(channelId: String, info: JSONObject): ChannelFeed {
        val playlistId = "UU${channelId.drop(2)}"
        val sourceChannel = jsonText(info, "channel_id")
        require(jsonText(info, "_type") == "playlist" && jsonText(info, "id") == playlistId &&
            (sourceChannel.isEmpty() || sourceChannel == channelId)) {
            "YouTube returned a different channel or an invalid uploads list."
        }
        require(!restrictedAvailability(jsonText(info, "availability"))) {
            "This channel's uploads are not publicly available without signing in."
        }
        val entries = info.optJSONArray("entries")
            ?: throw IllegalStateException("YouTube did not return an uploads list. Update the YouTube tools and try again.")
        val publicEntries = (0 until minOf(entries.length(), 15)).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = jsonText(entry, "id")
            val title = jsonText(entry, "title")
            val entryChannel = jsonText(entry, "channel_id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) || title.isEmpty() ||
                (entryChannel.isNotEmpty() && entryChannel != channelId) ||
                restrictedAvailability(jsonText(entry, "availability")) ||
                title.equals("[Private video]", ignoreCase = true) || title.equals("[Deleted video]", ignoreCase = true)) {
                null
            } else entry
        }
        require(entries.length() == 0 || publicEntries.isNotEmpty()) {
            "YouTube did not return any readable public uploads for this channel."
        }
        val title = (publicEntries.firstNotNullOfOrNull { entry ->
            jsonText(entry, "channel").ifEmpty { jsonText(entry, "uploader") }.takeIf(String::isNotEmpty)
        } ?: jsonText(info, "channel").takeIf(String::isNotEmpty)
            ?: jsonText(info, "title").removePrefix("Uploads from ").takeIf(String::isNotEmpty)
            ?: channelId).take(300)
        val videos = publicEntries.map { entry ->
            YouTubeVideo(
                videoId = jsonText(entry, "id"),
                channelId = channelId,
                title = jsonText(entry, "title").take(500),
                channelTitle = title,
                publishedAt = YouTubeMediaPolicy.uploadTime(
                    (entry.opt("timestamp") as? Number)?.toDouble(),
                    (entry.opt("release_timestamp") as? Number)?.toDouble(),
                    jsonText(entry, "upload_date")
                )
            )
        }.distinctBy { it.videoId }.sortedByDescending { it.publishedAt }
        return ChannelFeed(channelId, title, videos)
    }

    private fun jsonText(json: JSONObject, name: String): String = (json.opt(name) as? String)?.trim().orEmpty()

    private fun restrictedAvailability(value: String): Boolean = value in
        setOf("private", "premium_only", "subscriber_only", "needs_auth", "unavailable")

    private fun readInfo(url: String, format: String, session: Session, checkFormats: Boolean = false): JSONObject {
        session.checkCancelled()
        val request = baseRequest(url).addOption("--dump-single-json").addOption("--skip-download")
            .addOption("--format", format)
        // Metadata can contain expired or otherwise unusable CDN candidates. Let yt-dlp
        // verify playback candidates before handing their URLs to the native player.
        if (checkFormats) request.addOption("--check-formats")
        val response = YoutubeDL.getInstance().execute(request, processId = session.id, callback = null)
        session.checkCancelled()
        return try {
            JSONObject(response.out)
        } catch (error: Exception) {
            throw IllegalStateException("YouTube returned unreadable video information. Update the YouTube tools and try again.", error)
        }
    }

    private fun hasCodec(info: JSONObject, name: String): Boolean =
        info.optString(name).let { it.isNotBlank() && it != "none" && it != "null" }

    private fun playbackUrl(info: JSONObject): String {
        val protocol = info.optString("protocol")
        require(protocol.isEmpty() || protocol in setOf("https", "http", "m3u8", "m3u8_native")) {
            "YouTube returned a stream format that cannot be played directly. Update the YouTube tools and try again."
        }
        return YouTubeMediaPolicy.streamUrl(info.optString("url"))
    }

    private fun readHeaders(info: JSONObject, headers: MutableMap<String, String>) {
        val json = info.optJSONObject("http_headers") ?: return
        json.keys().forEach { name ->
            if (!json.isNull(name)) {
                // Header names are case-insensitive; avoid passing duplicate variants to mpv.
                headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
                headers[name] = json.getString(name)
            }
        }
    }

    private class Session(val id: String) {
        val cancelled = AtomicBoolean(false)
        fun checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("YouTube operation cancelled.")
        }
    }

    private fun <T> withSession(processId: String, action: (Session) -> T): T {
        val session = Session(YouTubeMediaPolicy.processId(processId))
        synchronized(earlyCancellations) {
            check(sessions.putIfAbsent(processId, session) == null) { "This YouTube operation is already running." }
            if (earlyCancellations.remove(processId)) session.cancelled.set(true)
        }
        // Covers cancellation during initialization or just before the native process is registered.
        val cancellationWatch = cancellationExecutor.scheduleWithFixedDelay({
            if (session.cancelled.get()) destroyProcess(processId)
        }, 100, 250, TimeUnit.MILLISECONDS)
        val operationLock = runtimeLock.readLock()
        var locked = false
        return try {
            session.checkCancelled()
            operationLock.lockInterruptibly()
            locked = true
            session.checkCancelled()
            action(session)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (session.cancelled.get() || error is CancellationException || error is InterruptedException ||
                error is YoutubeDL.CanceledException) {
                destroyProcess(processId)
                throw CancellationException("YouTube operation cancelled.").apply { initCause(error) }
            }
            throw IllegalStateException(readableError(error), error)
        } finally {
            if (locked) operationLock.unlock()
            cancellationWatch.cancel(false)
            synchronized(earlyCancellations) {
                sessions.remove(processId, session)
                earlyCancellations.remove(processId)
            }
        }
    }

    private fun destroyProcess(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    private fun readableError(error: Exception): String {
        val lines = error.message.orEmpty().replace(Regex("\u001B\\[[0-9;]*m"), "").lineSequence()
            .map(String::trim).filter(String::isNotBlank).toList()
        return (lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.firstOrNull()
            ?: "YouTube could not complete the request. Check your connection or update the YouTube tools.")
            .removePrefix("ERROR:").trim().take(800)
    }
}
