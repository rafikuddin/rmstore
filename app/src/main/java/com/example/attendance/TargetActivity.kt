package com.example.attendance

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Target vs Collection. Everyone sees their own target and what they collected; a manager also sees everyone below
 * them; an admin sees everyone. The numbers come from the two Excel files an admin uploads (Admin Panel > 🎯 Targets).
 * The server decides whose numbers are allowed.
 */
class TargetActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private var daysLeft = 0          // remaining working days (for the RR captions)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Target vs Collection") { load() }
        setupBottomNav(null)
        enforceAccess("target")
        content = findViewById(R.id.noticeList)
        load()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun label(text: String, size: Float, color: String, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun cardOf(inner: View, onClick: (() -> Unit)? = null) = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        addView(inner)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }

    private fun pctColor(p: Double?): String = when {
        p == null -> "#8A5F52"
        p >= 100 -> "#15803D"
        p >= 70 -> "#B45309"
        else -> "#DC2626"
    }

    /** A thin progress bar; the fill stops at 100% (the % text shows more). */
    private fun bar(p: Double?): View {
        val frac = ((p ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Pal.c("#F0E3A6")) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(8); bottomMargin = dp(4) }
        }
        track.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor(pctColor(p))) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, frac)
        })
        if (frac < 1f) track.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - frac) })
        return track
    }

    private fun pctText(o: JSONObject): String = if (o.isNull("pct")) "no target" else "${trim(o.optDouble("pct"))}%"
    private fun trim(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

    private fun stat(title: String, value: String, color: String = "#3E1D16") = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(label(title, 11f, "#8A5F52", true).apply { letterSpacing = 0.06f })
        addView(label(value, 15f, color, true))
    }

    private fun load() {
        content.removeAllViews()
        content.addView(label("Loading…", 14f, "#8A5F52", false).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        api(JSONObject().put("action", "targetView")) { res, err ->
            content.removeAllViews()
            if (res == null) { content.addView(label(err ?: "Could not load.", 14f, "#DC2626", false).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return@api }
            show(res)
        }
    }

    private fun niceDate(iso: String): String = try {
        SimpleDateFormat("d MMM", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!)
    } catch (e: Exception) { iso }

    private fun show(res: JSONObject) {
        val scope = res.optString("scope")
        if (!res.optBoolean("loaded")) {
            val c = column()
            c.addView(label("🎯  Nothing to show yet", 16f, "#3E1D16", true))
            c.addView(label(if (scope == "all") "Upload the Target file and the Sale Collection file in Admin Panel → 🎯 Targets." else "Your admin has not uploaded the target and collection files yet.", 14f, "#8A5F52", false).apply { setPadding(0, dp(6), 0, 0) })
            content.addView(cardOf(c)); return
        }
        val days = res.optJSONObject("days")
        val per = res.optJSONObject("period")
        daysLeft = days?.optInt("remaining") ?: 0
        if (days != null && per != null) {
            val w = column()
            w.addView(label("WORKING DAYS  ·  ${niceDate(per.optString("from"))} – ${niceDate(per.optString("to"))}", 12f, "#6B352A", true).apply { letterSpacing = 0.06f })
            val st = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            st.addView(bigStat("PASSED", days.optInt("passed").toString(), "#6B352A"))
            st.addView(bigStat("REMAINING", days.optInt("remaining").toString(), "#B45309"))
            st.addView(bigStat("TOTAL", days.optInt("total").toString(), "#3E1D16"))
            w.addView(st)
            val asOf = days.optString("asOf")
            w.addView(label((if (asOf.isNotEmpty()) "Counted up to ${niceDate(asOf)}. " else "") + "Fridays and holidays are not working days.", 11f, "#A98578", false).apply { setPadding(0, dp(6), 0, 0) })
            content.addView(cardOf(w))
        }
        val from = res.optString("from"); val to = res.optString("to")
        val upd = res.optJSONObject("updated")
        val line = StringBuilder()
        if (from.isNotEmpty()) line.append("Collection ${niceDate(from)} – ${niceDate(to)}")
        val on = upd?.optString("collection").orEmpty()
        if (on.isNotEmpty()) line.append("  ·  updated ${on.take(10)}")
        if (line.isNotEmpty()) content.addView(label(line.toString(), 12f, "#8A5F52", false).apply { setPadding(dp(6), 0, dp(6), dp(8)) })

        // ---- my numbers ----
        val me = res.optJSONObject("me")
        if (me != null && scope != "all") content.addView(personCard(me, "MY TARGET", big = true))
        else if (me != null && me.optBoolean("hasTarget")) content.addView(personCard(me, "MY TARGET", big = true))

        // ---- team / everyone ----
        if (scope != "me") {
            val t = res.getJSONObject("totals")
            val c = column()
            c.addView(label(if (scope == "all") "EVERYONE" else "MY TEAM", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
            c.addView(row(label(pctText(t), 26f, pctColor(if (t.isNull("pct")) null else t.optDouble("pct")), true), label("${t.optInt("people")} people", 13f, "#8A5F52", false)))
            c.addView(bar(if (t.isNull("pct")) null else t.optDouble("pct")))
            val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            stats.addView(stat("TARGET", taka(t.optDouble("target"))))
            stats.addView(stat("COLLECTED", taka(t.optDouble("collected")), "#15803D"))
            stats.addView(stat("REMAINING", taka(t.optDouble("remaining")), "#B45309"))
            c.addView(stats)
            c.addView(rrLine(if (t.isNull("rr")) null else t.optDouble("rr"), true, t.optDouble("target") > 0))
            content.addView(cardOf(c))
            if (res.has("unassigned") && (res.optDouble("unassigned") != 0.0 || res.optDouble("unknownUser") != 0.0)) {
                val w = column()
                if (res.optDouble("unassigned") != 0.0) {
                    w.addView(label("⚠️  ${taka(res.optDouble("unassigned"))} of the collection has no Username in the file, so it is not counted for anyone.", 12f, "#B45309", false))
                    w.addView(label("Tap to see these by dealer", 11f, "#A98578", false).apply { setPadding(0, dp(4), 0, 0) })
                }
                if (res.optDouble("unknownUser") != 0.0) w.addView(label("⚠️  ${taka(res.optDouble("unknownUser"))} belongs to a Username that is not an app user.", 12f, "#B45309", false).apply { setPadding(0, dp(4), 0, 0) })
                content.addView(cardOf(w, if (res.optDouble("unassigned") != 0.0) ({ detail("", "No Username in the file", true) }) else null))
            }
            content.addView(label("PEOPLE", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(dp(6), dp(10), dp(6), dp(6)) })
            val rows = res.getJSONArray("rows")
            if (rows.length() == 0) content.addView(label("No one has a target or a collection yet.", 14f, "#8A5F52", false).apply { setPadding(dp(6), 0, dp(6), 0) })
            for (i in 0 until rows.length()) content.addView(personCard(rows.getJSONObject(i), null, big = false))
        } else if (me == null || (!me.optBoolean("hasTarget") && me.optDouble("collected") == 0.0)) {
            content.addView(cardOf(column().apply { addView(label("There is no target or collection for you in the uploaded files.", 14f, "#8A5F52", false)) }))
        }
    }

    private fun bigStat(title: String, value: String, color: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(label(value, 28f, color, true))
        addView(label(title, 11f, "#8A5F52", true).apply { letterSpacing = 0.06f })
    }

    /** RR = the amount still to collect each remaining working day to reach the target. */
    private fun rrLine(rr: Double?, hasTarget: Boolean, show: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#FFF6C4")) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            visibility = if (show) View.VISIBLE else View.GONE
        }
        box.addView(label("RR  ·  REQUIRED RATE PER WORKING DAY", 11f, "#6B352A", true).apply { letterSpacing = 0.05f })
        when {
            !hasTarget -> {}
            rr == null -> box.addView(label("No working days left", 15f, "#DC2626", true))
            rr == 0.0 -> box.addView(label("✅  Target reached", 15f, "#15803D", true))
            else -> {
                box.addView(label(taka(rr), 18f, "#6B352A", true))
                box.addView(label("every working day for the next $daysLeft working day${if (daysLeft == 1) "" else "s"}", 11f, "#8A5F52", false))
            }
        }
        return box
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(b)
    }

    private fun personCard(o: JSONObject, title: String?, big: Boolean): View {
        val c = column()
        val p = if (o.isNull("pct")) null else o.optDouble("pct")
        if (title != null) c.addView(label(title, 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
        val nm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nm.addView(label(o.optString("name"), if (big) 16f else 15f, "#3E1D16", true))
        if (!big) nm.addView(label("@" + o.optString("username"), 12f, "#8A5F52", false))
        val base = listOf(o.optString("baseCode"), o.optString("baseName")).filter { it.isNotBlank() }.joinToString(" · ")
        if (base.isNotEmpty()) nm.addView(label("Base: $base", 12f, "#8A5F52", false))
        c.addView(row(nm, label(pctText(o), if (big) 26f else 18f, pctColor(p), true)))
        c.addView(bar(p))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        stats.addView(stat("TARGET", if (o.optBoolean("hasTarget")) taka(o.optDouble("target")) else "—"))
        stats.addView(stat("COLLECTED", taka(o.optDouble("collected")), "#15803D"))
        stats.addView(stat("REMAINING", if (o.optBoolean("hasTarget")) taka(o.optDouble("remaining")) else "—", "#B45309"))
        c.addView(stats)
        c.addView(rrLine(if (o.isNull("rr")) null else o.optDouble("rr"), o.optBoolean("hasTarget"), o.optBoolean("hasTarget")))
        if (!o.optBoolean("hasTarget")) c.addView(label("No target in the file for this person.", 12f, "#B45309", false).apply { setPadding(0, dp(4), 0, 0) })
        c.addView(label("Tap for day by day", 11f, "#A98578", false).apply { setPadding(0, dp(6), 0, 0) })
        return cardOf(c) { detail(o.optString("username"), o.optString("name")) }
    }

    private fun detail(username: String, name: String, unassigned: Boolean = false) {
        api(JSONObject().put("action", "targetDetail").put("username", username).put("unassigned", unassigned)) { res, err ->
            if (res == null) { toast(err ?: "Could not load"); return@api }
            val sb = StringBuilder()
            sb.append("Collected ${taka(res.optDouble("collected"))}")
            if (res.optDouble("target") > 0) sb.append(" of ${taka(res.optDouble("target"))}")
            if (res.optDouble("target") > 0) sb.append(if (res.isNull("rr")) "\nRR: no working days left" else if (res.optDouble("rr") == 0.0) "\nRR: target reached" else "\nRR: ${taka(res.optDouble("rr"))} per working day")
            sb.append("\n${res.optInt("entries")} collection${if (res.optInt("entries") == 1) "" else "s"}\n\nBY DAY\n")
            val days = res.optJSONArray("days")
            if (days == null || days.length() == 0) sb.append("No collection yet.\n")
            else for (i in 0 until days.length()) { val d = days.getJSONObject(i); sb.append("${niceDate(d.optString("d"))}   ${taka(d.optDouble("a"))}\n") }
            val dealers = res.optJSONArray("dealers")
            if (dealers != null && dealers.length() > 0) {
                val n = res.optInt("dealerCount")
                sb.append("\nBY DEALER ($n)\n")
                for (i in 0 until dealers.length()) {
                    val d = dealers.getJSONObject(i)
                    val code = d.optString("code"); val nm = d.optString("name")
                    sb.append((listOf(code, nm).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "(no dealer)" }) + "   " + taka(d.optDouble("amount")) + "\n")
                    val b = listOf(d.optString("baseCode"), d.optString("baseName")).filter { it.isNotBlank() }.joinToString(" · ")
                    if (b.isNotEmpty()) sb.append("   Base: $b\n")
                }
                if (n > dealers.length()) sb.append("…and ${n - dealers.length()} more dealers\n")
            }
            val types = res.optJSONArray("types")
            if (types != null && types.length() > 0) {
                sb.append("\nBY TYPE\n")
                for (i in 0 until types.length()) {
                    val t = types.getJSONObject(i)
                    sb.append("${t.optString("type")}   ${taka(t.optDouble("amount"))}" + (if (!t.optBoolean("counted", true)) "   (not counted)" else "") + "\n")
                }
            }
            AlertDialog.Builder(this).setTitle(name).setView(ScrollView(this).apply {
                addView(TextView(this@TargetActivity).apply { text = sb.toString(); textSize = 14f; setTextColor(Pal.c("#6B352A")); setPadding(dp(22), dp(8), dp(22), dp(8)) })
            }).setPositiveButton("Close", null).show()
        }
    }
}
