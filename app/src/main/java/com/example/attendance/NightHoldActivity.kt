package com.example.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Night hold: an employee staying away overnight reports where. Submit stays disabled until a real
 * GPS fix, a district, a thana and a real hotel name are all present (the server checks them again).
 */
class NightHoldActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var tvLoc: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var etDistrict: EditText
    private lateinit var etThana: EditText
    private lateinit var etHotel: EditText
    private lateinit var btnSubmit: MaterialButton
    private lateinit var tvMsg: TextView
    private lateinit var cardDone: View
    private lateinit var cardForm: View
    private lateinit var tvDone: TextView
    private lateinit var cardClosed: View
    private lateinit var tvClosed: TextView

    private var location: Location? = null
    private var locAt = 0L
    private var address = ""
    private var district: String? = null
    private var thana: String? = null
    private var submittedToday = false           // tonight's night hold is already in
    private var record: JSONObject? = null         // tonight's night hold, if any
    private var statusKnown = false                // false until the first answer (saved or fresh) has arrived
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())
    // The night hold hours are set by an admin (default 9:00 PM to 6:00 AM) and arrive with every answer from the server,
    // which also enforces them and is the final judge of the clock.
    private var openHour = 21
    private var closeHour = 6
    private lateinit var tvHours: TextView          // "Open every night from ... to ...": follows the hours the admin set
    private var cycleOffset = 0                           // 0 = this cycle, -1 = the one before, ... (never in the future)
    private val otherNights = ArrayList<JSONObject>()     // the night holds of the cycle being looked at when it is not the current one
    private var otherLoading = false
    private val cycleNights = ArrayList<JSONObject>()      // this cycle's night holds (newest first), shown in the summary at the top
    private var forDate: String? = null            // set when an admin opened a past night for this person (manual entry)

    private fun showHours() {
        tvHours.text = if (forDate != null) "An admin opened this night for you. Every field and your live location are required."
        else "Open every night from ${hourText(openHour)} to ${hourText(closeHour)}. Every field and your live location are required."
    }

    private fun hourText(h: Int) = "${if (h % 12 == 0) 12 else h % 12}:00 ${if (h < 12) "AM" else "PM"}"

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchLocation()
        else locFailed("Location permission is needed to submit a night hold. Allow it, then tap Retry.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_night)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        forDate = intent.getStringExtra(EXTRA_FOR_DATE)?.takeIf { it.isNotBlank() }
        setupHeader(if (forDate != null) "Manual Night Hold" else "Night Hold") { loadStatus(); if (cardForm.visibility == View.VISIBLE) startLocation() }
        setupBottomNav(Tab.NIGHT)
        enforceAccess("night")

        tvLoc = findViewById(R.id.tvLoc)
        btnRetry = findViewById(R.id.btnRetryLoc)
        etDistrict = findViewById(R.id.etDistrict)
        etThana = findViewById(R.id.etThana)
        etHotel = findViewById(R.id.etHotel)
        btnSubmit = findViewById(R.id.btnNightSubmit)
        tvMsg = findViewById(R.id.tvNightMsg)
        cardDone = findViewById(R.id.cardDone)
        cardForm = findViewById(R.id.cardForm)
        tvDone = findViewById(R.id.tvDone)
        cardClosed = findViewById(R.id.cardClosed); tvClosed = findViewById(R.id.tvClosed)
        tvHours = findViewById(R.id.tvNightHours)
        showHours()

        etDistrict.setOnClickListener {
            PickerDialog.show(this, "Select district", BdLocations.districts(this)) { picked ->
                if (picked != district) { district = picked; thana = null; etThana.setText("") }
                etDistrict.setText(picked)
                updateButton()
            }
        }
        etThana.setOnClickListener {
            val d = district
            if (d == null) {
                Toast.makeText(this, "Choose a district first", Toast.LENGTH_SHORT).show()
            } else {
                PickerDialog.show(this, "Select thana · $d", BdLocations.thanas(this, d)) { picked ->
                    thana = picked
                    etThana.setText(picked)
                    updateButton()
                }
            }
        }
        etHotel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateButton() }
        })
        btnRetry.setOnClickListener { startLocation() }
        btnSubmit.setOnClickListener { submit() }

        if (forDate != null) { statusKnown = true; renderState(); msg("📝 Manual night hold for $forDate. An admin opened this night for you.", true) }
        else loadStatus()      // shows the right card (form / submitted / closed) from saved data at once, then refreshes it
    }

    override fun onResume() {
        super.onResume()
        if (statusKnown) renderState()
        clock.postDelayed(object : Runnable {            // notice 9:00 PM / 6:00 AM arriving while the screen is open
            override fun run() { if (statusKnown) renderState(); clock.postDelayed(this, 30000) }
        }, 30000)
    }

    override fun onPause() {
        super.onPause()
        clock.removeCallbacksAndMessages(null)
    }

    // ---- which night is it, and is the window open? ----

    private fun isoDay(c: Calendar) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)

    private fun windowOpen(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (openHour > closeHour) (h >= openHour || h < closeHour) else (h >= openHour && h < closeHour)
    }

    /** A night is named by the date its evening started: after midnight (before 6 AM) it is still last evening's night. */
    private fun nightKey(): String {
        val c = Calendar.getInstance()
        if (openHour > closeHour && c.get(Calendar.HOUR_OF_DAY) < closeHour) c.add(Calendar.DAY_OF_MONTH, -1)
        return isoDay(c)
    }

    // ---- today's status ----

    private fun loadStatus() {
        if (forDate != null) return
        val yesterday = isoDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) })   // after midnight tonight's record is dated yesterday
        apiCached(
            cacheKey = ResponseCache.nightKey(session.username),
            body = JSONObject().put("action", "nights").put("username", session.username)
                .put("from", minOf(yesterday, cycleStartKey())),
            maxAgeMs = 24 * HOUR_MS,
            busy = findViewById<View>(R.id.btnRefresh),
            onData = { res -> applyStatus(res) },
            onError = { err, hadSaved -> if (!hadSaved) msg("Couldn't check tonight's status: ${err ?: "no connection"}", false) }
        )
    }

    /** First day of the current cycle: the 26th of this or last month (keep in sync with PERIOD_START_DAY in Code.gs). */
    private fun cycleStartKey(offset: Int = 0): String {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)                    // (avoids the 31st rolling over while months are added)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        if (today < 26) c.add(Calendar.MONTH, -1)
        c.add(Calendar.MONTH, offset)
        c.set(Calendar.DAY_OF_MONTH, 26)
        return isoDay(c)
    }

    private fun cycleEndKey(offset: Int = 0): String {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        if (today < 26) c.add(Calendar.MONTH, -1)
        c.add(Calendar.MONTH, offset + 1)
        c.set(Calendar.DAY_OF_MONTH, 25)
        return isoDay(c)
    }

    /** ‹ / › : look at the night holds of an earlier cycle (the server returns everything from a date on, so the others are asked for when needed). */
    private fun moveCycle(delta: Int) {
        val next = cycleOffset + delta
        if (next > 0) return
        cycleOffset = next
        otherNights.clear()
        if (cycleOffset == 0) { renderSummary(); return }
        otherLoading = true
        renderSummary()
        val wanted = cycleOffset
        api(JSONObject().put("action", "nights").put("username", session.username).put("from", cycleStartKey(wanted))) { res, err ->
            if (wanted != cycleOffset) return@api                  // the person moved on meanwhile
            otherLoading = false
            val arr = res?.optJSONArray("nights")
            if (res == null) Toast.makeText(this, err ?: "Could not load that cycle.", Toast.LENGTH_SHORT).show()
            otherNights.clear()
            if (arr != null) for (i in 0 until arr.length()) otherNights.add(arr.getJSONObject(i))
            renderSummary()
        }
    }

    /** The summary card at the top: how many nights were submitted in this cycle, and where (hotel, thana, district; tap = map). */
    private fun renderSummary() {
        val card = findViewById<View>(R.id.cardSummary)
        if (forDate != null) { card.visibility = View.GONE; return }
        val from = cycleStartKey(cycleOffset); val to = cycleEndKey(cycleOffset)
        val source = if (cycleOffset == 0) cycleNights else otherNights
        val rows = source.filter { it.optString("date") in from..to }.sortedByDescending { it.optString("date") }
        findViewById<View>(R.id.btnCyclePrev).setOnClickListener { moveCycle(-1) }
        findViewById<View>(R.id.btnCycleNext).apply { setOnClickListener { moveCycle(1) }; alpha = if (cycleOffset == 0) 0.25f else 1f }
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val shortFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
        val dayFmt = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvSumHeading).text = "NIGHT HOLD · ${shortFmt.format(inFmt.parse(from) ?: Date())} – ${shortFmt.format(inFmt.parse(to) ?: Date())}"
        findViewById<TextView>(R.id.tvSumCount).text = if (rows.size == 1) "1 night" else "${rows.size} nights"
        val last = rows.firstOrNull()
        findViewById<TextView>(R.id.tvSumHint).text = if (otherLoading) "Loading…"
            else if (last == null) (if (cycleOffset == 0) "No night hold submitted yet in this cycle." else "No night hold in this cycle.")
            else "Last: " + (try { dayFmt.format(inFmt.parse(last.optString("date"))!!) } catch (e: Exception) { last.optString("date") }) +
                 (last.optString("district").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        // the details go in their own card below the form
        val details = findViewById<View>(R.id.cardDetails)
        details.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvDetHeading).text = if (cycleOffset == 0) "NIGHT HOLDS IN THIS CYCLE" else "NIGHT HOLDS · ${shortFmt.format(inFmt.parse(from) ?: Date())} – ${shortFmt.format(inFmt.parse(to) ?: Date())}"
        val list = findViewById<android.widget.LinearLayout>(R.id.sumList)
        list.removeAllViews()
        if (rows.isEmpty()) return
        rows.forEach { o ->
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
            val day = try { dayFmt.format(inFmt.parse(o.optString("date"))!!) } catch (e: Exception) { o.optString("date") }
            row.addView(TextView(this).apply { text = "🌙  $day" + (if (o.optString("time").isNotEmpty()) "  ·  ${o.optString("time")}" else ""); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Pal.c("#3E1D16")) })
            val place = listOf(o.optString("thana"), o.optString("district")).filter { it.isNotBlank() }.joinToString(", ")
            row.addView(TextView(this).apply { text = "🏨 ${o.optString("hotel")}\n📍 $place"; textSize = 13f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(2), 0, 0) })
            val lat = o.optString("lat").toDoubleOrNull(); val lng = o.optString("lng").toDoubleOrNull()
            if (lat != null && lng != null) {
                row.isClickable = true
                row.setOnClickListener {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(" + android.net.Uri.encode(o.optString("hotel")) + ")"))) }
                    catch (e: Exception) { Toast.makeText(this, "No maps app found.", Toast.LENGTH_SHORT).show() }
                }
            }
            list.addView(row)
        }
        list.addView(TextView(this).apply { text = "Tap a night to see its location on the map."; textSize = 11f; setTextColor(Pal.c("#A98578")); setPadding(0, dp(10), 0, 0) })
    }

    private fun applyStatus(res: JSONObject) {
        res.optJSONObject("window")?.let { openHour = it.optInt("openHour", 21); closeHour = it.optInt("closeHour", 6) }
        showHours()
        val tonight = nightKey()
        val arr = res.optJSONArray("nights")
        var found: JSONObject? = null
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("date") == tonight) found = o
            }
        }
        record = found
        cycleNights.clear()
        if (arr != null) for (i in 0 until arr.length()) cycleNights.add(arr.getJSONObject(i))
        renderSummary()
        statusKnown = true
        renderState()
    }

    /** Decides which card to show: submitted, the form (window open) or the closed notice. */
    private fun renderState() {
        val rec = record
        when {
            rec != null -> showDone(rec)
            forDate != null || windowOpen() -> showForm()
            else -> showClosed()
        }
    }

    private fun showClosed() {
        submittedToday = false
        cardForm.visibility = View.GONE
        cardDone.visibility = View.GONE
        cardClosed.visibility = View.VISIBLE
        tvClosed.text = "Night hold can be submitted every night from ${hourText(openHour)} to ${hourText(closeHour)}.\n\nCome back at ${hourText(openHour)}."
    }

    private fun showDone(rec: JSONObject) {
        submittedToday = true
        cardForm.visibility = View.GONE
        cardClosed.visibility = View.GONE
        cardDone.visibility = View.VISIBLE
        val addr = rec.optString("address")
        tvDone.text = "🌙 Night hold submitted for ${forDate ?: "tonight"}\n\n🏨 ${rec.optString("hotel")}\n" +
            "📍 ${rec.optString("thana")}, ${rec.optString("district")}\n🕒 ${rec.optString("time")}" +
            (if (addr.isNotEmpty()) "\n$addr" else "") +
            "\n\nOnly one night hold can be submitted per night."
    }

    private fun showForm() {
        val wasForm = cardForm.visibility == View.VISIBLE
        submittedToday = false
        cardDone.visibility = View.GONE
        cardClosed.visibility = View.GONE
        cardForm.visibility = View.VISIBLE
        updateButton()
        if (!wasForm && location == null) startLocation()        // the window just opened (or the screen just appeared): get the GPS fix
    }

    // ---- live location ----

    private fun startLocation() {
        if (submittedToday) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            fetchLocation()
        else
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private var locHandle: LiveLocation.Handle? = null

    private fun fetchLocation() {
        locHandle?.cancel()
        location = null
        address = ""
        tvLoc.text = LiveLocation.waitingText(0).let { "📍 $it" }
        btnRetry.visibility = View.GONE
        updateButton()
        locHandle = LiveLocation.start(
            this,
            onProgress = { sec -> if (location == null) tvLoc.text = "📍 " + LiveLocation.waitingText(sec) },
            onFail = { locFailed(it) },
            onFix = { loc ->
                if (isMock(loc)) {
                    locFailed("A fake / mock location was detected. Turn off any fake-GPS app, then tap Retry.")
                } else {
                    location = loc
                    locAt = System.currentTimeMillis()
                    val coords = String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
                    tvLoc.text = "📍 Location locked\n$coords\nFinding address…"
                    updateButton()
                    Thread {
                        val found = try {
                            @Suppress("DEPRECATION")
                            Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                                ?.firstOrNull()?.getAddressLine(0)
                        } catch (e: Exception) {
                            null
                        }
                        runOnUiThread {
                            if (location === loc) {
                                address = found ?: ""
                                tvLoc.text = "📍 Location locked\n$coords" + (if (address.isNotEmpty()) "\n$address" else "")
                            }
                        }
                    }.start()
                }
            }
        )
    }

    override fun onDestroy() {
        locHandle?.cancel()
        super.onDestroy()
    }

    private fun locFailed(message: String) {
        location = null
        tvLoc.text = "⚠️ $message"
        btnRetry.visibility = View.VISIBLE
        updateButton()
    }

    @Suppress("DEPRECATION")
    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider

    // ---- validation + submit ----

    private fun validHotel(raw: String): Boolean {
        val h = raw.trim()
        return h.length >= 3 && h.any { it.isLetter() }
    }

    private fun updateButton() {
        btnSubmit.isEnabled = !submittedToday && location != null && district != null && thana != null &&
            validHotel(etHotel.text.toString())
    }

    private fun msg(text: String, ok: Boolean) {
        tvMsg.text = text
        tvMsg.setTextColor(Pal.c(if (ok) "#166534" else "#DC2626"))
    }

    private fun submit() {
        val loc = location
        val d = district
        val t = thana
        val hotel = etHotel.text.toString().trim().replace(Regex("\\s+"), " ")
        if (loc == null) { msg("Waiting for your live location.", false); return }
        if (d == null) { msg("Please select a district.", false); return }
        if (t == null) { msg("Please select a thana.", false); return }
        if (!validHotel(hotel)) { msg("Please enter the actual hotel name.", false); return }
        if (System.currentTimeMillis() - locAt > 10 * 60 * 1000L) {
            msg("Your location is out of date, so it is being refreshed. Tap Submit again in a moment.", false)
            startLocation()
            return
        }

        btnSubmit.isEnabled = false
        msg("Submitting…", true)
        val body = JSONObject().put("action", "nightSubmit")
            .put("username", session.username)
            .put("lat", loc.latitude).put("lng", loc.longitude).put("mock", isMock(loc)).put("address", address)
            .put("district", d).put("thana", t).put("hotel", hotel)
        forDate?.let { body.put("forDate", it) }

        api(body) { res, err ->
            if (res != null) {
                // Saved copies of the Calendar and this screen are now out of date.
                ResponseCache.remove(this, ResponseCache.calKey(session.username), ResponseCache.nightKey(session.username), ResponseCache.homeKey(session.username))
                msg("", true)
                val saved = JSONObject().put("date", res.optString("date")).put("time", res.optString("time"))
                        .put("hotel", hotel).put("thana", t).put("district", d).put("address", address)
                record = saved
                if (forDate == null) {
                    cycleNights.removeAll { it.optString("date") == saved.optString("date") }
                    cycleNights.add(JSONObject(saved.toString()).put("lat", loc.latitude.toString()).put("lng", loc.longitude.toString()))
                    renderSummary()
                }
                showDone(saved)
            } else {
                msg("❌ $err", false)
                updateButton()
                if (res == null && err?.contains("only between") == true) { record = null; statusKnown = true; renderState() }   // the server says the window is closed
            }
        }
    }
}
