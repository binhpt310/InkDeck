package dev.inkdeck.eink.widget

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Workaround for an InkReader 6 window-geometry inconsistency. Use this as the root of any
 * full-screen layout that pins chrome to the bottom.
 *
 * The device reports two different heights for the same window:
 *
 * ```
 *   mFrame  = [0,0][758,1024]        content insets = top 56, bottom 0
 *   appBounds = Rect(0, 0 - 758, 960)   screenHeightDp = 682   (= 904 px below the status bar)
 * ```
 *
 * SystemUI runs with `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION` set globally (`vsysui=0x2600`; the
 * OEM launcher does the same), so the window frame stretches over a 64 px navigation strip that
 * does not exist and reports no inset for it — while `appBounds` still excludes it.
 *
 * The consequence: `android:id/content` is *measured* at 1024 − 56 = 968 px but *laid out* at
 * 960 − 56 = 904 px. A vertical LinearLayout hands the whole 64 px surplus to its weighted
 * child, and everything below that child falls off the bottom of the screen. In the InkDeck
 * shell that put 64 of the tab bar's 74 px past the edge, leaving a 10 px sliver.
 *
 * The fix is to measure against the height the layout will actually get. `screenHeightDp` is
 * the authoritative figure — it is what the OEM's own Configuration reports and it already
 * excludes the status bar. Clamping only ever shrinks, so a correct window is left alone.
 */
class AppBoundsLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (
            resources.configuration.screenHeightDp * resources.displayMetrics.density
            ).roundToInt()
        val given = MeasureSpec.getSize(heightMeasureSpec)

        val spec = if (available in 1 until given) {
            if (!warned) {
                warned = true
                Log.i(
                    TAG,
                    "clamping height ${given}px -> ${available}px " +
                        "(screenHeightDp=${resources.configuration.screenHeightDp}, " +
                        "density=${resources.displayMetrics.density})",
                )
            }
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.getMode(heightMeasureSpec))
        } else {
            heightMeasureSpec
        }

        super.onMeasure(widthMeasureSpec, spec)
    }

    private var warned = false

    private companion object {
        const val TAG = "InkDeckBounds"
    }
}
