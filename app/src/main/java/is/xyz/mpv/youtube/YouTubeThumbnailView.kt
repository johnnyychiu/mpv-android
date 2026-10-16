package `is`.xyz.mpv.youtube

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import coil.load
import `is`.xyz.mpv.R

/** A 16:9 viewport; Coil ties each request to the view and its Activity lifecycle. */
class YouTubeThumbnailView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
    }

    fun showVideo(videoId: String?) {
        val url = videoId?.takeIf(YouTubeUrls::isVideoId)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        // A new request also cancels any previous request on this recycled image view.
        load(url) {
            crossfade(150)
            placeholder(R.drawable.youtube_thumbnail_placeholder)
            error(R.drawable.youtube_thumbnail_placeholder)
            fallback(R.drawable.youtube_thumbnail_placeholder)
        }
    }

    /** Saved rows only read app-private artwork. They never fall back to a remote URL. */
    fun showSavedVideo(videoId: String?) {
        load(OfflineThumbnailStore(context).file(videoId)) {
            placeholder(R.drawable.youtube_thumbnail_placeholder)
            error(R.drawable.youtube_thumbnail_placeholder)
            fallback(R.drawable.youtube_thumbnail_placeholder)
        }
    }
}
