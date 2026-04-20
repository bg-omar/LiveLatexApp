package com.omariskandarani.livelatexapp.tikz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Simplified touch canvas: knot polyline + basic shapes, same grid semantics as the IntelliJ TikZ dialog.
 */
class TikzCanvasView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(ctx, attrs, defStyleAttr) {

    enum class Tool {
        KNOT, LINE, CIRCLE, RECTANGLE, DOT, TEXT
    }

    companion object {
        private const val STEP = 100f
        private const val SUB = 25f
        private const val GRID_UNITS = 4
        private const val LONG_PRESS_MS = 380L
    }

    private fun pickRadiusPx(): Float = 48f * resources.displayMetrics.density

    var tool: Tool = Tool.KNOT
        set(value) {
            field = value
            pendingFirst = null
            invalidate()
        }

    /** Fired for [Tool.TEXT]: provide screen-space point (snapped) to place a label. */
    var onRequestTextAt: ((PointF) -> Unit)? = null

    private val knotPts = ArrayList<PointF>()
    private val shapes = ArrayList<CanvasShape>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 120, 120, 140)
        strokeWidth = 1f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 28, 77, 185)
        strokeWidth = 3f
    }
    private val knotEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 185, 28, 28)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val knotPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 185, 28, 28)
        style = Paint.Style.FILL
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 55, 65, 81)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 37, 99, 235)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 28, 77, 185)
        textSize = 28f
    }

    private var dragKnotIdx = -1
    private var downX = 0f
    private var downY = 0f
    private var movedPastSlop = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var pendingFirst: PointF? = null

    private val longHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    fun clearAll() {
        knotPts.clear()
        shapes.clear()
        pendingFirst = null
        invalidate()
    }

    /**
     * Load geometry from unit-space preset (centered on current view size). Call after layout, or use [post].
     */
    fun loadPreset(
        ptsUnits: List<Pair<Double, Double>>,
        circlesUnits: List<Triple<Double, Double, Double>>
    ) {
        knotPts.clear()
        shapes.clear()
        pendingFirst = null
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val cx = w / 2f
        val cy = h / 2f
        for ((ux, uy) in ptsUnits) {
            knotPts.add(PointF(cx + ux.toFloat() * STEP, cy - uy.toFloat() * STEP))
        }
        for ((ux, uy, ru) in circlesUnits) {
            shapes.add(CanvasShape.Circ(PointF(cx + ux.toFloat() * STEP, cy - uy.toFloat() * STEP), ru))
        }
        invalidate()
    }

    fun addLabel(p: PointF, text: String) {
        if (text.isBlank()) return
        shapes.add(CanvasShape.Label(p, text.trim()))
        invalidate()
    }

    fun buildTikzString(flipCrossings: String, showGuides: Boolean, rotateDeg: Float): String {
        val w = if (width > 0) width else (400 * resources.displayMetrics.density).toInt()
        val h = if (height > 0) height else (400 * resources.displayMetrics.density).toInt()
        return TikzCanvasExport.build(
            w,
            h,
            knotPts.toList(),
            shapes.toList(),
            flipCrossings,
            showGuides,
            rotateDeg
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minH = (380 * resources.displayMetrics.density).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minH, MeasureSpec.EXACTLY))
    }

    private fun snap(p: PointF): PointF {
        val cx = width / 2f
        val cy = height / 2f
        val sx = (kotlin.math.round((p.x - cx) / SUB) * SUB).toFloat()
        val sy = (kotlin.math.round((p.y - cy) / SUB) * SUB).toFloat()
        return PointF(cx + sx, cy + sy)
    }

    private fun nearestKnotIndex(x: Float, y: Float): Int {
        if (knotPts.isEmpty()) return -1
        val pick = pickRadiusPx()
        val pr = pick * pick
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in knotPts.indices) {
            val p = knotPts[i]
            val d = hypot(x - p.x, y - p.y)
            if (d * d <= pr && d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun addKnotIfNotDuplicate(p: PointF) {
        val idx = nearestKnotIndex(p.x, p.y)
        if (idx >= 0) return
        knotPts.add(p)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        for (i in -GRID_UNITS..GRID_UNITS) {
            val x = cx + i * STEP
            val y = cy + i * STEP
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
        canvas.drawLine(cx, 0f, cx, height.toFloat(), axisPaint)
        canvas.drawLine(0f, cy, width.toFloat(), cy, axisPaint)

        for (s in shapes) {
            when (s) {
                is CanvasShape.Dot -> canvas.drawCircle(s.p.x, s.p.y, 6f, knotPointPaint)
                is CanvasShape.Label -> {
                    canvas.drawText(s.text, s.p.x + 8f, s.p.y - 4f, labelPaint)
                }
                is CanvasShape.LineSeg -> {
                    canvas.drawLine(s.a.x, s.a.y, s.b.x, s.b.y, shapePaint)
                }
                is CanvasShape.Rect -> {
                    val l = min(s.a.x, s.b.x)
                    val r = max(s.a.x, s.b.x)
                    val t = min(s.a.y, s.b.y)
                    val b = max(s.a.y, s.b.y)
                    canvas.drawRect(l, t, r, b, shapePaint)
                }
                is CanvasShape.Circ -> {
                    val rPx = (s.rUnits * STEP).toFloat()
                    canvas.drawCircle(s.c.x, s.c.y, rPx, shapePaint)
                }
            }
        }

        if (knotPts.size >= 2) {
            for (i in 0 until knotPts.size - 1) {
                val a = knotPts[i]
                val b = knotPts[i + 1]
                canvas.drawLine(a.x, a.y, b.x, b.y, knotEdgePaint)
            }
        }
        for (p in knotPts) {
            canvas.drawCircle(p.x, p.y, 8f, knotPointPaint)
        }

        pendingFirst?.let { p ->
            canvas.drawCircle(p.x, p.y, 10f, previewPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                movedPastSlop = false
                dragKnotIdx = if (tool == Tool.KNOT) nearestKnotIndex(x, y) else -1
                longPressRunnable = Runnable {
                    if (tool != Tool.KNOT) return@Runnable
                    val i = nearestKnotIndex(downX, downY)
                    if (i >= 0) {
                        knotPts.removeAt(i)
                        if (dragKnotIdx == i) dragKnotIdx = -1
                        invalidate()
                    }
                }
                longHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKnotIdx >= 0) {
                    longPressRunnable?.let { longHandler.removeCallbacks(it) }
                }
                if (hypot(x - downX, y - downY) > touchSlop) {
                    movedPastSlop = true
                    longPressRunnable?.let { longHandler.removeCallbacks(it) }
                }
                if (tool == Tool.KNOT && dragKnotIdx >= 0) {
                    knotPts[dragKnotIdx] = snap(PointF(x, y))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { longHandler.removeCallbacks(it) }
                longPressRunnable = null

                if (tool == Tool.KNOT) {
                    if (dragKnotIdx >= 0) {
                        dragKnotIdx = -1
                    } else if (!movedPastSlop) {
                        addKnotIfNotDuplicate(snap(PointF(x, y)))
                    }
                    invalidate()
                    return true
                }

                if (movedPastSlop) return true

                val p = snap(PointF(x, y))
                when (tool) {
                    Tool.LINE -> {
                        if (pendingFirst == null) {
                            pendingFirst = p
                        } else {
                            shapes.add(CanvasShape.LineSeg(pendingFirst!!, p))
                            pendingFirst = null
                        }
                    }
                    Tool.CIRCLE -> {
                        if (pendingFirst == null) {
                            pendingFirst = p
                        } else {
                            val c = pendingFirst!!
                            val rPx = hypot(p.x - c.x, p.y - c.y).coerceAtLeast(SUB)
                            val rUnits = (rPx / STEP).toDouble().coerceAtLeast(0.05)
                            shapes.add(CanvasShape.Circ(c, rUnits))
                            pendingFirst = null
                        }
                    }
                    Tool.RECTANGLE -> {
                        if (pendingFirst == null) {
                            pendingFirst = p
                        } else {
                            shapes.add(CanvasShape.Rect(pendingFirst!!, p))
                            pendingFirst = null
                        }
                    }
                    Tool.DOT -> shapes.add(CanvasShape.Dot(p))
                    Tool.TEXT -> onRequestTextAt?.invoke(p)
                    else -> {}
                }
                invalidate()
            }
        }
        return true
    }
}
