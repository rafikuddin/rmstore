package com.example.attendance

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Admin side of Surveys: the list (open / close / delete), the survey builder (title, picture, deadline, audience, anonymous,
 * questions of nine kinds) and the results (totals per question, attachments, who has not answered, Excel download).
 */
class SurveyAdminActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private val picker = PicturePicker(this)
    private var mode = 0                                   // 0 list, 1 builder, 2 results

    // ---- the survey being built
    private var editId = ""
    private var title = ""
    private var desc = ""
    private var deadline = ""
    private var audAll = true
    private val audSet = LinkedHashSet<String>()
    private var anon = false
    private var notify = true
    private val questions = ArrayList<JSONObject>()
    private var imageJpeg: ByteArray? = null
    private var existingImage = ""
    private var removeImage = false
    private var responses = 0
    private var etTitle: EditText? = null
    private var etDesc: EditText? = null
    private var people: JSONArray? = null

    private val xlsxType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private var excelFile: File? = null
    private val saveExcel = registerForActivityResult(ActivityResultContracts.CreateDocument(xlsxType)) { uri ->
        val f = excelFile
        if (uri != null && f != null) {
            try { contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }; toast("Saved") }
            catch (e: Exception) { toast("Could not save the file.") }
        }
    }

    private val kinds = listOf(
        "text" to "✏️  Short answer", "para" to "📝  Paragraph", "single" to "🔘  Single choice", "multi" to "☑️  Multiple choice",
        "drop" to "🔽  Dropdown", "rating" to "⭐  Rating", "yesno" to "👍  Yes / No", "number" to "🔢  Number", "date" to "📅  Date",
        "photo" to "📎  Attachment (picture)"
    )
    private fun kindName(t: String) = kinds.firstOrNull { it.first == t }?.second?.substringAfter("  ") ?: t

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { finish(); return }
        setContentView(R.layout.activity_notices)
        setupHeader("Manage surveys") { if (mode == 0) loadList() }
        setupBottomNav(null)
        if (!Access.canManageSurveys(this)) { toast("Only a survey manager can open this."); finish(); return }
        content = findViewById(R.id.noticeList)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == 0) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                else if (mode == 1) AlertDialog.Builder(this@SurveyAdminActivity).setMessage("Leave without saving this survey?")
                    .setPositiveButton("Leave") { _, _ -> loadList() }.setNegativeButton("Keep editing", null).show()
                else loadList()
            }
        })
        loadList()
    }

    // ---------------------------------------------------------------- small helpers
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

    private fun heading(t: String) = label(t, 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(dp(6), dp(10), dp(6), dp(6)) }

    private fun chip(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Pal.c(fg))
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Pal.c(bg)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
    }

    private fun chipButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Pal.c("#6B352A")); setTypeface(null, Typeface.BOLD)
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
        isClickable = true; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); marginEnd = dp(6) }
    }

    private fun bigButton(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (multi) { minLines = 2; gravity = Gravity.TOP }
    }

    private fun niceDate(iso: String): String {
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.take(10))
            if (d == null) iso else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d)
        } catch (e: Exception) { iso }
    }

    // ---------------------------------------------------------------- the list
    private fun loadList() {
        mode = 0
        content.removeAllViews()
        content.addView(label("Loading…", 14f, "#8A5F52").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
        api(JSONObject().put("action", "adminSurveyList")) { res, err ->
            content.removeAllViews()
            content.addView(bigButton("➕  New survey") { newDraft(); renderBuilder() })
            if (res == null) { content.addView(label(err ?: "Could not load.", 14f, "#DC2626").apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return@api }
            val list = res.optJSONArray("surveys") ?: JSONArray()
            if (list.length() == 0) {
                val c = column()
                c.addView(label("📋  No surveys yet", 16f, "#3E1D16", true))
                c.addView(label("Tap “New survey” to ask your team something.", 14f, "#8A5F52").apply { setPadding(0, dp(6), 0, 0) })
                content.addView(cardOf(c)); return@api
            }
            for (i in 0 until list.length()) content.addView(listCard(list.getJSONObject(i)))
        }
    }

    private fun listCard(o: JSONObject): View {
        val c = column()
        c.addView(label(o.optString("title"), 16f, "#3E1D16", true))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        when (o.optString("state")) {
            "open" -> chips.addView(chip("● Open", "#166534", "#DCFCE7"))
            "expired" -> chips.addView(chip("Deadline passed", "#B45309", "#FEF3C7"))
            else -> chips.addView(chip("Closed", "#4A241C", "#EBDDA0"))
        }
        if (o.optString("deadline").isNotBlank()) chips.addView(chip("⏰ ${niceDate(o.optString("deadline"))}", "#B45309", "#FEF3C7"))
        if (o.optBoolean("anonymous")) chips.addView(chip("🔒 Anonymous", "#6B352A", "#FFF6C4"))
        c.addView(chips)
        c.addView(label("${o.optInt("responses")} of ${o.optInt("audience")} answered  ·  ${o.optInt("questions")} questions" + (if (o.optBoolean("everyone")) "  ·  everyone" else "  ·  chosen people"), 12f, "#8A5F52").apply { setPadding(0, dp(6), 0, 0) })
        return cardOf(c) { surveyMenu(o) }
    }

    private fun surveyMenu(o: JSONObject) {
        val id = o.optString("id")
        val open = o.optString("status") == "open"
        val items = arrayOf("📊  Results", "✏️  Edit", if (open) "⏸  Close the survey" else "▶️  Open the survey again", "🗑  Delete")
        AlertDialog.Builder(this).setTitle(o.optString("title")).setItems(items) { _, which ->
            when (which) {
                0 -> showResults(id)
                1 -> editSurvey(id)
                2 -> api(JSONObject().put("action", "adminSurveySetStatus").put("id", id).put("status", if (open) "closed" else "open")) { r, e ->
                    if (r == null) toast(e ?: "Could not change.") else loadList()
                }
                3 -> confirmDelete(id, o.optString("title"), o.optInt("responses"))
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun confirmDelete(id: String, name: String, answers: Int) {
        val pw = FloatField(this, "Your own password", password = true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(label("This deletes “$name” and its $answers answer${if (answers == 1) "" else "s"} (and attachments) for good.", 13f, "#6B352A"))
        box.addView(pw)
        AlertDialog.Builder(this).setTitle("Delete survey?").setView(box)
            .setPositiveButton("Delete") { _, _ ->
                api(JSONObject().put("action", "adminSurveyDelete").put("id", id).put("adminPassword", pw.text.toString())) { r, e ->
                    if (r == null) AlertDialog.Builder(this).setTitle("Not deleted").setMessage(e ?: "Unknown problem").setPositiveButton("OK", null).show()
                    else { toast("Deleted"); loadList() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- the builder
    private fun newDraft() {
        editId = ""; title = ""; desc = ""; deadline = ""; audAll = true; audSet.clear(); anon = false; notify = true
        questions.clear(); imageJpeg = null; existingImage = ""; removeImage = false; responses = 0
    }

    private fun editSurvey(id: String) {
        api(JSONObject().put("action", "adminSurveyGet").put("id", id)) { r, e ->
            if (r == null) { toast(e ?: "Could not open."); return@api }
            newDraft()
            editId = id; title = r.optString("title"); desc = r.optString("description"); deadline = r.optString("deadline")
            anon = r.optBoolean("anonymous"); responses = r.optInt("responses"); existingImage = r.optString("image"); notify = false
            val a = r.opt("audience")
            if (a is JSONArray) { audAll = false; for (i in 0 until a.length()) audSet.add(a.optString(i)) } else audAll = true
            val qs = r.optJSONArray("questions") ?: JSONArray()
            for (i in 0 until qs.length()) questions.add(qs.getJSONObject(i))
            renderBuilder()
        }
    }

    private fun syncDraft() { etTitle?.let { title = it.text.toString() }; etDesc?.let { desc = it.text.toString() } }

    private fun renderBuilder() {
        mode = 1
        content.removeAllViews()
        val locked = responses > 0
        // basics
        val a = column()
        a.addView(label(if (editId.isEmpty()) "NEW SURVEY" else "EDIT SURVEY", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
        etTitle = field("Title (for example: Office feedback)").apply { setText(title) }
        etDesc = field("Short explanation (optional)", true).apply { setText(desc) }
        a.addView(etTitle); a.addView(etDesc)
        val dl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dl.addView(chipButton(if (deadline.isEmpty()) "⏰  Deadline: none" else "⏰  Deadline: ${niceDate(deadline)}") {
            syncDraft()
            val c = Calendar.getInstance()
            if (deadline.length == 10) try { c.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(deadline)!! } catch (e: Exception) { }
            val dlg = DatePickerDialog(this, { _, y, m, d -> deadline = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d); renderBuilder() },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            if (editId.isEmpty()) dlg.datePicker.minDate = System.currentTimeMillis() - 86400000L
            dlg.show()
        })
        if (deadline.isNotEmpty()) dl.addView(chipButton("✖") { syncDraft(); deadline = ""; renderBuilder() })
        a.addView(dl)
        // picture
        val pic = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val shown = imageJpeg
        if (shown != null) {
            pic.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(8) }
                setImageBitmap(BitmapFactory.decodeByteArray(shown, 0, shown.size))
            })
        } else if (existingImage.isNotEmpty() && !removeImage) {
            pic.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(8) }
                PhotoLoader.load(this@SurveyAdminActivity, session, existingImage, this, 640)
            })
        }
        val hasPic = shown != null || (existingImage.isNotEmpty() && !removeImage)
        val picRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        picRow.addView(chipButton(if (hasPic) "🖼  Change picture" else "🖼  Attach a picture") {
            syncDraft(); picker.choose { bytes -> imageJpeg = bytes; removeImage = false; renderBuilder() }
        })
        if (hasPic) picRow.addView(chipButton("✖  Remove") { syncDraft(); imageJpeg = null; if (existingImage.isNotEmpty()) removeImage = true; renderBuilder() })
        pic.addView(picRow)
        a.addView(pic)
        content.addView(cardOf(a))

        // audience + options
        val w = column()
        w.addView(label("WHO ANSWERS", 12f, "#6B352A", true).apply { letterSpacing = 0.08f })
        val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rAll = RadioButton(this).apply { text = "Everyone"; id = View.generateViewId(); isChecked = audAll }
        val rSome = RadioButton(this).apply { text = "Chosen people"; id = View.generateViewId(); isChecked = !audAll }
        g.addView(rAll); g.addView(rSome)
        g.setOnCheckedChangeListener { _, checked -> syncDraft(); audAll = checked == rAll.id; renderBuilder() }
        w.addView(g)
        if (!audAll) w.addView(chipButton("👥  Choose people (${audSet.size} chosen)") { syncDraft(); choosePeople() })
        w.addView(CheckBox(this).apply {
            text = "🔒  Anonymous (names are not saved with the answers)"; isChecked = anon; isEnabled = !locked
            setOnCheckedChangeListener { _, v -> anon = v }
        })
        if (editId.isEmpty() && audAll) w.addView(CheckBox(this).apply {
            text = "🔔  Also post a notice to everyone"; isChecked = notify
            setOnCheckedChangeListener { _, v -> notify = v }
        })
        if (locked) w.addView(label("This survey already has $responses answer${if (responses == 1) "" else "s"}, so its questions and the Anonymous choice cannot be changed.", 12f, "#B45309").apply { setPadding(0, dp(8), 0, 0) })
        content.addView(cardOf(w))

        // questions
        content.addView(heading("QUESTIONS (${questions.size})"))
        questions.forEachIndexed { i, q -> content.addView(questionCard(i, q, locked)) }
        if (!locked) content.addView(bigButton("➕  Add a question") { syncDraft(); pickKind() })
        content.addView(bigButton("💾  Save survey") { save() })
    }

    private fun questionCard(i: Int, q: JSONObject, locked: Boolean): View {
        val c = column()
        c.addView(label("${i + 1}  ·  ${kindName(q.optString("t"))}" + (if (q.optBoolean("req")) "  ·  required" else ""), 11f, "#8A5F52", true))
        c.addView(label(q.optString("q"), 15f, "#3E1D16", true).apply { setPadding(0, dp(3), 0, 0) })
        val o = q.optJSONArray("o")
        if (o != null && o.length() > 0) c.addView(label((0 until o.length()).joinToString("  ·  ") { o.optString(it) }, 12f, "#8A5F52").apply { setPadding(0, dp(3), 0, 0) })
        if (q.optString("t") == "rating") c.addView(label("1 to ${if (q.optInt("max") == 10) 10 else 5}", 12f, "#8A5F52"))
        if (!locked) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            row.addView(chipButton("↑") { syncDraft(); if (i > 0) { questions.add(i - 1, questions.removeAt(i)); renderBuilder() } })
            row.addView(chipButton("↓") { syncDraft(); if (i < questions.size - 1) { questions.add(i + 1, questions.removeAt(i)); renderBuilder() } })
            row.addView(chipButton("✏️  Edit") { syncDraft(); editQuestion(i, q.optString("t")) })
            row.addView(chipButton("🗑") { syncDraft(); questions.removeAt(i); renderBuilder() })
            c.addView(row)
        }
        return cardOf(c)
    }

    private fun pickKind() {
        AlertDialog.Builder(this).setTitle("What kind of question?")
            .setItems(kinds.map { it.second }.toTypedArray()) { _, which -> editQuestion(-1, kinds[which].first) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editQuestion(index: Int, type: String) {
        val old = if (index >= 0) questions[index] else null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(label(kindName(type), 13f, "#8A5F52", true))
        val et = field("Write the question", true).apply { setText(old?.optString("q").orEmpty()) }
        box.addView(et)
        val req = CheckBox(this).apply { text = "Required"; isChecked = old?.optBoolean("req") ?: false }
        box.addView(req)
        val etOpts: EditText? = if (type == "single" || type == "multi" || type == "drop") {
            box.addView(label("Choices, one on each line (2 to 15)", 12f, "#8A5F52").apply { setPadding(0, dp(8), 0, 0) })
            field("Choice 1\nChoice 2", true).apply {
                minLines = 4
                val o = old?.optJSONArray("o"); if (o != null) setText((0 until o.length()).joinToString("\n") { o.optString(it) })
            }.also { box.addView(it) }
        } else null
        val ten: CheckBox? = if (type == "rating") {
            CheckBox(this).apply { text = "Scale of 10 (default is 5)"; isChecked = old?.optInt("max") == 10 }.also { box.addView(it) }
        } else null
        val dlg = AlertDialog.Builder(this).setTitle(if (old == null) "New question" else "Edit question")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = et.text.toString().trim()
                if (text.length < 2) { et.error = "Write the question"; return@setOnClickListener }
                val q = JSONObject().put("t", type).put("q", text).put("req", req.isChecked)
                if (etOpts != null) {
                    val list = etOpts.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
                    if (list.size < 2) { etOpts.error = "Write at least two choices"; return@setOnClickListener }
                    if (list.size > 15) { etOpts.error = "At most 15 choices"; return@setOnClickListener }
                    q.put("o", JSONArray(list))
                }
                if (ten != null) q.put("max", if (ten.isChecked) 10 else 5)
                if (index >= 0) questions[index] = q else questions.add(q)
                dlg.dismiss(); renderBuilder()
            }
        }
        dlg.show()
    }

    private fun choosePeople() {
        val show = { list: JSONArray ->
            val names = ArrayList<String>(); val ids = ArrayList<String>()
            for (i in 0 until list.length()) {
                val u = list.getJSONObject(i)
                ids.add(u.optString("username").lowercase()); names.add("${u.optString("name")}  (@${u.optString("username")})")
            }
            val checked = BooleanArray(ids.size) { audSet.contains(ids[it]) }
            val dlg = AlertDialog.Builder(this).setTitle("Who should answer?")
                .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("OK") { _, _ -> audSet.clear(); ids.forEachIndexed { i, id -> if (checked[i]) audSet.add(id) }; renderBuilder() }
                .setNeutralButton("Select all", null)
                .setNegativeButton("Cancel", null).create()
            dlg.setOnShowListener {
                val all = dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
                fun label() { all.text = if (checked.all { it }) "Clear all" else "Select all" }
                label()
                all.setOnClickListener {         // does not close the dialog
                    val on = !checked.all { it }
                    for (i in checked.indices) { checked[i] = on; dlg.listView.setItemChecked(i, on) }
                    label()
                }
            }
            dlg.show()
        }
        val have = people
        if (have != null) show(have) else api(JSONObject().put("action", "surveyPeople")) { r, e ->
            val l = r?.optJSONArray("users")
            if (l == null) toast(e ?: "Could not load the people.") else { people = l; show(l) }
        }
    }

    private fun save() {
        syncDraft()
        if (title.trim().length < 3) { toast("Write a title for the survey."); return }
        if (questions.isEmpty()) { toast("Add at least one question."); return }
        if (!audAll && audSet.isEmpty()) { toast("Choose who should answer, or choose Everyone."); return }
        val body = JSONObject().put("action", "adminSurveySave").put("title", title.trim()).put("description", desc.trim())
            .put("questions", JSONArray(questions.map { it })).put("anonymous", anon).put("deadline", deadline)
            .put("audience", if (audAll) JSONObject.wrap("all") else JSONArray(audSet.toList()))
        if (editId.isNotEmpty()) body.put("id", editId) else body.put("notify", notify && audAll)
        imageJpeg?.let { body.put("image", Base64.encodeToString(it, Base64.NO_WRAP)) }
        if (removeImage) body.put("removeImage", true)
        toast("Saving…")
        api(body) { r, e ->
            if (r == null) { AlertDialog.Builder(this).setTitle("Not saved").setMessage(e ?: "Unknown problem").setPositiveButton("OK", null).show(); return@api }
            toast(if (editId.isEmpty()) "Survey created" + (if (r.optBoolean("notice")) " and notice posted" else "") else "Saved")
            loadList()
        }
    }

    // ---------------------------------------------------------------- the results
    private fun showResults(id: String) {
        api(JSONObject().put("action", "adminSurveyResults").put("id", id)) { r, e ->
            if (r == null) { toast(e ?: "Could not load the results."); return@api }
            mode = 2
            content.removeAllViews()
            val h = column()
            h.addView(label(r.optString("title"), 20f, "#3E1D16", true))
            h.addView(label("${r.optInt("total")} of ${r.optInt("audience")} answered" + (if (r.optBoolean("anonymous")) "  ·  🔒 anonymous" else ""), 14f, "#6B352A", true).apply { setPadding(0, dp(4), 0, 0) })
            val names = r.optJSONArray("notAnsweredNames")
            if (!r.optBoolean("anonymous") && r.optInt("notAnswered") > 0 && names != null) {
                h.addView(chipButton("⏳  ${r.optInt("notAnswered")} have not answered yet") {
                    val list = (0 until names.length()).joinToString("\n") { "• " + names.optString(it) } + (if (r.optInt("notAnswered") > names.length()) "\n…and more" else "")
                    AlertDialog.Builder(this).setTitle("Not answered yet").setMessage(list).setPositiveButton("Close", null).show()
                })
            }
            h.addView(chipButton("📥  Download Excel") { exportExcel(id) })
            content.addView(cardOf(h))
            val qs = r.optJSONArray("questions") ?: JSONArray()
            for (i in 0 until qs.length()) content.addView(resultCard(i, qs.getJSONObject(i)))
        }
    }

    private fun resultCard(i: Int, q: JSONObject): View {
        val c = column()
        c.addView(label("${i + 1}. ${q.optString("q")}", 15f, "#3E1D16", true))
        c.addView(label("${q.optInt("n")} answered  ·  ${kindName(q.optString("t"))}", 11f, "#8A5F52").apply { setPadding(0, dp(2), 0, dp(6)) })
        if (q.has("avg")) {
            val t = q.optString("t")
            c.addView(label(if (t == "rating") "Average ${trim(q.optDouble("avg"))}" else "Average ${trim(q.optDouble("avg"))}  ·  lowest ${trim(q.optDouble("min"))}  ·  highest ${trim(q.optDouble("max"))}", 14f, "#6B352A", true))
        }
        val opts = q.optJSONArray("options")
        if (opts != null) for (k in 0 until opts.length()) {
            val o = opts.getJSONObject(k)
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(2)) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(label(o.optString("label"), 13f, "#3E1D16").apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            top.addView(label("${o.optInt("count")}  ·  ${trim(o.optDouble("pct"))}%", 13f, "#8A5F52", true))
            row.addView(top)
            val frac = (o.optDouble("pct") / 100.0).coerceIn(0.0, 1.0).toFloat()
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(Pal.c("#F0E3A6")) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(3) }
            }
            track.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(Color.parseColor("#6B352A")) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, frac)
            })
            if (frac < 1f) track.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - frac) })
            row.addView(track)
            c.addView(row)
        }
        val texts = q.optJSONArray("texts")
        if (texts != null) for (k in 0 until texts.length()) {
            val t = texts.getJSONObject(k)
            val by = t.optString("by")
            c.addView(label("“${t.optString("v")}”" + (if (by.isNotBlank()) "  — $by" else ""), 13f, "#3E1D16").apply { setPadding(0, dp(4), 0, dp(2)) })
        }
        if (q.optInt("moreTexts") > 0) c.addView(label("…and ${q.optInt("moreTexts")} more (see the Excel file)", 12f, "#A98578"))
        val photos = q.optJSONArray("photos")
        if (photos != null && photos.length() > 0) {
            var row: LinearLayout? = null
            for (k in 0 until photos.length()) {
                if (k % 3 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }; c.addView(row) }
                val p = photos.getJSONObject(k)
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) } }
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Pal.c("#F0E3A6")) }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100))
                    setOnClickListener { showPhotoDialog(session, p.optString("id")) }
                }
                PhotoLoader.load(this, session, p.optString("id"), img, 200)
                cell.addView(img)
                if (p.optString("by").isNotBlank()) cell.addView(label(p.optString("by"), 10f, "#8A5F52").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                row?.addView(cell)
            }
            if (q.optInt("morePhotos") > 0) c.addView(label("…and ${q.optInt("morePhotos")} more attachments (links in the Excel file)", 12f, "#A98578"))
        }
        return cardOf(c)
    }

    private fun trim(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.2f", d)

    private fun exportExcel(id: String) {
        toast("Preparing the file…")
        api(JSONObject().put("action", "adminSurveyExport").put("id", id)) { r, e ->
            if (r == null) { toast(e ?: "Could not prepare the file."); return@api }
            try {
                val dir = File(cacheDir, "reports").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, r.optString("name", "survey.xlsx"))
                f.writeBytes(Base64.decode(r.getString("data"), Base64.DEFAULT))
                excelFile = f
                AlertDialog.Builder(this).setTitle("✅ Excel file ready").setMessage("${f.name}\n${r.optInt("responses")} answers")
                    .setPositiveButton("💾 Save") { _, _ -> saveExcel.launch(f.name) }
                    .setNeutralButton("📤 Share") { _, _ ->
                        try {
                            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(xlsxType)
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(android.content.Intent.createChooser(send, "Share survey results"))
                        } catch (ex: Exception) { toast("Could not share the file.") }
                    }.setNegativeButton("Close", null).show()
            } catch (ex: Exception) { toast("Could not prepare the file.") }
        }
    }
}
