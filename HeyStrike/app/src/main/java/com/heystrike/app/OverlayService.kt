package com.heystrike.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        api = StrikeApi(applicationContext, tts)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hide()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val heard = intent?.getStringExtra("heard").orEmpty()
                showOrb()
                // If launched with text already, answer it; else listening state stays for STT caller.
                if (heard.isNotBlank()) answerAndClose(heard)
                return START_STICKY
            }
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
            return
        }
        if (root != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        root = SiriOrbView(this) {
            hide()
            stopSelf()
        }
        wm?.addView(root, params())
    }

    private var gen = 0 // glitch guard: overlapping answers can't fight over the orb

    private fun answerAndClose(text: String) {
        val myGen = ++gen
        Thread {
            (root as? SiriOrbView)?.setText("“$text”\n\n…")
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
                    full.append(tok)
                    pending.append(tok)
                    (root as? SiriOrbView)?.setText("“$text”\n\n$full")
                    flushSpeech(false)
                },
                onDone = {
                    if (myGen != gen) return@handleStream
                    flushSpeech(true)
                    try { Thread.sleep(3500) } catch (_: Exception) {}
                    if (myGen != gen) return@handleStream
                    hide()
                    stopSelf()
                })
        }.start()
    }

    private fun speakChunk(s: String) {
        if (s.isBlank()) return
        val clean = s.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF☀-➿➕➖*#>`_]"), "").trim().take(400)
        if (clean.isBlank()) return
        // QUEUE_ADD = ChatGPT-style continuous speech while text keeps streaming
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "strike$gen")
    }

    private fun hide() {
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null
    }

    override fun onDestroy() {
        hide()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HIDE = "hide"
        fun show(c: Context, heard: String = "") {
            val i = Intent(c, OverlayService::class.java)
            if (heard.isNotBlank()) i.putExtra("heard", heard)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }
}

/** Glowing orb + text drawn with plain Android views (no deps). */
class SiriOrbView(c: Context, onTap: () -> Unit) : FrameLayout(c) {
    private val label = android.widget.TextView(c).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 18f
        gravity = android.view.Gravity.CENTER
        text = "Listening…"
        setPadding(48, 48, 48, 48)
    }
    private val orb = View(c).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt(), 0xFF3B82F6.toInt())
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 320f
        }
        alpha = 0.95f
    }

    init {
        setBackgroundColor(0xCC000000.toInt())
        val orbParams = LayoutParams(340, 340, Gravity.CENTER)
        addView(orb, orbParams)
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        lp.topMargin = 420
        addView(label, lp)
        setOnClickListener { onTap() }
        // gentle pulse
        orb.animate().scaleX(1.12f).scaleY(1.12f).setDuration(700)
            .withEndAction { orb.animate().scaleX(1f).scaleY(1f).setDuration(700).start() }
            .start()
    }

    fun setText(s: String) { post { label.text = s } }
}
