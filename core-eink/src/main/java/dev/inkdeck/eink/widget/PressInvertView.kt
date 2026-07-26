package dev.inkdeck.eink.widget

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Base for every tappable InkDeck widget.
 *
 * design.md §5.1: "Pressed state = full invert, held 120 ms minimum so it is perceptible at
 * 16 fps." Both halves matter. Invert because a ripple or a tint change dithers into noise;
 * the 120 ms floor because a press shorter than two panel frames may never be drawn at all,
 * and an unacknowledged tap is what makes users double-tap.
 *
 * Subclasses draw normally and consult [inverted] to swap ink and paper.
 */
abstract class PressInvertView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    protected var inverted: Boolean = false
        private set

    private var pressStartedAt = 0L

    private val releaseInvert = Runnable {
        inverted = false
        invalidate()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(releaseInvert)
                inverted = true
                pressStartedAt = SystemClock.uptimeMillis()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val held = SystemClock.uptimeMillis() - pressStartedAt
                postDelayed(releaseInvert, (MIN_HOLD_MS - held).coerceAtLeast(0L))
                if (isInsideBounds(event)) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(releaseInvert)
                releaseInvert.run()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        removeCallbacks(releaseInvert)
        super.onDetachedFromWindow()
    }

    private fun isInsideBounds(event: MotionEvent): Boolean =
        event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height

    private companion object {
        const val MIN_HOLD_MS = 120L
    }
}
