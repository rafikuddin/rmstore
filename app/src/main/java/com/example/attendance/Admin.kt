package com.example.attendance

import android.content.Context

/**
 * Whether the signed-in person is an admin. The server decides (the Role column in the Users sheet) and tells the app
 * at login and with every Home refresh; the app only uses it to show or hide the Admin screen. Every admin action is
 * checked again on the server, so changing this value on the phone gives no extra power.
 * Stored in the same encrypted file as the session, so signing out clears it too.
 */
object AdminFlag {
    private const val KEY = "adm"
    fun get(ctx: Context): Boolean = SecureStore.prefs(ctx, "session_secure").getBoolean(KEY, false)
    fun set(ctx: Context, value: Boolean) {
        SecureStore.prefs(ctx, "session_secure").edit().putBoolean(KEY, value).apply()
    }
}

/** Intent extra: the date (yyyy-MM-dd) an admin opened for manual attendance / night hold. Absent = the normal screen. */
const val EXTRA_FOR_DATE = "forDate"
