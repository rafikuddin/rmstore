package com.example.attendance

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** One button of the bottom bar. [lock] is the key an admin can lock for a person (null = never locked). */
class NavItem(val key: String, val icon: String, val label: String, val short: String, val target: Class<*>, val lock: String?)

/** Everything the admin can put in the bottom bar, with the screen each one opens. */
object NavItems {
    val all = listOf(
        NavItem("home", "🏠", "Home", "Home", MainActivity::class.java, null),
        NavItem("attendance", "📸", "Attendance", "Attend", AttendanceActivity::class.java, "attendance"),
        NavItem("leave", "🌴", "Leave", "Leave", LeaveActivity::class.java, "leave"),
        NavItem("team", "👥", "Team", "Team", TeamActivity::class.java, "team"),
        NavItem("calendar", "📅", "Calendar", "Calendar", CalendarActivity::class.java, "calendar"),
        NavItem("night", "🌙", "Night Hold", "Night", NightHoldActivity::class.java, "night"),
        NavItem("orders", "🛒", "Orders", "Orders", OrdersActivity::class.java, null),
        NavItem("customers", "🏪", "Customers", "Shops", CustomersActivity::class.java, null),
        NavItem("notices", "🔔", "Notices", "Notices", NoticesActivity::class.java, "notices"),
        NavItem("reports", "📊", "Reports", "Reports", ReportActivity::class.java, "reports"),
        NavItem("target", "🎯", "Target", "Target", TargetActivity::class.java, "target"),
        NavItem("survey", "📋", "Surveys", "Surveys", SurveyActivity::class.java, "survey")
    )
    fun find(key: String) = all.firstOrNull { it.key == key }
}

/**
 * The Home dashboard and bottom bar an admin arranged. The server sends it with Home; the phone keeps the last copy.
 * Blocks: the dashboard cards in order, some hidden. Tiles: the three small quick tiles that can be hidden one by one.
 */
object HomeLayout {
    /** The dashboard blocks in the standard order, each with the views it is made of. */
    val blocks: List<Pair<String, IntArray>> = listOf(
        "today" to intArrayOf(R.id.cardToday),
        "quick" to intArrayOf(R.id.rowQuick),
        "notice" to intArrayOf(R.id.cardNotice),
        "actions" to intArrayOf(R.id.hdrActions, R.id.rowTiles),
        "target" to intArrayOf(R.id.tileTarget),
        "survey" to intArrayOf(R.id.tileSurvey),
        "orders" to intArrayOf(R.id.tileOrders),
        "customers" to intArrayOf(R.id.tileCustomers),
        "balance" to intArrayOf(R.id.hdrBalance, R.id.cardBalance),
        "night" to intArrayOf(R.id.hdrNight, R.id.cardNight)
    )
    val blockNames = mapOf(
        "today" to "📍  Today's status", "quick" to "⚡  Attendance / Leave buttons", "notice" to "📢  Latest notice",
        "actions" to "🧩  Quick actions (Night, Calendar, Reports)", "target" to "🎯  Target vs Collection", "survey" to "📋  Surveys",
        "orders" to "🛒  Orders", "customers" to "🏪  Customers", "balance" to "🌴  Leave balance", "night" to "🌙  Night hold this month"
    )
    val tiles: List<Pair<String, Int>> = listOf("tNight" to R.id.tileNight, "tCalendar" to R.id.tileCalendar, "tReports" to R.id.tileReports)
    val tileNames = mapOf("tNight" to "🌙  Night Hold tile", "tCalendar" to "📅  Calendar tile", "tReports" to "📊  Reports tile")
    val defaultNav = listOf("home", "attendance", "leave", "team", "calendar", "night")
    const val NAV_MAX = 6

    private const val PREFS = "home_layout"
    private const val KEY = "layout"

    fun save(ctx: Context, layout: JSONObject?) {
        if (layout == null) return
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, layout.toString()).apply() } catch (e: Exception) { }
    }

    private fun saved(ctx: Context): JSONObject? =
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { JSONObject(it) } } catch (e: Exception) { null }

    private fun strings(o: JSONObject?, name: String): List<String>? {
        val a = o?.optJSONArray(name) ?: return null
        return (0 until a.length()).map { a.optString(it) }
    }

    /** Dashboard block keys, top to bottom: saved ones first, anything missing added at the end. */
    fun order(ctx: Context): List<String> = order(saved(ctx))
    fun order(o: JSONObject?): List<String> {
        val std = blocks.map { it.first }
        val out = ArrayList<String>()
        strings(o, "order")?.forEach { if (it in std && it !in out) out.add(it) }
        std.forEach { if (it !in out) out.add(it) }
        return out
    }

    fun hidden(ctx: Context): Set<String> = hidden(saved(ctx))
    fun hidden(o: JSONObject?): Set<String> = (strings(o, "hidden") ?: emptyList()).toSet()

    /** Bottom bar keys left to right (Home first, only known ones, at most [NAV_MAX]). */
    fun nav(ctx: Context): List<String> = nav(saved(ctx))
    fun nav(o: JSONObject?): List<String> {
        val raw = strings(o, "nav")
        val out = ArrayList<String>()
        out.add("home")
        (raw ?: defaultNav).forEach { if (NavItems.find(it) != null && it !in out) out.add(it) }
        return out.take(NAV_MAX)
    }

    /** Puts the dashboard blocks of the Home screen in the saved order and hides the switched-off ones. */
    fun apply(a: AppCompatActivity) {
        val content = a.findViewById<LinearLayout>(R.id.homeContent) ?: return
        @Suppress("UNCHECKED_CAST")
        var wrappers = content.getTag(R.id.homeContent) as? HashMap<String, LinearLayout>
        if (wrappers == null) {
            val w = HashMap<String, LinearLayout>()
            for ((key, ids) in blocks) {
                val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
                for (id in ids) {
                    val v = a.findViewById<View>(id) ?: continue
                    (v.parent as? ViewGroup)?.removeView(v)
                    box.addView(v)
                }
                w[key] = box
            }
            content.setTag(R.id.homeContent, w)
            wrappers = w
        }
        val hidden = hidden(a)
        val update = a.findViewById<View>(R.id.cardUpdate)
        for ((_, box) in wrappers) (box.parent as? ViewGroup)?.removeView(box)
        if (update != null) { (update.parent as? ViewGroup)?.removeView(update); content.addView(update) }
        for (key in order(a)) {
            val box = wrappers[key] ?: continue
            var gone = key in hidden
            if (key == "actions" && tiles.all { it.first in hidden }) gone = true
            box.visibility = if (gone) View.GONE else View.VISIBLE
            content.addView(box)
        }
        for ((key, id) in tiles) a.findViewById<View>(id)?.visibility = if (key in hidden) View.GONE else View.VISIBLE
    }
}
