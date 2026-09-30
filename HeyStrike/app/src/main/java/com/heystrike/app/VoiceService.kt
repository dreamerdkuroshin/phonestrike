package com.heystrike.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Hosts the wake-word gate in a foreground mic service:
 * ONE AudioRecord -> Vosk gate -> beep + orb -> command -> brain -> back to gate.
 * Screen-off safe via wake lock; auto-restarts on boot via BootReceiver.
 */
class VoiceService : Service() {

    private var gate: GateEngine? = null
    private var tts: TextToSpeech? = null
    private lateinit var api: StrikeApi
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg("Hey Strike listening — say the wake word")
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "HeyStrike:gate")
        try { wakeLock?.acquire(12 * 60 * 60 * 1000L) } catch (_: Exception) {}
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        api = StrikeApi(applicationContext, tts)

        if (!ModelManager.ready(this)) {
            startFg("Open Hey Strike app → Download voice model (40MB, once)")
            return
        }
        gate = GateEngine(
            modelDir = ModelManager.dir(this).absolutePath,
            onWake = { OverlayService.show(this) },
            onCommand = { text -> OverlayService.show(this, text) },
            onError = { msg -> startFg("Gate error: $msg — reopen app to retry") }
        )
        gate?.start()
    }

    private fun startFg(text: String) {
        val ch = "strike_voice"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(ch, "Hey Strike", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, ch)
            .setContentTitle("Hey Strike")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(1, n)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Glitch guard: swiping the app away must not kill listening; restart it.
        try {
            val s = Intent(applicationContext, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { gate?.stop() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}
