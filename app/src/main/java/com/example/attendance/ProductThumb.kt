package com.example.attendance

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * A rounded square showing a product's picture (a 📦 shows until the picture arrives, or when the product has none).
 * The picture is loaded and kept on the phone by [PhotoLoader]. The ImageView is the SECOND child of the returned frame
 * (child 0 is the 📦), so a screen that has just chosen a new picture can put it in with setImageBitmap.
 */
fun AppCompatActivity.productThumb(session: Session, imageId: String, sizeDp: Int): FrameLayout {
    val density = resources.displayMetrics.density
    val size = (sizeDp * density).toInt()
    val frame = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = GradientDrawable().apply { cornerRadius = 14f * density; setColor(Pal.c("#F0E3A6")) }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
    frame.addView(TextView(this).apply {
        text = "📦"; textSize = sizeDp / 2.6f; gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    })
    frame.addView(ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (imageId.isNotEmpty()) PhotoLoader.load(this@productThumb, session, imageId, this, 200)
    })
    return frame
}
