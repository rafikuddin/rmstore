package com.example.attendance

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

object ChangePasswordDialog {
    private const val MIN_LENGTH = 8   // keep in step with MIN_PASSWORD_LENGTH in Code.gs (the server enforces it too)

    @Volatile private var forcedShowing = false

    /**
     * [forced] = the server will not do anything else until the password is changed (a weak password, or a temporary one from an admin):
     * the pop-up can't be dismissed, and the only other choice is to sign out.
     */
    fun show(activity: AppCompatActivity, session: Session, forced: Boolean = false) {
        if (forced) { if (forcedShowing) return; forcedShowing = true }
        val ctx: Context = activity
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()

        fun field(label: String) = FloatField(ctx, label, password = true)
        val currentPass = field("Current password")
        val newPass = field("New password (at least $MIN_LENGTH characters)")
        val confirmPass = field("Confirm new password")
        val error = TextView(ctx).apply { setTextColor(Pal.c("#DC2626")) }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            if (forced) addView(TextView(ctx).apply {
                text = "Your password is too easy to guess, or an admin gave it to you. Choose a new one to continue."
                setTextColor(Pal.c("#8A5F52"))
            })
            addView(currentPass)
            addView(newPass)
            addView(confirmPass)
            addView(error)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (forced) "Choose a new password" else "Change Password")
            .setView(layout)
            .setCancelable(!forced)
            .setPositiveButton("Change", null)   // set below so we can block auto-dismiss on error
            .setNegativeButton(if (forced) "Sign out" else "Cancel") { _, _ -> if (forced) activity.endSession("You signed out.") }
            .create()
        if (forced) dialog.setOnDismissListener { forcedShowing = false }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cur = currentPass.text.toString()
                val p1 = newPass.text.toString()
                val p2 = confirmPass.text.toString()
                when {
                    cur.isEmpty() -> error.text = "Enter your current password"
                    p1.length < MIN_LENGTH -> error.text = "Password must be at least $MIN_LENGTH characters"
                    p1 != p2 -> error.text = "Passwords don't match"
                    else -> {
                        error.text = "Updating..."
                        val body = JSONObject().put("action", "changePassword")
                            .put("currentPassword", cur).put("newPassword", p1)
                        // The server answers with a fresh token (the old one stops working); api() saves it.
                        activity.api(body) { res, err ->
                            if (res != null) {
                                session.mustChangePassword = false
                                Toast.makeText(ctx, "Password changed", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                if (forced) activity.recreate()          // everything was refused until now: load the screen again
                            } else error.text = err
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
