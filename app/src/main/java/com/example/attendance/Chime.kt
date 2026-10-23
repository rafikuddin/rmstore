package com.example.attendance

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/**
 * The app's notification sound (res/raw/notify_chime.wav, a short rising two-note "ding-ding").
 *  - [uri] is what the reminder notifications use (see Reminders.show).
 *  - [play] sounds it inside the app when new notices arrive. It follows the phone's notification volume, so silent / vibrate mode
 *    keeps it quiet.
 */
object Chime {
    fun uri(ctx: Context): Uri = Uri.parse("android.resource://" + ctx.packageName + "/" + R.raw.notify_chime)

    private var lastAt = 0L

    fun play(ctx: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAt < 3000) return          // the bell and the pop-up can both ask at once: one ding-ding is enough
        lastAt = now
        try {
            val mp = MediaPlayer.create(
                ctx.applicationContext, R.raw.notify_chime,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
            ) ?: return
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.start()
        } catch (e: Exception) { /* no sound is better than a crash */ }
    }
}
