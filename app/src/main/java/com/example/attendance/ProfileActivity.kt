package com.example.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * "My Profile": change the profile photo, and keep phone numbers, email, emergency contact, bike number
 * and (for managers) laptop model. Everyone edits only their own profile; the server enforces that.
 */
class ProfileActivity : AppCompatActivity() {
    companion object {
        /** Set by the Admin Panel: the username whose profile the admin is viewing / editing. Absent = the signed-in person's own profile. */
        const val EXTRA_USER = "targetUser"
        const val EXTRA_NAME = "targetName"
        /** A line manager (allowed by an admin) looking at the profile of someone below them: nothing can be changed. */
        const val EXTRA_VIEW_ONLY = "viewOnly"
    }
    private var viewOnly = false

    private var target: String? = null               // null = my own profile; otherwise an admin is editing this person
    private lateinit var session: Session
    private lateinit var imgProfile: ImageView
    private lateinit var tvInitial: TextView
    private lateinit var tvPhotoMsg: TextView
    private lateinit var tvMsg: TextView
    private lateinit var etOfficial: EditText
    private lateinit var etPersonal: EditText
    private lateinit var etEmail: EditText
    private lateinit var etEmergency: EditText
    private lateinit var ddRelation: MaterialAutoCompleteTextView
    private lateinit var tilOther: TextInputLayout
    private lateinit var etOther: EditText
    private lateinit var etBike: EditText
    private lateinit var etBaseName: EditText
    private lateinit var etBaseCode: EditText
    private lateinit var ddTshirt: MaterialAutoCompleteTextView
    private lateinit var tilLaptop: TextInputLayout
    private lateinit var etLaptop: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnTake: View
    private lateinit var btnPick: View

    private var relations: List<String> = listOf("Wife", "Son", "Brother", "Mother", "Father", "Daughter")
    private var canLaptop = false
    private var bases: List<Pair<String, String>> = emptyList()      // code to name, from the server's Bases list
    private var baseCode = ""                                        // the chosen Base Code ("" = none)
    private var tshirtSizes: List<String> = listOf("S", "M", "L", "XL", "XXL", "2XL")
    private var cameraFile: File? = null
    private var loaded = false          // the form is only saved after the server's copy has been shown, so nothing is overwritten by blanks

    private val pickPicture = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadPhoto { contentResolver.openInputStream(uri) }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val f = cameraFile
        if (saved && f != null) uploadPhoto { FileInputStream(f) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else photoMsg("Camera permission is needed to take a photo. You can choose a picture instead.", false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        target = intent.getStringExtra(EXTRA_USER)?.takeIf { it.isNotBlank() }
        viewOnly = target != null && intent.getBooleanExtra(EXTRA_VIEW_ONLY, false)
        setupHeader(if (target == null) "My Profile" else if (viewOnly) "Profile" else "Edit Profile") { loadProfile() }
        setupBottomNav(null)
        if (target == null) enforceAccess("profile")
        session = Session(this)
        val shownName = if (target == null) session.name else (intent.getStringExtra(EXTRA_NAME) ?: target!!)
        imgProfile = findViewById(R.id.imgProfile); tvInitial = findViewById(R.id.tvPhotoInitial)
        tvPhotoMsg = findViewById(R.id.tvPhotoMsg); tvMsg = findViewById(R.id.tvProfileMsg)
        etOfficial = findViewById(R.id.etOfficial); etPersonal = findViewById(R.id.etPersonal); etEmail = findViewById(R.id.etEmail)
        etEmergency = findViewById(R.id.etEmergency); ddRelation = findViewById(R.id.ddRelation)
        tilOther = findViewById(R.id.tilOther); etOther = findViewById(R.id.etOther)
        etBaseName = findViewById(R.id.etBaseName); etBaseCode = findViewById(R.id.etBaseCode); ddTshirt = findViewById(R.id.ddTshirt)
        etBaseName.setOnClickListener { chooseBase() }
        etBike = findViewById(R.id.etBike); tilLaptop = findViewById(R.id.tilLaptop); etLaptop = findViewById(R.id.etLaptop)
        btnSave = findViewById(R.id.btnSaveProfile); btnTake = findViewById(R.id.btnTakePhoto); btnPick = findViewById(R.id.btnPickPhoto)

        findViewById<TextView>(R.id.tvProfileName).text = shownName
        findViewById<TextView>(R.id.tvProfileUser).text = if (target == null) "ID: ${session.username}" else if (viewOnly) "ID: $target  ·  view only" else "ID: $target  ·  editing as admin"
        tvInitial.text = shownName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        setRelationChoices()
        setTshirtChoices()
        ddRelation.setOnItemClickListener { _, _, _, _ -> updateOtherBox() }
        btnSave.setOnClickListener { save() }
        btnPick.setOnClickListener { pickPicture.launch("image/*") }
        btnTake.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        if (viewOnly) {                                   // read only: no typing, no photo change, no save
            listOf<View>(etOfficial, etPersonal, etEmail, etEmergency, ddRelation, etOther, etBike, etLaptop, ddTshirt, etBaseName).forEach { it.isEnabled = false }
            btnSave.visibility = View.GONE; btnTake.visibility = View.GONE; btnPick.visibility = View.GONE
        }
        loadProfile()
    }

    /** Own profile: profile / saveProfile / saveProfilePhoto. Admin editing someone: adminProfile / adminSaveProfile / adminSaveProfilePhoto + the username. */
    private fun request(action: String): JSONObject {
        val t = target ?: return JSONObject().put("action", action)
        if (viewOnly) return JSONObject().put("action", "team" + action.replaceFirstChar { it.uppercaseChar() }).put("targetUsername", t)
        return JSONObject().put("action", "admin" + action.replaceFirstChar { it.uppercaseChar() }).put("targetUsername", t)
    }

    /** Own profile: a normal call. An admin saving someone else's profile or photo is asked for their own password first (see AdminElev.kt). */
    private fun send(body: JSONObject, onCancel: () -> Unit, done: (JSONObject?, String?) -> Unit) {
        if (target != null && !viewOnly) adminApi(body, onCancel, done) else api(body, done)
    }

    private fun setRelationChoices() {
        ddRelation.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, relations + "Other"))
    }

    private fun updateOtherBox() {
        tilOther.visibility = if (ddRelation.text.toString() == "Other") View.VISIBLE else View.GONE
    }

    private fun setTshirtChoices() {
        ddTshirt.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, tshirtSizes))
    }

    /** Base Name: a searchable list; choosing one fills in the Base Code. Names used by several bases show their code too. */
    private fun chooseBase() {
        if (!loaded) { msg("Please wait until your profile has loaded.", false); return }
        if (bases.isEmpty()) { msg("The base list is not available. Pull to refresh and try again.", false); return }
        val dup = bases.groupingBy { it.second }.eachCount()
        val none = "— No base —"
        val labels = ArrayList<String>(); val codes = ArrayList<String>()
        labels.add(none); codes.add("")
        bases.forEach { (code, name) -> labels.add(if ((dup[name] ?: 0) > 1) "$name  ($code)" else name); codes.add(code) }
        PickerDialog.show(this, "Choose the Base Name", labels) { picked ->
            val i = labels.indexOf(picked)
            if (i >= 0) showBase(codes[i])
        }
    }

    private fun showBase(code: String) {
        baseCode = code
        val name = bases.firstOrNull { it.first == code }?.second
        etBaseName.setText(name ?: "")
        etBaseCode.setText(if (name != null) code else "")
    }

    private fun loadProfile() {
        msg("Loading...", true)
        api(request("profile")) { res, err ->
            if (res == null) { msg("❌ $err", false); return@api }
            msg("", true)
            val list = res.optJSONArray("relations")
            if (list != null && list.length() > 0) relations = (0 until list.length()).map { list.getString(it) }
            setRelationChoices()
            canLaptop = res.optBoolean("canLaptop")
            val bl = res.optJSONArray("bases")
            if (bl != null) bases = (0 until bl.length()).map { val b = bl.getJSONArray(it); Pair(b.getString(0), b.getString(1)) }
            val tl = res.optJSONArray("tshirtSizes")
            if (tl != null && tl.length() > 0) tshirtSizes = (0 until tl.length()).map { tl.getString(it) }
            setTshirtChoices()
            tilLaptop.visibility = if (canLaptop) View.VISIBLE else View.GONE
            val p = res.getJSONObject("profile")
            etOfficial.setText(p.optString("officialPhone")); etPersonal.setText(p.optString("personalPhone"))
            etEmail.setText(p.optString("email")); etEmergency.setText(p.optString("emergencyPhone"))
            etBike.setText(p.optString("bike")); etLaptop.setText(p.optString("laptop"))
            val savedCode = p.optString("baseCode")
            if (savedCode.isNotEmpty() && bases.none { it.first == savedCode }) {       // a base removed from the list since: show what was saved
                baseCode = savedCode; etBaseName.setText(p.optString("baseName")); etBaseCode.setText(savedCode)
            } else showBase(savedCode)
            ddTshirt.setText(p.optString("tshirt"), false)
            val rel = p.optString("relation")
            when {
                rel.isEmpty() -> { ddRelation.setText("", false); etOther.setText("") }
                relations.contains(rel) -> { ddRelation.setText(rel, false); etOther.setText("") }
                else -> { ddRelation.setText("Other", false); etOther.setText(rel) }     // a relation the person wrote themselves
            }
            updateOtherBox()
            val photo = p.optString("photo")
            if (photo.isNotEmpty()) {
                imgProfile.visibility = View.VISIBLE
                PhotoLoader.load(this, session, photo, imgProfile, 400)
            } else imgProfile.visibility = View.GONE
            loaded = true
        }
    }

    private fun save() {
        if (!loaded) { msg("Please wait until your profile has loaded.", false); return }
        val choice = ddRelation.text.toString().trim()
        val relation = if (choice == "Other") etOther.text.toString().trim() else choice
        val emergency = etEmergency.text.toString().trim()
        if (choice == "Other" && relation.length < 2) { msg("Please write the relation in the box.", false); return }
        if (emergency.isNotEmpty() && relation.isEmpty()) { msg("Please choose who the emergency contact is.", false); return }
        if (emergency.isEmpty() && relation.isNotEmpty()) { msg("Please enter the emergency phone number.", false); return }
        val body = request("saveProfile")
            .put("officialPhone", etOfficial.text.toString().trim()).put("personalPhone", etPersonal.text.toString().trim())
            .put("email", etEmail.text.toString().trim()).put("emergencyPhone", emergency).put("relation", relation)
            .put("bike", etBike.text.toString().trim()).put("laptop", etLaptop.text.toString().trim())
            .put("baseCode", baseCode).put("tshirt", ddTshirt.text.toString().trim())
        btnSave.isEnabled = false
        msg("Saving...", true)
        send(body, { btnSave.isEnabled = true; msg("", true) }) { res, err ->
            btnSave.isEnabled = true
            if (res != null) msg("✅ Profile saved", true) else msg("❌ $err", false)
        }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val f = File(dir, "profile_photo.jpg")
            cameraFile = f
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
        } catch (e: Exception) {
            photoMsg("The camera could not be opened. Please choose a picture instead.", false)
        }
    }

    /** Reads the picture, turns it upright, shrinks it (longest side 720 px, JPEG under about 400 KB) and sends it. */
    private fun uploadPhoto(open: () -> InputStream?) {
        photoMsg("Preparing the picture...", true)
        setPhotoButtons(false)
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
                    val scale = minOf(1f, 720f / maxOf(raw.width, raw.height))
                    m.postScale(scale, scale)
                    val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    var quality = 80
                    var out: ByteArray
                    do {
                        val bo = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bo)
                        out = bo.toByteArray()
                        quality -= 12
                    } while (out.size > 400000 && quality > 25)
                    out
                }
            } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            runOnUiThread {
                if (bytes == null) {
                    setPhotoButtons(true); photoMsg("❌ That picture could not be read. Please try another one.", false)
                } else {
                    photoMsg("Uploading...", true)
                    val body = request("saveProfilePhoto").put("photo", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    send(body, { setPhotoButtons(true); photoMsg("", true) }) { res, err ->
                        setPhotoButtons(true)
                        if (res != null) {
                            imgProfile.visibility = View.VISIBLE
                            imgProfile.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                            photoMsg("✅ Photo updated", true)
                            if (target == null) ResponseCache.remove(this, ResponseCache.homeKey(session.username))   // Home shows the new photo next time
                        } else photoMsg("❌ $err", false)
                    }
                }
            }
        }.start()
    }

    private fun setPhotoButtons(on: Boolean) {
        btnTake.isEnabled = on; btnPick.isEnabled = on
        btnTake.alpha = if (on) 1f else 0.5f; btnPick.alpha = if (on) 1f else 0.5f
    }

    private fun photoMsg(m: String, ok: Boolean) {
        tvPhotoMsg.text = m
        tvPhotoMsg.setTextColor(Pal.c(if (ok) "#166534" else "#DC2626"))
    }

    private fun msg(m: String, ok: Boolean) {
        tvMsg.text = m
        tvMsg.setTextColor(Pal.c(if (ok) "#166534" else "#DC2626"))
    }
}
