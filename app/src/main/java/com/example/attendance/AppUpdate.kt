package com.example.attendance

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Updating the app from inside the app.
 *
 * An admin uploads the new APK (Admin Panel -> App); the server keeps it in small pieces. Every phone learns the newest
 * version number when it opens Home, and when it is higher than the installed one an Update button appears. The phone
 * downloads the pieces through the server (signed-in users only), checks the file's SHA-256 against the value the admin's
 * phone computed, and opens Android's installer. Android only accepts an update signed with the SAME key as the installed
 * app, so a wrong or tampered file can never replace it.
 */
object AppUpdate {
    /** Size of one uploaded/downloaded piece. Must match UPDATE_PIECE_BYTES in Code.gs. */
    const val PIECE = 524288

    @Suppress("DEPRECATION")
    fun codeOf(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    fun installedCode(ctx: Context): Long = try { codeOf(ctx.packageManager.getPackageInfo(ctx.packageName, 0)) } catch (e: Exception) { 0L }
    fun installedName(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** True when the server's published version is higher than the one installed on this phone. */
    fun isNewer(ctx: Context, info: JSONObject?): Boolean = info != null && info.optLong("versionCode") > installedCode(ctx)

    // ---- Compulsory updates ----
    // When the admin publishes a COMPULSORY version, every phone with an older build is stopped on the "Update required" screen
    // (ForceUpdateActivity) and can use nothing else. The requirement is remembered here (plain, not secret) so it also holds
    // offline and after the app is closed; it disappears as soon as the new build is installed, or when the server says the
    // update is no longer compulsory (checked on Home and on the Update screen's "Check again").
    private fun forcePrefs(ctx: Context) = ctx.getSharedPreferences("app_update_force", Context.MODE_PRIVATE)

    /** Call with the server's `update` object (or null) whenever it is received. Keeps or clears the stored requirement. */
    fun remember(ctx: Context, info: JSONObject?) {
        val p = forcePrefs(ctx).edit()
        if (info != null && info.optBoolean("mandatory") && isNewer(ctx, info)) p.putString("info", info.toString()) else p.remove("info")
        p.apply()
    }

    /** The compulsory update this phone still has to install, or null. Self-clears once the installed build is new enough. */
    fun required(ctx: Context): JSONObject? {
        val raw = forcePrefs(ctx).getString("info", null) ?: return null
        val info = try { JSONObject(raw) } catch (e: Exception) { forcePrefs(ctx).edit().remove("info").apply(); return null }
        if (!isNewer(ctx, info)) { forcePrefs(ctx).edit().remove("info").apply(); return null }
        return info
    }

    /** Opens the "Update required" screen on top of [activity] when a compulsory update is waiting. Returns true when it did. */
    fun enforce(activity: android.app.Activity): Boolean {
        if (activity is ForceUpdateActivity || activity is LoginActivity || activity is SignupActivity) return false
        if (!Session(activity).loggedIn || required(activity) == null) return false
        activity.startActivity(Intent(activity, ForceUpdateActivity::class.java))
        return true
    }

    private fun updatesDir(ctx: Context): File = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Removes a downloaded or half-prepared APK (they are 6 MB or more). Safe to call at any time. */
    fun cleanUp(ctx: Context) {
        try { File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() } } catch (e: Exception) { /* nothing to clean */ }
    }

    fun uploadFile(ctx: Context): File = File(updatesDir(ctx), "upload.apk")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** SHA-256 of the certificate an APK file is signed with ("" when it cannot be read). The server compares it with the release key. */
    @Suppress("DEPRECATION")
    fun certSha256(ctx: Context, f: File): String = try {
        val api28 = Build.VERSION.SDK_INT >= 28
        val flags = if (api28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val pi = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, flags)
        val sig = if (api28) pi?.signingInfo?.apkContentsSigners?.firstOrNull() else pi?.signatures?.firstOrNull()
        if (sig == null) "" else hex(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()))
    } catch (e: Exception) { "" }

    private fun mayInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    /** "Update available" pop-up with the admin's notes. */
    fun offer(activity: AppCompatActivity, info: JSONObject) {
        val mb = info.optLong("size") / 1048576.0
        val notes = info.optString("notes")
        val msg = "Version ${info.optString("versionName")} is ready (${"%.1f".format(mb)} MB). You have ${installedName(activity)}.\n\n" +
            (if (notes.isNotEmpty()) "$notes\n\n" else "") + "Your data is not affected."
        AlertDialog.Builder(activity).setTitle("Update available").setMessage(msg)
            .setPositiveButton("Update now") { _, _ -> start(activity, info) }
            .setNegativeButton("Later", null).show()
    }

    fun start(activity: AppCompatActivity, info: JSONObject) {
        if (!mayInstall(activity)) {
            AlertDialog.Builder(activity).setTitle("Allow installing")
                .setMessage("Android needs your permission before this app can install its own update. On the next screen turn on \"Allow from this source\", then come back and tap Update again.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        download(activity, info)
    }

    /** A small progress pop-up used for downloading (users) and uploading (admins). */
    class ProgressBox(private val activity: AppCompatActivity, title: String, max: Int, onCancel: (() -> Unit)?) {
        private val text = TextView(activity)
        private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        private val dialog: AlertDialog

        init {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            bar.max = max
            bar.progress = 0
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad / 2)
                addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val b = AlertDialog.Builder(activity).setTitle(title).setView(box).setCancelable(false)
            if (onCancel != null) b.setNegativeButton("Cancel") { _, _ -> onCancel() }
            dialog = b.create()
            dialog.show()
        }

        fun set(done: Int, message: String) {
            activity.runOnUiThread { bar.progress = done; text.text = message }
        }

        fun close() {
            activity.runOnUiThread { try { dialog.dismiss() } catch (e: Exception) { /* screen already closed */ } }
        }
    }

    /** One piece with up to 3 tries. Returns the bytes, or throws with a readable message. */
    private fun fetchPiece(ctx: Context, index: Int, versionCode: Long): ByteArray {
        var last = "Download failed. Check your internet and try again."
        for (attempt in 1..3) {
            try {
                val r = postJson(ctx, JSONObject().put("action", "appChunk").put("index", index).put("versionCode", versionCode))
                if (r.optBoolean("auth")) throw IllegalStateException("Your session ended. Please sign in again.")
                if (r.optBoolean("ok")) return Base64.decode(r.getString("data"), Base64.DEFAULT)
                last = r.optString("error", last)
                if (r.optBoolean("changed")) break          // a newer version was published meanwhile: retrying cannot help
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                last = "Download failed. Check your internet and try again."
            }
        }
        throw IllegalStateException(last)
    }

    private fun download(activity: AppCompatActivity, info: JSONObject) {
        val chunks = info.optInt("chunks")
        val code = info.optLong("versionCode")
        val size = info.optLong("size")
        val wantHash = info.optString("sha256").lowercase()
        var cancelled = false
        val box = ProgressBox(activity, "Downloading update", chunks) { cancelled = true }
        box.set(0, "Starting…")
        val app = activity.applicationContext

        Thread {
            var error: String? = null
            var file: File? = null
            try {
                cleanUp(app)
                val f = File(updatesDir(app), "update.apk")
                file = f
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(f).use { out ->
                    for (i in 0 until chunks) {
                        if (cancelled) { error = "cancelled"; break }
                        val bytes = fetchPiece(app, i, code)
                        out.write(bytes)
                        digest.update(bytes)
                        box.set(i + 1, "Downloading… ${i + 1} of $chunks")
                    }
                }
                if (error == null && (f.length() != size || hex(digest.digest()) != wantHash))
                    error = "The downloaded file is damaged. Please try again."
            } catch (e: IllegalStateException) {
                error = e.message ?: "Download failed."
            } catch (e: Exception) {
                error = "Download failed. Check your internet and try again."
            }
            val finalError = error
            val finalFile = file
            box.close()
            activity.runOnUiThread {
                when {
                    finalError == "cancelled" -> { finalFile?.delete(); toast(activity, "Update cancelled.") }
                    finalError != null -> {
                        finalFile?.delete()
                        AlertDialog.Builder(activity).setTitle("Update failed").setMessage(finalError).setPositiveButton("OK", null).show()
                    }
                    finalFile != null -> install(activity, finalFile)
                }
            }
        }.start()
    }

    /** Hands the downloaded file to Android's installer. The person confirms there, and the app restarts when it finishes. */
    private fun install(activity: AppCompatActivity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val go = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(go)
        } catch (e: Exception) {
            AlertDialog.Builder(activity).setTitle("Could not open the installer")
                .setMessage("The update was downloaded but Android would not open it. Try again, or ask your admin for the APK.")
                .setPositiveButton("OK", null).show()
        }
    }
}
