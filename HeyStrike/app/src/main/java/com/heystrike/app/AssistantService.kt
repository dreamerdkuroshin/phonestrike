package com.heystrike.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors

/**
 * System-assistant mode. When Hey Strike holds the assistant role the OS keeps
 * this service alive for background hotwording, headset long-press, and
 * lockscreen invocation (same privilege class as Google Assistant).
 *
 * Lifecycle: onReady -> verify mic/model/role on a background executor ->
 * hand the ONE shared wake-word gate to StrikeVoiceController (owner=assistant).
 * Wake -> showSession (system UI layer) -> command capture -> answer -> back
 * to listening. Heavy work (Vosk/AudioRecord) stays on the gate thread; this
 * service itself stays lightweight.
 */
class AssistantService : VoiceInteractionService() {

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "strike-assistant-init").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "AssistantService.onReady")
        exec.execute { bootGate() }
    }

    private fun bootGate() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "microphone permission ${if (mic) "granted" else "DENIED"}")

        val role = try {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } catch (_: Exception) { false }
        Log.i(TAG, "assistant role ${if (role) "active" else "NOT held"}")

        if (!ModelManager.ready(this)) {
            Log.e(TAG, "Vosk model missing — open the app once to download it")
            startMicFgs() // notification shows the real reason (voice model missing)
            return
        }
        if (!mic) {
            Log.e(TAG, "microphone permission missing")
            startMicFgs() // notification shows the real reason (mic permission)
            return
        }

        acquireWakeLock()
        if (!ModelManager.sherpaReady(this)) {
            // best-effort background download — first command falls back to Vosk
            // until the streaming model lands
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
        var ok = startGate()
        if (!ok && StrikeVoiceController.currentOwner() == StrikeVoiceController.OWNER_VOICE) {
            // Role granted while standalone owned the gate — system mode wins.
            Log.i(TAG, "releasing standalone gate — system assistant takes ownership")
            StrikeVoiceController.stopWakeWord(StrikeVoiceController.OWNER_VOICE)
            ok = startGate()
        }
        Log.i(TAG, if (ok) "wake gate starting" else "wake gate refused (owner=${StrikeVoiceController.currentOwner()})")

        // Best-effort mic FGS: needed on some builds for background capture;
        // legal from assist-role context, declines gracefully where not.
        startMicFgs()
    }

    private fun startMicFgs() {
        try {
            val s = Intent(this, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
        } catch (e: Exception) {
            Log.w(TAG, "mic foreground service declined: ${e.message}")
        }
    }

    private fun startGate() = StrikeVoiceController.startWakeWord(
        StrikeVoiceController.OWNER_ASSISTANT,
        applicationContext,
        onWake = { main.post { onWakeWord() } },
        onCommand = { text -> onCommandCaptured(text) },
        onError = { msg ->
            Log.e(TAG, "gate error: $msg (controller retries)")
            startMicFgs() // refresh the notification with the gate-error text
        },
        onPartial = { p -> AssistantSessionService.active?.showListening(p) },
        onInterrupt = { AssistantSessionService.active?.interruptSpeaking() }
    )

    private fun onWakeWord() {
        Log.i(TAG, "wake detected — session shown (assisting)")
        try {
            showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
        } catch (e: Exception) {
            Log.e(TAG, "showSession failed: ${e.message}")
            StrikeVoiceController.consumeWakeShown()
            // fallback: visual layer only
            OverlayService.show(this)
        }
    }

    private fun onCommandCaptured(text: String) {
        Log.i(TAG, "command captured (${text.length} chars)")
        val s = AssistantSessionService.active
        if (s != null && s.isShown) {
            s.answer(text)
        } else {
            Log.w(TAG, "no session UI — answering via overlay fallback")
            OverlayService.show(this, text)
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        try {
            showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
        } catch (e: Exception) {
            Log.e(TAG, "keyguard showSession failed: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyStrike:assistant").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
            Log.i(TAG, "partial wake lock acquired (screen-off listening)")
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "AssistantService.onDestroy")
        StrikeVoiceController.stopWakeWord(StrikeVoiceController.OWNER_ASSISTANT)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        exec.shutdown()
        super.onDestroy()
    }

    companion object {
        const val TAG = "HeyStrikeAssistant"
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistantSession(this).also { active = it }

    companion object {
        @Volatile
        var active: AssistantSession? = null
    }
}

/**
 * The system session window: Siri orb over whatever is on screen (incl.
 * lockscreen — no FGS needed for system UI). Wake path: the gate is already
 * capturing, session just shows. Gesture path (assist key/gesture): session
 * asks the shared gate for a one-shot command capture. answer() streams the
 * agent reply into the orb + TTS, then hands control back to listening.
 */
class AssistantSession(ctx: Context) : VoiceInteractionSession(ctx) {

    private val TAG = AssistantService.TAG

    @Volatile
    var isShown = false
        private set

    private var orb: SiriOrbView? = null
    private var tts: TextToSpeech? = null
    private var api: StrikeApi? = null
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // screen stays on while the session is showing
        setKeepAwake(true)
    }

    override fun onDestroy() {
        isShown = false
        if (AssistantSessionService.active === this) AssistantSessionService.active = null
        super.onDestroy()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        isShown = true
        Log.i(TAG, "session shown (flags=$showFlags)")
        try {
            // visible + wakes the screen even from the lockscreen
            // (getWindow() on a session returns the Dialog, not the Window)
            window?.window?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        } catch (_: Exception) {}
        if (orb == null) {
            val v = SiriOrbView(context) { hide() }
            orb = v
            setContentView(v)
        } else {
            orb?.setText("Listening…")
        }
        if (StrikeVoiceController.consumeWakeShown()) {
            // wake-word path: the gate is already capturing the command
        } else {
            // gesture/assist path: one-shot capture on the shared gate
            StrikeVoiceController.requestCapture()
        }
    }

    override fun onHide() {
        super.onHide()
        isShown = false
        Log.i(TAG, "session hidden")
        gen++ // cancel any in-flight answer
        StrikeVoiceController.notifyIdle()
        tts?.stop()
        tts?.shutdown()
        tts = null
        api = null
        if (OverlayService.showing) {
            try {
                context.startService(Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_HIDE
                })
            } catch (_: Exception) {}
        }
    }

    /** Live partial transcript while the gate listens (main thread via setText). */
    fun showListening(partial: String) {
        if (!isShown || partial.isBlank()) return
        orb?.setText("Listening…\n“$partial”")
    }

    /** Barge-in: stop TTS + cancel the answer thread; the gate is already
     *  capturing the new command (beep suppressed on that path). */
    fun interruptSpeaking() {
        if (!isShown) return
        gen++
        tts?.stop()
        orb?.setText("Interrupted — listening…")
        Log.i(TAG, "assistant speaking interrupted by user")
    }

    fun answer(text: String) {
        val myGen = ++gen
        Log.i(TAG, "agent started")
        Thread({
            orb?.setText("“$text”\n\n…")
            val a = ensureApi()
            val full = StringBuilder()
            val pending = StringBuilder()
            var spoke = false
            fun flush(force: Boolean) {
                if (myGen != gen) return
                val s = pending.toString()
                val end = s.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                if (end >= 0 || (force && s.isNotBlank())) {
                    val part = if (end >= 0) s.substring(0, end + 1) else s
                    pending.delete(0, part.length)
                    val clean = part.trim()
                        .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF☀-➿➕➖*#>`_]"), "")
                        .trim().take(400)
                    if (clean.isNotBlank()) {
                        if (!spoke) {
                            spoke = true
                            StrikeVoiceController.notifySpeaking()
                            Log.i(TAG, "tts started")
                        }
                        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "strike$myGen")
                    }
                }
            }
            a.handleStream(text,
                onToken = { tok ->
                    if (myGen != gen) return@handleStream
                    full.append(tok)
                    pending.append(tok)
                    orb?.setText("“$text”\n\n$full")
                    flush(false)
                },
                onDone = {
                    if (myGen != gen) return@handleStream
                    flush(true)
                    if (full.isNotBlank()) ConversationManager.recordAnswer(context, full.toString())
                    Log.i(TAG, "agent completed")
                    try { Thread.sleep(3500) } catch (_: Exception) {}
                    if (myGen != gen) return@handleStream
                    // ponytail: "tts completed" logged here, not at real queue end
                    Log.i(TAG, "tts completed; wake gate resumed")
                    StrikeVoiceController.notifyIdle()
                    main.post { if (myGen == gen) hide() }
                })
        }, "strike-session-answer").start()
    }

    @Volatile
    private var ttsReady = false

    private fun ensureApi(): StrikeApi {
        if (tts == null) tts = TextToSpeech(context) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }
        // speak() before onInit completes = silent first answer; wait (bg thread)
        var guard = 0
        while (!ttsReady && tts != null && guard++ < 50) Thread.sleep(100)
        if (api == null) api = StrikeApi(context.applicationContext, tts)
        return api!!
    }
}
