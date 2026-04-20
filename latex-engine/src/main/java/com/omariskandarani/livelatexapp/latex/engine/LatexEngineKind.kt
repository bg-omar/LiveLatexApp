package com.omariskandarani.livelatexapp.latex.engine

/**
 * Offline LaTeX backend selected in settings.
 * [TEX_LIVE] is reserved for a future full TeX Live–style JNI backend.
 */
enum class LatexEngineKind(val prefValue: Int) {
    TECTONIC(0),
    TEX_LIVE(1);

    companion object {
        fun fromPref(value: Int): LatexEngineKind =
            entries.find { it.prefValue == value } ?: TECTONIC
    }
}
