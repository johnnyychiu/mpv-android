package `is`.xyz.mpv.youtube

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import `is`.xyz.mpv.MPVActivity
import `is`.xyz.mpv.MainActivity
import `is`.xyz.mpv.R
import `is`.xyz.mpv.Utils
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class YouTubeActivity : AppCompatActivity() {
    private lateinit var model: YouTubeViewModel
    private lateinit var videos: VideoAdapter
    private lateinit var channels: ChannelAdapter
    private lateinit var downloads: DownloadAdapter
    private lateinit var content: RecyclerView
    private lateinit var tabs: TabLayout
    private lateinit var busy: View
    private lateinit var busyText: TextView
    private lateinit var notice: View
    private lateinit var noticeText: TextView
    private lateinit var empty: View
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptySubtitle: TextView
    private lateinit var emptyAction: MaterialButton
    private lateinit var emptyLink: MaterialButton
    private var section = Section.FEED
    private var incomingUrl: String? = null
    private var pendingDownload: PendingDownload? = null
    private var downloadInfos: List<WorkInfo> = emptyList()
    private var displayedNotice: String? = null
    private var dismissedNotice: String? = null
    private val scrollStates = mutableMapOf<Section, Parcelable?>()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denial does not prevent a user-requested foreground download.
        launchSavePicker()
    }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingDownload
        pendingDownload = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || pending == null) return@registerForActivityResult
        try {
            require(uri.scheme == "content")
            val flags = (result.data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            model.showMessage(getString(R.string.youtube_download_grant_failed))
            return@registerForActivityResult
        }
        try {
            YouTubeDownloadWorker.enqueue(this, pending.url, pending.title, pending.format, uri)
            selectSection(Section.DOWNLOADS)
        } catch (error: Exception) {
            model.showMessage(error.message ?: getString(R.string.youtube_download_unknown_error))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContentView(R.layout.youtube_activity)
        Utils.handleInsetsAsPadding(findViewById(R.id.youtube_root))
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.youtube_toolbar))
        supportActionBar?.setDisplayShowTitleEnabled(false)
        model = ViewModelProvider(this)[YouTubeViewModel::class.java]
        incomingUrl = intent.getStringExtra("youtube_url") ?: intent.dataString
        savedInstanceState?.let { saved ->
            section = runCatching { Section.valueOf(saved.getString("youtube_section").orEmpty()) }.getOrDefault(Section.FEED)
            dismissedNotice = saved.getString("youtube_dismissed_notice")
            Section.entries.forEach { scrollStates[it] = BundleCompat.getParcelable(saved, "youtube_scroll_${it.name}", Parcelable::class.java) }
            val url = saved.getString("youtube_pending_url")
            val title = saved.getString("youtube_pending_title")
            val format = saved.getString("youtube_pending_format")
            if (url != null && title != null && format != null) {
                pendingDownload = runCatching { PendingDownload(YouTubeUrls.videoUrl(url), title, DownloadFormat.valueOf(format)) }.getOrNull()
            }
        }
        bindViews()
        val configuration = resources.configuration
        if (configuration.screenWidthDp >= 600) {
            tabs.layoutParams = tabs.layoutParams.apply { width = dp(360) }
        }
        if (configuration.fontScale > 1.05f && configuration.screenWidthDp < 480 * configuration.fontScale) {
            tabs.tabMode = TabLayout.MODE_SCROLLABLE
        }
        videos = VideoAdapter(
            play = { model.play(it.url) },
            download = { video, format -> requestDownload(video.url, video.title, format) },
        )
        channels = ChannelAdapter(::showChannelDetails, ::confirmRemoveChannel)
        downloads = DownloadAdapter(
            cancel = { WorkManager.getInstance(this).cancelWorkById(it) },
            open = ::openDownloadedFile,
            details = { showDetails(getString(R.string.youtube_downloads), it) },
        )
        listOf(videos, channels, downloads).forEach { it.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        val gap = resources.getDimensionPixelSize(R.dimen.youtube_grid_gap)
        val verticalGap = resources.getDimensionPixelSize(R.dimen.youtube_grid_vertical_gap)
        val edge = resources.getDimensionPixelSize(R.dimen.youtube_screen_padding)
        content.setPadding(edge - gap / 2, 0, edge - gap / 2, dp(12))
        content.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(gap / 2, verticalGap / 2, gap / 2, verticalGap / 2)
            }
        })
        // These labels are always visible; navigation does not depend on an overflow menu.
        tabs.addTab(tabs.newTab().setText(R.string.youtube_feed), false)
        tabs.addTab(tabs.newTab().setText(R.string.youtube_channels), false)
        tabs.addTab(tabs.newTab().setText(R.string.youtube_downloads), false)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { switchSection(Section.entries[tab.position]) }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) { content.smoothScrollToPosition(0) }
        })
        tabs.getTabAt(section.ordinal)?.select()
        findViewById<View>(R.id.youtube_cancel).setOnClickListener { model.cancel() }
        findViewById<View>(R.id.youtube_notice_close).setOnClickListener {
            dismissedNotice = displayedNotice
            notice.visibility = View.GONE
        }
        noticeText.setOnClickListener { displayedNotice?.let { showDetails(getString(R.string.youtube_details), it) } }
        emptyLink.setOnClickListener { showVideoLink() }
        model.state.observe(this) { state ->
            render(state)
            state.playback?.let { resolved ->
                model.consumePlayback()
                playResolved(resolved)
            }
            if (state.operation == null && !model.initialActionHandled) {
                model.initialActionHandled = true
                val url = incomingUrl
                if (url != null) model.play(url)
                else if (state.snapshot.subscriptions.isNotEmpty()) model.refresh()
            }
        }
        WorkManager.getInstance(this).getWorkInfosByTagLiveData("youtube-download").observe(this) { infos ->
            downloadInfos = infos.orEmpty().sortedWith(compareBy<WorkInfo> { it.state.isFinished }.thenByDescending(::createdAt).thenBy { it.id.toString() })
            downloads.submitList(downloadInfos)
            renderSection(model.state.value ?: YouTubeUiState())
            val active = downloadInfos.count { !it.state.isFinished }
            if (active > 0) tabs.getTabAt(Section.DOWNLOADS.ordinal)?.orCreateBadge?.apply {
                number = active
                backgroundColor = ContextCompat.getColor(this@YouTubeActivity, R.color.youtube_accent)
                badgeTextColor = ContextCompat.getColor(this@YouTubeActivity, R.color.youtube_on_accent)
            } else tabs.getTabAt(Section.DOWNLOADS.ordinal)?.removeBadge()
        }
    }

    private fun bindViews() {
        content = findViewById(R.id.youtube_content)
        tabs = findViewById(R.id.youtube_tabs)
        busy = findViewById(R.id.youtube_busy)
        busyText = findViewById(R.id.youtube_busy_text)
        notice = findViewById(R.id.youtube_notice)
        noticeText = findViewById(R.id.youtube_notice_text)
        empty = findViewById(R.id.youtube_empty)
        emptyIcon = findViewById(R.id.youtube_empty_icon)
        emptyTitle = findViewById(R.id.youtube_empty_title)
        emptySubtitle = findViewById(R.id.youtube_empty_subtitle)
        emptyAction = findViewById(R.id.youtube_empty_action)
        emptyLink = findViewById(R.id.youtube_empty_link)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = intent.getStringExtra("youtube_url") ?: intent.dataString
        incomingUrl?.let {
            model.initialActionHandled = true
            model.cancel()
            model.play(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        scrollStates[section] = content.layoutManager?.onSaveInstanceState()
        outState.putString("youtube_section", section.name)
        outState.putString("youtube_dismissed_notice", dismissedNotice)
        scrollStates.forEach { (key, state) -> outState.putParcelable("youtube_scroll_${key.name}", state) }
        pendingDownload?.let {
            outState.putString("youtube_pending_url", it.url)
            outState.putString("youtube_pending_title", it.title)
            outState.putString("youtube_pending_format", it.format.name)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Release attached thumbnail targets immediately; Coil also observes Activity destruction.
        content.adapter = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD, 0, R.string.youtube_add_channel).setIcon(R.drawable.youtube_ic_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_REFRESH, 1, R.string.youtube_refresh).setIcon(R.drawable.youtube_ic_refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_LINK, 2, R.string.youtube_open_video).setIcon(R.drawable.youtube_ic_link).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_LOCAL, 3, R.string.youtube_local_media)
        menu.add(0, MENU_UPDATE, 4, R.string.youtube_update)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val state = model.state.value ?: YouTubeUiState()
        val idle = state.operation == null
        menu.findItem(MENU_ADD)?.isEnabled = idle
        menu.findItem(MENU_REFRESH)?.isEnabled = idle && state.snapshot.subscriptions.isNotEmpty()
        menu.findItem(MENU_LINK)?.isEnabled = canStartPlayback(state)
        menu.findItem(MENU_UPDATE)?.isEnabled = idle
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ADD -> { showAddChannel(); true }
        MENU_REFRESH -> { model.refresh(); true }
        MENU_LINK -> { showVideoLink(); true }
        MENU_LOCAL -> { startActivity(Intent(this, MainActivity::class.java)); true }
        MENU_UPDATE -> { model.updateEngine(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun selectSection(value: Section) { tabs.getTabAt(value.ordinal)?.select() }

    private fun switchSection(value: Section) {
        if (content.adapter != null) scrollStates[section] = content.layoutManager?.onSaveInstanceState()
        section = value
        val width = resources.configuration.screenWidthDp
        val columns = if (value == Section.FEED) when {
            width >= 1000 -> 3
            width >= 600 -> 2
            else -> 1
        } else if (width >= 1000) 2 else 1
        content.layoutManager = GridLayoutManager(this, columns)
        content.adapter = when (value) { Section.FEED -> videos; Section.CHANNELS -> channels; Section.DOWNLOADS -> downloads }
        scrollStates[value]?.let { content.layoutManager?.onRestoreInstanceState(it) }
        renderSection(model.state.value ?: YouTubeUiState())
    }

    private fun render(state: YouTubeUiState) {
        val idle = state.operation == null
        videos.setPlaybackEnabled(canStartPlayback(state))
        channels.setActionsEnabled(idle)
        videos.submitList(state.snapshot.videos)
        val uploadCounts = state.snapshot.videos.groupingBy { it.channelId }.eachCount()
        channels.submitList(state.snapshot.subscriptions.map { ChannelRow(it, uploadCounts[it.channelId] ?: 0) })
        busy.visibility = if (idle) View.GONE else View.VISIBLE
        busyText.text = when (state.operation) {
            YouTubeOperation.LOAD -> getString(R.string.youtube_loading)
            YouTubeOperation.ADD -> getString(R.string.youtube_adding)
            YouTubeOperation.REMOVE -> getString(R.string.youtube_removing)
            YouTubeOperation.PLAY -> getString(R.string.youtube_resolving)
            YouTubeOperation.UPDATE -> getString(R.string.youtube_updating)
            YouTubeOperation.REFRESH -> if (state.totalChannels > 0) getString(R.string.youtube_refresh_progress, (state.completedChannels + 1).coerceAtMost(state.totalChannels), state.totalChannels) else getString(R.string.youtube_refreshing)
            null -> ""
        }
        renderSection(state)
        invalidateOptionsMenu()
    }

    private fun renderSection(state: YouTubeUiState) {
        val idle = state.operation == null
        val hasChannels = state.snapshot.subscriptions.isNotEmpty()
        val isEmpty = when (section) {
            Section.FEED -> state.snapshot.videos.isEmpty()
            Section.CHANNELS -> !hasChannels
            Section.DOWNLOADS -> downloadInfos.isEmpty()
        }
        content.visibility = if (isEmpty) View.GONE else View.VISIBLE
        empty.visibility = if (isEmpty && state.operation != YouTubeOperation.LOAD) View.VISIBLE else View.GONE
        emptyAction.isEnabled = idle
        emptyLink.isEnabled = canStartPlayback(state)
        emptyLink.visibility = View.VISIBLE
        when (section) {
            Section.FEED -> {
                emptyIcon.setImageResource(R.drawable.youtube_ic_library)
                emptyTitle.setText(if (hasChannels) R.string.youtube_no_uploads_title else R.string.youtube_empty_headline)
                emptySubtitle.setText(if (hasChannels) R.string.youtube_no_uploads_copy else R.string.youtube_empty_copy)
                emptyAction.setText(if (hasChannels) R.string.youtube_refresh else R.string.youtube_add_channel)
                emptyAction.setIconResource(if (hasChannels) R.drawable.youtube_ic_refresh else R.drawable.youtube_ic_add)
                emptyAction.setOnClickListener { if (hasChannels) model.refresh() else showAddChannel() }
            }
            Section.CHANNELS -> {
                emptyIcon.setImageResource(R.drawable.youtube_ic_channels)
                emptyTitle.setText(R.string.youtube_empty_channels_title)
                emptySubtitle.setText(R.string.youtube_empty_channels_copy)
                emptyAction.setText(R.string.youtube_add_channel)
                emptyAction.setIconResource(R.drawable.youtube_ic_add)
                emptyAction.setOnClickListener { showAddChannel() }
            }
            Section.DOWNLOADS -> {
                emptyIcon.setImageResource(R.drawable.youtube_ic_download)
                emptyTitle.setText(R.string.youtube_empty_downloads_title)
                emptySubtitle.setText(R.string.youtube_empty_downloads_copy)
                emptyAction.setText(R.string.youtube_browse_feed)
                emptyAction.setIconResource(R.drawable.youtube_ic_play)
                emptyAction.isEnabled = true
                emptyAction.setOnClickListener { selectSection(Section.FEED) }
            }
        }
        val failures = state.snapshot.subscriptions.filter { it.lastError != null }
        displayedNotice = state.message?.let { presentError(this, it) } ?: if (section == Section.FEED && failures.isNotEmpty()) {
            getString(R.string.youtube_cached_notice) + "\n\n" + failures.joinToString("\n") { "${it.title}: ${presentError(this, it.lastError.orEmpty())}" }
        } else null
        val showNotice = !displayedNotice.isNullOrBlank() && displayedNotice != dismissedNotice
        notice.visibility = if (showNotice) View.VISIBLE else View.GONE
        noticeText.text = displayedNotice
        noticeText.contentDescription = getString(R.string.youtube_notice_details, displayedNotice.orEmpty())
    }

    private fun showAddChannel() = showLinkDialog(R.string.youtube_add_channel, R.string.youtube_channel_hint, R.string.youtube_add) { text ->
        model.addChannel(YouTubeUrls.channel(text).url)
    }

    private fun showVideoLink() = showLinkDialog(R.string.youtube_open_video, R.string.youtube_video_hint, R.string.youtube_continue) { text ->
        val url = YouTubeUrls.videoUrl(text)
        val title = "YouTube ${Uri.parse(url).getQueryParameter("v")}"
        MaterialAlertDialogBuilder(this).setTitle(R.string.youtube_video_actions).setItems(arrayOf(
            getString(R.string.youtube_watch), getString(R.string.youtube_download_mp4), getString(R.string.youtube_download_mp3),
        )) { _, index ->
            when (index) {
                0 -> model.play(url)
                1 -> requestDownload(url, title, DownloadFormat.MP4)
                2 -> requestDownload(url, title, DownloadFormat.MP3)
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showLinkDialog(title: Int, hint: Int, action: Int, submit: (String) -> Unit) {
        val inputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            setBoxCornerRadii(0f, 0f, 0f, 0f)
            this.hint = getString(hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextSize(14f)
        }
        inputLayout.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(inputLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(container)
            .setPositiveButton(action, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { submit(input.text.toString()); dialog.dismiss() }
                catch (error: IllegalArgumentException) { inputLayout.error = error.message }
            }
        }
        dialog.show()
    }

    private fun showChannelDetails(channel: YouTubeSubscription) {
        val detail = buildString {
            append(YouTubeUrls.channelUrl(channel.channelId))
            append("\n\n")
            append(if (channel.lastRefreshedAt > 0) getString(R.string.youtube_last_refresh, dateTime(channel.lastRefreshedAt)) else getString(R.string.youtube_never_refreshed))
            channel.lastError?.let { append("\n\n${presentError(this@YouTubeActivity, it)}") }
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(channel.title).setMessage(detail)
            .setPositiveButton(R.string.youtube_close, null)
            .setNeutralButton(R.string.youtube_remove) { _, _ -> confirmRemoveChannel(channel) }.create()
        showWithIdleAction(dialog, AlertDialog.BUTTON_NEUTRAL)
    }

    private fun confirmRemoveChannel(channel: YouTubeSubscription) {
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.youtube_remove_channel).setMessage(getString(R.string.youtube_remove_message, channel.title))
            .setPositiveButton(R.string.youtube_remove) { _, _ -> model.removeChannel(channel.channelId) }
            .setNegativeButton(android.R.string.cancel, null).create()
        showWithIdleAction(dialog, AlertDialog.BUTTON_POSITIVE)
    }

    private fun showWithIdleAction(dialog: AlertDialog, button: Int) {
        val observer = Observer<YouTubeUiState> { state -> dialog.getButton(button).isEnabled = state.operation == null }
        dialog.setOnShowListener { model.state.observe(this, observer) }
        dialog.setOnDismissListener { model.state.removeObserver(observer) }
        dialog.show()
    }

    private fun requestDownload(url: String, title: String, format: DownloadFormat) {
        if (pendingDownload != null) return
        pendingDownload = PendingDownload(YouTubeUrls.videoUrl(url), title, format)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchSavePicker()
    }

    private fun launchSavePicker() {
        val pending = pendingDownload ?: return
        val basename = pending.title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(100).ifEmpty { "youtube-video" }
        try {
            saveDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = pending.format.mimeType
                putExtra(Intent.EXTRA_TITLE, "$basename.${pending.format.extension}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        } catch (_: Exception) {
            pendingDownload = null
            model.showMessage(getString(R.string.youtube_download_picker_failed))
        }
    }

    private fun playResolved(video: ResolvedVideo) {
        try {
            val headers = Bundle().apply { video.httpHeaders.forEach { (key, value) -> putString(key, value) } }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.videoUrl), this, MPVActivity::class.java).apply {
                putExtra("title", video.title)
                putExtra("youtube_resolved", true)
                video.audioUrl?.let { putExtra("youtube_audio_url", it) }
                putExtra("youtube_http_headers", headers)
            })
        } catch (_: Exception) { model.showMessage(getString(R.string.youtube_play_failed)) }
    }

    private fun openDownloadedFile(destination: String, title: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination), this, MPVActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("title", title))
        } catch (_: Exception) { Toast.makeText(this, R.string.youtube_download_open_failed, Toast.LENGTH_LONG).show() }
    }

    private fun showDetails(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton(R.string.youtube_close, null).show()
    }

    private fun canStartPlayback(state: YouTubeUiState) = state.operation == null || state.operation == YouTubeOperation.REFRESH || state.operation == YouTubeOperation.LOAD
    private fun dp(value: Int) = Utils.convertDp(this, value.toFloat())
    private fun dateTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    private enum class Section { FEED, CHANNELS, DOWNLOADS }
    private data class PendingDownload(val url: String, val title: String, val format: DownloadFormat)
    private data class ChannelRow(val subscription: YouTubeSubscription, val uploads: Int)

    private class VideoAdapter(val play: (YouTubeVideo) -> Unit, val download: (YouTubeVideo, DownloadFormat) -> Unit) :
        ListAdapter<YouTubeVideo, VideoAdapter.Holder>(object : DiffUtil.ItemCallback<YouTubeVideo>() {
            override fun areItemsTheSame(oldItem: YouTubeVideo, newItem: YouTubeVideo) = oldItem.videoId == newItem.videoId
            override fun areContentsTheSame(oldItem: YouTubeVideo, newItem: YouTubeVideo) = oldItem == newItem
        }) {
        private var playbackEnabled = true
        fun setPlaybackEnabled(enabled: Boolean) {
            if (playbackEnabled == enabled) return
            playbackEnabled = enabled
            notifyItemRangeChanged(0, itemCount)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.youtube_video_row, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val video = getItem(position)
            val context = holder.itemView.context
            holder.thumbnail.showVideo(video.videoId)
            holder.title.text = video.title
            holder.avatar.text = initial(video.channelTitle)
            holder.channel.text = video.channelTitle
            holder.date.visibility = if (video.publishedAt > 0) View.VISIBLE else View.GONE
            holder.date.text = if (video.publishedAt > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(video.publishedAt)) else ""
            holder.play.isEnabled = playbackEnabled
            holder.itemView.isEnabled = playbackEnabled
            holder.itemView.contentDescription = context.getString(R.string.youtube_watch_accessibility, video.title, video.channelTitle)
            holder.play.contentDescription = context.getString(R.string.youtube_watch_video, video.title)
            holder.mp4.contentDescription = context.getString(R.string.youtube_mp4_accessibility, video.title)
            holder.mp3.contentDescription = context.getString(R.string.youtube_mp3_accessibility, video.title)
            holder.itemView.setOnClickListener { if (playbackEnabled) play(video) }
            holder.play.setOnClickListener { play(video) }
            holder.mp4.setOnClickListener { download(video, DownloadFormat.MP4) }
            holder.mp3.setOnClickListener { download(video, DownloadFormat.MP3) }
        }
        override fun onViewRecycled(holder: Holder) { holder.thumbnail.showVideo(null); super.onViewRecycled(holder) }
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: YouTubeThumbnailView = view.findViewById(R.id.youtube_thumbnail)
            val title: TextView = view.findViewById(R.id.youtube_video_title)
            val avatar: TextView = view.findViewById(R.id.youtube_video_avatar)
            val channel: TextView = view.findViewById(R.id.youtube_video_channel)
            val date: TextView = view.findViewById(R.id.youtube_video_date)
            val play: MaterialButton = view.findViewById(R.id.youtube_play)
            val mp4: MaterialButton = view.findViewById(R.id.youtube_mp4)
            val mp3: MaterialButton = view.findViewById(R.id.youtube_mp3)
        }
    }

    private class ChannelAdapter(val details: (YouTubeSubscription) -> Unit, val remove: (YouTubeSubscription) -> Unit) :
        ListAdapter<ChannelRow, ChannelAdapter.Holder>(object : DiffUtil.ItemCallback<ChannelRow>() {
            override fun areItemsTheSame(oldItem: ChannelRow, newItem: ChannelRow) = oldItem.subscription.channelId == newItem.subscription.channelId
            override fun areContentsTheSame(oldItem: ChannelRow, newItem: ChannelRow) = oldItem == newItem
        }) {
        private var actionsEnabled = true
        fun setActionsEnabled(enabled: Boolean) { if (actionsEnabled != enabled) { actionsEnabled = enabled; notifyItemRangeChanged(0, itemCount) } }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.youtube_channel_row, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            val channel = row.subscription
            val context = holder.itemView.context
            holder.avatar.text = initial(channel.title)
            holder.title.text = channel.title
            holder.metadata.text = context.resources.getQuantityString(R.plurals.youtube_upload_count, row.uploads, row.uploads)
            holder.error.visibility = if (channel.lastError != null) View.VISIBLE else View.GONE
            holder.error.setText(R.string.youtube_channel_refresh_error)
            holder.remove.isEnabled = actionsEnabled
            holder.remove.contentDescription = context.getString(R.string.youtube_remove_accessibility, channel.title)
            holder.remove.setOnClickListener { remove(channel) }
            holder.itemView.setOnClickListener { details(channel) }
        }
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.youtube_channel_avatar)
            val title: TextView = view.findViewById(R.id.youtube_channel_title)
            val metadata: TextView = view.findViewById(R.id.youtube_channel_metadata)
            val error: TextView = view.findViewById(R.id.youtube_channel_error)
            val remove: ImageButton = view.findViewById(R.id.youtube_channel_remove)
        }
    }

    private class DownloadAdapter(val cancel: (UUID) -> Unit, val open: (String, String) -> Unit, val details: (String) -> Unit) :
        ListAdapter<WorkInfo, DownloadAdapter.Holder>(object : DiffUtil.ItemCallback<WorkInfo>() {
            override fun areItemsTheSame(oldItem: WorkInfo, newItem: WorkInfo) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: WorkInfo, newItem: WorkInfo) = oldItem == newItem
        }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.youtube_download_row, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val info = getItem(position)
            val context = holder.itemView.context
            val title = info.outputData.getString("title") ?: info.progress.getString("title") ?: context.getString(R.string.youtube_download_generic)
            val format = info.tags.firstOrNull { it.startsWith("youtube-format:") }?.removePrefix("youtube-format:")?.takeIf { it == "MP4" || it == "MP3" }
            holder.title.text = title
            holder.format.text = format.orEmpty()
            holder.format.visibility = if (format == null) View.GONE else View.VISIBLE
            val timestamp = createdAt(info)
            holder.created.visibility = if (timestamp > 0) View.VISIBLE else View.GONE
            holder.created.text = if (timestamp > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)) else ""
            val percent = info.progress.getInt("percent", -1).coerceIn(-1, 100)
            val status = when (info.state) {
                WorkInfo.State.ENQUEUED -> context.getString(R.string.youtube_download_queued)
                WorkInfo.State.BLOCKED -> context.getString(R.string.youtube_download_waiting)
                WorkInfo.State.RUNNING -> {
                    val label = info.progress.getString("status") ?: context.getString(R.string.youtube_download_running)
                    if (percent >= 0) context.getString(R.string.youtube_download_progress, label, percent) else label
                }
                WorkInfo.State.SUCCEEDED -> context.getString(R.string.youtube_download_complete)
                WorkInfo.State.CANCELLED -> context.getString(R.string.youtube_download_cancelled)
                WorkInfo.State.FAILED -> context.getString(R.string.youtube_download_failed, presentError(context, info.outputData.getString("error") ?: context.getString(R.string.youtube_download_unknown_error)))
            }
            holder.status.text = status
            holder.status.setTextColor(ContextCompat.getColor(context, when (info.state) {
                WorkInfo.State.SUCCEEDED -> R.color.youtube_success
                WorkInfo.State.FAILED -> R.color.youtube_error
                else -> R.color.youtube_text_secondary
            }))
            holder.progress.visibility = if (info.state.isFinished) View.GONE else View.VISIBLE
            holder.progress.isIndeterminate = !info.state.isFinished && (percent < 0 || info.state != WorkInfo.State.RUNNING)
            if (!holder.progress.isIndeterminate) holder.progress.setProgressCompat(percent.coerceAtLeast(0), false)
            val destination = info.outputData.getString("destination")
            holder.action.visibility = if (!info.state.isFinished || (info.state == WorkInfo.State.SUCCEEDED && destination != null)) View.VISIBLE else View.GONE
            if (!info.state.isFinished) {
                holder.action.setText(android.R.string.cancel)
                holder.action.contentDescription = context.getString(R.string.youtube_cancel_download, title)
                holder.action.setOnClickListener { cancel(info.id) }
            } else {
                holder.action.setText(R.string.youtube_open)
                holder.action.contentDescription = context.getString(R.string.youtube_open_download, title)
                holder.action.setOnClickListener { destination?.let { open(it, title) } }
            }
            holder.itemView.setOnClickListener(if (info.state == WorkInfo.State.FAILED) View.OnClickListener { details(status) } else null)
        }
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.youtube_download_title)
            val format: TextView = view.findViewById(R.id.youtube_download_format)
            val created: TextView = view.findViewById(R.id.youtube_download_created)
            val status: TextView = view.findViewById(R.id.youtube_download_status)
            val progress: LinearProgressIndicator = view.findViewById(R.id.youtube_download_progress)
            val action: MaterialButton = view.findViewById(R.id.youtube_download_action)
        }
    }

    private companion object {
        const val MENU_ADD = 101
        const val MENU_REFRESH = 102
        const val MENU_LINK = 103
        const val MENU_LOCAL = 104
        const val MENU_UPDATE = 105
        val REQUEST_LIMIT_ERROR = Regex("""\bHTTP(?:\s+(?:Error|status(?:\s+code)?))?\s*:?\s*429\b|\bsign\s+in\s+to\s+confirm(?:\s+that)?\s+you(?:['’]re|\s+are)\s+not\s+a\s+bot\b""", RegexOption.IGNORE_CASE)
        fun presentError(context: Context, message: String): String =
            if (!message.startsWith("Could not update the YouTube tools.") && REQUEST_LIMIT_ERROR.containsMatchIn(message)) {
                context.getString(R.string.youtube_request_limited)
            } else message
        fun createdAt(info: WorkInfo): Long = info.tags.firstOrNull { it.startsWith("youtube-created:") }?.removePrefix("youtube-created:")?.toLongOrNull() ?: 0L
        fun initial(title: String): String = title.trim().takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))).uppercase(Locale.getDefault()) } ?: "?"
    }
}
