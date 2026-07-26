package dev.inkdeck.market

import android.content.Context

/**
 * The user's widget selection and refresh cadence.
 *
 * `SharedPreferences`, not Room — Plan.md's brief for this phase says so explicitly, and it is
 * right: this is one ordered list of nine-ish short strings and one integer, read once at
 * fragment start. A Room entity would add a DAO, a migration and a coroutine to read a value that
 * fits in a preference file, and would make the market feature a reason to bump the database
 * version.
 *
 * Order in [enabledIds] is the order of the grid, so toggling a widget on appends it to the end
 * rather than sorting it somewhere the user did not put it.
 */
class MarketPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabledIds: List<String>
        get() = prefs.getString(KEY_ENABLED, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: MarketCatalog.defaultEnabled
        set(value) {
            prefs.edit().putString(KEY_ENABLED, value.joinToString(SEP)).apply()
        }

    /** Minutes between automatic refreshes. 0 means manual only. design.md §9.2 offers 0/5/15/30. */
    var refreshMinutes: Int
        get() = prefs.getInt(KEY_REFRESH, DEFAULT_REFRESH_MINUTES)
        set(value) {
            prefs.edit().putInt(KEY_REFRESH, value).apply()
        }

    /**
     * Symbols the user added with `✚ symbol` (design.md §9.2), stored as `id|display` records.
     * `|` cannot appear in either half — ids are `category:SYMBOL` and displays are derived from
     * the symbol — so no escaping is needed and none is implied.
     */
    var customAssets: List<MarketAsset>
        get() = prefs.getString(KEY_CUSTOM, null)
            ?.split(SEP)
            ?.mapNotNull { record ->
                val bar = record.indexOf('|')
                if (bar <= 0) MarketAsset.parse(record)
                else MarketAsset.parse(record.substring(0, bar), record.substring(bar + 1))
            }
            .orEmpty()
        set(value) {
            prefs.edit()
                .putString(KEY_CUSTOM, value.joinToString(SEP) { "${it.id}|${it.display}" })
                .apply()
        }

    /** Built-ins first, then anything the user added, deduplicated by id. */
    fun allAssets(): List<MarketAsset> {
        val seen = LinkedHashMap<String, MarketAsset>()
        MarketCatalog.builtIn.forEach { seen[it.id] = it }
        customAssets.forEach { seen.putIfAbsent(it.id, it) }
        return seen.values.toList()
    }

    /** The grid contents: enabled ids resolved to assets, in the user's own order. */
    fun enabledAssets(): List<MarketAsset> {
        val byId = allAssets().associateBy { it.id }
        return enabledIds.mapNotNull { byId[it] ?: MarketAsset.parse(it) }
    }

    fun setEnabled(asset: MarketAsset, enabled: Boolean) {
        val current = enabledIds.toMutableList()
        val present = current.remove(asset.id)
        if (enabled) {
            current += asset.id
        } else if (!present) {
            return
        }
        enabledIds = current
    }

    fun addCustom(asset: MarketAsset) {
        if (MarketCatalog.builtIn.any { it.id == asset.id }) return
        val current = customAssets.toMutableList()
        if (current.any { it.id == asset.id }) return
        current += asset
        customAssets = current
    }

    companion object {
        private const val FILE = "inkdeck.market"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CUSTOM = "custom"
        private const val KEY_REFRESH = "refreshMinutes"
        private const val SEP = ","

        const val DEFAULT_REFRESH_MINUTES = 5

        /** design.md §9.2's segmented control, in order. 0 is "manual". */
        val REFRESH_CHOICES = intArrayOf(0, 5, 15, 30)
    }
}
