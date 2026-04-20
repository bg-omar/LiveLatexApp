package com.omariskandarani.livelatexapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined templates and hidden built-in templates. Effective list =
 * [built-ins not hidden] + [custom templates].
 */
object UserTemplatesPrefs {
    private const val PREF_NAME = "LiveLatexApp"
    private const val KEY_HIDDEN_BUILTIN_IDS = "user_templates_hidden_builtin_ids"
    private const val KEY_CUSTOM_JSON = "user_templates_custom_json_v1"

    private const val J_ID = "id"
    private const val J_NAME = "name"
    private const val J_DESC = "description"
    private const val J_CONTENT = "content"

    fun getHiddenBuiltinIds(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HIDDEN_BUILTIN_IDS, null) ?: return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun setHiddenBuiltinIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HIDDEN_BUILTIN_IDS, ids.joinToString(","))
            .apply()
    }

    fun hideBuiltin(context: Context, builtinId: String) {
        val next = getHiddenBuiltinIds(context) + builtinId
        setHiddenBuiltinIds(context, next)
    }

    /** Show one built-in preset in the list again (inverse of [hideBuiltin]). */
    fun unhideBuiltin(context: Context, builtinId: String) {
        val next = getHiddenBuiltinIds(context) - builtinId
        setHiddenBuiltinIds(context, next)
    }

    fun clearHiddenBuiltins(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HIDDEN_BUILTIN_IDS)
            .apply()
    }

    fun getCustomTemplates(context: Context): List<LatexTemplates.Template> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString(J_ID)
                    if (id.isBlank()) continue
                    add(
                        LatexTemplates.Template(
                            id = id,
                            name = o.optString(J_NAME),
                            description = o.optString(J_DESC),
                            content = o.optString(J_CONTENT),
                            isBuiltin = false
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustomTemplates(context: Context, list: List<LatexTemplates.Template>) {
        val arr = JSONArray()
        for (t in list) {
            if (t.isBuiltin) continue
            arr.put(
                JSONObject().apply {
                    put(J_ID, t.id)
                    put(J_NAME, t.name)
                    put(J_DESC, t.description)
                    put(J_CONTENT, t.content)
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_JSON, arr.toString())
            .apply()
    }

    fun upsertCustomTemplate(context: Context, template: LatexTemplates.Template) {
        val list = getCustomTemplates(context).toMutableList()
        val idx = list.indexOfFirst { it.id == template.id }
        val entry = template.copy(isBuiltin = false)
        if (idx >= 0) list[idx] = entry else list.add(entry)
        saveCustomTemplates(context, list)
    }

    fun deleteCustomTemplate(context: Context, id: String) {
        val list = getCustomTemplates(context).filter { it.id != id }
        saveCustomTemplates(context, list)
    }

    fun getEffectiveTemplates(context: Context): List<LatexTemplates.Template> {
        val hidden = getHiddenBuiltinIds(context)
        val builtins = LatexTemplates.BUILTIN_TEMPLATES.filter { it.id !in hidden }
        return builtins + getCustomTemplates(context)
    }
}
