package com.example.attendance

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

class TeamActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OF = "of"
        const val EXTRA_OF_NAME = "ofName"
    }

    private lateinit var session: Session
    private lateinit var countsBox: LinearLayout
    private lateinit var listTeam: LinearLayout
    private lateinit var listLeaves: LinearLayout
    private var of: String? = null          // null = viewing my own team
    private var canDecide = false           // true for anyone below you, at any depth (server re-checks every decision)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_team)
        of = intent.getStringExtra(EXTRA_OF)
        val ofName = intent.getStringExtra(EXTRA_OF_NAME)
        setupHeader(if (ofName != null) "$ofName's Team" else "My Team") { loadAll() }
        setupBottomNav(Tab.TEAM)
        enforceAccess("team")
        session = Session(this)
        countsBox = findViewById(R.id.countsBox)
        listTeam = findViewById(R.id.listTeam)
        listLeaves = findViewById(R.id.listLeaves)
        loadAll()
    }

    private fun body(): JSONObject {
        val b = JSONObject().put("username", session.username)
        of?.let { b.put("of", it) }
        return b
    }

    private fun openTeamOf(username: String, name: String) {
        startActivity(Intent(this, TeamActivity::class.java)
            .putExtra(EXTRA_OF, username).putExtra(EXTRA_OF_NAME, name))
    }

    // One request for the whole screen — status, roll-ups, counts and leave requests together.
    // Opens instantly from the last saved copy (if it is from the last 30 minutes), then refreshes.
    private fun loadAll() {
        val showedSaved = apiCached(
            cacheKey = ResponseCache.teamKey(session.username, of),
            body = body().put("action", "team"),
            maxAgeMs = HOUR_MS / 2,
            busy = findViewById<View>(R.id.btnRefresh),
            onData = { res -> render(res) },
            onError = { err, hadSaved ->
                if (!hadSaved) {
                    listTeam.removeAllViews(); listLeaves.removeAllViews()
                    listTeam.addView(note(err ?: "Failed to load")); listLeaves.addView(note(err ?: "Failed to load"))
                }
            }
        )
        if (!showedSaved) {
            listTeam.removeAllViews(); listTeam.addView(note("Loading..."))
            listLeaves.removeAllViews(); listLeaves.addView(note("Loading..."))
        }
    }

    private fun render(res: JSONObject) {
        listTeam.removeAllViews(); listLeaves.removeAllViews()
        renderCounts(res.getJSONObject("counts"))
        val reports = res.getJSONArray("reports")
        if (reports.length() == 0) listTeam.addView(note("No one reports here yet."))
        else for (i in 0 until reports.length()) listTeam.addView(teamCard(reports.getJSONObject(i)))

        canDecide = res.optBoolean("canDecide", res.optBoolean("isOwn"))
        val leaves = res.getJSONArray("leaves")
        if (leaves.length() == 0) listLeaves.addView(note("No leave requests here."))
        else for (o in pendingFirst(leaves)) listLeaves.addView(leaveCard(o))
    }

    // Shows today's selfie (loaded privately through the Apps Script photo proxy) when present,
    // otherwise a plain initials circle — so every row always has a consistent-looking avatar.
    private fun avatarView(o: JSONObject): View {
        val size = dp(46)
        val card = MaterialCardView(this).apply {
            radius = (size / 2).toFloat(); cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
        }
        val selfie = o.optString("selfie")
        if (o.optString("status") == "Present" && selfie.isNotEmpty()) {
            card.setCardBackgroundColor(Pal.c("#EBDDA0"))
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            card.clipToOutline = true
            card.addView(img)
            PhotoLoader.load(this, session, selfie, img)
        } else {
            card.setCardBackgroundColor(Pal.c("#F0E3A6"))
            card.addView(TextView(this).apply {
                text = o.optString("name").trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                gravity = Gravity.CENTER; setTextColor(Pal.c("#6B352A")); setTypeface(null, Typeface.BOLD); textSize = 16f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        return card
    }

    /** A title line, then six equal coloured tiles in two rows of three. Nothing scrolls sideways, so no label is ever cut off. */
    private fun renderCounts(c: JSONObject) {
        countsBox.removeAllViews()
        // label, number, text colour, tile colour, symbol
        val items = listOf(
            listOf("Present", c.optInt("present"), "#15803D", "#E6F6EC", "✅"),
            listOf("Late", c.optInt("late"), "#B45309", "#FEF3C7", "⏰"),
            listOf("On Leave", c.optInt("onLeave"), "#C2410C", "#FFEDD5", "🌴"),
            listOf("Off", c.optInt("off"), "#6F4A3F", "#F8EFC4", "🗓"),
            listOf("Absent", c.optInt("absent"), "#B91C1C", "#FDE8E8", "❌"),
            listOf("Pending", c.optInt("pending"), "#6B352A", "#FFF1A6", "⏳")
        )
        val total = c.optInt("total", items.sumOf { v -> v[1] as Int })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "TEAM TODAY"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A")); letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(pill("$total member" + (if (total == 1) "" else "s"), "#6B352A", "#F0E3A6"))
        countsBox.addView(head)
        for (rowStart in listOf(0, 3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            for (k in 0 until 3) {
                val item = items[rowStart + k]
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(4), dp(12), dp(4), dp(10))
                    background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c(item[3] as String)) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (k > 0) marginStart = dp(5); if (k < 2) marginEnd = dp(5)
                    }
                }
                tile.addView(TextView(this).apply {
                    text = (item[1] as Int).toString(); textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(item[2] as String))
                })
                tile.addView(TextView(this).apply {
                    text = (item[4] as String) + " " + (item[0] as String); textSize = 12f; setTypeface(null, Typeface.BOLD)
                    setTextColor(Pal.c(item[2] as String)); maxLines = 1
                })
                row.addView(tile)
            }
            countsBox.addView(row)
        }
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c("#6B352A"))
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(if (fg == "#FFFFFF") Color.parseColor(bg) else Pal.c(bg)) }
    }

    private fun cardWrap(inner: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(inner)
    }

    private fun teamCard(o: JSONObject): View {
        val status = o.optString("status")
        val late = o.optBoolean("late")
        val pillLabel = if (status == "Present" && late) "Late" else status
        val (fg, bg) = when {
            status == "Present" && late -> "#9A3412" to "#FFEDD5"
            status == "Present" -> "#166534" to "#DCFCE7"
            status == "On Leave" -> "#B45309" to "#FEF3C7"
            status == "Off" -> "#4A241C" to "#EBDDA0"
            status == "Absent" -> "#FFFFFF" to "#B91C1C"
            else -> "#B91C1C" to "#FEE2E2"
        }
        val isManagerRow = o.optBoolean("isManager")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(avatarView(o))
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(this).apply { text = o.optString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
        val detail = o.optString("detail")
        nameCol.addView(TextView(this).apply {
            text = "Own attendance: ${o.optInt("presentThisMonth")} day(s) this month" + if (detail.isNotEmpty() && status == "Present") " · $detail" else ""
            textSize = 12f; setTextColor(Pal.c("#8A5F52"))
        })
        val address = o.optString("address")
        if (status == "Present" && address.isNotEmpty()) {
            nameCol.addView(TextView(this).apply {
                text = "📍 $address"; textSize = 12f; setTextColor(Pal.c("#8A5F52"))
                maxLines = 2; setPadding(0, dp(2), 0, 0)
            })
        }
        top.addView(nameCol)
        top.addView(pill(if (status == "On Leave" && detail.isNotEmpty()) detail.substringBefore(" ") else pillLabel, fg, bg))
        col.addView(top)

        val summary = o.optJSONObject("summary")
        if (isManagerRow && summary != null) {
            val p = summary.optInt("present"); val lt = summary.optInt("late"); val l = summary.optInt("onLeave")
            val off = summary.optInt("off"); val ab = summary.optInt("absent"); val pe = summary.optInt("pending"); val t = summary.optInt("total")
            col.addView(TextView(this).apply {
                text = "👥 Team: $p Present" + (if (lt > 0) " ($lt Late)" else "") + " · $l On Leave" +
                    (if (off > 0) " · $off Off" else "") + (if (ab > 0) " · $ab Absent" else "") + " · $pe Pending  (of $t)"
                textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#6B352A"))
                setPadding(0, dp(8), 0, 0)
            })
            col.addView(TextView(this).apply {
                text = "Tap to view →"; textSize = 12f; setTextColor(Pal.c("#B39284"))
                setPadding(0, dp(2), 0, 0)
            })
        }

        val card = cardWrap(col)
        if (isManagerRow) {
            card.isClickable = true; card.isFocusable = true
            card.setOnClickListener { openTeamOf(o.optString("username"), o.optString("name")) }
        }
        return card
    }

    private fun leaveCard(o: JSONObject): View {
        val status = o.optString("status")
        val (fg, bg) = when (status) {
            "Approved" -> "#166534" to "#DCFCE7"
            "Rejected" -> "#B91C1C" to "#FEE2E2"
            else -> "#B45309" to "#FEF3C7"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        name.addView(TextView(this).apply { text = o.optString("name"); textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
        name.addView(TextView(this).apply { text = "${o.optString("type")} · ${o.optString("days")} day(s)"; textSize = 13f; setTextColor(Pal.c("#8A5F52")) })
        top.addView(name)
        top.addView(pill(status, fg, bg))
        col.addView(top)
        col.addView(TextView(this).apply {
            text = "${o.optString("from")}  →  ${o.optString("to")}"; textSize = 13f; setTextColor(Pal.c("#8A5F52"))
            (layoutParams as? LinearLayout.LayoutParams)
            setPadding(0, dp(6), 0, 0)
        })
        if (o.optString("reason").isNotEmpty())
            col.addView(TextView(this).apply { text = o.optString("reason"); textSize = 13f; setTextColor(Pal.c("#8A5F52")) })
        if (o.optString("application").isNotEmpty()) col.addView(applicationLink(session, o.optString("application")))
        decidedLine(o).takeIf { it.isNotEmpty() }?.let { line ->
            col.addView(TextView(this).apply { text = line; textSize = 13f; setTextColor(Pal.c(fg)); setPadding(0, dp(6), 0, 0) })
        }

        if (status == "Pending" && canDecide) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
            val approve = MaterialButton(this).apply {
                text = "✓ Approve"; setBackgroundColor(Pal.c("#16A34A"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            }
            val reject = MaterialButton(this).apply {
                text = "✕ Reject"; setBackgroundColor(Pal.c("#DC2626"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
            }
            approve.setOnClickListener { decide(o, "Approved", approve, reject) }
            reject.setOnClickListener { decide(o, "Rejected", approve, reject) }
            btnRow.addView(approve); btnRow.addView(reject)
            col.addView(btnRow)
        }
        return cardWrap(col)
    }

    private fun decide(o: JSONObject, decision: String, vararg buttons: MaterialButton) {
        buttons.forEach { it.isEnabled = false }
        val body = body().put("action", "decideLeave").put("empUsername", o.optString("username"))
            .put("appliedOn", o.optString("appliedOn")).put("from", o.optString("from")).put("to", o.optString("to"))
            .put("decision", decision)
        api(body) { res, err ->
            if (res != null) { loadAll() }
            else { buttons.forEach { it.isEnabled = true }; android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
