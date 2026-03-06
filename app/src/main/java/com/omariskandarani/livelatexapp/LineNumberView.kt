package com.omariskandarani.livelatexapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.EditText

/**
 * A view that draws line numbers for the given EditText. Sync with the editor by calling
 * [setEditText] and ensure the editor calls [invalidate] on scroll and text change.
 */
class LineNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var editText: EditText? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * resources.displayMetrics.density
        val typedValue = TypedValue()
        @Suppress("DiscouragedApi")
        if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)) {
            color = typedValue.data
        } else {
            color = 0xFF9E9E9E.toInt()
        }
    }

    fun setEditText(editor: EditText?) {
        editText = editor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val editor = editText ?: return
        val lineCount = editor.lineCount.coerceAtLeast(1)
        val lineHeight = editor.lineHeight
        val scrollY = editor.scrollY
        val paddingTop = editor.paddingTop
        val firstVisible = (scrollY / lineHeight).toInt().coerceIn(0, lineCount - 1)
        val lastVisible = ((scrollY + height) / lineHeight).toInt().coerceIn(0, lineCount - 1)
        val descent = paint.descent()
        for (i in firstVisible..lastVisible) {
            val lineNum = i + 1
            val y = paddingTop + (i + 1) * lineHeight - descent - scrollY
            val text = lineNum.toString()
            val x = width - paint.measureText(text) - 8f
            canvas.drawText(text, x, y, paint)
        }
    }
}
