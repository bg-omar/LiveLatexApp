package com.omariskandarani.livelatexapp.latex.engine

import android.content.Context

object LatexEnginePrefs {
    private const val PREF = "LiveLatexApp"
    private const val KEY_ENGINE_KIND = "latex_engine_kind"

    fun getEngineKind(context: Context): LatexEngineKind {
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_ENGINE_KIND, LatexEngineKind.TECTONIC.prefValue)
        return LatexEngineKind.fromPref(v)
    }

    fun setEngineKind(context: Context, kind: LatexEngineKind) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ENGINE_KIND, kind.prefValue)
            .apply()
    }
}
