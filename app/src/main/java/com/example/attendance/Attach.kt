package com.example.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * "Attach a picture": take a photo or choose one, turn it upright and shrink it to a JPEG of at most [MAX_BYTES].
 * Create one as a property of the screen (it registers the Android pickers), then call [choose].
 */
class PicturePicker(private val act: AppCompatActivity) {
    companion object { const val MAX_BYTES = 450_000 }

    private var callback: ((ByteArray) -> Unit)? = null
    private var cameraFile: File? = null

    private val pick = act.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) load { act.contentResolver.openInputStream(uri) }
    }
    private val take = act.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val f = cameraFile
        if (saved && f != null) load { FileInputStream(f) }
    }
    private val permission = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(act, "Camera permission is needed to take a photo. You can choose a picture instead.", Toast.LENGTH_LONG).show()
    }

    /** Asks "Take a photo" or "Choose a picture"; [onPicture] gets the finished JPEG. */
    fun choose(onPicture: (ByteArray) -> Unit) {
        callback = onPicture
        AlertDialog.Builder(act).setTitle("Attach a picture")
            .setItems(arrayOf("📷  Take a photo", "🖼  Choose a picture")) { _, which ->
                if (which == 0) {
                    if (ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                    else permission.launch(Manifest.permission.CAMERA)
                } else pick.launch("image/*")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun launchCamera() {
        try {
            val dir = File(act.cacheDir, "images").apply { mkdirs() }
            val f = File(dir, "survey_attachment.jpg")
            cameraFile = f
            take.launch(FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f))
        } catch (e: Exception) {
            Toast.makeText(act, "The camera could not be opened. Please choose a picture instead.", Toast.LENGTH_LONG).show()
        }
    }

    private fun load(open: () -> InputStream?) {
        Toast.makeText(act, "Preparing the picture…", Toast.LENGTH_SHORT).show()
        Thread {
            val bytes: ByteArray? = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2800 || bounds.outHeight / sample > 2800) sample *= 2
                val raw = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                if (raw == null) null else {
                    val orientation = open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
                    val deg = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                    val m = Matrix()
                    if (deg != 0) m.postRotate(deg.toFloat())
                    val scale = minOf(1f, 1280f / maxOf(raw.width, raw.height))
                    m.postScale(scale, scale)
                    val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    var quality = 75
                    var out: ByteArray
                    do {
                        val bo = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bo)
                        out = bo.toByteArray()
                        quality -= 10
                    } while (out.size > MAX_BYTES && quality > 20)
                    if (out.size > MAX_BYTES) null else out
                }
            } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            act.runOnUiThread {
                val cb = callback
                if (bytes == null || cb == null) Toast.makeText(act, "❌ That picture could not be used. Please try another one.", Toast.LENGTH_LONG).show()
                else cb(bytes)
            }
        }.start()
    }
}
