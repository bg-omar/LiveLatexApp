package com.omariskandarani.livelatexapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-defined punctuation / snippet rows (Pro) + insert usage counts for ordering. */
object PunctuationPrefs {
    private const val PREF_NAME = "LiveLatexApp"
    private const val KEY_CUSTOM = "punctuation_custom_json_v1"
    private const val KEY_INSERT_USAGE = "punctuation_insert_usage_v1"

    private const val TOP_USAGE_SLOTS = 16

    private const val J_ID = "id"
    private const val J_LABEL = "label"
    private const val J_INSERT = "insert"

    data class CustomEntry(val id: String, val label: String, val insertText: String)

    fun getCustomEntries(context: Context): List<CustomEntry> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString(J_ID).ifBlank { UUID.randomUUID().toString() }
                    val label = o.optString(J_LABEL)
                    val ins = o.optString(J_INSERT)
                    if (label.isNotBlank() || ins.isNotBlank()) {
                        add(CustomEntry(id, label, ins))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<CustomEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().apply {
                    put(J_ID, e.id)
                    put(J_LABEL, e.label)
                    put(J_INSERT, e.insertText)
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM, arr.toString())
            .apply()
    }

    fun upsert(context: Context, entry: CustomEntry) {
        val list = getCustomEntries(context).toMutableList()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        save(context, getCustomEntries(context).filter { it.id != id })
    }

    /** Increments use count for this insert string (used to promote top items in the grid). */
    fun recordInsertUse(context: Context, insertText: String) {
        if (insertText.isEmpty()) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_INSERT_USAGE, null) ?: "{}"
        val o = try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
        val key = insertText
        val next = (o.optLong(key, 0L)).coerceAtMost(Long.MAX_VALUE - 1L) + 1L
        o.put(key, next)
        prefs.edit().putString(KEY_INSERT_USAGE, o.toString()).apply()
    }

    fun getInsertUseCounts(context: Context): Map<String, Long> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_INSERT_USAGE, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap {
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    put(k, o.optLong(k, 0L))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Puts up to [TOP_USAGE_SLOTS] most-used entries (by insert text) first, then the rest of
     * [basePairs] in original order, skipping duplicate insert texts.
     */
    fun orderedPunctuationPairs(context: Context, basePairs: List<Pair<String, String>>): List<Pair<String, String>> {
        if (basePairs.isEmpty()) return emptyList()
        val counts = getInsertUseCounts(context)
        val usedInsert = LinkedHashSet<String>()
        val out = mutableListOf<Pair<String, String>>()

        val scored = basePairs
            .map { p -> p to (counts[p.second] ?: 0L) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Pair<String, String>, Long>> { it.second }
                    .thenBy { it.first.first }
            )
        val topBlock = scored
            .distinctBy { it.first.second }
            .map { it.first }
            .take(TOP_USAGE_SLOTS)

        for (p in topBlock) {
            if (usedInsert.add(p.second)) out.add(p)
        }
        for (p in basePairs) {
            if (p.second !in usedInsert) {
                usedInsert.add(p.second)
                out.add(p)
            }
        }
        return out
    }
}
