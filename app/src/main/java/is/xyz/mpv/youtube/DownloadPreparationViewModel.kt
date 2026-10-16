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

data class DownloadPreparationRequest(val url: String, val format: DownloadFormat)

data class PreparedDownload(
    val url: String,
    val title: String,
    val videoId: String,
    val format: DownloadFormat,
)

data class DownloadPreparationState(
    val request: DownloadPreparationRequest? = null,
    val ready: PreparedDownload? = null,
    val error: String? = null,
) {
    val preparing: Boolean get() = request != null
}

/** Keeps title lookup across rotation and never holds an Activity or launches its picker. */
class DownloadPreparationViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableLiveData(DownloadPreparationState())
    val state: LiveData<DownloadPreparationState> = mutableState
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cleared = false
    @Volatile private var generation = 0
    private var processId: String? = null
    private var task: Future<*>? = null

    fun prepare(url: String, format: DownloadFormat) {
        if (cleared || current().preparing || current().ready != null) return
        val canonical = try {
            YouTubeUrls.videoUrl(url)
        } catch (error: IllegalArgumentException) {
            mutableState.value = DownloadPreparationState(error = error.message ?: "Enter a valid YouTube video link.")
            return
        }
        val token = ++generation
        val id = "download-metadata-${UUID.randomUUID()}"
        processId = id
        mutableState.value = DownloadPreparationState(request = DownloadPreparationRequest(canonical, format))
        task = executor.submit {
            try {
                ensureActive(token)
                val metadata = YouTubeEngine.readMetadata(getApplication(), canonical, id)
                ensureActive(token)
                publish(token, DownloadPreparationState(ready = PreparedDownload(
                    canonical, metadata.title, metadata.videoId, format,
                )))
            } catch (_: CancellationException) {
                publish(token, DownloadPreparationState())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                publish(token, DownloadPreparationState())
            } catch (error: Exception) {
                publish(token, DownloadPreparationState(error = error.message?.takeIf(String::isNotBlank)?.take(500)
                    ?: "Could not read the video title. Please try again."))
            }
        }
    }

    /** Called on the main thread before launching an external permission dialog or save picker. */
    fun consumeReady(): PreparedDownload? = current().ready?.also {
        mutableState.value = current().copy(ready = null)
    }

    fun consumeError() {
        if (current().error != null) mutableState.value = current().copy(error = null)
    }

    fun cancel() {
        generation++
        task?.cancel(true)
        task = null
        processId?.let(YouTubeEngine::cancel)
        processId = null
        mutableState.value = DownloadPreparationState()
    }

    override fun onCleared() {
        cleared = true
        generation++
        task?.cancel(true)
        processId?.let(YouTubeEngine::cancel)
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    private fun current(): DownloadPreparationState = mutableState.value ?: DownloadPreparationState()

    private fun ensureActive(token: Int) {
        if (cleared || token != generation || Thread.currentThread().isInterrupted) throw CancellationException("Cancelled")
    }

    private fun publish(token: Int, value: DownloadPreparationState) {
        main.post {
            if (!cleared && token == generation) {
                processId = null
                task = null
                mutableState.value = value
            }
        }
    }
}
