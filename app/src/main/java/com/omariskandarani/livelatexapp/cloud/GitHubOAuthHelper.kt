package com.omariskandarani.livelatexapp.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHub OAuth 2.0 flow.
 * Redirect URI must be: livelatex://github-callback
 * Configure your GitHub OAuth App at https://github.com/settings/developers
 */
object GitHubOAuthHelper {
    private const val REDIRECT_URI = "livelatex://github-callback"
    private const val AUTH_URL = "https://github.com/login/oauth/authorize"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val SCOPE = "repo,read:user"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * clientId and clientSecret: from your GitHub OAuth App.
     * For production, do the code exchange on a backend and pass only clientId in the app.
     */
    fun launchSignIn(context: Context, clientId: String, clientSecret: String?) {
        if (clientId.isBlank()) {
            Toast.makeText(context, "Configure GitHub Client ID in BuildConfig or settings", Toast.LENGTH_LONG).show()
            return
        }
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", "livelatex_github")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /**
     * Call from MainActivity when receiving livelatex://github-callback?code=...
     */
    suspend fun handleCallback(context: Context, uri: Uri, clientId: String, clientSecret: String?): Boolean {
        val code = uri.getQueryParameter("code") ?: return false
        val state = uri.getQueryParameter("state")
        if (state != "livelatex_github") return false

        if (clientSecret.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Add GitHub Client Secret (e.g. in BuildConfig) to complete sign-in", Toast.LENGTH_LONG).show()
            }
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .build()
                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .post(body)
                    .addHeader("Accept", "application/json")
                    .build()
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false
                val json = response.body?.string() ?: return@withContext false
                val token = parseAccessToken(json)
                if (token != null) {
                    CloudPrefs.setGitHubAccessToken(context, token)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Connected to GitHub", Toast.LENGTH_SHORT).show()
                    }
                    true
                } else false
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "GitHub sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                false
            }
        }
    }

    private fun parseAccessToken(json: String): String? {
        val regex = """"access_token"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }
}
