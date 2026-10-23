package com.example.attendance

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * My Reports — open to everyone. Download your own attendance or night hold report as an Excel file.
 * A manager can also choose "My team" (their own report plus everyone below them). The server decides
 * whose data is allowed; nobody can get anyone else's report.
 */
class ReportActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout

    private var reportFile: File? = null
    private val xlsxType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument(xlsxType)) { uri ->
        val f = reportFile
        if (uri != null && f != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(f).use { it.copyTo(out) } }
                toast("Saved. Open it from your Files app.")
            } catch (e: Exception) {
                toast("Could not save the file.")
            }
        }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val nice = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)            // the same plain scrolling page
        setupHeader("My Reports")
        setupBottomNav(null)
        enforceAccess("reports")
        content = findViewById(R.id.noticeList)
        build()
    }

    // ---------- small view helpers ----------

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun label(text: String, size: Float, color: String, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun column(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
    }

    private fun cardOf(inner: View) = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(inner)
    }

    private fun bigButton(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun chipButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Pal.c("#6B352A"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dateChip(title: String, onPick: (String) -> Unit): TextView {
        val chip = chipButton("📅  $title") {}
        chip.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                val v = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                onPick(v)
                chip.text = "📅  $title: " + (try { nice.format(iso.parse(v)!!) } catch (e: Exception) { v })
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        chip.visibility = View.GONE
        return chip
    }

    /** "26 Oct 2026 – 25 Nov 2026" for this pay period (previous = false) or the one before it. */
    private fun payPeriodText(previous: Boolean): String {
        val show = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val start = Calendar.getInstance().apply {
            if (get(Calendar.DAY_OF_MONTH) < 26) add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 26)
            if (previous) add(Calendar.MONTH, -1)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 25) }
        return show.format(start.time) + " – " + show.format(end.time)
    }

    /** A row of equal-width choice chips. Returns the row; [onPick] is called with the chosen key. */
    private fun chipRow(options: List<Pair<String, String>>, textSize: Float, onPick: (String) -> Unit, views: HashMap<String, TextView>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        options.forEachIndexed { i, (key, text) ->
            val v = TextView(this).apply {
                this.text = text; this.textSize = textSize; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), dp(12), dp(6), dp(12)); isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) }
                setOnClickListener { onPick(key) }
            }
            views[key] = v; row.addView(v)
        }
        return row
    }

    private fun paint(views: Map<String, TextView>, selected: String) {
        views.forEach { (k, v) ->
            val on = k == selected
            v.setTextColor(Pal.c(if (on) "#FFFFFF" else "#8A5F52"))
            v.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (on) Pal.chipOn else Pal.c("#F0E3A6")) }
        }
    }

    // ---------- the screen ----------

    private fun build() {
        content.removeAllViews()
        val isManager = session.isManager
        var choice = "current"
        var kind = "attendance"
        var who = "me"
        var from = ""
        var to = ""

        val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val msg = label("", 14f, "#DC2626", false).apply { setPadding(0, dp(8), 0, 0) }
        val hint = label("", 13f, "#8A5F52", false).apply { setPadding(0, dp(10), 0, 0) }
        val chipFrom = dateChip("From") { from = it }
        val chipTo = dateChip("To") { to = it }
        val infoSummary = label("", 13f, "#8A5F52", false)
        val infoDetailTitle = label("", 14f, "#6B352A", true).apply { setPadding(0, dp(10), 0, 0) }
        val infoDetail = label("", 13f, "#8A5F52", false)
        val whoHint = label("", 13f, "#8A5F52", false).apply { setPadding(0, dp(8), 0, 0) }

        val whoViews = HashMap<String, TextView>()
        val kindViews = HashMap<String, TextView>()
        val periodViews = HashMap<String, TextView>()

        fun refreshWho() {
            paint(whoViews, who)
            whoHint.text = if (who == "team") "Your own report plus everyone below you." else "Only your own report."
            result.removeAllViews()
        }
        fun refreshKind() {
            paint(kindViews, kind)
            if (kind == "night") {
                infoSummary.text = "One row per person: how many night holds, how many were manual entries, days in the period."
                infoDetailTitle.text = "🌙  Night Holds"
                infoDetail.text = "Every night hold: night date, time, district, thana, hotel, location, and whether it was a normal or manual entry."
            } else {
                infoSummary.text = "One row per person: Present, Late, Leave, Absent, Day Off, Total Days."
                infoDetailTitle.text = "🗓  Daily Details"
                infoDetail.text = "Every person, every day: status (colour-coded), time, leave type, day-off reason and location."
            }
            result.removeAllViews()
        }
        fun refreshPeriod() {
            paint(periodViews, choice)
            chipFrom.visibility = if (choice == "custom") View.VISIBLE else View.GONE
            chipTo.visibility = chipFrom.visibility
            hint.text = when (choice) {
                "current" -> payPeriodText(false) + "  (counted up to today)"
                "previous" -> payPeriodText(true)
                else -> "Pick the first and the last day (up to one year)."
            }
        }

        val form = column()
        form.addView(label("Download as Excel", 18f, "#3E1D16", true))
        form.addView(label(if (isManager) "Your report, or your whole team's." else "Your own attendance and night hold report.", 13f, "#8A5F52", false))

        if (isManager) {
            form.addView(label("WHOSE REPORT", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, 0) })
            form.addView(chipRow(listOf("me" to "👤  Only me", "team" to "👥  My team"), 14f, { who = it; refreshWho() }, whoViews))
            form.addView(whoHint)
        }
        form.addView(label("REPORT", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, 0) })
        form.addView(chipRow(listOf("attendance" to "📍  Attendance", "night" to "🌙  Night hold"), 14f, { kind = it; refreshKind() }, kindViews))
        form.addView(label("PERIOD", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, 0) })
        form.addView(chipRow(listOf("current" to "This pay period", "previous" to "Last pay period", "custom" to "Choose dates"), 13f, { choice = it; refreshPeriod() }, periodViews))
        form.addView(hint); form.addView(chipFrom); form.addView(chipTo)
        form.addView(bigButton("📊  Prepare Excel file") {
            if (choice == "custom" && (from.isEmpty() || to.isEmpty())) { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Choose the From and To dates"; return@bigButton }
            msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Preparing the file… this can take a few seconds."
            result.removeAllViews()
            val body = JSONObject().put("action", "myReport").put("period", choice).put("kind", kind).put("who", if (isManager) who else "me")
            if (choice == "custom") body.put("from", from).put("to", to)
            api(body) { res, err ->
                if (res == null) { msg.setTextColor(Pal.c("#DC2626")); msg.text = err; return@api }
                try {
                    val dir = File(cacheDir, "reports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, res.optString("name", "report.xlsx"))
                    f.writeBytes(Base64.decode(res.getString("data"), Base64.DEFAULT))
                    msg.text = ""
                    result.removeAllViews()
                    result.addView(readyCard(f, res.optInt("users"), res.optInt("days"), res.optString("from"), res.optString("to"), if (res.has("records")) res.optInt("records") else -1))
                } catch (e: Exception) {
                    msg.setTextColor(Pal.c("#DC2626")); msg.text = "Could not prepare the file."
                }
            }
        })
        form.addView(msg)
        content.addView(cardOf(form))
        content.addView(result)

        val info = column()
        info.addView(label("What is in the file", 16f, "#3E1D16", true))
        info.addView(label("📋  Summary", 14f, "#6B352A", true).apply { setPadding(0, dp(10), 0, 0) })
        info.addView(infoSummary)
        info.addView(infoDetailTitle)
        info.addView(infoDetail)
        content.addView(cardOf(info))

        refreshWho(); refreshKind(); refreshPeriod()
    }

    private fun readyCard(f: File, users: Int, days: Int, from: String, to: String, records: Int): View {
        val col = column()
        col.addView(label("✅  Your Excel file is ready", 16f, "#166534", true))
        col.addView(label(f.name, 13f, "#3E1D16", false).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(label("$users ${if (users == 1) "person" else "people"} · $days days ($from to $to)" +
            (if (records >= 0) " · $records night hold${if (records == 1) "" else "s"}" else ""), 13f, "#8A5F52", false))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        buttons.addView(MaterialButton(this).apply {
            text = "💾  Save"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            setOnClickListener { reportFile = f; saveReport.launch(f.name) }
        })
        buttons.addView(MaterialButton(this).apply {
            text = "📤  Share"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
            setOnClickListener {
                try {
                    val uri = FileProvider.getUriForFile(this@ReportActivity, "$packageName.fileprovider", f)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(xlsxType)
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(android.content.Intent.createChooser(send, "Share report"))
                } catch (e: Exception) { toast("Could not share the file.") }
            }
        })
        col.addView(buttons)
        return cardOf(col)
    }
}
