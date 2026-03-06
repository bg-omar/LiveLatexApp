package com.omariskandarani.livelatexapp.cloud

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

/**
 * Google Sign-In with Drive scope.
 * For Drive API calls you need to obtain an access token (e.g. via a backend
 * using the server auth code, or using GoogleAuthUtil). This helper stores
 * the signed-in account email so the app can show "Connected to Drive".
 */
object GoogleDriveHelper {
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun getSignInClient(context: Context, serverClientId: String?): GoogleSignInClient {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
        if (!serverClientId.isNullOrBlank()) {
            builder.requestServerAuthCode(serverClientId)
        }
        return GoogleSignIn.getClient(context, builder.build())
    }

    /**
     * Call after sign-in activity result. Returns true if sign-in succeeded.
     */
    fun handleSignInResult(context: Context, data: Intent?): Boolean {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account = try {
            task.getResult(com.google.android.gms.common.api.ApiException::class.java)
        } catch (e: Exception) {
            Toast.makeText(context, "Google sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            return false
        }
        account?.email?.let { email ->
            CloudPrefs.setGoogleAccountEmail(context, email)
            Toast.makeText(context, "Connected to Google Drive", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    fun signOut(context: Context, client: GoogleSignInClient) {
        client.signOut().addOnCompleteListener {
            CloudPrefs.disconnectGoogleDrive(context)
        }
    }

    fun isSignedIn(context: Context): Boolean =
        GoogleSignIn.getLastSignedInAccount(context) != null && CloudPrefs.isGoogleDriveConnected(context)
}
