package com.omariskandarani.livelatexapp

import android.content.Context

/**
 * Stored defaults for template placeholders: author, affiliate, address, email, orcid.
 * Applied when creating a new document from a template.
 */
object TemplateDefaultsPrefs {
    private const val PREF_NAME = "LiveLatexApp"
    private const val KEY_AUTHOR = "template_author"
    private const val KEY_AFFILIATE = "template_affiliate"
    private const val KEY_ADDRESS = "template_address"
    private const val KEY_EMAIL = "template_email"
    private const val KEY_ORCID = "template_orcid"

    data class TemplateDefaults(
        val author: String,
        val affiliate: String,
        val address: String,
        val email: String,
        val orcid: String
    ) {
        fun isEmpty(): Boolean =
            author.isBlank() && affiliate.isBlank() && address.isBlank() && email.isBlank() && orcid.isBlank()
    }

    fun get(context: Context): TemplateDefaults {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return TemplateDefaults(
            author = prefs.getString(KEY_AUTHOR, "") ?: "",
            affiliate = prefs.getString(KEY_AFFILIATE, "") ?: "",
            address = prefs.getString(KEY_ADDRESS, "") ?: "",
            email = prefs.getString(KEY_EMAIL, "") ?: "",
            orcid = prefs.getString(KEY_ORCID, "") ?: ""
        )
    }

    fun save(context: Context, defaults: TemplateDefaults) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTHOR, defaults.author)
            .putString(KEY_AFFILIATE, defaults.affiliate)
            .putString(KEY_ADDRESS, defaults.address)
            .putString(KEY_EMAIL, defaults.email)
            .putString(KEY_ORCID, defaults.orcid)
            .apply()
    }
}
