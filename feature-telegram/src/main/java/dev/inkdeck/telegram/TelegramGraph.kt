package dev.inkdeck.telegram

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.fragment.app.Fragment
import dev.inkdeck.tasks.alarm.ReminderDelivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hand wiring for the Telegram feature — same shape and the same reasoning as
 * `dev.inkdeck.tasks.TaskGraph`: no DI framework, a plain application-context singleton, because
 * the things that need to reach it (a Service started cold, a Fragment, a reminder receiver) have
 * no Application object in common at the moment they run.
 *
 * ### What the app module has to call
 *
 * ```kotlin
 * // MainActivity.onResume — reconnect-on-resume is the design, see TelegramService
 * TelegramGraph.startIfEnabled(this)
 * ```
 *
 * That is the whole integration besides showing [settingsFragment]. Everything else — enabling,
 * pairing, disconnecting — is driven from the settings screen.
 */
object TelegramGraph {

    private const val TAG = "InkDeckTg"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var routeRegistered = false

    /** Live status for the settings screen and anything else that wants to show connectivity. */
    val status get() = TelegramState.status

    /**
     * Start the poll loop if the user has turned the feature on. Idempotent and safe to call on
     * every resume — that repetition *is* the reconnect strategy, because the ROM force-stops the
     * process and no timer survives it (Plan.md §5.1b, §5.1b-2).
     */
    fun startIfEnabled(context: Context) {
        val app = context.applicationContext
        // Registered even when the feature is off. The route is inert by predicate rather than by
        // absence (see [registerReminderRoute]), so there is no state where the app has to
        // remember to add it back.
        registerReminderRoute(app)
        if (!TelegramStore(app).enabled) return
        start(app)
    }

    /**
     * Put Telegram ahead of the local notification in `ReminderDelivery` — the task module never
     * learns this module exists, so registration is this module's job and the app module's
     * business is only `startIfEnabled`.
     *
     * **Inert, not unregistered, when the bot is off.** Deregistering on disable was the obvious
     * alternative and is worse twice over: `ReminderDelivery.clear()` would take out any other
     * module's routes as collateral, and a route that comes and goes has to be re-added from
     * every path that could re-enable the feature. Instead the route asks
     * [TelegramNotifier.notifyTask], which returns false whenever the bot cannot deliver, and
     * `dispatch` falls through to the local notification exactly as if nothing were registered.
     *
     * Once per process: `register` prepends unconditionally, so calling it on every resume would
     * stack a new copy of the same route each time.
     */
    @Synchronized
    private fun registerReminderRoute(app: Context) {
        if (routeRegistered) return
        routeRegistered = true
        ReminderDelivery.register { context, task -> TelegramNotifier.notifyTask(context, task) }
        Log.i(TAG, "registered Telegram as a reminder route")
    }

    fun start(context: Context) {
        val app = context.applicationContext
        try {
            app.startForegroundService(Intent(app, TelegramService::class.java))
        } catch (e: IllegalStateException) {
            // Refused when the app is not in a state allowed to start a foreground service. The
            // next resume tries again; there is nothing useful to do here and crashing the
            // launch path over a background poller would be the wrong trade.
            Log.w(TAG, "poll service refused", e)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        app.startService(
            Intent(app, TelegramService::class.java).setAction(TelegramService.ACTION_STOP)
        )
    }

    /**
     * Turn the feature on or off and bring the service in line. Writing the flag is cheap;
     * everything else here is I/O-free, so this is safe from the main thread.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        TelegramStore(app).enabled = enabled
        registerReminderRoute(app)
        if (enabled) start(app) else stop(app)
    }

    fun isEnabled(context: Context): Boolean = TelegramStore(context).enabled

    /** The screen from design.md §12. The host owns where it goes in the back stack. */
    fun settingsFragment(): Fragment = TelegramSettingsFragment()

    /**
     * Forget the paired chat and roll the pairing code — design.md §12's Disconnect / Re-pair.
     *
     * Vault work, so it runs off the main thread and reports back on completion rather than
     * returning a value the caller would have to block for.
     */
    fun unpairAsync(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        scope.launch {
            val store = TelegramStore(app)
            store.openVault()?.let { store.clearPairing(it) }
            TelegramState.update {
                it.copy(phase = TelegramState.Phase.UNPAIRED, chatId = null)
            }
            // Back to main: the only caller is a Fragment redrawing itself.
            withContext(Dispatchers.Main) { onDone?.invoke() }
        }
    }
}
