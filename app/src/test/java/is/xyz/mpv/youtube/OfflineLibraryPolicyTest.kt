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
        val withAlias = OfflineLibraryPolicy.record(firstSave, repeatedImport)
        assertEquals(listOf(id(99)), withAlias.downloads.single().sourceIds)
        assertEquals(withAlias, OfflineLibraryPolicy.record(withAlias, repeatedImport))
    }

    @Test fun duplicateDocumentPreservesItsIdentifierAndPlaylistReferences() {
        val initial = playlist(listOf(first), listOf(first.id))
        val newer = first.copy(id = id(99), title = "Renamed document", savedAt = 200)
        val result = OfflineLibraryPolicy.record(initial, newer)
        assertEquals(listOf(newer.copy(id = first.id, sourceIds = listOf(id(99)))), result.downloads)
        assertEquals(initial.playlists, result.playlists)
        assertEquals(result, OfflineLibraryPolicy.record(result, first))
    }

    @Test fun conflictingWorkerIdentifierDoesNotReplaceAnotherDocument() {
        val initial = OfflineLibrarySnapshot(listOf(first))
        assertThrows(IllegalArgumentException::class.java) {
            OfflineLibraryPolicy.record(initial, second.copy(id = first.id, savedAt = 200))
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

    @Test fun renamingADocumentKeepsPlaylistReferencesDateFormatAndArtwork() {
        val original = first.copy(videoId = "8Pae6Wy1abE", sourceIds = listOf(id(99)))
        val initial = playlist(listOf(original, second), listOf(second.id, original.id))
        val result = OfflineLibraryPolicy.renameDownload(initial, original.id, " 小薯茄 😀.mp4 ", "content://provider/document/renamed")
        assertEquals(original.copy(title = "小薯茄 😀.mp4", destination = "content://provider/document/renamed", userRenamed = true), result.downloads.first())
        assertEquals(second, result.downloads.last())
        assertEquals(initial.playlists, result.playlists)
    }

    @Test fun renamedFilesIgnoreNewerWorkerNamesEvenWhenProviderKeepsTheSameUri() {
        val renamed = OfflineLibraryPolicy.renameDownload(OfflineLibrarySnapshot(listOf(first)), first.id, "My title.mp4", first.destination)
        assertEquals(renamed, OfflineLibraryPolicy.record(renamed, first.copy(title = "Old worker name", savedAt = 999)))
    }

    @Test fun olderWorkerMetadataCannotRestoreADifferentDestination() {
        val initial = OfflineLibrarySnapshot(listOf(first))
        listOf(99L, 100L).forEach { oldDate ->
            assertEquals(initial, OfflineLibraryPolicy.record(initial, first.copy(destination = "content://provider/document/old", savedAt = oldDate)))
        }
    }

    @Test fun importedFirstWorkerAliasCannotReintroduceOldUriAfterRename() {
        val worker = first.copy(id = id(99), videoId = "8Pae6Wy1abE")
        val aliased = OfflineLibraryPolicy.record(playlist(listOf(first), listOf(first.id)), worker)
        val renamed = OfflineLibraryPolicy.renameDownload(aliased, first.id, "Saved title.mp4", "content://provider/document/new")
        val backfilled = OfflineLibraryPolicy.record(renamed, worker.copy(savedAt = 999))
        assertEquals(renamed, backfilled)
        assertEquals(1, backfilled.downloads.size)
        assertEquals(listOf(id(99)), backfilled.downloads.single().sourceIds)
        assertEquals("8Pae6Wy1abE", backfilled.downloads.single().videoId)
    }

    @Test fun optionalMetadataDefaultsKeepOlderRecordsValidAndImportsPreserveKnownArtwork() {
        assertNull(first.videoId)
        assertFalse(first.userRenamed)
        assertTrue(first.sourceIds.isEmpty())
        assertEquals(first, OfflineLibraryPolicy.validate(OfflineLibrarySnapshot(listOf(first))).downloads.single())
        val known = first.copy(videoId = "8Pae6Wy1abE")
        val importedAgain = OfflineLibraryPolicy.record(OfflineLibrarySnapshot(listOf(known)), first.copy(id = id(99), savedAt = 200))
        assertEquals("8Pae6Wy1abE", importedAgain.downloads.single().videoId)
        assertEquals(first.id, importedAgain.downloads.single().id)
    }

    @Test fun legacyVideoIdRecoveryOnlyAcceptsTheExactFormerPlaceholderTitle() {
        assertEquals("8Pae6Wy1abE", OfflineLibraryPolicy.legacyVideoId("YouTube 8Pae6Wy1abE"))
        listOf("8Pae6Wy1abE", "youtube 8Pae6Wy1abE", " YouTube 8Pae6Wy1abE", "YouTube 8Pae6Wy1abE ",
            "YouTube 8Pae6Wy1abE.mp4", "YouTube 8Pae6Wy1abE\n", "My YouTube 8Pae6Wy1abE", "YouTube ../bad").forEach {
            assertNull(OfflineLibraryPolicy.legacyVideoId(it))
        }
    }

    @Test fun videoIdsAndAliasesRejectPathInjectionDuplicateOwnershipAndExcessHistory() {
        listOf("", "../8Pae6Wy1abE", "8Pae6Wy1abE/", "8Pae6Wy1ab\n", "https://i.ytimg.com/a").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.videoId(invalid) }
        }
        assertEquals("_gDMltq-VoI", OfflineLibraryPolicy.videoId("_gDMltq-VoI"))
        listOf(
            listOf(first.copy(sourceIds = listOf(first.id))),
            listOf(first.copy(sourceIds = listOf(id(99), id(99)))),
            listOf(first.copy(sourceIds = listOf("invalid"))),
            listOf(first.copy(sourceIds = listOf(second.id)), second),
            listOf(first.copy(sourceIds = listOf(id(99))), second.copy(sourceIds = listOf(id(99)))),
            listOf(first.copy(sourceIds = (1000..1000 + OfflineLibraryPolicy.MAX_SOURCE_IDS).map(::id)))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.validate(OfflineLibrarySnapshot(invalid)) }
        }
    }

    @Test fun renameRejectsUnknownFilesUnsafeAddressesAndDestinationCollisions() {
        val initial = OfflineLibrarySnapshot(listOf(first, second))
        assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.renameDownload(initial, id(99), "Name.mp4", first.destination) }
        assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.renameDownload(initial, first.id, "Name.mp4", second.destination) }
        assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.renameDownload(initial, first.id, "Name.mp4", "file:///tmp/video.mp4") }
        listOf("", "bad\nname", "a".repeat(OfflineLibraryPolicy.MAX_TITLE_CHARS + 1)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { OfflineLibraryPolicy.renameDownload(initial, first.id, name, first.destination) }
        }
    }

    private fun playlist(downloads: List<OfflineDownload>, ids: List<String>) =
        OfflineLibrarySnapshot(downloads, listOf(OfflinePlaylist(playlistId, "Offline", ids)))

    private fun download(index: Int) = OfflineDownload(id(index), "content://provider/document/$index", "File $index", DownloadFormat.MP4, 100)
    private fun id(index: Int) = UUID(0, index.toLong()).toString()
}
