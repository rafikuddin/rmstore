package com.example.attendance

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * One reliable way to get the live GPS position, used by both the Attendance and the Night Hold screens.
 *
 * Why this exists: asking Google for "one current location" can come back empty the first time on a phone whose
 * GPS has been idle (a cold start), and only the second try works. This helper instead:
 *   1. checks the phone's Location switch first and says so if it is off,
 *   2. takes a very recent saved position straight away when there is one (not older than 45 seconds),
 *   3. keeps listening for live updates until a good fix arrives (no single try that can come back empty),
 *   4. lets the fix be a little rougher after 10 seconds so a phone indoors still gets a position,
 *   5. reports progress every second and gives up with a clear message after 45 seconds.
 */
object LiveLocation {

    private const val GOOD_ACCURACY_M = 150f      // accepted at once
    private const val LOOSE_ACCURACY_M = 1000f    // accepted after LOOSEN_AFTER_S
    private const val LOOSEN_AFTER_S = 10
    private const val GIVE_UP_AFTER_S = 45
    private const val MAX_AGE_MS = 45_000L

    class Handle internal constructor(private val stopper: () -> Unit) {
        fun cancel() = stopper()
    }

    /**
     * Starts the search. [onProgress] is called every second with the seconds spent so far,
     * then exactly one of [onFix] / [onFail] is called, unless the returned handle is cancelled first.
     * All callbacks run on the main thread. Location permission must already be granted.
     */
    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        onProgress: (seconds: Int) -> Unit,
        onFix: (Location) -> Unit,
        onFail: (String) -> Unit
    ): Handle {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val client = LocationServices.getFusedLocationProviderClient(app)
        val startedAt = SystemClock.elapsedRealtime()
        var finished = false
        val cbHolder = arrayOfNulls<LocationCallback>(1)

        fun stop() {
            finished = true
            handler.removeCallbacksAndMessages(null)
            cbHolder[0]?.let { try { client.removeLocationUpdates(it) } catch (_: Exception) {} }
        }

        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
            handler.post {
                onFail("Location (GPS) is switched off on your phone. Turn it on from the quick settings, then tap Retry.")
            }
            return Handle { }
        }

        fun ageMs(l: Location): Long = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L

        fun accept(l: Location, seconds: Int): Boolean {
            if (ageMs(l) > MAX_AGE_MS) return false
            if (!l.hasAccuracy()) return true
            val limit = if (seconds >= LOOSEN_AFTER_S) LOOSE_ACCURACY_M else GOOD_ACCURACY_M
            return l.accuracy <= limit
        }

        fun deliver(l: Location) {
            if (finished) return
            stop()
            onFix(l)
        }

        fun seconds(): Int = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (finished) return
                // The newest first; take the first one that is good enough for the time spent so far.
                val s = seconds()
                val best = result.locations.asReversed().firstOrNull { accept(it, s) }
                if (best != null) deliver(best)
            }
        }

        cbHolder[0] = callback

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(0L)
            .build()

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    if (!finished) { stop(); onFail("Location failed: ${it.message ?: "unknown error"}. Turn on GPS, then tap Retry.") }
                }
        } catch (e: SecurityException) {
            stop()
            handler.post { onFail("Location permission is needed. Allow it, then tap Retry.") }
            return Handle { }
        }

        // A recent saved position is the quickest answer, so ask for it in parallel.
        client.lastLocation.addOnSuccessListener { l ->
            if (!finished && l != null && accept(l, 0)) deliver(l)
        }

        val ticker = object : Runnable {
            override fun run() {
                if (finished) return
                val s = seconds()
                if (s >= GIVE_UP_AFTER_S) {
                    stop()
                    onFail("Could not find your location. Switch Location ON (High accuracy), move near a window or outside, then tap Retry.")
                    return
                }
                onProgress(s)
                handler.postDelayed(this, 1000L)
            }
        }
        handler.postDelayed(ticker, 1000L)

        return Handle { if (!finished) stop() }
    }

    /** The text shown on screen while searching. */
    fun waitingText(seconds: Int): String = when {
        seconds < 5 -> "Getting your location…"
        seconds < 15 -> "Getting your location… ${seconds}s"
        else -> "Still searching… ${seconds}s\nMove near a window or outside for a faster fix."
    }
}
