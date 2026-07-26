package dev.inkdeck.lab

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.refresh.BroadcastFlush
import dev.inkdeck.eink.refresh.CompositeFlush
import dev.inkdeck.eink.refresh.FlushStrategy
import dev.inkdeck.eink.refresh.InvertRestoreFlush
import dev.inkdeck.eink.refresh.NoFlush
import dev.inkdeck.eink.widget.EinkButton

/**
 * Settles the one Phase 1 question the harness cannot answer — Plan.md §3.4.
 *
 * `screencap` reads the RGB framebuffer, so a panel flush is invisible to it: the framebuffer
 * contents are identical before and after a waveform refresh. `einknav watch` cannot see this,
 * and neither can any automated check. Only a person looking at the panel can.
 *
 * So this screen makes the comparison as easy as possible to judge by eye:
 *
 *   1. "Make ghosts" cycles high-contrast patterns with no flush, accumulating residue, and
 *      settles on a mostly-white witness field where residue shows as grey haze.
 *   2. Each button below runs exactly one flush strategy.
 *   3. Watch the panel. The witness field either snaps to clean white or it does not.
 *
 * The control button matters: run "No flush" after making ghosts to see what *not* flushing
 * looks like, so a strategy that does nothing is not mistaken for one that works.
 */
class EinkLabActivity : AppCompatActivity() {

    private lateinit var pattern: GhostPatternView
    private lateinit var status: TextView

    private var runs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(EinkTheme.paper(this@EinkLabActivity))
        }

        status = TextView(this).apply {
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Caption)
            setTextColor(EinkTheme.ink900(this@EinkLabActivity))
            val pad = EinkTheme.dp(this@EinkLabActivity, 12f).toInt()
            setPadding(pad, pad, pad, pad)
            text = getString(dev.inkdeck.R.string.lab_ready)
        }
        root.addView(status, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pattern = GhostPatternView(this)
        root.addView(
            pattern,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(
            button("1. Make ghosts (no flush)") {
                status.text = "cycling patterns…"
                pattern.cycle { status.text = "ghosts made. Now try a flush." }
            }
        )
        root.addView(button("2a. Broadcast flush") { run(BroadcastFlush(this)) })
        root.addView(button("2b. Invert-restore flush") { run(InvertRestoreFlush()) })
        root.addView(
            button("2c. Broadcast + invert-restore") {
                run(CompositeFlush(BroadcastFlush(this), InvertRestoreFlush()))
            }
        )
        root.addView(button("2d. Control — no flush") { run(NoFlush) })

        setContentView(root)
        EinkAnim.strip(root)
    }

    private fun run(strategy: FlushStrategy) {
        runs++
        status.text = "run #$runs  strategy=${strategy.id}"
        strategy.flush(this) {
            status.text = "run #$runs  strategy=${strategy.id}  — done. Look at the panel."
        }
    }

    private fun button(label: String, onClick: () -> Unit): EinkButton =
        EinkButton(this).apply {
            text = label
            variant = EinkButton.Variant.SECONDARY
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_touch_min),
            ).also {
                val m = EinkTheme.dp(this@EinkLabActivity, 4f).toInt()
                it.setMargins(m * 2, m, m * 2, m)
            }
        }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).also {
        it.gravity = Gravity.CENTER_HORIZONTAL
    }
}
