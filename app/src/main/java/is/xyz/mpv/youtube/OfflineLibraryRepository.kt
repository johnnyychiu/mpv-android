package `is`.xyz.mpv.youtube

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Durable file history and playlist membership, independent of WorkManager retention. */
class OfflineLibraryRepository(context: Context) {
    private val storage = AtomicFile(File(context.applicationContext.filesDir, "youtube-offline-library.json"))

    fun load(): OfflineLibrarySnapshot = synchronized(lock) { read() }

    fun record(download: OfflineDownload) = change { OfflineLibraryPolicy.record(it, download) }

    fun renameDownload(id: String, title: String, destination: String) = change {
        OfflineLibraryPolicy.renameDownload(it, id, title, destination)
    }

    fun createPlaylist(name: String) = change {
        OfflineLibraryPolicy.createPlaylist(it, UUID.randomUUID().toString(), name)
    }

    fun renamePlaylist(id: String, name: String) = change { OfflineLibraryPolicy.renamePlaylist(it, id, name) }
    fun deletePlaylist(id: String) = change { OfflineLibraryPolicy.deletePlaylist(it, id) }
    fun addToPlaylist(id: String, downloadId: String) = change { OfflineLibraryPolicy.addToPlaylist(it, id, downloadId) }
    fun removeFromPlaylist(id: String, downloadId: String) = change { OfflineLibraryPolicy.removeFromPlaylist(it, id, downloadId) }
    fun movePlaylistItem(id: String, downloadId: String, delta: Int) = change {
        OfflineLibraryPolicy.movePlaylistItem(it, id, downloadId, delta)
    }

    private fun change(edit: (OfflineLibrarySnapshot) -> OfflineLibrarySnapshot): OfflineLibrarySnapshot = synchronized(lock) {
        // All repository instances share this lock. Re-read so worker completions and
        // screen edits cannot replace one another with a cached, older snapshot.
        val previous = read()
        val next = OfflineLibraryPolicy.validate(edit(previous))
        if (next != previous) write(next)
        next
    }

    private fun read(): OfflineLibrarySnapshot {
        try {
            val savedInput = try { storage.openRead() } catch (error: FileNotFoundException) {
                if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) {
                    return OfflineLibrarySnapshot()
                }
                throw error
            }
            val encoded = savedInput.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES) { "The offline library is too large to read." }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            val root = JSONObject(encoded)
            require(number(root, "version") == 1L) { "The offline library uses an unsupported format." }
            val downloads = root.getJSONArray("downloads")
            val playlists = root.getJSONArray("playlists")
            require(downloads.length() <= OfflineLibraryPolicy.MAX_DOWNLOADS && playlists.length() <= OfflineLibraryPolicy.MAX_PLAYLISTS) {
                "The offline library contains too many entries."
            }
            val files = (0 until downloads.length()).map { index ->
                val item = downloads.getJSONObject(index)
                val title = text(item, "title")
                OfflineDownload(text(item, "id"), text(item, "destination"), title,
                    DownloadFormat.valueOf(text(item, "format")), number(item, "savedAt"),
                    if (item.has("videoId") && !item.isNull("videoId")) text(item, "videoId") else OfflineLibraryPolicy.legacyVideoId(title),
                    if (item.has("userRenamed")) item.get("userRenamed") as? Boolean
                        ?: throw IOException("A saved rename flag is invalid.") else false,
                    if (item.has("sourceIds")) identifiers(item.getJSONArray("sourceIds")) else emptyList())
            }
            val lists = (0 until playlists.length()).map { index ->
                val item = playlists.getJSONObject(index)
                val entries = item.getJSONArray("downloadIds")
                require(entries.length() <= OfflineLibraryPolicy.MAX_PLAYLIST_ENTRIES) { "A saved playlist is too large." }
                val ids = (0 until entries.length()).map { position ->
                    entries.get(position) as? String ?: throw IOException("A playlist entry is invalid.")
                }
                OfflinePlaylist(text(item, "id"), text(item, "name"), ids)
            }
            return OfflineLibraryPolicy.validate(OfflineLibrarySnapshot(files, lists))
        } catch (error: Exception) {
            // Preserve corrupt/unsupported data for recovery; never silently overwrite it.
            throw IOException("The saved offline library could not be read.", error)
        }
    }

    private fun write(snapshot: OfflineLibrarySnapshot) {
        val downloads = JSONArray()
        snapshot.downloads.forEach {
            downloads.put(JSONObject().put("id", it.id).put("destination", it.destination).put("title", it.title)
                .put("format", it.format.name).put("savedAt", it.savedAt).put("videoId", it.videoId ?: JSONObject.NULL)
                .put("userRenamed", it.userRenamed).put("sourceIds", JSONArray(it.sourceIds)))
        }
        val playlists = JSONArray()
        snapshot.playlists.forEach {
            playlists.put(JSONObject().put("id", it.id).put("name", it.name).put("downloadIds", JSONArray(it.downloadIds)))
        }
        val bytes = JSONObject().put("version", 1).put("downloads", downloads).put("playlists", playlists)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "The offline library is too large to save." }
        val output = storage.startWrite()
        try {
            output.write(bytes)
            storage.finishWrite(output)
        } catch (error: Exception) {
            storage.failWrite(output)
            throw IOException("The offline library could not be saved.", error)
        }
    }

    private fun text(item: JSONObject, key: String): String =
        item.get(key) as? String ?: throw IOException("A saved library field is invalid.")

    private fun identifiers(values: JSONArray): List<String> {
        require(values.length() <= OfflineLibraryPolicy.MAX_SOURCE_IDS) { "A saved file has too many source identifiers." }
        return (0 until values.length()).map { position ->
            values.get(position) as? String ?: throw IOException("A saved source identifier is invalid.")
        }
    }

    private fun number(item: JSONObject, key: String): Long {
        val value = item.get(key) as? Number ?: throw IOException("A saved library date is invalid.")
        val result = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == result.toDouble()) { "A saved library number is invalid." }
        return result
    }

    private companion object {
        val lock = Any()
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
