package dev.inkdeck.terminal

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import dev.inkdeck.data.ssh.KnownHostsStore
import dev.inkdeck.data.ssh.StrictHostKeyChecking

/**
 * Trust-on-first-use host key verification — Plan.md §4.2, design.md §7.5.
 *
 * The decision is made entirely here rather than through JSch's own `StrictHostKeyChecking=ask`
 * prompt flow, so the session config is pinned to `yes` and JSch simply obeys [check]. That
 * keeps one policy in one place, and lets the prompt show a real SHA256 fingerprint instead of
 * JSch's stock message.
 *
 * [approval] is called on the SSH thread and must block until the user answers.
 */
class InkDeckHostKeyRepository(
    private val store: KnownHostsStore,
    private val mode: StrictHostKeyChecking,
    private val approval: HostKeyApproval,
) : HostKeyRepository {

    data class Request(
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        /** Only set on a mismatch — the fingerprint that was pinned before. */
        val previousFingerprint: String? = null,
    )

    interface HostKeyApproval {
        /**
         * A host not seen before. Return true to pin and connect.
         * Called on the SSH thread; blocking is expected.
         */
        fun approveUnknown(request: Request): Boolean

        /**
         * The pinned key no longer matches. This is the event the whole scheme exists to catch,
         * so it is a notification rather than a question — the connection is already refused.
         */
        fun reportChanged(request: Request)
    }

    override fun check(host: String?, key: ByteArray?): Int {
        if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED

        // Per-host opt-out. Available because the user's own ssh_config asks for it, never the
        // default, because this key reaches a trading server.
        if (mode == StrictHostKeyChecking.NO) return HostKeyRepository.OK

        val (name, port) = splitHostPort(host)
        val encoded = KnownHostsStore.encodeKey(key)
        val pinned = store.find(name, port)

        return when {
            pinned == null -> {
                if (mode == StrictHostKeyChecking.YES) return HostKeyRepository.NOT_INCLUDED
                val request = Request(name, port, keyTypeOf(key), KnownHostsStore.sha256Fingerprint(key))
                if (approval.approveUnknown(request)) {
                    store.pin(
                        KnownHostsStore.Pin(
                            host = name,
                            port = port,
                            keyType = request.keyType,
                            keyBase64 = encoded,
                            firstSeenEpochMs = System.currentTimeMillis(),
                        )
                    )
                    HostKeyRepository.OK
                } else {
                    HostKeyRepository.NOT_INCLUDED
                }
            }

            pinned.keyBase64 == encoded -> HostKeyRepository.OK

            else -> {
                approval.reportChanged(
                    Request(
                        host = name,
                        port = port,
                        keyType = keyTypeOf(key),
                        fingerprint = KnownHostsStore.sha256Fingerprint(key),
                        previousFingerprint = pinned.fingerprint,
                    )
                )
                HostKeyRepository.CHANGED
            }
        }
    }

    // Pinning happens inside check(); JSch only calls add() through its own prompt flow, which
    // is bypassed here.
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) {
        host?.let { val (n, p) = splitHostPort(it); store.remove(n, p) }
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) = remove(host, type)

    override fun getKnownHostsRepositoryID(): String = "InkDeck vault"

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        store.all()
            .filter { host == null || it.host.equals(splitHostPort(host).first, ignoreCase = true) }
            .mapNotNull {
                runCatching { HostKey(it.host, KnownHostsStore.decodeKey(it.keyBase64)) }.getOrNull()
            }
            .toTypedArray()

    private companion object {

        /** JSch passes either `host` or `[host]:port`. */
        fun splitHostPort(raw: String): Pair<String, Int> {
            if (raw.startsWith("[")) {
                val close = raw.indexOf("]:")
                if (close > 0) {
                    val name = raw.substring(1, close)
                    val port = raw.substring(close + 2).toIntOrNull() ?: 22
                    return name to port
                }
            }
            return raw to 22
        }

        /**
         * The key blob is SSH wire format: a 4-byte big-endian length followed by the type
         * string ("ssh-rsa", "ssh-ed25519", "ecdsa-sha2-nistp256", …).
         */
        fun keyTypeOf(key: ByteArray): String {
            if (key.size < 4) return "unknown"
            val length = ((key[0].toInt() and 0xff) shl 24) or
                ((key[1].toInt() and 0xff) shl 16) or
                ((key[2].toInt() and 0xff) shl 8) or
                (key[3].toInt() and 0xff)
            if (length !in 1..64 || key.size < 4 + length) return "unknown"
            return String(key, 4, length, Charsets.US_ASCII)
        }
    }
}
