package `is`.xyz.mpv.youtube

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

enum class YouTubeOperation { LOAD, REFRESH, ADD, REMOVE, PLAY, UPDATE }

data class YouTubeUiState(
    val snapshot: SubscriptionSnapshot = SubscriptionSnapshot(),
    val operation: YouTubeOperation? = null,
    val completedChannels: Int = 0,
    val totalChannels: Int = 0,
    val message: String? = null,
    val playback: ResolvedVideo? = null,
)

/** Retains in-flight work across rotation, without holding an Activity or a View. */
class YouTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableLiveData(YouTubeUiState())
    val state: LiveData<YouTubeUiState> = mutableState
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var repository: SubscriptionRepository? = null
    @Volatile private var processId: String? = null
    @Volatile private var cleared = false
    private var generation = 0
    private var task: Future<*>? = null
    var initialActionHandled = false

    init {
        launch(YouTubeOperation.LOAD) {
            val saved = repo().load()
            TaskResult(snapshot = saved)
        }
    }

    fun refresh() = launch(YouTubeOperation.REFRESH) { token -> TaskResult(snapshot = refreshFeeds(token)) }

    fun addChannel(url: String) = launch(YouTubeOperation.ADD) { TaskResult(snapshot = repo().addChannel(url)) }

    fun removeChannel(id: String) = launch(YouTubeOperation.REMOVE) { TaskResult(snapshot = repo().removeChannel(id)) }

    fun play(url: String) {
        if (current().operation == YouTubeOperation.REFRESH || current().operation == YouTubeOperation.LOAD) cancel()
        launch(YouTubeOperation.PLAY) {
            val canonical = YouTubeUrls.videoUrl(url)
            val id = "playback-${UUID.randomUUID()}"
            processId = id
            try {
                ensureActive()
                YouTubeEngine.initialize(getApplication())
                ensureActive()
                TaskResult(playback = YouTubeEngine.resolvePlayback(getApplication(), canonical, id))
            } finally {
                if (processId == id) processId = null
            }
        }
    }

    fun updateEngine() = launch(YouTubeOperation.UPDATE) {
        TaskResult(message = YouTubeEngine.update(getApplication()))
    }

    fun consumePlayback() {
        mutableState.value = current().copy(playback = null)
    }

    fun showMessage(message: String) {
        mutableState.value = current().copy(message = message)
    }

    fun cancel() {
        generation++
        task?.cancel(true)
        repository?.cancel()
        processId?.let(YouTubeEngine::cancel)
        mutableState.value = current().copy(operation = null, completedChannels = 0, totalChannels = 0, playback = null)
    }

    override fun onCleared() {
        cleared = true
        task?.cancel(true)
        repository?.cancel()
        processId?.let(YouTubeEngine::cancel)
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    private fun refreshFeeds(token: Int): SubscriptionSnapshot = repo().refresh { done, total ->
        publish(token) { it.copy(operation = YouTubeOperation.REFRESH, completedChannels = done, totalChannels = total) }
    }

    private fun repo(): SubscriptionRepository = repository ?: SubscriptionRepository(getApplication()).also { repository = it }

    private fun current(): YouTubeUiState = mutableState.value ?: YouTubeUiState()

    private fun launch(operation: YouTubeOperation, block: (Int) -> TaskResult) {
        if (current().operation != null || cleared) return
        val token = ++generation
        mutableState.value = current().copy(operation = operation, message = null, completedChannels = 0, totalChannels = 0, playback = null)
        task = executor.submit {
            try {
                ensureActive()
                val result = block(token)
                ensureActive()
                publish(token) { previous -> previous.copy(
                    operation = null,
                    snapshot = result.snapshot ?: previous.snapshot,
                    message = result.message,
                    playback = result.playback,
                ) }
            } catch (_: CancellationException) {
                publish(token) { it.copy(operation = null) }
            } catch (error: Exception) {
                publish(token) { it.copy(operation = null, message = error.message?.take(500) ?: "This operation failed. Please try again.") }
            }
        }
    }

    private fun publish(token: Int, transform: (YouTubeUiState) -> YouTubeUiState) {
        main.post { if (!cleared && token == generation) mutableState.value = transform(current()) }
    }

    private fun ensureActive() {
        if (cleared || Thread.currentThread().isInterrupted) throw CancellationException("Cancelled")
    }

    private data class TaskResult(
        val snapshot: SubscriptionSnapshot? = null,
        val message: String? = null,
        val playback: ResolvedVideo? = null,
    )
}
