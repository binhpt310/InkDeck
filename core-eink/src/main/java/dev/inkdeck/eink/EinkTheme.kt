package dev.inkdeck.eink

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/**
 * Code-side access to the design.md §2 grey ramp and §3 type scale.
 *
 * The ramp lives in res/values{,-night}/colors.xml so XML and Canvas resolve the same tokens
 * through the same night-mode qualifier. Nothing in the app should hardcode a grey.
 */
object EinkTheme {

    private const val PREFS = "inkdeck.theme"
    private const val KEY_DARK = "inkdeck.theme.dark"

    @ColorInt fun ink900(c: Context): Int = ContextCompat.getColor(c, R.color.ink_900)
    @ColorInt fun ink700(c: Context): Int = ContextCompat.getColor(c, R.color.ink_700)
    @ColorInt fun ink500(c: Context): Int = ContextCompat.getColor(c, R.color.ink_500)
    @ColorInt fun ink300(c: Context): Int = ContextCompat.getColor(c, R.color.ink_300)
    @ColorInt fun ink200(c: Context): Int = ContextCompat.getColor(c, R.color.ink_200)
    @ColorInt fun paper(c: Context): Int = ContextCompat.getColor(c, R.color.paper)

    fun dp(c: Context, value: Float): Float = value * c.resources.displayMetrics.density

    fun sp(c: Context, value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, c.resources.displayMetrics
    )

    fun uiTypeface(c: Context): Typeface =
        Typeface.create(c.getString(R.string.ink_font_ui), Typeface.NORMAL)

    fun uiEmphasisTypeface(c: Context): Typeface =
        Typeface.create(c.getString(R.string.ink_font_ui_emphasis), Typeface.NORMAL)

    fun monoTypeface(c: Context): Typeface =
        Typeface.create(c.getString(R.string.ink_font_mono), Typeface.NORMAL)

    fun isDark(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * design.md §2.2: dark is offered because it was requested, and defaults off. Driving most
     * pixels black increases ghosting and, with no frontlight on this device, reads worse in
     * dim light rather than better.
     *
     * Phase 9 item 5: the choice now persists in app-private SharedPreferences so it survives
     * process restart. The recreate-drops-SSH problem is **not** fixed here — see the comment on
     * MainActivity.toggleTheme and Plan §12 item 8.
     */
    fun applyDark(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK, enabled).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Apply whatever the user picked last, defaulting to light. Call from `Application.onCreate`
     * before any Activity is created — `setDefaultNightMode` re-creates the current Activity
     * and the rest of the app will not get a second chance to call this.
     */
    fun restorePersisted(context: Context) {
        val dark = prefs(context).getBoolean(KEY_DARK, false)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, 0)
}
