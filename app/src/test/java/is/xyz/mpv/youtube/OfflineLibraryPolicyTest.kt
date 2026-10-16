package `is`.xyz.mpv.youtube

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OfflineLibraryPolicyTest {
    private val playlistId = id(9000)
    private val first = download(1)
    private val second = download(2)
    private val third = download(3)

    @Test fun recordIsIdempotentForWorkerRetriesAndBackfills() {
        val firstSave = OfflineLibraryPolicy.record(OfflineLibrarySnapshot(), first)
        assertEquals(firstSave, OfflineLibraryPolicy.record(firstSave, first))
        val repeatedImport = first.copy(id = id(99))
        assertEquals(firstSave, OfflineLibraryPolicy.record(firstSave, repeatedImport))
    }

    @Test fun duplicateDocumentPreservesItsIdentifierAndPlaylistReferences() {
        val initial = playlist(listOf(first), listOf(first.id))
        val newer = first.copy(id = id(99), title = "Renamed document", savedAt = 200)
        val result = OfflineLibraryPolicy.record(initial, newer)
        assertEquals(listOf(newer.copy(id = first.id)), result.downloads)
        assertEquals(initial.playlists, result.playlists)
        assertEquals(result, OfflineLibraryPolicy.record(result, first))
    }

    @Test fun conflictingWorkerIdentifierDoesNotReplaceAnotherDocument() {
        val initial = OfflineLibrarySnapshot(listOf(first))
        assertThrows(IllegalArgumentException::class.java) {
            OfflineLibraryPolicy.record(initial, second.copy(id = first.id))
        }
        assertEquals(listOf(first), initial.downloads)
    }

    @Test fun serializedCompletionAndPlaylistEditsPreserveBothChanges() {
        var current = playlist(listOf(first), listOf(first.id))
        current = OfflineLibraryPolicy.record(current, second)
        current = OfflineLibraryPolicy.renamePlaylist(current, playlistId, "Renamed")
        current = OfflineLibraryPolicy.record(current, third)
        assertEquals(listOf(first, second, third), current.downloads)
        assertEquals("Renamed", current.playlists.single().name)
        assertEquals(listOf(first.id), current.playlists.single().downloadIds)
        val reverse = OfflineLibraryPolicy.record(OfflineLibraryPolicy.record(OfflineLibrarySnapshot(), second), first)
        val forward = OfflineLibraryPolicy.record(OfflineLibraryPolicy.record(OfflineLibrarySnapshot(), first), second)
        assertEquals(reverse.downloads.toSet(), forward.downloads.toSet())
    }

    @Test fun createAndRenamePreserveOtherPlaylistsAndDownloads() {
        val initial = playlist(listOf(first), listOf(first.id))
        val created = OfflineLibraryPolicy.createPlaylist(initial, id(9001), "  音樂 😀  ")
        val renamed = OfflineLibraryPolicy.renamePlaylist(created, id(9001), "  My music  ")
        assertEquals(initial.downloads, renamed.downloads)
        assertEquals(initial.playlists.first(), renamed.playlists.first())
        assertEquals("My music", renamed.playlists.last().name)
        assertTrue(renamed.playlists.last().downloadIds.isEmpty())
    }

    @Test fun addingAndRemovingItemsPreservesOrderAndSavedFiles() {
        var current = playlist(listOf(first, second, third), listOf(first.id))
        current = OfflineLibraryPolicy.addToPlaylist(current, playlistId, second.id)
        current = OfflineLibraryPolicy.addToPlaylist(current, playlistId, third.id)
        assertEquals(current, OfflineLibraryPolicy.addToPlaylist(current, playlistId, second.id))
        current = OfflineLibraryPolicy.removeFromPlaylist(current, playlistId, second.id)
        assertEquals(listOf(first.id, third.id), current.playlists.single().downloadIds)
        assertEquals(listOf(first, second, third), current.downloads)
        val deleted = OfflineLibraryPolicy.deletePlaylist(current, playlistId)
        assertTrue(deleted.playlists.isEmpty())
        assertEquals(current.downloads, deleted.downloads)
    }

    @Test fun movingItemsUsesStableOrderAndClampsWithoutIntegerOverflow() {
        val initial = playlist(listOf(first, second, third), listOf(first.id, second.id, third.id))
        val moved = OfflineLibraryPolicy.movePlaylistItem(initial, playlistId, third.id, -1)
        assertEquals(listOf(first.id, third.id, second.id), moved.playlists.single().downloadIds)
        val last = OfflineLibraryPolicy.movePlaylistItem(moved, playlistId, first.id, Int.MAX_VALUE)
        assertEquals(listOf(third.id, second.id, first.id), last.playlists.single().downloadIds)
        val firstAgain = OfflineLibraryPolicy.movePlaylistItem(last, playlistId, first.id, Int.MIN_VALUE)
        assertEquals(listOf(first.id, third.id, second.id), firstAgain.playlists.single().downloadIds)
    }

    @Test fun rejectsUnsafeDocumentSchemesAuthoritiesAndPlaylistLineInjection() {
        listOf(
            "https://example.org/video.mp4", "file:///sdcard/video.mp4", "memory://#EXTM3U",
            "content:opaque", "content:///missing-authority", "content://user@provider/document/1",
            "content://provider:123/document/1", "content://provider/document/1#fragment",
            "content://provider/document/1\nhttps://example.org/extra", "content://provider/document/1\r",
            "content://provider/document/1\u0000", "content://provider/document/1\t", "content://provider/document/\\1",
            "content://provider/document/" + "a".repeat(OfflineLibraryPolicy.MAX_URI_CHARS)
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { OfflineLibraryPolicy.destination(value) }
        }
        val valid = "content://com.android.providers.downloads.documents/document/a%20b%0Aname"
        // Encoded bytes remain one M3U line; the player separately validates any translated path.
        assertEquals(valid, OfflineLibraryPolicy.destination(valid))
    }

    @Test fun playlistEntryLimitDoesNotEvictExistingItems() {
        val files = (1..OfflineLibraryPolicy.MAX_PLAYLIST_ENTRIES + 1).map(::download)
        val full = playlist(files, files.dropLast(1).map { it.id })
        assertEquals(full, OfflineLibraryPolicy.addToPlaylist(full, playlistId, files.first().id))
        assertThrows(IllegalArgumentException::class.java) {
            OfflineLibraryPolicy.addToPlaylist(full, playlistId, files.last().id)
        }
        assertEquals(OfflineLibraryPolicy.MAX_PLAYLIST_ENTRIES, full.playlists.single().downloadIds.size)
    }

    @Test fun catalogAndPlaylistCountLimitsNeverDiscardExistingRecords() {
        val files = (1..OfflineLibraryPolicy.MAX_DOWNLOADS).map(::download)
        val full = OfflineLibrarySnapshot(files)
        assertEquals(full, OfflineLibraryPolicy.record(full, files.first()))
        assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.record(full, download(2000)) }
        val lists = (1..OfflineLibraryPolicy.MAX_PLAYLISTS).map { OfflinePlaylist(id(9000 + it), "List $it", emptyList()) }
        val fullLists = full.copy(playlists = lists)
        assertThrows(IllegalArgumentException::class.java) {
            OfflineLibraryPolicy.createPlaylist(fullLists, playlistId, "Extra")
        }
        assertEquals(files, fullLists.downloads)
        assertEquals(lists, fullLists.playlists)
    }

    @Test fun rejectsInvalidNamesAndUnknownMembership() {
        listOf("", "   ", "a\nb", "a\u0000b", "a".repeat(OfflineLibraryPolicy.MAX_PLAYLIST_NAME_CHARS + 1)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                OfflineLibraryPolicy.createPlaylist(OfflineLibrarySnapshot(), playlistId, name)
            }
        }
        val initial = playlist(listOf(first), listOf(first.id))
        assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.addToPlaylist(initial, playlistId, second.id) }
        assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.renamePlaylist(initial, id(9999), "Missing") }
        assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.movePlaylistItem(initial, playlistId, second.id, 1) }
    }

    @Test fun rejectsCorruptSnapshotsInsteadOfDroppingRecords() {
        listOf(
            OfflineLibrarySnapshot(listOf(first, first)),
            OfflineLibrarySnapshot(listOf(first, first.copy(id = second.id))),
            OfflineLibrarySnapshot(listOf(first.copy(id = "not-a-uuid"))),
            OfflineLibrarySnapshot(listOf(first.copy(savedAt = -1))),
            playlist(listOf(first), listOf(second.id)),
            playlist(listOf(first), listOf(first.id, first.id))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.validate(invalid) }
        }
    }

    @Test fun recordedTitlesKeepUnicodeAndRemoveControls() {
        val cleaned = OfflineLibraryPolicy.record(OfflineLibrarySnapshot(), first.copy(title = " 小薯茄 😀\n音樂\u0000 "))
        assertEquals("小薯茄 😀 音樂", cleaned.downloads.single().title)
        val blank = OfflineLibraryPolicy.record(OfflineLibrarySnapshot(), first.copy(title = "\n\r"))
        assertEquals("Saved media", blank.downloads.single().title)
    }

    private fun playlist(downloads: List<OfflineDownload>, ids: List<String>) =
        OfflineLibrarySnapshot(downloads, listOf(OfflinePlaylist(playlistId, "Offline", ids)))

    private fun download(index: Int) = OfflineDownload(id(index), "content://provider/document/$index", "File $index", DownloadFormat.MP4, 100)
    private fun id(index: Int) = UUID(0, index.toLong()).toString()
}
