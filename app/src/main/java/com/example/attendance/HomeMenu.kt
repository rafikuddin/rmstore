package com.example.attendance

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** One row of the 👤 menu. [color] tints the icon circle and the text; [bold] makes the row stand out (for example "Update app"). */
class MenuEntry(val icon: String, val label: String, val color: String = "#1E2247", val bold: Boolean = false, val onClick: () -> Unit)

/**
 * The 👤 menu on Home: a rounded white card with a coloured icon beside every choice, a divider before the last
 * action and the app version at the bottom. Replaces the plain Android pop-up list.
 */
fun AppCompatActivity.showHomeMenu(anchor: View, entries: List<MenuEntry>, footer: String) {
    val width = dp(250)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) }
    }
    val popup = PopupWindow(root, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    popup.elevation = dp(14).toFloat()
    popup.isOutsideTouchable = true

    val ripple = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)

    entries.forEachIndexed { i, e ->
        if (i == entries.size - 1 && entries.size > 1) {            // a thin line before the last action (Logout)
            root.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#ECEEF8"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(4); bottomMargin = dp(4); marginStart = dp(8); marginEnd = dp(8)
                }
            })
        }
        val tint = Color.parseColor(e.color)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setBackgroundResource(ripple.resourceId)
            isClickable = true; isFocusable = true
            setOnClickListener { popup.dismiss(); e.onClick() }
        }
        row.addView(TextView(this).apply {
            text = e.icon; textSize = 17f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(30, Color.red(tint), Color.green(tint), Color.blue(tint))) }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        })
        row.addView(TextView(this).apply {
            text = e.label; textSize = 15f; setTextColor(tint)
            setTypeface(null, if (e.bold) Typeface.BOLD else Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    root.addView(TextView(this).apply {
        text = footer; textSize = 11f; setTextColor(Color.parseColor("#9AA0C0")); gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(4))
    })
    popup.showAsDropDown(anchor, anchor.width - width, dp(6))       // right edge lines up with the 👤 button
}
