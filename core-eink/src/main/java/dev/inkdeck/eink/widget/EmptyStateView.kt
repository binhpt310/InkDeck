package dev.inkdeck.eink.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R

/**
 * The four states every data surface owes the user — design.md §5.7.
 *
 * There is no colour to distinguish them, so each carries a drawn glyph with a distinct
 * silhouette (§2.3): an open frame for empty, a filled badge for error, an hourglass for stale
 * or offline. Loading gets a [StepBar] and no glyph, because a five-state bar already says
 * "working" without animating.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class State { LOADING, EMPTY, ERROR, OFFLINE }

    private val glyph = StateGlyphView(context)
    private val titleView = TextView(context)
    private val detailView = TextView(context)
    private val stepBar = StepBar(context)
    private val actionButton = EinkButton(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = resources.getDimensionPixelSize(R.dimen.ink_screen_margin)
        setPadding(pad, pad, pad, pad)

        val glyphSize = EinkTheme.dp(context, 48f).toInt()
        addView(glyph, LayoutParams(glyphSize, glyphSize).also {
            it.bottomMargin = resources.getDimensionPixelSize(R.dimen.ink_space_4)
        })

        titleView.apply {
            gravity = Gravity.CENTER
            setTextAppearance(R.style.TextAppearance_InkDeck_BodyLarge)
        }
        addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        detailView.apply {
            gravity = Gravity.CENTER
            setTextAppearance(R.style.TextAppearance_InkDeck_Caption)
        }
        addView(detailView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.topMargin = resources.getDimensionPixelSize(R.dimen.ink_space_2)
        })

        addView(stepBar, LayoutParams(EinkTheme.dp(context, 160f).toInt(), EinkTheme.dp(context, 12f).toInt())
            .also { it.topMargin = resources.getDimensionPixelSize(R.dimen.ink_space_4) })

        addView(actionButton, LayoutParams(
            EinkTheme.dp(context, 180f).toInt(),
            resources.getDimensionPixelSize(R.dimen.ink_touch_min),
        ).also { it.topMargin = resources.getDimensionPixelSize(R.dimen.ink_space_6) })
    }

    /** Advance the loading bar. The caller owns the pacing; this view never self-animates. */
    fun setLoadingProgress(step: Int) {
        stepBar.progress = step
    }

    fun show(
        state: State,
        title: CharSequence,
        detail: CharSequence? = null,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ) {
        glyph.state = state
        glyph.visibility = if (state == State.LOADING) GONE else VISIBLE

        titleView.text = title

        detailView.text = detail ?: ""
        detailView.visibility = if (detail.isNullOrEmpty()) GONE else VISIBLE

        stepBar.visibility = if (state == State.LOADING) VISIBLE else GONE

        if (actionLabel != null && onAction != null) {
            actionButton.visibility = VISIBLE
            actionButton.text = actionLabel
            actionButton.variant =
                if (state == State.EMPTY) EinkButton.Variant.PRIMARY else EinkButton.Variant.SECONDARY
            actionButton.setOnClickListener { onAction() }
        } else {
            actionButton.visibility = GONE
            actionButton.setOnClickListener(null)
        }
    }
}

/**
 * Draws the state silhouettes. Canvas rather than vector assets because each is a handful of
 * primitives and this keeps the shapes in the same file as the semantics they encode.
 */
private class StateGlyphView(context: Context) : View(context) {

    var state: EmptyStateView.State = EmptyStateView.State.EMPTY
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 2f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = EinkTheme.sp(context, 24f)
        typeface = EinkTheme.uiEmphasisTypeface(context)
    }

    private val rect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val ink = EinkTheme.ink900(context)
        val paper = EinkTheme.paper(context)
        stroke.color = ink
        fill.color = ink

        val inset = stroke.strokeWidth
        rect.set(inset, inset, width - inset, height - inset)
        val radius = EinkTheme.dp(context, 4f)

        when (state) {
            EmptyStateView.State.EMPTY -> {
                // An open frame: nothing here yet.
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }

            EmptyStateView.State.ERROR -> {
                // §2.3: "!" in a solid black square badge.
                canvas.drawRoundRect(rect, radius, radius, fill)
                text.color = paper
                val fm = text.fontMetrics
                canvas.drawText(
                    "!",
                    width / 2f,
                    height / 2f - (fm.ascent + fm.descent) / 2f,
                    text,
                )
            }

            EmptyStateView.State.OFFLINE -> {
                // Hourglass: two triangles meeting at the centre. Reads as "stale" at a glance
                // and shares no silhouette with the other three.
                path.reset()
                path.moveTo(rect.left, rect.top)
                path.lineTo(rect.right, rect.top)
                path.lineTo(rect.centerX(), rect.centerY())
                path.lineTo(rect.right, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.lineTo(rect.centerX(), rect.centerY())
                path.close()
                canvas.drawPath(path, stroke)
            }

            EmptyStateView.State.LOADING -> Unit
        }
    }
}
