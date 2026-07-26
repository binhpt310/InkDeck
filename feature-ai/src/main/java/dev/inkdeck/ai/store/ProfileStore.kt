package dev.inkdeck.ai.store

import android.content.Context
import org.json.JSONArray

/**
 * The BYOK profile list and which one is active — design.md §10.1.
 *
 * `SharedPreferences`, not Room: this is a handful of rows read at screen open, and `:core-data`
 * is not this module's to add an entity to. It contains **no secrets** — every profile carries a
 * vault id and a masked hint, and the key bytes live in [dev.inkdeck.data.vault.SecretVault].
 *
 * Writes are `commit()` rather than `apply()`. The ROM force-stops this package roughly 30 s
 * after it backgrounds (Plan.md §5.1b-2), and `apply()`'s write is only *scheduled*; losing the
 * profile the user just pasted a key into would be indistinguishable from the key not saving.
 */
class ProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<AiProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return seed()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AiProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun find(id: String): AiProfile? = all().firstOrNull { it.id == id }

    fun findByName(name: String): AiProfile? =
        all().firstOrNull { it.name.equals(name, ignoreCase = true) || it.id == name }

    /**
     * The profile requests go to. Falls back to the first one rather than to null, so a vault
     * restore or a deleted active profile leaves the chat usable instead of inert.
     */
    fun active(): AiProfile? {
        val profiles = all()
        val id = prefs.getString(KEY_ACTIVE, null)
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).commit()
    }

    fun upsert(profile: AiProfile) {
        val kept = all().filterNot { it.id == profile.id }
        write(kept + profile)
        if (prefs.getString(KEY_ACTIVE, null) == null) setActive(profile.id)
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
        if (prefs.getString(KEY_ACTIVE, null) == id) {
            all().firstOrNull()?.let { setActive(it.id) } ?: prefs.edit().remove(KEY_ACTIVE).commit()
        }
    }

    fun nextId(name: String): String = AiProfile.idFor(name, all().map { it.id }.toSet())

    private fun write(profiles: List<AiProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).commit()
    }

    /**
     * First run gets the row design.md §10.1 draws: Anthropic, no key. A profile with a blank key
     * is not usable, but it turns "configure a provider from nothing" into "paste your key", and
     * the base URL and model id are exactly the two things a first-time user has no way to guess.
     * Nothing here is a secret and nothing is personal — it is the vendor's public API root.
     */
    private fun seed(): List<AiProfile> {
        val id = "anthropic-main"
        val profile = AiProfile(
            id = id,
            name = id,
            baseUrl = DEFAULT_ANTHROPIC_BASE,
            model = DEFAULT_ANTHROPIC_MODEL,
            kind = ProviderKind.ANTHROPIC,
            keyVaultId = AiProfile.vaultIdFor(id),
        )
        write(listOf(profile))
        setActive(id)
        return listOf(profile)
    }

    companion object {
        private const val PREFS = "inkdeck_ai"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "activeProfile"

        const val DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-opus-5"
        const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
    }
}
