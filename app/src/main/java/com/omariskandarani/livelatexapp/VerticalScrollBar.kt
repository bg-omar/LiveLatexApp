package com.omariskandarani.livelatexapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Vertical scrollbar with a draggable thumb. Sync with a ScrollView:
 * call [setProgress] when the ScrollView scrolls; set [onProgressChange] to update the ScrollView when the user drags.
 */
class VerticalScrollBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 0f
    private var thumbRatio: Float = 0.3f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE0E0E0.toInt()
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF757575.toInt()
    }
    private val trackRect = RectF()
    private val thumbRect = RectF()

    var onProgressChange: ((Float) -> Unit)? = null

    fun setProgress(p: Float) {
        val newProgress = p.coerceIn(0f, 1f)
        if (newProgress != progress) {
            progress = newProgress
            invalidate()
        }
    }

    /** Ratio of thumb height to track height (viewport/content). Clamped to [0.1f, 1f]. */
    fun setThumbRatio(ratio: Float) {
        thumbRatio = ratio.coerceIn(0.1f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = w / 2f
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val thumbH = (h * thumbRatio).coerceIn(w * 2f, h)
        val range = (h - thumbH).coerceAtLeast(0f)
        val top = progress * range
        thumbRect.set(0f, top, w, top + thumbH)
        canvas.drawRoundRect(thumbRect, radius, radius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val h = height.toFloat()
                val thumbH = (h * thumbRatio).coerceIn(width.toFloat() * 2f, h)
                val range = (h - thumbH).coerceAtLeast(0f)
                if (range <= 0f) return true
                val y = event.y.coerceIn(thumbH / 2f, h - thumbH / 2f)
                val newProgress = ((y - thumbH / 2f) / range).coerceIn(0f, 1f)
                setProgress(newProgress)
                onProgressChange?.invoke(progress)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }
}
