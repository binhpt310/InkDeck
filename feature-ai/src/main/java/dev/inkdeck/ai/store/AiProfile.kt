package dev.inkdeck.ai.store

import org.json.JSONObject

/**
 * Which wire protocol a base URL speaks — Plan.md §6.1: "any OpenAI-compatible base URL +
 * Anthropic".
 *
 * This is the only thing the UI knows about the difference. Auth header, request body and SSE
 * event shape all differ between the two and all of that is confined to
 * `dev.inkdeck.ai.provider`.
 */
enum class ProviderKind {
    /** `POST {base}/v1/chat/completions`, `Authorization: Bearer`. OpenAI, Ollama, vLLM, LM Studio, … */
    OPENAI_COMPATIBLE,

    /** `POST {base}/v1/messages`, `x-api-key` + `anthropic-version`. */
    ANTHROPIC;

    companion object {
        fun parse(name: String?): ProviderKind =
            if (name == ANTHROPIC.name) ANTHROPIC else OPENAI_COMPATIBLE

        /** Accepts what a Telegram `/llm <provider> …` command would plausibly say (Plan.md §7.2). */
        fun fromUserWord(word: String): ProviderKind =
            if (word.trim().lowercase() in ANTHROPIC_WORDS) ANTHROPIC else OPENAI_COMPATIBLE

        private val ANTHROPIC_WORDS = setOf("anthropic", "claude")
    }
}

/**
 * One BYOK endpoint — design.md §10.1.
 *
 * **Holds no key material.** [keyVaultId] names a secret in
 * [dev.inkdeck.data.vault.SecretVault]; [keyHint] is the masked tail (`sk-ant-…7f3a`) computed
 * once at the moment the key is stored, so nothing ever has to open the vault just to draw a
 * settings row. That is the whole reason the hint is persisted rather than derived on demand.
 */
data class AiProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val kind: ProviderKind,
    /** Secret id inside the vault. Constrained to the vault's legal id charset by [idFor]. */
    val keyVaultId: String,
    /** Masked tail for display, or empty when no key is stored. Never the key. */
    val keyHint: String = "",
) {
    val hasKey: Boolean get() = keyHint.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_BASE, baseUrl)
        put(KEY_MODEL, model)
        put(KEY_KIND, kind.name)
        put(KEY_VAULT, keyVaultId)
        put(KEY_HINT, keyHint)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_BASE = "baseUrl"
        private const val KEY_MODEL = "model"
        private const val KEY_KIND = "kind"
        private const val KEY_VAULT = "keyVaultId"
        private const val KEY_HINT = "keyHint"

        fun fromJson(o: JSONObject): AiProfile {
            val id = o.optString(KEY_ID)
            return AiProfile(
                id = id,
                name = o.optString(KEY_NAME, id),
                baseUrl = o.optString(KEY_BASE),
                model = o.optString(KEY_MODEL),
                kind = ProviderKind.parse(o.optString(KEY_KIND)),
                keyVaultId = o.optString(KEY_VAULT).ifEmpty { vaultIdFor(id) },
                keyHint = o.optString(KEY_HINT),
            )
        }

        /**
         * Slug of the display name. Readable ids matter here because Telegram's `/llm use <name>`
         * (Plan.md §7.2) addresses profiles by name and because the id ends up as a filename in
         * the vault directory.
         */
        fun idFor(name: String, taken: Set<String>): String {
            val base = name.lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .trim('-')
                .replace(Regex("-+"), "-")
                .take(32)
                .ifEmpty { "profile" }
            if (base !in taken) return base
            var n = 2
            while ("$base-$n" in taken) n++
            return "$base-$n"
        }

        fun vaultIdFor(profileId: String): String = "ai_key_$profileId"

        /**
         * `sk-ant-api03-…7f3a` from the key itself, without ever building a String of it.
         *
         * The head runs to the second `-` when there is one within the first eight characters —
         * every provider prefix in the wild (`sk-`, `sk-ant-`, `sk-proj-`) lands there, and it is
         * the part that identifies *which* key without helping anyone use it. Failing that, three
         * characters: an opaque token should not leak eight.
         */
        fun maskOf(key: CharArray): String {
            if (key.size < MIN_MASKABLE) return "…"
            val head = StringBuilder()
            var dashes = 0
            for (i in 0 until minOf(HEAD_SCAN, key.size)) {
                head.append(key[i])
                if (key[i] == '-') {
                    dashes++
                    if (dashes == 2) break
                }
            }
            if (dashes < 2) {
                head.setLength(3)
            }
            return head.toString() + "…" + String(key, key.size - TAIL, TAIL)
        }

        private const val MIN_MASKABLE = 12
        private const val HEAD_SCAN = 8
        private const val TAIL = 4
    }
}
