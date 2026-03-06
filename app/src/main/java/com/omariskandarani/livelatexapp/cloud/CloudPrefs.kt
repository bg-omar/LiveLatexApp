package com.omariskandarani.livelatexapp.cloud

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for cloud connection tokens and state.
 * Used by GitHub OAuth and Google Drive.
 */
object CloudPrefs {
    private const val PREFS_NAME = "livelatex_cloud_prefs"
    private const val KEY_GITHUB_ACCESS_TOKEN = "github_access_token"
    private const val KEY_GOOGLE_ACCOUNT_EMAIL = "google_account_email"

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun getGitHubAccessToken(context: Context): String? =
        prefs(context).getString(KEY_GITHUB_ACCESS_TOKEN, null)

    fun setGitHubAccessToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_GITHUB_ACCESS_TOKEN, token).apply()
    }

    fun isGitHubConnected(context: Context): Boolean =
        !getGitHubAccessToken(context).isNullOrBlank()

    fun getGoogleAccountEmail(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)

    fun setGoogleAccountEmail(context: Context, email: String?) {
        prefs(context).edit().putString(KEY_GOOGLE_ACCOUNT_EMAIL, email).apply()
    }

    fun isGoogleDriveConnected(context: Context): Boolean =
        !getGoogleAccountEmail(context).isNullOrBlank()

    fun disconnectGitHub(context: Context) = setGitHubAccessToken(context, null)
    fun disconnectGoogleDrive(context: Context) = setGoogleAccountEmail(context, null)
}
