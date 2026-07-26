package dev.inkdeck.terminal

import android.content.Context
import dev.inkdeck.data.ssh.HostEntry
import dev.inkdeck.data.ssh.HostStore
import dev.inkdeck.data.ssh.SshConfigParser
import dev.inkdeck.data.ssh.StrictHostKeyChecking
import dev.inkdeck.data.vault.SecretVault
import java.io.File
import java.security.SecureRandom

/**
 * Sideload route for keys and `ssh_config` — Plan.md §4.2, §7.4.
 *
 * Files are picked up from the app's **own** external files directory:
 *
 * ```
 * adb push key.pem /sdcard/Android/data/dev.inkdeck/files/import/
 * adb push config  /sdcard/Android/data/dev.inkdeck/files/import/
 * ```
 *
 * That path is writable by `adb push` and readable by this app with **no storage permission at
 * all** on API 27, which is why it beats `/sdcard/Download` and a file picker. It is also the
 * channel Plan.md §7.4 insists on for the trading-server key: over USB, never through Telegram.
 *
 * The staged copy is **shredded and deleted** the moment it lands in the vault. On API 27
 * anything holding `READ_EXTERNAL_STORAGE` can read this directory, so a private key left there
 * would be readable by every other app on the device — which would defeat the entire vault.
 */
object AdbImport {

    data class Report(
        val importedKeys: List<String>,
        val importedHosts: List<String>,
        val importedSecrets: List<String>,
        val warnings: List<String>,
    ) {
        val isEmpty: Boolean
            get() = importedKeys.isEmpty() && importedHosts.isEmpty() && importedSecrets.isEmpty()
    }

    fun importDir(context: Context): File =
        File(context.getExternalFilesDir(null), "import").apply { mkdirs() }

    fun pending(context: Context): List<File> =
        importDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /**
     * Import everything staged. Keys go into [vault] (which must be unlocked), hosts into
     * [hostStore], and any `IdentityFile` that names an imported key is re-pointed at its vault
     * id — a `D:\…\key.pem` path means nothing on Android.
     */
    fun importAll(context: Context, vault: SecretVault, hostStore: HostStore): Report {
        check(vault.isUnlocked) { "vault must be unlocked before import" }

        val keys = mutableListOf<String>()
        val hosts = mutableListOf<String>()
        val secrets = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val files = pending(context)

        // Keys first, so a config in the same batch can be re-pointed at them.
        val keyIdsByFileName = HashMap<String, String>()
        for (file in files.filter { looksLikePrivateKey(it) }) {
            val id = vaultIdFor(file.name)
            val bytes = file.readBytes()
            try {
                vault.put(id, bytes)
                keys += id
                keyIdsByFileName[file.name.lowercase()] = id
            } finally {
                bytes.fill(0)
            }
            shred(file)
        }

        for (file in files.filter { looksLikeConfig(it) }) {
            val result = SshConfigParser.parse(file.readText())
            warnings += result.warnings
            result.ignored.forEach { (line, raw) ->
                warnings += "config line $line: unsupported keyword, ignored — '$raw'"
            }
            for (entry in result.hosts) {
                hostStore.upsert(entry.withResolvedIdentity(keyIdsByFileName, vault, warnings))
                hosts += entry.alias
            }
            file.delete()
        }

        for (file in files.filter { looksLikeEnv(it) }) {
            secrets += importEnv(file, vault, hostStore, warnings)
            shred(file)
        }

        return Report(keys, hosts, secrets, warnings)
    }

    /**
     * Load `KEY=value` pairs from a `.env` into the vault, one secret per key.
     *
     * This exists so config can be managed as a single file on the desktop and pushed over USB,
     * rather than typed on a 16 fps panel. It is the sane half of "keep everything in a .env":
     * the file is a *transport*, shredded on arrival, and what persists on the device is the
     * encrypted vault.
     *
     * What it is deliberately not: a place for the vault passphrase. A passphrase sitting in
     * plaintext beside the vault it opens provides no protection at all — if you do not want to
     * type one, turn the passphrase off (the default) rather than writing it down next to the
     * lock.
     *
     * ```
     * # pushed to .../files/import/.env
     * TELEGRAM_BOT_TOKEN=123456:AA...
     * ANTHROPIC_API_KEY=sk-ant-...
     * ```
     */
    private fun importEnv(
        file: File,
        vault: SecretVault,
        hostStore: HostStore,
        warnings: MutableList<String>,
    ): List<String> {
        val loaded = mutableListOf<String>()
        file.readLines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val separator = line.indexOf('=')
            if (separator <= 0) {
                warnings += ".env line ${index + 1}: expected KEY=value"
                return@forEachIndexed
            }

            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().trim('"', '\'')

            // SSH_HOST_* is not a secret and must not go in the vault — it is a host definition.
            // This is the stand-in for the host editor that §4.2 still owes; a host is otherwise
            // only creatable by importing an ssh_config, which is awkward for one entry.
            if (key.startsWith(HOST_PREFIX, ignoreCase = true)) {
                val alias = key.substring(HOST_PREFIX.length)
                parseHostLine(alias, value, index + 1, warnings)
                    ?.let { hostStore.upsert(it); loaded += "host:${it.alias}" }
                return@forEachIndexed
            }

            val id = vaultIdFor(key)

            if (key.equals("VAULT_PASSPHRASE", ignoreCase = true)) {
                warnings += ".env: VAULT_PASSPHRASE ignored. Storing the passphrase beside the " +
                    "vault defeats it — use the passphrase toggle in settings instead."
                return@forEachIndexed
            }
            if (value.isEmpty()) {
                warnings += ".env line ${index + 1}: '$key' has an empty value, skipped"
                return@forEachIndexed
            }

            runCatching { vault.putString(id, value) }
                .onSuccess { loaded += id }
                .onFailure { warnings += ".env: could not store '$key' (${it.message})" }
        }
        return loaded
    }

    /**
     * `SSH_HOST_<alias>=user@host:port key=<vault-id> strict=<yes|ask|no>`
     *
     * A one-line host definition, because typing one into a form on a 16 fps panel is worse than
     * typing it into a file on the desktop — and because Plan.md §4.2's host editor does not
     * exist yet. Only `user@host` is required.
     *
     * `strict` defaults to `ASK` (trust-on-first-use) exactly as [SshConfigParser] does, and for
     * the same reason: this key reaches a trading server, so `no` has to be an explicit choice
     * per host, never a default.
     */
    private fun parseHostLine(
        alias: String,
        value: String,
        lineNumber: Int,
        warnings: MutableList<String>,
    ): HostEntry? {
        if (alias.isBlank()) {
            warnings += ".env line $lineNumber: SSH_HOST_ needs an alias after the underscore"
            return null
        }
        val parts = value.split(Regex("\\s+")).filter { it.isNotBlank() }
        val destination = parts.firstOrNull()
        if (destination.isNullOrBlank()) {
            warnings += ".env line $lineNumber: '$alias' has no user@host"
            return null
        }

        val user = destination.substringBefore('@', "").takeIf { it.isNotBlank() }
        val hostAndPort = destination.substringAfter('@', destination)
        val host = hostAndPort.substringBefore(':')
        val port = hostAndPort.substringAfter(':', "22").toIntOrNull() ?: 22
        if (host.isBlank()) {
            warnings += ".env line $lineNumber: '$alias' has no hostname"
            return null
        }

        var identity: String? = null
        var strict = StrictHostKeyChecking.ASK
        parts.drop(1).forEach { token ->
            val name = token.substringBefore('=').lowercase()
            val arg = token.substringAfter('=', "")
            when (name) {
                "key", "identity" -> identity = arg.takeIf { it.isNotBlank() }
                "strict" -> strict = StrictHostKeyChecking.parse(arg)
                else -> warnings += ".env line $lineNumber: '$alias' — ignored '$token'"
            }
        }
        if (strict == StrictHostKeyChecking.NO) {
            warnings += ".env: '$alias' set strict=no. Host-key checking is off for that host; " +
                "an interception will not be detected."
        }

        return HostEntry(
            alias = alias,
            hostName = host,
            user = user,
            port = port,
            identityVaultId = identity,
            strictHostKeyChecking = strict,
        )
    }

    private fun looksLikeEnv(file: File): Boolean {
        val name = file.name.lowercase()
        return name == ".env" || name.endsWith(".env")
    }

    /**
     * Match `IdentityFile` to a vault id by basename. The path itself is unusable — the point of
     * the exercise — so the filename is the only thing that carries over.
     */
    private fun HostEntry.withResolvedIdentity(
        keyIdsByFileName: Map<String, String>,
        vault: SecretVault,
        warnings: MutableList<String>,
    ): HostEntry {
        val hint = identityFileHint ?: return this
        val baseName = hint.substringAfterLast('\\').substringAfterLast('/').lowercase()

        val id = keyIdsByFileName[baseName]
            ?: vaultIdFor(baseName).takeIf { vault.contains(it) }

        if (id == null) {
            warnings += "host '$alias': IdentityFile '$hint' has no matching key in the vault. " +
                "Push ${baseName.ifEmpty { "the .pem" }} to the import directory and re-import."
        }
        return copy(identityVaultId = id)
    }

    private fun looksLikePrivateKey(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.endsWith(".pub")) return false
        if (looksLikeEnv(file)) return false
        if (name.endsWith(".pem") || name.startsWith("id_")) return true
        // Fall back to content sniffing so an extensionless key still imports.
        return runCatching {
            file.bufferedReader().use { it.readLine() }?.contains("PRIVATE KEY") == true
        }.getOrDefault(false)
    }

    private fun looksLikeConfig(file: File): Boolean {
        val name = file.name.lowercase()
        return name == "config" || name.endsWith(".sshconfig") || name.endsWith(".conf")
    }

    fun vaultIdFor(fileName: String): String =
        fileName.substringBeforeLast('.')
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .ifEmpty { "key" }

    /**
     * Overwrite before unlinking. Flash translation layers mean this is not a guarantee, but it
     * removes the plaintext from anything that reads the file rather than the raw block device.
     */
    private fun shred(file: File) {
        runCatching {
            val length = file.length().toInt()
            if (length > 0) {
                val noise = ByteArray(length)
                SecureRandom().nextBytes(noise)
                file.writeBytes(noise)
            }
        }
        file.delete()
    }

    private const val HOST_PREFIX = "SSH_HOST_"
}
