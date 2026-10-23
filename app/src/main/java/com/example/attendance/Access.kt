package com.example.attendance

import android.content.Context
import android.view.View
import org.json.JSONObject
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Menus an admin can lock for one person (Admin Panel > Users > 🔐 Access). The server refuses the actions of a locked
 * option; the app also hides the way in. The list of locked keys comes with every server reply (see Session.locks).
 */
object Access {
    /** key, icon + name. The keys must match ACCESS_ITEMS in Code.gs. */
    val items = listOf(
        "attendance" to "📸  Attendance",
        "leave" to "🌴  Leave",
        "night" to "🌙  Night Hold",
        "calendar" to "📅  Calendar",
        "team" to "👥  Team",
        "notices" to "🔔  Notices",
        "reports" to "📊  My Reports",
        "profile" to "👤  My Profile",
        "manual" to "📝  Manual entry",
        "target" to "🎯  Target vs Collection",
        "survey" to "📋  Surveys",
        "admin" to "🛠  Admin Panel (admins only)"
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("access_locks", Context.MODE_PRIVATE)
    private fun prefKey(ctx: Context) = "lk_" + Session(ctx).username.lowercase()

    /** Keeps the locked menus the server sent in a reply (it adds "locks" to every answer). Replies without it change nothing. */
    fun remember(ctx: Context, reply: JSONObject?) {
        if (reply != null && reply.has("teamView")) prefs(ctx).edit().putBoolean("tv_" + Session(ctx).username.lowercase(), reply.optBoolean("teamView")).apply()
        if (reply != null && reply.has("surveyAdmin")) prefs(ctx).edit().putBoolean("sa_" + Session(ctx).username.lowercase(), reply.optBoolean("surveyAdmin")).apply()
        val a = reply?.optJSONArray("locks") ?: return
        prefs(ctx).edit().putString(prefKey(ctx), (0 until a.length()).joinToString(",") { a.optString(it) }).apply()
    }

    /** May this person create, edit and read the results of surveys? (An admin, or somebody an admin gave the right.) The server checks again. */
    /** May this line manager open the Profile and Calendar of the people below them? (Given by an admin; the server checks again.) */
    fun canViewTeam(ctx: Context): Boolean = prefs(ctx).getBoolean("tv_" + Session(ctx).username.lowercase(), false)

    fun canManageSurveys(ctx: Context): Boolean = prefs(ctx).getBoolean("sa_" + Session(ctx).username.lowercase(), false)

    fun isLocked(ctx: Context, key: String): Boolean =
        (prefs(ctx).getString(prefKey(ctx), "") ?: "").split(",").any { it == key }

    fun name(key: String): String = items.firstOrNull { it.first == key }?.second?.substringAfter("  ")?.substringBefore(" (") ?: key

    /** The option a screen belongs to, or null when it can never be locked. */
    fun keyFor(c: Class<*>): String? = when (c) {
        AttendanceActivity::class.java -> "attendance"
        LeaveActivity::class.java -> "leave"
        NightHoldActivity::class.java -> "night"
        CalendarActivity::class.java -> "calendar"
        TeamActivity::class.java -> "team"
        NoticesActivity::class.java -> "notices"
        ReportActivity::class.java -> "reports"
        TargetActivity::class.java -> "target"
        SurveyActivity::class.java -> "survey"
        ProfileActivity::class.java -> "profile"
        AdminActivity::class.java -> "admin"
        else -> null
    }
}

fun AppCompatActivity.isLocked(key: String): Boolean = Access.isLocked(this, key)

fun AppCompatActivity.toastLocked(key: String) {
    Toast.makeText(this, "🔒 ${Access.name(key)} is locked for your account. Ask your admin to unlock it.", Toast.LENGTH_LONG).show()
}

/** Call at the end of a screen's onCreate: a locked screen closes at once. Returns true when the person may stay. */
fun AppCompatActivity.enforceAccess(key: String): Boolean {
    if (!isLocked(key)) return true
    toastLocked(key)
    finish()
    return false
}

/** A locked way in (tile, button) looks faded. */
fun View.fadeIfLocked(activity: AppCompatActivity, key: String) { alpha = if (activity.isLocked(key)) 0.4f else 1f }
