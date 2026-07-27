package dev.inkdeck.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import dev.inkdeck.R
import dev.inkdeck.databinding.ActivityMainBinding
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkGeometry
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.debug.IdleProbe
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.refresh.FlushStrategies
import dev.inkdeck.eink.refresh.RefresherHost
import dev.inkdeck.ai.AiFragment
import dev.inkdeck.eink.widget.FloatingMenu
import dev.inkdeck.market.MarketFragment
import dev.inkdeck.tasks.TaskGraph
import dev.inkdeck.tasks.TasksFragment
import dev.inkdeck.tasks.alarm.TaskNotifications
import dev.inkdeck.telegram.TelegramGraph
import dev.inkdeck.terminal.TerminalFragment

/**
 * The single Activity — Plan.md §3.2. One Activity, a Fragment per feature, no shared-element
 * transitions and no back-stack animation.
 *
 * Tabs are switched with hide/show rather than replace. That costs a little memory for four
 * retained view hierarchies and buys the thing Phase 2 needs: an SSH session that survives a
 * trip to the Tasks tab and back.
 */
class MainActivity : AppCompatActivity(), RefresherHost {

    private lateinit var binding: ActivityMainBinding

    override lateinit var refresher: EinkRefresher
        private set

    private class TabDef(
        val tag: String,
        val icon: Int,
        val label: Int,
        /**
         * design.md §7.1 gives the terminal its own 56 dp header with host and connection state.
         * Showing the shell's generic title bar as well would cost 112 dp of chrome out of 682,
         * so this tab replaces it rather than stacking on it.
         */
        val hidesTitleBar: Boolean = false,
        val create: () -> Fragment,
    )

    private val tabs: List<TabDef> by lazy {
        listOf(
            TabDef(
                tag = "terminal",
                icon = R.drawable.ic_tab_terminal,
                label = R.string.tab_terminal,
                hidesTitleBar = true,
            ) { TerminalFragment() },
            TabDef("tasks", R.drawable.ic_tab_tasks, R.string.tab_tasks) { TasksFragment() },
            TabDef("market", R.drawable.ic_tab_market, R.string.tab_market) { MarketFragment() },
            TabDef(
                tag = "ai",
                icon = R.drawable.ic_tab_ai,
                label = R.string.tab_ai,
                // AiFragment draws its own 56 dp header (model picker, history, new-chat); the
                // generic title bar on top of it would cost 112 dp of a 682 dp column for one
                // title, same reasoning as the terminal tab.
                hidesTitleBar = true,
            ) { AiFragment() },
        )
    }

    private var currentIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refresher = EinkRefresher(this, FlushStrategies.default(this))

        EinkGeometry.log(this)
        EinkAnim.strip(binding.root)

        binding.actionFlush.apply {
            setIconResource(R.drawable.ic_flush)
            iconTint = EinkTheme.ink900(this@MainActivity)
            // Plan.md §3.4 item 4: a manual flush affordance. The OEM status bar has one, so the
            // expectation already exists; this is the in-app equivalent until the floating menu
            // takes it over in Phase 6.
            setOnClickListener { refresher.flush("manual") }
        }

        binding.tabBar.setTabs(tabs.map { TabBar.Spec(it.icon, it.label) })
        binding.tabBar.onTabSelected = { selectTab(it) }

        installFloatingMenu()

        // The only fragment transaction in this Activity that uses the back stack — tab
        // switching deliberately does not (see selectTab). A full-screen fragment appearing or
        // disappearing replaces the whole viewport, which is [F] per §13.
        supportFragmentManager.addOnBackStackChangedListener {
            refresher.flush("fragment-backstack-changed")
        }

        // Re-arm reminders on every start, not only from BootReceiver. The ROM's MOGU_KILL_APP
        // sweep force-stops the app (Plan.md §0), and Android silently drops every alarm of a
        // force-stopped package with no broadcast to tell us it happened.
        TaskGraph.rearmAsync(this)

        selectTab(savedInstanceState?.getInt(KEY_TAB) ?: 0)
        openTaskFromNotification(intent)
    }

    override fun onResume() {
        super.onResume()
        // Plan.md §5.1b-2 / §7.1: the ROM force-stops the poll loop, and nothing can wake it back
        // up on a schedule. Calling this on every resume — not just once in onCreate — IS the
        // reconnect strategy; TelegramGraph.startIfEnabled is idempotent against an already-live
        // loop, so this is cheap when nothing was actually killed.
        TelegramGraph.startIfEnabled(this)
        // Phase 9 item 8: idle drain harness. Guarded by BuildConfig.DEBUG inside the probe.
        IdleProbe.activityResumed("MainActivity")
        IdleProbe.start(this)
    }

    override fun onPause() {
        super.onPause()
        IdleProbe.activityPaused("MainActivity")
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        // launchMode is singleTask, so tapping a reminder while the app is already up arrives
        // here rather than through onCreate.
        setIntent(intent)
        openTaskFromNotification(intent)
    }

    private fun openTaskFromNotification(intent: android.content.Intent?) {
        val taskId = intent?.getLongExtra(TaskNotifications.EXTRA_OPEN_TASK_ID, -1L) ?: -1L
        if (taskId <= 0) return
        intent?.removeExtra(TaskNotifications.EXTRA_OPEN_TASK_ID)
        selectTab(tabs.indexOfFirst { it.tag == "tasks" })
    }

    // ------------------------------------------------------------------ floating menu

    private var floatingMenu: FloatingMenu? = null
    private var awake = false

    /**
     * design.md §11.
     *
     * Added to `android.R.id.content` rather than nested in the activity layout: the root there
     * is a vertical LinearLayout, which would stack the menu below the tab bar instead of
     * floating it over everything.
     *
     * In-app only. A system-wide overlay needs `SYSTEM_ALERT_WINDOW`, which is Plan.md's open
     * question 5 — not a permission to grant ourselves unasked.
     */
    private fun installFloatingMenu() {
        val content = findViewById<android.widget.FrameLayout>(android.R.id.content)
        val menu = FloatingMenu(this).apply {
            onFlush = { refresher.flush("floating-long-press") }
            onVisibilityChanged = { refresher.flush("floating-menu-expanded=$it") }
            items = buildMenuItems()
        }
        content.addView(
            menu,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        floatingMenu = menu
    }

    private fun buildMenuItems(): List<FloatingMenu.Item> = listOf(
        FloatingMenu.Item(R.drawable.ic_flush, getString(R.string.menu_flush)) {
            refresher.flush("floating-menu")
        },
        FloatingMenu.Item(R.drawable.ic_menu_keyboard, getString(R.string.menu_keys)) {
            terminalFragment()?.toggleKeyboardFromMenu()
                ?: toast(getString(R.string.menu_terminal_only))
        },
        FloatingMenu.Item(R.drawable.ic_tab_ai, getString(R.string.menu_ai)) {
            selectTab(tabs.indexOfFirst { it.tag == "ai" })
        },
        FloatingMenu.Item(R.drawable.ic_menu_theme, getString(R.string.menu_theme)) {
            toggleTheme()
        },
        FloatingMenu.Item(R.drawable.ic_menu_rotate, getString(R.string.menu_rotate)) {
            cycleOrientation()
        },
        // Phase 4. Shown disabled rather than hidden so the 3×3 grid keeps a stable shape —
        // muscle memory for cell position is worth more than hiding an unfinished feature.
        FloatingMenu.Item(R.drawable.ic_menu_quick, getString(R.string.menu_quick)) {
            quickCapture()
        },
        FloatingMenu.Item(R.drawable.ic_menu_awake, getString(R.string.menu_awake)) {
            toggleAwake()
        },
        FloatingMenu.Item(R.drawable.ic_menu_files, getString(R.string.menu_files)) {
            terminalFragment()?.toggleFilesFromMenu()
                ?: toast(getString(R.string.menu_terminal_only))
        },
        // §11.3 reserved this cell for `More` until a Phase filled it — the same shape `Quick`
        // took in Phase 4. Telegram settings is a full configuration screen (pairing, allowlist,
        // auto-delete, command reference), not a quick action, but there is no dedicated tab for
        // it and no room left in a fixed 3×3 grid, so this is where it lives.
        FloatingMenu.Item(R.drawable.ic_menu_telegram, getString(R.string.menu_telegram)) {
            openTelegramSettings()
        },
        // Phase 9 item 4: terminal font-size cycle. The 3×3 grid is now over its designed
        // capacity (FloatingMenu scrolls beyond 9 items), so this slots in without displacing
        // anything. See Plan §12 item 2 for the trade-off and the owner's call to retire one
        // cell if 3×3 is non-negotiable.
        FloatingMenu.Item(R.drawable.ic_menu_font, getString(R.string.menu_font)) {
            cycleTerminalFont()
        },
    )

    private fun terminalFragment(): TerminalFragment? =
        supportFragmentManager.findFragmentByTag("terminal") as? TerminalFragment

    private fun tasksFragment(): TasksFragment? =
        supportFragmentManager.findFragmentByTag("tasks") as? TasksFragment

    private fun aiFragment(): AiFragment? =
        supportFragmentManager.findFragmentByTag("ai") as? AiFragment

    private fun marketFragment(): MarketFragment? =
        supportFragmentManager.findFragmentByTag("market") as? MarketFragment

    /**
     * A genuine back-stack push, unlike the tab fragments (which are hidden, never destroyed, to
     * keep the SSH session alive) and unlike the task/AI/market overlays (which are siblings
     * drawn over one tab's own view).
     *
     * Added to **`android.R.id.content`** — the window's own root, same container
     * [installFloatingMenu] uses — not this Activity's `R.id.content`, which is the tab-switching
     * container. The first attempt used the tab container and broke the moment the user switched
     * tabs from the `TabBar` instead of pressing Back: `selectTab` only knows about the four tab
     * fragments, so it neither hides nor accounts for a sibling added there, and each first visit
     * to a tab adds that tab's fragment as a *new*, later — therefore higher — child, burying
     * Telegram settings underneath while its view stayed fully attached and clickable. Using the
     * window root instead covers the tab bar as well, so there is nothing left for a tab tap to
     * reach.
     *
     * Guarded against a second push: the floating menu can be tapped again while this is already
     * open, and stacking two identical back-stack entries would need two Back presses to leave.
     */
    private fun openTelegramSettings() {
        if (supportFragmentManager.findFragmentByTag(TAG_TELEGRAM_SETTINGS) != null) return
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_NONE)
            .add(android.R.id.content, TelegramGraph.settingsFragment(), TAG_TELEGRAM_SETTINGS)
            .addToBackStack(TAG_TELEGRAM_SETTINGS)
            .commit()
        // A full-screen fragment replaces everything under it — [F] per §13.
        refresher.flush("telegram-settings-open")
    }

    /**
     * design.md §11.3 `✚ Quick`: a new task from anywhere, without hunting for the tab first.
     *
     * It switches to Tasks rather than opening a floating sheet over the current screen. A
     * sheet would be a second editor to keep in sync, and on a 682 dp column it would cover
     * what it is drawn over anyway. `commitNow` inside [selectTab] means the fragment exists by
     * the time this returns, so there is nothing to post.
     */
    private fun quickCapture() {
        selectTab(tabs.indexOfFirst { it.tag == "tasks" })
        tasksFragment()?.quickCapture()
    }

    /**
     * Phase 9 item 4. Hands the cycle to the terminal fragment (the only place that owns a
     * `TerminalView`), then reports the new size in sp. The fragment is null when the user taps
     * the cell before opening the tab once — the `Aa` cell still works in that state, switching
     * to the terminal and applying the size, because `cycleFontSize` is called after the tab is
     * selected. We select first to be sure the fragment view exists.
     */
    private fun cycleTerminalFont() {
        selectTab(tabs.indexOfFirst { it.tag == "terminal" })
        val fragment = terminalFragment() ?: return
        fragment.cycleFontSize { sizeSp ->
            toast(getString(R.string.menu_font_toast, sizeSp.toInt()))
            refresher.flush("font-cycle=$sizeSp")
        }
    }

    /**
     * design.md §11.3 `⟳ Rotate`: portrait → landscape → reverse portrait → reverse landscape.
     * App-owned because adb `user_rotation` cannot rotate a portrait-locked activity, and the
     * device has no reliable rotation gesture.
     */
    private fun cycleOrientation() {
        val next = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        requestedOrientation = next
        refresher.flush("rotate")
    }

    private fun toggleAwake() {
        awake = !awake
        if (awake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        toast(getString(if (awake) R.string.menu_awake_on else R.string.menu_awake_off))
    }

    /**
     * Switching night mode recreates the Activity, which destroys the Fragment holding the SSH
     * session. Rather than dropping a live connection silently, say so and let the user decide.
     *
     * The proper fix is to hoist SshSession into a ViewModel so it survives recreation — that
     * is Plan §12 item 8 (Phase 10), not a Phase 9 polish item. Phase 9 item 5 only persists
     * the choice so it survives process restart.
     */
    private fun toggleTheme() {
        val goingDark = !EinkTheme.isDark(this)
        val live = terminalFragment()?.hasLiveSession() == true
        if (!live) {
            EinkTheme.applyDark(this, goingDark)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.menu_theme)
            .setMessage(R.string.menu_theme_disconnects)
            .setPositiveButton(R.string.menu_theme_switch) { _, _ -> EinkTheme.applyDark(this, goingDark) }
            .setNegativeButton(R.string.action_cancel_generic, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        val menu = floatingMenu
        if (menu != null && menu.isExpanded) {
            menu.collapse()
            return
        }
        // The file viewer and the task editor are overlays inside their tabs, not back-stack
        // entries, so each has to be dismissed explicitly or Back would leave the app from on
        // top of them.
        if (terminalFragment()?.closeViewerIfOpen() == true) return
        if (tasksFragment()?.closeEditorIfOpen() == true) return
        if (aiFragment()?.closeSettingsIfOpen() == true) return
        if (marketFragment()?.closeOverlayIfOpen() == true) return

        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentIndex)
    }

    private fun selectTab(index: Int) {
        if (index == currentIndex || index !in tabs.indices) return

        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tx.setTransition(FragmentTransaction.TRANSIT_NONE)

        tabs.forEachIndexed { i, tab ->
            val existing = fm.findFragmentByTag(tab.tag)
            when {
                i == index && existing == null -> tx.add(R.id.content, tab.create(), tab.tag)
                i == index -> tx.show(existing!!)
                existing != null -> tx.hide(existing)
            }
        }
        tx.commitNow()

        currentIndex = index
        binding.tabBar.selectedIndex = index
        binding.title.text = getString(tabs[index].label)
        binding.titleBar.visibility = if (tabs[index].hidesTitleBar) View.GONE else View.VISIBLE

        // design.md §13: tab switch is [F]. The whole viewport is replaced, so anything less
        // leaves the previous tab ghosted under the new one.
        refresher.flush("tab-switch->${tabs[index].tag}")
    }

    private companion object {
        const val KEY_TAB = "inkdeck.tab"
        const val TAG_TELEGRAM_SETTINGS = "telegram-settings"
    }
}
