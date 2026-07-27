package dev.inkdeck.eink.refresh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * How a full-panel waveform flush is triggered — Plan.md §3.4.
 *
 * The device is not rooted and exposes no waveform API, so neither option here is a real EPD
 * call. [BroadcastFlush] pokes the OEM's own hook; [InvertRestoreFlush] fakes it by forcing a
 * full-screen black/white transition that the panel controller cannot satisfy with a partial
 * update. Which one actually works is a question only the panel can answer — see EinkLabActivity.
 */
interface FlushStrategy {
    val id: String
    fun flush(activity: Activity, onComplete: (() -> Unit)? = null)
}

const val ACTION_EINK_FORCE_REFRESH = "android.eink.force.refresh"

internal const val REFRESH_LOG = "InkDeckRefresh"

/** 16.0 fps panel (Plan.md §0) — one frame is 62.5 ms. */
const val PANEL_FRAME_MS = 63L

/**
 * Send the OEM's `android.eink.force.refresh` broadcast — the intent SystemUI's status-bar
 * refresh arrow sends. Unprotected, so an ordinary app can send it.
 *
 * **Verified working on the panel** (Phase 1, on-device A/B against [InvertRestoreFlush] and a
 * no-flush control). Sending this from an ordinary app visibly flushes the display and clears
 * accumulated ghosting.
 *
 * Worth recording because the static analysis said otherwise: `dumpsys activity broadcasts`
 * shows *no* receiver for this action — neither a manifest entry in the Receiver Resolver Table
 * nor a dynamic BroadcastFilter — and every send completes in a few ms with zero recipients.
 * Whatever services it sits below the app framework (the Allwinner `aw_display` /
 * `com.softwinner.IDisplayService` path is the likely candidate) and is invisible to `dumpsys`.
 * The lesson for this device: the resolver table is not evidence of absence.
 *
 * **Phase 9 item 4 — off the UI thread.** `sendBroadcast` is a binder call. Done on the caller's
 * thread it can take a millisecond or more; in a flurry of `[F]` events (four task toggles in
 * a row, a tab switch fired from a click handler, a long-press flush) it serialises everything
 * on the main thread for no benefit. We post the send to the main `Handler` and invoke
 * `onComplete` after the post runs, which keeps the call contract synchronous from the
 * refresher's point of view while taking the IPC off the click-handler critical path.
 */
class BroadcastFlush(context: Context) : FlushStrategy {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override val id = "broadcast"

    override fun flush(activity: Activity, onComplete: (() -> Unit)?) {
        mainHandler.post {
            appContext.sendBroadcast(Intent(ACTION_EINK_FORCE_REFRESH))
            Log.d(REFRESH_LOG, "flush strategy=broadcast action=$ACTION_EINK_FORCE_REFRESH")
            onComplete?.invoke()
        }
    }
}

/**
 * Plan.md §3.4 fallback: cover the window in full black for a couple of frames, then full
 * white for one, then restore. Every pixel changes twice, which forces the controller into a
 * full-screen waveform and clears accumulated ghosting.
 *
 * Costs ~190 ms of blanked screen. That is the price of the only flush this hardware reliably
 * gives an unprivileged app, and it is why the ghost budget exists rather than flushing freely.
 */
class InvertRestoreFlush(
    private val blackMs: Long = PANEL_FRAME_MS * 2,
    private val whiteMs: Long = PANEL_FRAME_MS,
) : FlushStrategy {

    override val id = "invert-restore"

    override fun flush(activity: Activity, onComplete: (() -> Unit)?) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root == null) {
            onComplete?.invoke()
            return
        }
        // Re-entrancy guard: a flush already in flight subsumes this one.
        if (root.findViewWithTag<View>(TAG) != null) {
            onComplete?.invoke()
            return
        }

        val veil = View(activity).apply {
            tag = TAG
            setBackgroundColor(Color.BLACK)
            // Swallow taps for the ~190 ms the screen is unreadable, so a stray press does not
            // land on whatever is underneath.
            isClickable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(veil)
        Log.d(REFRESH_LOG, "flush strategy=invert-restore black=${blackMs}ms white=${whiteMs}ms")

        veil.postDelayed({
            veil.setBackgroundColor(Color.WHITE)
            veil.postDelayed({
                (veil.parent as? ViewGroup)?.removeView(veil)
                onComplete?.invoke()
            }, whiteMs)
        }, blackMs)
    }

    private companion object {
        const val TAG = "inkdeck-flush-veil"
    }
}

/** Runs the broadcast first, then the invert-restore. Used to A/B the two in EinkLabActivity. */
class CompositeFlush(private val first: FlushStrategy, private val second: FlushStrategy) :
    FlushStrategy {

    override val id = "${first.id}+${second.id}"

    override fun flush(activity: Activity, onComplete: (() -> Unit)?) {
        first.flush(activity) { second.flush(activity, onComplete) }
    }
}

object FlushStrategies {

    /**
     * [BroadcastFlush], confirmed on the panel in Phase 1 — Plan.md §3.4.
     *
     * This is the outcome Plan.md hoped for. The flush costs one intent instead of ~190 ms of
     * blanked, tap-swallowing screen, which matters most in the terminal: an invert-restore
     * flush there would black out output mid-read every time the ghost budget tripped.
     *
     * The panel still performs a visible full-screen waveform, so a flush is cheap for the app
     * but not free for the reader. The ghost budget stays.
     *
     * [InvertRestoreFlush] is kept as a fallback for any surface where the broadcast turns out
     * not to be enough.
     */
    fun default(context: Context): FlushStrategy = BroadcastFlush(context)
}

/** For measuring what the UI looks like with no flush at all. */
object NoFlush : FlushStrategy {
    override val id = "none"
    override fun flush(activity: Activity, onComplete: (() -> Unit)?) {
        Log.d(REFRESH_LOG, "flush strategy=none (suppressed)")
        onComplete?.invoke()
    }
}
