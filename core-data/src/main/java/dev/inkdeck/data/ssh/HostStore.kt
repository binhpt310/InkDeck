package dev.inkdeck.data.ssh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the host list as a single JSON file in `filesDir`.
 *
 * Not Room. Plan.md §3.2 makes Room the source of truth for tasks, where queries, ordering and
 * reactive reads earn it; a handful of host entries read once at screen open does not. This
 * keeps Phase 2 free of a schema and a migration path.
 *
 * Contains no secrets — [HostEntry.identityVaultId] is a vault id, and the key bytes live in
 * [dev.inkdeck.data.vault.SecretVault].
 */
class HostStore(context: Context) {

    private val file = File(context.filesDir, "hosts.json")

    fun load(): List<HostEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { HostEntry.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(hosts: List<HostEntry>) {
        val array = JSONArray()
        hosts.forEach { array.put(it.toJson()) }
        writeAtomic(array.toString())
    }

    /** Insert or replace by [HostEntry.alias]. */
    fun upsert(entry: HostEntry) {
        val hosts = load().filterNot { it.alias == entry.alias } + entry
        save(hosts.sortedBy { it.alias })
    }

    fun remove(alias: String) {
        save(load().filterNot { it.alias == alias })
    }

    fun find(alias: String): HostEntry? = load().firstOrNull { it.alias == alias }

    private fun writeAtomic(text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(file)) { "could not replace ${file.name}" }
    }
}

/**
 * Trust-on-first-use host key pinning — Plan.md §4.2, design.md §7.5.
 *
 * A pin records the exact key blob the server presented the first time. A later mismatch is
 * surfaced as a hard failure, not a warning: the whole point is that the key reaching a trading
 * server changing under you is the one event worth interrupting for.
 *
 * Deliberately not `~/.ssh/known_hosts` format. A hashed OpenSSH file buys nothing here (there
 * is no shell to leak it to) and parsing it correctly is more code than this.
 */
class KnownHostsStore(context: Context) {

    data class Pin(
        val host: String,
        val port: Int,
        /** e.g. `ssh-ed25519`, `ssh-rsa`. */
        val keyType: String,
        /** Base64 of the raw key blob, as the server sent it. */
        val keyBase64: String,
        val firstSeenEpochMs: Long,
    ) {
        val fingerprint: String get() = sha256Fingerprint(decodeKey(keyBase64))
    }

    private val file = File(context.filesDir, "known_hosts.json")

    fun all(): List<Pin> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Pin(
                    host = o.getString("host"),
                    port = o.optInt("port", 22),
                    keyType = o.getString("keyType"),
                    keyBase64 = o.getString("key"),
                    firstSeenEpochMs = o.optLong("firstSeen", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun find(host: String, port: Int): Pin? =
        all().firstOrNull { it.host.equals(host, ignoreCase = true) && it.port == port }

    fun pin(pin: Pin) {
        val kept = all().filterNot {
            it.host.equals(pin.host, ignoreCase = true) && it.port == pin.port
        }
        write(kept + pin)
    }

    fun remove(host: String, port: Int) {
        write(all().filterNot { it.host.equals(host, ignoreCase = true) && it.port == port })
    }

    private fun write(pins: List<Pin>) {
        val array = JSONArray()
        pins.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("host", p.host)
                    put("port", p.port)
                    put("keyType", p.keyType)
                    put("key", p.keyBase64)
                    put("firstSeen", p.firstSeenEpochMs)
                }
            )
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(array.toString())
        check(tmp.renameTo(file)) { "could not replace ${file.name}" }
    }

    companion object {
        fun encodeKey(raw: ByteArray): String =
            android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)

        fun decodeKey(encoded: String): ByteArray =
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)

        /** OpenSSH's `SHA256:…` form — unpadded base64, so it can be eyeballed against `ssh-keyscan`. */
        fun sha256Fingerprint(rawKey: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rawKey)
            val b64 = android.util.Base64.encodeToString(
                digest,
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            return "SHA256:$b64"
        }
    }
}
