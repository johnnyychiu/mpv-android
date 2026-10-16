package `is`.xyz.mpv.youtube

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import `is`.xyz.mpv.MPVActivity
import `is`.xyz.mpv.R
import `is`.xyz.mpv.Utils

class OfflinePlaylistsActivity : AppCompatActivity() {
    private lateinit var model: OfflineLibraryViewModel
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var adapter: OfflineAdapter
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: MaterialButton
    private var selectedPlaylistId: String? = null
    private var pendingImportPlaylistId: String? = null
    private var addDownloadId: String? = null

    private val importDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val playlistId = pendingImportPlaylistId
        pendingImportPlaylistId = null
        if (uris.isNotEmpty()) model.importFiles(uris, playlistId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContentView(R.layout.youtube_offline_activity)
        Utils.handleInsetsAsPadding(findViewById(R.id.youtube_offline_root))
        toolbar = findViewById(R.id.youtube_offline_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this) { goBack() }
        selectedPlaylistId = savedInstanceState?.getString("offline_playlist")
        pendingImportPlaylistId = savedInstanceState?.getString("offline_import_playlist")
        addDownloadId = intent.getStringExtra(EXTRA_ADD_DOWNLOAD)
        model = ViewModelProvider(this)[OfflineLibraryViewModel::class.java]
        list = findViewById(R.id.youtube_offline_list)
        empty = findViewById(R.id.youtube_offline_empty)
        emptyText = findViewById(R.id.youtube_offline_empty_text)
        emptyAction = findViewById(R.id.youtube_offline_empty_action)
        adapter = OfflineAdapter(::openRow, ::showRowMenu)
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.youtube_offline_error_close).setOnClickListener { model.clearError() }
        model.state.observe(this) { state ->
            render(state)
            state.event?.let { event ->
                model.consumeEvent()
                when (event) {
                    is OfflineLibraryEvent.Open -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(event.destination), this, MPVActivity::class.java)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("title", event.title))
                    is OfflineLibraryEvent.Created -> {
                        if (event.addedDownload && addDownloadId != null) addedAndFinish()
                        else { selectedPlaylistId = event.playlistId; render(current()) }
                    }
                    is OfflineLibraryEvent.Added -> if (addDownloadId != null) addedAndFinish()
                    is OfflineLibraryEvent.Imported -> if (event.count > 0) Toast.makeText(this, resources.getQuantityString(R.plurals.youtube_offline_imported_count, event.count, event.count), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("offline_playlist", selectedPlaylistId)
        outState.putString("offline_import_playlist", pendingImportPlaylistId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { list.adapter = null; super.onDestroy() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CREATE, 0, R.string.youtube_offline_new_playlist).setIcon(R.drawable.youtube_ic_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_PLAY, 1, R.string.youtube_offline_play_all).setIcon(R.drawable.youtube_ic_play).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_ADD, 2, R.string.youtube_offline_add_downloads).setIcon(R.drawable.youtube_ic_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_IMPORT, 3, R.string.youtube_offline_import)
        menu.add(0, MENU_RENAME, 4, R.string.youtube_offline_rename)
        menu.add(0, MENU_DELETE, 5, R.string.youtube_offline_delete)
        menu.add(0, MENU_REFRESH, 6, R.string.youtube_refresh)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val state = current()
        val playlist = selectedPlaylist()
        menu.findItem(MENU_CREATE).isVisible = playlist == null
        menu.findItem(MENU_PLAY).isVisible = playlist != null
        menu.findItem(MENU_ADD).isVisible = playlist != null
        menu.findItem(MENU_RENAME).isVisible = playlist != null
        menu.findItem(MENU_DELETE).isVisible = playlist != null
        menu.findItem(MENU_IMPORT).isVisible = addDownloadId == null
        for (index in 0 until menu.size()) menu.getItem(index).isEnabled = state.loaded && !state.busy
        menu.findItem(MENU_PLAY).isEnabled = !state.busy && !playlist?.downloadIds.isNullOrEmpty()
        menu.findItem(MENU_REFRESH).isEnabled = !state.busy
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_CREATE -> { showNameDialog(); true }
        MENU_PLAY -> { selectedPlaylist()?.let(::playAll); true }
        MENU_ADD -> { selectedPlaylist()?.let(::showAddFiles); true }
        MENU_IMPORT -> { importFiles(); true }
        MENU_RENAME -> { selectedPlaylist()?.let { showNameDialog(it) }; true }
        MENU_DELETE -> { selectedPlaylist()?.let(::confirmDelete); true }
        MENU_REFRESH -> { model.refresh(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun render(state: OfflineLibraryUiState) {
        val playlist = state.snapshot.playlists.firstOrNull { it.id == selectedPlaylistId }
        if (state.loaded && playlist == null) selectedPlaylistId = null
        supportActionBar?.title = playlist?.name ?: getString(if (addDownloadId != null) R.string.youtube_offline_add_to_playlist else R.string.youtube_playlists)
        findViewById<View>(R.id.youtube_offline_progress).visibility = if (state.busy) View.VISIBLE else View.GONE
        findViewById<View>(R.id.youtube_offline_error_row).visibility = if (state.error != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.youtube_offline_error).text = state.error
        val rows = if (playlist == null) state.snapshot.playlists.map {
            OfflineRow(it.id, it.name, resources.getQuantityString(R.plurals.youtube_offline_file_count, it.downloadIds.size, it.downloadIds.size), playlist = it)
        } else {
            val saved = state.snapshot.downloads.associateBy { it.id }
            playlist.downloadIds.mapIndexedNotNull { index, id -> saved[id]?.let { OfflineRow(id, it.title, it.format.name, download = it, index = index) } }
        }
        adapter.enabled = !state.busy
        adapter.showMenus = addDownloadId == null
        adapter.submitList(rows)
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (rows.isEmpty() && state.loaded) View.VISIBLE else View.GONE
        emptyText.setText(if (playlist == null) R.string.youtube_offline_no_playlists else R.string.youtube_offline_no_files)
        emptyAction.setText(if (playlist == null) R.string.youtube_offline_new_playlist else R.string.youtube_offline_add_downloads)
        emptyAction.isEnabled = !state.busy
        emptyAction.setOnClickListener { if (playlist == null) showNameDialog() else showAddFiles(playlist) }
        invalidateOptionsMenu()
    }

    private fun openRow(row: OfflineRow) {
        if (current().busy) return
        row.playlist?.let { playlist ->
            val target = addDownloadId
            if (target != null) model.addDownloads(playlist.id, listOf(target))
            else { selectedPlaylistId = playlist.id; list.scrollToPosition(0); render(current()) }
        }
        row.download?.let { model.open(it.destination, it.title) }
    }

    private fun showRowMenu(anchor: View, row: OfflineRow) {
        if (current().busy) return
        val popup = PopupMenu(this, anchor)
        row.playlist?.let { playlist ->
            popup.menu.add(0, MENU_PLAY, 0, R.string.youtube_offline_play_all).isEnabled = playlist.downloadIds.isNotEmpty()
            popup.menu.add(0, MENU_RENAME, 1, R.string.youtube_offline_rename)
            popup.menu.add(0, MENU_DELETE, 2, R.string.youtube_offline_delete)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_PLAY -> playAll(playlist)
                    MENU_RENAME -> showNameDialog(playlist)
                    MENU_DELETE -> confirmDelete(playlist)
                }
                true
            }
        }
        row.download?.let { download ->
            val playlist = selectedPlaylist() ?: return
            popup.menu.add(0, MENU_OPEN, 0, R.string.youtube_open)
            popup.menu.add(0, MENU_UP, 1, R.string.youtube_offline_move_up).isEnabled = row.index > 0
            popup.menu.add(0, MENU_DOWN, 2, R.string.youtube_offline_move_down).isEnabled = row.index < playlist.downloadIds.lastIndex
            popup.menu.add(0, MENU_REMOVE, 3, R.string.youtube_offline_remove)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_OPEN -> model.open(download.destination, download.title)
                    MENU_UP -> model.movePlaylistItem(playlist.id, download.id, -1)
                    MENU_DOWN -> model.movePlaylistItem(playlist.id, download.id, 1)
                    MENU_REMOVE -> model.removeFromPlaylist(playlist.id, download.id)
                }
                true
            }
        }
        popup.show()
    }

    private fun showNameDialog(playlist: OfflinePlaylist? = null) {
        val inputLayout = TextInputLayout(this).apply { hint = getString(R.string.youtube_offline_playlist_name) }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(OfflineLibraryPolicy.MAX_PLAYLIST_NAME_CHARS))
            setText(playlist?.name.orEmpty())
            setSelection(text?.length ?: 0)
        }
        inputLayout.addView(input)
        val container = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(inputLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (playlist == null) R.string.youtube_offline_new_playlist else R.string.youtube_offline_rename)
            .setView(container).setNegativeButton(android.R.string.cancel, null).setPositiveButton(if (playlist == null) R.string.youtube_add else R.string.youtube_offline_rename, null).create()
        val observer = Observer<OfflineLibraryUiState> { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !it.busy }
        dialog.setOnShowListener {
            model.state.observe(this, observer)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank() || name.any(Char::isISOControl)) inputLayout.error = getString(R.string.youtube_offline_name_invalid, OfflineLibraryPolicy.MAX_PLAYLIST_NAME_CHARS)
                else {
                    if (playlist == null) model.createPlaylist(name, addDownloadId) else model.renamePlaylist(playlist.id, name)
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener { model.state.removeObserver(observer) }
        dialog.show()
    }

    private fun confirmDelete(playlist: OfflinePlaylist) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.youtube_offline_delete).setMessage(getString(R.string.youtube_offline_delete_message, playlist.name))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.youtube_offline_delete) { _, _ -> model.deletePlaylist(playlist.id) }.show()
    }

    private fun showAddFiles(playlist: OfflinePlaylist) {
        val available = current().snapshot.downloads.filter { it.id !in playlist.downloadIds }.sortedByDescending { it.savedAt }
        if (available.isEmpty()) {
            if (current().snapshot.downloads.isEmpty()) MaterialAlertDialogBuilder(this).setTitle(R.string.youtube_offline_no_files)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.youtube_offline_import) { _, _ -> importFiles() }.show()
            else Toast.makeText(this, R.string.youtube_offline_all_added, Toast.LENGTH_SHORT).show()
            return
        }
        val capacity = OfflineLibraryPolicy.MAX_PLAYLIST_ENTRIES - playlist.downloadIds.size
        val selected = mutableSetOf<Int>()
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.youtube_offline_add_downloads)
            .setMultiChoiceItems(available.map { "${it.title.take(100)} · ${it.format.name}" }.toTypedArray(), BooleanArray(available.size)) { dialog, index, checked ->
                if (checked && selected.size >= capacity) {
                    (dialog as AlertDialog).listView.setItemChecked(index, false)
                    Toast.makeText(this, getString(R.string.youtube_offline_selection_limit, capacity), Toast.LENGTH_SHORT).show()
                } else if (checked) selected.add(index) else selected.remove(index)
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selected.isNotEmpty()
            }.setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.youtube_add, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                model.addDownloads(playlist.id, selected.sorted().map { available[it].id })
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun importFiles() {
        pendingImportPlaylistId = selectedPlaylistId
        try { importDocuments.launch(arrayOf("video/mp4", "audio/mpeg")) }
        catch (_: Exception) { pendingImportPlaylistId = null; Toast.makeText(this, R.string.youtube_download_picker_failed, Toast.LENGTH_LONG).show() }
    }

    private fun playAll(playlist: OfflinePlaylist) {
        startActivity(Intent(this, MPVActivity::class.java).putExtra("youtube_offline_playlist", playlist.id))
    }

    private fun addedAndFinish() { Toast.makeText(this, R.string.youtube_offline_added, Toast.LENGTH_SHORT).show(); finish() }
    private fun goBack() { if (selectedPlaylistId != null) { selectedPlaylistId = null; render(current()) } else finish() }
    private fun selectedPlaylist() = current().snapshot.playlists.firstOrNull { it.id == selectedPlaylistId }
    private fun current() = model.state.value ?: OfflineLibraryUiState()
    private fun dp(value: Int) = Utils.convertDp(this, value.toFloat())

    private data class OfflineRow(val id: String, val title: String, val metadata: String, val playlist: OfflinePlaylist? = null, val download: OfflineDownload? = null, val index: Int = -1)

    private class OfflineAdapter(val open: (OfflineRow) -> Unit, val more: (View, OfflineRow) -> Unit) : ListAdapter<OfflineRow, OfflineAdapter.Holder>(object : DiffUtil.ItemCallback<OfflineRow>() {
        override fun areItemsTheSame(oldItem: OfflineRow, newItem: OfflineRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: OfflineRow, newItem: OfflineRow) = oldItem == newItem
    }) {
        var enabled = true
            set(value) { if (field != value) { field = value; notifyItemRangeChanged(0, itemCount) } }
        var showMenus = true
            set(value) { if (field != value) { field = value; notifyItemRangeChanged(0, itemCount) } }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.youtube_offline_row, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            holder.title.text = row.title
            holder.metadata.text = row.metadata
            holder.icon.visibility = if (row.index < 0) View.VISIBLE else View.GONE
            holder.index.visibility = if (row.index >= 0) View.VISIBLE else View.GONE
            holder.index.text = (row.index + 1).toString()
            holder.more.visibility = if (showMenus) View.VISIBLE else View.GONE
            holder.more.contentDescription = holder.itemView.context.getString(R.string.youtube_offline_options, row.title)
            holder.more.isEnabled = enabled
            holder.itemView.isEnabled = enabled
            holder.itemView.setOnClickListener { if (enabled) open(row) }
            holder.more.setOnClickListener { if (enabled) more(it, row) }
        }
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.youtube_offline_title)
            val metadata: TextView = view.findViewById(R.id.youtube_offline_metadata)
            val icon: ImageView = view.findViewById(R.id.youtube_offline_icon)
            val index: TextView = view.findViewById(R.id.youtube_offline_index)
            val more: ImageButton = view.findViewById(R.id.youtube_offline_more)
        }
    }

    companion object {
        const val EXTRA_ADD_DOWNLOAD = "offline_add_download"
        private const val MENU_CREATE = 201
        private const val MENU_PLAY = 202
        private const val MENU_ADD = 203
        private const val MENU_IMPORT = 204
        private const val MENU_RENAME = 205
        private const val MENU_DELETE = 206
        private const val MENU_REFRESH = 207
        private const val MENU_OPEN = 208
        private const val MENU_UP = 209
        private const val MENU_DOWN = 210
        private const val MENU_REMOVE = 211
    }
}
