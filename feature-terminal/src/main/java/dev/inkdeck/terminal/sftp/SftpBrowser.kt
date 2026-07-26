package dev.inkdeck.terminal.sftp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import dev.inkdeck.terminal.SshSession
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SFTP operations for the file sidebar — Plan.md §9 phase 3, design.md §7.2.
 *
 * Shares the terminal's SSH connection ([SshSession.openSftp]) rather than dialling a second
 * one. The channel is opened lazily on first use and reopened if the server drops it, so a
 * sidebar left open across a reconnect recovers by itself.
 *
 * Every call is asynchronous: the work runs on [worker] and the callback comes back on the main
 * looper, because all callers are Views. Nothing here touches the UI itself.
 */
class SftpBrowser(private val session: SshSession) {

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val isLink: Boolean,
        val size: Long,
        val permissions: String,
        val modifiedEpochSec: Long,
    ) {
        val isParent: Boolean get() = name == ".."

        /** design.md §7.2 uses type glyphs, never thumbnails. */
        val glyph: String
            get() = when {
                isParent -> "▲"
                isDirectory -> "▣"
                else -> "▢"
            }

        val displaySize: String
            get() = when {
                isDirectory -> ""
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
                size < 1024L * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
                else -> String.format("%.1f GB", size / (1024.0 * 1024 * 1024))
            }
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inkdeck-sftp").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var channel: ChannelSftp? = null

    /** Current remote directory, absolute. Empty until the first [list]. */
    @Volatile
    var path: String = ""
        private set

    /**
     * The login directory, captured the first time a channel is opened.
     *
     * Recorded once and never recomputed, because after any `cd` the server's `pwd()` is
     * wherever the browser happens to be — so it stops being an answer to "where is home".
     */
    @Volatile
    var homePath: String = ""
        private set

    // ---------------------------------------------------------------- operations

    /** @param target absolute path, or null for the server's default (usually `$HOME`). */
    fun list(target: String?, onResult: (String, List<Entry>) -> Unit, onError: (String) -> Unit) {
        run(onError) { sftp ->
            val resolved = target ?: sftp.pwd()
            sftp.cd(resolved)
            val here = sftp.pwd()

            @Suppress("UNCHECKED_CAST")
            val raw = sftp.ls(".") as Vector<ChannelSftp.LsEntry>
            val entries = raw
                .asSequence()
                .filter { it.filename != "." }
                .map { it.toEntry() }
                // Directories first, then files, each alphabetical — and `..` always on top,
                // because on a 56 dp row list it is the control people reach for most.
                .sortedWith(
                    compareByDescending<Entry> { it.isParent }
                        .thenByDescending { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                .toList()

            path = here
            main.post { onResult(here, entries) }
        }
    }

    fun refresh(onResult: (String, List<Entry>) -> Unit, onError: (String) -> Unit) =
        list(path.ifEmpty { null }, onResult, onError)

    fun mkdir(name: String, onDone: () -> Unit, onError: (String) -> Unit) {
        run(onError) { sftp ->
            sftp.mkdir(resolve(name))
            main.post(onDone)
        }
    }

    fun rename(from: String, to: String, onDone: () -> Unit, onError: (String) -> Unit) {
        run(onError) { sftp ->
            sftp.rename(resolve(from), resolve(to))
            main.post(onDone)
        }
    }

    /** Recursive for directories — `rmdir` refuses a non-empty one. */
    fun delete(entry: Entry, onDone: () -> Unit, onError: (String) -> Unit) {
        run(onError) { sftp ->
            if (entry.isDirectory && !entry.isLink) {
                deleteTree(sftp, resolve(entry.name))
            } else {
                sftp.rm(resolve(entry.name))
            }
            main.post(onDone)
        }
    }

    fun download(
        entry: Entry,
        destination: File,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        run(onError) { sftp ->
            destination.parentFile?.mkdirs()
            destination.outputStream().use { out: OutputStream ->
                sftp.get(resolve(entry.name), out)
            }
            main.post { onDone(destination) }
        }
    }

    /**
     * Read a file into memory for the viewer.
     *
     * Capped rather than unbounded. This device has ~550 MB free and two ~1 GHz cores, and the
     * viewer lays the whole thing out in one pass — pulling a 200 MB log would not fail
     * gracefully, it would take the app down. [maxBytes] of a large file is read and
     * [ReadResult.truncated] says so, which is more useful than refusing outright.
     */
    fun read(
        entry: Entry,
        maxBytes: Int,
        onResult: (ReadResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        run(onError) { sftp ->
            val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, entry.size.toInt().coerceAtLeast(1024)))
            sftp.get(resolve(entry.name)).use { input ->
                val chunk = ByteArray(READ_CHUNK)
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(chunk, 0, minOf(chunk.size, maxBytes - total))
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    total += read
                }
            }
            val bytes = buffer.toByteArray()
            val result = ReadResult(
                name = entry.name,
                bytes = bytes,
                totalSize = entry.size,
                truncated = entry.size > bytes.size,
            )
            main.post { onResult(result) }
        }
    }

    data class ReadResult(
        val name: String,
        val bytes: ByteArray,
        val totalSize: Long,
        val truncated: Boolean,
    ) {
        // ByteArray in a data class: identity equality is what callers actually want here, and
        // the generated structural version would compare megabytes.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    fun upload(
        source: InputStream,
        remoteName: String,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        run(onError) { sftp ->
            source.use { sftp.put(it, resolve(remoteName)) }
            main.post(onDone)
        }
    }

    fun close() {
        worker.execute { runCatching { channel?.disconnect() } }
        worker.shutdown()
        channel = null
    }

    // ---------------------------------------------------------------- plumbing

    private fun resolve(name: String): String =
        if (name.startsWith("/")) name else "$path/$name".replace("//", "/")

    private fun run(onError: (String) -> Unit, block: (ChannelSftp) -> Unit) {
        if (worker.isShutdown) return
        worker.execute {
            try {
                val sftp = ensureChannel() ?: run {
                    main.post { onError("Not connected.") }
                    return@execute
                }
                block(sftp)
            } catch (e: SftpException) {
                // SFTP status codes are the useful part — "Permission denied", "No such file".
                main.post { onError(e.message ?: "SFTP error ${e.id}") }
            } catch (e: Exception) {
                Log.w(TAG, "sftp op failed: ${e.javaClass.simpleName}")
                main.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Reopen transparently if the channel died but the session is still up. */
    private fun ensureChannel(): ChannelSftp? {
        channel?.let { if (it.isConnected) return it }
        channel = session.openSftp()
        if (homePath.isEmpty()) {
            homePath = runCatching { channel?.pwd() }.getOrNull().orEmpty()
        }
        return channel
    }

    private fun deleteTree(sftp: ChannelSftp, target: String) {
        @Suppress("UNCHECKED_CAST")
        val children = sftp.ls(target) as Vector<ChannelSftp.LsEntry>
        for (child in children) {
            if (child.filename == "." || child.filename == "..") continue
            val childPath = "$target/${child.filename}"
            if (child.attrs.isDir) deleteTree(sftp, childPath) else sftp.rm(childPath)
        }
        sftp.rmdir(target)
    }

    private fun ChannelSftp.LsEntry.toEntry(): Entry {
        val a: SftpATTRS = attrs
        return Entry(
            name = filename,
            isDirectory = a.isDir,
            isLink = a.isLink,
            size = a.size,
            permissions = a.permissionsString ?: "",
            modifiedEpochSec = a.mTime.toLong(),
        )
    }

    private companion object {
        const val TAG = "InkDeckSftp"
        const val READ_CHUNK = 32 * 1024
    }
}
