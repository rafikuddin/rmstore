package com.example.attendance

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Encrypted SharedPreferences: AES-256, with the key kept inside the Android Keystore.
 * The sign-in token and the saved server responses live here instead of in plain text files.
 */
object SecureStore {
    private val open = HashMap<String, SharedPreferences>()
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    @Synchronized
    fun prefs(ctx: Context, name: String): SharedPreferences {
        open[name]?.let { return it }
        val app = ctx.applicationContext
        val p = try {
            create(app, name)
        } catch (e: Exception) {
            // The file or its Keystore key can't be read (this happens after some phone restores / OS updates).
            // Start clean: the person simply signs in again. Nothing is lost, the data lives in the Google Sheet.
            app.deleteSharedPreferences(name)
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(MASTER_KEY_ALIAS) }
            } catch (ignored: Exception) {
            }
            create(app, name)
        }
        open[name] = p
        return p
    }

    private fun create(app: Context, name: String): SharedPreferences {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, name, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

/** Removes what the app keeps on the phone about a person. */
object Wipe {
    /** Saved server responses, downloaded selfies and any captured photo that is still lying around. */
    fun cachedData(ctx: Context) {
        ResponseCache.clear(ctx)
        PhotoLoader.clearMemory()
        AdminElev.clear()
        // Everything in the cache folder goes: selfies, profile pictures, an admin's downloaded reports, half-made uploads. Nothing here is needed after a sign-out.
        try { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } } catch (e: Exception) { /* best effort */ }
    }

    fun signOutLocally(ctx: Context) {
        cachedData(ctx)
        Reminders.stop(ctx)                  // no more reminders for someone who is signed out
        Session(ctx).clear()
    }
}

/** True when the location came from a fake-GPS / mock-location app. */
@Suppress("DEPRECATION")
fun isMockLocation(loc: Location): Boolean =
    if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
