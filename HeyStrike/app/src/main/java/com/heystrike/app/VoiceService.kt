package com.heystrike.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Foreground mic service: in-app SpeechRecognizer (NO system popup),
 * watches for "hey strike" then shows the Siri orb and answers.
 * Works with screen off while service runs; on lockscreen the orb shows
 * over the keyguard via FLAG_SHOW_WHEN_LOCKED.
 */
class VoiceService : Service() {

    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var api: StrikeApi
    private var restartWanted = true

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        api = StrikeApi(applicationContext, tts)
        beginLoop()
    }

    private fun startFg() {
        val ch = "strike_voice"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(ch, "Hey Strike", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, ch)
            .setContentTitle("Hey Strike listening")
            .setContentText("Say \"Hey Strike\" — orb appears like Siri")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(1, n)
    }

    private fun listenOnce() {
        try { sr?.destroy() } catch (_: Exception) {}
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Thread.sleep(5000)
            if (restartWanted) listenOnce()
            return
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                val heard = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onHeard(heard)
            }
            override fun onError(e: Int) { loopSoon() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { sr?.startListening(i) } catch (_: Exception) { loopSoon() }
    }

    private fun onHeard(heard: String) {
        val low = heard.lowercase()
        val wake = low.contains("strike") || low.contains("hey siri") ||
            listOf("open ", "battery", "what time", "call ", "torch", "search ")
                .any { low.startsWith(it) }
        if (wake) {
            // strip wake for single-shot ("hey strike open whatsapp")
            val cmd = low.split("strike").lastOrNull()?.trim(" ,.!?".toSet())
                .orEmpty().ifBlank { heard }
            // Orb shows immediately (Siri-style), answers + speaks, then hides.
            OverlayService.show(this, if (cmd.length > 2) cmd else heard)
        }
        loopSoon()
    }

    private fun loopSoon() {
        Thread {
            Thread.sleep(600)
            if (restartWanted) runOnUiThreadSafe { listenOnce() }
        }.start()
    }

    private fun runOnUiThreadSafe(f: () -> Unit) {
        android.os.Handler(mainLooper).post { try { f() } catch (_: Exception) {} }
    }

    private fun beginLoop() = listenOnce()

    override fun onDestroy() {
        restartWanted = false
        try { sr?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}
