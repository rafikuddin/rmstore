package com.example.attendance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Home dashboard */
class MainActivity : AppCompatActivity() {
    private lateinit var session: Session
    private lateinit var tvStatus: TextView
    private lateinit var tvMonth: TextView
    private val notifPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    private var allowances: org.json.JSONArray? = null     // dates an admin opened for this person's manual attendance / night hold
    private var pendingSurveys = 0                          // surveys this person has not answered yet
    private var pendingUpdate: JSONObject? = null           // the newer app version the server offers, if any

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(this)
        if (!session.loggedIn) { goLogin(); return }
        setContentView(R.layout.activity_home)
        tvStatus = findViewById(R.id.tvStatus)
        tvMonth = findViewById(R.id.tvMonth)
        findViewById<TextView>(R.id.tvName).text = session.name
        findViewById<TextView>(R.id.tvAvatar).text = session.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        findViewById<TextView>(R.id.tvDate).text =
            SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
        findViewById<TextView>(R.id.btnRefresh).visibility = android.view.View.GONE     // no refresh button: pull Home down instead
        pullToRefresh(findViewById(R.id.btnRefresh)) { loadStatus() }
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        if (Pal.reopenMenu) { Pal.reopenMenu = false; findViewById<TextView>(R.id.btnMenu).postDelayed({ if (!isFinishing) showMenu(null) }, 350) }   // the Light / Dark switch restarts the screen: bring the menu back
        findViewById<android.view.View>(R.id.cardAvatar).setOnClickListener { open(ProfileActivity::class.java) }
        findViewById<TextView>(R.id.btnBell).setOnClickListener { open(NoticesActivity::class.java) }

        findViewById<android.view.View>(R.id.cardNight).setOnClickListener { open(NightHoldActivity::class.java) }
        findViewById<android.view.View>(R.id.btnQuickAttendance).setOnClickListener { open(AttendanceActivity::class.java) }
        findViewById<android.view.View>(R.id.btnQuickLeave).setOnClickListener { open(LeaveActivity::class.java) }
        findViewById<android.view.View>(R.id.tileNight).setOnClickListener { open(NightHoldActivity::class.java) }
        findViewById<android.view.View>(R.id.tileCalendar).setOnClickListener { open(CalendarActivity::class.java) }
        findViewById<android.view.View>(R.id.tileReports).setOnClickListener { open(ReportActivity::class.java) }
        findViewById<android.view.View>(R.id.tileTarget).setOnClickListener { open(TargetActivity::class.java) }
        findViewById<android.view.View>(R.id.tileSurvey).setOnClickListener { open(SurveyActivity::class.java) }
        findViewById<android.view.View>(R.id.cardNotice).setOnClickListener { open(NoticesActivity::class.java) }
        findViewById<android.view.View>(R.id.cardUpdate).setOnClickListener { pendingUpdate?.let { AppUpdate.offer(this, it) } }
        Thread { AppUpdate.cleanUp(applicationContext) }.start()     // a downloaded APK from an earlier update is no longer needed
        setupBottomNav(Tab.HOME)
        Reminders.schedule(this)            // attendance reminders in the notification bar (once; KEEP means no duplicates)
        askNotificationPermission()
        maybeShowNoticePopup()
        if (session.mustChangePassword) ChangePasswordDialog.show(this, session, forced = true)   // too easy / temporary password: required, not optional
    }

    override fun onResume() {
        super.onResume()
        if (session.loggedIn) {
            if (session.mustChangePassword) ChangePasswordDialog.show(this, session, forced = true)
            showLocks()
            loadStatus()
            loadSurveyCount()
        }
    }

    private val versionName: String
        get() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun open(c: Class<*>, extras: (Intent) -> Unit) {
        val key = Access.keyFor(c)
        if (key != null && isLocked(key)) { toastLocked(key); return }
        startActivity(Intent(this, c).also(extras))
    }

    private fun open(c: Class<*>) {
        val key = Access.keyFor(c)
        if (key != null && isLocked(key)) { toastLocked(key); return }
        startActivity(Intent(this, c))
    }

    /** Locked ways in look faded (the server refuses them anyway). */
    private fun showLocks() {
        mapOf(R.id.btnQuickAttendance to "attendance", R.id.btnQuickLeave to "leave", R.id.cardNight to "night", R.id.tileNight to "night",
            R.id.tileCalendar to "calendar", R.id.tileReports to "reports", R.id.tileTarget to "target", R.id.tileSurvey to "survey", R.id.btnBell to "notices", R.id.bellIcon to "notices", R.id.cardNotice to "notices").forEach { (id, key) ->
            findViewById<android.view.View>(id)?.fadeIfLocked(this, key)
        }
        setupBottomNav(Tab.HOME)
    }

    /** The side menu (👤 button): search, the menus, Light / Dark switch, who is signed in and Logout. */
    private fun showMenu(@Suppress("UNUSED_PARAMETER") anchor: android.view.View?) {
        val items = ArrayList<SideItem>()
        val bell = findViewById<TextView>(R.id.tvBellBadge)
        val unread = if (bell.visibility == android.view.View.VISIBLE) (bell.text.toString().toIntOrNull() ?: 0) else 0
        items.add(SideItem("🏠", "Home", "Menu", current = true) { })
        items.add(SideItem("📸", "Attendance", "Menu", locked = isLocked("attendance"), key = "attendance") { open(AttendanceActivity::class.java) })
        items.add(SideItem("🌴", "Leave", "Menu", locked = isLocked("leave"), key = "leave") { open(LeaveActivity::class.java) })
        items.add(SideItem("🌙", "Night Hold", "Menu", locked = isLocked("night"), key = "night") { open(NightHoldActivity::class.java) })
        items.add(SideItem("📅", "Calendar", "Menu", locked = isLocked("calendar"), key = "calendar") { open(CalendarActivity::class.java) })
        if (session.isManager) items.add(SideItem("👥", "Team", "Menu", locked = isLocked("team"), key = "team") { open(TeamActivity::class.java) })
        if (Access.canViewTeam(this)) items.add(SideItem("🪪", "Team members (profile & calendar)", "Menu", key = "members") { open(TeamMembersActivity::class.java) })
        items.add(SideItem("🔔", "Notices", "Menu", badge = unread, locked = isLocked("notices"), key = "notices") { open(NoticesActivity::class.java) })
        items.add(SideItem("📊", "My Reports", "Menu", locked = isLocked("reports"), key = "reports") { open(ReportActivity::class.java) })
        items.add(SideItem("🎯", "Target vs Collection", "Menu", locked = isLocked("target"), key = "target") { open(TargetActivity::class.java) })
        items.add(SideItem("📋", "Surveys", "Menu", badge = pendingSurveys, locked = isLocked("survey"), key = "survey") { open(SurveyActivity::class.java) })

        items.add(SideItem("👤", "My Profile", "Settings", locked = isLocked("profile"), key = "profile") { open(ProfileActivity::class.java) })
        items.add(SideItem("🔑", "Change Password", "Settings", key = "password") { ChangePasswordDialog.show(this, session) })
        items.add(SideItem("⏰", "Attendance reminders: ${if (Reminders.enabled(this)) "On" else "Off"}", "Settings", key = "reminders") { toggleReminders() })
        allowances?.takeIf { it.length() > 0 && !isLocked("manual") }?.let { a ->
            items.add(SideItem("📝", "Manual entry (${a.length()})", "Settings", tint = "#7C3AED", key = "manual") { showManualEntries(a) })
        }
        pendingUpdate?.let { u ->
            items.add(SideItem("⬆️", "Update app (v${u.optString("versionName")})", "Settings", tint = "#2563EB", key = "update") { AppUpdate.offer(this, u) })
        }
        if (AdminFlag.get(this) && !isLocked("admin")) {
            // the Admin Panel carries its tabs as a sub-menu, in the order the admin chose; each one opens that tab
            val subs = MenuLayout.order(this, "admin").mapNotNull { AdminTabs.find(it) }.map { t ->
                SideItem(t.icon, t.name, "Settings") { open(AdminActivity::class.java) { it.putExtra(AdminActivity.EXTRA_TAB, t.key) } }
            }
            items.add(SideItem("🛠", "Admin Panel", "Settings", key = "admin", children = subs) { open(AdminActivity::class.java) })
        }
        val role = if (AdminFlag.get(this)) "Admin" else if (session.isManager) "Manager" else "Employee"
        showSideMenu(MenuLayout.sort(this, items), session.name, "ID: ${session.username} · $role", "RM Store · App version $versionName") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Log out?").setMessage("You will have to sign in again.")
                .setPositiveButton("Log out") { _, _ -> logout() }.setNegativeButton("Cancel", null).show()
        }
    }

    /** Android 13 and newer need the person's OK before any notification can show. Asked once. */
    private fun askNotificationPermission(force: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < 33 || !Reminders.enabled(this) || Reminders.canNotify(this)) return
        val p = Reminders.prefs(this)
        if (!force && p.getBoolean("asked", false)) return
        p.edit().putBoolean("asked", true).apply()
        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toggleReminders() {
        val on = !Reminders.enabled(this)
        Reminders.setEnabled(this, on)
        if (on) {
            askNotificationPermission(force = true)
            android.widget.Toast.makeText(this, "Reminders are on. If you do not see them, allow notifications for this app in Android settings.", android.widget.Toast.LENGTH_LONG).show()
        } else android.widget.Toast.makeText(this, "Reminders are off.", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Lists the days an admin opened for this person; tapping one opens the normal screen for that day. */
    private fun showManualEntries(a: org.json.JSONArray) {
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val nice = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
        val items = ArrayList<JSONObject>()
        for (i in 0 until a.length()) items.add(a.getJSONObject(i))
        val labels = items.map { o ->
            val d = o.optString("date")
            val shown = try { nice.format(iso.parse(d)!!) } catch (e: Exception) { d }
            (if (o.optString("kind") == "night") "🌙 Night hold · " else "📍 Attendance · ") + shown
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Manual entry")
            .setItems(labels) { _, which ->
                val o = items[which]
                val cls = if (o.optString("kind") == "night") NightHoldActivity::class.java else AttendanceActivity::class.java
                startActivity(Intent(this, cls).putExtra(EXTRA_FOR_DATE, o.optString("date")))
            }
            .setNegativeButton("Close", null).show()
    }

    /** Tells the server to end this sign-in (best effort, in the background), then wipes everything kept on the phone. */
    private fun logout() {
        val token = session.token
        Thread { try { postJsonRaw(JSONObject().put("action", "logout"), token) } catch (e: Exception) { } }.start()
        Wipe.signOutLocally(this)
        goLogin()
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /** This pay period's night holds: today's status on top, then one row per night. */
    private fun showNights(arr: org.json.JSONArray?, today: String, from: String, to: String) {
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        val list = findViewById<android.widget.LinearLayout>(R.id.nightList)
        list.removeAllViews()
        val rows = ArrayList<JSONObject>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = o.optString("date")
                if (d >= from && d <= to) rows.add(o)
            }
        }
        rows.sortByDescending { it.optString("date") }
        val periodLabel = SimpleDateFormat("dd MMM", Locale.getDefault())
        val startLbl = periodLabel.format(inFmt.parse(from) ?: Date())
        val endLbl = periodLabel.format(inFmt.parse(to) ?: Date())
        findViewById<TextView>(R.id.tvNightHeading).text = "NIGHT HOLD · $startLbl – $endLbl"
        findViewById<TextView>(R.id.tvNightCount).text = if (rows.size == 1) "1 night" else "${rows.size} nights"

        val tonight = rows.firstOrNull { it.optString("date") == today }
        val tvToday = findViewById<TextView>(R.id.tvNightToday)
        if (tonight != null) {
            tvToday.text = "✅ Submitted · ${tonight.optString("time")}"
            tvToday.setTextColor(Pal.c("#16A34A"))
        } else {
            tvToday.text = "Not submitted tonight"
            tvToday.setTextColor(Pal.c("#8A5F52"))
        }

        if (rows.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No night hold submitted this month. Tap to submit one."
                textSize = 13f; setTextColor(Pal.c("#8A5F52")); setPadding(0, dp(10), 0, 0)
            })
            return
        }
        for (o in rows) {
            val dateText = try { outFmt.format(inFmt.parse(o.optString("date"))!!) } catch (e: Exception) { o.optString("date") }
            val line = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            line.addView(TextView(this).apply {
                text = "🌙  $dateText"; textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Pal.c("#6B352A"))
            })
            line.addView(TextView(this).apply {
                text = "🏨 ${o.optString("hotel")} · ${o.optString("thana")}, ${o.optString("district")}"
                textSize = 13f; setTextColor(Pal.c("#8A5F52")); setPadding(dp(28), 0, 0, 0)
            })
            list.addView(line)
        }
    }

    /** Today's selfie as a small round picture in the status circle, and where it was taken. Back to the pin until attendance is marked. */
    private fun showTodayDetail(rec: JSONObject?) {
        val box = findViewById<android.view.View>(R.id.todayDetail)
        val mini = findViewById<android.widget.ImageView>(R.id.imgTodayMini)
        val pin = findViewById<android.view.View>(R.id.tvTodayPin)
        val miniCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardTodayMini)
        miniCard.clipToOutline = true
        if (rec == null) {
            box.visibility = android.view.View.GONE
            mini.visibility = android.view.View.GONE; pin.visibility = android.view.View.VISIBLE
            miniCard.setOnClickListener(null); miniCard.isClickable = false
            return
        }
        box.visibility = android.view.View.VISIBLE
        val selfie = rec.optString("selfie")
        if (selfie.isNotEmpty()) {
            pin.visibility = android.view.View.GONE; mini.visibility = android.view.View.VISIBLE
            PhotoLoader.load(this, session, selfie, mini, 200)
            miniCard.isClickable = true
            miniCard.setOnClickListener { showBigSelfie(selfie) }     // tap the small picture to see it full size
        } else {
            mini.visibility = android.view.View.GONE; pin.visibility = android.view.View.VISIBLE   // an older saved copy has no photo id until the refresh arrives
        }
        val address = rec.optString("address")
        val coords = "${rec.optString("lat")}, ${rec.optString("lng")}"
        findViewById<TextView>(R.id.tvTodayLoc).text = "📍 " + (if (address.isNotEmpty() && address != "Address unavailable") "$address\n$coords" else coords)
    }

    private fun showBigSelfie(fileId: String) {
        val img = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            minimumHeight = (320 * resources.displayMetrics.density).toInt()
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt())
        }
        PhotoLoader.load(this, session, fileId, img, 640)
        androidx.appcompat.app.AlertDialog.Builder(this).setView(img).setPositiveButton("Close", null).show()
    }

    /** A banner when the server offers a newer app version than the one installed. Hidden when this phone is up to date. */
    private fun showUpdate(info: JSONObject?) {
        val card = findViewById<android.view.View>(R.id.cardUpdate)
        AppUpdate.remember(this, info)
        if (AppUpdate.enforce(this)) return
        if (!AppUpdate.isNewer(this, info) || info == null) { pendingUpdate = null; card.visibility = android.view.View.GONE; return }
        pendingUpdate = info
        findViewById<TextView>(R.id.tvUpdateTitle).text = "Update available · v${info.optString("versionName")}"
        val notes = info.optString("notes")
        findViewById<TextView>(R.id.tvUpdateText).text = if (notes.isNotEmpty()) notes else "A new version of the app is ready. Tap Update."
        card.visibility = android.view.View.VISIBLE
    }

    /** How many surveys are waiting for this person (shown on the Home tile and as a badge in the side menu). */
    private fun loadSurveyCount() {
        if (isLocked("survey")) { pendingSurveys = 0; return }
        api(JSONObject().put("action", "surveyList")) { res, _ ->
            pendingSurveys = res?.optInt("pending") ?: 0
            findViewById<TextView>(R.id.tvSurveySub)?.text =
                if (pendingSurveys > 0) "$pendingSurveys waiting for your answer" else "Answer the questions your team has sent"
        }
    }

    /** Bell badge: how many notices have not been opened yet. */
    private var lastUnread = -1          // -1 until the first count; a later, higher count means a new notice arrived

    private var bellRung = false      // the bell rings once each time Home opens with unread notices, and again for every new one

    fun refreshBell(arr: org.json.JSONArray?) {
        val badge = findViewById<TextView>(R.id.tvBellBadge)
        val n = NoticeState.unread(this, arr)
        val isNew = lastUnread in 0 until n
        if (isNew) Chime.play(this)          // a new notice arrived while the app was open: ding-ding
        lastUnread = n
        if (n > 0) {
            badge.text = if (n > 9) "9+" else n.toString(); badge.visibility = android.view.View.VISIBLE
            if (isNew || !bellRung) {
                bellRung = true
                findViewById<android.view.View>(R.id.bellIcon)?.let { bell -> bell.post { BellAnim.ring(bell, badge) } }
            }
        } else badge.visibility = android.view.View.GONE
    }

    /** Once per sign-in / app start: fetch the current notices fresh and show them in a pop-up. */
    private fun maybeShowNoticePopup() {
        if (!NoticePopup.isDue()) return
        api(JSONObject().put("action", "notices")) { res, _ ->
            val arr = res?.optJSONArray("notices") ?: return@api
            refreshBell(arr)
            NoticePopup.show(this, arr)
        }
    }

    /** The newest notice (admin or your manager's), shown as a card under Today's Status. Hidden when there are none. */
    private fun showNotices(arr: org.json.JSONArray?) {
        refreshBell(arr)
        val card = findViewById<android.view.View>(R.id.cardNotice)
        if (arr == null || arr.length() == 0) { card.visibility = android.view.View.GONE; return }
        val first = arr.getJSONObject(0)
        findViewById<TextView>(R.id.tvNoticeTitle).text = first.optString("title")
        findViewById<TextView>(R.id.tvNoticeText).text = first.optString("message")
        val more = arr.length() - 1
        findViewById<TextView>(R.id.tvNoticeMore).text = if (more > 0) "+$more more · tap to see all" else "Tap to read"
        card.visibility = android.view.View.VISIBLE
    }

    /** Fills the three leave bars: the bar shows what is left (full = nothing used yet). */
    private fun showBalance(b: org.json.JSONArray) {
        findViewById<TextView>(R.id.tvBalHeading).text = "LEAVE BALANCE ${Calendar.getInstance().get(Calendar.YEAR)}"
        var used = 0
        var pending = 0
        for (i in 0 until b.length()) {
            val o = b.getJSONObject(i)
            val quota = o.optInt("quota")
            val rem = o.optInt("remaining").coerceIn(0, maxOf(quota, 0))
            used += o.optInt("used")
            pending += o.optInt("pending")
            val pct = if (quota > 0) (rem * 100 / quota) else 0
            val ids = when (o.optString("type").substringBefore(" ")) {
                "Casual" -> Triple(R.id.pbCasual, R.id.tvCasual, R.id.tvCasualVal)
                "Sick" -> Triple(R.id.pbSick, R.id.tvSick, R.id.tvSickVal)
                "Earn" -> Triple(R.id.pbEarn, R.id.tvEarn, R.id.tvEarnVal)
                "Paternity" -> Triple(R.id.pbPaternity, R.id.tvPaternity, R.id.tvPaternityVal)
                else -> null
            }
            if (ids != null) {
                findViewById<android.widget.ProgressBar>(ids.first).progress = pct
                findViewById<TextView>(ids.third).text = "$rem left of $quota · ${o.optInt("used")} used"
            }
        }
        findViewById<TextView>(R.id.tvBalUsed).text = "Used $used" + (if (pending > 0) " · Pending $pending" else "")
    }

    // Friday and any admin-set holiday are days off — never counted as absent, never shown as "not marked yet".
    // "This month" runs PERIOD_START_DAY → PERIOD_END_DAY (26th → 25th), not the calendar month —
    // keep this in sync with Code.gs's PERIOD_START_DAY/PERIOD_END_DAY if you ever change the cycle.
    /** The profile photo in the round avatar at the top; the initial letter stays when there is no photo. */
    private fun showAvatar(photoId: String) {
        val img = findViewById<android.widget.ImageView>(R.id.imgAvatar)
        if (photoId.isEmpty()) { img.visibility = android.view.View.GONE; return }
        img.visibility = android.view.View.VISIBLE
        if (img.tag != photoId) PhotoLoader.load(this, session, photoId, img, 200)
    }

    private fun loadStatus() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Calendar.getInstance()
        val today = fmt.format(now.time)
        val isFridayToday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val isAfterCutoff = now.get(Calendar.HOUR_OF_DAY) >= 18   // matches Code.gs's ABSENT_AFTER_HOUR (6:00 PM)

        val periodStartDay = 26
        val periodEndDay = 25
        val periodStart = (now.clone() as Calendar).apply {
            if (get(Calendar.DAY_OF_MONTH) < periodStartDay) add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, periodStartDay)
        }
        val periodEnd = (periodStart.clone() as Calendar).apply { add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, periodEndDay) }
        val periodStartKey = fmt.format(periodStart.time)
        val periodEndKey = fmt.format(periodEnd.time)

        val render: (JSONObject) -> Unit = { res ->
            var present = 0
            var todayTime: String? = null
            var todayLate = false
            var todayRecord: JSONObject? = null
            val presentThisPeriod = HashSet<String>()
            val recs = res.getJSONArray("records")
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i); val d = r.getString("date")
                if (d == today) { todayTime = r.optString("time"); todayLate = r.optBoolean("late"); todayRecord = r }
                if (d >= periodStartKey && d <= periodEndKey) { present++; presentThisPeriod.add(d) }
            }
            val leaves = res.optJSONArray("leaves")
            fun approvedLeaveOn(dateKey: String): String? {
                leaves?.let { lv ->
                    for (i in 0 until lv.length()) {
                        val l = lv.getJSONObject(i)
                        if (l.optString("status") == "Approved" && dateKey >= l.getString("from") && dateKey <= l.getString("to"))
                            return l.optString("type")
                    }
                }
                return null
            }
            val leaveType = approvedLeaveOn(today)

            // Holidays added by an admin (date -> name). They count as days off, just like Friday.
            val holidays = HashMap<String, String>()
            res.optJSONArray("holidays")?.let { h ->
                for (i in 0 until h.length()) { val o = h.getJSONObject(i); holidays[o.optString("date")] = o.optString("name") }
            }
            val holidayToday = holidays[today]

            // Absent = days from the start of this pay period to yesterday, that aren't Friday, present, or approved leave.
            var absent = 0
            val cursor = periodStart.clone() as Calendar
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            while (!cursor.after(yesterday)) {
                val dKey = fmt.format(cursor.time)
                if (cursor.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY && !holidays.containsKey(dKey) && !presentThisPeriod.contains(dKey) && approvedLeaveOn(dKey) == null) absent++
                cursor.add(Calendar.DAY_OF_MONTH, 1)
            }

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
            val manager = if (res.optBoolean("isManager")) android.view.View.VISIBLE else android.view.View.GONE
            session.isManager = res.optBoolean("isManager")
            AdminFlag.set(this, res.optBoolean("isAdmin"))
            MenuLayout.save(this, res.optJSONObject("menuLayout"))
            allowances = res.optJSONArray("allowances")
            showAvatar(res.optString("myPhoto"))
            showNotices(res.optJSONArray("notices"))
            showUpdate(res.optJSONObject("appUpdate"))
            findViewById<android.view.View>(R.id.navTeam).visibility = manager
            showTodayDetail(todayRecord)
            res.optJSONArray("balance")?.let { showBalance(it) }
            // A night is named by the date its evening started, so between midnight and 6 AM "tonight" is still yesterday's date.
            // The server says which night it is (the night hold hours are set by an admin); the old 6 AM rule is only a fallback.
            val tonightKey = res.optJSONObject("night")?.optString("nightDate")?.takeIf { it.isNotEmpty() }
                ?: fmt.format((now.clone() as Calendar).apply { if (get(Calendar.HOUR_OF_DAY) < 6) add(Calendar.DAY_OF_MONTH, -1) }.time)
            showNights(res.optJSONArray("nights"), tonightKey, periodStartKey, periodEndKey)
        }

        // Shows the last saved answer straight away (so Home opens instantly), then refreshes it.
        // Only this pay period's records are requested, so the reply stays small as history grows.
        apiCached(
            cacheKey = ResponseCache.homeKey(session.username),
            body = JSONObject().put("action", "history").put("username", session.username)
                .put("from", periodStartKey).put("nights", true)
                .put("appVersion", AppUpdate.installedName(this)).put("appCode", AppUpdate.installedCode(this)),   // lets admins see who has which version
            maxAgeMs = 24 * HOUR_MS,
            busy = findViewById<android.view.View>(R.id.btnRefresh),
            onData = { res -> Access.remember(this, res); render(res); showLocks() },
            onError = { err, hadSaved ->
                if (err?.contains("choose a new password first") == true) {      // the server wants a new password before anything else
                    session.mustChangePassword = true
                    ChangePasswordDialog.show(this, session, forced = true)
                } else if (!hadSaved) { tvStatus.text = "Offline"; tvMonth.text = err ?: "" }
            }
        )
    }
}
