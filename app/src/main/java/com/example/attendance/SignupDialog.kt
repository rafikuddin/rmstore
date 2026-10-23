package com.example.attendance

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * "Create account" on the login page. Step 1: name, username, e-mail and a password; the server e-mails a 6-digit code to that address.
 * Step 2: the code. The request then waits for an admin to approve it (Admin Panel > Users > Sign-up requests).
 * [onDone] gets the server's message, which the login page shows.
 */
object SignupDialog {
    private const val MIN_LENGTH = 8   // keep in step with MIN_PASSWORD_LENGTH in Code.gs (the server enforces it too)

    fun show(activity: AppCompatActivity, onDone: (String) -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        fun field(label: String, type: Int) = EditText(activity).apply { hint = label; inputType = type }

        val info = TextView(activity).apply {
            text = "Fill this in. We e-mail a 6-digit code to confirm your address, then an admin approves your account."
            setTextColor(Pal.c("#6C7293"))
        }
        val etName = field("Full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val etUser = field("Username (letters, numbers, . _ -)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val etMail = field("E-mail address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val etPass = field("Password (at least $MIN_LENGTH characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val etPass2 = field("Confirm password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val etCode = field("6-digit code from the e-mail", InputType.TYPE_CLASS_NUMBER).apply { visibility = View.GONE }
        val msg = TextView(activity).apply { setTextColor(Pal.c("#DC2626")); setPadding(0, pad / 2, 0, 0) }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(info); addView(etName); addView(etUser); addView(etMail); addView(etPass); addView(etPass2); addView(etCode); addView(msg)
        }
        var step = 1
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Create account")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Send code", null)          // set below so errors do not close the dialog
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                msg.setTextColor(Pal.c("#DC2626"))
                val u = etUser.text.toString().trim()
                if (step == 1) {
                    val n = etName.text.toString().trim()
                    val m = etMail.text.toString().trim()
                    val p1 = etPass.text.toString()
                    when {
                        n.length < 2 -> msg.text = "Enter your full name"
                        u.length < 3 -> msg.text = "Enter a username (at least 3 characters)"
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(m).matches() -> msg.text = "Enter a valid e-mail address"
                        p1.length < MIN_LENGTH -> msg.text = "Password must be at least $MIN_LENGTH characters"
                        p1 != etPass2.text.toString() -> msg.text = "Passwords don't match"
                        else -> {
                            positive.isEnabled = false
                            msg.setTextColor(Pal.c("#6C7293")); msg.text = "Sending the code…"
                            activity.api(JSONObject().put("action", "signupStart").put("name", n).put("username", u).put("email", m).put("password", p1)) { res, err ->
                                positive.isEnabled = true
                                if (res != null) {
                                    step = 2
                                    info.text = res.optString("message")
                                    listOf(etName, etUser, etMail, etPass, etPass2).forEach { it.isEnabled = false }
                                    etCode.visibility = View.VISIBLE
                                    positive.text = "Confirm"
                                    msg.text = ""
                                } else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
                            }
                        }
                    }
                } else {
                    val code = etCode.text.toString().trim()
                    if (code.length != 6) { msg.text = "Enter the 6-digit code from the e-mail"; return@setOnClickListener }
                    positive.isEnabled = false
                    msg.setTextColor(Pal.c("#6C7293")); msg.text = "Checking the code…"
                    activity.api(JSONObject().put("action", "signupConfirm").put("username", u).put("code", code)) { res, err ->
                        positive.isEnabled = true
                        if (res != null) { dialog.dismiss(); onDone(res.optString("message")) }
                        else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
                    }
                }
            }
        }
        dialog.show()
    }
}
