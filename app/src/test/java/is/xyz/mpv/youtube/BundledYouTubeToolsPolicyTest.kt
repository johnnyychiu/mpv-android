package `is`.xyz.mpv.youtube

import org.junit.Assert.*
import org.junit.Test

class BundledYouTubeToolsPolicyTest {
    private val minimum = "2026.08.19"

    @Test fun freshWrapperRuntimeAndUnknownRestoredRuntimeAreReplaced() {
        assertTrue(install("2025.11.12"))
        assertTrue(install(null))
        assertTrue(install("unknown"))
        // An old install marker is deliberately not an input: it cannot prove the
        // no-backup runtime survived data restoration or an interrupted reinstall.
    }

    @Test fun matchingVerifiedBinaryDoesNotNeedMetadataOrAnotherCopy() {
        assertFalse(BundledYouTubeToolsPolicy.shouldInstall(true, true, listOf(null), minimum))
    }

    @Test fun confirmedEqualOrNewerStableAndNightlyUpdatesArePreserved() {
        assertFalse(install("2026.08.19"))
        assertFalse(install("yt-dlp 2026.09.01"))
        assertFalse(install("2026.10.16.232959"))
        assertFalse(install("Nightly 2027.01.01.123456"))
        assertTrue(install("2026.08.18.235959"))
    }

    @Test fun validReleaseTagCanIdentifyAnUpdateWhenItsNameCannot() {
        assertFalse(BundledYouTubeToolsPolicy.shouldInstall(true, false, listOf("Nightly build", "2026.10.16.123456"), minimum))
        assertTrue(BundledYouTubeToolsPolicy.shouldInstall(true, false, listOf("Nightly build", "a45ce7f"), minimum))
    }

    @Test fun missingRuntimeIsInstalledEvenWhenUpdateMetadataWasRestored() {
        assertTrue(BundledYouTubeToolsPolicy.shouldInstall(false, false, listOf("2027.01.01"), minimum))
    }

    @Test fun recreatedLegacyBinaryOverridesStaleNewerUpdateMetadata() {
        assertTrue(BundledYouTubeToolsPolicy.shouldInstall(true, false, listOf("2027.01.01"), minimum, matchesLegacyRuntime = true))
        assertFalse(BundledYouTubeToolsPolicy.shouldInstall(true, true, listOf("2027.01.01"), minimum, matchesLegacyRuntime = true))
    }

    @Test fun invalidDatesDoNotPretendToBeNewerVersions() {
        listOf("2027.02.30", "2027.13.01", "2027.01.00", "12027.01.01", "2027.01.011", "0000.01.01").forEach {
            assertTrue(it, install(it))
        }
    }

    private fun install(version: String?) = BundledYouTubeToolsPolicy.shouldInstall(true, false, listOf(version), minimum)
}
