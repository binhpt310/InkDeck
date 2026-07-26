package dev.inkdeck.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR

/**
 * Bottom navigation — design.md §6.
 *
 * ```
 *    ▐▛▜▌        ☑           ▤             ✦          56 dp
 *   Terminal    Tasks      Market         AI          caption
 *   ↑ active: ink_900 icon + emphasis label + 3 dp top bar
 *     inactive: ink_500 icon + regular label
 * ```
 *
 * The 3 dp top bar is the load-bearing part of the active state. Icon and label weight alone
 * are a one-ramp-step difference that the panel dithers away; a solid bar is unambiguous at any
 * viewing angle, which is design.md §14 item 3 applied to navigation.
 *
 * No press animation: a tab switch is `[F]` (§13), and the flush itself is the acknowledgement.
 */
class TabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    class Spec(@DrawableRes val icon: Int, @StringRes val label: Int)

    var onTabSelected: ((index: Int) -> Unit)? = null

    var selectedIndex: Int = -1
        set(value) {
            if (field == value) return
            field = value
            for (i in 0 until childCount) {
                (getChildAt(i) as TabItemView).active = (i == value)
            }
        }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(EinkTheme.paper(context))
    }

    fun setTabs(specs: List<Spec>) {
        removeAllViews()
        specs.forEachIndexed { index, spec ->
            val item = TabItemView(context).apply {
                icon = ContextCompat.getDrawable(context, spec.icon)
                label = context.getString(spec.label)
                contentDescription = label
                setOnClickListener { onTabSelected?.invoke(index) }
            }
            addView(item, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        selectedIndex = -1
    }
}

private class TabItemView(context: Context) : View(context) {

    var icon: Drawable? = null
        set(value) {
            field = value?.mutate()
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = EinkTheme.sp(context, 14f)
    }
    private val barPaint = Paint().apply { style = Paint.Style.FILL }

    private val iconSize = resources.getDimensionPixelSize(EinkR.dimen.ink_icon)
    private val indicatorHeight = resources.getDimensionPixelSize(EinkR.dimen.ink_tab_indicator)
    private val iconLabelGap = EinkTheme.dp(context, 3f)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val ink = if (active) EinkTheme.ink900(context) else EinkTheme.ink500(context)

        if (active) {
            barPaint.color = EinkTheme.ink900(context)
            canvas.drawRect(0f, 0f, width.toFloat(), indicatorHeight.toFloat(), barPaint)
        }

        textPaint.color = ink
        textPaint.typeface = if (active) {
            EinkTheme.uiEmphasisTypeface(context)
        } else {
            EinkTheme.uiTypeface(context)
        }

        val fm = textPaint.fontMetrics
        val labelHeight = fm.descent - fm.ascent
        val blockHeight = iconSize + iconLabelGap + labelHeight
        // Centred in the space below the indicator so the icon does not ride up against it.
        val top = indicatorHeight + (height - indicatorHeight - blockHeight) / 2f

        icon?.let { d ->
            val left = ((width - iconSize) / 2f).toInt()
            d.setBounds(left, top.toInt(), left + iconSize, top.toInt() + iconSize)
            d.setColorFilter(ink, PorterDuff.Mode.SRC_IN)
            d.draw(canvas)
        }

        canvas.drawText(
            label,
            width / 2f,
            top + iconSize + iconLabelGap - fm.ascent,
            textPaint,
        )
    }

    override fun performClick(): Boolean = super.performClick()
}
