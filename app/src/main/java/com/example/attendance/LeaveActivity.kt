package com.example.attendance

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class LeaveActivity : AppCompatActivity() {
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val nice = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val types = listOf("Casual Leave", "Sick Leave", "Earn Leave", "Paternity Leave")
    private var from: Calendar? = null
    private var to: Calendar? = null
    private lateinit var session: Session
    private lateinit var ddType: MaterialAutoCompleteTextView
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var etReason: EditText
    private lateinit var tvDays: TextView
    private lateinit var tvMsg: TextView
    private lateinit var btnApply: MaterialButton
    private lateinit var list: LinearLayout

    // The leave-application picture (already shrunk to a JPEG), if the person attached one.
    private var applicationJpeg: ByteArray? = null
    private var cameraFile: File? = null
    private lateinit var appPreviewBox: View
    private lateinit var imgApp: ImageView
    private lateinit var tvAppInfo: TextView

    private val pickPicture = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadPicture { contentResolver.openInputStream(uri) }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val f = cameraFile
        if (saved && f != null) loadPicture { FileInputStream(f) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else msg("Camera permission is needed to take a photo. You can choose a picture instead.", false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leave)
        setupHeader("Apply for Leave") { loadList() }
        setupBottomNav(Tab.LEAVE)
        enforceAccess("leave")
        session = Session(this)
        ddType = findViewById(R.id.ddType)
        etFrom = findViewById(R.id.etFrom); etTo = findViewById(R.id.etTo)
        etReason = findViewById(R.id.etReason)
        tvDays = findViewById(R.id.tvDays); tvMsg = findViewById(R.id.tvMsg)
        btnApply = findViewById(R.id.btnApply); list = findViewById(R.id.listLeaves)

        ddType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, types))
        ddType.setText(types[0], false)

        etFrom.setOnClickListener {
            pick(from, null) { c -> from = c; if (to == null || to!!.before(c)) to = c.clone() as Calendar; refresh() }
        }
        etTo.setOnClickListener {
            pick(to ?: from, from) { c -> to = c; refresh() }
        }
        btnApply.setOnClickListener { apply() }
        appPreviewBox = findViewById(R.id.appPreviewBox); imgApp = findViewById(R.id.imgApp); tvAppInfo = findViewById(R.id.tvAppInfo)
        findViewById<View>(R.id.btnPickApp).setOnClickListener { pickPicture.launch("image/*") }
        findViewById<View>(R.id.btnTakeApp).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        findViewById<View>(R.id.btnRemoveApp).setOnClickListener { clearPicture() }
        imgApp.setOnClickListener { applicationJpeg?.let { showPreviewDialog(it) } }
        refresh()
        loadList()
    }

    private fun days(): Int {
        val f = from ?: return 0
        val t = to ?: return 0
        return Math.round((t.timeInMillis - f.timeInMillis) / 86400000.0).toInt() + 1
    }

    private fun pick(start: Calendar?, min: Calendar?, onPick: (Calendar) -> Unit) {
        val c = start ?: Calendar.getInstance()
        val dlg = DatePickerDialog(this, { _, y, m, d ->
            onPick(Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0, 0) })
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        min?.let { dlg.datePicker.minDate = it.timeInMillis - 43200000L }
        dlg.show()
    }

    private fun refresh() {
        etFrom.setText(from?.let { nice.format(it.time) } ?: "")
        etTo.setText(to?.let { nice.format(it.time) } ?: "")
        tvDays.text = "Total: ${days()} day(s)"
    }

    private fun apply() {
        val f = from; val t = to
        if (f == null || t == null) { msg("Please choose From and To dates", false); return }
        // BOTH a reason and a picture of the leave application are required. The server checks this too.
        val reason = etReason.text.toString().trim()
        val pic = applicationJpeg
        if (reason.length < 3) { msg("Please write the reason for your leave.", false); return }
        if (pic == null) { msg("Please attach a picture of your leave application.", false); return }
        btnApply.isEnabled = false
        msg("Submitting...", true)
        val body = JSONObject().put("action", "leave").put("username", session.username)
            .put("type", ddType.text.toString()).put("from", iso.format(f.time)).put("to", iso.format(t.time))
            .put("reason", reason)
        body.put("application", Base64.encodeToString(pic, Base64.NO_WRAP))
        api(body) { res, err ->
            btnApply.isEnabled = true
            if (res != null) {
                msg("✅ Leave applied for ${res.optInt("days")} day(s). Status: Pending", true)
                // Saved copies of Home / Calendar / Leave are now out of date.
                ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username),
                    ResponseCache.leavesKey(session.username))
                from = null; to = null; etReason.setText(""); clearPicture(); refresh(); loadList()
            } else msg("❌ $err", false)
        }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val f = File(dir, "leave_application.jpg")
            cameraFile = f
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
        } catch (e: Exception) {
            msg("The camera could not be opened. Please choose a picture instead.", false)
        }
    }

    /** Reads the picture, turns it upright, shrinks it (longest side 1400 px) and keeps it as a JPEG under about 1 MB. */
    private fun loadPicture(open: () -> InputStream?) {
        msg("Preparing the picture...", true)
        Thread {
            val bytes: ByteArray? = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2800 || bounds.outHeight / sample > 2800) sample *= 2
                val raw = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                if (raw == null) null else {
                    val orientation = open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
                    val deg = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                    val m = Matrix()
                    if (deg != 0) m.postRotate(deg.toFloat())
                    val scale = minOf(1f, 1400f / maxOf(raw.width, raw.height))
                    m.postScale(scale, scale)
                    val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    var quality = 75
                    var out = ByteArray(0)
                    do {
                        val bo = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bo)
                        out = bo.toByteArray()
                        quality -= 12
                    } while (out.size > 1000000 && quality > 25)
                    out
                }
            } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            runOnUiThread {
                if (bytes == null) msg("❌ That picture could not be read. Please try another one.", false)
                else { applicationJpeg = bytes; showPreview(bytes); msg("", true) }
            }
        }.start()
    }

    private fun showPreview(bytes: ByteArray) {
        imgApp.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        tvAppInfo.text = "📎 Leave application attached (${bytes.size / 1024} KB)\nTap the picture to see it bigger."
        appPreviewBox.visibility = View.VISIBLE
    }

    private fun clearPicture() {
        applicationJpeg = null
        imgApp.setImageDrawable(null)
        appPreviewBox.visibility = View.GONE
    }

    private fun showPreviewDialog(bytes: ByteArray) {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#3E1D16"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7).toInt())
            setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setView(img).setPositiveButton("Close", null).show()
    }

    private fun msg(m: String, ok: Boolean) {
        tvMsg.text = m
        tvMsg.setTextColor(Pal.c(if (ok) "#166534" else "#DC2626"))
    }

    private fun fmt(s: String) = try { nice.format(iso.parse(s)!!) } catch (e: Exception) { s }

    private fun loadList() {
        // Balance and requests appear instantly from the last saved copy, then refresh.
        apiCached(
            cacheKey = ResponseCache.leavesKey(session.username),
            body = JSONObject().put("action", "leaves").put("username", session.username),
            maxAgeMs = 24 * HOUR_MS,
            busy = findViewById<View>(R.id.btnRefresh),
            onData = { res -> showLeaves(res) },
            onError = { err, hadSaved ->
                if (!hadSaved) { list.removeAllViews(); list.addView(TextView(this).apply { text = err }) }
            }
        )
    }

    private fun showLeaves(res: JSONObject) {
        list.removeAllViews()
        renderBalance(res)
        val arr = res.getJSONArray("leaves")
        if (arr.length() == 0) list.addView(TextView(this).apply { text = "No leave requests yet."; setTextColor(Pal.c("#8A5F52")) })
        for (o in pendingFirst(arr)) list.addView(card(o))
    }

    /** Four balance tiles in a 2 x 2 grid: type, days left (big), a bar of what is left, and used / pending. */
    private fun renderBalance(res: JSONObject) {
        findViewById<TextView>(R.id.tvBalTitle).text = "Leave Balance · ${res.optString("year")}"
        val box = findViewById<LinearLayout>(R.id.balBox)
        box.removeAllViews()
        val arr = res.optJSONArray("balance") ?: return
        var tQuota = 0; var tUsed = 0; var tRem = 0
        var row: LinearLayout? = null
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val quota = b.optInt("quota"); val used = b.optInt("used"); val pend = b.optInt("pending"); val rem = b.optInt("remaining")
            tQuota += quota; tUsed += used; tRem += rem
            val type = b.optString("type").substringBefore(" ")
            // accent colour, soft tile colour, symbol
            val look = when (type) {
                "Casual" -> Triple("#0D9488", "#E6F7F4", "🌴")
                "Sick" -> Triple("#6B352A", "#FFF6C4", "🤒")
                "Earn" -> Triple("#D97706", "#FEF3D6", "💰")
                else -> Triple("#0284C7", "#E3F3FC", "👶")
            }
            val accent = Pal.c(if (rem <= 0) "#DC2626" else look.first)
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) dp(8) else dp(10) }
                }
                box.addView(row)
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Pal.c(look.second)) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i % 2 == 0) marginEnd = dp(5) else marginStart = dp(5)
                }
            }
            tile.addView(TextView(this).apply {
                text = "${look.third}  $type"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(accent)
            })
            val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            big.addView(TextView(this).apply { text = rem.toString(); textSize = 34f; setTypeface(null, Typeface.BOLD); setTextColor(accent) })
            big.addView(TextView(this).apply {
                text = "  of $quota left"; textSize = 12f; setTextColor(Pal.c("#8A5F52"))
                setPadding(0, 0, 0, dp(7))
            })
            tile.addView(big)
            // the bar: coloured part = days still left
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(Color.argb(40, 0, 0, 0)) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(6) }
            }
            val leftPart = rem.coerceIn(0, maxOf(quota, 1)).toFloat()
            track.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(accent) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, leftPart)
            })
            track.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, maxOf(quota, 1) - leftPart) })
            tile.addView(track)
            tile.addView(TextView(this).apply {
                text = "Used $used" + if (pend > 0) "  ·  Pending $pend" else ""
                textSize = 11f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(6), 0, 0)
            })
            row?.addView(tile)
        }
        findViewById<TextView>(R.id.tvBalTotal).text = "Total: $tUsed used · $tRem remaining of $tQuota days"
    }

    private fun card(o: JSONObject): View {
        val status = o.optString("status", "Pending")
        val (fg, bg) = when (status) {
            "Approved" -> "#166534" to "#DCFCE7"
            "Rejected" -> "#B91C1C" to "#FEE2E2"
            else -> "#B45309" to "#FEF3C7"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        col.addView(TextView(this).apply {
            text = "${o.optString("type")}  •  ${o.optString("days")} day(s)"
            textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Pal.c("#3E1D16"))
        })
        col.addView(TextView(this).apply { text = "${fmt(o.optString("from"))}  →  ${fmt(o.optString("to"))}"; setTextColor(Pal.c("#8A5F52")) })
        if (o.optString("reason").isNotEmpty()) col.addView(TextView(this).apply { text = o.optString("reason"); setTextColor(Pal.c("#8A5F52")) })
        if (o.optString("application").isNotEmpty()) col.addView(applicationLink(session, o.optString("application")))
        col.addView(TextView(this).apply {
            text = status; setTextColor(Pal.c(fg)); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(bg)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        decidedLine(o).takeIf { it.isNotEmpty() }?.let { line ->
            col.addView(TextView(this).apply { text = line; textSize = 13f; setTextColor(Pal.c(fg)); setPadding(0, dp(6), 0, 0) })
        }
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(col)
        }
    }
}

/** Applications waiting for a decision first, then the rest; the order inside each group is kept. */
fun pendingFirst(arr: org.json.JSONArray): List<JSONObject> {
    val all = ArrayList<JSONObject>()
    for (i in 0 until arr.length()) all.add(arr.getJSONObject(i))
    return all.sortedBy { if (it.optString("status", "Pending") == "Pending") 0 else 1 }
}

/** "✅ Approved by Name · 18 Oct 2026, 06:00 PM" (or Rejected). Empty while pending or when nobody is recorded (a hand-edited sheet). */
fun decidedLine(o: JSONObject): String {
    val status = o.optString("status", "Pending")
    val by = o.optString("decidedBy")
    if (status == "Pending" || by.isEmpty()) return ""
    val on = o.optString("decidedOn")
    val nice = try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(on.take(10))
        if (d == null) "" else java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(d) + (if (on.length > 11) ", " + on.substring(11).trim() else "")
    } catch (e: Exception) { "" }
    return (if (status == "Approved") "✅ Approved by " else "❌ Rejected by ") + by + (if (nice.isNotEmpty()) " · $nice" else "")
}
