package com.omariskandarani.livelatexapp.tikz

import com.omariskandarani.livelatexapp.R

/**
 * Knot/circle presets aligned with the IntelliJ [TikzCanvasDialog] (unit-space coordinates).
 */
data class TikzPreset(
    val labelRes: Int,
    val ptsUnits: List<Pair<Double, Double>> = emptyList(),
    val circlesUnits: List<Triple<Double, Double, Double>> = emptyList(),
    /** Default flip list for knot exports; null or empty → user/auto in export. */
    val flipList: String? = null
)

object TikzPresets {

    /**
     * Order matches the desktop preset dropdown (plus Custom is separate in the UI).
     */
    val PRESETS: List<TikzPreset> = listOf(
        TikzPreset(
            R.string.insert_tikz_preset_trefoil,
            ptsUnits = listOf(
                0.0 to 2.0,
                -1.0 to 1.0,
                -1.0 to 0.0,
                1.0 to -1.0,
                2.0 to 0.0,
                0.0 to 0.75,
                -2.0 to 0.0,
                -1.0 to -1.0,
                1.0 to 0.0,
                1.0 to 1.0,
                0.0 to 2.0,
                0.0 to 2.0
            ),
            flipList = "2,4,6,8,10,12,14"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_4_1,
            ptsUnits = listOf(
                -2.0 to -2.0,
                -2.0 to 2.0,
                1.0 to -0.5,
                -1.0 to -0.5,
                2.0 to 2.0,
                2.0 to -2.0,
                -1.0 to 0.5,
                1.0 to 0.5,
                -2.0 to -2.0
            ),
            flipList = "2,4,6,8,10,12,14"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_5_1,
            ptsUnits = listOf(
                -0.25 to 1.75,
                -1.25 to -0.50,
                -0.75 to -1.0,
                1.50 to 0.50,
                1.25 to 1.0,
                -1.25 to 1.0,
                -1.50 to 0.50,
                0.75 to -1.0,
                1.25 to -0.50,
                0.25 to 1.75,
                -0.25 to 1.75,
                -0.25 to 1.75
            ),
            flipList = "2,4,6,8,10,12,14"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_5_2,
            ptsUnits = listOf(
                2.0 to -1.5,
                1.5 to 1.0,
                0.0 to 2.0,
                -2.0 to 1.0,
                -1.0 to -1.5,
                0.5 to -2.0,
                -1.25 to -2.25,
                -2.0 to -1.5,
                -1.5 to 1.0,
                0.0 to 2.0,
                2.0 to 1.0,
                1.0 to -1.5,
                -0.5 to -2.0,
                1.25 to -2.25,
                2.0 to -1.5
            ),
            flipList = "2,4,6,8,10,12,14,16,18"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_6_1,
            ptsUnits = listOf(
                0.0 to 2.0,
                -2.0 to 2.0,
                -1.5 to -1.0,
                0.5 to -2.0,
                -1.5 to -2.0,
                -2.5 to -0.5,
                -2.0 to 1.0,
                0.0 to 3.0,
                2.0 to 1.0,
                2.5 to -0.5,
                1.5 to -2.0,
                -0.5 to -2.0,
                1.5 to -1.0,
                2.0 to 2.0,
                0.0 to 2.0
            ),
            flipList = "2,4,6,8,10,12,14,16,18"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_7_1,
            ptsUnits = listOf(
                -0.25 to 2.0,
                -1.50 to 0.50,
                -1.50 to 0.0,
                0.50 to -1.0,
                1.0 to -0.75,
                1.25 to 1.25,
                0.75 to 1.75,
                -0.75 to 1.75,
                -1.25 to 1.25,
                -1.0 to -0.75,
                -0.50 to -1.0,
                1.50 to 0.0,
                1.50 to 0.50,
                0.25 to 2.0,
                -0.25 to 2.0,
                -0.25 to 2.0
            ),
            flipList = "2,4,6,8,10,12,14,16,18"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_7_2,
            ptsUnits = listOf(
                0.0 to 2.0,
                -2.50 to 2.25,
                -1.75 to 0.0,
                -1.50 to -2.50,
                -0.25 to -2.75,
                0.50 to -1.50,
                -0.25 to -1.0,
                -3.0 to 0.0,
                -1.25 to 1.25,
                -0.25 to 3.25,
                1.25 to 1.25,
                2.75 to 0.0,
                0.75 to -0.75,
                -0.50 to -1.50,
                0.50 to -2.75,
                1.75 to -1.75,
                1.50 to 0.0,
                2.0 to 2.50,
                0.0 to 2.0
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_8_1,
            ptsUnits = listOf(
                0.75 to 3.25,
                -1.0 to 2.0,
                -3.0 to 1.75,
                -2.0 to -0.25,
                -1.75 to -2.50,
                -0.50 to -1.0,
                -3.25 to -0.50,
                -1.75 to 1.25,
                -1.25 to 3.25,
                0.25 to 2.0,
                2.50 to 1.75,
                1.0 to -2.50,
                -0.75 to -2.0,
                0.50 to -0.75,
                2.75 to -0.50,
                1.25 to 1.25,
                0.75 to 3.25
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_9_1,
            ptsUnits = listOf(
                0.25 to 3.25,
                -1.25 to 2.0,
                -3.25 to 1.50,
                -2.25 to -0.50,
                -2.0 to -2.50,
                0.0 to -2.0,
                2.25 to -1.75,
                1.75 to 0.0,
                2.25 to 2.25,
                0.25 to 2.25,
                -1.75 to 3.0,
                -2.25 to 1.0,
                -3.50 to -0.75,
                -1.50 to -1.75,
                0.25 to -3.0,
                1.75 to -1.0,
                3.0 to 0.25,
                1.0 to 2.0,
                0.25 to 3.25
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_9_2,
            ptsUnits = listOf(
                0.75 to 3.25,
                -1.0 to 2.0,
                -3.0 to 1.75,
                -2.0 to -0.25,
                -1.75 to -2.50,
                -0.50 to -2.50,
                0.25 to -1.50,
                -0.50 to -1.0,
                -3.25 to -0.50,
                -1.75 to 1.25,
                -1.25 to 3.25,
                0.25 to 2.0,
                2.50 to 1.75,
                1.75 to 0.0,
                1.0 to -2.50,
                -0.75 to -2.0,
                0.50 to -0.75,
                2.75 to -0.50,
                1.25 to 1.25,
                0.75 to 3.25
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_10_1,
            ptsUnits = listOf(
                0.0 to 2.0,
                -1.75 to 3.0,
                -1.75 to 1.0,
                -3.0 to -1.0,
                -1.0 to -0.75,
                0.50 to -2.0,
                -1.50 to -2.0,
                -2.25 to -0.25,
                -3.25 to 1.25,
                -1.25 to 1.75,
                0.0 to 3.25,
                1.25 to 1.75,
                3.25 to 1.25,
                2.25 to -0.25,
                1.50 to -2.0,
                -0.50 to -2.0,
                1.0 to -0.75,
                3.0 to -1.0,
                1.75 to 1.0,
                1.75 to 3.0,
                0.0 to 2.0
            ),
            flipList = "2,4,6,8,10,12,14,16,18,20"
        ),
        TikzPreset(
            R.string.insert_tikz_preset_hopf,
            circlesUnits = listOf(
                Triple(1.0, 0.0, 2.0),
                Triple(-1.0, 0.0, 2.0)
            ),
            flipList = null
        ),
        TikzPreset(
            R.string.insert_tikz_preset_borromean,
            circlesUnits = listOf(
                Triple(1.0, 0.0, 2.0),
                Triple(-1.0, 0.0, 2.0),
                Triple(0.0, kotlin.math.sqrt(3.0), 2.0)
            ),
            flipList = null
        )
    )
}
