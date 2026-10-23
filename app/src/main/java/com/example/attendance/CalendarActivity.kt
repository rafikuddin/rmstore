package com.example.attendance

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class CalendarActivity : AppCompatActivity() {
    companion object {
        /** Set by the Admin Panel: show this person's calendar (read-only) instead of my own. */
        const val EXTRA_USER = "targetUser"
        const val EXTRA_NAME = "targetName"
        /** A line manager (allowed by an admin) looking at the calendar of someone below them. */
        const val EXTRA_VIEW_ONLY = "viewOnly"
    }
    private var target: String? = null
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val records = HashMap<String, JSONObject>()
    private val leaveDays = HashMap<String, JSONObject>()
    private val nightDays = HashMap<String, JSONObject>()   // date -> that day's night hold
    private val holidays = HashMap<String, String>()        // date -> holiday name (added by an admin)
    // `cal` always holds the 26th that starts the currently-shown pay period. Adding/subtracting a
    // MONTH is safe here because the 26th exists in every month, so the day-of-month never drifts.
    // Keep PERIOD_START_DAY/END in sync with Code.gs's PERIOD_START_DAY/PERIOD_END_DAY if ever changed.
    private val PERIOD_START_DAY = 26
    private val PERIOD_END_DAY = 25
    private val cal = Calendar.getInstance().apply {
        if (get(Calendar.DAY_OF_MONTH) < PERIOD_START_DAY) add(Calendar.MONTH, -1)
        set(Calendar.DAY_OF_MONTH, PERIOD_START_DAY)
    }
    private lateinit var grid: GridLayout
    private lateinit var tvMonth: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)
        target = intent.getStringExtra(EXTRA_USER)?.takeIf { it.isNotBlank() }
        val shownName = intent.getStringExtra(EXTRA_NAME) ?: target ?: ""
        setupHeader(if (target == null) "My Attendance" else shownName) { loadData() }
        setupBottomNav(if (target == null) Tab.CALENDAR else null)
        if (target == null) enforceAccess("calendar")
        grid = findViewById(R.id.grid)
        tvMonth = findViewById(R.id.tvMonth)
        tvCount = findViewById(R.id.tvCount)
        tvDetail = findViewById(R.id.tvDetail)
        if (target != null) tvDetail.text = "Viewing $shownName (@$target). Tap any date to see the time and location."
        findViewById<TextView>(R.id.btnPrev).setOnClickListener { cal.add(Calendar.MONTH, -1); render() }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { cal.add(Calendar.MONTH, 1); render() }
        render()
        loadData()
    }

    private fun loadData() {
        val t = target
        if (t != null) {                      // an admin looking at someone else: always fresh, nothing is saved on the phone
            tvCount.text = "Loading..."
            val refresh = findViewById<View>(R.id.btnRefresh)
            Spin.start(refresh, true)
            api(JSONObject().put("action", if (intent.getBooleanExtra(EXTRA_VIEW_ONLY, false)) "teamHistory" else "adminHistory").put("targetUsername", t)) { res, err ->
                Spin.stop(refresh)
                if (res != null) showHistory(res) else tvCount.text = err ?: "Could not load."
            }
            return
        }
        val s = Session(this)
        // Draws the last saved copy immediately (if any), then replaces it with the fresh answer.
        val showedSaved = apiCached(
            cacheKey = ResponseCache.calKey(s.username),
            body = JSONObject().put("action", "history").put("username", s.username).put("nights", true),
            maxAgeMs = 24 * HOUR_MS,
            busy = findViewById<View>(R.id.btnRefresh),
            onData = { res -> showHistory(res) },
            onError = { err, hadSaved -> if (!hadSaved) tvCount.text = err }
        )
        if (!showedSaved) tvCount.text = "Loading..."
    }

    private fun showHistory(res: JSONObject) {
        run {
            records.clear(); leaveDays.clear(); nightDays.clear(); holidays.clear()
            res.optJSONArray("holidays")?.let { hl ->
                for (i in 0 until hl.length()) hl.getJSONObject(i).let { holidays[it.optString("date")] = it.optString("name") }
            }
            res.optJSONArray("nights")?.let { nl ->
                for (i in 0 until nl.length()) nl.getJSONObject(i).let { nightDays[it.getString("date")] = it }
            }
            val arr = res.getJSONArray("records")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { records[it.getString("date")] = it }
            res.optJSONArray("leaves")?.let { lv ->
                for (i in 0 until lv.length()) {
                    val l = lv.getJSONObject(i)
                    val c = Calendar.getInstance(); c.time = iso.parse(l.getString("from"))!!
                    val end = iso.parse(l.getString("to"))!!.time
                    var n = 0
                    while (c.timeInMillis <= end && n++ < 120) { leaveDays[iso.format(c.time)] = l; c.add(Calendar.DAY_OF_MONTH, 1) }
                }
            }
            render()
        }
    }

    private fun cell(text: String) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(Pal.c("#3E1D16"))
        layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0; height = dp(44); setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun circle(fill: String?, stroke: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (fill != null) setColor(Color.parseColor(fill))
        if (stroke) setStroke(dp(2), Pal.c("#6B352A"))
    }

    // Friday and admin-set holidays are days off — never counted as absent. To add Saturday too,
    // also check `dow == Calendar.SATURDAY` everywhere FRIDAY is checked below.
    // A "month" here is one pay period: PERIOD_START_DAY of one calendar month through
    // PERIOD_END_DAY of the next, so the grid spans a real date range, not a calendar month.
    private fun render() {
        grid.removeAllViews()
        val periodStart = cal.clone() as Calendar
        val periodEnd = (cal.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, PERIOD_END_DAY) }
        val dFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val yFmt = SimpleDateFormat("yyyy", Locale.getDefault())
        tvMonth.text = "${dFmt.format(periodStart.time)} – ${dFmt.format(periodEnd.time)} ${yFmt.format(periodEnd.time)}"

        for (d in listOf("S", "M", "T", "W", "T", "F", "S"))
            grid.addView(cell(d).apply { setTypeface(null, Typeface.BOLD); setTextColor(Pal.c("#8A5F52")) })
        repeat(periodStart.get(Calendar.DAY_OF_WEEK) - 1) { grid.addView(cell("")) }

        val now = Calendar.getInstance()
        val todayKey = String.format(Locale.US, "%04d-%02d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val isAfterCutoff = now.get(Calendar.HOUR_OF_DAY) >= 18   // matches Code.gs's ABSENT_AFTER_HOUR (6:00 PM)
        var present = 0; var late = 0; var leave = 0; var absent = 0; var off = 0; var holidayCount = 0; var nightCount = 0

        val cursor = periodStart.clone() as Calendar
        while (!cursor.after(periodEnd)) {
            val key = String.format(Locale.US, "%04d-%02d-%02d", cursor.get(Calendar.YEAR), cursor.get(Calendar.MONTH) + 1, cursor.get(Calendar.DAY_OF_MONTH))
            val c = cell(cursor.get(Calendar.DAY_OF_MONTH).toString())
            val isToday = key == todayKey
            val isFriday = cursor.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
            val lv = leaveDays[key]
            when {
                records.containsKey(key) -> {
                    present++
                    val isLate = records[key]?.optBoolean("late") == true
                    if (isLate) late++
                    c.setTextColor(Color.WHITE); c.background = circle(if (isLate) "#EA580C" else "#16A34A", isToday)
                    c.setOnClickListener { show(key) }
                }
                lv != null -> {
                    val approved = lv.optString("status") == "Approved"
                    if (approved) leave++
                    c.setTextColor(if (approved) Color.WHITE else Pal.c("#78350F"))
                    c.background = circle(if (approved) "#F59E0B" else "#FDE68A", isToday)
                    c.setOnClickListener { show(key) }
                }
                holidays.containsKey(key) -> {
                    holidayCount++
                    c.setTextColor(Color.WHITE); c.background = circle("#8B5CF6", isToday)
                    c.setOnClickListener { show(key) }
                }
                isFriday -> {
                    off++
                    c.setTextColor(Pal.c("#4A241C")); c.background = circle("#EBDDA0", isToday)
                    c.setOnClickListener { show(key) }
                }
                key < todayKey || (isToday && isAfterCutoff) -> {
                    absent++
                    c.setTextColor(Color.WHITE); c.background = circle("#DC2626", isToday)
                    c.setOnClickListener { show(key) }
                }
                isToday -> c.background = circle(null, true)
            }
            if (nightDays.containsKey(key)) {
                nightCount++
                val label = SpannableStringBuilder(cursor.get(Calendar.DAY_OF_MONTH).toString())
                val moonStart = label.length
                label.append("\n🌙")
                label.setSpan(RelativeSizeSpan(0.6f), moonStart, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                c.text = label
            }
            c.setOnClickListener { show(key) }   // every date can be tapped, so night hold status is always one tap away
            if (isToday) c.setTypeface(null, Typeface.BOLD)
            grid.addView(c)
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        tvCount.text = "This period: $present present" + (if (late > 0) " ($late late)" else "") +
            " · $leave leave · $absent absent · $off Friday off" +
            (if (holidayCount > 0) " · $holidayCount holiday" else "") +
            (if (nightCount > 0) " · $nightCount night hold" else "")
    }

    /** A tappable line that opens a map at these coordinates (hidden when the date has no usable coordinates). */
    private fun mapLink(view: TextView, lat: String?, lng: String?) {
        val la = lat?.toDoubleOrNull(); val lo = lng?.toDoubleOrNull()
        if (la == null || lo == null) { view.visibility = View.GONE; view.setOnClickListener(null); return }
        view.visibility = View.VISIBLE
        view.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$la,$lo")))
            } catch (e: Exception) {
                Toast.makeText(this, "No app could open the map.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun show(key: String) {
        val r = records[key]
        mapLink(findViewById(R.id.btnMapDay), r?.optString("lat"), r?.optString("lng"))
        mapLink(findViewById(R.id.btnMapNight), nightDays[key]?.optString("lat"), nightDays[key]?.optString("lng"))
        val lv = leaveDays[key]
        val now = Calendar.getInstance()
        val todayKey = String.format(Locale.US, "%04d-%02d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val isAfterCutoff = now.get(Calendar.HOUR_OF_DAY) >= 18
        val p = key.split("-").map { it.toInt() }
        val dayCal = Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2]) }
        val isFriday = dayCal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val dayText = when {
            r != null -> "${if (r.optBoolean("late")) "⏰ Late Present" else "✅ Present"} · $key\nTime: ${r.optString("time")}\nLocation: ${r.optString("lat")}, ${r.optString("lng")}\n${r.optString("address")}"
            lv != null -> "🌴 ${lv.optString("type")} (${lv.optString("status")})\n${lv.optString("from")} → ${lv.optString("to")} · ${lv.optString("days")} day(s)\n${lv.optString("reason")}"
            holidays.containsKey(key) -> "🎉 Holiday · ${holidays[key]} · $key"
            isFriday -> "🗓️ Weekly Off (Friday) · $key"
            key < todayKey || (key == todayKey && isAfterCutoff) -> "❌ Absent · $key\nNo attendance was marked and no approved leave covers this day."
            else -> ""
        }
        val night = nightDays[key]
        val nightText = when {
            night != null -> "🌙 Night hold: Submitted\n🏨 ${night.optString("hotel")}\n" +
                "📍 ${night.optString("thana")}, ${night.optString("district")}\n🕒 ${night.optString("time")}" +
                (if (night.optString("address").isNotEmpty()) "\n${night.optString("address")}" else "")
            key <= todayKey -> "🌙 Night hold: None"
            else -> ""
        }
        tvDetail.text = when {
            dayText.isEmpty() && nightText.isEmpty() -> "Nothing recorded for $key yet."
            dayText.isEmpty() -> nightText
            nightText.isEmpty() -> dayText
            else -> "$dayText\n\n$nightText"
        }
    }
}
