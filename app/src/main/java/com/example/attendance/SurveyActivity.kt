package com.example.attendance

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Surveys. The list shows the surveys made for you; tap one to answer it (once). The questions can be short answer, paragraph,
 * single choice, multiple choice, dropdown, rating, yes / no, number, date or a picture attachment, whatever the admin chose.
 * Admins also get a button to create surveys and read the results (SurveyAdminActivity).
 */
class SurveyActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private val picker = PicturePicker(this)
    private var inForm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Surveys") { if (!inForm) load() }
        setupBottomNav(null)
        enforceAccess("survey")
        content = findViewById(R.id.noticeList)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inForm) load() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        load()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }

    private fun cardOf(inner: View, onClick: (() -> Unit)? = null) = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); setCardBackgroundColor(Pal.card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        addView(inner)
    }

    private fun chip(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(fg))
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Pal.c(bg)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
    }

    private fun niceDate(iso: String): String {
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.take(10))
            if (d == null) iso else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d)
        } catch (e: Exception) { iso }
    }

    // ---------------------------------------------------------------- the list
    private fun load() {
        inForm = false
        content.removeAllViews()
        content.addView(label("Loading…", 14f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        api(JSONObject().put("action", "surveyList")) { res, err ->
            content.removeAllViews()
            if (Access.canManageSurveys(this)) {
                val a = column()
                a.addView(label("⚙️  Manage surveys", 15f, "#6B352A", true))
                a.addView(label("Create a survey, see the results, download Excel", 12f, "#8A5F52"))
                content.addView(cardOf(a) { startActivity(android.content.Intent(this, SurveyAdminActivity::class.java)) })
            }
            if (res == null) { content.addView(label(err ?: "Could not load.", 14f, "#DC2626").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return@api }
            val list = res.optJSONArray("surveys") ?: JSONArray()
            if (list.length() == 0) {
                val c = column()
                c.addView(label("📋  No surveys right now", 16f, "#3E1D16", true))
                c.addView(label("When your admin sends you a survey it will appear here.", 14f, "#8A5F52").apply { setPadding(0, dp(6), 0, 0) })
                content.addView(cardOf(c)); return@api
            }
            for (i in 0 until list.length()) content.addView(surveyCard(list.getJSONObject(i)))
        }
    }

    private fun surveyCard(o: JSONObject): View {
        val c = column()
        c.addView(label(o.optString("title"), 16f, "#3E1D16", true))
        val d = o.optString("description")
        if (d.isNotBlank()) c.addView(label(d, 13f, "#8A5F52").apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val answered = o.optBoolean("answered")
        val state = o.optString("state")
        if (answered) chips.addView(chip("✅ Answered ${niceDate(o.optString("answeredOn"))}", "#166534", "#DCFCE7"))
        else chips.addView(chip("NEW", "#FFFFFF", "#6B352A").apply { (background as GradientDrawable).setColor(Color.parseColor("#6B352A")) })
        if (state == "closed") chips.addView(chip("Closed", "#4A241C", "#EBDDA0"))
        if (o.optString("deadline").isNotBlank() && !answered) chips.addView(chip("⏰ ${niceDate(o.optString("deadline"))}", "#B45309", "#FEF3C7"))
        c.addView(chips)
        c.addView(label("${o.optInt("questions")} question${if (o.optInt("questions") == 1) "" else "s"}" + (if (o.optBoolean("anonymous")) "  ·  🔒 Anonymous" else ""), 12f, "#A98578").apply { setPadding(0, dp(6), 0, 0) })
        return cardOf(c) { openSurvey(o.optString("id")) }
    }

    private fun openSurvey(id: String) {
        api(JSONObject().put("action", "surveyGet").put("id", id)) { res, err ->
            if (res == null) { toast(err ?: "Could not open this survey."); load(); return@api }
            inForm = true
            content.removeAllViews()
            if (res.optBoolean("answered")) showAnswered(res) else showForm(res)
        }
    }

    private fun header(res: JSONObject): View {
        val c = column()
        c.addView(label(res.optString("title"), 20f, "#3E1D16", true))
        if (res.optString("description").isNotBlank()) c.addView(label(res.optString("description"), 14f, "#8A5F52").apply { setPadding(0, dp(6), 0, 0) })
        val img = res.optString("image")
        if (img.isNotEmpty()) {
            val v = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply { topMargin = dp(10) }
                setOnClickListener { showPhotoDialog(session, img) }
            }
            PhotoLoader.load(this, session, img, v, 640)
            c.addView(v)
        }
        val notes = ArrayList<String>()
        if (res.optString("deadline").isNotBlank()) notes.add("⏰ Please answer by ${niceDate(res.optString("deadline"))}")
        if (res.optBoolean("anonymous")) notes.add("🔒 Anonymous: your name is not saved with your answers.")
        if (notes.isNotEmpty()) c.addView(label(notes.joinToString("\n"), 12f, "#B45309").apply { setPadding(0, dp(8), 0, 0) })
        return cardOf(c)
    }

    // ---------------------------------------------------------------- answering
    private fun showForm(res: JSONObject) {
        content.addView(header(res))
        val qs = res.getJSONArray("questions")
        val getters = ArrayList<Pair<JSONObject, () -> Any?>>()
        for (i in 0 until qs.length()) {
            val q = qs.getJSONObject(i)
            val c = column()
            c.addView(label("${i + 1}. ${q.optString("q")}" + (if (q.optBoolean("req")) "  *" else ""), 15f, "#3E1D16", true))
            val (view, getter) = control(q)
            view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            c.addView(view)
            getters.add(q to getter)
            content.addView(cardOf(c))
        }
        val msg = label("", 13f, "#DC2626").apply { setPadding(dp(6), 0, dp(6), dp(6)) }
        val send = MaterialButton(this).apply {
            text = "✅  Submit"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        send.setOnClickListener {
            val answers = JSONObject()
            for ((q, g) in getters) {
                val v = g()
                val empty = v == null || (v is String && v.isBlank()) || (v is JSONArray && v.length() == 0)
                if (empty) {
                    if (q.optBoolean("req")) { msg.text = "Please answer: ${q.optString("q")}"; return@setOnClickListener }
                    continue
                }
                answers.put(q.optString("id"), v)
            }
            if (answers.length() == 0) { msg.text = "Please answer at least one question."; return@setOnClickListener }
            msg.text = ""
            send.isEnabled = false
            api(JSONObject().put("action", "surveySubmit").put("id", res.optString("id")).put("answers", answers)) { r, err ->
                send.isEnabled = true
                if (r == null) { msg.text = err ?: "Could not send. Please try again."; return@api }
                AlertDialog.Builder(this).setTitle("Thank you").setMessage("Your answers were sent.").setPositiveButton("OK") { _, _ -> load() }
                    .setOnCancelListener { load() }.show()
            }
        }
        content.addView(msg)
        content.addView(send)
    }

    /** The input for one question and a function that reads what the person chose (null = nothing). */
    private fun control(q: JSONObject): Pair<View, () -> Any?> {
        val opts = ArrayList<String>()
        q.optJSONArray("o")?.let { for (i in 0 until it.length()) opts.add(it.optString(i)) }
        return when (q.optString("t")) {
            "text" -> { val e = EditText(this).apply { hint = "Your answer"; inputType = InputType.TYPE_CLASS_TEXT; filters = arrayOf(InputFilter.LengthFilter(300)) }; e to { e.text.toString().trim() } }
            "para" -> { val e = EditText(this).apply { hint = "Your answer"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; gravity = Gravity.TOP; filters = arrayOf(InputFilter.LengthFilter(1000)) }; e to { e.text.toString().trim() } }
            "number" -> { val e = EditText(this).apply { hint = "Number"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }; e to { e.text.toString().trim() } }
            "date" -> {
                var value = ""
                val t = chipButton("📅  Choose a date")
                t.setOnClickListener {
                    val c = Calendar.getInstance()
                    DatePickerDialog(this, { _, y, m, d ->
                        value = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d); t.text = "📅  ${niceDate(value)}"
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }
                t to { value }
            }
            "single" -> {
                val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                opts.forEach { o -> g.addView(RadioButton(this).apply { text = o; textSize = 15f; id = View.generateViewId() }) }
                g to { val id = g.checkedRadioButtonId; if (id == -1) null else g.findViewById<RadioButton>(id).text.toString() }
            }
            "drop" -> {
                val s = Spinner(this)
                s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Choose…") + opts)
                s to { if (s.selectedItemPosition > 0) opts[s.selectedItemPosition - 1] else null }
            }
            "multi" -> {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val boxes = opts.map { o -> CheckBox(this).apply { text = o; textSize = 15f }.also { box.addView(it) } }
                box to { val a = JSONArray(); boxes.filter { it.isChecked }.forEach { a.put(it.text.toString()) }; a }
            }
            "rating" -> {
                val max = if (q.optInt("max") == 10) 10 else 5
                var picked = 0
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val cells = (1..max).map { n ->
                    TextView(this).apply {
                        text = n.toString(); textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) }
                    }.also { row.addView(it) }
                }
                fun paint() = cells.forEachIndexed { i, c ->
                    val on = i + 1 <= picked
                    c.setTextColor(if (on) Color.WHITE else Pal.c("#8A5F52"))
                    c.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (on) Color.parseColor("#6B352A") else Pal.c("#F0E3A6")) }
                }
                cells.forEachIndexed { i, c -> c.setOnClickListener { picked = i + 1; paint() } }
                paint()
                val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                wrap.addView(row)
                wrap.addView(label("1 = lowest, $max = highest", 11f, "#A98578").apply { setPadding(0, dp(4), 0, 0) })
                wrap to { if (picked == 0) null else picked }
            }
            "yesno" -> {
                var picked = ""
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val cells = listOf("Yes", "No").map { n ->
                    TextView(this).apply {
                        text = n; textSize = 15f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
                    }.also { row.addView(it) }
                }
                fun paint() = cells.forEach { c ->
                    val on = c.text.toString() == picked
                    c.setTextColor(if (on) Color.WHITE else Pal.c("#8A5F52"))
                    c.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (on) Color.parseColor("#6B352A") else Pal.c("#F0E3A6")) }
                }
                cells.forEach { c -> c.setOnClickListener { picked = c.text.toString(); paint() } }
                paint()
                row to { if (picked.isEmpty()) null else picked }
            }
            "photo" -> {
                var jpeg: ByteArray? = null
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { bottomMargin = dp(8) }
                }
                val info = label("", 12f, "#8A5F52")
                val attach = chipButton("📎  Attach a picture")
                val remove = chipButton("✖  Remove").apply { visibility = View.GONE }
                attach.setOnClickListener {
                    picker.choose { bytes ->
                        jpeg = bytes
                        preview.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)); preview.visibility = View.VISIBLE
                        info.text = "Attached (${bytes.size / 1024} KB)"; attach.text = "📎  Change picture"; remove.visibility = View.VISIBLE
                    }
                }
                remove.setOnClickListener { jpeg = null; preview.visibility = View.GONE; info.text = ""; attach.text = "📎  Attach a picture"; remove.visibility = View.GONE }
                box.addView(preview); box.addView(info); box.addView(attach); box.addView(remove)
                box to { jpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) } }
            }
            else -> label("This kind of question is not supported by this version of the app. Please update the app.", 13f, "#DC2626") to { null }
        }
    }

    private fun chipButton(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Pal.c("#6B352A")); setTypeface(null, Typeface.BOLD)
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    // ---------------------------------------------------------------- what I answered
    private fun showAnswered(res: JSONObject) {
        content.addView(header(res))
        content.addView(label("✅ You answered on ${niceDate(res.optString("answeredOn"))}. Thank you!", 13f, "#166534", true).apply { setPadding(dp(6), 0, dp(6), dp(8)) })
        val qs = res.getJSONArray("questions")
        val ans = res.optJSONObject("answers") ?: JSONObject()
        for (i in 0 until qs.length()) {
            val q = qs.getJSONObject(i)
            val c = column()
            c.addView(label("${i + 1}. ${q.optString("q")}", 14f, "#8A5F52", true))
            val v = ans.opt(q.optString("id"))
            if (v == null || v == JSONObject.NULL) c.addView(label("— not answered —", 14f, "#A98578").apply { setPadding(0, dp(4), 0, 0) })
            else if (q.optString("t") == "photo") {
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(6) }
                    setOnClickListener { showPhotoDialog(session, v.toString()) }
                }
                PhotoLoader.load(this, session, v.toString(), img, 640)
                c.addView(img)
            } else {
                val text = if (v is JSONArray) (0 until v.length()).joinToString(", ") { v.optString(it) } else if (q.optString("t") == "date") niceDate(v.toString()) else v.toString()
                c.addView(label(if (q.optString("t") == "rating") "$text / ${if (q.optInt("max") == 10) 10 else 5}" else text, 15f, "#3E1D16", true).apply { setPadding(0, dp(4), 0, 0) })
            }
            content.addView(cardOf(c))
        }
    }
}
