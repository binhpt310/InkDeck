package dev.inkdeck.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.inkdeck.telegram.command.CommandRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * The long-poll loop, held up by a foreground service — Plan.md §7.1, §7.3, §5.1b-2.
 *
 * ### Why a foreground service, and why that is still not enough
 *
 * `getUpdates` has to be *waiting* when a message arrives, which means a live process. This ROM
 * force-stops backgrounded packages ~30 s after they are re-evaluated (Plan.md §5.1b-2) and
 * refuses `AlarmManager` outright (§5.1b), so there is no timer that could restart us and no
 * schedule that could stand in for the poll. A foreground service is the only thing that keeps
 * the process alive at all — and Phase 4 measured it being killed anyway at `adj 200`.
 *
 * **So reconnect-on-resume is the design, not the fallback.** [TelegramGraph.start] is
 * idempotent and cheap, and the app calls it on every resume. The honest contract is: messages
 * sent while the device is asleep are delivered when it next runs, in one burst, because the
 * `getUpdates` offset is persisted (see [TelegramStore.updateOffset]) and Telegram holds the
 * backlog for 24 h. That is not push. It is the most this hardware allows.
 *
 * ### Notification
 *
 * `IMPORTANCE_MIN`, ongoing, no timestamp — same shape as `ReminderGuardService`. It exists
 * because the foreground-service contract demands one, not because anyone wants to read it.
 *
 * ### Backoff
 *
 * Failures double from 5 s to a 5-minute cap. An *empty* poll is a success: `getUpdates` is
 * supposed to return nothing after 50 quiet seconds, and treating that as a fault would back the
 * poller off to five minutes on a perfectly healthy connection within a few minutes of idling.
 */
class TelegramService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    private lateinit var store: TelegramStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = TelegramStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !store.enabled) {
            stopCleanly()
            return START_NOT_STICKY
        }

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // Phase 9 item 8: log the service boundary. The probe is the only place that records the
        // start/stop in a single tag for the 8 h drain measurement.
        dev.inkdeck.eink.debug.IdleProbe.serviceStarted("TelegramService")

        // Idempotent: every resume calls start(), and re-entering a running loop would open a
        // second long poll on the same bot — Telegram answers the older one with a 409 and both
        // pollers thrash.
        if (loop?.isActive != true) {
            loop = scope.launch { run() }
        }

        // NOT_STICKY for the same reason ReminderGuardService gives: a force-stopped package is
        // not restarted, so a sticky flag buys nothing and adds a second racing start path.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        scope.cancel()
        TelegramState.set(TelegramState.Phase.STOPPED)
        dev.inkdeck.eink.debug.IdleProbe.serviceStopped("TelegramService")
        super.onDestroy()
    }

    private fun stopCleanly() {
        loop?.cancel()
        loop = null
        TelegramState.set(TelegramState.Phase.STOPPED)
        dev.inkdeck.eink.debug.IdleProbe.serviceStopped("TelegramService")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ the loop

    private suspend fun run() {
        Log.i(TAG, "poll loop start")
        var backoffMs = BACKOFF_MIN_MS

        // The supplier, not the token. Invoked per request, so the plaintext exists for the life
        // of one call rather than for the ~50 s the socket is parked — see TelegramClient.
        val client = TelegramClient {
            store.openVault()?.let { store.token(it) }
        }
        val router = CommandRouter(this, store, client).apply {
            onStateChanged = { refreshNotification() }
        }

        while (coroutineContext.isActive) {
            if (!store.enabled) break

            // Drained at the top of every cycle, including the very first — a reminder queued
            // while the process was dead must go out before this loop parks in the 50 s
            // getUpdates call, not after it returns.
            runCatching { TelegramNotifier.drainQueue(this@TelegramService) }
                .onFailure { Log.w(TAG, "outbound drain failed", it) }

            val vault = store.openVault()
            if (vault == null) {
                // Passphrase mode, or no vault yet. Nothing a background service can do about
                // either; park rather than spin, and let the settings screen say which it is.
                TelegramState.set(TelegramState.Phase.VAULT_LOCKED)
                refreshNotification()
                delay(IDLE_RECHECK_MS)
                continue
            }
            if (!store.hasToken(vault)) {
                TelegramState.set(TelegramState.Phase.NO_TOKEN)
                refreshNotification()
                delay(IDLE_RECHECK_MS)
                continue
            }

            if (store.botUsername == null) {
                // One call, once, purely so the settings header can say @name. A failure here is
                // not a reason to stop polling.
                store.botUsername = client.getMe()
            }

            val paired = store.pairedChatId(vault)
            // Keep the cheap mirror honest. TelegramNotifier.canNotify reads it from a
            // BroadcastReceiver that cannot afford to open the vault, and a stale `true` there
            // would send a reminder into a chat that no longer exists *instead of* the local
            // notification — ReminderDelivery stops at the first route that claims success.
            store.notePaired(paired != null)

            TelegramState.update {
                it.copy(
                    phase = if (paired == null) TelegramState.Phase.UNPAIRED
                    else TelegramState.Phase.CONNECTED,
                    botUsername = store.botUsername,
                    chatId = paired,
                    retryInSeconds = 0,
                )
            }
            refreshNotification()

            val updates = client.getUpdates(store.updateOffset)
            if (updates == null) {
                val seconds = (backoffMs / 1000).toInt()
                TelegramState.update {
                    it.copy(phase = TelegramState.Phase.RETRYING, retryInSeconds = seconds)
                }
                refreshNotification()
                // Phase 9 item 6: was Log.d, fires once per backoff cycle. Demoted to Log.v so
                // an 8 h idle logcat is a few lines of flushes, not thousands of poll retries.
                Log.v(TAG, "poll failed, retrying in ${seconds}s")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                continue
            }

            backoffMs = BACKOFF_MIN_MS

            for (update in updates) {
                // Advance the offset **before** handling, not after. Telegram re-delivers
                // anything not acknowledged, so a command that crashes mid-handling would
                // otherwise be replayed on every poll forever — and for /llm that means storing
                // a key whose message has already been deleted, in a loop.
                store.updateOffset = update.updateId + 1
                runCatching { router.handle(update, vault) }
                    .onFailure { Log.w(TAG, "handler failed for update ${update.updateId}", it) }
            }
            if (updates.isNotEmpty()) {
                TelegramState.update { it.copy(lastMessageAt = System.currentTimeMillis()) }
            }
        }

        Log.i(TAG, "poll loop stop")
        TelegramState.set(TelegramState.Phase.STOPPED)
    }

    // ------------------------------------------------------------------ notification

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val status = TelegramState.status.value
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = open?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val text = when (status.phase) {
            TelegramState.Phase.CONNECTED -> getString(R.string.tg_notif_listening)
            TelegramState.Phase.UNPAIRED -> getString(R.string.tg_notif_unpaired)
            TelegramState.Phase.NO_TOKEN -> getString(R.string.tg_notif_no_token)
            TelegramState.Phase.VAULT_LOCKED -> getString(R.string.tg_notif_vault_locked)
            TelegramState.Phase.RETRYING ->
                getString(R.string.tg_notif_retrying, status.retryInSeconds)
            TelegramState.Phase.STOPPED -> getString(R.string.tg_notif_stopped)
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_telegram)
            .setContentTitle(getString(R.string.tg_notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.tg_channel_poll),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.tg_channel_poll_detail)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "InkDeckTg"

        private const val CHANNEL = "telegram_poll"
        private const val NOTIFICATION_ID = 0x1DED

        const val ACTION_STOP = "dev.inkdeck.telegram.STOP"

        private const val BACKOFF_MIN_MS = 5_000L

        /** Plan.md §7.3's slowest documented interval. Beyond this the bot is simply off. */
        private const val BACKOFF_MAX_MS = 5 * 60_000L

        /** How often to re-check a missing token or a locked vault. */
        private const val IDLE_RECHECK_MS = 60_000L
    }
}
