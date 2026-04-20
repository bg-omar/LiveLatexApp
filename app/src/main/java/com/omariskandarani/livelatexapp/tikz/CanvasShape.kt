package com.omariskandarani.livelatexapp.tikz

import android.graphics.PointF

/** Drawable primitives for the simplified TikZ canvas (matches IntelliJ TikzCanvasDialog model). */
sealed class CanvasShape {
    data class Dot(val p: PointF) : CanvasShape()
    data class Label(val p: PointF, val text: String) : CanvasShape()
    data class LineSeg(val a: PointF, val b: PointF) : CanvasShape()
    data class Rect(val a: PointF, val b: PointF) : CanvasShape()
    /** Radius in grid units (same as plugin: rPx / STEP). */
    data class Circ(val c: PointF, val rUnits: Double) : CanvasShape()
}
