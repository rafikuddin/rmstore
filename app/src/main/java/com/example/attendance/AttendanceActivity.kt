package com.example.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.location.Geocoder
import android.location.Location
import android.media.ExifInterface
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AttendanceActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var previewView: PreviewView
    private lateinit var imgSelfie: ImageView
    private lateinit var tvLive: TextView
    private lateinit var tvSummary: TextView
    private lateinit var cardSummary: MaterialCardView
    private lateinit var btnCapture: Button
    private lateinit var btnSubmit: Button

    private var imageCapture: ImageCapture? = null
    private var photoFile: File? = null
    private var captureTime: Date? = null
    private var location: Location? = null
    private var address: String = "Address unavailable"
    private var entryHidden = false           // today is already Present / Late / Absent: no camera, no Submit
    private var forDate: String? = null          // set when an admin opened a past day for this person (manual entry)

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it.values.all { granted -> granted }) startCamera()
        else toast("Camera & location permissions are required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { goLogin(); return }
        setContentView(R.layout.activity_attendance)
        previewView = findViewById(R.id.previewView)
        imgSelfie = findViewById(R.id.imgSelfie)
        tvLive = findViewById(R.id.tvLive)
        tvSummary = findViewById(R.id.tvSummary)
        cardSummary = findViewById(R.id.cardSummary)
        forDate = intent.getStringExtra(EXTRA_FOR_DATE)?.takeIf { it.isNotBlank() }
        setupHeader(if (forDate != null) "Manual Attendance" else "Mark Attendance")
        setupBottomNav(Tab.ATTENDANCE)
        enforceAccess("attendance")
        btnCapture = findViewById(R.id.btnCapture)
        btnSubmit = findViewById(R.id.btnSubmit)

        btnCapture.setOnClickListener {
            if (photoFile != null) retake() else captureSelfie()
        }
        btnSubmit.setOnClickListener { submit() }
        if (forDate == null) loadTodayStatus() else findViewById<View>(R.id.atStatusCard).visibility = View.GONE   // a manual day is not "today"

        forDate?.let { d ->
            cardSummary.visibility = View.VISIBLE
            cardSummary.setCardBackgroundColor(Pal.c("#FFF6C4"))
            tvSummary.setTextColor(Pal.c("#6B352A"))
            tvSummary.text = "📝 Manual attendance for $d\nAn admin opened this day for you. Take your selfie as usual."
        }
        if (!entryHidden) ensureCamera()
    }

    /** Starts the camera, asking for the camera and location permissions first when needed. */
    private fun ensureCamera() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            startCamera() else permLauncher.launch(perms)
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            if (entryHidden) { provider.unbindAll(); return@addListener }      // nothing to capture today
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureSelfie() {
        val capture = imageCapture ?: return
        btnCapture.isEnabled = false
        captureTime = Date()
        val file = File(cacheDir, "selfie_${System.currentTimeMillis()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    photoFile = file
                    btnCapture.text = "🔄  Retake"
                    btnCapture.isEnabled = true
                    fetchLocation()                       // starts right away, in parallel with the preview below
                    Thread {
                        val preview = try { loadScaled(file.absolutePath, 800) } catch (e: Exception) { null }
                        runOnUiThread {
                            if (preview != null && photoFile == file) {
                                imgSelfie.setImageBitmap(preview)
                                imgSelfie.visibility = View.VISIBLE
                            }
                        }
                    }.start()
                }
                override fun onError(e: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    toast("Capture failed: ${e.message}")
                }
            })
    }

    /** Loads a photo, applies EXIF rotation and shrinks it so uploads stay small. */
    private fun loadScaled(path: String, max: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > max * 2 || o.outHeight / s > max * 2) s *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        val deg = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val m = Matrix()
        if (deg != 0) m.postRotate(deg.toFloat())
        val sc = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        m.postScale(sc, sc)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun retake() {
        photoFile = null
        imgSelfie.visibility = View.GONE
        btnCapture.text = "📸  Capture Selfie"
        btnSubmit.isEnabled = false
        if (forDate == null) cardSummary.visibility = View.GONE
        tvLive.text = "Date: -\nTime: -\nLocation: -"
        locHandle?.cancel()
        location = null
    }

    private var locHandle: LiveLocation.Handle? = null

    override fun onDestroy() {
        locHandle?.cancel()
        super.onDestroy()
    }

    private fun fetchLocation() {
        locHandle?.cancel()
        location = null
        tvLive.text = "Getting location..."
        locHandle = LiveLocation.start(
            this,
            onProgress = { sec -> if (location == null) tvLive.text = LiveLocation.waitingText(sec) },
            onFail = { tvLive.text = "⚠️ $it\nThen tap Retake and capture again." },
            onFix = { loc ->
                if (isMockLocation(loc)) {
                    tvLive.text = "⚠️ A fake / mock location was detected. Turn off any fake-GPS app, then tap Retake and capture again."
                } else {
                    location = loc
                    Thread {
                        address = try {
                            @Suppress("DEPRECATION")
                            Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                                ?.firstOrNull()?.getAddressLine(0) ?: "Address unavailable"
                        } catch (e: Exception) { "Address unavailable" }
                        runOnUiThread { if (location === loc) { showLive(); btnSubmit.isEnabled = true } }
                    }.start()
                }
            }
        )
    }

    private fun showLive() {
        val t = captureTime ?: return
        val l = location ?: return
        tvLive.text = "Date: ${dateFmt.format(t)}\nTime: ${timeFmt.format(t)}\n" +
            "Location: ${String.format(Locale.US, "%.5f, %.5f", l.latitude, l.longitude)}\n$address"
    }

    private fun submit() {
        val t = captureTime ?: return
        val l = location ?: return
        val file = photoFile ?: return

        btnSubmit.isEnabled = false
        btnCapture.isEnabled = false
        cardSummary.visibility = View.VISIBLE
        cardSummary.setCardBackgroundColor(Pal.c("#E3F2FD"))
        tvSummary.setTextColor(Pal.c("#0D47A1"))
        tvSummary.text = "Submitting..."

        val selfieTimeText = "${dateFmt.format(t)} ${timeFmt.format(t)}"
        val username = session.username
        val addr = address

        // Shrinking and encoding the photo is heavy, so it runs off the screen thread (no freezing).
        // 640px at quality 65 is plenty to recognise a face and uploads much faster on slow mobile data.
        Thread {
            val body: JSONObject = try {
                val out = ByteArrayOutputStream()
                loadScaled(file.absolutePath, 640).compress(Bitmap.CompressFormat.JPEG, 65, out)
                val photo = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                JSONObject()
                    .put("action", "submit")
                    .put("username", username)
                    .put("selfieTime", selfieTimeText)
                    .put("lat", l.latitude).put("lng", l.longitude).put("mock", isMockLocation(l))
                    .put("address", addr)
                    .put("photo", photo)
                    .also { if (forDate != null) it.put("forDate", forDate) }
            } catch (e: Exception) {
                runOnUiThread {
                    btnSubmit.isEnabled = true; btnCapture.isEnabled = true
                    cardSummary.setCardBackgroundColor(Pal.c("#FFEBEE"))
                    tvSummary.setTextColor(Pal.c("#B71C1C"))
                    tvSummary.text = "❌ Couldn't prepare the photo. Please retake the selfie."
                }
                return@Thread
            }
            runOnUiThread { sendAttendance(body, t, l) }
        }.start()
    }

    private fun sendAttendance(body: JSONObject, t: Date, l: Location) {
        api(body) { res, err ->
            btnCapture.isEnabled = true
            if (res != null) {
                // Saved copies of Home and Calendar are now out of date.
                ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username))
                Reminders.clearSelf(this)   // the "mark your attendance" reminder is no longer needed
                photoFile?.delete()   // the selfie now lives on the server; don't keep a copy on the phone
                val late = res.optBoolean("late")
                cardSummary.setCardBackgroundColor(Pal.c(if (late) "#FFEDD5" else "#DCFCE7"))
                tvSummary.setTextColor(Pal.c(if (late) "#9A3412" else "#166534"))
                tvSummary.text = "${if (late) "⏰ Late Present" else "✅ Attendance Submitted"}${if (res.optBoolean("manual")) " (manual)" else ""}\n\nDate: ${res.optString("date")}\nTime: ${res.optString("time")}\n" +
                    "Selfie time: ${dateFmt.format(t)} ${timeFmt.format(t)}\n" +
                    "Location: ${String.format(Locale.US, "%.5f, %.5f", l.latitude, l.longitude)}\n$address"
                loadTodayStatus()      // the card on top now shows the new attendance
                popup(if (late) "⏰ Late Present" else "✅ Attendance submitted",
                    (if (late) "Your attendance was recorded as late.\n\n" else "Your attendance was submitted successfully.\n\n") +
                    "Date: ${res.optString("date")}\nTime: ${res.optString("time")}")
            } else {
                val already = err?.contains("already", ignoreCase = true) == true
                btnSubmit.isEnabled = !already          // a second submit for the same day can never work
                if (already) {                           // the saved copy of Home was out of date: refresh the status card, which then hides the form
                    ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username))
                    loadTodayStatus()
                }
                cardSummary.setCardBackgroundColor(Pal.c(if (already) "#E3F2FD" else "#FFEBEE"))
                tvSummary.setTextColor(Pal.c(if (already) "#0D47A1" else "#B71C1C"))
                tvSummary.text = if (already) "ℹ️ $err" else "❌ $err"
                if (already) popup("ℹ️ Already submitted", err ?: "Your attendance for today is already submitted.")
            }
        }
    }

    /** Today's Status (same card and rules as on Home): status, present/absent count of the 26th–25th period, selfie and location. */
    private fun loadTodayStatus() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Calendar.getInstance()
        val today = fmt.format(now.time)
        val isFridayToday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val isAfterCutoff = now.get(Calendar.HOUR_OF_DAY) >= 18          // matches Code.gs ABSENT_AFTER_HOUR
        val periodStart = (now.clone() as Calendar).apply {
            if (get(Calendar.DAY_OF_MONTH) < 26) add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 26)
        }
        val periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 25) }
        val startKey = fmt.format(periodStart.time)
        val endKey = fmt.format(periodEnd.time)
        val tvStatus = findViewById<TextView>(R.id.atStatus)
        val tvMonth = findViewById<TextView>(R.id.atMonth)

        val render: (JSONObject) -> Unit = render@{ res ->
            var present = 0
            var todayTime: String? = null
            var todayLate = false
            var todayRecord: JSONObject? = null
            val presentDays = HashSet<String>()
            val recs = res.optJSONArray("records") ?: return@render
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i); val d = r.getString("date")
                if (d == today) { todayTime = r.optString("time"); todayLate = r.optBoolean("late"); todayRecord = r }
                if (d >= startKey && d <= endKey) { present++; presentDays.add(d) }
            }
            val leaves = res.optJSONArray("leaves")
            fun leaveOn(dateKey: String): String? {
                if (leaves != null) for (i in 0 until leaves.length()) {
                    val l = leaves.getJSONObject(i)
                    if (l.optString("status") == "Approved" && dateKey >= l.getString("from") && dateKey <= l.getString("to")) return l.optString("type")
                }
                return null
            }
            val holidays = HashMap<String, String>()
            res.optJSONArray("holidays")?.let { h -> for (i in 0 until h.length()) { val o = h.getJSONObject(i); holidays[o.optString("date")] = o.optString("name") } }
            var absent = 0
            val cursor = periodStart.clone() as Calendar
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            while (!cursor.after(yesterday)) {
                val k = fmt.format(cursor.time)
                if (cursor.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY && !holidays.containsKey(k) && !presentDays.contains(k) && leaveOn(k) == null) absent++
                cursor.add(Calendar.DAY_OF_MONTH, 1)
            }
            val leaveType = leaveOn(today)
            val holidayToday = holidays[today]
            when {
                todayTime != null && todayLate -> { tvStatus.text = "⏰ Late Present · $todayTime"; tvStatus.setTextColor(Pal.c("#D97706")) }
                todayTime != null -> { tvStatus.text = "✅ Present · $todayTime"; tvStatus.setTextColor(Pal.c("#16A34A")) }
                leaveType != null -> { tvStatus.text = "🌴 On $leaveType"; tvStatus.setTextColor(Pal.c("#D97706")) }
                holidayToday != null -> { tvStatus.text = "🎉 Holiday · $holidayToday"; tvStatus.setTextColor(Pal.c("#7C3AED")) }
                isFridayToday -> { tvStatus.text = "🗓️ Weekly Off (Friday)"; tvStatus.setTextColor(Pal.c("#8A5F52")) }
                isAfterCutoff -> { tvStatus.text = "❌ Absent today"; tvStatus.setTextColor(Pal.c("#B91C1C")) }
                else -> { tvStatus.text = "⏳ Not marked yet"; tvStatus.setTextColor(Pal.c("#DC2626")) }
            }
            tvMonth.text = "Present: $present day(s) (26th–25th)" + (if (absent > 0) "  ·  Absent: $absent" else "")
            showTodayDetail(todayRecord)
            // Already submitted (Present / Late), or marked Absent: there is nothing left to capture or submit today.
            val absentToday = todayTime == null && leaveType == null && holidayToday == null && !isFridayToday && isAfterCutoff
            setEntryVisible(!(todayTime != null || absentToday))
        }

        apiCached(
            cacheKey = ResponseCache.homeKey(session.username),
            body = JSONObject().put("action", "history").put("username", session.username)
                .put("from", startKey).put("nights", true)
                .put("appVersion", AppUpdate.installedName(this)).put("appCode", AppUpdate.installedCode(this)),
            maxAgeMs = 24 * HOUR_MS,
            onData = { res -> render(res) },
            onError = { err, hadSaved -> if (!hadSaved) { tvStatus.text = "Offline"; tvMonth.text = err ?: "" } }
        )
    }

    /** Camera, Capture Selfie, the live date/time/location card and Submit are only shown while attendance can still be marked. */
    private fun setEntryVisible(show: Boolean) {
        if (forDate != null) return                       // a manual day opened by an admin always shows the form
        val wasHidden = entryHidden
        entryHidden = !show
        if (show && wasHidden) ensureCamera()              // a saved copy said "done", the fresh answer says it is not
        val v = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardCamera).visibility = v
        findViewById<View>(R.id.cardLive).visibility = v
        btnCapture.visibility = v
        btnSubmit.visibility = v
        if (!show) {
            try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { }
        }
    }

    /** Small round selfie, address and coordinates of today's attendance; back to the pin until it is marked. */
    private fun showTodayDetail(rec: JSONObject?) {
        val box = findViewById<View>(R.id.atDetail)
        val mini = findViewById<ImageView>(R.id.atMini)
        val pin = findViewById<View>(R.id.atPin)
        val miniCard = findViewById<MaterialCardView>(R.id.atMiniCard)
        miniCard.clipToOutline = true
        if (rec == null) {
            box.visibility = View.GONE
            mini.visibility = View.GONE; pin.visibility = View.VISIBLE
            miniCard.setOnClickListener(null); miniCard.isClickable = false
            return
        }
        box.visibility = View.VISIBLE
        val selfie = rec.optString("selfie")
        if (selfie.isNotEmpty()) {
            pin.visibility = View.GONE; mini.visibility = View.VISIBLE
            PhotoLoader.load(this, session, selfie, mini, 200)
            miniCard.isClickable = true
            miniCard.setOnClickListener {
                val big = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt())
                }
                PhotoLoader.load(this, session, selfie, big, 640)
                androidx.appcompat.app.AlertDialog.Builder(this).setView(big).setPositiveButton("Close", null).show()
            }
        } else { mini.visibility = View.GONE; pin.visibility = View.VISIBLE }
        val addr = rec.optString("address")
        val coords = "${rec.optString("lat")}, ${rec.optString("lng")}"
        findViewById<TextView>(R.id.atLoc).text = "📍 " + (if (addr.isNotEmpty() && addr != "Address unavailable") "$addr\n$coords" else coords)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** A small pop-up after submitting: success, or "already submitted". */
    private fun popup(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
        // Closes by itself after 5 seconds (OK closes it sooner).
        val closer = Runnable { if (dialog.isShowing && !isFinishing && !isDestroyed) dialog.dismiss() }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(closer, 5000)
        dialog.setOnDismissListener { handler.removeCallbacks(closer) }
    }
}
