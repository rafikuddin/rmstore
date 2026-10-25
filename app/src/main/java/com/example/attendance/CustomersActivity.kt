package com.example.attendance

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Customers. A person asks for a new customer (name, NID, TIN (optional), trade license, division / district / thana,
 * pictures and the live location of the customer's place). The request goes to the person's line manager (when they have one),
 * then to an admin. The admin's approval gives the customer a code (RPC-01, RPC-02 ...). The server decides who may do what.
 */
class CustomersActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private var isAdmin = false
    private var isManager = false
    private var mode = "new"                        // new | mine | review
    private var reviewAll = false

    // the request being typed (kept while you switch pages)
    private var name = ""
    private var nid = ""
    private var tin = ""
    private var trade = ""
    private var division = ""
    private var district = ""
    private var thana = ""
    private var detail = ""
    private val pictures = ArrayList<ByteArray>()
    private var lat: Double? = null
    private var lng: Double? = null
    private var mock = false
    private var locText = "📍 Location not captured yet"
    private var locHandle: LiveLocation.Handle? = null

    private val picker = PicturePicker(this)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLocation() else { locText = "📍 Location permission is needed. Allow it, then tap the button again."; if (mode == "new") show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Customers") { show() }
        setupNav("customers")
        content = findViewById(R.id.noticeList)
        isAdmin = AdminFlag.get(this)
        isManager = session.isManager || isAdmin
        show()
    }

    override fun onDestroy() {
        locHandle?.cancel()
        super.onDestroy()
    }

    // ---------- small view helpers ----------

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(bg)) }
    }

    private fun statusPill(status: String): TextView = when (status) {
        "Approved" -> pill(status, "#166534", "#DCFCE7")
        "Rejected", "Cancelled" -> pill(status, "#B91C1C", "#FEE2E2")
        else -> pill(status, "#B45309", "#FEF3C7")
    }

    private fun cardOf(inner: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(inner)
    }

    private fun column(pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Pal.c(if (selected) "#FFFFFF" else "#6B352A"))
        setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c(if (selected) "#6B352A" else "#F0E3A6")) }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    /** A full-width pick button that looks like a drop-down. */
    private fun dropdown(text: String, enabled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Pal.c("#6B352A"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
        alpha = if (enabled) 1f else 0.5f
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { if (enabled) onClick() }
    }

    private fun button(text: String, outlined: Boolean = false, onClick: () -> Unit) =
        (if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(this)).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun input(hint: String, type: Int, start: String = "") = EditText(this).apply {
        this.hint = hint; inputType = type; setText(start)
        setTextColor(Pal.c("#3E1D16")); setHintTextColor(Pal.c("#A98578"))
    }

    private fun watch(e: EditText, f: (String) -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { f(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    // ---------- the page ----------

    private fun show() {
        content.removeAllViews()
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        tabs.addView(chip("➕ New customer", mode == "new") { mode = "new"; show() })
        tabs.addView(chip("📄 My requests", mode == "mine") { mode = "mine"; show() })
        if (isManager) tabs.addView(chip(if (isAdmin) "✅ All requests" else "✅ Review", mode == "review") { mode = "review"; show() })
        content.addView(tabs)
        when (mode) {
            "new" -> showNew()
            "mine" -> loadList("mine")
            else -> loadList("review")
        }
    }

    // ---------- new customer ----------

    private fun showNew() {
        val form = column()
        form.addView(label("CUSTOMER", 12f, "#6B352A", true))
        val etName = input("Customer name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, name); watch(etName) { name = it }
        val etNid = input("NID number (10, 13 or 17 digits)", InputType.TYPE_CLASS_NUMBER, nid); watch(etNid) { nid = it }
        val etTin = input("TIN number (optional)", InputType.TYPE_CLASS_NUMBER, tin); watch(etTin) { tin = it }
        val etTrade = input("Trade license number", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, trade); watch(etTrade) { trade = it }
        form.addView(etName); form.addView(etNid); form.addView(etTin); form.addView(etTrade)

        form.addView(label("ADDRESS", 12f, "#6B352A", true).apply { setPadding(0, dp(14), 0, 0) })
        form.addView(dropdown("Division: " + (if (division.isEmpty()) "choose ▾" else "$division ▾")) {
            PickerDialog.show(this, "Division", BdLocations.divisions(this)) { picked ->
                if (picked != division) { division = picked; district = ""; thana = "" }
                show()
            }
        })
        form.addView(dropdown("District: " + (if (district.isEmpty()) "choose ▾" else "$district ▾"), division.isNotEmpty()) {
            PickerDialog.show(this, "District of $division", BdLocations.districtsIn(this, division)) { picked ->
                if (picked != district) { district = picked; thana = "" }
                show()
            }
        })
        form.addView(dropdown("Thana: " + (if (thana.isEmpty()) "choose ▾" else "$thana ▾"), district.isNotEmpty()) {
            PickerDialog.show(this, "Thana of $district", BdLocations.thanas(this, district)) { picked -> thana = picked; show() }
        })
        val etDetail = input("Road, village, shop no. (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, detail); watch(etDetail) { detail = it }
        form.addView(etDetail)

        form.addView(label("ATTACHMENTS (NID / trade license copy)", 12f, "#6B352A", true).apply { setPadding(0, dp(14), 0, 0) })
        pictures.forEachIndexed { i, b ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(label("📎 Picture ${i + 1} (${b.size / 1024} KB)", 14f, "#3E1D16").apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(label("✕", 20f, "#B91C1C", true).apply {
                setPadding(dp(12), dp(4), dp(4), dp(4)); isClickable = true
                setOnClickListener { pictures.removeAt(i); show() }
            })
            form.addView(row)
        }
        if (pictures.size < MAX_PICTURES) form.addView(button("📷  Add picture (${pictures.size}/$MAX_PICTURES)", outlined = true) {
            picker.choose { bytes -> pictures.add(bytes); show() }
        })

        form.addView(label("LOCATION OF THE CUSTOMER'S PLACE", 12f, "#6B352A", true).apply { setPadding(0, dp(14), 0, 0) })
        form.addView(label(locText, 14f, "#3E1D16").apply { setPadding(0, dp(4), 0, 0) })
        form.addView(button(if (lat == null) "📍  Capture live location (be at the customer's place)" else "📍  Capture again", outlined = true) { askLocation() })
        content.addView(cardOf(form))
        content.addView(button("✅  Send for approval") { submit() })
    }

    private fun askLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startLocation()
        else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startLocation() {
        locHandle?.cancel()
        lat = null; lng = null
        locText = "📍 " + LiveLocation.waitingText(0)
        if (mode == "new") show()
        locHandle = LiveLocation.start(
            this,
            onProgress = { sec -> if (lat == null && mode == "new") locText = "📍 " + LiveLocation.waitingText(sec) },
            onFail = { msg -> locText = "📍 $msg"; if (mode == "new") show() },
            onFix = { loc ->
                if (isMockLocation(loc)) {
                    locText = "📍 A fake / mock location was detected. Turn off any fake-GPS app, then capture again."
                } else {
                    lat = loc.latitude; lng = loc.longitude; mock = false
                    locText = "📍 Location captured\n" + String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude) + "  (±${loc.accuracy.toInt()} m)"
                }
                if (mode == "new") show()
            }
        )
    }

    private fun submit() {
        val nidDigits = nid.filter { it.isDigit() }
        when {
            name.isBlank() -> { toast("Enter the customer name."); return }
            nidDigits.length != 10 && nidDigits.length != 13 && nidDigits.length != 17 -> { toast("The NID number must be 10, 13 or 17 digits."); return }
            tin.isNotBlank() && tin.filter { it.isDigit() }.length !in 9..15 -> { toast("The TIN must be 9 to 15 digits, or leave it blank."); return }
            trade.isBlank() -> { toast("Enter the trade license number."); return }
            division.isEmpty() || district.isEmpty() || thana.isEmpty() -> { toast("Choose the division, district and thana."); return }
            pictures.isEmpty() -> { toast("Add at least one picture."); return }
            lat == null || lng == null -> { toast("Capture the live location first."); return }
        }
        val arr = JSONArray()
        pictures.forEach { arr.put(Base64.encodeToString(it, Base64.NO_WRAP)) }
        val body = JSONObject().put("action", "customerRequest").put("name", name.trim()).put("nid", nidDigits).put("tin", tin.filter { it.isDigit() })
            .put("tradeLicense", trade.trim()).put("division", division).put("district", district).put("thana", thana)
            .put("addressDetail", detail.trim()).put("lat", lat).put("lng", lng).put("mock", mock).put("pictures", arr)
        toast("Sending…")
        api(body) { res, err ->
            if (res == null) { toast(err ?: "Could not send the request."); return@api }
            toast("Request ${res.optString("id")} sent. " + if (res.optString("status") == "Pending Manager") "Waiting for your line manager." else "Waiting for the admin.")
            name = ""; nid = ""; tin = ""; trade = ""; division = ""; district = ""; thana = ""; detail = ""
            pictures.clear(); lat = null; lng = null; locText = "📍 Location not captured yet"
            mode = "mine"; show()
        }
    }

    // ---------- lists ----------

    private fun loadList(scope: String) {
        content.addView(label("Loading…", 14f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        api(JSONObject().put("action", "customers").put("scope", scope)) { res, err ->
            if (scope != (if (mode == "review") "review" else "mine")) return@api      // the person already switched page
            if (content.childCount > 1) content.removeViewAt(content.childCount - 1)
            if (res == null) { content.addView(label(err ?: "Could not load.", 14f, "#DC2626").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return@api }
            val all = ArrayList<JSONObject>()
            val arr = res.optJSONArray("customers")
            if (arr != null) for (i in 0 until arr.length()) all.add(arr.getJSONObject(i))
            if (scope == "review") {
                val todo = res.optInt("todo")
                val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
                bar.addView(chip("Needs my decision ($todo)", !reviewAll) { reviewAll = false; show() })
                bar.addView(chip("All", reviewAll) { reviewAll = true; show() })
                content.addView(bar)
                val shown = if (reviewAll) all else all.filter { needsMe(it) }
                if (shown.isEmpty()) content.addView(label(if (reviewAll) "No requests yet." else "Nothing is waiting for your decision.", 15f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
                shown.forEach { content.addView(requestCard(it, true)) }
            } else {
                if (all.isEmpty()) content.addView(label("You have not asked for any customer yet.", 15f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
                all.forEach { content.addView(requestCard(it, false)) }
            }
        }
    }

    private fun needsMe(o: JSONObject): Boolean {
        val st = o.optString("status")
        if (isAdmin) return st == "Pending Admin" || st == "Pending Manager"
        return st == "Pending Manager" && o.optString("approver").equals(session.username, true)
    }

    private fun requestCard(o: JSONObject, review: Boolean): View {
        val id = o.optString("id"); val status = o.optString("status"); val code = o.optString("code")
        val col = column()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        if (code.isNotEmpty()) names.addView(label(code, 18f, "#166534", true))
        names.addView(label(o.optString("name"), 16f, "#3E1D16", true))
        names.addView(label("$id · ${o.optString("createdOn")}", 12f, "#8A5F52"))
        if (review) names.addView(label("Asked by ${o.optString("requesterName")} (${o.optString("username")})", 12f, "#8A5F52"))
        top.addView(names); top.addView(statusPill(status))
        col.addView(top)
        fun line(t: String) = col.addView(label(t, 13f, "#3E1D16").apply { setPadding(0, dp(3), 0, 0) })
        line("NID: ${o.optString("nid")}")
        if (o.optString("tin").isNotBlank()) line("TIN: ${o.optString("tin")}")
        line("Trade license: ${o.optString("tradeLicense")}")
        line("📍 ${o.optString("thana")}, ${o.optString("district")}, ${o.optString("division")}" + (if (o.optString("addressDetail").isNotBlank()) "\n${o.optString("addressDetail")}" else ""))
        if (o.optString("managerBy").isNotBlank()) col.addView(label("Manager ${o.optString("managerBy")} · ${o.optString("managerOn")}" + (if (o.optString("managerRemark").isNotBlank()) " — ${o.optString("managerRemark")}" else ""), 12f, "#8A5F52"))
        if (o.optString("adminBy").isNotBlank()) col.addView(label("Admin ${o.optString("adminBy")} · ${o.optString("adminOn")}" + (if (o.optString("adminRemark").isNotBlank()) " — ${o.optString("adminRemark")}" else ""), 12f, "#8A5F52"))

        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val files = o.optJSONArray("files")
        if (files != null) for (i in 0 until files.length()) {
            val fid = files.optString(i)
            links.addView(label("📎 Picture ${i + 1}", 13f, "#8A4333", true).apply {
                setPadding(0, 0, dp(14), 0); isClickable = true
                setOnClickListener { showPhotoDialog(session, fid) }
            })
        }
        val la = o.optDouble("lat"); val ln = o.optDouble("lng")
        links.addView(label("🗺 Map", 13f, "#8A4333", true).apply {
            isClickable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$la,$ln?q=$la,$ln(${Uri.encode(o.optString("name"))})")))
                } catch (e: Exception) { toast("No map app found. Location: $la, $ln") }
            }
        })
        col.addView(links)

        if (!review && (status == "Pending Manager" || status == "Pending Admin")) {
            col.addView(button("Cancel this request", outlined = true) {
                AlertDialog.Builder(this).setTitle("Cancel request $id?").setMessage("It will not go any further.")
                    .setPositiveButton("Cancel request") { _, _ -> decide("customerCancel", id, "", "") }
                    .setNegativeButton("Keep it", null).show()
            })
        }
        if (review && needsMe(o)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            row.addView(MaterialButton(this).apply {
                text = "✅ Approve"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                setOnClickListener {
                    val msg = if (isAdmin) "The customer is approved and gets the next code (RPC-…)." else "It goes to the admin for the final approval."
                    AlertDialog.Builder(this@CustomersActivity).setTitle("Approve ${o.optString("name")}?").setMessage(msg)
                        .setPositiveButton("Approve") { _, _ -> decide("customerDecide", id, "approve", "") }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "✖ Reject"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                setOnClickListener { rejectDialog(id) }
            })
            col.addView(row)
        }
        return cardOf(col)
    }

    private fun rejectDialog(id: String) {
        val et = input("Reason (required)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        val d = AlertDialog.Builder(this).setTitle("Reject request $id?").setView(box)
            .setPositiveButton("Reject", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = et.text.toString().trim()
                if (r.length < 3) { et.error = "Write a short reason"; return@setOnClickListener }
                d.dismiss(); decide("customerDecide", id, "reject", r)
            }
        }
        d.show()
    }

    private fun decide(action: String, id: String, decision: String, remark: String) {
        val body = JSONObject().put("action", action).put("requestId", id)
        if (decision.isNotEmpty()) body.put("decision", decision)
        if (remark.isNotEmpty()) body.put("remark", remark)
        api(body) { res, err ->
            if (res == null) { toast(err ?: "Could not save."); return@api }
            val code = res.optString("code")
            toast(if (code.isNotEmpty()) "Approved. Customer code: $code" else "Done: " + res.optString("status"))
            show()
        }
    }

    companion object { private const val MAX_PICTURES = 3 }
}
