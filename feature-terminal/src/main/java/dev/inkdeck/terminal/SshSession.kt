package dev.inkdeck.terminal

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.inkdeck.data.ssh.HostEntry
import dev.inkdeck.data.ssh.KnownHostsStore
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One SSH connection and its interactive shell — Plan.md §4.1, §4.4.
 *
 * Deliberately a single [Session]. Phase 3's SFTP browser opens a second *channel* on this same
 * connection rather than a second connection: one TCP socket, one handshake, one host-key check,
 * and half the memory on a device with ~550 MB free (Plan.md §10).
 *
 * All network work happens on [thread]. Callbacks fire on that thread too — [TerminalView]
 * handles the hop to the main looper itself, since it already throttles repaints there.
 */
class SshSession(
    private val entry: HostEntry,
    private val knownHosts: KnownHostsStore,
    private val approval: InkDeckHostKeyRepository.HostKeyApproval,
) {

    enum class Status { IDLE, CONNECTING, CONNECTED, DISCONNECTED, FAILED }

    var onStatus: ((Status, String?) -> Unit)? = null
    var onOutput: ((ByteArray, Int) -> Unit)? = null

    @Volatile
    var status: Status = Status.IDLE
        private set

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    private var thread: Thread? = null

    /** Serialises every out-of-band request on the session. See [resize]. */
    private val control: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inkdeck-ssh-ctl-${entry.alias}").apply { isDaemon = true }
    }

    private var lastCols = 0
    private var lastRows = 0

    @Volatile
    private var closing = false

    /**
     * @param privateKeyPem PEM bytes of the identity. **Zeroed before this method returns** —
     *   the caller should not reuse the array. This is the only copy that leaves the vault, and
     *   it exists for the few milliseconds JSch needs to parse it.
     */
    fun connect(privateKeyPem: ByteArray?, cols: Int, rows: Int) {
        check(thread == null) { "session already started" }
        closing = false
        setStatus(Status.CONNECTING, null)

        thread = Thread({ run(privateKeyPem, cols, rows) }, "inkdeck-ssh-${entry.alias}").apply {
            isDaemon = true
            start()
        }
    }

    private fun run(privateKeyPem: ByteArray?, cols: Int, rows: Int) {
        try {
            val jsch = JSch()

            if (privateKeyPem != null) {
                try {
                    // No passphrase argument: an encrypted .pem would need one prompted from the
                    // UI, which Phase 2 does not do yet. An encrypted key fails here with a clear
                    // JSchException rather than a confusing auth failure later.
                    jsch.addIdentity(entry.alias, privateKeyPem, null, null)
                } finally {
                    privateKeyPem.fill(0)
                }
            }

            jsch.hostKeyRepository = InkDeckHostKeyRepository(
                store = knownHosts,
                mode = entry.strictHostKeyChecking,
                approval = approval,
            )

            val s = jsch.getSession(entry.user, entry.hostName, entry.port)
            // Our repository has already made the trust decision; "yes" makes JSch respect its
            // verdict without running its own prompt flow.
            s.setConfig("StrictHostKeyChecking", "yes")
            s.setConfig("PreferredAuthentications", "publickey")
            if (entry.compression) {
                s.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
                s.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            }
            // Plan.md §4.4: the reader sleeps aggressively, so keepalives are not optional.
            s.serverAliveInterval = entry.serverAliveInterval * 1000
            s.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX

            s.connect(CONNECT_TIMEOUT_MS)
            session = s

            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType(TERM_TYPE, cols, rows, cols * PIXELS_PER_COL, rows * PIXELS_PER_ROW)

            // Server -> us. Must be taken before connect(), or the MOTD and first prompt are
            // written before the pipe exists and are lost.
            val input: InputStream = ch.inputStream

            // Us -> server. Deliberately NOT channel.getOutputStream().
            //
            // That wrapper threw `IOException: Already closed` on every keystroke here while the
            // channel itself reported connected=true closed=false eof=false — taking it after
            // connect() rather than before made no difference. Feeding the channel a pipe
            // instead is the pattern JSch's own interactive examples use: JSch runs a reader
            // thread over this stream and turns whatever arrives into channel data packets.
            val toServer = PipedOutputStream()
            ch.setInputStream(PipedInputStream(toServer, PIPE_BYTES), true)
            out = toServer

            ch.connect(CONNECT_TIMEOUT_MS)
            channel = ch

            setStatus(Status.CONNECTED, null)
            pump(input)
        } catch (t: Throwable) {
            if (!closing) {
                // Message only. A stack trace here can carry host names and key paths into
                // logcat, which any app on the device can read on API 27.
                Log.w(TAG, "session ${entry.alias} failed: ${t.javaClass.simpleName}")
                setStatus(Status.FAILED, t.message ?: t.javaClass.simpleName)
            }
        } finally {
            closeQuietly()
            if (closing) setStatus(Status.DISCONNECTED, null)
        }
    }

    private fun pump(input: InputStream) {
        val buffer = ByteArray(READ_BUFFER)
        while (!closing) {
            val read = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (read < 0) break
            if (read > 0) onOutput?.invoke(buffer, read)
        }
        if (!closing) setStatus(Status.DISCONNECTED, null)
    }

    fun write(bytes: ByteArray) {
        val stream = out ?: return
        // Called from the UI thread on every keystroke. Writes to a connected channel are
        // buffered and return immediately; a stalled link surfaces as a failed session, not a
        // frozen keyboard.
        try {
            stream.write(bytes)
            stream.flush()
        } catch (e: Exception) {
            val ch = channel
            Log.w(
                TAG,
                "write failed: ${e.javaClass.name}: ${e.message} " +
                    "(channel connected=${ch?.isConnected} closed=${ch?.isClosed} " +
                    "eof=${ch?.isEOF} session=${session?.isConnected})",
            )
            setStatus(Status.DISCONNECTED, null)
        }
    }

    /**
     * Renegotiate the PTY after a rotation, a font change, or the soft keyboard opening — sends
     * SIGWINCH.
     *
     * Runs on [control], never the caller's thread. `setPtySize` writes a `window-change` request
     * onto the session, and doing that from the UI thread while JSch's own reader thread is live
     * on the same session killed the channel outright: no exception on our side, the server
     * simply closed it and the read loop saw EOF about a second later. Serialising every control
     * operation onto one thread fixed it.
     *
     * Also skips no-op resizes. The IME opening fires several layout passes, and each one would
     * otherwise put another request on the wire.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (cols == lastCols && rows == lastRows) return
        lastCols = cols
        lastRows = rows

        control.execute {
            val ch = channel ?: return@execute
            if (closing || !ch.isConnected) return@execute
            try {
                ch.setPtySize(cols, rows, cols * PIXELS_PER_COL, rows * PIXELS_PER_ROW)
                Log.d(TAG, "pty resized to ${cols}x$rows")
            } catch (e: Exception) {
                Log.w(TAG, "pty resize failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Open an SFTP channel on **this same connection** — Plan.md §10.
     *
     * A second `Session` would mean a second TCP socket, a second key exchange and a second
     * host-key check for the same server. On a device with ~550 MB free and a 2-core CPU that is
     * a real cost, and SSH multiplexes channels precisely so it is not necessary.
     *
     * Blocking. Call from a background thread — [dev.inkdeck.terminal.sftp.SftpBrowser] has its
     * own.
     */
    fun openSftp(): ChannelSftp? {
        val s = session ?: return null
        if (!s.isConnected) return null
        return try {
            (s.openChannel("sftp") as ChannelSftp).apply { connect(CONNECT_TIMEOUT_MS) }
        } catch (e: Exception) {
            Log.w(TAG, "sftp channel failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    val isConnected: Boolean get() = session?.isConnected == true && channel?.isConnected == true

    fun disconnect() {
        closing = true
        control.shutdownNow()
        closeQuietly()
        thread = null
    }

    private fun closeQuietly() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
        out = null
    }

    private fun setStatus(next: Status, message: String?) {
        status = next
        onStatus?.invoke(next, message)
    }

    private companion object {
        const val TAG = "InkDeckSsh"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val SERVER_ALIVE_COUNT_MAX = 3
        const val READ_BUFFER = 4096

        /**
         * Not "xterm". The vendored vt320 is a VT220-class emulator, and claiming xterm makes
         * full-screen apps send sequences it cannot parse — vim's `CSI > 4 ; m`
         * (modifyOtherKeys) spilled onto the prompt as literal `4;m`, and its alternate-screen
         * switch was ignored outright.
         *
         * Advertising what the emulator actually is fixes that at the source, with no patch to
         * the vendored GPL sources. Cost: no 256-colour, which this grayscale panel discards
         * anyway (design.md §14 item 3).
         */
        const val TERM_TYPE = "vt220"

        /** Keystrokes are tiny; this only has to absorb a paste. */
        const val PIPE_BYTES = 32 * 1024

        // Only used to fill the pixel fields of the PTY request. Nothing on the far side reads
        // them for a text session, but sending zeros confuses some servers.
        const val PIXELS_PER_COL = 8
        const val PIXELS_PER_ROW = 16
    }
}
