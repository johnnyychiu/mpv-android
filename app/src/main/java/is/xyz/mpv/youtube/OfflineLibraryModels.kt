package `is`.xyz.mpv.youtube

import java.net.URI
import java.util.UUID

data class OfflineDownload(
    val id: String,
    val destination: String,
    val title: String,
    val format: DownloadFormat,
    val savedAt: Long,
    val videoId: String? = null,
    val userRenamed: Boolean = false,
    val sourceIds: List<String> = emptyList()
)

data class OfflinePlaylist(val id: String, val name: String, val downloadIds: List<String>)

data class OfflineLibrarySnapshot(
    val downloads: List<OfflineDownload> = emptyList(),
    val playlists: List<OfflinePlaylist> = emptyList()
)

/** Pure validation and edits; the repository serializes these read-modify-write operations. */
object OfflineLibraryPolicy {
    const val MAX_DOWNLOADS = 1000
    const val MAX_PLAYLISTS = 50
    const val MAX_PLAYLIST_ENTRIES = 200
    const val MAX_PLAYLIST_NAME_CHARS = 80
    const val MAX_TITLE_CHARS = 500
    const val MAX_URI_CHARS = 4096
    const val MAX_SOURCE_IDS = 100
    private val videoIdentifier = Regex("[A-Za-z0-9_-]{11}")

    fun videoId(value: String): String {
        require(videoIdentifier.matches(value)) { "The saved YouTube video ID is invalid." }
        return value
    }

    fun legacyVideoId(title: String): String? =
        Regex("YouTube ([A-Za-z0-9_-]{11})").matchEntire(title)?.groupValues?.get(1)

    fun destination(value: String): String {
        require(value.length <= MAX_URI_CHARS && value.none { it.isISOControl() || it == '\\' }) {
            "The saved file address is invalid."
        }
        val uri = try { URI(value) } catch (_: Exception) {
            throw IllegalArgumentException("The saved file address is invalid.")
        }
        require(uri.scheme == "content" && !uri.isOpaque && !uri.rawAuthority.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port == -1) {
            "Choose a saved document to add to the offline library."
        }
        return value
    }

    fun validate(snapshot: OfflineLibrarySnapshot): OfflineLibrarySnapshot {
        require(snapshot.downloads.size <= MAX_DOWNLOADS) { "The offline library is full ($MAX_DOWNLOADS files)." }
        require(snapshot.playlists.size <= MAX_PLAYLISTS) { "You can save up to $MAX_PLAYLISTS playlists." }
        snapshot.downloads.forEach {
            identifier(it.id)
            destination(it.destination)
            require(it.title.isNotBlank() && it.title.length <= MAX_TITLE_CHARS && it.title.none(Char::isISOControl)) {
                "A saved file has an invalid title."
            }
            require(it.savedAt >= 0) { "A saved file has an invalid date." }
            it.videoId?.let(::videoId)
            require(it.sourceIds.size <= MAX_SOURCE_IDS && it.id !in it.sourceIds &&
                it.sourceIds.toSet().size == it.sourceIds.size) { "A saved file has invalid source identifiers." }
            it.sourceIds.forEach(::identifier)
        }
        val downloadIds = snapshot.downloads.map { it.id }.toSet()
        require(downloadIds.size == snapshot.downloads.size &&
            snapshot.downloads.map { it.destination }.toSet().size == snapshot.downloads.size) {
            "The offline library contains duplicate files."
        }
        val allSourceIds = snapshot.downloads.flatMap { listOf(it.id) + it.sourceIds }
        require(allSourceIds.toSet().size == allSourceIds.size) { "A saved source belongs to more than one file." }
        require(snapshot.playlists.map { it.id }.toSet().size == snapshot.playlists.size) {
            "The offline library contains duplicate playlists."
        }
        snapshot.playlists.forEach {
            identifier(it.id)
            require(it.name == playlistName(it.name)) { "A playlist has an invalid name." }
            require(it.downloadIds.size <= MAX_PLAYLIST_ENTRIES) { "A playlist can hold up to $MAX_PLAYLIST_ENTRIES files." }
            require(it.downloadIds.toSet().size == it.downloadIds.size && it.downloadIds.all(downloadIds::contains)) {
                "A playlist contains missing or duplicate files."
            }
        }
        return snapshot.copy(downloads = snapshot.downloads.map { it.copy(sourceIds = it.sourceIds.toList()) }, playlists = snapshot.playlists.map {
            it.copy(downloadIds = it.downloadIds.toList())
        })
    }

    fun record(previous: OfflineLibrarySnapshot, value: OfflineDownload): OfflineLibrarySnapshot {
        val current = validate(previous)
        val cleanedTitle = value.title.map { if (it.isISOControl()) ' ' else it }.joinToString("")
            .trim().take(MAX_TITLE_CHARS).ifBlank { "Saved media" }
        val incoming = value.copy(title = cleanedTitle)
        validate(OfflineLibrarySnapshot(listOf(incoming)))
        val incomingIds = (listOf(incoming.id) + incoming.sourceIds).toSet()
        val sourceOwners = current.downloads.filter { item ->
            item.id in incomingIds || item.sourceIds.any(incomingIds::contains)
        }
        require(sourceOwners.size <= 1) { "A saved file identifier is already in use." }
        val sameId = sourceOwners.singleOrNull()
        val existing = sameId ?: current.downloads.find { it.destination == incoming.destination }
        if (existing != null) {
            // Remember every worker/import ID before a provider rename changes the URI.
            val aliases = (existing.sourceIds + incomingIds).distinct().filterNot { it == existing.id }
            val stale = incoming.savedAt <= existing.savedAt || existing.userRenamed
            // A stale job can still name the old URI. It must not undo a rename or
            // recreate the old document, including when the canonical ID came from an import.
            require(stale || sameId == null || existing.destination == incoming.destination) {
                "A saved file identifier is already in use."
            }
            val replacement = if (stale) existing.copy(
                videoId = existing.videoId ?: incoming.videoId, sourceIds = aliases
            ) else incoming.copy(
                id = existing.id, videoId = incoming.videoId ?: existing.videoId, sourceIds = aliases
            )
            return validate(current.copy(downloads = current.downloads.map {
                if (it.id == existing.id) replacement else it
            }))
        }
        require(current.downloads.size < MAX_DOWNLOADS) { "The offline library is full ($MAX_DOWNLOADS files)." }
        return current.copy(downloads = current.downloads + incoming)
    }

    fun renameDownload(previous: OfflineLibrarySnapshot, id: String, title: String, destination: String): OfflineLibrarySnapshot {
        val current = validate(previous)
        identifier(id)
        require(current.downloads.any { it.id == id }) { "The saved file is no longer in the library." }
        val name = title.trim()
        require(name.isNotBlank() && name.length <= MAX_TITLE_CHARS && name.none(Char::isISOControl)) {
            "Use a file name of 1–$MAX_TITLE_CHARS characters without line breaks."
        }
        val address = OfflineLibraryPolicy.destination(destination)
        require(current.downloads.none { it.id != id && it.destination == address }) {
            "That document is already in the offline library."
        }
        return validate(current.copy(downloads = current.downloads.map {
            if (it.id == id) it.copy(title = name, destination = address, userRenamed = true) else it
        }))
    }

    fun createPlaylist(previous: OfflineLibrarySnapshot, id: String, name: String): OfflineLibrarySnapshot {
        val current = validate(previous)
        identifier(id)
        require(current.playlists.none { it.id == id }) { "A playlist identifier is already in use." }
        require(current.playlists.size < MAX_PLAYLISTS) { "You can save up to $MAX_PLAYLISTS playlists." }
        return current.copy(playlists = current.playlists + OfflinePlaylist(id, playlistName(name), emptyList()))
    }

    fun renamePlaylist(previous: OfflineLibrarySnapshot, id: String, name: String) =
        editPlaylist(previous, id) { it.copy(name = playlistName(name)) }

    fun deletePlaylist(previous: OfflineLibrarySnapshot, id: String): OfflineLibrarySnapshot {
        val current = validate(previous)
        identifier(id)
        return current.copy(playlists = current.playlists.filterNot { it.id == id })
    }

    fun addToPlaylist(previous: OfflineLibrarySnapshot, id: String, downloadId: String): OfflineLibrarySnapshot {
        require(previous.downloads.any { it.id == downloadId }) { "The saved file is no longer in the library." }
        return editPlaylist(previous, id) {
            if (downloadId in it.downloadIds) it
            else {
                require(it.downloadIds.size < MAX_PLAYLIST_ENTRIES) { "A playlist can hold up to $MAX_PLAYLIST_ENTRIES files." }
                it.copy(downloadIds = it.downloadIds + downloadId)
            }
        }
    }

    fun removeFromPlaylist(previous: OfflineLibrarySnapshot, id: String, downloadId: String) =
        editPlaylist(previous, id) { it.copy(downloadIds = it.downloadIds.filterNot { item -> item == downloadId }) }

    fun movePlaylistItem(previous: OfflineLibrarySnapshot, id: String, downloadId: String, delta: Int) =
        editPlaylist(previous, id) {
            val oldIndex = it.downloadIds.indexOf(downloadId)
            require(oldIndex >= 0) { "The file is no longer in this playlist." }
            val newIndex = (oldIndex.toLong() + delta).coerceIn(0, it.downloadIds.lastIndex.toLong()).toInt()
            val ids = it.downloadIds.toMutableList()
            ids.add(newIndex, ids.removeAt(oldIndex))
            it.copy(downloadIds = ids)
        }

    private fun editPlaylist(
        previous: OfflineLibrarySnapshot,
        id: String,
        edit: (OfflinePlaylist) -> OfflinePlaylist
    ): OfflineLibrarySnapshot {
        val current = validate(previous)
        identifier(id)
        require(current.playlists.any { it.id == id }) { "The playlist is no longer available." }
        return validate(current.copy(playlists = current.playlists.map { if (it.id == id) edit(it) else it }))
    }

    private fun identifier(value: String) {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
            "A saved item has an invalid identifier."
        }
    }

    private fun playlistName(value: String): String {
        val name = value.trim()
        require(name.isNotBlank() && name.length <= MAX_PLAYLIST_NAME_CHARS && name.none(Char::isISOControl)) {
            "Use a playlist name of 1–$MAX_PLAYLIST_NAME_CHARS characters without line breaks."
        }
        return name
    }
}
