package `is`.xyz.mpv.youtube

data class YouTubeSubscription(
    val channelId: String,
    val title: String,
    val lastRefreshedAt: Long = 0,
    val lastError: String? = null,
)

data class YouTubeVideo(
    val videoId: String,
    val channelId: String,
    val title: String,
    val channelTitle: String,
    val publishedAt: Long,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$videoId"
}

data class ChannelFeed(val channelId: String, val title: String, val videos: List<YouTubeVideo>)

data class SubscriptionSnapshot(
    val subscriptions: List<YouTubeSubscription> = emptyList(),
    val videos: List<YouTubeVideo> = emptyList(),
)

/** Pure cache rules shared by persistence and refresh; failed feeds retain their previous uploads. */
object SubscriptionFeeds {
    const val MAX_VIDEOS_PER_CHANNEL = 50
    const val MAX_CHANNELS = 100

    fun merge(
        previous: SubscriptionSnapshot,
        feeds: Map<String, Result<ChannelFeed>>,
        refreshedAt: Long,
    ): SubscriptionSnapshot {
        val subscriptions = previous.subscriptions.distinctBy { it.channelId }.map { subscription ->
            val result = feeds[subscription.channelId] ?: return@map subscription
            result.fold(
                onSuccess = { feed ->
                    require(feed.channelId == subscription.channelId) { "Unexpected channel in feed." }
                    subscription.copy(title = feed.title, lastRefreshedAt = refreshedAt, lastError = null)
                },
                onFailure = { subscription.copy(lastError = it.message?.take(240) ?: "Unable to refresh channel.") },
            )
        }
        val videos = subscriptions.flatMap { subscription ->
            val cached = previous.videos.filter { it.channelId == subscription.channelId }.associateBy { it.videoId }
            val latest = feeds[subscription.channelId]?.getOrNull()?.videos.orEmpty()
                .map { video ->
                    val knownDate = cached[video.videoId]?.publishedAt ?: 0
                    if (video.publishedAt <= 0 && knownDate > 0) video.copy(publishedAt = knownDate) else video
                }
            (latest + cached.values)
                .filter { it.channelId == subscription.channelId && YouTubeUrls.isVideoId(it.videoId) }
                .distinctBy { it.videoId }
                .sortedByDescending { it.publishedAt }
                .take(MAX_VIDEOS_PER_CHANNEL)
                .map { it.copy(channelTitle = subscription.title) }
        }.sortedByDescending { it.publishedAt }
            .distinctBy { it.videoId }
        return SubscriptionSnapshot(subscriptions, videos)
    }

    fun remove(previous: SubscriptionSnapshot, channelId: String): SubscriptionSnapshot =
        SubscriptionSnapshot(
            previous.subscriptions.filterNot { it.channelId == channelId },
            previous.videos.filterNot { it.channelId == channelId },
        )
}
