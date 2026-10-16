package `is`.xyz.mpv.youtube

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HongKongChannelsTest {
    @Test fun newInstallationGetsSixNamedPublicChannels() {
        val result = HongKongChannels.seed(SubscriptionSnapshot(), 0)
        assertEquals(1, result.appliedVersion)
        assertEquals(6, result.snapshot.subscriptions.size)
        assertEquals(HongKongChannels.channels.map { it.title }, result.snapshot.subscriptions.map { it.title })
        assertTrue(result.snapshot.subscriptions.all { YouTubeUrls.isChannelId(it.channelId) })
        assertEquals(6, result.snapshot.subscriptions.map { it.channelId }.distinct().size)
    }

    @Test fun seedPreservesManualSubscriptionsNamesAndVideosWithoutDuplicates() {
        val existingStarter = YouTubeSubscription(HongKongChannels.channels.first().channelId, "My saved name", 123)
        val manual = YouTubeSubscription(channelId(99), "Manual channel", 456)
        val video = YouTubeVideo("abcdefghijk", manual.channelId, "Cached", manual.title, 100)
        val previous = SubscriptionSnapshot(listOf(manual, existingStarter), listOf(video))
        val result = HongKongChannels.seed(previous, 0, starters = HongKongChannels.channels + HongKongChannels.channels)
        assertEquals(listOf(manual, existingStarter), result.snapshot.subscriptions.take(2))
        assertEquals(7, result.snapshot.subscriptions.size)
        assertEquals(listOf(video), result.snapshot.videos)
    }

    @Test fun seedUsesOnlyRemainingCapacityAndConsumesVersionAtFullCapacity() {
        val previous = SubscriptionSnapshot((0 until SubscriptionFeeds.MAX_CHANNELS - 1).map {
            YouTubeSubscription(channelId(it), "Manual $it")
        })
        val result = HongKongChannels.seed(previous, 0)
        assertEquals(SubscriptionFeeds.MAX_CHANNELS, result.snapshot.subscriptions.size)
        assertEquals(previous.subscriptions, result.snapshot.subscriptions.dropLast(1))
        assertEquals(HongKongChannels.channels.first().channelId, result.snapshot.subscriptions.last().channelId)
        val full = HongKongChannels.seed(result.snapshot, 0)
        assertEquals(result.snapshot, full.snapshot)
        assertEquals(HongKongChannels.SEED_VERSION, full.appliedVersion)
    }

    @Test fun removedStartersStayRemovedAcrossLoadsAndLaterSeedVersions() {
        val initial = HongKongChannels.seed(SubscriptionSnapshot(), 0)
        val removedId = initial.snapshot.subscriptions.first().channelId
        val removed = SubscriptionFeeds.remove(initial.snapshot, removedId)
        assertEquals(removed, HongKongChannels.seed(removed, initial.appliedVersion).snapshot)
        val newChannel = HongKongChannels.StarterChannel(channelId(999), "Later starter", introducedVersion = 2)
        val upgraded = HongKongChannels.seed(removed, initial.appliedVersion, targetVersion = 2,
            starters = HongKongChannels.channels + newChannel)
        assertFalse(upgraded.snapshot.subscriptions.any { it.channelId == removedId })
        assertEquals(newChannel.channelId, upgraded.snapshot.subscriptions.last().channelId)
        assertEquals(2, upgraded.appliedVersion)
    }

    @Test fun anOlderAppDoesNotResetANewerSeedMarker() {
        val previous = SubscriptionSnapshot(listOf(YouTubeSubscription(channelId(1), "Existing")))
        val result = HongKongChannels.seed(previous, appliedVersion = 3)
        assertEquals(previous, result.snapshot)
        assertEquals(3, result.appliedVersion)
    }

    @Test fun completedChannelRefreshPreservesOtherCachesAndSurvivesLaterFailure() {
        val first = channelId(1)
        val second = channelId(2)
        val oldFirst = YouTubeVideo("abcdefghijk", first, "First old", "First", 10)
        val oldSecond = YouTubeVideo("zyxwvutsrqp", second, "Second old", "Second", 20)
        val latest = YouTubeVideo("01234567890", first, "First new", "First", 30)
        val previous = SubscriptionSnapshot(listOf(YouTubeSubscription(first, "First"), YouTubeSubscription(second, "Second", 50)),
            listOf(oldFirst, oldSecond))
        val completed = SubscriptionFeeds.merge(previous, mapOf(first to Result.success(ChannelFeed(first, "First", listOf(latest)))), 100)
        assertEquals(setOf(latest.videoId, oldFirst.videoId, oldSecond.videoId), completed.videos.map { it.videoId }.toSet())
        assertEquals(previous.subscriptions[1], completed.subscriptions[1])
        val failed = SubscriptionFeeds.merge(completed, mapOf(second to Result.failure<ChannelFeed>(IOException("Offline"))), 200)
        assertEquals(completed.videos, failed.videos)
        assertEquals(completed.subscriptions[0], failed.subscriptions[0])
        assertEquals("Offline", failed.subscriptions[1].lastError)
    }

    private fun channelId(index: Int) = "UC${index.toString().padStart(22, '0')}"
}
