package com.example.attendance

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/**
 * The notices this person may see, newest first: admin notices (for everyone) and team notices from the manager
 * above them. A manager also gets a button here to post a notice for their own team.
 */
class NoticesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var session: Session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Notices") { load() }
        setupBottomNav(null)
        enforceAccess("notices")
        list = findViewById(R.id.noticeList)
        load()
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Pal.c("#6B352A"))
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun load() {
        list.removeAllViews()
        list.addView(note("Loading…"))
        api(JSONObject().put("action", "notices")) { res, err ->
            list.removeAllViews()
            if (res == null) { list.addView(note(err ?: "Could not load notices.")); return@api }
            val arr = res.optJSONArray("notices")
            NoticeState.markSeen(this, arr)          // opening the list clears the bell's red count
            if (session.isManager || res.optBoolean("canPost")) list.addView(postCard())
            if (arr == null || arr.length() == 0) { list.addView(note("No notices right now.")); return@api }
            for (i in 0 until arr.length()) list.addView(card(arr.getJSONObject(i)))
        }
    }

    private fun card(o: JSONObject): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(TextView(this).apply {
            text = "📢  ${o.optString("title")}"; textSize = 16f
            setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16"))
        })
        col.addView(TextView(this).apply {
            text = o.optString("message"); textSize = 14f; setTextColor(Pal.c("#4A241C"))
            setPadding(0, dp(6), 0, 0)
        })
        val team = o.optBoolean("team")
        col.addView(TextView(this).apply {
            text = "${o.optString("date")} ${o.optString("time")} · ${o.optString("postedBy")}" + (if (team) " · 👥 team notice" else "")
            textSize = 12f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(8), 0, 0)
        })
        if (o.optBoolean("mine")) {
            col.addView(TextView(this).apply {
                text = "🗑  Remove this notice"; textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(Pal.c("#DC2626")); setPadding(0, dp(10), 0, dp(2))
                isClickable = true; isFocusable = true
                setOnClickListener { confirmRemove(o.optString("id"), o.optString("title")) }
            })
        }
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(col)
        }
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    private fun postCard(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        col.addView(TextView(this).apply {
            text = "👥  Notice for my team"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16"))
        })
        col.addView(TextView(this).apply {
            text = "Only you and the people below you will see it."; textSize = 13f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(4), 0, dp(8))
        })
        col.addView(MaterialButton(this).apply { text = "✍️  Post a notice"; setOnClickListener { showPostDialog() } })
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.c("#FFF6C4"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(col)
        }
    }

    private fun showPostDialog() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply { hint = "Title"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        val message = EditText(this).apply {
            hint = "Message"; minLines = 3; gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(600))
        }
        val days = EditText(this).apply { hint = "Show for how many days (default 7)"; inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(2)) }
        val cbSms = android.widget.CheckBox(this).apply { text = "📱  Also send as an SMS to my team"; textSize = 14f }
        form.addView(title); form.addView(message); form.addView(days); form.addView(cbSms)
        val dialog = AlertDialog.Builder(this).setTitle("Notice for my team").setView(form)
            .setPositiveButton("Post", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                val m = message.text.toString().trim()
                if (t.length < 2) { title.error = "Please enter a title"; return@setOnClickListener }
                if (m.length < 2) { message.error = "Please enter the message"; return@setOnClickListener }
                val body = JSONObject().put("action", "postNotice").put("title", t).put("message", m)
                days.text.toString().toIntOrNull()?.let { body.put("days", it) }
                val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                fun post(sms: Boolean) {
                    okBtn.isEnabled = false
                    if (sms) body.put("sms", true)
                    api(body) { res, err ->
                        if (res != null) {
                            dialog.dismiss()
                            ResponseCache.remove(this, ResponseCache.homeKey(session.username))
                            val s = SmsUi.summary(res.optJSONObject("sms"))
                            toast("Posted for your team" + (if (s.isNotEmpty()) "\n$s" else ""))
                            load()
                        } else {
                            okBtn.isEnabled = true
                            toast(err ?: "Could not post the notice")
                        }
                    }
                }
                if (!cbSms.isChecked) { post(false); return@setOnClickListener }
                okBtn.isEnabled = false
                api(JSONObject().put("action", "smsPreview").put("scope", "team")) { pv, perr ->
                    okBtn.isEnabled = true
                    if (pv == null) { toast(perr ?: "Could not check SMS"); return@api }
                    if (!pv.optBoolean("configured") || pv.optInt("people") == 0) {
                        AlertDialog.Builder(this).setTitle("SMS cannot be sent")
                            .setMessage(if (!pv.optBoolean("configured")) "SMS is not set up yet. Ask your admin to set it up (Admin Panel > SMS). Post the notice without SMS?" else "Nobody in your team has a phone number saved. Post the notice without SMS?")
                            .setPositiveButton("Post without SMS") { _, _ -> post(false) }.setNegativeButton("Cancel", null).show()
                    } else {
                        AlertDialog.Builder(this).setTitle("Send SMS?").setMessage(SmsUi.confirmText(pv, "your team"))
                            .setPositiveButton("Post + send SMS") { _, _ -> post(true) }
                            .setNeutralButton("Post without SMS") { _, _ -> post(false) }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmRemove(id: String, title: String) {
        AlertDialog.Builder(this).setTitle("Remove this notice?")
            .setMessage("“$title” will disappear for your team.")
            .setPositiveButton("Remove") { _, _ ->
                api(JSONObject().put("action", "deleteNotice").put("id", id)) { res, err ->
                    if (res != null) {
                        ResponseCache.remove(this, ResponseCache.homeKey(session.username))
                        load()
                    } else toast(err ?: "Could not remove it")
                }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
