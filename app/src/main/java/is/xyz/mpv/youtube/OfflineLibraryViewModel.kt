package `is`.xyz.mpv.youtube

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.WorkInfo
import `is`.xyz.mpv.R
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class OfflineLibraryUiState(
    val snapshot: OfflineLibrarySnapshot = OfflineLibrarySnapshot(),
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val event: OfflineLibraryEvent? = null,
)

sealed interface OfflineLibraryEvent {
    data class Open(val destination: String, val title: String) : OfflineLibraryEvent
    data class Created(val playlistId: String, val addedDownload: Boolean) : OfflineLibraryEvent
    data class Added(val playlistId: String) : OfflineLibraryEvent
    data class Imported(val count: Int) : OfflineLibraryEvent
}

/** Serializes local catalog/provider work and retains it across Activity recreation. */
class OfflineLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableLiveData(OfflineLibraryUiState())
    val state: LiveData<OfflineLibraryUiState> = mutableState
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancellation = CancellationSignal()
    private var repository: OfflineLibraryRepository? = null
    private var pending = 0
    @Volatile private var cleared = false

    fun refresh(history: List<WorkInfo> = emptyList()) = task(preserveError = true) {
        var snapshot = repo().load()
        var warning: String? = null
        history.filter { it.state == WorkInfo.State.SUCCEEDED }.forEach { info ->
            if (cleared) return@task Result(snapshot)
            val destination = info.outputData.getString("destination") ?: return@forEach
            try {
                OfflineLibraryPolicy.destination(destination)
                val uri = Uri.parse(destination)
                val sourceId = info.id.toString()
                val known = snapshot.downloads.firstOrNull { it.id == sourceId || sourceId in it.sourceIds || it.destination == destination }
                val title = info.outputData.getString("title") ?: known?.title ?: displayName(uri)
                val formatName = info.tags.firstOrNull { it.startsWith("youtube-format:") }?.removePrefix("youtube-format:")
                    ?: info.outputData.getString("format")
                val format = formatName?.let { runCatching { DownloadFormat.valueOf(it) }.getOrNull() }
                    ?: known?.format ?: fileFormat(uri, displayName(uri))
                val savedAt = info.tags.firstOrNull { it.startsWith("youtube-created:") }?.removePrefix("youtube-created:")?.toLongOrNull() ?: 0L
                val videoId = info.outputData.getString("videoId")?.takeIf(YouTubeUrls::isVideoId)
                // Even known destinations must teach the catalog this job's alias before a URI-changing rename.
                snapshot = repo().record(OfflineDownload(sourceId, destination, title, format, savedAt, videoId))
            } catch (error: Exception) {
                warning = warning ?: error.message ?: string(R.string.youtube_offline_load_failed)
            }
        }
        Result(snapshot, warning = warning)
    }

    fun createPlaylist(name: String, downloadId: String? = null) = task {
        val before = repo().load().playlists.mapTo(mutableSetOf()) { it.id }
        var snapshot = repo().createPlaylist(name)
        val created = snapshot.playlists.first { it.id !in before }
        if (downloadId != null) snapshot = repo().addToPlaylist(created.id, downloadId)
        Result(snapshot, OfflineLibraryEvent.Created(created.id, downloadId != null))
    }

    fun renamePlaylist(id: String, name: String) = task { Result(repo().renamePlaylist(id, name)) }
    fun deletePlaylist(id: String) = task { Result(repo().deletePlaylist(id)) }
    fun removeFromPlaylist(id: String, downloadId: String) = task { Result(repo().removeFromPlaylist(id, downloadId)) }
    fun movePlaylistItem(id: String, downloadId: String, delta: Int) = task { Result(repo().movePlaylistItem(id, downloadId, delta)) }

    fun renameDownload(id: String, requestedName: String) = task {
        require(requestedName.isNotBlank() && requestedName.none(Char::isISOControl)) { string(R.string.youtube_offline_filename_invalid) }
        val snapshot = repo().load()
        val saved = snapshot.downloads.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException(string(R.string.youtube_offline_file_unavailable, requestedName))
        val filename = MediaFilenamePolicy.filename(requestedName, saved.format.extension, saved.videoId ?: "media")
        // Validate the prospective catalog edit before asking another app to change the file.
        OfflineLibraryPolicy.renameDownload(snapshot, id, filename, saved.destination)
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        val original = Uri.parse(saved.destination)
        require(DocumentsContract.isDocumentUri(context, original) &&
            context.checkUriPermission(original, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            string(R.string.youtube_offline_rename_unsupported)
        }
        val flags = resolver.query(original, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null, cancellation)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } ?: 0
        require(flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) { string(R.string.youtube_offline_rename_unsupported) }
        val originalName = displayName(original).ifBlank { saved.title }
        val renamed = try { DocumentsContract.renameDocument(resolver, original, filename) }
        catch (error: Exception) { throw IOException(string(R.string.youtube_offline_rename_failed), error) }
            ?: throw IOException(string(R.string.youtube_offline_rename_failed))
        try {
            val destination = OfflineLibraryPolicy.destination(renamed.toString())
            val retained = retainReadWriteAccess(renamed)
            val actualName = runCatching { displayName(renamed) }.getOrDefault(filename).ifBlank { filename }
            require(actualName.endsWith(".${saved.format.extension}", ignoreCase = true)) { string(R.string.youtube_offline_rename_failed) }
            val updated = repo().renameDownload(id, actualName, destination)
            Result(updated, warning = if (retained) null else string(R.string.youtube_offline_rename_access_lost, actualName))
        } catch (error: Exception) {
            // A provider and app-private JSON cannot share a transaction. Restore the real name if saving the catalog fails.
            val restored = runCatching { DocumentsContract.renameDocument(resolver, renamed, originalName) }.getOrNull()
            if (restored != null) {
                retainReadWriteAccess(restored)
                val originalAddressRestored = restored == original || runCatching {
                    repo().renameDownload(id, saved.title, OfflineLibraryPolicy.destination(restored.toString()))
                }.isSuccess
                if (originalAddressRestored) throw IOException(string(R.string.youtube_offline_rename_restored), error)
            }
            throw IOException(string(R.string.youtube_offline_rename_recovery, (restored ?: renamed).toString()), error)
        }
    }

    fun addDownloads(id: String, downloadIds: List<String>) = task {
        var snapshot = repo().load()
        downloadIds.distinct().forEach { snapshot = repo().addToPlaylist(id, it) }
        Result(snapshot, OfflineLibraryEvent.Added(id))
    }

    fun open(destination: String, title: String) = task {
        try {
            val uri = Uri.parse(OfflineLibraryPolicy.destination(destination))
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r", cancellation)?.use { require(it.statSize != 0L) }
                ?: throw IOException()
        } catch (error: Exception) {
            throw IOException(string(R.string.youtube_offline_file_unavailable, title), error)
        }
        Result(repo().load(), OfflineLibraryEvent.Open(destination, title))
    }

    fun importFiles(uris: List<Uri>, playlistId: String?) = task {
        val selected = uris.distinct()
        require(selected.size <= MAX_IMPORT_FILES) { string(R.string.youtube_offline_import_limit, MAX_IMPORT_FILES) }
        var snapshot = repo().load()
        var imported = 0
        var failed = 0
        var firstFailure: String? = null
        selected.forEach { uri ->
            if (cleared) return@task Result(snapshot)
            try {
                val destination = OfflineLibraryPolicy.destination(uri.toString())
                val name = displayName(uri)
                val format = fileFormat(uri, name)
                val resolver = getApplication<Application>().contentResolver
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                require(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                    string(R.string.youtube_offline_read_grant_failed)
                }
                resolver.openFileDescriptor(uri, "r", cancellation)?.use {
                    require(it.statSize != 0L) { string(R.string.youtube_offline_empty_file, name) }
                } ?: throw IOException(string(R.string.youtube_offline_file_unavailable, name))
                snapshot = repo().record(OfflineDownload(UUID.randomUUID().toString(), destination, name, format, System.currentTimeMillis()))
                if (playlistId != null) {
                    val saved = snapshot.downloads.first { it.destination == destination }
                    snapshot = repo().addToPlaylist(playlistId, saved.id)
                }
                imported++
            } catch (error: Exception) {
                failed++
                firstFailure = firstFailure ?: error.message ?: string(R.string.youtube_offline_import_failed)
            }
        }
        val warning = if (failed > 0) string(R.string.youtube_offline_import_partial, failed, firstFailure.orEmpty()) else null
        Result(snapshot, OfflineLibraryEvent.Imported(imported), warning)
    }

    fun clearError() { mutableState.value = current().copy(error = null) }
    fun consumeEvent() { mutableState.value = current().copy(event = null) }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.take(OfflineLibraryPolicy.MAX_TITLE_CHARS) else null
        }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().take(OfflineLibraryPolicy.MAX_TITLE_CHARS)
    }

    private fun retainReadWriteAccess(uri: Uri): Boolean {
        return runCatching {
            val context = getApplication<Application>()
            val resolver = context.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            // Some providers keep the original URI/grant. Failure to re-take an already valid grant is harmless.
            runCatching { resolver.takePersistableUriPermission(uri, flags) }
            val persistent = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            val granted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED
            val readable = runCatching { resolver.openFileDescriptor(uri, "r", cancellation)?.use { true } ?: false }.getOrDefault(false)
            persistent && granted && readable
        }.getOrDefault(false)
    }

    private fun fileFormat(uri: Uri, name: String): DownloadFormat {
        val mime = getApplication<Application>().contentResolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return when (mime) {
            "video/mp4" -> DownloadFormat.MP4
            "audio/mpeg", "audio/mp3" -> DownloadFormat.MP3
            null, "", "application/octet-stream", "binary/octet-stream" -> when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "mp4" -> DownloadFormat.MP4
                "mp3" -> DownloadFormat.MP3
                else -> throw IllegalArgumentException(string(R.string.youtube_offline_unsupported_file, name))
            }
            else -> throw IllegalArgumentException(string(R.string.youtube_offline_unsupported_file, name))
        }
    }

    private fun task(preserveError: Boolean = false, block: () -> Result) {
        if (cleared) return
        pending++
        mutableState.value = current().copy(busy = true, error = if (preserveError) current().error else null)
        executor.execute {
            val result = runCatching(block)
            val recovered = if (result.isFailure) runCatching { repo().load() }.getOrNull() else null
            main.post {
                if (cleared) return@post
                pending--
                result.fold(
                    onSuccess = { value -> mutableState.value = current().copy(snapshot = value.snapshot, busy = pending > 0, loaded = true, error = value.warning?.take(500) ?: if (preserveError) current().error else null, event = value.event ?: current().event) },
                    onFailure = { error -> mutableState.value = current().copy(snapshot = recovered ?: current().snapshot, busy = pending > 0, loaded = true, error = error.message?.take(500) ?: string(R.string.youtube_offline_load_failed)) },
                )
            }
        }
    }

    private fun repo() = repository ?: OfflineLibraryRepository(getApplication()).also { repository = it }
    private fun current() = mutableState.value ?: OfflineLibraryUiState()
    private fun string(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    override fun onCleared() {
        cleared = true
        cancellation.cancel()
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    private data class Result(val snapshot: OfflineLibrarySnapshot, val event: OfflineLibraryEvent? = null, val warning: String? = null)
    companion object { const val MAX_IMPORT_FILES = 50 }
}
