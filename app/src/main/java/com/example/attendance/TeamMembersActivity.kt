package com.example.attendance

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/**
 * Team members: for a line manager an admin has allowed. Lists ONLY the people below them (any depth) with a Profile and a
 * Calendar button, both read only. The server checks the right and that every person is really below the manager.
 */
class TeamMembersActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private lateinit var etSearch: SeekField
    private val members = ArrayList<JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Team members") { load() }
        setupBottomNav(null)
        if (!Access.canViewTeam(this)) { android.widget.Toast.makeText(this, "Ask your admin to give you this right.", android.widget.Toast.LENGTH_LONG).show(); finish(); return }
        content = findViewById(R.id.noticeList)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        load()
    }

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun load() {
        content.removeAllViews()
        content.addView(label("Loading…", 14f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        api(JSONObject().put("action", "teamMembers")) { res, err ->
            content.removeAllViews()
            if (res == null) { content.addView(label(err ?: "Could not load.", 14f, "#DC2626").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return@api }
            members.clear()
            val arr = res.optJSONArray("members")
            if (arr != null) for (i in 0 until arr.length()) members.add(arr.getJSONObject(i))
            if (members.isEmpty()) {
                content.addView(label("Nobody reports to you yet.", 15f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
                return@api
            }
            etSearch = SeekField(this, "Search by name or Employee ID").also {
                it.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(e: Editable?) { fill(e?.toString()?.trim().orEmpty()) }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            content.addView(etSearch)
            content.addView(LinearLayout(this).apply { tag = "rows"; orientation = LinearLayout.VERTICAL })
            fill("")
        }
    }

    private fun rowsBox(): LinearLayout = content.findViewWithTag("rows")

    private fun fill(q: String) {
        val box = rowsBox()
        box.removeAllViews()
        val shown = members.filter { q.isEmpty() || it.optString("name").contains(q, true) || it.optString("username").contains(q, true) }
        box.addView(label("${shown.size} ${if (shown.size == 1) "person" else "people"} below you", 12f, "#8A5F52", true).apply { setPadding(dp(6), dp(10), dp(6), dp(6)) })
        shown.forEach { box.addView(card(it)) }
    }

    private fun card(o: JSONObject): View {
        val username = o.optString("username"); val name = o.optString("name")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        col.addView(label(name, 16f, "#3E1D16", true))
        col.addView(label("ID: $username" + (if (o.optBoolean("isManager")) "  ·  manager" else "") + (if (o.optString("manager").isNotBlank()) "  ·  reports to ${o.optString("manager")}" else ""), 12f, "#8A5F52"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "👤 Profile"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                startActivity(Intent(this@TeamMembersActivity, ProfileActivity::class.java)
                    .putExtra(ProfileActivity.EXTRA_USER, username).putExtra(ProfileActivity.EXTRA_NAME, name).putExtra(ProfileActivity.EXTRA_VIEW_ONLY, true))
            }
        })
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📅 Calendar"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
            setOnClickListener {
                startActivity(Intent(this@TeamMembersActivity, CalendarActivity::class.java)
                    .putExtra(CalendarActivity.EXTRA_USER, username).putExtra(CalendarActivity.EXTRA_NAME, name).putExtra(CalendarActivity.EXTRA_VIEW_ONLY, true))
            }
        })
        col.addView(row)
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(col)
        }
    }
}
