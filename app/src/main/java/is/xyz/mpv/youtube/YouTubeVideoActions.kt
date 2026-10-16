package `is`.xyz.mpv.youtube

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** Keep the controls compact normally, but allow two rows instead of clipping scaled text. */
class YouTubeVideoActions @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childCount == 3 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val watch = getChildAt(0)
            val spacer = getChildAt(1)
            val downloads = getChildAt(2)
            val naturalWidth = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            watch.measure(naturalWidth, heightMeasureSpec)
            downloads.measure(naturalWidth, heightMeasureSpec)
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val stacked = watch.measuredWidth + downloads.measuredWidth > availableWidth
            orientation = if (stacked) VERTICAL else HORIZONTAL
            spacer.visibility = if (stacked) View.GONE else View.VISIBLE
            downloads.layoutParams.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
