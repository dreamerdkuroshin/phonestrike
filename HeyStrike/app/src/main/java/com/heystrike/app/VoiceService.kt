package com.heystrike.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground mic service with TWO modes, ONE gate (StrikeVoiceController):
 *  A. Shell mode (assistant role held): only the mic FGS + wake lock +
 *     notification — AssistantService owns the wake-word gate.
 *  B. Standalone mode: this service owns the gate (explicit "always listen").
 * Never both: the controller refuses a second engine.
 */
class VoiceService : Service() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var shell = false

    private val stateListener: (StrikeVoiceController.State) -> Unit = { refreshFg() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        shell = isAssistantHeld()
        acquireWakeLock()
        StrikeVoiceController.addStateListener(stateListener)
        refreshFg()

        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!mic || !ModelManager.ready(this)) return // notification already says why

        if (!shell && !ModelManager.sherpaReady(this)) {
            // background: streaming command model; first command uses Vosk fallback
            Thread {
                try {
                    Log.i(TAG, "sherpa model download starting")
                    ModelManager.downloadSherpa(applicationContext, { _, _ -> })
                    Log.i(TAG, "sherpa model ready")
                } catch (e: Exception) {
                    Log.w(TAG, "sherpa download failed: ${e.message}")
                }
            }.start()
        }
        // Starts the gate if nobody owns it. In shell mode the assistant
        // service normally owns it — a refused start is fine. It is the
        // fallback for "assistant boot failed / gate died" AND it revives a
        // dead session (controller revive path).
        val ok = StrikeVoiceController.startWakeWord(
            StrikeVoiceController.OWNER_VOICE,
            applicationContext,
            onWake = { OverlayService.show(this) },
            onCommand = { text -> OverlayService.show(this, text) },
            onError = { msg -> startFg("Gate error: $msg — reopen app to retry") },
            onPartial = { p -> OverlayService.inst?.showPartial(p) },
            onInterrupt = { OverlayService.inst?.interruptAnswer() }
        )
        if (!ok) {
            Log.i(TAG, "gate refused — engine active (owner=${StrikeVoiceController.currentOwner()})")
        }
        refreshFg()
    }

    /**
     * The notification tells the TRUTH about why listening is/isn't active —
     * silent gate death was reported as "service stopped, no response".
     */
    private fun refreshFg() {
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val text = when {
            !mic -> "Microphone permission missing — open Hey Strike"
            !ModelManager.ready(this) -> "Voice model missing — open Hey Strike → Download"
            StrikeVoiceController.lastError != null && !StrikeVoiceController.isRunning() ->
                "Gate error: ${StrikeVoiceController.lastError} — open app to retry"
            StrikeVoiceController.isRunning() && shell -> "Hey Strike — system assistant listening"
            StrikeVoiceController.isRunning() -> "Hey Strike listening — say the wake word"
            shell -> "Hey Strike — assistant starting…"
            else -> "Hey Strike — starting…"
        }
        startFg(text)
    }

    private fun isAssistantHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } catch (_: Exception) { false }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "HeyStrike:gate")
        try { wakeLock?.acquire(12 * 60 * 60 * 1000L) } catch (_: Exception) {}
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
        try {
            startForeground(1, n)
        } catch (e: Exception) {
            // background mic FGS start denied (API 34 rules) -> degrade, don't crash
            Log.e(TAG, "foreground start declined: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        refreshFg() // any (re)start re-evaluates the real gate state
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Glitch guard: swiping the app away must not kill listening; restart it.
        try {
            val s = Intent(applicationContext, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        // no-op when another owner holds the gate (ownership check inside)
        StrikeVoiceController.removeStateListener(stateListener)
        StrikeVoiceController.stopWakeWord(StrikeVoiceController.OWNER_VOICE)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val TAG = "HeyStrikeAssistant"
    }
}
