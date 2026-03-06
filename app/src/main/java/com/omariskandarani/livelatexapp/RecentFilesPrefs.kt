package com.omariskandarani.livelatexapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists recent file URIs and display names for the sidebar. */
object RecentFilesPrefs {
    private const val PREF_NAME = "LiveLatexApp"
    private const val KEY_RECENT = "recent_files"
    private const val MAX_RECENT = 10

    data class Entry(val uri: String, val displayName: String)

    fun getRecent(context: Context): List<Entry> {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Entry(obj.getString("uri"), obj.optString("name", "?"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecent(context: Context, uri: String, displayName: String) {
        val current = getRecent(context).toMutableList()
        val newEntry = Entry(uri, displayName)
        current.removeAll { it.uri == uri }
        current.add(0, newEntry)
        val trimmed = current.take(MAX_RECENT)
        val arr = JSONArray()
        trimmed.forEach { e ->
            arr.put(JSONObject().put("uri", e.uri).put("name", e.displayName))
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT, arr.toString())
            .apply()
    }

    fun removeRecent(context: Context, uri: String) {
        val current = getRecent(context).toMutableList()
        current.removeAll { it.uri == uri }
        val arr = JSONArray()
        current.forEach { e ->
            arr.put(JSONObject().put("uri", e.uri).put("name", e.displayName))
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT, arr.toString())
            .apply()
    }

    fun clearRecent(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECENT)
            .apply()
    }
}
