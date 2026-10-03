package com.heystrike.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Single owner of the ONE wake-word GateEngine.
 *
 * AssistantService (system-assistant mode) and VoiceService (standalone mode)
 * both acquire/release the gate here, so the app can never run two engines,
 * two AudioRecords, or two Vosk recognizers. Starting twice is a no-op,
 * stopping twice is safe, and releasing one owner never kills the other
 * owner's listener.
 *
 * States: IDLE -> WAKE_DETECTED -> LISTENING -> USER_SPEAKING ->
 * POSSIBLE_END -> PROCESSING -> ASSISTANT_SPEAKING -> INTERRUPTED ->
 * COOLDOWN -> IDLE / ERROR. Exactly one command can be active (the gate
 * thread is sequential by construction and requestCapture only fires from IDLE).
 */
object StrikeVoiceController {

    enum class State {
        IDLE, WAKE_DETECTED, LISTENING, USER_SPEAKING, POSSIBLE_END,
        PROCESSING, ASSISTANT_SPEAKING, INTERRUPTED, COOLDOWN, ERROR
    }

    const val TAG = "HeyStrikeAssistant"
    const val OWNER_ASSISTANT = "assistant"
    const val OWNER_VOICE = "voice"
    private const val COOLDOWN_MS = 4000L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    private var gate: GateEngine? = null
    private var owner: String? = null
    private var started = false // an owner holds the controller (survives the ERROR retry window)
    private var retries = 0
    private var wakeShown = false // gate already woke -> session must not re-trigger capture
    private var restartRunnable: Runnable? = null

    private var onWakeCb: (() -> Unit)? = null
    private var onCommandCb: ((String) -> Unit)? = null
    private var onPartialCb: ((String) -> Unit)? = null
    private var onInterruptCb: (() -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null
    private var modelPath = ""
    private var sherpaPath: String? = null
    private var appCtx: Context? = null

    // Latency stamps (uptime ms): T0 wake, T1 command listening, T2 finalized,
    // T3 final transcript, T4 first LLM token, T5 first TTS.
    @Volatile private var t0 = 0L
    @Volatile private var t1 = 0L
    @Volatile private var t2 = 0L
    @Volatile private var t3 = 0L
    @Volatile private var t4 = 0L
    // per-command trace id (spec 21: one id ties speech -> STT -> plan -> tools)
    @Volatile private var traceSeq = 0

    @Volatile
    var state: State = State.IDLE
        private set

    /** UI listens for state changes (always delivered on the main thread). */
    private val stateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    fun addStateListener(l: (State) -> Unit) { stateListeners.add(l) }
    fun removeStateListener(l: (State) -> Unit) { stateListeners.remove(l) }

    fun isRunning(): Boolean = synchronized(lock) { gate != null }

    /** Last gate failure (cleared when the gate reports ready again). */
    @Volatile
    var lastError: String? = null
        private set

    fun currentOwner(): String? = synchronized(lock) { owner }

    fun stateName(): String = state.name

    fun startWakeWord(
        ownerName: String,
        ctx: Context,
        onWake: () -> Unit,
        onCommand: (text: String) -> Unit,
        onError: (msg: String) -> Unit = {},
        onPartial: (text: String) -> Unit = {},
        onInterrupt: () -> Unit = {}
    ): Boolean = synchronized(lock) {
        if (gate != null) {
            Log.i(TAG, "startWakeWord($ownerName) ignored — already active (owner=$owner)")
            return false
        }
        if (started) {
            // Dead session (retry budget exhausted, gate=null, started=true):
            // without this revival every future start is refused forever.
            Log.w(TAG, "startWakeWord($ownerName) reviving dead session (owner=$owner)")
            restartRunnable?.let { main.removeCallbacks(it) }
            restartRunnable = null
            started = false
            owner = null
            retries = 0
        }
        owner = ownerName
        started = true
        retries = 0
        wakeShown = false
        // Hindi mode: Vosk-hi does wake (constrained grammar) + commands;
        // the English sherpa command model would garbage-decode Hindi, so
        // sherpaPath=null falls the command stage back to Vosk-hi.
        val hi = Prefs.voiceLang(ctx.applicationContext) == "hi" &&
            ModelManager.hiReady(ctx.applicationContext)
        modelPath = (if (hi) ModelManager.hiDir(ctx.applicationContext)
        else ModelManager.dir(ctx.applicationContext)).absolutePath
        sherpaPath = if (hi) null
        else ModelManager.sherpaDir(ctx.applicationContext).absolutePath
        appCtx = ctx.applicationContext
        onWakeCb = onWake
        onCommandCb = onCommand
        onErrorCb = onError
        onPartialCb = onPartial
        onInterruptCb = onInterrupt
        setStateInternal(State.IDLE)
        Log.i(TAG, "wake gate starting (owner=$ownerName)")
        val g = newGate()
        gate = g
        g.start()
        true
    }

    fun stopWakeWord(ownerName: String): Boolean {
        val r: Runnable?
        val g: GateEngine?
        synchronized(lock) {
            if (!started) {
                Log.i(TAG, "stopWakeWord($ownerName): already stopped")
                return false
            }
            if (owner != ownerName) {
                Log.i(TAG, "stopWakeWord($ownerName) refused — owner=$owner")
                return false
            }
            started = false
            g = gate
            gate = null
            owner = null
            wakeShown = false
            r = restartRunnable
            restartRunnable = null
            setStateInternal(State.IDLE)
            r?.let { main.removeCallbacks(it) }
            // gate.stop() joins the gate thread (bounded 2s). Held under the
            // lock on purpose: a concurrent startWakeWord must wait so we can
            // never have two engines on the mic. Callbacks guard on `started`,
            // so a gate thread blocked here finishes as a no-op.
            g?.stop()
        }
        Log.i(TAG, "wake gate stopped (owner=$ownerName)")
        return true
    }

    /** Session/gesture path: one-shot command capture on the live stream. */
    fun requestCapture() {
        synchronized(lock) {
            when (state) {
                State.IDLE, State.COOLDOWN, State.INTERRUPTED -> {}
                State.PROCESSING, State.ASSISTANT_SPEAKING -> {
                    // explicit gesture (mic/assist key) beats the current answer
                    setStateInternal(State.INTERRUPTED)
                    onInterruptCb?.let { main.post { it() } }
                }
                else -> {
                    Log.i(TAG, "capture request refused — one command at a time (state=$state)")
                    return
                }
            }
            gate?.requestCommand()
            Log.i(TAG, "session requested command capture (state=$state)")
        }
    }

    /** true = the wake (not the session) initiated the show; session skips re-capture. */
    fun consumeWakeShown(): Boolean = synchronized(lock) {
        val w = wakeShown
        wakeShown = false
        w
    }

    fun notifySpeaking() {
        synchronized(lock) {
            if (gate != null && state == State.PROCESSING) {
                setStateInternal(State.ASSISTANT_SPEAKING)
                if (t3 > 0) {
                    val now = SystemClock.uptimeMillis()
                    Log.i(TAG, "T5 first TTS audio (+${now - t3}ms after T3" +
                        if (t4 > 0) ", +${now - t4}ms after T4)" else ")")
                }
            }
        }
    }

    /** Barge-in: user spoke while we were answering — stop TTS, take the turn. */
    fun notifyInterrupted() {
        synchronized(lock) {
            if (state == State.PROCESSING || state == State.ASSISTANT_SPEAKING) {
                setStateInternal(State.INTERRUPTED)
                Log.i(TAG, "assistant interrupted by user speech")
                onInterruptCb?.let { main.post { it() } }
            }
        }
    }

    fun notifyIdle() {
        synchronized(lock) {
            if (gate != null && (state == State.PROCESSING || state == State.ASSISTANT_SPEAKING)) {
                enterCooldownLocked()
            }
        }
    }

    // ---- latency instrumentation (T0..T5) ----
    fun noteWake() {
        t0 = SystemClock.uptimeMillis()
        t1 = 0; t2 = 0; t3 = 0; t4 = 0
        Log.i(TAG, "T0 wake detected")
    }

    fun noteCommandListening() {
        t1 = SystemClock.uptimeMillis()
        Log.i(TAG, "T1 command listening started (${if (t0 > 0) t1 - t0 else -1}ms after wake)")
    }

    fun noteFinalized() {
        t2 = SystemClock.uptimeMillis()
        Log.i(TAG, "T2 speech finalized (${if (t1 > 0) t2 - t1 else -1}ms listening)")
    }

    fun noteFirstToken() {
        if (t3 <= 0 || t4 > 0) return
        t4 = SystemClock.uptimeMillis()
        Log.i(TAG, "T4 first LLM token (+${t4 - t3}ms after T3)")
    }

    private fun newGate() = GateEngine(
        modelDir = modelPath,
        sherpaDir = sherpaPath,
        // Hindi model decodes the English wake as Devanagari ("हे स्ट्राइक",
        // seen verbatim on-device) — constrain to the native phrase there.
        wakeWords = if (modelPath.contains("small-hi")) listOf("हे स्ट्राइक")
        else listOf("hey strike"),
        assets = appCtx?.assets,
        onReady = {
            synchronized(lock) {
                if (!started) return@synchronized
                retries = 0
                lastError = null
                Log.i(TAG, "Vosk ready")
                Log.i(TAG, "AudioRecord ready")
                setStateInternal(State.IDLE)
                Log.i(TAG, "listening")
            }
        },
        onWake = {
            synchronized(lock) {
                if (!started) return@synchronized
                wakeShown = true
                noteWake()
                setStateInternal(State.WAKE_DETECTED)
                onWakeCb?.invoke()
                setStateInternal(State.LISTENING)
                Log.i(TAG, "listening for command")
            }
        },
        onSpeechStart = {
            synchronized(lock) {
                if (!started) return@synchronized
                setStateInternal(State.USER_SPEAKING)
            }
        },
        onEndpoint = {
            synchronized(lock) {
                if (!started) return@synchronized
                setStateInternal(State.POSSIBLE_END)
            }
        },
        onBargeIn = { notifyInterrupted() },
        onPartial = { text -> onPartialCb?.invoke(text) },
        onCommand = { raw ->
            synchronized(lock) {
                if (!started) return@synchronized
                val text = CommandNormalizer.normalize(raw)
                if (text.isBlank()) {
                    Log.i(TAG, "no speech captured — back to listening")
                    enterCooldownLocked()
                    // blank command left the orb/session on screen forever (all
                    // buttons untouchable) — dismiss both explicitly
                    appCtx?.let { c -> main.post { OverlayService.hideNow(c) } }
                    main.post { AssistantSessionService.active?.hide() }
                    return@synchronized
                }
                t3 = SystemClock.uptimeMillis()
                val trace = ++traceSeq
                setStateInternal(State.PROCESSING)
                Log.i(TAG, "TRACE $trace | T3 final transcript (+${t3 - t0}ms after T0)")
                Log.i(TAG, "TRACE $trace | command: \"$text\"")
                onCommandCb?.invoke(text)
            }
        },
        onError = { msg -> handleGateError(msg) }
    )

    private fun enterCooldownLocked() {
        setStateInternal(State.COOLDOWN)
        Log.i(TAG, "wake gate resumed (cooldown)")
        main.postDelayed({
            synchronized(lock) {
                if (started && state == State.COOLDOWN) setStateInternal(State.IDLE)
            }
        }, COOLDOWN_MS)
        // command window closed — T3/T4 no longer meaningful for out-of-band calls
        t3 = 0; t4 = 0
    }

    private fun handleGateError(msg: String) {
        val cb: ((String) -> Unit)?
        synchronized(lock) {
            // The engine self-cleans in its finally block; drop the reference.
            gate = null
            lastError = msg
            setStateInternal(State.ERROR)
            Log.e(TAG, "wake gate error: $msg")
            cb = onErrorCb
            if (started && retries < 3) {
                retries++
                Log.i(TAG, "controlled retry $retries/3 in 5s")
                val r = Runnable { retryGate() }
                restartRunnable = r
                main.postDelayed(r, 5000)
            } else if (started) {
                Log.e(TAG, "retry budget exhausted — staying in ERROR until service restart")
            } else {
                // stopped meanwhile — nothing to retry
            }
        }
        cb?.invoke(msg)
    }

    private fun retryGate() {
        synchronized(lock) {
            restartRunnable = null
            if (!started) {
                Log.i(TAG, "retry cancelled — controller stopped")
                return
            }
            if (gate != null) return
            setStateInternal(State.IDLE)
            Log.i(TAG, "wake gate retry starting (owner=$owner)")
            val g = newGate()
            gate = g
            g.start()
        }
    }

    private fun setStateInternal(s: State) {
        if (state == s) return
        state = s
        Log.i(TAG, "state -> $s")
        if (stateListeners.isEmpty()) return
        val snapshot = stateListeners.toList()
        main.post { snapshot.forEach { it(s) } }
    }
}
