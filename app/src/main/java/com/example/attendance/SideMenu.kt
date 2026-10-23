package com.example.attendance

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** One row of the side menu. [section] is the small heading it sits under; [current] marks the screen you are on. */
class SideItem(
    val icon: String, val label: String, val section: String, val badge: Int = 0, val locked: Boolean = false,
    val current: Boolean = false, val tint: String? = null,
    /** Which entry this is (the same words the server and the Admin Panel > Menu tab use); empty for entries that cannot be moved. */
    val key: String = "",
    /** A sub-menu: shown under this row and folded or unfolded by tapping it. */
    val children: List<SideItem> = emptyList(),
    val onClick: () -> Unit
)

/**
 * The side menu on Home (the 👤 button): a panel that slides in from the right edge, top to bottom, with a search box, the menus,
 * a ☀️ Light / 🌙 Dark switch and the signed-in person with a logout button at the bottom. The › button folds it into a slim
 * icon strip (and the choice is remembered). Works in both Day and Night colours.
 */
fun AppCompatActivity.showSideMenu(items: List<SideItem>, name: String, sub: String, footer: String, onLogout: () -> Unit) {
    val prefs = getSharedPreferences("ui_theme", Context.MODE_PRIVATE)
    var collapsed = prefs.getBoolean("side_collapsed", false)
    var query = ""
    val accent = Pal.chipOn          // filled circles (white text on top): Clay Brown, a lighter clay at night

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val frame = FrameLayout(this)
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {            // flush with the top, bottom and right edge; only the left corners are rounded
            val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r); setColor(Pal.card)
        }
        elevation = dp(18).toFloat()
        setPadding(dp(14), dp(12), dp(14), dp(10))
        isClickable = true
    }
    fun panelWidth() = if (collapsed) dp(78) else dp(296)
    frame.addView(panel, FrameLayout.LayoutParams(panelWidth(), ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.END })
    dialog.setContentView(frame)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.5f)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun close(then: (() -> Unit)? = null) {
        panel.animate().translationX((panel.width + dp(24)).toFloat()).setDuration(170).withEndAction {
            try { dialog.dismiss() } catch (e: Exception) { }
            then?.invoke()
        }.start()
    }
    frame.setOnClickListener { close() }

    val ripple = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
    fun txt(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    val ink = Pal.c("#6B352A"); val muted = Pal.c("#8A5F52"); val faint = Pal.c("#A98578")

    lateinit var render: () -> Unit
    lateinit var list: LinearLayout

    lateinit var refill: () -> Unit          // addRow and fillList call each other, and a local function can only call ones declared above it

    fun matches(it0: SideItem, q: String) = q.isEmpty() || it0.label.lowercase().contains(q)

    /** One row. [child] rows belong to a sub-menu: slightly smaller and moved in. A row with a sub-menu folds / unfolds it (in the slim strip it just opens). */
    fun addRow(it0: SideItem, child: Boolean) {
        val tint = Pal.c(it0.tint ?: "#6B352A")
        val hasSub = !child && it0.children.isNotEmpty() && !collapsed
        val subOpen = prefs.getBoolean("side_sub_open", true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = if (collapsed) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(if (child) 4 else 8), dp(if (child) 6 else 9), dp(10), dp(if (child) 6 else 9))
            background = if (it0.current) GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#FFF6C4")) } else null
            if (!it0.current) setBackgroundResource(ripple.resourceId)
            isClickable = true; isFocusable = true
            alpha = if (it0.locked) 0.45f else 1f
            if (hasSub) setOnClickListener { prefs.edit().putBoolean("side_sub_open", !subOpen).apply(); refill() }
            else setOnClickListener { close { it0.onClick() } }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2); if (child) marginStart = dp(30)
            }
        }
        val size = if (child) 28 else 34
        val ic = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)) }
        ic.addView(TextView(this).apply { text = it0.icon; textSize = if (child) 15f else 18f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(size), dp(size)))
        if (collapsed && it0.badge > 0) ic.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#EF4444")) }
        }, FrameLayout.LayoutParams(dp(9), dp(9)).apply { gravity = Gravity.TOP or Gravity.END })
        row.addView(ic)
        if (!collapsed) {
            row.addView(txt((if (it0.locked) "🔒 " else "") + it0.label, if (child) 14f else 15f, if (it0.current) Pal.c("#6B352A") else tint, it0.current || it0.tint != null).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(if (child) 8 else 10) }
            })
            if (it0.badge > 0) row.addView(TextView(this).apply {
                text = if (it0.badge > 99) "99+" else it0.badge.toString(); textSize = 11f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER; minWidth = dp(22)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(Color.parseColor("#EF4444")) }
            })
            if (hasSub) row.addView(txt(if (subOpen || query.isNotBlank()) "▾" else "▸", 16f, muted, true).apply {
                contentDescription = if (subOpen) "Fold the sub-menu" else "Unfold the sub-menu"
                setPadding(dp(8), 0, 0, 0)
            })
        }
        list.addView(row)
    }

    fun fillList() {
        list.removeAllViews()
        val q = query.trim().lowercase()
        val shown = items.filter { matches(it, q) || it.children.any { c -> matches(c, q) } }
        if (shown.isEmpty()) { list.addView(txt("Nothing found", 13f, muted).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }); return }
        var last = ""
        shown.forEach { it0 ->
            if (it0.section != last) {
                if (last.isNotEmpty()) list.addView(View(this).apply {
                    setBackgroundColor(Pal.c("#F3E7B0"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) }
                })
                if (!collapsed) list.addView(txt(it0.section, 12f, muted, true).apply { setPadding(dp(8), dp(6), dp(8), dp(4)) })
                last = it0.section
            }
            addRow(it0, false)
            if (!collapsed && it0.children.isNotEmpty() && (q.isNotEmpty() || prefs.getBoolean("side_sub_open", true))) {
                it0.children.filter { c -> q.isEmpty() || matches(it0, q) || matches(c, q) }.forEach { c -> addRow(c, true) }
            }
        }
    }

    refill = { fillList() }

    render = {
        panel.removeAllViews()
        (panel.layoutParams as FrameLayout.LayoutParams).width = panelWidth()
        panel.requestLayout()

        // header: logo, name, fold button
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(11).toFloat()) }
            }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val fold = TextView(this).apply {
            text = if (collapsed) "‹" else "›"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            isClickable = true; isFocusable = true
            setOnClickListener { collapsed = !collapsed; prefs.edit().putBoolean("side_collapsed", collapsed).apply(); render() }
        }
        if (collapsed) {
            head.gravity = Gravity.CENTER
            panel.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            panel.addView(fold, LinearLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        } else {
            head.addView(txt("RM Store", 20f, ink, true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) } })
            head.addView(fold, LinearLayout.LayoutParams(dp(28), dp(28)))
            panel.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // search
        if (collapsed) {
            panel.addView(TextView(this).apply {
                text = "🔍"; textSize = 17f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F8EFC4")) }
                setOnClickListener { collapsed = false; prefs.edit().putBoolean("side_collapsed", false).apply(); render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(14) })
        } else {
            val seek = SeekField(this, "Search…").apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14); bottomMargin = dp(4) }
                setText(query)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; fillList() }
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                })
            }
            panel.addView(seek)
        }

        // the menus
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fillList()

        // Light / Dark
        val night = Pal.night
        if (collapsed) {
            panel.addView(TextView(this).apply {
                text = if (night) "☀️" else "🌙"; textSize = 17f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Pal.c("#F8EFC4")) }
                setOnClickListener { close { Pal.reopenMenu = true; Pal.set(this@showSideMenu, !night) } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(6) })
        } else {
            val seg = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Pal.c("#F8EFC4")) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            fun part(label: String, on: Boolean, goNight: Boolean) = TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
                setTextColor(if (on) ink else muted); setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                if (on) { background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (Pal.night) Pal.c("#F0E3A6") else Color.parseColor("#FFFDF2")) }; elevation = dp(2).toFloat() }
                isClickable = true
                setOnClickListener { if (!on) close { Pal.reopenMenu = true; Pal.set(this@showSideMenu, goNight) } }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            seg.addView(part("☀️  Light", !night, false)); seg.addView(part("🌙  Dark", night, true))
            panel.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        // who is signed in + logout
        panel.addView(View(this).apply { setBackgroundColor(Pal.c("#F3E7B0")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        val me = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (collapsed) Gravity.CENTER else Gravity.CENTER_VERTICAL }
        me.addView(TextView(this).apply {
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            isClickable = collapsed
            setOnClickListener { close { onLogout() } }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        if (!collapsed) {
            val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            who.addView(txt(name, 14f, ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            who.addView(txt(sub, 11f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            me.addView(who, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10); marginEnd = dp(6) })
            me.addView(TextView(this).apply {
                text = "⎋"; textSize = 22f; setTextColor(Color.parseColor("#EF4444")); gravity = Gravity.CENTER
                contentDescription = "Log out"
                isClickable = true; isFocusable = true
                setOnClickListener { close { onLogout() } }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        panel.addView(me, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!collapsed) panel.addView(txt(footer, 10f, faint).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    render()

    dialog.show()
    panel.translationX = dp(330).toFloat()
    panel.animate().translationX(0f).setDuration(220).start()
}
