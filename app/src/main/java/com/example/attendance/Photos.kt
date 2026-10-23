package com.example.attendance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/**
 * Loads a selfie into an ImageView. A selfie never changes once saved, so after the first download
 * it is kept on the phone (and in memory) and every later view is instant with no server call.
 */
object PhotoLoader {
    private val memory = LruCache<String, Bitmap>(48)

    fun clearMemory() = memory.evictAll()

    /** [minSide] = the smallest edge, in pixels, the picture is kept at: 200 for small avatars, 640 for a full-width photo. */
    fun load(activity: AppCompatActivity, session: Session, fileId: String, target: ImageView, minSide: Int = 200) {
        target.tag = fileId
        val memKey = "$fileId@$minSide"
        memory.get(memKey)?.let { target.setImageBitmap(it); return }

        val appContext = activity.applicationContext
        val safeName = fileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80)
        val file = File(activity.cacheDir, "selfie_$safeName.jpg")

        Thread {
            var bmp = decode(file, minSide)
            if (bmp == null) {
                try {
                    val res = postJson(appContext, JSONObject().put("action", "photo").put("id", fileId))
                    if (res.optBoolean("ok")) {
                        file.writeBytes(Base64.decode(res.getString("data"), Base64.DEFAULT))
                        bmp = decode(file, minSide)
                    }
                } catch (e: Exception) {
                    // Leave the initials circle in place; a later refresh will try again.
                }
            }
            val result = bmp
            if (result != null) {
                if (minSide <= 400) memory.put(memKey, result)     // big pictures (leave applications) are not kept in memory
                activity.runOnUiThread { if (target.tag == fileId) target.setImageBitmap(result) }
            }
        }.start()
    }

    /** Decodes at roughly avatar size so memory stays small. Deletes a corrupt file. */
    private fun decode(f: File, minSide: Int): Bitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= minSide && bounds.outHeight / (sample * 2) >= minSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) f.delete()
        return bmp
    }
}

/** Opens a stored picture (for example a leave application) full size in a pop-up. Only people allowed by the server can load it. */
fun AppCompatActivity.showPhotoDialog(session: Session, fileId: String) {
    val h = (resources.displayMetrics.heightPixels * 0.7).toInt()
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.parseColor("#3E1D16"))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }
    PhotoLoader.load(this, session, fileId, img, 1000)
    androidx.appcompat.app.AlertDialog.Builder(this).setView(img).setPositiveButton("Close", null).show()
}

/** A tappable "📎 View leave application" line, added under a leave request that has a picture. */
fun AppCompatActivity.applicationLink(session: Session, fileId: String): TextView = TextView(this).apply {
    text = "📎 View leave application"
    textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Pal.c("#8A4333"))
    setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (2 * resources.displayMetrics.density).toInt())
    setOnClickListener { showPhotoDialog(session, fileId) }
}
