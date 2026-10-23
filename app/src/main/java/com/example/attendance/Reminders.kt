package com.example.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Attendance reminders in the phone's notification bar.
 *
 * About every 30 minutes (Android decides the exact moment to save battery) a background job asks the server "do I need a reminder?".
 * The SERVER decides (Code.gs, reminderStatus): from 8:30 AM until 6:00 PM on working days, a person who has not marked attendance
 * gets "Mark your attendance", and a manager also gets "N team members have not marked yet" with the names. Nothing is shown
 * once the person has marked attendance, is on approved leave, or it is Friday / a holiday. This is not a push message from a
 * server (that would need Firebase), so it can be a little late on phones with an aggressive battery saver.
 */
object Reminders {
    private const val WORK = "attendance-reminders"
    // v2: a channel's sound can only be set when it is created, so the chime needed a new channel (the old silent/default one is removed).
    private const val CHANNEL = "attendance_reminders_v2"
    private const val OLD_CHANNEL = "attendance_reminders"
    private val VIBRATION = longArrayOf(0, 250, 120, 250)
    const val ID_SELF = 4101
    const val ID_TEAM = 4102
    private const val PREFS = "reminders"          // only an on/off switch and "when did I last remind": nothing private

    fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("on", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("on", on).apply()
        if (on) schedule(ctx) else stop(ctx)
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Starts the background check (once; calling it again keeps the existing schedule). */
    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val request = PeriodicWorkRequest.Builder(ReminderWorker::class.java, 30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Stops the background check and removes any reminder still showing (sign-out, or the person switched reminders off). */
    fun stop(ctx: Context) {
        try { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK) } catch (e: Exception) { /* nothing scheduled */ }
        clearAll(ctx)
    }

    fun clearSelf(ctx: Context) { NotificationManagerCompat.from(ctx.applicationContext).cancel(ID_SELF) }

    fun clearAll(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx.applicationContext)
        nm.cancel(ID_SELF); nm.cancel(ID_TEAM)
        prefs(ctx).edit().remove("self_at").remove("team_at").apply()
    }

    @SuppressLint("MissingPermission")          // canNotify() checks it
    fun show(ctx: Context, id: Int, title: String, text: String, target: Class<*>) {
        if (!canNotify(ctx)) return
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(OLD_CHANNEL) != null) nm.deleteNotificationChannel(OLD_CHANNEL)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Attendance reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminds you to mark attendance, and tells managers who has not marked yet."
                    setSound(Chime.uri(app), android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    enableVibration(true); vibrationPattern = VIBRATION
                })
            }
        }
        val open = PendingIntent.getActivity(
            app, id, Intent(app, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(Chime.uri(app)).setVibrate(VIBRATION)          // Android 7 and older; on 8+ the channel decides
            .build()
        NotificationManagerCompat.from(app).notify(id, n)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val app = applicationContext
        if (!Session(app).loggedIn || !Reminders.enabled(app)) return Result.success()
        val r = try { postJson(app, JSONObject().put("action", "reminder")) } catch (e: Exception) { return Result.success() }   // no internet: try again next time
        if (!r.optBoolean("ok")) return Result.success()                  // signed out, or a new password is required first
        if (!r.optBoolean("enabled", true)) { Reminders.clearAll(app); return Result.success() }

        val p = Reminders.prefs(app)
        val now = System.currentTimeMillis()
        val gap = 55 * 60 * 1000L                                         // at most one reminder of each kind per hour

        val self = r.optJSONObject("self")
        if (self == null) Reminders.clearSelf(app)                        // marked meanwhile, on leave, or the day is over
        else if (now - p.getLong("self_at", 0L) >= gap) {
            val late = self.optBoolean("late")
            Reminders.show(
                app, Reminders.ID_SELF,
                if (late) "⏰ You have not marked attendance" else "⏰ Mark your attendance",
                if (late) "Your attendance is not in yet today. Tap to submit it now."
                else "The office starts soon. Submit before ${r.optString("lateAtText", "9:10 AM")} to be on time. Tap to submit.",
                AttendanceActivity::class.java
            )
            p.edit().putLong("self_at", now).apply()
        }

        val team = r.optJSONObject("team")
        if (team == null) androidx.core.app.NotificationManagerCompat.from(app).cancel(Reminders.ID_TEAM)
        else if (now - p.getLong("team_at", 0L) >= gap) {
            val count = team.optInt("count")
            val names = team.optJSONArray("names")
            val shown = ArrayList<String>()
            if (names != null) for (i in 0 until names.length()) shown.add(names.optString(i))
            val more = count - shown.size
            Reminders.show(
                app, Reminders.ID_TEAM,
                "👥 $count team member${if (count == 1) "" else "s"} not marked yet",
                shown.joinToString(", ") + (if (more > 0) " and $more more" else "") + ". Tap to open My Team.",
                TeamActivity::class.java
            )
            p.edit().putLong("team_at", now).apply()
        }
        return Result.success()
    }
}
