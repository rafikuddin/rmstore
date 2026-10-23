package com.example.attendance

import android.app.AlertDialog
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * The six risky admin actions (who may use what, active/inactive, notices for everyone, editing someone's profile or photo)
 * need the admin's own password. After one correct password the server hands back a short "proof" that is good for 15 minutes,
 * so a second action does not ask again. The proof lives only in memory: closing the app or signing out forgets it.
 */
object AdminElev {
    private var proof: String = ""
    private var at: Long = 0L
    private const val KEEP_MS = 14 * 60 * 1000L      // the server accepts it for 15 minutes; stop using it a little earlier

    fun fresh(): Boolean = proof.isNotEmpty() && System.currentTimeMillis() - at < KEEP_MS
    fun clear() { proof = ""; at = 0L }
    fun remember(res: JSONObject?) {
        val e = res?.optString("elev").orEmpty()
        if (e.isNotEmpty()) { proof = e; at = System.currentTimeMillis() }
    }
    fun attach(body: JSONObject): JSONObject { if (fresh()) body.put("elev", proof); return body }
}

/**
 * Sends one of the six risky admin actions. When there is no fresh proof it first asks for the admin password in a small dialog.
 * [onCancel] runs when the admin backs out of that dialog (so a caller can re-enable its own button).
 */
fun AppCompatActivity.adminApi(body: JSONObject, onCancel: () -> Unit = {}, done: (JSONObject?, String?) -> Unit) {
    fun send(b: JSONObject) {
        val usedProof = b.has("elev")
        api(b) { res, err ->
            AdminElev.remember(res)
            if (err != null && usedProof && err.contains("password", ignoreCase = true)) AdminElev.clear()
            done(res, err)
        }
    }
    if (AdminElev.fresh()) { send(AdminElev.attach(body)); return }
    val field = FloatField(this, "Your admin password", password = true)
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(field)
    }
    var answered = false
    AlertDialog.Builder(this)
        .setTitle("Confirm with your password")
        .setMessage("This change affects other people, so please type your own password. You will not be asked again for 15 minutes.")
        .setView(col)
        .setPositiveButton("Confirm") { _, _ ->
            val pw = field.text.toString()
            if (pw.isEmpty()) { onCancel(); return@setPositiveButton }
            answered = true
            send(body.put("adminPassword", pw))
        }
        .setNegativeButton("Cancel") { _, _ -> onCancel() }
        .setOnCancelListener { if (!answered) onCancel() }
        .show()
}
