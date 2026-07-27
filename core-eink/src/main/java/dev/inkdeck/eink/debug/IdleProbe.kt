package dev.inkdeck.eink.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.inkdeck.eink.BuildConfig

/**
 * Phase 9 item 8 — battery/idle drain measurement harness.
 *
 * The agent that built Phase 9 could not run the 8 h measurement itself (the InkReader 6 was
 * offline for the duration of the session) and the project has no other telemetry channel. The
 * minimum useful thing this can do is emit a single-tag, time-stamped stream of every
 * activity/service/wake event, plus a 60-min battery level sample, so the owner can run
 *
 *   adb logcat -d -s InkDeckIdle -v time > idle.log
 *
 * after an 8 h shift and have a record to read. That is what this class is.
 *
 * Guarded by [BuildConfig.DEBUG] — release builds are no-op. The 60-min timer is the only
 * thing that keeps firing in steady state, and it dies when [stop] is called or the process is
 * collected.
 */
object IdleProbe {

    private const val TAG = "InkDeckIdle"
    private const val SAMPLE_PERIOD_MS = 60L * 60L * 1000L
    private const val STARTED_MARKER = "started"
    private const val STOPPED_MARKER = "stopped"

    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var wakeReceiver: BroadcastReceiver? = null

    /**
     * Start the probe. Idempotent — a second call while already running is a no-op. The owner
     * should call this from `Application.onCreate`; the agent wired it from `MainActivity` to
     * keep the surface area small (see [dev.inkdeck.ui.MainActivity.onResume]).
     */
    @Synchronized
    fun start(context: Context) {
        if (!BuildConfig.DEBUG) return
        if (tick != null) return
        Log.i(TAG, STARTED_MARKER)
        // First sample is one period in, so the first event is `started` and subsequent events
        // are `+60m`, `+120m`, … — easier to read off `awk` than two kinds of marker.
        scheduleNext(context)
        if (wakeReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_ON) {
                        Log.i(TAG, "screen-on")
                    } else if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        Log.i(TAG, "screen-off")
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.applicationContext.registerReceiver(r, filter)
            wakeReceiver = r
        }
    }

    /**
     * Stop the probe. Cancels the timer and unregisters the receiver. Safe to call from any
     * thread; the receiver unregister must happen on the main thread on API 27, and this is
     * a debug-only API called from `onResume`/`onPause`, so the caller is already there.
     */
    @Synchronized
    fun stop() {
        if (tick == null && wakeReceiver == null) return
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        Log.i(TAG, STOPPED_MARKER)
    }

    /** Record an activity-lifecycle event from `MainActivity.onResume` / `onPause`. */
    fun activityResumed(name: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "activity-resume $name")
    }

    fun activityPaused(name: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "activity-pause $name")
    }

    /** Record a service start/stop from each foreground service's own lifecycle. */
    fun serviceStarted(name: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "service-start $name")
    }

    fun serviceStopped(name: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "service-stop $name")
    }

    private fun scheduleNext(context: Context) {
        val r = Runnable {
            sampleBattery(context.applicationContext)
            scheduleNext(context)
        }
        tick = r
        handler.postDelayed(r, SAMPLE_PERIOD_MS)
    }

    private fun sampleBattery(context: Context) {
        // Stick to the sticky broadcast — no permission needed and no callback to wire.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        Log.i(TAG, "battery pct=$pct")
    }
}
