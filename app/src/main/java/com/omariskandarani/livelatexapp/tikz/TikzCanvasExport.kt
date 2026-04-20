package com.omariskandarani.livelatexapp.tikz

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Export knot + shapes to TikZ, using the same grid convention as the IntelliJ [TikzCanvasDialog]
 * (STEP = 100 px per unit, y-up in TikZ).
 */
object TikzCanvasExport {

    private const val STEP = 100.0

    private fun formatQ(x: Double): String {
        val xi = x.toInt()
        return if (abs(x - xi) < 1e-9) xi.toString() else "%.6g".format(x)
    }

    private fun quantQ(u: Double): Double = round(u * 4.0) / 4.0

    private fun fmtQ(u: Double): String =
        if (abs(u - u.roundToInt()) < 1e-9) "%d".format(u.roundToInt()) else "%.2f".format(u)

    private fun latexEscape(s: String): String =
        s.replace("\\", "\\textbackslash{}")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("%", "\\%")
            .replace("#", "\\#")
            .replace("_", "\\_")
            .replace("&", "\\&")
            .replace("^", "\\^{}")
            .replace("~", "\\~{}")

    private fun tikzRotateSuffix(rotateDeg: Float): String {
        val d = rotateDeg % 360f
        val norm = if (d < 0) d + 360f else d
        if (norm < 1e-3f || abs(norm - 360f) < 1e-3f) return ""
        val s = if (abs(norm - norm.roundToInt()) < 1e-3f) norm.roundToInt().toString() else formatQ(norm.toDouble())
        return ", rotate=$s"
    }

    private fun toGridQ(p: PointF, cx: Int, cy: Int): Pair<Double, Double> {
        val x = quantQ((p.x - cx).toDouble() / STEP)
        val y = quantQ(-((p.y - cy).toDouble() / STEP))
        return x to y
    }

    private fun appendShapes(sb: StringBuilder, shapes: List<CanvasShape>, cx: Int, cy: Int) {
        if (shapes.isEmpty()) return
        fun toTikz(p: PointF): String {
            val (x, y) = toGridQ(p, cx, cy)
            return "(${fmtQ(x)}, ${fmtQ(y)})"
        }
        for (s in shapes) {
            when (s) {
                is CanvasShape.LineSeg ->
                    sb.append("    \\draw ").append(toTikz(s.a)).append(" -- ").append(toTikz(s.b)).append(";\n")
                is CanvasShape.Circ ->
                    sb.append("    \\draw ").append(toTikz(s.c)).append(" circle (${fmtQ(s.rUnits)});\n")
                is CanvasShape.Rect ->
                    sb.append("    \\draw ").append(toTikz(s.a)).append(" rectangle ").append(toTikz(s.b)).append(";\n")
                is CanvasShape.Dot ->
                    sb.append("    \\fill ").append(toTikz(s.p)).append(" circle (2pt);\n")
                is CanvasShape.Label ->
                    sb.append("    \\node at ").append(toTikz(s.p)).append(" {")
                        .append(latexEscape(s.text)).append("};\n")
            }
        }
    }

    private fun exportShapesOnly(shapes: List<CanvasShape>, canvasW: Int, canvasH: Int, rotateDeg: Float): String {
        val cx = canvasW / 2
        val cy = canvasH / 2
        val sb = StringBuilder()
        sb.append("    \\begin{tikzpicture}[use Hobby shortcut${tikzRotateSuffix(rotateDeg)}]\n")
        appendShapes(sb, shapes, cx, cy)
        sb.append("    \\end{tikzpicture}\n")
        return sb.toString().trimEnd()
    }

    /**
     * Knot block inside one tikzpicture, then optional shapes, then \\end{tikzpicture}.
     */
    private fun exportKnotWithShapesInside(
        knotPts: List<PointF>,
        shapes: List<CanvasShape>,
        canvasW: Int,
        canvasH: Int,
        flipCrossings: String,
        showGuides: Boolean,
        rotateDeg: Float
    ): String {
        val cx = canvasW / 2
        val cy = canvasH / 2

        fun toGridQPt(p: PointF) = toGridQ(p, cx, cy)

        val dedup = ArrayList<PointF>(knotPts.size)
        for (p in knotPts) {
            if (dedup.isEmpty() || dedup.last() != p) dedup += p
        }
        while (dedup.size >= 2 && dedup.last() == dedup.first()) dedup.removeAt(dedup.size - 1)
        val gpts = dedup.map { toGridQPt(it) }
        val n = gpts.size

        val sb = StringBuilder()
        sb.append("    \\begin{tikzpicture}[use Hobby shortcut${tikzRotateSuffix(rotateDeg)}]\n")

        for (i in 0 until n) {
            val (qx, qy) = gpts[i]
            sb.append("    \\coordinate (P${i + 1}) at (${fmtQ(qx)}, ${fmtQ(qy)});\n")
        }
        sb.append("\n")

        val flip = flipCrossings.trim().ifBlank {
            (2..n step 2).joinToString(",")
        }
        val opts = listOf(
            "consider self intersections",
            "clip width=5pt, clip radius=3pt",
            "ignore endpoint intersections=false",
            "flip crossing/.list={$flip}",
            "% ----draft mode=crossings % uncomment to see numbers"
        )
        sb.append("    \\begin{knot}[\n")
        sb.append("        ").append(opts.joinToString(",\n        ")).append("\n")
        sb.append("    ]\n")

        val names = (1..n).map { "P$it" }
        val tail = names.drop(1).joinToString("..") { "($it)" }
        sb.append("    \\strand\n")
        sb.append("    ([closed] ${names.first()})..$tail;\n")
        sb.append("    \\end{knot}\n")

        if (showGuides) {
            sb.append("    \\SSTGuidesPoints{P}{$n}\n")
        }

        appendShapes(sb, shapes, cx, cy)
        sb.append("    \\end{tikzpicture}\n")
        return sb.toString().trimEnd()
    }

    /**
     * Full export: knot strand if enough points; else shapes-only; else minimal empty picture.
     */
    fun build(
        canvasW: Int,
        canvasH: Int,
        knotPts: List<PointF>,
        shapes: List<CanvasShape>,
        flipCrossings: String,
        showGuides: Boolean,
        rotateDeg: Float
    ): String {
        val w = canvasW.coerceAtLeast(1)
        val h = canvasH.coerceAtLeast(1)

        val dedup = ArrayList<PointF>(knotPts.size)
        for (p in knotPts) {
            if (dedup.isEmpty() || dedup.last() != p) dedup += p
        }
        while (dedup.size >= 2 && dedup.last() == dedup.first()) dedup.removeAt(dedup.size - 1)
        val knotOk = dedup.size >= 2

        return when {
            knotOk ->
                exportKnotWithShapesInside(knotPts, shapes, w, h, flipCrossings, showGuides, rotateDeg)
            shapes.isNotEmpty() ->
                exportShapesOnly(shapes, w, h, rotateDeg)
            else ->
                "\\begin{tikzpicture}\n\\end{tikzpicture}"
        }
    }
}
