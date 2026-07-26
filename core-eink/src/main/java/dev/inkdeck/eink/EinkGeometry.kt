package dev.inkdeck.eink

import android.content.Context
import android.util.Log
import kotlin.math.roundToInt

/**
 * The app's real content box.
 *
 * Worth having as one function rather than scattered arithmetic because this device disagrees
 * with itself about its own size: the window frame is 758 × 1024 with only a 56 px top inset,
 * but `appBounds` and `Configuration` report 758 × 960. See [dev.inkdeck.eink.widget
 * .AppBoundsLinearLayout] for why.
 *
 * `Configuration.screenHeightDp` / `screenWidthDp` are the figures that match what a layout
 * actually receives, so those are what everything here derives from — including the terminal
 * cell grid in Phase 2, which would otherwise negotiate a PTY several rows taller than the
 * panel can show.
 */
object EinkGeometry {

    data class ContentBox(
        val widthPx: Int,
        val heightPx: Int,
        val widthDp: Int,
        val heightDp: Int,
        val density: Float,
    )

    fun contentBox(context: Context): ContentBox {
        val config = context.resources.configuration
        val density = context.resources.displayMetrics.density
        return ContentBox(
            widthPx = (config.screenWidthDp * density).roundToInt(),
            heightPx = (config.screenHeightDp * density).roundToInt(),
            widthDp = config.screenWidthDp,
            heightDp = config.screenHeightDp,
            density = density,
        )
    }

    /** One line to logcat, for reconciling the docs against the hardware. */
    fun log(context: Context, tag: String = "InkDeckGeometry") {
        val dm = context.resources.displayMetrics
        val box = contentBox(context)
        Log.i(
            tag,
            "content=${box.widthPx}x${box.heightPx}px (${box.widthDp}x${box.heightDp}dp) " +
                "density=${box.density} dpi=${dm.densityDpi} " +
                "displayMetrics=${dm.widthPixels}x${dm.heightPixels}px",
        )
    }
}
