package com.omariskandarani.livelatexapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.ScrollView

/**
 * A view that draws line numbers for the given EditText. Sync with the editor by calling
 * [setEditText] and ensure the editor calls [invalidate] on scroll and text change.
 * Width fits the widest line label (current line count) plus horizontal padding.
 */
class LineNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var editText: EditText? = null
    private var lastDigitCountForLayout = -1

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

    private val minWidthPx: Int
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            20f,
            resources.displayMetrics
        ).toInt()

    fun setEditText(editor: EditText?) {
        editText = editor
        lastDigitCountForLayout = -1
        requestLayout()
    }

    private fun lineCountLabel(): String {
        val n = editText?.lineCount?.coerceAtLeast(1) ?: 1
        return n.toString()
    }

    private fun digitCountForLayout(): Int = lineCountLabel().length

    override fun invalidate() {
        val d = digitCountForLayout()
        if (d != lastDigitCountForLayout) {
            lastDigitCountForLayout = d
            requestLayout()
        }
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val label = lineCountLabel()
        val textW = paint.measureText(label)
        val horizontal = paddingLeft + paddingRight
        val desired = (textW + horizontal).toInt().coerceAtLeast(minWidthPx)
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(View.MeasureSpec.getSize(heightMeasureSpec), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val editor = editText ?: return
        val lineCount = editor.lineCount.coerceAtLeast(1)
        val lineHeight = editor.lineHeight
        val paddingTop = editor.paddingTop
        val descent = paint.descent()
        val gapEnd = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            resources.displayMetrics
        )
        val parent = editor.parent
        if (parent is ScrollView) {
            for (i in 0 until lineCount) {
                val lineNum = i + 1
                val y = paddingTop + (i + 1) * lineHeight - descent
                val text = lineNum.toString()
                val x = width - paddingRight - gapEnd - paint.measureText(text)
                canvas.drawText(text, x, y, paint)
            }
        } else {
            val scrollY = editor.scrollY
            val firstVisible = (scrollY / lineHeight).toInt().coerceIn(0, lineCount - 1)
            val lastVisible = ((scrollY + height) / lineHeight).toInt().coerceIn(0, lineCount - 1)
            for (i in firstVisible..lastVisible) {
                val lineNum = i + 1
                val y = paddingTop + (i + 1) * lineHeight - descent - scrollY
                val text = lineNum.toString()
                val x = width - paddingRight - gapEnd - paint.measureText(text)
                canvas.drawText(text, x, y, paint)
            }
        }
    }
}
