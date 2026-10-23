package com.example.attendance

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * "Forgot password?" on the login page, in three steps inside one dialog:
 *  1. the Employee ID; the server e-mails a 6-digit code to the address saved in My Profile.
 *  2. the code, with the same padlock-and-boxes animation as sign-up ([OtpView]); the sixth digit is checked by the server (`resetCheck`:
 *     a wrong code costs a try, nothing is changed, the code is not used up).
 *  3. the new password; the server checks the code once more together with the password (`resetConfirm`) and sets it. The code works for
 *     10 minutes, only once, 5 tries.
 * [onDone] gets the Employee ID after the password was changed, so the login page can fill it in.
 */
object ForgotPasswordDialog {
    private const val MIN_LENGTH = 8   // keep in step with MIN_PASSWORD_LENGTH in Code.gs (the server enforces it too)

    fun show(activity: AppCompatActivity, presetUser: String, onDone: (String) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        fun field(label: String, type: Int) = FloatField(activity, label, type, password = (type and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)

        val info = TextView(activity).apply {
            text = "Enter your Employee ID. We will e-mail a 6-digit code to the e-mail address saved in your My Profile."
            setTextColor(Pal.c("#8A5F52"))
        }
        val etUser = field("Employee ID", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply { setText(presetUser) }
        val otp = OtpView(activity).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // the real input for the code: invisible, it only collects the digits and shows the keyboard; OtpView draws them
        val etCode = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(OtpView.LENGTH))
            background = null; alpha = 0f; isCursorVisible = false; visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt().coerceAtLeast(1), (1 * d).toInt().coerceAtLeast(1))
        }
        val etNew = field("New password (at least $MIN_LENGTH characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { visibility = View.GONE }
        val etNew2 = field("Confirm new password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { visibility = View.GONE }
        val msg = TextView(activity).apply { setTextColor(Pal.c("#DC2626")); setPadding(0, pad / 2, 0, 0) }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(info); addView(etUser); addView(otp); addView(etCode); addView(etNew); addView(etNew2); addView(msg)
        }
        var step = 1
        var busy = false          // a code is being checked / the "verified" scene is playing
        var code = ""
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Reset password")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Send code", null)          // set below so errors do not close the dialog
            .setNeutralButton("Send a new code", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun showKeyboard() {
            etCode.requestFocus()
            etCode.post { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(etCode, InputMethodManager.SHOW_IMPLICIT) }
        }
        fun hideKeyboard() {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etCode.windowToken, 0)
        }

        fun goStep(s: Int, text: String) {
            step = s
            info.text = text
            etUser.isEnabled = s == 1
            info.visibility = if (s == 2) View.GONE else View.VISIBLE        // the animation has its own title and sentence
            otp.visibility = if (s == 2) View.VISIBLE else View.GONE
            etCode.visibility = if (s == 2) View.VISIBLE else View.GONE
            etNew.visibility = if (s == 3) View.VISIBLE else View.GONE
            etNew2.visibility = if (s == 3) View.VISIBLE else View.GONE
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.visibility = if (s == 2) View.GONE else View.VISIBLE
            positive.text = if (s == 3) "Change password" else "Send code"
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = if (s == 2) View.VISIBLE else View.GONE
            msg.text = ""
        }

        otp.onTap = { showKeyboard() }
        etCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!busy) { msg.text = ""; otp.setDigits(s?.toString() ?: "") } }
        })
        otp.onComplete = { digits ->
            busy = true; code = digits
            etCode.isEnabled = false
            hideKeyboard()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
            activity.api(JSONObject().put("action", "resetCheck").put("username", etUser.text.toString().trim()).put("code", digits)) { res, err ->
                if (res != null) otp.success() else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err; otp.fail() }
            }
        }
        otp.onFinished = {
            busy = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
            goStep(3, "Code accepted. Now choose a new password (at least $MIN_LENGTH characters).")
            etNew.requestFocus()
        }
        otp.onRetry = { busy = false; etCode.isEnabled = true; etCode.setText(""); dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true; showKeyboard() }

        fun sendCode(btn: android.widget.Button) {
            val u = etUser.text.toString().trim()
            if (u.isEmpty()) { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Enter your Employee ID"; return }
            btn.isEnabled = false
            msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Sending…"
            activity.api(JSONObject().put("action", "resetRequest").put("username", u)) { res, err ->
                btn.isEnabled = true
                if (res != null) {
                    otp.reset(); etCode.isEnabled = true; etCode.setText("")
                    otp.setSubtitle("Enter the 6-digit code sent to the e-mail saved in your My Profile. It works for 10 minutes.")
                    goStep(2, res.optString("message"))
                    otp.postDelayed({ if (step == 2) showKeyboard() }, 250)
                } else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
            }
        }

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutral.visibility = View.GONE
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { if (!busy) dialog.dismiss() }
            neutral.setOnClickListener { if (!busy) sendCode(neutral) }
            positive.setOnClickListener {
                if (step == 1) { sendCode(positive); return@setOnClickListener }
                val p1 = etNew.text.toString()
                val p2 = etNew2.text.toString()
                msg.setTextColor(Pal.c("#DC2626"))
                when {
                    p1.length < MIN_LENGTH -> msg.text = "Password must be at least $MIN_LENGTH characters"
                    p1 != p2 -> msg.text = "Passwords don't match"
                    else -> {
                        positive.isEnabled = false
                        msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Changing the password…"
                        val u = etUser.text.toString().trim()
                        activity.api(JSONObject().put("action", "resetConfirm").put("username", u).put("code", code).put("newPassword", p1)) { res, err ->
                            positive.isEnabled = true
                            if (res != null) { dialog.dismiss(); onDone(u) }
                            else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
                        }
                    }
                }
            }
        }
        dialog.setCancelable(false)
        dialog.show()
    }
}
