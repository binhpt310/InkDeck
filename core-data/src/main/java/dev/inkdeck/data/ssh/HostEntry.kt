package dev.inkdeck.data.ssh

import org.json.JSONObject

/**
 * How InkDeck verifies a server's host key.
 *
 * [ASK] is trust-on-first-use: pin the key the first time, refuse loudly if it ever changes.
 * That is the default everywhere (Plan.md §4.2) because it keeps the "no prompts after the
 * first connect" convenience an `ssh_config` with `StrictHostKeyChecking no` is reaching for,
 * while still catching an actual interception.
 */
enum class StrictHostKeyChecking {
    /** Refuse anything not already pinned. */
    YES,

    /** Trust on first use, then pin. The default. */
    ASK,

    /** Accept any key, every time. Available per host; never the default. */
    NO,

    ;

    companion object {
        fun parse(raw: String): StrictHostKeyChecking = when (raw.trim().lowercase()) {
            "yes" -> YES
            "no", "off" -> NO
            // OpenSSH's accept-new is exactly TOFU.
            "ask", "accept-new" -> ASK
            else -> ASK
        }
    }
}

/**
 * One resolved SSH destination.
 *
 * [identityVaultId] is the id of the private key inside [dev.inkdeck.data.vault.SecretVault], not
 * a path. Plan.md §4.2: a Windows `IdentityFile` such as `D:\VAYLA\…\key.pem` is meaningless on
 * Android, so import copies the key into the vault and re-points the entry.
 * [identityFileHint] keeps the original string for display only — nothing ever opens it.
 */
data class HostEntry(
    val alias: String,
    val hostName: String,
    val user: String? = null,
    val port: Int = 22,
    val identityVaultId: String? = null,
    val identityFileHint: String? = null,
    val strictHostKeyChecking: StrictHostKeyChecking = StrictHostKeyChecking.ASK,
    val userKnownHostsFile: String? = null,
    val serverAliveInterval: Int = 30,
    val compression: Boolean = false,
) {

    /** "binh@ec2-…-1.compute.amazonaws.com:22" for the host list subtitle. */
    val display: String
        get() = buildString {
            user?.let { append(it).append('@') }
            append(hostName)
            if (port != 22) append(':').append(port)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("alias", alias)
        put("hostName", hostName)
        put("user", user ?: JSONObject.NULL)
        put("port", port)
        put("identityVaultId", identityVaultId ?: JSONObject.NULL)
        put("identityFileHint", identityFileHint ?: JSONObject.NULL)
        put("strictHostKeyChecking", strictHostKeyChecking.name)
        put("userKnownHostsFile", userKnownHostsFile ?: JSONObject.NULL)
        put("serverAliveInterval", serverAliveInterval)
        put("compression", compression)
    }

    companion object {
        fun fromJson(o: JSONObject): HostEntry = HostEntry(
            alias = o.getString("alias"),
            hostName = o.getString("hostName"),
            user = o.optStringOrNull("user"),
            port = o.optInt("port", 22),
            identityVaultId = o.optStringOrNull("identityVaultId"),
            identityFileHint = o.optStringOrNull("identityFileHint"),
            strictHostKeyChecking = StrictHostKeyChecking.valueOf(
                o.optString("strictHostKeyChecking", StrictHostKeyChecking.ASK.name)
            ),
            userKnownHostsFile = o.optStringOrNull("userKnownHostsFile"),
            serverAliveInterval = o.optInt("serverAliveInterval", 30),
            compression = o.optBoolean("compression", false),
        )
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
