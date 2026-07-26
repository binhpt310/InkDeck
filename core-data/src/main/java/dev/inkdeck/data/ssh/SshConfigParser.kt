package dev.inkdeck.data.ssh

/**
 * Parser for the subset of `ssh_config` listed in Plan.md §4.2, so a user can paste the config
 * they already have instead of retyping it into a form.
 *
 * Supported keywords — everything else is collected into [ParseResult.ignored] rather than
 * silently dropped, because a quietly discarded `ProxyJump` is the kind of thing that turns
 * into a baffling connection failure:
 *
 *   Host · HostName · User · Port · IdentityFile · StrictHostKeyChecking ·
 *   UserKnownHostsFile · ServerAliveInterval · Compression
 *
 * Real-config details that are handled: keywords are case-insensitive, `key value` and
 * `key = value` both work, values may be double-quoted (needed for the Windows path in the
 * reference config), and `#` starts a comment.
 *
 * Not handled, deliberately: `Match` blocks, `Include`, wildcard resolution across blocks, and
 * OpenSSH's first-value-wins inheritance from a `Host *` block. A `Host` line with several
 * patterns yields one entry per pattern.
 */
object SshConfigParser {

    data class ParseResult(
        val hosts: List<HostEntry>,
        /** `line number to raw line` for keywords outside the supported subset. */
        val ignored: List<Pair<Int, String>>,
        val warnings: List<String>,
    )

    private class Block(val patterns: List<String>) {
        val values = HashMap<String, String>()
    }

    fun parse(text: String): ParseResult {
        val blocks = ArrayList<Block>()
        val ignored = ArrayList<Pair<Int, String>>()
        val warnings = ArrayList<String>()
        var current: Block? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            val (keyword, value) = splitKeyValue(line) ?: run {
                warnings += "line ${index + 1}: could not parse '${rawLine.trim()}'"
                return@forEachIndexed
            }

            if (keyword.equals("Host", ignoreCase = true)) {
                current = Block(value.split(Regex("\\s+")).filter { it.isNotEmpty() })
                    .also { blocks += it }
                return@forEachIndexed
            }

            val block = current
            if (block == null) {
                // OpenSSH allows global defaults before the first Host. We do not resolve
                // inheritance, so say so rather than pretending the setting took effect.
                warnings += "line ${index + 1}: '$keyword' appears before any Host block and was not applied"
                return@forEachIndexed
            }

            if (keyword.lowercase() in SUPPORTED) {
                block.values[keyword.lowercase()] = value
            } else {
                ignored += (index + 1) to rawLine.trim()
            }
        }

        val hosts = blocks.flatMap { block ->
            block.patterns.mapNotNull { pattern ->
                if (pattern == "*" || pattern.contains('*') || pattern.contains('?')) {
                    warnings += "host pattern '$pattern' is a wildcard; wildcard inheritance is not supported and it was skipped"
                    null
                } else {
                    toEntry(pattern, block, warnings)
                }
            }
        }

        return ParseResult(hosts, ignored, warnings)
    }

    private fun toEntry(alias: String, block: Block, warnings: MutableList<String>): HostEntry {
        val v = block.values
        val port = v["port"]?.toIntOrNull() ?: 22
        if (v["port"] != null && v["port"]?.toIntOrNull() == null) {
            warnings += "host '$alias': Port '${v["port"]}' is not a number, using 22"
        }

        val requested = v["stricthostkeychecking"]?.let(StrictHostKeyChecking::parse)
            ?: StrictHostKeyChecking.ASK

        // Plan.md §4.2: `no` stays available per host but is never what an import silently
        // applies. The downgrade has to happen here as well as in the warning — warning about a
        // downgrade while storing the permissive value is worse than not warning at all.
        val strict = if (requested == StrictHostKeyChecking.NO) {
            warnings += "host '$alias': config requests StrictHostKeyChecking no — " +
                "imported as trust-on-first-use instead (Plan.md §4.2). Re-enable per host if you mean it."
            StrictHostKeyChecking.ASK
        } else {
            requested
        }
        if (v["userknownhostsfile"]?.trim()?.equals("/dev/null", ignoreCase = true) == true) {
            warnings += "host '$alias': UserKnownHostsFile /dev/null discards host keys; " +
                "InkDeck pins them in its own store instead."
        }

        return HostEntry(
            alias = alias,
            hostName = v["hostname"] ?: alias,
            user = v["user"],
            port = port,
            // Import rewrites this into a vault id; the raw path is kept for display only.
            identityVaultId = null,
            identityFileHint = v["identityfile"],
            strictHostKeyChecking = strict,
            userKnownHostsFile = v["userknownhostsfile"],
            serverAliveInterval = v["serveraliveinterval"]?.toIntOrNull() ?: 30,
            compression = v["compression"]?.let { it.equals("yes", ignoreCase = true) } ?: false,
        )
    }

    private fun splitKeyValue(line: String): Pair<String, String>? {
        val separator = line.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
        if (separator <= 0) return null
        val keyword = line.substring(0, separator)
        val value = line.substring(separator + 1).trim().removePrefix("=").trim()
        if (value.isEmpty()) return null
        return keyword to unquote(value)
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    private val SUPPORTED = setOf(
        "hostname",
        "user",
        "port",
        "identityfile",
        "stricthostkeychecking",
        "userknownhostsfile",
        "serveraliveinterval",
        "compression",
    )
}
