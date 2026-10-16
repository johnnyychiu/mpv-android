package `is`.xyz.mpv.youtube

/** Public channel IDs verified against the channels' own YouTube pages on 2026-10-16. */
internal object HongKongChannels {
    const val SEED_VERSION = 1

    data class StarterChannel(val channelId: String, val title: String, val introducedVersion: Int = 1)
    data class SeedResult(val snapshot: SubscriptionSnapshot, val appliedVersion: Int)

    val channels = listOf(
        StarterChannel("UCHQoZ0_MHDXIgWeRvPTLTJw", "Pomato 小薯茄"), // @pomatohk
        StarterChannel("UCa1d9ZXVMU0BZQRXZHt8Cpw", "Mill MILK"), // @mm.millmilk
        StarterChannel("UCWHojUvKOvV1xwCANqSsnUg", "FHProductionHK"), // @FHProductionHK
        StarterChannel("UCiJnCs2K5gP-DXnMxlstC9A", "毛記電視"), // @100MOSTHongKong
        StarterChannel("UCGKZBiAkSYd1v1O_5vFky7A", "米紙"), // @ricezi2hk
        StarterChannel("UCDpK1rg5I9Zc3ToY13vbR3w", "笑波子 SMILING BORIS"), // @smilingboris1118
    )

    /** Mark a seed version consumed even at capacity, so later removals are respected. */
    fun seed(
        previous: SubscriptionSnapshot,
        appliedVersion: Int,
        targetVersion: Int = SEED_VERSION,
        starters: List<StarterChannel> = channels,
    ): SeedResult {
        require(appliedVersion >= 0 && targetVersion >= 0)
        if (appliedVersion >= targetVersion) return SeedResult(previous, appliedVersion)
        val knownIds = previous.subscriptions.mapTo(mutableSetOf()) { it.channelId }
        val available = (SubscriptionFeeds.MAX_CHANNELS - previous.subscriptions.size).coerceAtLeast(0)
        val additions = starters.asSequence()
            .filter { it.introducedVersion > appliedVersion && it.introducedVersion <= targetVersion }
            .filter { YouTubeUrls.isChannelId(it.channelId) && it.title.isNotBlank() && knownIds.add(it.channelId) }
            .take(available)
            .map { YouTubeSubscription(it.channelId, it.title.take(300)) }
            .toList()
        return SeedResult(previous.copy(subscriptions = previous.subscriptions + additions), targetVersion)
    }
}
