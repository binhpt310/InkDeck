package dev.inkdeck.telegram

import android.content.Context
import android.content.SharedPreferences
import dev.inkdeck.data.vault.SecretVault
import java.security.SecureRandom

/**
 * Everything the bot needs to remember, split by sensitivity — Plan.md §7.4 mitigation 3.
 *
 * ```
 *   SecretVault      TELEGRAM_BOT_TOKEN    the bot token
 *                    TELEGRAM_CHAT_ID      the allowlisted chat
 *   SharedPreferences enabled, autoDelete, updateOffset, pairing code, @username
 * ```
 *
 * The split is not arbitrary. The token is the whole credential and lives in the same encrypted
 * vault as the SSH keys. The chat id is not secret, but it *is* the allowlist (§7.4 mitigation 2),
 * and an allowlist an attacker with `filesDir` access can rewrite is not an allowlist — so it goes
 * in the vault too, where a rewrite fails the AEAD tag instead of silently succeeding. The poll
 * offset and the toggles are neither secret nor security-relevant and would only cost an AES-GCM
 * round trip per update if they were.
 *
 * The vault ids are exactly the keys `AdbImport` derives from a pushed `.env`
 * (`TELEGRAM_BOT_TOKEN=…`), so a token sideloaded over USB is already where this module looks
 * for it. That is the intended setup path — see design.md §12's warning box for why typing it
 * through the bot is not.
 */
internal class TelegramStore(context: Context) {

    private val app = context.applicationContext

    private val prefs: SharedPreferences =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ toggles

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * design.md §12 shows this defaulting ON, and Plan.md §7.4 calls it the mitigation that makes
     * `/llm` defensible at all. Defaulting it off would ship the convenience without the thing
     * that pays for it.
     */
    var autoDelete: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DELETE, value).apply()

    /**
     * `getUpdates` offset. Persisted rather than held in memory because the ROM force-stops this
     * process (Plan.md §5.1b-2): an in-memory offset would replay the whole backlog on every
     * restart, and a `/llm` replayed after its message was deleted is a stored key nobody asked
     * for.
     */
    var updateOffset: Long
        get() = prefs.getLong(KEY_OFFSET, 0L)
        set(value) = prefs.edit().putLong(KEY_OFFSET, value).apply()

    /** Cached from `getMe` purely so the settings header can say `@name` — never authoritative. */
    var botUsername: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    // ------------------------------------------------------------------ pairing

    /**
     * The six-digit code from design.md §12, generated on demand and shown on the settings
     * screen. Generated with [SecureRandom], not `Random`: it is a one-shot bearer credential for
     * the allowlist, and a predictable one would let anybody who found the bot claim it.
     */
    fun pairingCode(): String {
        prefs.getString(KEY_PAIR_CODE, null)?.let { return it }
        return regeneratePairingCode()
    }

    fun regeneratePairingCode(): String {
        val code = (RANDOM.nextInt(900_000) + 100_000).toString()
        prefs.edit()
            .putString(KEY_PAIR_CODE, code)
            .putInt(KEY_PAIR_ATTEMPTS, 0)
            .apply()
        return code
    }

    /**
     * Count a wrong `/pair`, and roll the code once [MAX_PAIR_ATTEMPTS] have failed.
     *
     * Six digits is a million guesses, which is not much against a script — but rotating the code
     * after five wrong tries makes guessing pointless, because the target moves and the user can
     * see on screen that it did. @return true if the code was rotated.
     */
    fun notePairFailure(): Boolean {
        val next = prefs.getInt(KEY_PAIR_ATTEMPTS, 0) + 1
        if (next >= MAX_PAIR_ATTEMPTS) {
            regeneratePairingCode()
            return true
        }
        prefs.edit().putInt(KEY_PAIR_ATTEMPTS, next).apply()
        return false
    }

    // ------------------------------------------------------------------ vault

    /**
     * The vault, unlocked, or null if it cannot be opened without the user.
     *
     * Blocking: [SecretVault.unlockAuto] does a key unwrap, and in passphrase mode there is
     * nothing this module can do on its own — the caller degrades to a "vault locked" state
     * rather than prompting from a background service.
     */
    fun openVault(): SecretVault? {
        val vault = SecretVault.get(app)
        if (!vault.isInitialised) return null
        if (!vault.isUnlocked && !vault.unlockAuto()) return null
        return vault
    }

    fun hasToken(vault: SecretVault): Boolean = vault.contains(ID_BOT_TOKEN)

    /**
     * Is there a token at all, without opening the vault? [SecretVault.contains] is a file
     * existence check — no key unwrap, no decrypt — so this is safe on a path that has ten
     * seconds and no business doing crypto. Says nothing about whether the token is *valid*.
     */
    fun hasStoredToken(): Boolean = SecretVault.get(app).contains(ID_BOT_TOKEN)

    /**
     * A cheap "is a chat paired?" flag, mirrored out of the vault.
     *
     * The vault copy stays the **sole authority for which chat is allowed** — this is a boolean,
     * never an id, so rewriting it cannot redirect anything to an attacker's chat; the worst it
     * can do is make [TelegramNotifier.canNotify] answer wrongly and send a reminder down the
     * fallback route. That is the trade being bought: `ReminderDelivery` needs an answer without
     * a PBKDF2-or-keystore unwrap on a BroadcastReceiver's ten-second budget.
     *
     * Kept honest by [reconcilePairedHint], which the poll loop calls every cycle.
     */
    var pairedHint: Boolean
        get() = prefs.getBoolean(KEY_PAIRED_HINT, false)
        private set(value) = prefs.edit().putBoolean(KEY_PAIRED_HINT, value).apply()

    /**
     * Re-derive [pairedHint] from the vault, the only place that actually knows. The poll loop
     * has already read the chat id by the time it calls this, so it passes the answer in rather
     * than paying for a second decrypt.
     */
    fun notePaired(paired: Boolean) {
        if (paired != pairedHint) pairedHint = paired
    }

    /**
     * Read the token. **Call this at the point of use and let the result go out of scope.**
     * Nothing in this module keeps it in a field, puts it in a log line, or in a query string —
     * see [TelegramClient] for how it reaches the wire.
     *
     * Honest limitation: [SecretVault.getString] hands back a `String`, which cannot be zeroed.
     * Narrowing the lifetime is all that is available without changing `:core-data`.
     */
    fun token(vault: SecretVault): String? =
        runCatching { vault.getString(ID_BOT_TOKEN) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun pairedChatId(vault: SecretVault): Long? =
        runCatching { vault.getString(ID_CHAT_ID).trim().toLong() }.getOrNull()

    fun setPairedChatId(vault: SecretVault, chatId: Long) {
        vault.putString(ID_CHAT_ID, chatId.toString())
        pairedHint = true
    }

    fun clearPairing(vault: SecretVault) {
        vault.delete(ID_CHAT_ID)
        pairedHint = false
        regeneratePairingCode()
        // The backlog belongs to the old pairing. Keeping the offset is right — dropping it would
        // replay messages from the chat that was just disconnected.
    }

    companion object {
        private const val PREFS = "telegram"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_DELETE = "auto_delete"
        private const val KEY_OFFSET = "update_offset"
        private const val KEY_USERNAME = "bot_username"
        private const val KEY_PAIRED_HINT = "paired"
        private const val KEY_PAIR_CODE = "pair_code"
        private const val KEY_PAIR_ATTEMPTS = "pair_attempts"

        private const val MAX_PAIR_ATTEMPTS = 5

        /** Matches `AdbImport.vaultIdFor("TELEGRAM_BOT_TOKEN")` — a pushed `.env` lands here. */
        const val ID_BOT_TOKEN = "TELEGRAM_BOT_TOKEN"
        const val ID_CHAT_ID = "TELEGRAM_CHAT_ID"

        private val RANDOM = SecureRandom()
    }
}
