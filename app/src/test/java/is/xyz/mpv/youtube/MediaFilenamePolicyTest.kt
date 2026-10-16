package `is`.xyz.mpv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilenamePolicyTest {
    @Test fun preservesChineseEmojiAndOriginalSpacing() {
        val title = "小薯茄 🍟  👩‍💻 新影片"
        assertEquals("$title.mp4", MediaFilenamePolicy.filename(title, "mp4"))
    }

    @Test fun removesReservedCharactersControlsAndUnsafeDirectionalOverrides() {
        assertEquals("abcdefghij.mp3", MediaFilenamePolicy.filename("a/b\\c:d*e?f\"g<h>i|j\n\u0000\u0085\u202E", "mp3"))
        assertEquals("good😀.mp4", MediaFilenamePolicy.filename("\uD83Dgood😀\uDE00", "mp4"))
    }

    @Test fun addsTheMediaExtensionExactlyOnce() {
        assertEquals("A video.mp4", MediaFilenamePolicy.filename("A video.MP4.mp4  ", ".MP4"))
        assertEquals("Song.mp3", MediaFilenamePolicy.filename("Song.mp3", "mp3"))
        assertEquals("extensionless", MediaFilenamePolicy.filename("extensionless", ""))
    }

    @Test fun protectsReservedDeviceNamesAndFallsBackForEmptyNames() {
        listOf("CON", "nul", "AUX.txt", "COM1", "LPT³").forEach {
            assertEquals("_$it.mp4", MediaFilenamePolicy.filename(it, "mp4"))
        }
        assertEquals("BaW_jenozKc.mp4", MediaFilenamePolicy.filename("../\\:*?\"<>|..", "mp4", "BaW_jenozKc"))
        assertEquals("BaW_jenozKc.mp4", MediaFilenamePolicy.filename(".mp4", "mp4", "BaW_jenozKc"))
        assertEquals("video.mp3", MediaFilenamePolicy.filename("", "mp3", "..."))
        assertEquals("title.mp4", MediaFilenamePolicy.filename(" .title.  ", "mp4"))
    }

    @Test fun limitsTheWholeFilenameInUtf8WithoutSplittingCodePoints() {
        listOf("a".repeat(500), "小😀".repeat(100), "🍟".repeat(100), "漢".repeat(100)).forEach { title ->
            val filename = MediaFilenamePolicy.filename(title, "mp4")
            assertTrue(filename.toByteArray(Charsets.UTF_8).size <= 240)
            assertEquals(filename, String(filename.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            assertTrue(filename.endsWith(".mp4"))
            assertFalse(filename.contains('\uFFFD'))
            assertEquals(filename, MediaFilenamePolicy.filename(filename, "mp4"))
        }
        assertEquals(240, MediaFilenamePolicy.filename("🍟".repeat(100), "mp4").toByteArray(Charsets.UTF_8).size)
    }

    @Test fun rechecksDeviceNamesAfterTruncatingTrailingSpaces() {
        val title = "CON" + " ".repeat(250) + "x"
        assertEquals("_CON.mp4", MediaFilenamePolicy.filename(title, "mp4"))
    }

    @Test fun rejectsExtensionsThatCouldAddAPathOrAnotherSuffix() {
        listOf("../mp4", "mp4/evil", "mp4.mp3", "mp3\n", "a".repeat(17)).forEach {
            assertThrows(IllegalArgumentException::class.java) { MediaFilenamePolicy.filename("Video", it) }
        }
    }
}
