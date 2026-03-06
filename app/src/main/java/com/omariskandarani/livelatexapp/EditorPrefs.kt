package com.omariskandarani.livelatexapp

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object EditorPrefs {
    private const val PREF_NAME = "LiveLatexApp"
    const val KEY_EDITOR_FONT_SIZE_SP = "editor_font_size_sp"
    const val DEFAULT_FONT_SIZE_SP = 14f
    const val MIN_FONT_SIZE_SP = 10f
    const val MAX_FONT_SIZE_SP = 40f

    const val KEY_AUTO_SAVE_ENABLED = "auto_save_enabled"
    const val DEFAULT_AUTO_SAVE_ENABLED = true
    const val KEY_AUTO_SAVE_INTERVAL_SEC = "auto_save_interval_sec"
    const val DEFAULT_AUTO_SAVE_INTERVAL_SEC = 30

    const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
    const val KEY_SHOW_LINE_NUMBERS = "show_line_numbers"
    const val DEFAULT_SHOW_LINE_NUMBERS = false

    const val KEY_NIGHT_MODE = "night_mode"

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

    fun isAutoSaveEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SAVE_ENABLED, DEFAULT_AUTO_SAVE_ENABLED)
    }

    fun setAutoSaveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SAVE_ENABLED, enabled)
            .apply()
    }

    fun getAutoSaveIntervalSec(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_AUTO_SAVE_INTERVAL_SEC, DEFAULT_AUTO_SAVE_INTERVAL_SEC)
    }

    fun setAutoSaveIntervalSec(context: Context, sec: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_AUTO_SAVE_INTERVAL_SEC, sec.coerceIn(10, 300))
            .apply()
    }

    fun isFirstLaunchDone(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIRST_LAUNCH_DONE, false)
    }

    fun setFirstLaunchDone(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIRST_LAUNCH_DONE, true)
            .apply()
    }

    fun getShowLineNumbers(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_LINE_NUMBERS, DEFAULT_SHOW_LINE_NUMBERS)
    }

    fun setShowLineNumbers(context: Context, show: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_LINE_NUMBERS, show)
            .apply()
    }

    fun getNightMode(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    fun setNightMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NIGHT_MODE, mode)
            .apply()
    }
}
