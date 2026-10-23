package com.example.attendance

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which notices this phone has already opened, so the bell on Home can show a red count of new ones.
 * Kept in the same encrypted file as the session, so signing out clears it.
 */
object NoticeState {
    private const val KEY = "seen_notices"
    private fun prefs(ctx: android.content.Context) = SecureStore.prefs(ctx, "session_secure")

    private fun ids(arr: JSONArray?): Set<String> {
        val out = HashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("id"))
        return out
    }

    /** How many of these notices have not been opened yet. */
    fun unread(ctx: android.content.Context, arr: JSONArray?): Int {
        val seen = prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()
        return ids(arr).count { it.isNotEmpty() && !seen.contains(it) }
    }

    /** Marks every notice in [arr] as opened. Only the ones still active are remembered, so the list never grows. */
    fun markSeen(ctx: android.content.Context, arr: JSONArray?) {
        prefs(ctx).edit().putStringSet(KEY, HashSet(ids(arr))).apply()
    }
}

/**
 * The pop-up on Home that shows the current notices every time the person signs in, and each time the app is
 * opened fresh. (It is shown by the app itself, not a phone-wide push message, so it needs no extra service.)
 */
object NoticePopup {
    private var due = true            // true until it has been shown once for this sign-in / app start

    /** Call right after a successful sign-in so the pop-up appears on Home again. */
    fun arm() { due = true }

    fun isDue() = due

    fun show(activity: AppCompatActivity, notices: JSONArray) {
        if (!due || notices.length() == 0 || activity.isFinishing) return
        due = false
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(18))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(Pal.card) }
        }
        root.addView(TextView(activity).apply {
            text = "📢"; textSize = 30f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.c("#FFF4E5")) }
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
        })
        root.addView(TextView(activity).apply {
            text = if (notices.length() == 1) "New notice" else "${notices.length()} notices for you"
            textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A"))
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(6))
        })

        // The newest three; the rest are one tap away on the Notices screen.
        val shown = minOf(3, notices.length())
        for (i in 0 until shown) {
            val o: JSONObject = notices.getJSONObject(i)
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c("#FFFAE3")) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            box.addView(TextView(activity).apply {
                text = o.optString("title"); textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#7C2D12"))
            })
            box.addView(TextView(activity).apply {
                text = o.optString("message"); textSize = 13f; setTextColor(Pal.c("#4A241C"))
                maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
            })
            box.addView(TextView(activity).apply {
                text = "${o.optString("date")} ${o.optString("time")}"; textSize = 11f
                setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(4), 0, 0)
            })
            root.addView(box)
        }
        if (notices.length() > shown) {
            root.addView(TextView(activity).apply {
                text = "+${notices.length() - shown} more"; textSize = 12f; setTextColor(Pal.c("#8A5F52"))
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            })
        }

        Chime.play(activity)          // a new-notice pop-up comes with the app's notification sound
        val dialog = AlertDialog.Builder(activity).setView(root).create()
        root.addView(MaterialButton(activity).apply {
            text = "Got it"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
            setOnClickListener { NoticeState.markSeen(activity, notices); dialog.dismiss(); (activity as? MainActivity)?.refreshBell(notices) }
        })
        root.addView(TextView(activity).apply {
            text = "View all notices"; textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(Pal.c("#8A5F52")); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(2))
            setOnClickListener { dialog.dismiss(); activity.startActivity(Intent(activity, NoticesActivity::class.java)) }
        })
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
}
