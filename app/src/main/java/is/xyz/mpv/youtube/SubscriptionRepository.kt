package `is`.xyz.mpv.youtube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/** Called on a single background executor. The only cross-thread operation is cancel(). */
class SubscriptionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("youtube_subscriptions", Context.MODE_PRIVATE)
    private val client = PublicYouTubeClient()
    private var snapshot: SubscriptionSnapshot? = null
    private var appliedStarterVersion = 0
    @Volatile private var feedProcessId: String? = null

    fun load(): SubscriptionSnapshot {
        snapshot?.let { return it }
        val (saved, version) = readSavedSnapshot()
        val seeded = HongKongChannels.seed(saved, version)
        return if (seeded.appliedVersion != version) {
            // The list and marker are one atomic preference value. A failed read or save must
            // not consume the seed or replace the user's existing subscriptions.
            save(seeded.snapshot, seeded.appliedVersion)
        } else {
            appliedStarterVersion = version
            saved.also { snapshot = it }
        }
    }

    private fun readSavedSnapshot(): Pair<SubscriptionSnapshot, Int> {
        val encoded = preferences.getString("snapshot", null) ?: return SubscriptionSnapshot() to 0
        require(encoded.length <= MAX_CACHE_CHARS) { "Saved channel data is too large to read." }
        val root = try { JSONObject(encoded) } catch (error: Exception) {
            throw IOException("Saved channel data could not be read.", error)
        }
        require(root.optInt("version", 0) == 1) { "Saved channel data uses an unsupported format." }
        val channels = root.optJSONArray("channels") ?: throw IOException("Saved channel data is missing its channel list.")
        val items = root.optJSONArray("videos") ?: throw IOException("Saved channel data is missing its video list.")
        val starterVersion = if (root.has(STARTER_VERSION_KEY)) {
            val value = root.opt(STARTER_VERSION_KEY)
            require(value is Number && value.toInt() >= 0 && value.toDouble() == value.toInt().toDouble()) {
                "Saved channel data has an invalid starter version."
            }
            value.toInt()
        } else 0
        val subscriptions = (0 until minOf(channels.length(), SubscriptionFeeds.MAX_CHANNELS)).mapNotNull { index ->
            val item = channels.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!YouTubeUrls.isChannelId(id)) return@mapNotNull null
            YouTubeSubscription(id, item.optString("title", id).take(300), item.optLong("refreshed"),
                item.optString("error").takeIf { it.isNotBlank() }?.take(240))
        }.distinctBy { it.channelId }
        val subscribedIds = subscriptions.map { it.channelId }.toSet()
        val videos = (0 until minOf(items.length(), SubscriptionFeeds.MAX_CHANNELS * SubscriptionFeeds.MAX_VIDEOS_PER_CHANNEL)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val channelId = item.optString("channel")
            if (!YouTubeUrls.isVideoId(id) || channelId !in subscribedIds) return@mapNotNull null
            YouTubeVideo(id, channelId, item.optString("title").take(500), item.optString("channelTitle").take(300), item.optLong("published"))
        }
        return SubscriptionFeeds.merge(SubscriptionSnapshot(subscriptions, videos), emptyMap(), 0) to starterVersion
    }

    fun addChannel(input: String): SubscriptionSnapshot {
        val previous = load()
        require(previous.subscriptions.size < SubscriptionFeeds.MAX_CHANNELS) { "You can follow up to ${SubscriptionFeeds.MAX_CHANNELS} channels." }
        val link = YouTubeUrls.channel(input)
        val id = link.channelId ?: YouTubeFeedParser.channelIdFromPage(client.get(link.url, YouTubeFeedParser.MAX_PAGE_BYTES))
        require(previous.subscriptions.none { it.channelId == id }) { "This channel is already in your subscriptions." }
        val result = fetchFeed(id)
        val initial = previous.copy(subscriptions = previous.subscriptions + YouTubeSubscription(id, id))
        return save(SubscriptionFeeds.merge(initial, mapOf(id to result), System.currentTimeMillis()))
    }

    fun refresh(onProgress: (Int, Int, SubscriptionSnapshot) -> Unit = { _, _, _ -> }): SubscriptionSnapshot {
        var current = load()
        val subscriptions = current.subscriptions
        onProgress(0, subscriptions.size, current)
        subscriptions.forEachIndexed { index, subscription ->
            ensureActive()
            val result = fetchFeed(subscription.channelId)
            ensureActive()
            current = save(SubscriptionFeeds.merge(current, mapOf(subscription.channelId to result), System.currentTimeMillis()))
            // Persist before publishing: cancellation keeps completed channels and the prior
            // cached videos for every channel whose request has not completed.
            onProgress(index + 1, subscriptions.size, current)
        }
        ensureActive()
        return current
    }

    fun removeChannel(channelId: String): SubscriptionSnapshot = save(SubscriptionFeeds.remove(load(), channelId))

    fun cancel() {
        client.cancel()
        feedProcessId?.let(YouTubeEngine::cancel)
    }

    private fun fetchFeed(channelId: String): Result<ChannelFeed> {
        val rssError = try {
            return Result.success(YouTubeFeedParser.parseRss(client.get(YouTubeUrls.feedUrl(channelId), YouTubeFeedParser.MAX_FEED_BYTES), channelId))
        } catch (error: Exception) {
            ensureActive()
            if (error is CancellationException) throw error
            error
        }
        // Some regions/channels return 404 for otherwise public feeds. The bounded public
        // uploads-playlist extractor provides a fallback without a Google API or account.
        val id = "feed-${UUID.randomUUID()}"
        feedProcessId = id
        return try {
            ensureActive()
            YouTubeEngine.initialize(appContext)
            ensureActive()
            Result.success(YouTubeEngine.recentUploads(appContext, channelId, id))
        } catch (error: Exception) {
            ensureActive()
            if (error is CancellationException) throw error
            Result.failure(IOException("RSS: ${rssError.message.orEmpty().take(100)}; yt-dlp: ${error.message.orEmpty().take(100)}", error))
        } finally {
            if (feedProcessId == id) feedProcessId = null
        }
    }

    private fun save(value: SubscriptionSnapshot, starterVersion: Int = appliedStarterVersion): SubscriptionSnapshot {
        ensureActive()
        val channels = JSONArray()
        value.subscriptions.forEach { channel ->
            channels.put(JSONObject().put("id", channel.channelId).put("title", channel.title)
                .put("refreshed", channel.lastRefreshedAt).put("error", channel.lastError.orEmpty()))
        }
        val videos = JSONArray()
        value.videos.forEach { video ->
            videos.put(JSONObject().put("id", video.videoId).put("channel", video.channelId).put("title", video.title)
                .put("channelTitle", video.channelTitle).put("published", video.publishedAt))
        }
        val encoded = JSONObject().put("version", 1).put(STARTER_VERSION_KEY, starterVersion)
            .put("channels", channels).put("videos", videos).toString()
        require(encoded.length <= MAX_CACHE_CHARS) { "Saved channel data is too large." }
        ensureActive()
        if (!preferences.edit().putString("snapshot", encoded).commit()) throw IOException("The channel list could not be saved.")
        appliedStarterVersion = starterVersion
        snapshot = value
        return value
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Cancelled")
    }

    private companion object {
        const val MAX_CACHE_CHARS = 6 * 1024 * 1024
        const val STARTER_VERSION_KEY = "hongKongStarterVersion"
    }
}

/** Public requests with an anonymous reject-optional-cookies choice; no account or Google API. */
private class PublicYouTubeClient {
    @Volatile private var connection: HttpsURLConnection? = null

    fun cancel() { connection?.disconnect() }

    fun get(url: String, limit: Int): String {
        var uri = URI(url)
        val deadline = System.nanoTime() + 45_000_000_000L
        repeat(4) { redirect ->
            checkActive(deadline)
            require(YouTubeUrls.isPublicYouTubeUri(uri)) { "Only public HTTPS YouTube pages can be read." }
            val active = uri.toURL().openConnection() as HttpsURLConnection
            connection = active
            try {
                active.connectTimeout = 12_000
                active.readTimeout = 12_000
                active.instanceFollowRedirects = false
                active.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) mpv-android-subscriptions/1.0")
                active.setRequestProperty("Accept", "application/atom+xml,text/html;q=0.9")
                active.setRequestProperty("Accept-Encoding", "identity")
                active.setRequestProperty("Cookie", "SOCS=CAI")
                val status = active.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(redirect < 3) { "YouTube redirected too many times." }
                    val location = active.getHeaderField("Location") ?: throw IOException("YouTube returned an invalid redirect.")
                    uri = uri.resolve(location)
                    require(YouTubeUrls.isPublicYouTubeUri(uri)) { "YouTube requested a sign-in or consent page. Try a /channel/UC… link." }
                    return@repeat
                }
                if (status !in 200..299) throw IOException("YouTube returned HTTP $status. Try refreshing later.")
                require(active.contentLengthLong <= limit) { "YouTube's response is too large." }
                val bytes = ByteArrayOutputStream(minOf(limit, 32 * 1024))
                active.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkActive(deadline)
                        val count = input.read(buffer)
                        if (count == -1) break
                        require(bytes.size() + count <= limit) { "YouTube's response is too large." }
                        bytes.write(buffer, 0, count)
                    }
                }
                checkActive(deadline)
                return bytes.toString("UTF-8")
            } finally {
                active.disconnect()
                if (connection === active) connection = null
            }
        }
        throw IOException("YouTube redirected too many times.")
    }

    private fun checkActive(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Cancelled")
        if (System.nanoTime() > deadline) throw IOException("YouTube request timed out. Try again.")
    }
}
