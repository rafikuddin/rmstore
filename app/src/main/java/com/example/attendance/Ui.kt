package com.example.attendance

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.util.WeakHashMap
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

fun AppCompatActivity.setupHeader(title: String, onRefresh: (() -> Unit)? = null) {
    findViewById<TextView>(R.id.tvTitle).text = title
    findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    val refresh = findViewById<TextView>(R.id.btnRefresh)
    refresh.visibility = android.view.View.GONE        // there is no refresh button any more: pull the page down instead
    if (onRefresh != null) pullToRefresh(refresh, onRefresh)
}

/**
 * Pull down on the page and let go: a round spinner drops in at the top while [onRefresh] reloads the screen, then it goes away.
 * The page's (first) ScrollView is wrapped in a SwipeRefreshLayout; [anchor] is the old refresh button, which stays in the layout (hidden)
 * only because the loading code spins it and stops it when the answer arrives ([Spin] also closes the pull spinner then).
 */
fun AppCompatActivity.pullToRefresh(anchor: View, onRefresh: () -> Unit) {
    val sv = findScroll(findViewById<ViewGroup>(android.R.id.content)) ?: return
    val parent = sv.parent as? ViewGroup ?: return
    if (parent is SwipeRefreshLayout) return
    val index = parent.indexOfChild(sv)
    val lp = sv.layoutParams
    parent.removeView(sv)
    val swipe = SwipeRefreshLayout(this).apply {
        layoutParams = lp
        setColorSchemeColors(Pal.c("#6B352A"))
        setProgressBackgroundColorSchemeColor(Pal.card)
    }
    swipe.addView(sv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    parent.addView(swipe, index)
    Spin.tie(anchor, swipe)
    swipe.setOnRefreshListener {
        Spin.start(anchor)
        onRefresh()
        // Screens that report when they finish (see apiCached) stop it themselves; for any other, a moment is enough. Never spin for ever.
        anchor.postDelayed({ if (!Spin.isTied(anchor)) Spin.stop(anchor) }, 900)
        anchor.postDelayed({ swipe.isRefreshing = false }, 20000)
    }
}

private fun findScroll(v: View): ScrollView? {
    if (v is ScrollView) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
    return null
}

fun AppCompatActivity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()


enum class Tab { HOME, ATTENDANCE, LEAVE, TEAM, CALENDAR, NIGHT }

/** Keeps the old calls working: each [Tab] is one key of the bottom bar. */
fun AppCompatActivity.setupBottomNav(active: Tab?) {
    setupNav(when (active) {
        Tab.HOME -> "home"; Tab.ATTENDANCE -> "attendance"; Tab.LEAVE -> "leave"
        Tab.TEAM -> "team"; Tab.CALENDAR -> "calendar"; Tab.NIGHT -> "night"; null -> null
    })
}

/**
 * Builds the bottom bar (layout/bottom_nav.xml) that every screen shows, with the buttons an admin chose, in the order they chose.
 * [active] is the key of this screen (null = none, for screens like Admin that are not a button).
 */
fun AppCompatActivity.setupNav(active: String?) {
    val bar = findViewById<LinearLayout>(R.id.navBar) ?: return
    val manager = Session(this).isManager
    val keys = HomeLayout.nav(this).filter { it != "team" || manager || it == active }
    val short = keys.size > 5
    bar.removeAllViews()
    for (key in keys) {
        val nav = NavItems.find(key) ?: continue
        val on = key == active
        val lock = nav.lock
        val locked = lock != null && !on && isLocked(lock)
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true; isFocusable = true
            setPadding(0, dp(6), 0, dp(8))
        }
        val dot = View(this)
        item.addView(dot, LinearLayout.LayoutParams(dp(22), dp(4)).apply { bottomMargin = dp(4) })
        if (on) dot.setBackgroundResource(R.drawable.bg_dot)
        val icon = TextView(this).apply { text = nav.icon; textSize = 22f; alpha = if (on) 1f else if (locked) 0.25f else 0.6f }
        item.addView(icon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val label = TextView(this).apply {
            text = (if (locked) "🔒 " else "") + (if (short) nav.short else nav.label)
            textSize = 10f; maxLines = 1
            setTextColor(ContextCompat.getColor(this@setupNav, if (on) R.color.primary else R.color.muted))
            setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        item.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        item.setOnClickListener {
            if (lock != null && !on && isLocked(lock)) { toastLocked(lock); return@setOnClickListener }
            if (!on) {
                val go = Intent(this, nav.target)
                if (key == "home") go.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(go)
                // Switching tabs from a sub-screen replaces it, so Back always returns to Home.
                if (this !is MainActivity) finish()
            }
        }
        bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}

/**
 * The ⟳ refresh button spins while a screen is loading and glides to a stop on the upright position when it is done.
 * [start] with tied = true means the caller will call [stop] itself when its data arrives.
 */
object Spin {
    private class State(val anim: ObjectAnimator, var tied: Boolean)
    private val running = WeakHashMap<View, State>()

    fun isTied(v: View): Boolean = running[v]?.tied == true

    private val pulls = WeakHashMap<View, SwipeRefreshLayout>()
    /** Links the (hidden) refresh button to the page's pull-down spinner, so finishing the load also closes that spinner. */
    fun tie(v: View, pull: SwipeRefreshLayout) { pulls[v] = pull }

    fun start(v: View, tied: Boolean = false) {
        val existing = running[v]
        if (existing != null) { if (tied) existing.tied = true; return }
        v.animate().cancel()
        val a = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f).apply {
            duration = 700; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
        }
        running[v] = State(a, tied)
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) {}
            override fun onViewDetachedFromWindow(x: View) { running.remove(x)?.anim?.cancel(); x.removeOnAttachStateChangeListener(this) }
        })
        a.start()
    }

    /** Finishes the turn it is in (slowing down) and rests at 0 degrees. */
    fun stop(v: View) {
        pulls[v]?.isRefreshing = false
        val s = running.remove(v) ?: return
        val cur = v.rotation % 360f
        s.anim.cancel()
        val left = 360f - cur
        v.rotation = cur
        v.animate().rotation(360f).setDuration((700f * left / 360f).toLong().coerceAtLeast(150L))
            .setInterpolator(DecelerateInterpolator()).withEndAction { v.rotation = 0f }.start()
    }
}
