package com.heystrike.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Siri-style floating orb: appears over ANY screen when you say Hey Strike
 * (or tap Talk), shows listening glow, hides after the answer.
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var tts: TextToSpeech? = null
    private lateinit var api: StrikeApi

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                // Hindi/Hinglish answers speak Hindi; else US English.
                // Missing voice data must fall back, never go silent.
                val want = if (Prefs.voiceLang(this) == "hi") Locale("hi") else Locale.US
                tts?.language = want
                if (tts?.isLanguageAvailable(want) ?: -1 < 0) tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object :
                    android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        lastTtsError = "utterance $id failed to play"
                        Log.e(TAG, "TTS $id error — audio silent")
                    }
                })
                ttsReady = true
            } else {
                lastTtsError = "TTS engine init failed ($st)"
                Log.e(TAG, "TTS init failed: $st")
            }
        }
        api = StrikeApi(applicationContext, tts)
    }

    /** Live partial transcript from the gate (main thread). */
    fun showPartial(text: String) {
        if (!showing) return
        (root as? SiriOrbView)?.setText("Listening…\n“$text”")
    }

    /** Barge-in: stop TTS + cancel the answer thread; gate captures the new turn. */
    fun interruptAnswer() {
        gen++
        tts?.stop()
        speaking = false
        (root as? SiriOrbView)?.setText("Interrupted — listening…")
        (root as? SiriOrbView)?.setMode(SiriOrbView.MODE_LISTENING)
        Log.i(TAG, "overlay answer interrupted by user")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hide()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // started via startForegroundService: must enter FGS within 5s
                // (fixes ForegroundServiceDidNotStartInTimeException on API 31+)
                startFg()
                val heard = intent?.getStringExtra("heard").orEmpty()
                showOrb()
                // If launched with text already, answer it; else listening state stays for STT caller.
                if (heard.isNotBlank()) answerAndClose(heard)
                return START_STICKY
            }
        }
    }

    // The orb window is FULL-SCREEN: if nothing ever hides it, every button in
    // every app becomes untouchable. Watchdog guarantees auto-dismiss.
    private val main = Handler(android.os.Looper.getMainLooper())
    private var watchdog: Runnable? = null

    private fun armWatchdog(ms: Long) {
        watchdog?.let { main.removeCallbacks(it) }
        val w = Runnable {
            Log.w(TAG, "orb watchdog fired — dismissing (no answer path)")
            hide()
            stopSelf()
        }
        watchdog = w
        main.postDelayed(w, ms)
    }

    private fun startFg() {
        val ch = "strike_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(ch, "Hey Strike", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, ch)
            .setContentTitle("Hey Strike")
            .setContentText("Voice session active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        try {
            startForeground(2, n)
        } catch (e: Exception) {
            // startForegroundService() was already called — if we never reach
            // foreground the system kills us with RemoteServiceException ~5s
            // later (the reported crash). Stop now instead of crashing.
            Log.e(TAG, "overlay foreground declined: ${e.message} — stopping")
            stopSelf()
        }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER }

    private fun showOrb() {
        if (!Settings.canDrawOverlays(this)) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            stopSelf() // no overlay possible — don't idle as a foreground service
            return
        }
        if (root != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        root = SiriOrbView(this) {
            hide()
            stopSelf()
        }
        wm?.addView(root, params())
        showing = true
        // idle orb (mic tap / wake with no answer yet): never block the screen >15s
        armWatchdog(15_000)
    }

    private var gen = 0 // glitch guard: overlapping answers can't fight over the orb
    private var speaking = false

    private var ttsReady = false

    private fun answerAndClose(text: String) {
        val myGen = ++gen
        speaking = false
        armWatchdog(90_000) // answers live up to ~90s; still never permanent
        Log.i(TAG, "agent started")
        val t0 = System.currentTimeMillis()
        var firstTokenAt = 0L
        Thread {
            // first speak() before onInit = silent answer — wait here (bg thread)
            var guard = 0
            while (!ttsReady && tts != null && guard++ < 50) Thread.sleep(100)
            (root as? SiriOrbView)?.setText("“$text”\n\n…")
            (root as? SiriOrbView)?.setMode(SiriOrbView.MODE_THINKING)
            val full = StringBuilder()
            val pending = StringBuilder()
            fun flushSpeech(force: Boolean) {
                val s = pending.toString()
                // speak sentence-by-sentence as they complete (live speaking)
                val end = s.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                if (end >= 0 || (force && s.isNotBlank())) {
                    val part = if (end >= 0) s.substring(0, end + 1) else s
                    pending.delete(0, part.length)
                    speakChunk(part.trim())
                }
            }
            api.handleStream(text,
                onToken = { tok ->
                    if (myGen != gen) return@handleStream
                    if (firstTokenAt == 0L) {
                        firstTokenAt = System.currentTimeMillis()
                        // stale turn: the answer took so long the user moved
                        // on (e.g. left Reels minutes ago) — speaking it now
                        // is wrong-time audio, so die quietly instead
                        if (firstTokenAt - t0 > 90_000) {
                            Log.i(TAG, "stale turn (>90s to first token) — dropped, not spoken")
                            gen++ // invalidate: later tokens must not revive this turn
                            hide()
                            stopSelf()
                            return@handleStream
                        }
                    }
                    full.append(tok)
                    pending.append(tok)
                    (root as? SiriOrbView)?.setText("“$text”\n\n$full")
                    flushSpeech(false)
                },
                onDone = {
                    if (myGen != gen) return@handleStream
                    flushSpeech(true)
                    if (full.isNotBlank()) ConversationManager.recordAnswer(applicationContext, full.toString())
                    Log.i(TAG, "agent completed; draining TTS queue")
                    // keep the normal 3.5s linger, then wait for the REAL queue
                    // end — the old code hid at 3.5s flat and shutdown() cut
                    // long answers off mid-sentence
                    try { Thread.sleep(3500) } catch (_: Exception) {}
                    var guard = 0
                    while (tts?.isSpeaking == true && myGen == gen && guard++ < 120) Thread.sleep(500)
                    if (myGen != gen) return@handleStream
                    Log.i(TAG, "tts completed; wake gate resumed")
                    StrikeVoiceController.notifyIdle()
                    hide()
                    stopSelf()
                })
        }.start()
    }

    private fun speakChunk(s: String) {
        if (s.isBlank()) return
        // URLs belong on screen, never spoken (spec 5) — strip before markdown cleanup
        val clean = s.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF☀-➿➕➖*#>`_]"), "").trim().take(400)
        if (clean.isBlank()) return
        if (!speaking) {
            speaking = true
            (root as? SiriOrbView)?.setMode(SiriOrbView.MODE_SPEAKING)
            StrikeVoiceController.notifySpeaking()
            Log.i(TAG, "tts started")
        }
        // QUEUE_ADD = ChatGPT-style continuous speech while text keeps streaming
        val rc = try {
            tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "strike$gen")
                ?: TextToSpeech.ERROR
        } catch (e: Exception) {
            lastTtsError = "speak() threw: ${e.message}"
            TextToSpeech.ERROR
        }
        if (rc == TextToSpeech.ERROR) {
            lastTtsError = "speak() rejected (engine null or busy)"
            Log.e(TAG, "TTS speak rejected — answer will show as text only")
        }
    }

    private fun hide() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null
        showing = false
    }

    override fun onDestroy() {
        inst = null
        hide()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HIDE = "hide"
        const val TAG = "HeyStrikeAssistant"

        /** Last TTS failure, if any — surfaced in Settings voice output row. */
        @Volatile
        var lastTtsError: String? = null

        /** True while the orb is on screen (guards stale HIDE intents). */
        @Volatile
        var showing = false

        @Volatile
        var inst: OverlayService? = null

        fun show(c: Context, heard: String = "") {
            val i = Intent(c, OverlayService::class.java)
            if (heard.isNotBlank()) i.putExtra("heard", heard)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
                // background FGS start denied — answer path degrades, no crash
                Log.w(TAG, "overlay start declined: ${e.message}")
            }
        }

        /** Dismiss the orb if it is showing (blank command, session cleanup). */
        fun hideNow(c: Context) {
            if (!showing) return
            try {
                c.startService(Intent(c, OverlayService::class.java).apply { action = ACTION_HIDE })
            } catch (_: Exception) {}
        }
    }
}

/** Glowing orb + text drawn with plain Android views (no deps).
 *  Transparent backdrop (no black veil), dp-scaled core + halo, looping
 *  pulse, real state tints — listening blue, thinking purple, speaking orange. */
class SiriOrbView(c: Context, onTap: () -> Unit) : FrameLayout(c) {
    companion object {
        const val MODE_LISTENING = 0
        const val MODE_THINKING = 1
        const val MODE_SPEAKING = 2
    }

    private val label = android.widget.TextView(c).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 18f
        gravity = android.view.Gravity.CENTER
        text = "Listening…\n(tap to close)"
        setPadding(48, 24, 48, 48)
        // legibility without the old black veil: soft text shadow
        setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
    }
    private val glow = View(c).apply { alpha = 0.30f }
    private val orb = View(c).apply { alpha = 0.98f }
    private var pulseX: android.animation.ObjectAnimator? = null
    private var pulseY: android.animation.ObjectAnimator? = null

    init {
        // ponytail: transparent, not the old 0xCC000000 full-screen black
        setBackgroundColor(0x00000000)
        val d = resources.displayMetrics.density
        // Gemini-style: medium orb docked near the bottom, halo behind it
        val orbPx = (150 * d).toInt()
        val glowPx = (230 * d).toInt()
        val baseMargin = (100 * d).toInt()
        val glowLp = LayoutParams(glowPx, glowPx, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
        glowLp.bottomMargin = baseMargin - (glowPx - orbPx) / 2
        addView(glow, glowLp)
        val orbLp = LayoutParams(orbPx, orbPx, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
        orbLp.bottomMargin = baseMargin
        addView(orb, orbLp)
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
        lp.bottomMargin = baseMargin + orbPx + (12 * d).toInt()
        addView(label, lp)
        setOnClickListener { onTap() }
        applyMode(MODE_LISTENING)
    }

    fun setText(s: String) { post { label.text = s } }

    fun setMode(mode: Int) { post { applyMode(mode) } }

    private fun startPulse(speedMs: Long) {
        pulseX?.cancel()
        pulseY?.cancel()
        pulseX = android.animation.ObjectAnimator.ofFloat(orb, "scaleX", 1f, 1.1f).apply {
            duration = speedMs
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }
        pulseY = android.animation.ObjectAnimator.ofFloat(orb, "scaleY", 1f, 1.1f).apply {
            duration = speedMs
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }
        pulseX?.start()
        pulseY?.start()
    }

    private fun applyMode(mode: Int) {
        // radial: light center -> brand -> deep edge; halo = brand
        val core = when (mode) {
            MODE_THINKING -> intArrayOf(0xFFC4B5FD.toInt(), 0xFF7C3AED.toInt(), 0xFF5B21B6.toInt())
            MODE_SPEAKING -> intArrayOf(0xFFFCD34D.toInt(), 0xFFF59E0B.toInt(), 0xFFB45309.toInt())
            else -> intArrayOf(0xFF93C5FD.toInt(), 0xFF3B82F6.toInt(), 0xFF1D4ED8.toInt())
        }
        val d = resources.displayMetrics.density
        orb.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = core
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 75 * d
        }
        glow.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = intArrayOf(core[1], 0x00000000)
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 115 * d
        }
        // pulse follows the state: idle breathe, busy shimmer
        startPulse(when (mode) {
            MODE_THINKING -> 600L
            MODE_SPEAKING -> 450L
            else -> 900L
        })
    }
}
