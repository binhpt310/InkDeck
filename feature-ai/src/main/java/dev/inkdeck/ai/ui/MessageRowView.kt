package dev.inkdeck.ai.ui

import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.inkdeck.ai.ChatMessage
import dev.inkdeck.ai.R
import dev.inkdeck.ai.Role
import dev.inkdeck.eink.EinkGeometry
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.widget.StepBar
import dev.inkdeck.eink.R as EinkR
import java.util.Date

/**
 * One chat bubble — design.md §10.
 *
 * ```
 *                     ┌──────────────────────────────┐
 *                     │ Why is my bot skipping…      │  user: 1.5 dp border,
 *                     │                  13:31       │  right-aligned, max 80 %
 *                     └──────────────────────────────┘
 *   The log line means…                                 assistant: no border,
 *   ┌──────────────────────────────┐                    left-aligned, ink_900
 *   │ MAX_SPREAD_BPS = 15          │  code: ink_200 fill
 *   └──────────────────────────────┘
 * ```
 *
 * Real `TextView`s, not Canvas text. Wrapping, measuring and selection-free layout of a
 * multi-paragraph answer is exactly what `StaticLayout` is for, and Canvas text would also be
 * invisible to TalkBack and `uiautomator` — the trap noted in docs/AGENT_BRIEF.md.
 *
 * The assistant bubble has no border by design: it is the bulk of the screen, and a box drawn
 * around 80 % of the content is 1.5 dp of ink per line of reading for no information.
 */
class MessageRowView(context: Context) : LinearLayout(context) {

    private val bubble = BubbleColumn(context)
    private val stepBar = StepBar(context)

    private val gap = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
    private val margin = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
    private val pad = resources.getDimensionPixelSize(EinkR.dimen.ink_space_3)

    init {
        orientation = HORIZONTAL
        setPadding(margin, gap, margin, gap)
        addView(bubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bubble.orientation = VERTICAL
    }

    fun bind(message: ChatMessage, streaming: Boolean, workingStep: Int) {
        val user = message.role == Role.USER
        val boxed = user || message.isError

        gravity = if (user) Gravity.END else Gravity.START

        // 80 % for the user (design.md §10), full width for the assistant: an answer is the
        // reason the screen exists and there is no second column to leave room for.
        val content = EinkGeometry.contentBox(context).widthPx - margin * 2
        bubble.maxWidthPx = if (user) (content * USER_WIDTH_FRACTION).toInt() else content

        bubble.setBackgroundResource(if (boxed) R.drawable.bg_ai_bubble else 0)
        bubble.setPadding(
            if (boxed) pad else 0,
            if (boxed) pad else 0,
            if (boxed) pad else 0,
            if (boxed) pad else 0,
        )

        bubble.removeAllViews()

        if (message.isError) {
            // §2.3: error is a filled badge glyph, never a colour. Prefixed rather than drawn so
            // it stays inside the flowing text and needs no extra row.
            bubble.addView(textView(context.getString(R.string.ai_error_prefix, message.text)))
        } else {
            MarkdownLite.split(message.text).forEach { segment ->
                when (segment) {
                    is MarkdownLite.Segment.Text -> bubble.addView(textView(segment.text))
                    is MarkdownLite.Segment.Code -> bubble.addView(codeView(segment.text))
                }
            }
        }

        if (streaming) {
            // design.md §10: "While buffering, show a stepped ▪▪▪░░ bar in the assistant bubble."
            // §5.7 and §14 item 9 make this the only sanctioned "working" affordance — a moving
            // typing indicator would repaint continuously at a rate the panel cannot serve.
            stepBar.steps = STEP_BAR_STEPS
            stepBar.progress = workingStep
            stepBar.contentDescription = context.getString(R.string.ai_working)
            bubble.addView(
                stepBar,
                LayoutParams(EinkTheme.dp(context, 120f).toInt(), EinkTheme.dp(context, 12f).toInt())
                    .also { it.topMargin = gap },
            )
        } else {
            bubble.addView(metaView(message))
        }

        contentDescription = context.getString(
            if (user) R.string.ai_a11y_you else R.string.ai_a11y_assistant,
            message.text,
        )
    }

    private fun textView(text: String) = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_Body)
        this.text = text
    }

    private fun codeView(text: String) = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_MonoUi)
        setBackgroundResource(R.drawable.bg_ai_code)
        val p = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
        setPadding(p, p, p, p)
        this.text = text
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = gap; it.bottomMargin = gap }
    }

    private fun metaView(message: ChatMessage) = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
        gravity = Gravity.END
        text = DateFormat.getTimeFormat(context).format(Date(message.atEpochMs))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    /**
     * `WRAP_CONTENT` up to a ceiling. `LinearLayout` has no `maxWidth`, and the alternatives —
     * a weighted spacer, or a percentage width — would make every bubble that wide, so a
     * three-word question would sit in an 80 %-wide box.
     */
    private class BubbleColumn(context: Context) : LinearLayout(context) {
        var maxWidthPx: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = if (maxWidthPx > 0 && MeasureSpec.getSize(widthMeasureSpec) > maxWidthPx) {
                MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST)
            } else {
                widthMeasureSpec
            }
            super.onMeasure(spec, heightMeasureSpec)
        }
    }

    private companion object {
        const val USER_WIDTH_FRACTION = 0.8f
        const val STEP_BAR_STEPS = 5
    }
}
