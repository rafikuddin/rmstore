package com.example.attendance

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Config {
    // Paste the Web app URL you get after deploying the Google Apps Script (Code.gs)
    const val SCRIPT_URL = "https://rmstore.samuda-sales.workers.dev"
}

/**
 * Who is signed in. The password is NEVER kept on the phone: after login the server hands back a signed
 * token, and that token (not the password) goes along with every request. All of it is stored encrypted.
 */
class Session(ctx: Context) {
    private val p = SecureStore.prefs(ctx, "session_secure")
    var username: String
        get() = p.getString("u", "") ?: ""
        set(v) { p.edit().putString("u", v).apply() }
    var token: String
        get() = p.getString("t", "") ?: ""
        set(v) { p.edit().putString("t", v).apply() }
    var name: String
        get() = p.getString("n", "") ?: ""
        set(v) { p.edit().putString("n", v).apply() }
    var isManager: Boolean
        get() = p.getBoolean("m", false)
        set(v) { p.edit().putBoolean("m", v).apply() }
    /** Set at login when the password is shorter than the server's minimum; Home then asks for a new one. */
    var mustChangePassword: Boolean
        get() = p.getBoolean("mc", false)
        set(v) { p.edit().putBoolean("mc", v).apply() }
    val loggedIn: Boolean get() = username.isNotEmpty() && token.isNotEmpty()
    fun clear() { p.edit().clear().apply() }
}

/** Calls the Google Apps Script backend on a background thread; done() runs on the UI thread. */
fun AppCompatActivity.api(body: JSONObject, done: (JSONObject?, String?) -> Unit) {
    val session = Session(this)
    val token = session.token
    Thread {
        var result: JSONObject? = null
        var error: String? = null
        var sessionEnded = false
        var newToken = ""
        try {
            val json = postJsonRaw(body, token)
            newToken = json.optString("token", "")          // login, password change, or a quiet renewal
            if (json.optBoolean("ok")) result = json
            else {
                error = json.optString("error", "Request failed")
                sessionEnded = json.optBoolean("auth")        // the server no longer accepts this sign-in
            }
        } catch (e: Exception) {
            error = "Network or server error. Check your internet connection."
        }
        runOnUiThread {
            if (newToken.isNotEmpty()) session.token = newToken
            if (sessionEnded) { endSession(); return@runOnUiThread }
            done(result, error)
        }
    }.start()
}

/** The sign-in is no longer valid (expired, password changed elsewhere, or signed out): wipe the phone and show Login. */
fun AppCompatActivity.endSession(message: String = "Your session ended. Please sign in again.") {
    Wipe.signOutLocally(this)
    startActivity(
        Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(LoginActivity.EXTRA_MESSAGE, message)
    )
    finish()
}

/** One blocking call to the backend (background thread only). The sign-in [token] is added when there is one. */
internal fun postJsonRaw(body: JSONObject, token: String): JSONObject {
    if (token.isNotEmpty()) body.put("token", token)
    val c = URL(Config.SCRIPT_URL).openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.connectTimeout = 20000
    c.readTimeout = 60000
    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
    c.outputStream.use { it.write(body.toString().toByteArray()) }
    val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
    return JSONObject(stream.bufferedReader().readText())
}

/** Same call for code that has no Activity (background photo downloads). Keeps a renewed token if the server sent one. */
fun postJson(ctx: Context, body: JSONObject): JSONObject {
    val session = Session(ctx)
    val json = postJsonRaw(body, session.token)
    val renewed = json.optString("token", "")
    if (renewed.isNotEmpty()) session.token = renewed
    return json
}
