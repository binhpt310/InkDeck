package dev.inkdeck

import android.app.Application
import dev.inkdeck.eink.EinkTheme

class InkDeckApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // design.md §2.2: dark is opt-in and starts off. Persisting the user's choice arrives
        // with the floating menu in Phase 6; until then this pins the documented default rather
        // than inheriting whatever the OEM has set system-wide.
        EinkTheme.applyDark(false)
    }
}
