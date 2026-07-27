package dev.inkdeck

import android.app.Application
import dev.inkdeck.eink.EinkTheme

class InkDeckApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // design.md §2.2: dark is opt-in and starts off. Persisting the user's choice arrived
        // with the floating menu in Phase 6, and Phase 9 item 5 reads it back here. The default
        // for a fresh install is light, recorded in EinkTheme.restorePersisted.
        EinkTheme.restorePersisted(this)
    }
}
