package com.omariskandarani.livelatexapp

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows error messages in a modal dialog with scrollable text so long messages
 * are fully readable. Falls back to Toast when context is not an Activity.
 */
object ErrorDialog {

    fun show(context: Context, message: String, title: String? = null) {
        if (context is Activity) {
            context.runOnUiThread { showDialog(context, title ?: context.getString(R.string.error), message) }
        } else {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDialog(activity: Activity, title: String, message: String) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_error_message, null)
        val textView = view.findViewById<android.widget.TextView>(R.id.error_message_text)
        textView.text = message
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }
}
