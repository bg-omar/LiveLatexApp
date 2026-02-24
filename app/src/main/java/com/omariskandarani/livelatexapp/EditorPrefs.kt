package com.omariskandarani.livelatexapp

import android.content.Context

object EditorPrefs {
    private const val PREF_NAME = "LiveLatexApp"
    const val KEY_EDITOR_FONT_SIZE_SP = "editor_font_size_sp"
    const val DEFAULT_FONT_SIZE_SP = 14f
    const val MIN_FONT_SIZE_SP = 10f
    const val MAX_FONT_SIZE_SP = 40f

    fun getEditorFontSizeSp(context: Context): Float {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_EDITOR_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
    }

    fun setEditorFontSizeSp(context: Context, sizeSp: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_EDITOR_FONT_SIZE_SP, sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
            .apply()
    }
}
