package com.example.attendance

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import org.json.JSONArray
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Admin screen: add users, reset passwords, post notices and add custom holidays.
 * Only opened for people the server says are admins; the server checks every action again.
 */
class AdminActivity : AppCompatActivity() {
    companion object { const val EXTRA_TAB = "tab" }
    private lateinit var session: Session
    private lateinit var content: LinearLayout
    private lateinit var tabs: List<TextView>
    private lateinit var tabKeys: List<String>       // the key of each tab, in the order the admin chose (Menu tab)
    private var current = 0
    private var loadId = 0                       // a late answer for a tab the admin already left is ignored
    private var users: List<JSONObject> = emptyList()
    private var pickedHolidayDate: String = ""

    // The APK the admin picks to publish as the new version.
    private val pickApk = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) prepareApk(uri)
    }

    // Where the admin saves the Excel file (the phone's own "save as" screen, so no storage permission is needed).
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
        if (!session.loggedIn || !AdminFlag.get(this)) { finish(); return }
        setContentView(R.layout.activity_admin)
        setupHeader("Admin") { load() }
        setupBottomNav(null)
        enforceAccess("admin")
        content = findViewById(R.id.adminContent)
        arrangeTabs(intent.getStringExtra(EXTRA_TAB))
    }

    /** Puts the tab buttons in the order the admin chose and opens [startKey] (the first tab when empty or unknown). */
    private fun arrangeTabs(startKey: String?) {
        tabKeys = MenuLayout.order(this, "admin").filter { AdminTabs.find(it) != null }
        tabs = tabKeys.map { findViewById<TextView>(AdminTabs.find(it)!!.viewId) }
        val bar = tabs[0].parent as LinearLayout
        bar.removeAllViews()
        tabs.forEach { bar.addView(it) }
        tabs.forEachIndexed { i, t -> t.setOnClickListener { select(i) } }
        select(maxOf(0, tabKeys.indexOf(startKey)))
    }

    private fun select(i: Int) {
        current = i
        tabs.forEachIndexed { index, t ->
            val on = index == i
            t.setTextColor(Pal.c(if (on) "#FFFFFF" else "#8A5F52"))
            t.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (on) Pal.chipOn else Pal.c("#F0E3A6"))
            }
        }
        findViewById<android.widget.HorizontalScrollView>(R.id.tabScroll).post {
            findViewById<android.widget.HorizontalScrollView>(R.id.tabScroll).smoothScrollTo(maxOf(0, tabs[i].left - dp(40)), 0)
        }
        load()
    }

    private fun load() {
        when (tabKeys[current]) {
            "users" -> loadUsers()
            "notices" -> loadNotices()
            "holiday" -> loadHolidays()
            "entry" -> loadEntry()
            "report" -> loadReport()
            "targets" -> loadTargets()
            "sms" -> loadSms()
            "leave" -> loadLeaveQuota()
            "menu" -> loadMenuLayout()
            else -> loadApp()
        }
    }

    // ---------- small view helpers ----------

    private fun label(text: String, size: Float, color: String, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Pal.c(color))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun note(text: String) = label(text, 14f, "#6B352A", false).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }

    private fun heading(text: String) = label(text, 13f, "#6B352A", true).apply {
        letterSpacing = 0.08f
        setPadding(dp(6), dp(16), dp(6), dp(8))
    }

    private fun pill(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text; setTextColor(Pal.c(fg)); setTypeface(null, Typeface.BOLD); textSize = 12f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Pal.c(bg)) }
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

    private fun bigButton(text: String, onClick: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        inputType = type
    }

    /** A password box in the "Label input" style: the label lifts into the top edge on focus, with a show/hide eye. Read it with .text. */
    private fun passField(hint: String) = FloatField(this, hint, password = true)

    private fun chipButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Pal.c("#6B352A"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F0E3A6")) }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun scrollable(vararg views: View): ScrollView {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            views.forEach { addView(it) }
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    /** Home and Calendar keep a saved copy; after an admin change this phone's copies are out of date. */
    private fun dropSavedCopies() {
        ResponseCache.remove(this, ResponseCache.homeKey(session.username), ResponseCache.calKey(session.username), ResponseCache.nightKey(session.username))
    }

    // ---------- MENU ORDER ----------

    private val menuNames = mapOf(
        "attendance" to "📸  Attendance", "leave" to "🌴  Leave", "night" to "🌙  Night Hold", "calendar" to "📅  Calendar", "team" to "👥  Team",
        "members" to "🪪  Team members", "notices" to "🔔  Notices", "reports" to "📊  My Reports", "target" to "🎯  Target vs Collection", "survey" to "📋  Surveys",
        "profile" to "👤  My Profile", "password" to "🔑  Change Password", "reminders" to "⏰  Attendance reminders", "manual" to "📝  Manual entry",
        "update" to "⬆️  Update app", "admin" to "🛠  Admin Panel"
    )

    /** The order of the side menu, its Settings group and the Admin Panel tabs. Move with ▲ ▼, then Save: it applies to everyone. */
    private fun loadMenuLayout() {
        val id = ++loadId
        content.removeAllViews()
        val status = note("Loading…")
        content.addView(status)
        api(JSONObject().put("action", "adminMenuLayout")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load.")); return@api }
            fun listOfKeys(o: JSONObject, g: String): MutableList<String> {
                val a = o.optJSONArray(g); val out = ArrayList<String>()
                if (a != null) for (i in 0 until a.length()) out.add(a.getString(i))
                return out
            }
            val groups = listOf("menu" to "☰  Side menu: Menu", "settings" to "⚙️  Side menu: Settings", "admin" to "🛠  Admin Panel tabs")
            val saved = HashMap<String, List<String>>()
            val work = HashMap<String, MutableList<String>>()
            for ((g, _) in groups) { saved[g] = listOfKeys(res.getJSONObject("layout"), g); work[g] = saved[g]!!.toMutableList() }
            val std = res.getJSONObject("standard")
            fun nameOf(g: String, k: String) = if (g == "admin") AdminTabs.find(k).let { "${it?.icon ?: ""}  ${it?.name ?: k}" } else (menuNames[k] ?: k)

            val intro = column()
            intro.addView(label("🧭  Order of the menus", 16f, "#3E1D16", true))
            intro.addView(label("Move the entries up or down. The order applies to everyone: the side menu on Home (the Home entry always stays first), the Settings part below it, and the tabs of this Admin Panel, which are also shown under Admin Panel in the side menu. Entries a person may not use stay hidden or locked as before.", 13f, "#8A5F52", false).apply { setPadding(0, dp(6), 0, 0) })
            content.addView(cardOf(intro))

            val lists = LinkedHashMap<String, LinearLayout>()
            lateinit var draw: () -> Unit
            for ((g, title) in groups) {
                val box = column()
                box.addView(label(title, 15f, "#3E1D16", true))
                val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                box.addView(rows)
                lists[g] = rows
                content.addView(cardOf(box))
            }
            draw = {
                for ((g, _) in groups) {
                    val rows = lists[g]!!; val order = work[g]!!
                    rows.removeAllViews()
                    order.forEachIndexed { i, k ->
                        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
                        line.addView(label("${i + 1}", 12f, "#A98578", true).apply { minWidth = dp(22) })
                        line.addView(label(nameOf(g, k), 15f, "#6B352A", false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        fun arrow(t: String, enabled: Boolean, to: Int) = TextView(this).apply {
                            text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Pal.c("#6B352A")); alpha = if (enabled) 1f else 0.25f
                            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Pal.c("#F0E3A6")) }
                            isClickable = enabled; isFocusable = enabled
                            contentDescription = if (to < i) "Move up" else "Move down"
                            if (enabled) setOnClickListener { val x = order.removeAt(i); order.add(to, x); draw() }
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(38)).apply { marginStart = dp(6) }
                        }
                        line.addView(arrow("▲", i > 0, i - 1)); line.addView(arrow("▼", i < order.size - 1, i + 1))
                        rows.addView(line)
                    }
                }
            }
            draw()

            val msg = label("", 14f, "#DC2626", false).apply { setPadding(dp(4), 0, dp(4), dp(4)) }
            content.addView(msg)
            content.addView(bigButton("💾  Save the order") {
                val changed = groups.filter { (g, _) -> work[g] != saved[g] }.map { it.second.substringAfter("  ") }
                if (changed.isEmpty()) { msg.text = "Nothing changed."; return@bigButton }
                msg.text = ""
                AlertDialog.Builder(this).setTitle("Save the new order?")
                    .setMessage("Changed: " + changed.joinToString(", ") + ".\n\nIt applies to everyone. Phones pick it up the next time the Home screen loads.")
                    .setPositiveButton("Save") { _, _ ->
                        msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Saving…"
                        val layout = JSONObject(); for ((g, _) in groups) layout.put(g, org.json.JSONArray(work[g]!!))
                        api(JSONObject().put("action", "adminSetMenuLayout").put("layout", layout)) { r, e ->
                            if (r != null) {
                                MenuLayout.save(this, r.optJSONObject("layout")); dropSavedCopies(); toast("Menu order saved")
                                arrangeTabs("menu")
                            } else { msg.setTextColor(Pal.c("#DC2626")); msg.text = e ?: "Could not save." }
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            })
            content.addView(chipButton("↺  Standard order") {
                for ((g, _) in groups) { work[g] = listOfKeys(std, g); }
                draw(); msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Standard order filled in. Press Save to apply."
            })
        }
    }

    // ---------- LEAVE DAYS ----------

    /** The yearly days for each kind of leave. Applies to everyone; people who already used more simply have 0 left. */
    private fun loadLeaveQuota() {
        val id = ++loadId
        content.removeAllViews()
        val status = note("Loading…")
        content.addView(status)
        api(JSONObject().put("action", "adminLeaveQuota")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load.")); return@api }
            val types = ArrayList<String>()
            res.optJSONArray("types")?.let { for (i in 0 until it.length()) types.add(it.getString(i)) }
            val now = res.getJSONObject("quota")
            val std = res.getJSONObject("standard")
            val max = res.optInt("max", 365)

            val head = column()
            head.addView(label("🌴  Leave days a year", 16f, "#3E1D16", true))
            head.addView(label("How many days each person can take of every kind of leave, each year. It applies to everyone, now and from next year on. Someone who already took more than the new number simply has 0 left; nothing already approved is changed.", 13f, "#8A5F52", false).apply { setPadding(0, dp(6), 0, 0) })
            val boxes = LinkedHashMap<String, FloatField>()
            for (t in types) {
                val f = FloatField(this, "$t (days a year, 0 to $max)", InputType.TYPE_CLASS_NUMBER)
                f.setText(now.optInt(t).toString())
                boxes[t] = f
                head.addView(f)
            }
            val msg = label("", 14f, "#DC2626", false).apply { setPadding(0, dp(8), 0, 0) }
            head.addView(msg)
            head.addView(bigButton("💾  Save leave days") {
                val wanted = JSONObject()
                val changes = ArrayList<String>()
                for ((t, f) in boxes) {
                    val raw = f.text.toString().trim()
                    val n = raw.toIntOrNull()
                    if (raw.isEmpty() || n == null || n < 0 || n > max) { msg.text = "$t: enter a whole number from 0 to $max."; return@bigButton }
                    wanted.put(t, n)
                    if (n != now.optInt(t)) changes.add("$t: ${now.optInt(t)} → $n days")
                }
                if (changes.isEmpty()) { msg.text = "Nothing changed."; return@bigButton }
                msg.text = ""
                AlertDialog.Builder(this).setTitle("Change the leave days?")
                    .setMessage(changes.joinToString("\n") + "\n\nThis applies to everyone straight away.")
                    .setPositiveButton("Save") { _, _ ->
                        msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Saving…"
                        api(JSONObject().put("action", "adminSetLeaveQuota").put("quota", wanted)) { r, e ->
                            if (r != null) { dropSavedCopies(); toast("Leave days saved"); loadLeaveQuota() }
                            else { msg.setTextColor(Pal.c("#DC2626")); msg.text = e ?: "Could not save." }
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            })
            val stdText = types.joinToString(" · ") { "${it.removeSuffix(" Leave")} ${std.optInt(it)}" }
            head.addView(chipButton("↺  Fill in the standard days ($stdText)") {
                for ((t, f) in boxes) f.setText(std.optInt(t).toString())
                msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Filled in. Press Save to apply."
            })
            content.addView(cardOf(head))
        }
    }

    // ---------- USERS ----------

    private fun loadUsers() {
        val id = ++loadId
        content.removeAllViews()
        content.addView(bigButton("➕  Add new user") { addUserDialog() })
        val signupBtn = chipButton("📝  Sign-up requests") { signupDialog() }
        content.addView(signupBtn)
        api(JSONObject().put("action", "adminSignups")) { res, _ ->
            if (res != null && id == loadId) {
                val n = res.optJSONArray("requests")?.length() ?: 0
                signupBtn.text = "📝  Sign-up requests" + (if (n > 0) "  ($n waiting)" else "") + (if (!res.optBoolean("open", true)) "  ·  sign-up is OFF" else "")
            }
        }
        content.addView(chipButton("🔐  Menu access for several people") {
            if (users.isEmpty()) { toast("Wait until the users have loaded."); return@chipButton }
            val chosen = LinkedHashSet<String>()
            pickPeople(chosen) { if (chosen.isNotEmpty()) accessDialog(chosen.toList(), null, null) }
        })
        val status = note("Loading users…")
        content.addView(status)
        api(JSONObject().put("action", "adminUsers")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load users.")); return@api }
            val arr = res.optJSONArray("users")
            val list = ArrayList<JSONObject>()
            if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            users = list
            latestCode = res.optJSONObject("latest")?.optLong("versionCode") ?: 0L
            latestName = res.optJSONObject("latest")?.optString("versionName") ?: ""
            // Search by name, username or manager. The list below is rebuilt on every keystroke.
            val search = SeekField(this, "Search name, Employee ID or manager")
            val countLine = heading("")
            val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(search); content.addView(countLine); content.addView(results)
            fun show(q: String) {
                val needle = q.trim().lowercase(Locale.getDefault())
                val shown = if (needle.isEmpty()) list else list.filter {
                    it.optString("name").lowercase(Locale.getDefault()).contains(needle) ||
                        it.optString("username").lowercase(Locale.getDefault()).contains(needle) ||
                        it.optString("manager").lowercase(Locale.getDefault()).contains(needle)
                }
                val off = list.count { it.optBoolean("inactive") }
                countLine.text = if (needle.isEmpty()) "${list.size - off} ACTIVE USERS" + (if (off > 0) " · $off INACTIVE" else "") else "${shown.size} OF ${list.size} USERS"
                results.removeAllViews()
                if (shown.isEmpty()) results.addView(note("No user matches \"${q.trim()}\"."))
                else shown.forEach { results.addView(userCard(it)) }
            }
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { show(e?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            show("")
        }
    }

    private var latestCode = 0L        // the app version currently offered to everyone (0 = none published)
    private var latestName = ""

    /** "📱 v1.0.71 · seen 14 Oct" in green when up to date, orange when older than the published version, grey when never reported. */
    private fun versionLine(o: JSONObject): View {
        val v = o.optString("appVersion")
        val code = o.optLong("appCode")
        val seen = o.optString("lastSeen").take(10).let { d ->
            try {
                val p = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)
                if (p == null) "" else "  ·  seen " + java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(p)
            } catch (e: Exception) { "" }
        }
        return when {
            v.isEmpty() -> label("📱 App version not reported yet (old version, or never opened)", 12f, "#8A5F52", false)
            latestCode > 0 && code in 1 until latestCode ->
                label("📱 v$v$seen  ·  ⬆ needs update (latest v$latestName)", 12f, "#B45309", true)
            else -> label("📱 v$v$seen" + if (latestCode > 0 && code >= latestCode) "  ·  ✓ up to date" else "", 12f, "#15803D", true)
        }.apply { setPadding(0, dp(6), 0, 0) }
    }

    private fun userCard(o: JSONObject): View {
        val username = o.optString("username")
        val col = column()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        names.addView(label(o.optString("name"), 16f, "#3E1D16", true))
        val mgr = o.optString("manager")
        names.addView(label("ID: $username" + if (mgr.isNotEmpty()) "  ·  Manager: $mgr" else "", 13f, "#8A5F52", false))
        if (o.optBoolean("inactive") && o.optString("lastWorkingDay").isNotEmpty()) {
            val lwd = try { nice.format(iso.parse(o.optString("lastWorkingDay"))!!) } catch (e: Exception) { o.optString("lastWorkingDay") }
            names.addView(label("Last working day: $lwd" + (if (o.optString("inactiveRemarks").isNotEmpty()) "\nRemarks: ${o.optString("inactiveRemarks")}" else ""), 12f, "#B91C1C", false))
        }
        top.addView(names)
        if (o.optBoolean("isAdmin")) top.addView(pill("Admin", "#6B352A", "#FBEBC8"))
        val inactive = o.optBoolean("inactive")
        top.addView(if (inactive) pill("Inactive", "#B91C1C", "#FEE2E2") else pill("Active", "#166534", "#DCFCE7"))
        col.addView(top)
        col.addView(versionLine(o))
        if (!o.optBoolean("active", true)) col.addView(label("⛔ No password set — this account cannot sign in", 12f, "#B91C1C", false).apply { setPadding(0, dp(6), 0, 0) })
        else if (o.optBoolean("mustChange")) col.addView(label("⏳ Has not chosen their own password yet", 12f, "#B45309", false).apply { setPadding(0, dp(6), 0, 0) })
        val viewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        viewRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "👤 Profile"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                startActivity(android.content.Intent(this@AdminActivity, ProfileActivity::class.java)
                    .putExtra(ProfileActivity.EXTRA_USER, username).putExtra(ProfileActivity.EXTRA_NAME, o.optString("name")))
            }
        })
        viewRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📅 Calendar"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
            setOnClickListener {
                startActivity(android.content.Intent(this@AdminActivity, CalendarActivity::class.java)
                    .putExtra(CalendarActivity.EXTRA_USER, username).putExtra(CalendarActivity.EXTRA_NAME, o.optString("name")))
            }
        })
        col.addView(viewRow)
        val lockedNow = o.optJSONArray("locks")
        val grantsNow = o.optJSONArray("grants")
        if (!o.optBoolean("isAdmin") && grantsNow != null && (0 until grantsNow.length()).any { grantsNow.optString(it) == "surveys" })
            col.addView(label("📋 Can create & manage surveys", 12f, "#6B352A", true).apply { setPadding(0, dp(8), 0, 0) })
        if (grantsNow != null && (0 until grantsNow.length()).any { grantsNow.optString(it) == "teamview" })
            col.addView(label("🪪 Can see Profile & Calendar of own team", 12f, "#6B352A", true).apply { setPadding(0, dp(8), 0, 0) })
        if (lockedNow != null && lockedNow.length() > 0) {
            val names = (0 until lockedNow.length()).joinToString(", ") { Access.name(lockedNow.optString(it)) }
            col.addView(label("🔒 Locked: $names", 12f, "#B45309", true).apply { setPadding(0, dp(8), 0, 0) })
        }
        col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "🔐  Menu access"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { accessDialog(listOf(username), lockedNow, grantsNow) }
        })
        if (!username.equals(session.username, ignoreCase = true)) {
            col.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (inactive) "✅  Make Active" else "⛔  Make Inactive"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                setOnClickListener {
                    if (!inactive) inactiveDialog(o)
                    else AlertDialog.Builder(this@AdminActivity)
                        .setTitle("Make ${o.optString("name")} active?")
                        .setMessage("They will be able to sign in again.")
                        .setPositiveButton("Make Active") { _, _ ->
                            adminApi(JSONObject().put("action", "adminSetActive").put("username", username).put("active", true)) { res, err ->
                                if (res != null) { toast("Active again"); loadUsers() } else toast(err ?: "Could not save")
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            col.addView(MaterialButton(this).apply {
                text = "🔑  Reset password"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                setOnClickListener { resetDialog(o) }
            })
        }
        return cardOf(col)
    }

    /** Tick = the person MAY use it; untick = locked. [now] is the current locked list of one person (null when several are chosen). */
    private fun accessDialog(names: List<String>, now: JSONArray?, grantsNow: JSONArray?) {
        val locked = HashSet<String>()
        if (now != null) for (i in 0 until now.length()) locked.add(now.optString(i))
        val boxes = LinkedHashMap<String, CheckBox>()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        form.addView(label(if (names.size == 1) "Tick what this person may use. Untick to lock it. The server refuses a locked option, and the app hides the way in."
            else "These settings REPLACE the current ones of all ${names.size} chosen people. Tick what they may use; untick to lock it.", 13f, "#8A5F52", false))
        // The Admin Panel row only makes sense for admins, and never for yourself (you could not unlock it again).
        val anyAdmin = names.any { n -> users.any { it.optString("username").equals(n, true) && it.optBoolean("isAdmin") } }
        val includesMe = names.any { it.equals(session.username, true) }
        Access.items.forEach { (key, text) ->
            if (key == "admin" && (!anyAdmin || includesMe)) return@forEach
            val cb = CheckBox(this).apply { this.text = text; textSize = 15f; isChecked = !locked.contains(key) }
            boxes[key] = cb; form.addView(cb)
        }
        form.addView(label("Admin Panel access always comes from the Role column, not from this list.", 12f, "#A98578", false).apply { setPadding(0, dp(8), 0, 0) })
        // An extra right (not a menu): who may create, edit and read the results of surveys. Admins always may.
        form.addView(label("EXTRA RIGHTS", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, dp(2)) })
        val cbSurvey = CheckBox(this).apply {
            text = "📋  Can create & manage surveys"; textSize = 15f
            isChecked = grantsNow != null && (0 until grantsNow.length()).any { grantsNow.optString(it) == "surveys" }
        }
        form.addView(cbSurvey)
        val cbTeam = CheckBox(this).apply {
            text = "🪪  Can see Profile & Calendar of own team (line manager)"; textSize = 15f
            isChecked = grantsNow != null && (0 until grantsNow.length()).any { grantsNow.optString(it) == "teamview" }
        }
        form.addView(cbTeam)
        form.addView(label("A line manager with this tick gets 🪪 Team members in the menu: only the people below them, read only.", 12f, "#A98578", false))
        form.addView(label(if (names.size == 1) "Admins can always do this. Everybody else only with this tick; they then see ⚙️ Manage surveys inside Surveys."
            else "Applies to all ${names.size} chosen people (admins can always do this).", 12f, "#A98578", false))
        val title = if (names.size == 1) "Menu access: " + (users.firstOrNull { it.optString("username").equals(names[0], true) }?.optString("name") ?: names[0])
                    else "Menu access: ${names.size} people"
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                val body = JSONObject().put("action", "adminSetAccess").put("usernames", JSONArray(names))
                    .put("locks", JSONArray(boxes.filter { !it.value.isChecked }.keys.toList()))
                    .put("grants", JSONArray(listOfNotNull(if (cbSurvey.isChecked) "surveys" else null, if (cbTeam.isChecked) "teamview" else null)))
                adminApi(body) { res, err ->
                    if (res != null) { toast("Saved for ${res.optInt("updated")} ${if (res.optInt("updated") == 1) "person" else "people"}"); loadUsers() }
                    else toast(err ?: "Could not save")
                }
            }
            .setNeutralButton("Unlock all") { _, _ -> boxes.values.forEach { it.isChecked = true }
                adminApi(JSONObject().put("action", "adminSetAccess").put("usernames", JSONArray(names)).put("locks", JSONArray()).put("grants", JSONArray(listOfNotNull(if (cbSurvey.isChecked) "surveys" else null, if (cbTeam.isChecked) "teamview" else null)))) { res, err ->
                    if (res != null) { toast("Everything unlocked"); loadUsers() } else toast(err ?: "Could not save") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addUserDialog() {
        val etUser = field("Employee ID (letters, numbers, . _ -)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val etName = field("Full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        var manager = ""
        val btnManager = chipButton("Manager: none  ▾") {}
        btnManager.setOnClickListener {
            // A pick-one list with a search box: type part of a name or username to narrow it down.
            val none = "— No manager —"
            val labels = ArrayList<String>()
            labels.add(none)
            users.forEach { labels.add("${it.optString("name")}  (${it.optString("username")})") }
            PickerDialog.show(this, "Choose the manager", labels) { picked ->
                val which = labels.indexOf(picked)
                manager = if (which <= 0) "" else users[which - 1].optString("username")
                btnManager.text = if (which <= 0) "Manager: none  ▾" else "Manager: $manager  ▾"
            }
        }
        val etPass = field("Temporary password (blank = make one for me)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val cbAdmin = CheckBox(this).apply { text = "Make this person an admin" }
        val etAdminPw = passField("Your own password (to confirm)")
        val error = label("", 14f, "#DC2626", false)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add new user")
            .setView(scrollable(etUser, etName, btnManager, etPass, cbAdmin, etAdminPw, error))
            .setPositiveButton("Create", null)       // set below so errors don't close the dialog
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = etUser.text.toString().trim()
                val n = etName.text.toString().trim()
                val adminPw = etAdminPw.text.toString()
                when {
                    u.isEmpty() -> error.text = "Enter an Employee ID"
                    n.isEmpty() -> error.text = "Enter the full name"
                    adminPw.isEmpty() -> error.text = "Enter your own password to confirm"
                    else -> {
                        error.text = "Creating…"
                        val body = JSONObject().put("action", "adminAddUser")
                            .put("newUsername", u).put("name", n).put("manager", manager)
                            .put("password", etPass.text.toString().trim())
                            .put("makeAdmin", cbAdmin.isChecked).put("adminPassword", adminPw)
                        api(body) { res, err ->
                            if (res != null) {
                                dialog.dismiss()
                                val temp = res.optString("tempPassword")
                                if (temp.isNotEmpty()) showTempPassword("User created", res.optString("username"), temp)
                                else toast("User created. They choose their own password when they first sign in.")
                                loadUsers()
                            } else error.text = err
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** People who asked for an account on the login page (they already proved their e-mail). Approve creates the account; Reject tells them no. */
    private fun signupDialog() {
        api(JSONObject().put("action", "adminSignups")) { res, err ->
            if (res == null) { toast(err ?: "Could not load the requests"); return@api }
            val arr = res.optJSONArray("requests")
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
            var dlg: AlertDialog? = null
            val open = CheckBox(this).apply { text = "Let people create accounts on the login page"; textSize = 15f; isChecked = res.optBoolean("open", true) }
            open.setOnCheckedChangeListener { _, on ->
                api(JSONObject().put("action", "adminSignupSetting").put("open", on)) { r2, e2 ->
                    if (r2 == null) { toast(e2 ?: "Could not save"); open.isChecked = !on } else { toast(if (on) "Sign-up is ON" else "Sign-up is OFF"); loadUsers() }
                }
            }
            col.addView(open)
            col.addView(label("A new account only works after you approve it here.", 12f, "#A98578", false))
            if (arr == null || arr.length() == 0) col.addView(note("No requests are waiting."))
            else for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
                box.addView(label(o.optString("name"), 16f, "#3E1D16", true))
                box.addView(label("@${o.optString("username")}  ·  ${o.optString("email")}", 13f, "#8A5F52", false))
                box.addView(label("Asked on ${o.optString("on")}", 12f, "#A98578", false))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
                row.addView(MaterialButton(this).apply {
                    text = "✓ Approve"; setBackgroundColor(Pal.c("#16A34A"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                    setOnClickListener { dlg?.dismiss(); approveSignupDialog(o) }
                })
                row.addView(MaterialButton(this).apply {
                    text = "✕ Reject"; setBackgroundColor(Pal.c("#DC2626"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
                    setOnClickListener {
                        AlertDialog.Builder(this@AdminActivity).setTitle("Reject ${o.optString("name")}?")
                            .setMessage("They will get an e-mail saying the request was not approved.")
                            .setPositiveButton("Reject") { _, _ ->
                                api(JSONObject().put("action", "adminSignupDecide").put("username", o.optString("username")).put("decision", "reject")) { r2, e2 ->
                                    if (r2 != null) { toast("Rejected"); dlg?.dismiss(); loadUsers(); signupDialog() } else toast(e2 ?: "Could not save")
                                }
                            }.setNegativeButton("Cancel", null).show()
                    }
                })
                box.addView(row); col.addView(box)
            }
            dlg = AlertDialog.Builder(this).setTitle("Sign-up requests").setView(ScrollView(this).apply { addView(col) })
                .setNegativeButton("Close", null).show()
        }
    }

    private fun approveSignupDialog(o: JSONObject) {
        var manager = ""
        val btnManager = chipButton("Manager: none  ▾") {}
        btnManager.setOnClickListener {
            if (users.isEmpty()) { toast("Wait until the users have loaded."); return@setOnClickListener }
            val none = "— No manager —"
            val labels = ArrayList<String>()
            labels.add(none)
            users.forEach { labels.add("${it.optString("name")}  (${it.optString("username")})") }
            PickerDialog.show(this, "Choose the manager", labels) { picked ->
                val which = labels.indexOf(picked)
                manager = if (which <= 0) "" else users[which - 1].optString("username")
                btnManager.text = if (which <= 0) "Manager: none  ▾" else "Manager: $manager  ▾"
            }
        }
        val etAdminPw = passField("Your own password (to confirm)")
        val error = label("", 14f, "#DC2626", false)
        val info = label("${o.optString("name")} (@${o.optString("username")}) will be able to sign in with the password they chose. Pick their line manager now (you can change it later).", 14f, "#8A5F52", false)
        val dialog = AlertDialog.Builder(this).setTitle("Approve account")
            .setView(scrollable(info, btnManager, etAdminPw, error))
            .setPositiveButton("Approve", null).setNegativeButton("Cancel") { _, _ -> signupDialog() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = etAdminPw.text.toString()
                if (pw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                error.text = "Creating…"
                api(JSONObject().put("action", "adminSignupDecide").put("username", o.optString("username")).put("decision", "approve")
                    .put("manager", manager).put("adminPassword", pw)) { res, err ->
                    if (res != null) { dialog.dismiss(); toast("Account created. They were told by e-mail."); loadUsers() } else error.text = err
                }
            }
        }
        dialog.show()
    }

    /** Make Inactive: asks the Last working day and a remark, then the server saves both and e-mails the person's line managers and all admins. */
    private fun inactiveDialog(user: JSONObject) {
        val target = user.optString("username")
        val info = label("${user.optString("name")} (Employee ID $target) will not be able to sign in, and any phone where they are signed in is signed out now. Their data is kept. " +
            "An e-mail is sent to all their line managers and all admins.", 14f, "#8A5F52", false)
        var picked = ""
        val btnDate = chipButton("📅  Last working day (choose the date)") {}
        btnDate.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                picked = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                btnDate.text = "📅  Last working day: " + (try { nice.format(iso.parse(picked)!!) } catch (e: Exception) { picked })
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        val etRemarks = field("Remarks (for example: resigned, transferred, terminated)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Make ${user.optString("name")} inactive?")
            .setView(scrollable(info, btnDate, etRemarks, error))
            .setPositiveButton("Make Inactive", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val remarks = etRemarks.text.toString().trim()
                if (picked.isEmpty()) { error.setTextColor(Pal.c("#DC2626")); error.text = "Choose the last working day"; return@setOnClickListener }
                if (remarks.length < 3) { error.setTextColor(Pal.c("#DC2626")); error.text = "Write a short remark"; return@setOnClickListener }
                error.setTextColor(Pal.c("#8A5F52")); error.text = "Saving and sending e-mails…"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val resetButton = { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; error.setTextColor(Pal.c("#8A5F52")); error.text = "" }
                adminApi(JSONObject().put("action", "adminSetActive").put("username", target).put("active", false).put("lastWorkingDay", picked).put("remarks", remarks), onCancel = resetButton) { res, err ->
                    if (res != null) {
                        dialog.dismiss()
                        val sent = res.optInt("emailed"); val none = res.optInt("noEmail"); val failed = res.optInt("mailFailed")
                        toast("Now inactive. " + (if (sent > 0) "E-mailed $sent people" else "No e-mail sent") +
                            (if (none > 0) " ($none have no e-mail saved)" else "") + (if (failed > 0) " ($failed could not be sent)" else "") + ".")
                        loadUsers()
                    } else { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; error.setTextColor(Pal.c("#DC2626")); error.text = err }
                }
            }
        }
        dialog.show()
    }

    private fun resetDialog(user: JSONObject) {
        val target = user.optString("username")
        val info = label("Set a temporary password for ${user.optString("name")} (Employee ID $target). They will be signed out everywhere and must choose their own password next time.", 14f, "#8A5F52", false)
        val etPass = field("Temporary password (blank = make one for me)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val etAdminPw = passField("Your own password (to confirm)")
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Reset password")
            .setView(scrollable(info, etPass, etAdminPw, error))
            .setPositiveButton("Reset", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val adminPw = etAdminPw.text.toString()
                if (adminPw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                error.text = "Resetting…"
                val body = JSONObject().put("action", "adminResetPassword").put("targetUsername", target)
                    .put("password", etPass.text.toString().trim()).put("adminPassword", adminPw)
                api(body) { res, err ->
                    if (res != null) {
                        dialog.dismiss()
                        val temp = res.optString("tempPassword")
                        if (temp.isNotEmpty()) showTempPassword("Password reset", target, temp)
                        else toast("Password reset for Employee ID $target.")
                        loadUsers()
                    } else error.text = err
                }
            }
        }
        dialog.show()
    }

    /** The server shows a made-up password only once, so it is displayed large with a Copy button. */
    private fun showTempPassword(title: String, username: String, password: String) {
        val tv = TextView(this).apply {
            text = password; textSize = 28f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setTextIsSelectable(true); setTextColor(Pal.c("#6B352A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Employee ID: $username\n\nGive the person this temporary password. It is shown only now. They choose their own password when they sign in.")
            .setView(tv)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("password", password)
                if (android.os.Build.VERSION.SDK_INT >= 33)       // keeps the password out of the keyboard's clipboard preview
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                cm.setPrimaryClip(clip)
                if (android.os.Build.VERSION.SDK_INT >= 28)       // and wipes it again after a minute
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { if (cm.primaryClipDescription?.label == "password") cm.clearPrimaryClip() } catch (e: Exception) { }
                    }, 60000)
                toast("Copied (cleared from the clipboard in 1 minute)")
            }
            .setNegativeButton("Done", null)
            .show()
    }

    // ---------- NOTICES ----------

    private fun loadNotices() {
        val id = ++loadId
        content.removeAllViews()

        val etTitle = field("Title", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val etMessage = field("Message", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply { minLines = 3 }
        val etDays = field("Show for how many days (default 7)", InputType.TYPE_CLASS_NUMBER)
        val msg = label("", 14f, "#DC2626", false)
        val form = column()
        form.addView(label("New notice", 16f, "#3E1D16", true))
        val cbSms = CheckBox(this).apply { text = "📱  Also send as an SMS to everyone's phone"; textSize = 14f }
        form.addView(etTitle); form.addView(etMessage); form.addView(etDays); form.addView(cbSms)
        form.addView(bigButton("📢  Post to everyone") {
            val t = etTitle.text.toString().trim()
            val m = etMessage.text.toString().trim()
            if (t.length < 2 || m.length < 2) { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Enter a title and a message"; return@bigButton }
            val body = JSONObject().put("action", "adminPostNotice").put("title", t).put("message", m)
                .put("days", etDays.text.toString().trim().toIntOrNull() ?: 7)
            fun post(sms: Boolean) {
                msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Posting…"
                if (sms) body.put("sms", true)
                adminApi(body, onCancel = { msg.text = "" }) { res, err ->
                    if (res != null) {
                        val s = SmsUi.summary(res.optJSONObject("sms"))
                        toast("Notice posted. Everyone sees it on their Home screen." + (if (s.isNotEmpty()) "\n$s" else ""))
                        dropSavedCopies()
                        loadNotices()
                    } else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
                }
            }
            if (!cbSms.isChecked) { post(false); return@bigButton }
            msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Checking SMS…"
            api(JSONObject().put("action", "smsPreview").put("scope", "all")) { pv, perr ->
                if (pv == null) { msg.setTextColor(Pal.c("#DC2626")); msg.text = perr; return@api }
                msg.text = ""
                if (!pv.optBoolean("configured") || pv.optInt("people") == 0) {
                    AlertDialog.Builder(this).setTitle("SMS cannot be sent")
                        .setMessage(if (!pv.optBoolean("configured")) "SMS is not set up yet (📱 SMS tab). Post the notice without SMS?" else "Nobody has a phone number saved. Post the notice without SMS?")
                        .setPositiveButton("Post without SMS") { _, _ -> post(false) }.setNegativeButton("Cancel", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Send SMS?").setMessage(SmsUi.confirmText(pv, "everyone"))
                        .setPositiveButton("Post + send SMS") { _, _ -> post(true) }
                        .setNeutralButton("Post without SMS") { _, _ -> post(false) }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        })
        form.addView(msg)
        content.addView(cardOf(form))

        val status = note("Loading notices…")
        content.addView(status)
        api(JSONObject().put("action", "notices")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load notices.")); return@api }
            val arr = res.optJSONArray("notices")
            val n = arr?.length() ?: 0
            content.addView(heading("ACTIVE NOTICES ($n)"))
            if (arr == null || n == 0) { content.addView(note("No active notices.")); return@api }
            for (i in 0 until n) content.addView(noticeCard(arr.getJSONObject(i)))
        }
    }

    private fun noticeCard(o: JSONObject): View {
        val col = column()
        col.addView(label(o.optString("title"), 16f, "#3E1D16", true))
        col.addView(label(o.optString("message"), 14f, "#4A241C", false).apply { setPadding(0, dp(4), 0, 0) })
        col.addView(label("Posted ${o.optString("date")} by ${o.optString("postedBy")}" +
            (if (o.optString("expires").isNotEmpty()) " · visible until ${o.optString("expires")}" else ""), 12f, "#8A5F52", false).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(MaterialButton(this).apply {
            text = "🗑  Remove"; setBackgroundColor(Pal.c("#DC2626"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener {
                AlertDialog.Builder(this@AdminActivity).setTitle("Remove this notice?")
                    .setMessage(o.optString("title"))
                    .setPositiveButton("Remove") { _, _ ->
                        adminApi(JSONObject().put("action", "adminDeleteNotice").put("id", o.optString("id"))) { res, err ->
                            if (res != null) { dropSavedCopies(); loadNotices() } else toast(err ?: "Could not remove it")
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        return cardOf(col)
    }

    // ---------- HOLIDAYS ----------

    private fun loadHolidays() {
        val id = ++loadId
        content.removeAllViews()
        pickedHolidayDate = ""

        val btnDate = chipButton("📅  Choose the date") {}
        btnDate.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                pickedHolidayDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                val shown = try { nice.format(iso.parse(pickedHolidayDate)!!) } catch (e: Exception) { pickedHolidayDate }
                btnDate.text = "📅  $shown"
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        val etName = field("Holiday name (for example Eid Holiday)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val msg = label("", 14f, "#DC2626", false)
        val form = column()
        form.addView(label("Add a holiday", 16f, "#3E1D16", true))
        form.addView(label("A holiday works like Friday: nobody is counted absent that day.", 13f, "#8A5F52", false))
        form.addView(btnDate); form.addView(etName)
        form.addView(bigButton("🎉  Add holiday") {
            val name = etName.text.toString().trim()
            if (pickedHolidayDate.isEmpty()) { msg.text = "Choose the date first"; return@bigButton }
            if (name.length < 2) { msg.text = "Enter the holiday name"; return@bigButton }
            msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Saving…"
            api(JSONObject().put("action", "adminAddHoliday").put("date", pickedHolidayDate).put("name", name)) { res, err ->
                if (res != null) { toast("Holiday added"); dropSavedCopies(); loadHolidays() }
                else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
            }
        })
        form.addView(msg)
        content.addView(cardOf(form))

        val status = note("Loading holidays…")
        content.addView(status)
        api(JSONObject().put("action", "holidays")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load holidays.")); return@api }
            val arr = res.optJSONArray("holidays")
            val n = arr?.length() ?: 0
            content.addView(heading("HOLIDAYS ($n)"))
            if (arr == null || n == 0) { content.addView(note("No custom holidays yet.")); return@api }
            val today = iso.format(Calendar.getInstance().time)
            for (i in n - 1 downTo 0) content.addView(holidayCard(arr.getJSONObject(i), today))   // newest date first
        }
    }

    private fun holidayCard(o: JSONObject, today: String): View {
        val date = o.optString("date")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(label(o.optString("name"), 16f, "#3E1D16", true))
        val shown = try { nice.format(iso.parse(date)!!) } catch (e: Exception) { date }
        text.addView(label(shown + if (date < today) "  ·  past" else "", 13f, "#8A5F52", false))
        row.addView(text)
        row.addView(TextView(this).apply {
            this.text = "✕"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Pal.c("#DC2626"))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener {
                AlertDialog.Builder(this@AdminActivity).setTitle("Remove this holiday?")
                    .setMessage("${o.optString("name")} · $shown\n\nThat day will count as a normal working day again.")
                    .setPositiveButton("Remove") { _, _ ->
                        api(JSONObject().put("action", "adminDeleteHoliday").put("date", date)) { res, err ->
                            if (res != null) { dropSavedCopies(); loadHolidays() } else toast(err ?: "Could not remove it")
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        return cardOf(row)
    }

    // ---------- REPORT (attendance as an Excel file) ----------

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

    private fun loadReport() {
        ++loadId
        content.removeAllViews()
        var choice = "current"
        var kind = "attendance"                  // or "night"
        var from = ""
        var to = ""

        // ---- 1. what to download ----
        val hint = label("", 13f, "#8A5F52", false).apply { setPadding(0, dp(10), 0, 0) }
        val chipFrom = dateChip("From") { from = it }
        val chipTo = dateChip("To") { to = it }
        val options = listOf("current" to "This pay period", "previous" to "Last pay period", "custom" to "Choose dates")
        val views = HashMap<String, TextView>()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun refresh() {
            views.forEach { (k, v) ->
                val on = k == choice
                v.setTextColor(Pal.c(if (on) "#FFFFFF" else "#8A5F52"))
                v.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (on) Pal.chipOn else Pal.c("#F0E3A6")) }
            }
            chipFrom.visibility = if (choice == "custom") View.VISIBLE else View.GONE
            chipTo.visibility = chipFrom.visibility
            hint.text = when (choice) {
                "current" -> payPeriodText(false) + "  (counted up to today)"
                "previous" -> payPeriodText(true)
                else -> "Pick the first and the last day (up to one year)."
            }
        }
        options.forEachIndexed { i, (key, text) ->
            val v = TextView(this).apply {
                this.text = text; textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), dp(12), dp(6), dp(12)); isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) }
                setOnClickListener { choice = key; refresh() }
            }
            views[key] = v; row.addView(v)
        }

        val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val msg = label("", 14f, "#DC2626", false).apply { setPadding(0, dp(8), 0, 0) }
        val infoSummary = label("", 13f, "#8A5F52", false)
        val infoDetail = label("", 13f, "#8A5F52", false)
        val infoDetailTitle = label("", 14f, "#6B352A", true).apply { setPadding(0, dp(10), 0, 0) }
        val kindViews = HashMap<String, TextView>()
        val kindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun refreshKind() {
            kindViews.forEach { (k, v) ->
                val on = k == kind
                v.setTextColor(Pal.c(if (on) "#FFFFFF" else "#8A5F52"))
                v.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (on) Pal.chipOn else Pal.c("#F0E3A6")) }
            }
            if (kind == "night") {
                infoSummary.text = "One row per person: how many night holds, how many were manual entries, days in the period."
                infoDetailTitle.text = "🌙  Night Holds"
                infoDetail.text = "Every night hold: night date, time, district, thana, hotel, location, and whether it was a normal or manual entry."
            } else {
                infoSummary.text = "One row per person: Present, Late, Leave, Absent, Day Off, Total Days."
                infoDetailTitle.text = "🗓  Daily Details"
                infoDetail.text = "Every person, every day: status (colour-coded), time, leave type, day-off reason and location."
            }
            result.removeAllViews()                   // a file made for the other report is no longer on screen
        }
        listOf("attendance" to "📍  Attendance", "night" to "🌙  Night hold").forEachIndexed { i, (key, text) ->
            val v = TextView(this).apply {
                this.text = text; textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), dp(12), dp(6), dp(12)); isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) }
                setOnClickListener { kind = key; refreshKind() }
            }
            kindViews[key] = v; kindRow.addView(v)
        }
        val form = column()
        form.addView(label("Download as Excel", 18f, "#3E1D16", true))
        form.addView(label("Choose the report and the period.", 13f, "#8A5F52", false))
        form.addView(label("REPORT", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, 0) })
        form.addView(kindRow)
        form.addView(label("PERIOD", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, 0) })
        form.addView(row); form.addView(hint); form.addView(chipFrom); form.addView(chipTo)
        form.addView(bigButton("📊  Prepare Excel file") {
            if (choice == "custom" && (from.isEmpty() || to.isEmpty())) { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Choose the From and To dates"; return@bigButton }
            msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Preparing the file… this can take a few seconds."
            result.removeAllViews()
            val body = JSONObject().put("action", "adminReport").put("period", choice).put("kind", kind)
            if (choice == "custom") body.put("from", from).put("to", to)
            api(body) { res, err ->
                if (res == null) { msg.setTextColor(Pal.c("#DC2626")); msg.text = err; return@api }
                try {
                    val dir = File(cacheDir, "reports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, res.optString("name", "attendance.xlsx"))
                    f.writeBytes(Base64.decode(res.getString("data"), Base64.DEFAULT))
                    msg.text = ""
                    result.removeAllViews()
                    result.addView(reportReadyCard(f, res.optInt("users"), res.optInt("days"), res.optString("from"), res.optString("to"), if (res.has("records")) res.optInt("records") else -1))
                } catch (e: Exception) {
                    msg.setTextColor(Pal.c("#DC2626")); msg.text = "Could not prepare the file."
                }
            }
        })
        form.addView(msg)
        content.addView(cardOf(form))
        content.addView(result)
        content.addView(emailReportsCard {
            if (choice == "custom" && (from.isEmpty() || to.isEmpty())) null
            else JSONObject().put("period", choice).apply { if (choice == "custom") put("from", from).put("to", to) }
        })

        // ---- 2. what is inside ----
        val info = column()
        info.addView(label("What is in the file", 16f, "#3E1D16", true))
        info.addView(label("📋  Summary", 14f, "#6B352A", true).apply { setPadding(0, dp(10), 0, 0) })
        info.addView(infoSummary)
        info.addView(infoDetailTitle)
        info.addView(infoDetail)
        content.addView(cardOf(info))
        refresh()
        refreshKind()
    }

    private fun reportReadyCard(f: File, users: Int, days: Int, from: String, to: String, records: Int = -1): View {
        val col = column()
        col.addView(label("✅  Your Excel file is ready", 16f, "#166534", true))
        col.addView(label(f.name, 13f, "#3E1D16", false).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(label("$users people · $days days ($from to $to)" + (if (records >= 0) " · $records night hold${if (records == 1) "" else "s"}" else ""), 13f, "#8A5F52", false))
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
                    val uri = FileProvider.getUriForFile(this@AdminActivity, "$packageName.fileprovider", f)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(xlsxType)
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(android.content.Intent.createChooser(send, "Share attendance file"))
                } catch (e: Exception) { toast("Could not share the file.") }
            }
        })
        col.addView(buttons)
        return cardOf(col)
    }

    // ---------- TARGETS (upload the Target file and the Sale Collection file) ----------

    private var importKind = ""
    private val pickExcel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importExcel(uri, importKind)
    }

    private fun chooseExcel(kind: String) {
        importKind = kind
        pickExcel.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/vnd.ms-excel", "*/*"))
    }

    private fun loadTargets() {
        val id = ++loadId
        content.removeAllViews()
        val status = note("Loading…")
        content.addView(status)
        api(JSONObject().put("action", "adminTargetStatus")) { res, err ->
            if (id != loadId) return@api
            content.removeView(status)
            if (res == null) { content.addView(note(err ?: "Could not load.")); return@api }
            showTargets(res)
        }
    }

    private fun showTargets(res: JSONObject) {
        val tg = res.optJSONObject("target")
        val co = res.optJSONObject("collection")
        val head = column()
        head.addView(label("🎯  Target vs Collection", 16f, "#3E1D16", true))
        head.addView(label("Upload two Excel files. Everyone then sees their own target against what they collected; managers also see their team.", 13f, "#8A5F52", false).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(cardOf(head))

        val t = column()
        t.addView(label("1.  Target file", 15f, "#3E1D16", true))
        t.addView(label("Columns: Username, Name, Base Code, Base Name, Target (one target per person).", 12f, "#8A5F52", false))
        t.addView(label(if (tg == null) "Not uploaded yet." else "✅  ${tg.optInt("people")} people · total ${taka(tg.optDouble("total"))}\nUploaded ${tg.optString("on")} by ${tg.optString("by")}", 13f, if (tg == null) "#B45309" else "#166534", tg != null).apply { setPadding(0, dp(6), 0, 0) })
        t.addView(bigButton("📤  Upload Target file") { chooseExcel("target") })
        content.addView(cardOf(t))

        val c = column()
        c.addView(label("2.  Sale Collection report", 15f, "#3E1D16", true))
        c.addView(label("Columns used: Collection Date, Collection Amount, Collection Type and Username (the Username decides who the money counts for).", 12f, "#8A5F52", false))
        if (co == null) c.addView(label("Not uploaded yet.", 13f, "#B45309", false).apply { setPadding(0, dp(6), 0, 0) })
        else {
            c.addView(label("✅  ${co.optInt("rows")} rows · ${co.optString("from")} to ${co.optString("to")}\nCounted total ${taka(co.optDouble("total"))}\nUploaded ${co.optString("on")} by ${co.optString("by")}", 13f, "#166534", true).apply { setPadding(0, dp(6), 0, 0) })
            if (co.optDouble("unassigned") != 0.0)
                c.addView(label("⚠️  ${taka(co.optDouble("unassigned"))} has no Username and is not counted for anyone.", 12f, "#B45309", false).apply { setPadding(0, dp(4), 0, 0) })
        }
        c.addView(bigButton("📤  Upload Collection report") { chooseExcel("collection") })
        content.addView(cardOf(c))

        val per = res.optJSONObject("period")
        if (per != null) {
            val d = per.getJSONObject("days")
            val p = column()
            p.addView(label("Period and working days", 15f, "#3E1D16", true))
            p.addView(label("${per.optString("from")} to ${per.optString("to")}" + (if (per.optBoolean("auto")) "  (automatic: the month of the collection report)" else "  (set by you)"), 13f, "#6B352A", true).apply { setPadding(0, dp(6), 0, 0) })
            p.addView(label("Working days ${d.optInt("total")}  ·  passed ${d.optInt("passed")}  ·  remaining ${d.optInt("remaining")}" + (if (d.optString("asOf").isNotEmpty()) "\ncounted up to ${d.optString("asOf")}" else ""), 13f, "#6B352A", true))
            p.addView(label("Fridays and the holidays in the 🎉 Holiday tab are not working days. RR = (target − collection) ÷ remaining working days.", 12f, "#8A5F52", false).apply { setPadding(0, dp(4), 0, 0) })
            var nf = ""; var nt = ""
            val chipF = dateChip("Start") { nf = it }; val chipT = dateChip("End") { nt = it }
            chipF.visibility = View.VISIBLE; chipT.visibility = View.VISIBLE
            p.addView(chipF); p.addView(chipT)
            val pmsg = label("", 13f, "#DC2626", false)
            p.addView(bigButton("💾  Use these dates") {
                if (nf.isEmpty() || nt.isEmpty()) { pmsg.text = "Choose the Start and End dates"; return@bigButton }
                api(JSONObject().put("action", "adminTargetSettings").put("from", nf).put("to", nt)) { r, e -> if (r != null) { toast("Saved"); loadTargets() } else pmsg.text = e ?: "Could not save" }
            })
            if (!per.optBoolean("auto")) p.addView(chipButton("↩  Use the month of the report again") {
                api(JSONObject().put("action", "adminTargetSettings").put("auto", true)) { r, e -> if (r != null) { toast("Saved"); loadTargets() } else pmsg.text = e ?: "Could not save" }
            })
            p.addView(label("Passed days are counted up to", 13f, "#6B352A", true).apply { setPadding(0, dp(12), 0, 0) })
            val rg = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
            val rbData = RadioButton(this).apply { text = "the last date in the Collection report"; id = View.generateViewId() }
            val rbToday = RadioButton(this).apply { text = "today"; id = View.generateViewId() }
            rg.addView(rbData); rg.addView(rbToday)
            if (per.optString("asOfSetting") == "today") rbToday.isChecked = true else rbData.isChecked = true
            rg.setOnCheckedChangeListener { _, checked ->
                api(JSONObject().put("action", "adminTargetSettings").put("asOf", if (checked == rbToday.id) "today" else "data")) { r, e -> if (r != null) { toast("Saved"); loadTargets() } else pmsg.text = e ?: "Could not save" }
            }
            p.addView(rg); p.addView(pmsg)
            content.addView(cardOf(p))
        }

        val types = res.optJSONArray("types")
        if (types != null && types.length() > 0) {
            val k = column()
            k.addView(label("What counts as collected", 15f, "#3E1D16", true))
            k.addView(label("Tick the Collection Types that count towards the target. Untick things such as Bank Charge.", 12f, "#8A5F52", false))
            val boxes = ArrayList<Pair<String, CheckBox>>()
            for (i in 0 until types.length()) {
                val o = types.getJSONObject(i)
                val cb = CheckBox(this).apply { text = "${o.optString("type")}   (${taka(o.optDouble("amount"))})"; textSize = 14f; isChecked = o.optBoolean("counted") }
                boxes.add(Pair(o.optString("type"), cb)); k.addView(cb)
            }
            val msg = label("", 13f, "#DC2626", false)
            k.addView(bigButton("💾  Save") {
                val keep = JSONArray(boxes.filter { it.second.isChecked }.map { it.first })
                if (keep.length() == 0) { msg.text = "Tick at least one type"; return@bigButton }
                api(JSONObject().put("action", "adminTargetTypes").put("types", keep)) { r, e ->
                    if (r != null) { toast("Saved"); loadTargets() } else { msg.text = e ?: "Could not save" }
                }
            })
            k.addView(msg)
            content.addView(cardOf(k))
        }
    }

    /** Reads the chosen Excel file on the phone, shows what was found and asks for the admin's password before uploading. */
    private fun importExcel(uri: android.net.Uri, kind: String) {
        toast("Reading the file…")
        Thread {
            var summary = ""
            var rows: JSONArray? = null
            var problem: String? = null
            try {
                val table = contentResolver.openInputStream(uri)?.use { Xlsx.read(it) } ?: throw IllegalArgumentException("Could not open the file.")
                val arr = JSONArray()
                if (kind == "target") {
                    val r = TargetImport.parseTarget(table)
                    r.rows.forEach { arr.put(JSONObject().put("u", it.username).put("n", it.name).put("bc", it.baseCode).put("bn", it.baseName).put("t", it.target)) }
                    summary = "Target file\n\n${r.rows.size} people\nTotal target ${taka(r.total)}" + (if (r.skipped > 0) "\n${r.skipped} rows skipped (no Username or no Target number)" else "")
                } else {
                    val r = TargetImport.parseCollection(table)
                    r.rows.forEach { arr.put(JSONObject().put("d", it.date).put("u", it.username).put("k", it.type).put("a", it.amount).put("c", it.count)
                        .put("n", it.name).put("bc", it.baseCode).put("bn", it.baseName).put("dc", it.dealerCode).put("dn", it.dealerName)) }
                    summary = "Sale Collection report\n\n${r.fileRows} rows in the file · ${r.from} to ${r.to}\nTotal ${taka(r.total)}\n${r.people} people have a Username"
                    summary += if (r.hasDealer) "\n${r.dealers} dealers (Dealer Code and Dealer Name are saved, with Base Code, Base Name and Name)" else "\n⚠️ No Customer Code / Customer Name (dealer) columns found, so no dealer details are saved."
                    if (r.unassignedRows > 0) summary += "\n\n⚠️ ${r.unassignedRows} rows (${taka(r.unassigned)}) have NO Username. They cannot be given to anyone and will not count."
                    if (r.skipped > 0) summary += "\n${r.skipped} rows skipped (no valid date or amount)"
                    summary += "\n\nTypes: " + r.types.entries.joinToString(", ") { "${it.key} ${taka(it.value)}" }
                }
                rows = arr
            } catch (e: IllegalArgumentException) { problem = e.message
            } catch (e: Exception) { problem = "Could not read this file. Is it an Excel (.xlsx) file?"
            } catch (e: OutOfMemoryError) { problem = "This file is too big to read on the phone." }
            runOnUiThread {
                if (problem != null || rows == null) { AlertDialog.Builder(this).setTitle("Cannot use this file").setMessage(problem ?: "Unknown problem").setPositiveButton("OK", null).show(); return@runOnUiThread }
                val pw = passField("Your own password (to confirm)")
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
                box.addView(label(summary + "\n\nThis REPLACES the " + (if (kind == "target") "targets" else "collection data") + " now loaded.", 13f, "#6B352A", false))
                box.addView(pw)
                AlertDialog.Builder(this).setTitle("Upload this file?").setView(ScrollView(this).apply { addView(box) })
                    .setPositiveButton("Upload") { _, _ ->
                        toast("Uploading…")
                        api(JSONObject().put("action", "adminTargetUpload").put("kind", kind).put("rows", rows).put("adminPassword", pw.text.toString())) { r, e ->
                            if (r == null) { AlertDialog.Builder(this).setTitle("Not uploaded").setMessage(e ?: "Unknown problem").setPositiveButton("OK", null).show(); return@api }
                            var t = if (kind == "target") "Loaded ${r.optInt("people")} targets (total ${taka(r.optDouble("total"))})." else "Loaded ${r.optInt("rows")} collection lines, ${r.optString("from")} to ${r.optString("to")}, total ${taka(r.optDouble("total"))}."
                            t += "\n${r.optInt("matched")} ${if (kind == "target") "of them are" else "usernames are"} app users."
                            if (r.optInt("notInApp") > 0) {
                                val s = r.optJSONArray("notInAppSample")
                                t += "\n${r.optInt("notInApp")} Username${if (r.optInt("notInApp") == 1) " is" else "s are"} not an app user" + (if (s != null && s.length() > 0) ": " + (0 until s.length()).joinToString(", ") { s.optString(it) } else "") + ". Add them in Users with the same Username to see them."
                            }
                            AlertDialog.Builder(this).setTitle("Uploaded").setMessage(t).setPositiveButton("OK", null).show()
                            loadTargets()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }.start()
    }

    // ---------- E-mail every person their own attendance / night hold report ----------

    private fun ensureUsers(done: () -> Unit) {
        if (users.isNotEmpty()) { done(); return }
        api(JSONObject().put("action", "adminUsers")) { r, e ->
            if (r != null) { users = usersFrom(r); done() } else toast(e ?: "Could not load the people list")
        }
    }

    /** [period] gives {period[,from,to]} for the period chosen above, or null when the dates are not chosen yet. */
    private fun emailReportsCard(period: () -> JSONObject?): View {
        var scope = "all"
        var teamManager = ""
        val pickedUsers = LinkedHashSet<String>()
        val cbAtt = CheckBox(this).apply { text = "📍  Attendance report"; isChecked = true; textSize = 14f }
        val cbNight = CheckBox(this).apply { text = "🌙  Night hold report"; isChecked = true; textSize = 14f }
        val rgScope = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val rbAll = RadioButton(this).apply { text = "Everyone"; id = View.generateViewId() }
        val rbTeam = RadioButton(this).apply { text = "A whole team (a manager and everyone below)"; id = View.generateViewId() }
        val rbUsers = RadioButton(this).apply { text = "Selected people"; id = View.generateViewId() }
        rgScope.addView(rbAll); rgScope.addView(rbTeam); rgScope.addView(rbUsers); rbAll.isChecked = true
        val btnWho = chipButton("") {}
        btnWho.visibility = View.GONE
        fun refreshWho() {
            btnWho.text = if (scope == "team") (if (teamManager.isEmpty()) "Choose the manager  ▾" else "Team of: $teamManager  ▾")
            else (if (pickedUsers.isEmpty()) "Choose the people  ▾" else "${pickedUsers.size} selected: " + pickedUsers.joinToString(", ") + "  ▾")
        }
        rgScope.setOnCheckedChangeListener { _, checked ->
            scope = when (checked) { rbTeam.id -> "team"; rbUsers.id -> "users"; else -> "all" }
            btnWho.visibility = if (scope == "all") View.GONE else View.VISIBLE
            refreshWho()
        }
        btnWho.setOnClickListener {
            ensureUsers {
                if (scope == "team") {
                    val bosses = users.filter { u -> users.any { it.optString("manager").equals(u.optString("username"), true) } }
                    val labels = bosses.map { "${it.optString("name")}  (${it.optString("username")})" }
                    if (labels.isEmpty()) { toast("Nobody has a team yet."); return@ensureUsers }
                    PickerDialog.show(this, "Whose team?", labels) { picked -> teamManager = bosses[labels.indexOf(picked)].optString("username"); refreshWho() }
                } else pickPeople(pickedUsers) { refreshWho() }
            }
        }
        val etPw = passField("Your own password (to confirm)")
        val msg = label("", 14f, "#DC2626", false).apply { setPadding(0, dp(8), 0, 0) }

        fun fail(t: String) { msg.setTextColor(Pal.c("#DC2626")); msg.text = t }
        fun info(t: String) { msg.setTextColor(Pal.c("#8A5F52")); msg.text = t }

        // sends 10 people per request, one request after another, so a long list never times out
        fun sendAll(ids: List<String>, per: JSONObject, pw: String) {
            val batch = 10
            fun step(at: Int, sent: Int, failed: Int) {
                if (at >= ids.size) {
                    msg.setTextColor(Pal.c(if (failed == 0) "#166534" else "#B45309"))
                    msg.text = "✅  Sent $sent e-mail${if (sent == 1) "" else "s"}" + (if (failed > 0) " · $failed could not be sent" else "") + "."
                    etPw.setText("")
                    return
                }
                info("Sending… $at of ${ids.size} done")
                val body = JSONObject(per.toString()).put("action", "adminEmailSend").put("adminPassword", pw)
                    .put("attendance", cbAtt.isChecked).put("night", cbNight.isChecked)
                    .put("target", JSONArray(ids.subList(at, minOf(at + batch, ids.size))))
                api(body) { r, e ->
                    if (r == null) { fail("Stopped after $sent sent" + (if (failed > 0) " ($failed failed)" else "") + ": " + (e ?: "unknown problem")); return@api }
                    step(at + batch, sent + r.optInt("sent"), failed + r.optInt("failed"))
                }
            }
            step(0, 0, 0)
        }

        val col = column()
        col.addView(label("✉️  E-mail reports to staff", 18f, "#3E1D16", true))
        col.addView(label("Each person gets only their own report, as an Excel file, at the e-mail address in their My Profile. It uses the period chosen above.", 13f, "#8A5F52", false))
        col.addView(label("REPORTS", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(14), 0, 0) })
        col.addView(cbAtt); col.addView(cbNight)
        col.addView(label("WHO GETS IT", 12f, "#6B352A", true).apply { letterSpacing = 0.08f; setPadding(0, dp(10), 0, 0) })
        col.addView(rgScope); col.addView(btnWho); col.addView(etPw)
        col.addView(bigButton("✉️  Check and send") {
            val per = period()
            when {
                per == null -> fail("Choose the From and To dates above first")
                !cbAtt.isChecked && !cbNight.isChecked -> fail("Tick at least one report")
                scope == "team" && teamManager.isEmpty() -> fail("Choose the manager whose team gets it")
                scope == "users" && pickedUsers.isEmpty() -> fail("Choose at least one person")
                etPw.text.isNullOrEmpty() -> fail("Enter your own password to confirm")
                else -> {
                    info("Checking who has an e-mail address…")
                    val pv = JSONObject().put("action", "adminEmailPreview").put("scope", scope)
                    if (scope == "team") pv.put("target", teamManager)
                    if (scope == "users") pv.put("target", JSONArray(pickedUsers.toList()))
                    api(pv) { r, e ->
                        if (r == null) { fail(e ?: "Could not check"); return@api }
                        val n = r.optInt("count"); val noMail = r.optInt("noEmail"); val quota = r.optInt("quota")
                        msg.text = ""
                        when {
                            !r.optBoolean("authorized") -> fail("E-mail is not allowed yet. In the Google Apps Script editor run the function authorizeEmail once and approve it.")
                            n == 0 -> fail("Nobody has an e-mail address saved in My Profile.")
                            n > quota -> fail("Only $quota e-mails can still be sent today (Google's daily limit) but $n are needed. Choose fewer people, or try again tomorrow.")
                            else -> {
                                val names = (0 until minOf(r.optJSONArray("noEmailNames")?.length() ?: 0, 8)).joinToString(", ") { r.getJSONArray("noEmailNames").getString(it) }
                                var t = "$n ${if (n == 1) "person" else "people"} will get their own report by e-mail."
                                if (noMail > 0) t += "\n\n$noMail ${if (noMail == 1) "has" else "have"} no e-mail saved and will be skipped" + (if (names.isNotEmpty()) " ($names)" else "") + "."
                                t += "\n\nThis uses $n of the $quota e-mails left today."
                                val ids = (0 until r.getJSONArray("people").length()).map { r.getJSONArray("people").getString(it) }
                                AlertDialog.Builder(this).setTitle("Send the reports?").setMessage(t)
                                    .setPositiveButton("Send") { _, _ -> sendAll(ids, per, etPw.text.toString()) }
                                    .setNegativeButton("Cancel", null).show()
                            }
                        }
                    }
                }
            }
        })
        col.addView(msg)
        return cardOf(col)
    }

    // ---------- SMS (text messages through SimGate) ----------

    private fun loadSms() {
        val id = ++loadId
        content.removeAllViews()
        val status = note("Loading…")
        content.addView(status)
        api(JSONObject().put("action", "adminSmsSettings")) { res, err ->
            if (id != loadId) return@api
            if (res == null) { status.text = err ?: "Could not load."; return@api }
            if (users.isNotEmpty()) { content.removeView(status); showSms(res); return@api }
            api(JSONObject().put("action", "adminUsers")) { r2, _ ->          // the pickers need the people list
                if (id != loadId) return@api
                if (r2 != null) users = usersFrom(r2)
                content.removeView(status); showSms(res)
            }
        }
    }

    private fun showSms(res: JSONObject) {
        val configured = res.optBoolean("configured")
        // ---- 1. status ----
        val st = column()
        st.addView(label("📱  SMS to people's phones", 16f, "#3E1D16", true))
        if (configured) {
            st.addView(label("✅  Ready", 15f, "#166534", true).apply { setPadding(0, dp(6), 0, 0) })
            st.addView(label("Device ${res.optString("deviceId")} · key ${res.optString("keyHint")}", 13f, "#8A5F52", false))
            st.addView(label("This month (${res.optString("month")}): ${res.optInt("used")} sent · ${res.optInt("left")} left of ${res.optInt("limit")}", 13f, "#8A5F52", false))
        } else {
            st.addView(label("⚠️  Not set up yet. Follow the steps below.", 14f, "#B45309", true).apply { setPadding(0, dp(6), 0, 0) })
        }
        st.addView(label("Through SimGate: your own Android phone (with a SIM) sends the texts. The monthly limit below is a safety limit counted by this app.", 13f, "#8A5F52", false).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(cardOf(st))

        // ---- 2. setup ----
        val etKey = passField("API key from the SimGate app" + (if (res.optBoolean("hasKey")) " (leave empty to keep the saved one)" else ""))
        val etFrom = field("Device ID from the SimGate app" + (if (res.optString("deviceId").isNotEmpty()) " (now ${res.optString("deviceId")})" else ""), InputType.TYPE_CLASS_TEXT)
        val etLimit = field("Monthly limit (default 200)", InputType.TYPE_CLASS_NUMBER)
        val etPw = passField("Your own password (to confirm)")
        val msgSetup = label("", 14f, "#DC2626", false)
        val setup = column()
        setup.addView(label("Set up", 16f, "#3E1D16", true))
        setup.addView(label("1. On a spare Android phone with a SIM, install the SimGate app and register the device.\n2. In the SimGate app copy the API key and the Device ID.\n3. Paste both here and Save.\n4. In the Google Apps Script editor run authorizeSms once (Run, then Review permissions).\nThe key is kept privately on the server. It is never shown again, never in the app and never in the Sheet.", 13f, "#8A5F52", false))
        setup.addView(etKey); setup.addView(etFrom); setup.addView(etLimit); setup.addView(etPw)
        setup.addView(bigButton("💾  Save SMS settings") {
            if (etPw.text.isNullOrEmpty()) { msgSetup.setTextColor(Pal.c("#DC2626")); msgSetup.text = "Enter your own password to confirm"; return@bigButton }
            msgSetup.setTextColor(Pal.c("#8A5F52")); msgSetup.text = "Saving…"
            val body = JSONObject().put("action", "adminSmsSave").put("apiKey", etKey.text.toString().trim())
                .put("deviceId", etFrom.text.toString().trim()).put("adminPassword", etPw.text.toString())
            etLimit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { body.put("limit", it) }
            api(body) { r, e ->
                if (r != null) { toast("SMS settings saved"); loadSms() } else { msgSetup.setTextColor(Pal.c("#DC2626")); msgSetup.text = e }
            }
        })
        if (configured) setup.addView(bigButton("✉️  Send a test text to my phone") {
            msgSetup.setTextColor(Pal.c("#8A5F52")); msgSetup.text = "Sending a test to the phone number in your My Profile…"
            api(JSONObject().put("action", "adminSmsTest")) { r, e ->
                if (r != null) { msgSetup.setTextColor(Pal.c("#166534")); msgSetup.text = "Sent. It should reach your phone in a moment." }
                else { msgSetup.setTextColor(Pal.c("#DC2626")); msgSetup.text = e }
            }
        })
        setup.addView(msgSetup)
        content.addView(cardOf(setup))
        if (!configured) return

        // ---- 3. send a message ----
        var scope = "all"
        var teamManager = ""
        val pickedUsers = LinkedHashSet<String>()
        val rgScope = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val rbAll = RadioButton(this).apply { text = "Everyone"; id = View.generateViewId() }
        val rbTeam = RadioButton(this).apply { text = "A whole team (a manager and everyone below)"; id = View.generateViewId() }
        val rbUsers = RadioButton(this).apply { text = "Selected people"; id = View.generateViewId() }
        rgScope.addView(rbAll); rgScope.addView(rbTeam); rgScope.addView(rbUsers); rbAll.isChecked = true
        val btnWho = chipButton("") {}
        btnWho.visibility = View.GONE
        fun refreshWho() {
            btnWho.text = if (scope == "team") (if (teamManager.isEmpty()) "Choose the manager  ▾" else "Team of: $teamManager  ▾")
            else (if (pickedUsers.isEmpty()) "Choose the people  ▾" else "${pickedUsers.size} selected: " + pickedUsers.joinToString(", ") + "  ▾")
        }
        rgScope.setOnCheckedChangeListener { _, checked ->
            scope = when (checked) { rbTeam.id -> "team"; rbUsers.id -> "users"; else -> "all" }
            btnWho.visibility = if (scope == "all") View.GONE else View.VISIBLE
            refreshWho()
        }
        btnWho.setOnClickListener {
            if (scope == "team") {
                val bosses = users.filter { u -> users.any { it.optString("manager").equals(u.optString("username"), true) } }
                val labels = bosses.map { "${it.optString("name")}  (${it.optString("username")})" }
                if (labels.isEmpty()) { toast("Nobody has a team yet."); return@setOnClickListener }
                PickerDialog.show(this, "Whose team?", labels) { picked -> teamManager = bosses[labels.indexOf(picked)].optString("username"); refreshWho() }
            } else pickPeople(pickedUsers) { refreshWho() }
        }
        val etText = field("Message (up to 300 characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(300))
        }
        val etPw2 = passField("Your own password (to confirm)")
        val msg = label("", 14f, "#DC2626", false)
        val send = column()
        send.addView(label("Send a text message", 16f, "#3E1D16", true))
        send.addView(label("Each person gets one SMS on the Official phone in their profile (or the Personal phone if there is no Official one). Every send uses your monthly allowance and is listed on the SmsLog tab of the Sheet.", 13f, "#8A5F52", false))
        send.addView(label("Who gets it?", 14f, "#6B352A", true).apply { setPadding(0, dp(10), 0, 0) })
        send.addView(rgScope); send.addView(btnWho); send.addView(etText); send.addView(etPw2)
        send.addView(bigButton("📱  Send SMS") {
            val text = etText.text.toString().trim()
            when {
                text.length < 2 -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Write the message first" }
                scope == "team" && teamManager.isEmpty() -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Choose the manager whose team gets it" }
                scope == "users" && pickedUsers.isEmpty() -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Choose at least one person" }
                etPw2.text.isNullOrEmpty() -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Enter your own password to confirm" }
                else -> {
                    val who = when (scope) { "all" -> "everyone"; "team" -> "the team of $teamManager"; else -> "${pickedUsers.size} selected ${if (pickedUsers.size == 1) "person" else "people"}" }
                    AlertDialog.Builder(this).setTitle("Send this SMS?").setMessage("To $who:\n\n$text")
                        .setPositiveButton("Send") { _, _ ->
                            msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Sending…"
                            val body = JSONObject().put("action", "adminSmsSend").put("scope", scope).put("message", text).put("adminPassword", etPw2.text.toString())
                            if (scope == "team") body.put("target", teamManager)
                            if (scope == "users") body.put("target", JSONArray(pickedUsers.toList()))
                            api(body) { r, e ->
                                if (r != null) {
                                    toast(SmsUi.summary(r)); loadSms()
                                } else { msg.setTextColor(Pal.c("#DC2626")); msg.text = e }
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        })
        send.addView(msg)
        content.addView(cardOf(send))
    }

    // ---------- ENTRY (night hold hours, and manual attendance / night hold for a date the admin opens) ----------

    private fun hourLabel(h: Int) = "${if (h % 12 == 0) 12 else h % 12}:00 ${if (h < 12) "AM" else "PM"}"

    private fun usersFrom(res: JSONObject): List<JSONObject> {
        val arr = res.optJSONArray("users")
        val list = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return list
    }

    private fun loadEntry() {
        val id = ++loadId
        content.removeAllViews()
        val status = note("Loading…")
        content.addView(status)
        api(JSONObject().put("action", "adminEntrySettings")) { res, err ->
            if (id != loadId) return@api
            if (res == null) { status.text = err ?: "Could not load."; return@api }
            if (users.isNotEmpty()) { content.removeView(status); showEntry(res); return@api }
            api(JSONObject().put("action", "adminUsers")) { r2, _ ->       // the pickers need the people list
                if (id != loadId) return@api
                if (r2 != null) users = usersFrom(r2)
                content.removeView(status)
                showEntry(res)
            }
        }
    }

    private fun showEntry(res: JSONObject) {
        // ---- 1. night hold hours ----
        var open = res.optInt("nightOpen", 21)
        var close = res.optInt("nightClose", 6)
        val hours = (0..23).map { hourLabel(it) }
        val btnOpen = chipButton("Opens at: ${hourLabel(open)}  ▾") {}
        val btnClose = chipButton("Closes at: ${hourLabel(close)}  ▾") {}
        btnOpen.setOnClickListener { PickerDialog.show(this, "Night hold opens at", hours) { p -> open = hours.indexOf(p); btnOpen.text = "Opens at: $p  ▾" } }
        btnClose.setOnClickListener { PickerDialog.show(this, "Night hold closes at", hours) { p -> close = hours.indexOf(p); btnClose.text = "Closes at: $p  ▾" } }
        val msgHours = label("", 14f, "#DC2626", false)
        val f1 = column()
        f1.addView(label("Night hold hours", 16f, "#3E1D16", true))
        f1.addView(label("People can submit their night hold only inside these hours. Now: ${res.optString("label")}. If the closing hour is earlier than the opening hour, the hours run through midnight (for example 9 PM to 6 AM).", 13f, "#8A5F52", false))
        f1.addView(btnOpen); f1.addView(btnClose)
        f1.addView(bigButton("💾  Save night hold hours") {
            msgHours.setTextColor(Pal.c("#8A5F52")); msgHours.text = "Saving…"
            api(JSONObject().put("action", "adminSetNightHours").put("open", open).put("close", close)) { r, err ->
                if (r != null) { toast("Saved: ${r.optString("label")}"); dropSavedCopies(); loadEntry() }
                else { msgHours.setTextColor(Pal.c("#DC2626")); msgHours.text = err }
            }
        })
        f1.addView(msgHours)
        content.addView(cardOf(f1))

        // ---- 2. open a date for manual attendance / night hold ----
        var kind = "attendance"
        var pickedDate = ""
        var scope = "all"
        var teamManager = ""
        val pickedUsers = LinkedHashSet<String>()

        val rgKind = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rbAtt = RadioButton(this).apply { text = "Attendance"; id = View.generateViewId() }
        val rbNight = RadioButton(this).apply { text = "Night hold"; id = View.generateViewId() }
        rgKind.addView(rbAtt); rgKind.addView(rbNight); rbAtt.isChecked = true
        rgKind.setOnCheckedChangeListener { _, checked -> kind = if (checked == rbNight.id) "night" else "attendance" }

        val btnDate = chipButton("📅  Choose the date") {}
        btnDate.setOnClickListener {
            val c = Calendar.getInstance()
            val dlg = DatePickerDialog(this, { _, y, m, d ->
                pickedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                btnDate.text = "📅  " + (try { nice.format(iso.parse(pickedDate)!!) } catch (e: Exception) { pickedDate })
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            dlg.datePicker.maxDate = System.currentTimeMillis()          // today or an earlier day only
            dlg.show()
        }

        val rgScope = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val rbAll = RadioButton(this).apply { text = "Everyone"; id = View.generateViewId() }
        val rbTeam = RadioButton(this).apply { text = "A whole team (a manager and everyone below)"; id = View.generateViewId() }
        val rbUsers = RadioButton(this).apply { text = "Selected people"; id = View.generateViewId() }
        rgScope.addView(rbAll); rgScope.addView(rbTeam); rgScope.addView(rbUsers); rbAll.isChecked = true
        val btnWho = chipButton("") {}
        btnWho.visibility = View.GONE
        fun refreshWho() {
            btnWho.text = when (scope) {
                "team" -> if (teamManager.isEmpty()) "Choose the manager  ▾" else "Team of: $teamManager  ▾"
                else -> if (pickedUsers.isEmpty()) "Choose the people  ▾" else "${pickedUsers.size} selected: " + pickedUsers.joinToString(", ") + "  ▾"
            }
        }
        rgScope.setOnCheckedChangeListener { _, checked ->
            scope = when (checked) { rbTeam.id -> "team"; rbUsers.id -> "users"; else -> "all" }
            btnWho.visibility = if (scope == "all") View.GONE else View.VISIBLE
            refreshWho()
        }
        btnWho.setOnClickListener {
            if (scope == "team") {
                val bosses = users.filter { u -> users.any { it.optString("manager").equals(u.optString("username"), true) } }
                val labels = bosses.map { "${it.optString("name")}  (${it.optString("username")})" }
                if (labels.isEmpty()) { toast("Nobody has a team yet."); return@setOnClickListener }
                PickerDialog.show(this, "Whose team?", labels) { picked -> teamManager = bosses[labels.indexOf(picked)].optString("username"); refreshWho() }
            } else pickPeople(pickedUsers) { refreshWho() }
        }

        val etTime = field("Time to record (optional, 24-hour, for example 09:00)", InputType.TYPE_CLASS_TEXT)
        val etAdminPw = passField("Your own password (to confirm)")
        val msg = label("", 14f, "#DC2626", false)
        val f2 = column()
        f2.addView(label("Open a date for manual entry", 16f, "#3E1D16", true))
        f2.addView(label("Choose a day and who may fill it in. Those people then see \"Manual entry\" on their Home menu and submit it like normal (live selfie and GPS are still needed). Each person can fill each day once. Leave the time empty to record the real time.", 13f, "#8A5F52", false))
        f2.addView(label("What can they enter?", 14f, "#6B352A", true).apply { setPadding(0, dp(10), 0, 0) })
        f2.addView(rgKind)
        f2.addView(btnDate)
        f2.addView(label("Who can enter it?", 14f, "#6B352A", true).apply { setPadding(0, dp(10), 0, 0) })
        f2.addView(rgScope); f2.addView(btnWho)
        f2.addView(etTime); f2.addView(etAdminPw)
        f2.addView(bigButton("✅  Open this date") {
            val pw = etAdminPw.text.toString()
            when {
                pickedDate.isEmpty() -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Choose the date first" }
                scope == "team" && teamManager.isEmpty() -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Choose the manager whose team may enter it" }
                scope == "users" && pickedUsers.isEmpty() -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Choose at least one person" }
                pw.isEmpty() -> { msg.setTextColor(Pal.c("#DC2626")); msg.text = "Enter your own password to confirm" }
                else -> {
                    msg.setTextColor(Pal.c("#8A5F52")); msg.text = "Saving…"
                    val body = JSONObject().put("action", "adminAllowAdd").put("kind", kind).put("date", pickedDate).put("scope", scope)
                        .put("time", etTime.text.toString().trim()).put("adminPassword", pw)
                    if (scope == "team") body.put("target", teamManager)
                    if (scope == "users") body.put("target", JSONArray(pickedUsers.toList()))
                    api(body) { r, err ->
                        if (r != null) { toast("Date opened"); loadEntry() }
                        else { msg.setTextColor(Pal.c("#DC2626")); msg.text = err }
                    }
                }
            }
        })
        f2.addView(msg)
        content.addView(cardOf(f2))

        // ---- 3. dates already opened ----
        val arr = res.optJSONArray("allowances")
        val n = arr?.length() ?: 0
        content.addView(heading("OPENED DATES ($n)"))
        if (arr == null || n == 0) { content.addView(note("No manual-entry dates yet.")); return }
        for (i in 0 until n) content.addView(allowCard(arr.getJSONObject(i)))
    }

    /** A multi-pick list with a search box. [chosen] is updated in place; [done] runs when the admin taps OK. */
    private fun pickPeople(chosen: MutableSet<String>, done: () -> Unit) {
        val rows = users.filter { !it.optBoolean("inactive") }.map { Pair(it.optString("username"), "${it.optString("name")}  (${it.optString("username")})") }
        var shown: List<Pair<String, String>> = rows
        val search = SeekField(this, "Type to search", openOnShow = true)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, ArrayList<String>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as android.widget.CheckedTextView
                v.isChecked = chosen.contains(shown[position].first)
                return v
            }
        }
        // "Select all" works on the people currently listed (type in the search box first to select only a group, e.g. "Md.").
        val selectAll = CheckBox(this).apply { textSize = 15f; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        fun syncAll() {
            selectAll.text = "Select all (${shown.size} shown)"
            selectAll.setOnCheckedChangeListener(null)
            selectAll.isChecked = shown.isNotEmpty() && shown.all { chosen.contains(it.first) }
            selectAll.setOnCheckedChangeListener { _, on ->
                shown.forEach { if (on) chosen.add(it.first) else chosen.remove(it.first) }
                adapter.notifyDataSetChanged(); syncAll()
            }
        }
        fun refill(q: String) {
            shown = if (q.isEmpty()) rows else rows.filter { it.second.contains(q, ignoreCase = true) }
            adapter.clear(); adapter.addAll(shown.map { it.second }); adapter.notifyDataSetChanged(); syncAll()
        }
        refill("")
        val list = ListView(this).apply { this.adapter = adapter }
        list.setOnItemClickListener { _, _, pos, _ ->
            val u = shown[pos].first
            if (!chosen.add(u)) chosen.remove(u)
            adapter.notifyDataSetChanged(); syncAll()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { refill(e?.toString()?.trim() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(selectAll)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        }
        AlertDialog.Builder(this).setTitle("Choose the people").setView(box)
            .setPositiveButton("OK") { _, _ -> done() }
            .setNeutralButton("Clear") { _, _ -> chosen.clear(); done() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun allowCard(o: JSONObject): View {
        val id = o.optString("id")
        val active = o.optBoolean("active", true)
        val isNight = o.optString("kind") == "night"
        val date = o.optString("date")
        val shown = try { nice.format(iso.parse(date)!!) } catch (e: Exception) { date }
        val who = when (o.optString("scope")) {
            "all" -> "Everyone"
            "team" -> "Team of ${o.optString("targetName")}"
            else -> o.optString("targetName")
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(label((if (isNight) "🌙 Night hold" else "📍 Attendance") + " · $shown", 15f, if (active) "#3E1D16" else "#B39284", true))
        text.addView(label("For: $who", 13f, "#8A5F52", false))
        val extra = (if (o.optString("time").isNotEmpty()) "Time recorded: ${o.optString("time")} · " else "") + "by ${o.optString("createdBy")} · ${o.optString("createdOn")}"
        text.addView(label(extra, 12f, "#B39284", false))
        row.addView(text)
        if (active) {
            row.addView(TextView(this).apply {
                this.text = "✕"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Pal.c("#DC2626"))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener {
                    AlertDialog.Builder(this@AdminActivity).setTitle("Switch this off?")
                        .setMessage("$shown · $who\n\nPeople who have not filled it in yet will no longer be able to. Entries already made stay.")
                        .setPositiveButton("Switch off") { _, _ ->
                            api(JSONObject().put("action", "adminAllowRemove").put("id", id)) { r, err ->
                                if (r != null) loadEntry() else toast(err ?: "Could not switch it off")
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
        } else row.addView(pill("OFF", "#8A5F52", "#F8EFC4"))
        return cardOf(row)
    }

    // ---------- APP (publish a new version for everyone) ----------

    private fun loadApp() {
        val id = ++loadId
        content.removeAllViews()
        content.addView(heading("THIS PHONE"))
        content.addView(cardOf(column().apply {
            addView(label("Installed: ${AppUpdate.installedName(this@AdminActivity)}  (build ${AppUpdate.installedCode(this@AdminActivity)})", 15f, "#3E1D16", true))
        }))
        content.addView(heading("OFFERED TO EVERYONE"))
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(holder)
        holder.addView(note("Loading…"))
        content.addView(bigButton("⬆️  Upload new version (APK)") { pickApk.launch("*/*") })
        content.addView(note("How it works: build the APK on GitHub and download it to this phone, then upload it here. Everyone signed in sees an Update button on Home and installs it from inside the app. The file must be signed with the same key as the installed app."))
        api(JSONObject().put("action", "appInfo")) { res, err ->
            if (id != loadId) return@api
            holder.removeAllViews()
            if (res == null) { holder.addView(note(err ?: "Could not load the published version.")); return@api }
            val u = res.optJSONObject("update")
            if (u == null) { holder.addView(note("Nothing is published yet.")); return@api }
            val col = column()
            col.addView(label("Version ${u.optString("versionName")}  (build ${u.optLong("versionCode")})", 16f, "#3E1D16", true))
            col.addView(label("%.1f MB · uploaded ${u.optString("uploadedOn")}".format(u.optLong("size") / 1048576.0), 13f, "#8A5F52", false))
            if (u.optString("notes").isNotEmpty()) col.addView(label(u.optString("notes"), 13f, "#4A241C", false).apply { setPadding(0, dp(6), 0, 0) })
            val mandatory = u.optBoolean("mandatory")
            col.addView(label(if (mandatory) "🔒 COMPULSORY: phones with an older build can use nothing until they install it" else "Optional: people see an Update button on Home and can install it later",
                13f, if (mandatory) "#B91C1C" else "#8A5F52", true).apply { setPadding(0, dp(8), 0, 0) })
            col.addView(chipButton(if (mandatory) "Make it optional" else "Make it compulsory") { mandatoryDialog(!mandatory) })
            col.addView(chipButton("Stop offering this update") { removeUpdateDialog() })
            holder.addView(cardOf(col))
        }
    }

    /** Copies the chosen file into the app's cache while computing its fingerprint, then reads the version out of the APK itself. */
    private fun prepareApk(uri: android.net.Uri) {
        toast("Reading the file…")
        val app = applicationContext
        Thread {
            var problem: String? = null
            var sha = ""
            var size = 0L
            var name = ""
            var code = 0L
            try {
                AppUpdate.cleanUp(app)
                val f = AppUpdate.uploadFile(app)
                val digest = MessageDigest.getInstance("SHA-256")
                val limit = 40L * 1024 * 1024
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > limit) { problem = "That file is too big to be the app."; break }
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                        }
                    }
                } ?: run { problem = "That file could not be opened." }
                if (problem == null) {
                    @Suppress("DEPRECATION")
                    val pi = packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                    when {
                        pi == null -> problem = "That file is not an Android app (APK)."
                        pi.packageName != packageName -> problem = "That APK is not the RM Store Attendance app."
                        else -> { name = pi.versionName ?: ""; code = AppUpdate.codeOf(pi); sha = AppUpdate.hex(digest.digest()) }
                    }
                }
            } catch (e: Exception) {
                problem = "That file could not be read."
            }
            val p = problem
            runOnUiThread {
                if (p != null) { AppUpdate.cleanUp(app); AlertDialog.Builder(this).setTitle("Cannot use this file").setMessage(p).setPositiveButton("OK", null).show() }
                else confirmUpload(name, code, size, sha)
            }
        }.start()
    }

    private fun confirmUpload(name: String, code: Long, size: Long, sha: String) {
        val installed = AppUpdate.installedCode(this)
        val warn = when {
            code < installed -> "\n\n⚠️ This is OLDER than the version on this phone (build $installed). Phones that already have a newer build will not be offered it."
            code == installed -> "\n\n⚠️ This is the same build as the one on this phone, so nobody will be offered an update unless their phone has an older build."
            else -> ""
        }
        val info = label("Version $name (build $code)\n%.1f MB".format(size / 1048576.0) + "\n\nEveryone on an older build will be offered this update." + warn, 14f, "#4A241C", false)
        val etNotes = field("What's new? (optional, shown to users)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val cbForce = android.widget.CheckBox(this).apply {
            text = "Compulsory: people cannot use the app until they install it"; isChecked = true; setTextColor(Pal.c("#3E1D16"))
        }
        val etPw = passField("Your own password (to confirm)")
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this).setTitle("Publish this version?")
            .setView(scrollable(info, etNotes, cbForce, etPw, error))
            .setPositiveButton("Publish", null)
            .setNegativeButton("Cancel") { _, _ -> AppUpdate.cleanUp(applicationContext) }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = etPw.text.toString()
                if (pw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                dialog.dismiss()
                upload(name, code, size, sha, etNotes.text.toString().trim(), pw, cbForce.isChecked)
            }
        }
        dialog.show()
    }

    /** Uploads the picked APK in 512 KB pieces, then publishes it. Runs in the background with a progress bar. */
    private fun upload(name: String, code: Long, size: Long, sha: String, notes: String, adminPw: String, mandatory: Boolean) {
        val chunks = ((size + AppUpdate.PIECE - 1) / AppUpdate.PIECE).toInt()
        var cancelled = false
        val box = AppUpdate.ProgressBox(this, "Publishing update", chunks + 1) { cancelled = true }
        box.set(0, "Starting…")
        val app = applicationContext
        Thread {
            var error: String? = null
            var ended = false
            // One call with up to 3 tries on network trouble. A server answer (even an error) is final.
            fun send(body: JSONObject): JSONObject? {
                for (attempt in 1..3) {
                    try {
                        val r = postJson(app, JSONObject(body.toString()))
                        if (r.optBoolean("auth")) { ended = true; error = "Your session ended. Please sign in again."; return null }
                        if (!r.optBoolean("ok")) { error = r.optString("error", "The server refused the upload."); return null }
                        return r
                    } catch (e: Exception) { /* try again */ }
                }
                error = "Upload failed. Check your internet and try again."
                return null
            }
            try {
                val begin = send(JSONObject().put("action", "adminAppBegin").put("versionCode", code).put("versionName", name)
                    .put("notes", notes).put("size", size).put("chunks", chunks).put("sha256", sha).put("adminPassword", adminPw).put("mandatory", mandatory)
                    .put("certSha256", AppUpdate.certSha256(app, AppUpdate.uploadFile(app))))
                if (begin != null) {
                    val uploadId = begin.getString("uploadId")
                    FileInputStream(AppUpdate.uploadFile(app)).use { input ->
                        val buf = ByteArray(AppUpdate.PIECE)
                        for (i in 0 until chunks) {
                            if (cancelled) { error = "cancelled"; break }
                            var filled = 0
                            while (filled < buf.size) {
                                val n = input.read(buf, filled, buf.size - filled)
                                if (n < 0) break
                                filled += n
                            }
                            val piece = Base64.encodeToString(buf, 0, filled, Base64.NO_WRAP)
                            if (send(JSONObject().put("action", "adminAppChunk").put("uploadId", uploadId).put("index", i).put("data", piece)) == null) break
                            box.set(i + 1, "Uploading… ${i + 1} of $chunks")
                        }
                    }
                    if (error == null) {
                        box.set(chunks, "Publishing…")
                        send(JSONObject().put("action", "adminAppFinish").put("uploadId", uploadId))
                    }
                }
            } catch (e: Exception) {
                if (error == null) error = "Upload failed. Please try again."
            }
            AppUpdate.cleanUp(app)
            val e = error
            box.close()
            runOnUiThread {
                when {
                    ended -> endSession()
                    e == "cancelled" -> toast("Upload cancelled. Nothing was published.")
                    e != null -> AlertDialog.Builder(this).setTitle("Upload failed").setMessage(e).setPositiveButton("OK", null).show()
                    else -> {
                        AlertDialog.Builder(this).setTitle("Published ✅")
                            .setMessage(if (mandatory) "Version $name is now COMPULSORY. Next time a phone opens or refreshes Home, it shows the Update required screen and nothing else works until the update is installed."
                                else "Version $name is now offered to everyone. They see an Update button on Home next time they open or refresh the app.")
                            .setPositiveButton("OK", null).show()
                        loadApp()
                    }
                }
            }
        }.start()
    }

    /** Switch the published update between compulsory and optional (needs the admin's own password). */
    private fun mandatoryDialog(makeCompulsory: Boolean) {
        val info = label(if (makeCompulsory) "Phones with an older build will be stopped on an \"Update required\" screen and can use nothing else until they install this version. This happens the next time each phone opens or refreshes Home."
                         else "Phones will only be offered the update (an Update button on Home). Phones already showing the Update required screen are released when they tap Check again or reopen the app.", 14f, "#4A241C", false)
        val etPw = passField("Your own password (to confirm)")
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this).setTitle(if (makeCompulsory) "Make the update compulsory?" else "Make the update optional?")
            .setView(scrollable(info, etPw, error)).setPositiveButton("Yes", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = etPw.text.toString()
                if (pw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                api(JSONObject().put("action", "adminAppMandatory").put("mandatory", makeCompulsory).put("adminPassword", pw)) { res, err ->
                    if (res != null) { dialog.dismiss(); toast(if (makeCompulsory) "Now compulsory" else "Now optional"); loadApp() } else error.text = err
                }
            }
        }
        dialog.show()
    }

    private fun removeUpdateDialog() {
        val etPw = passField("Your own password (to confirm)")
        val error = label("", 14f, "#DC2626", false)
        val dialog = AlertDialog.Builder(this).setTitle("Stop offering this update?")
            .setView(scrollable(label("Phones will no longer be offered this version. The uploaded file is deleted.", 14f, "#8A5F52", false), etPw, error))
            .setPositiveButton("Stop offering", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = etPw.text.toString()
                if (pw.isEmpty()) { error.text = "Enter your own password to confirm"; return@setOnClickListener }
                error.text = "Working…"
                api(JSONObject().put("action", "adminAppRemove").put("adminPassword", pw)) { res, err ->
                    if (res != null) { dialog.dismiss(); toast("No update is offered now."); loadApp() } else error.text = err
                }
            }
        }
        dialog.show()
    }
}
