package com.example.attendance

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MESSAGE = "msg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = Session(this)
        if (session.loggedIn) { openMain(); return }
        setContentView(R.layout.activity_login)

        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val btn = findViewById<Button>(R.id.btnLogin)
        val tvErr = findViewById<TextView>(R.id.tvError)
        val cbRemember = findViewById<CheckBox>(R.id.cbRemember)
        findViewById<TextView>(R.id.tvVersion).text = "Version " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" })
        intent.getStringExtra(EXTRA_MESSAGE)?.let { tvErr.text = it }

        findViewById<TextView>(R.id.tvSignup).setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
        findViewById<TextView>(R.id.tvForgot).setOnClickListener {
            ForgotPasswordDialog.show(this, etUser.text.toString().trim()) { u ->
                etUser.setText(u); etPass.setText("")
                tvErr.setTextColor(Pal.c("#166534")); tvErr.text = "Your password was changed. Sign in with the new password."
            }
        }
        btn.setOnClickListener {
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) { tvErr.text = "Enter Employee ID and password"; return@setOnClickListener }
            btn.isEnabled = false
            tvErr.setTextColor(Pal.c("#DC2626"))
            tvErr.text = "Signing in..."
            // The password is sent only here, once. The server answers with a token, which api() saves; the password is not kept.
            api(JSONObject().put("action", "login").put("username", u).put("password", p).put("remember", cbRemember.isChecked)) { res, err ->
                btn.isEnabled = true
                etPass.setText("")
                if (res != null) {
                    session.username = u
                    session.name = res.optString("name", u)
                    Access.remember(this, res)
                    session.isManager = res.optBoolean("isManager")
                    AdminFlag.set(this, res.optBoolean("isAdmin"))
                    session.mustChangePassword = res.optBoolean("weakPassword")
                    NoticePopup.arm()                      // show the notices pop-up on Home after every sign-in
                    openMain()
                } else tvErr.text = err
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
