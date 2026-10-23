package com.example.attendance

import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Shown instead of every other screen while a COMPULSORY update is waiting (see AppUpdate.enforce). Back does nothing.
 * It goes away by itself once the new build is installed (the app restarts), or when the server says the update is no longer
 * compulsory ("Check again", also done every time this screen opens).
 */
class ForceUpdateActivity : AppCompatActivity() {
    private var info: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_force_update)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { /* no way out except updating */ } })
        findViewById<android.view.View>(R.id.btnFuInstall).setOnClickListener { info?.let { AppUpdate.start(this, it) } }
        findViewById<android.view.View>(R.id.tvFuCheck).setOnClickListener { check(true) }
        show(AppUpdate.required(this))
    }

    override fun onResume() {
        super.onResume()
        if (AppUpdate.required(this) == null) { finish(); return }
        check(false)
    }

    private fun show(i: JSONObject?) {
        if (i == null) { finish(); return }
        info = i
        val mb = i.optLong("size") / 1048576.0
        findViewById<TextView>(R.id.tvFuVersion).text = "Version ${i.optString("versionName")} is ready  (${"%.1f".format(mb)} MB)"
        findViewById<TextView>(R.id.tvFuInstalled).text = "You have ${AppUpdate.installedName(this)}"
        val notes = i.optString("notes")
        findViewById<TextView>(R.id.tvFuNotes).text = notes
        findViewById<TextView>(R.id.tvFuNotes).visibility = if (notes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Asks the server whether the update is still compulsory (it may have been removed or made optional meanwhile). */
    private fun check(manual: Boolean) {
        val msg = findViewById<TextView>(R.id.tvFuMsg)
        if (manual) msg.text = "Checking…"
        api(JSONObject().put("action", "appInfo")) { res, err ->
            if (res != null) {
                AppUpdate.remember(this, res.optJSONObject("update"))
                val still = AppUpdate.required(this)
                if (still == null) { finish(); return@api }
                show(still)
                msg.text = if (manual) "Still required. Please install the update." else ""
            } else if (manual) msg.text = err ?: "Could not check. Try again."
        }
    }
}
