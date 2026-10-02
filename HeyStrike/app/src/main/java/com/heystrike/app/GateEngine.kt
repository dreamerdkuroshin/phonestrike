package com.heystrike.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * ONE capture thread, two command-ASR backends behind the same state machine:
 *
 * App starts -> ONE long-running AudioRecord (16kHz PCM, VOICE_RECOGNITION —
 *   the device applies its own speech AEC/NS/AGC for this source; we do NOT
 *   stack AudioEffect on top, that double-processes audio)
 *   -> tiny-grammar Vosk gate ["hey strike","[unk]"] (cheap, always-on)
 *   -> ring buffer keeps ~0.9s of audio at all times
 *   -> wake detected (or barge-in while ASSISTANT_SPEAKING)
 *        -> FULL command ASR fed the ring-buffer TAIL first, then live audio:
 *             primary: sherpa-onnx streaming Zipformer 20M int8 (if downloaded)
 *             fallback: Vosk full-grammar recognizer
 *        -> endpoint state machine: WAITING -> USER_SPEAKING ->
 *           POSSIBLE_END (grace) -> FINALIZED (never first endpoint alone)
 *        -> final transcript -> controller (normalize) -> LLM/agent -> gate
 *
 * Never start/stop the recorder in a loop. Never cloud STT.
 */
class GateEngine(
    private val modelDir: String,
    private val sherpaDir: String?,
    private val assets: android.content.res.AssetManager? = null,
    private val onReady: () -> Unit = {},
    private val onWake: () -> Unit,
    private val onSpeechStart: () -> Unit = {},
    private val onEndpoint: () -> Unit = {},
    private val onPartial: (text: String) -> Unit = {},
    private val onCommand: (text: String) -> Unit,
    private val onBargeIn: () -> Unit = {},
    private val onError: (msg: String) -> Unit = {}
) {
    private enum class Phase { WAITING, SPEAKING, POSSIBLE_END, FINALIZED }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audio: AudioRecord? = null
    private var model: Model? = null
    private var gate: Recognizer? = null
    // Glitch guard: own speaker output re-entering the mic must not re-wake us
    @Volatile private var cooldownUntil = 0L
    // Gesture/assist path: session asked for a one-shot command capture
    @Volatile private var commandRequested = false

    // sherpa-onnx streaming command ASR (loaded once if model files exist)
    @Volatile private var sherpa: OnlineRecognizer? = null
    @Volatile private var sherpaTried = false

    // ~0.9s of 16kHz 16-bit mono = 28800 bytes of rolling audio (wake tail replay)
    private val ring = Ring(28800)

    /** Capture ONE command from the live stream (same AudioRecord, never a second mic). */
    fun requestCommand() { commandRequested = true }

    fun start() {
        if (running.getAndSet(true)) return
        // Prewarm sherpa OFF the hot path: a class-init/native failure here is
        // caught (Throwable) and the command stage simply stays on Vosk —
        // never a silent gate death on the first command after download.
        Thread({
            try {
                if (!sherpaFilesPresent()) return@Thread
                if (loadSherpa() != null) {
                    Log.i(StrikeVoiceController.TAG, "sherpa prewarm complete")
                }
            } catch (t: Throwable) {
                Log.e(StrikeVoiceController.TAG, "sherpa prewarm failed — staying on Vosk", t)
                try { sherpa?.release() } catch (_: Throwable) {}
                sherpa = null
                sherpaTried = true
            }
        }, "strike-sherpa-prewarm").apply { isDaemon = true; start() }
        thread = Thread({ loop() }, "strike-gate").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try { thread?.join(2000) } catch (_: Exception) {}
        closeAll()
    }

    private fun closeAll() {
        try { audio?.stop() } catch (_: Throwable) {}
        try { audio?.release() } catch (_: Throwable) {}
        audio = null
        try { gate?.close() } catch (_: Throwable) {}
        gate = null
        try { model?.close() } catch (_: Throwable) {}
        model = null
        try { sherpa?.release() } catch (_: Throwable) {}
        sherpa = null
    }

    private fun loop() {
        try {
            model = Model(modelDir)
            gate = Recognizer(model, 16000f, "[\"hey strike\", \"[unk]\"]")
            val min = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 2
            )
            if (audio?.state != AudioRecord.STATE_INITIALIZED) {
                onError("Mic init failed")
                running.set(false)
                return
            }
            audio?.startRecording()
            onReady()
            // 4096 bytes = 128ms/frame; wake partials checked EVERY frame
            val buf = ByteArray(4096)
            // barge-in adaptive echo floor (TTS speaker -> mic)
            var echoBase = 0.02
            var echoFrames = 0
            var barFrames = 0
            while (running.get()) {
                val n = audio?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) {
                    if (n < 0) Thread.sleep(50)
                    continue
                }
                ring.push(buf, n)
                val rec = gate ?: continue
                rec.acceptWaveForm(buf, n)
                val partial = try {
                    JSONObject(rec.partialResult).optString("partial", "")
                } catch (_: Exception) { "" }.lowercase()
                val wake = "hey strike" in partial || "hey str" in partial

                // ---- barge-in: user speaks while Strike is answering ----
                val speakingNow =
                    StrikeVoiceController.state == StrikeVoiceController.State.ASSISTANT_SPEAKING
                var barge = false
                if (speakingNow) {
                    val rms = rms(buf, n)
                    if (echoFrames < 12) {
                        // first ~1.5s of playback = echo profile (adaptive floor)
                        echoBase = if (echoFrames == 0) rms else echoBase * 0.8 + rms * 0.2
                        echoFrames++
                    } else if (rms > max(echoBase * 3.5, 0.045)) {
                        barFrames++
                        if (barFrames >= 3) barge = true // ~384ms sustained
                    } else barFrames = 0
                } else {
                    echoFrames = 0
                    barFrames = 0
                }

                if (speakingNow && (wake || barge)) {
                    Log.i(StrikeVoiceController.TAG,
                        "barge-in (wake=$wake energy=$barge) — stopping TTS, capturing turn")
                    commandRequested = false
                    barFrames = 0
                    echoFrames = 0
                    StrikeVoiceController.noteWake()
                    onBargeIn()
                    val tail = ring.tail()
                    doCommand(tail, beep = false)
                    cooldownUntil = System.currentTimeMillis() + 4000
                    try { gate?.close() } catch (_: Exception) {}
                    val m = model ?: break
                    gate = Recognizer(m, 16000f, "[\"hey strike\", \"[unk]\"]")
                    continue
                }

                val inCooldown = System.currentTimeMillis() < cooldownUntil
                if (wake && inCooldown) continue
                if (!wake && commandRequested && inCooldown) continue
                if (wake || commandRequested) {
                    commandRequested = false
                    // snapshot the tail BEFORE live reading resumes
                    val tail = ring.tail()
                    doCommand(tail)
                    cooldownUntil = System.currentTimeMillis() + 4000
                    // fresh gate after command (recognizer state consumed)
                    try { gate?.close() } catch (_: Exception) {}
                    val m = model ?: break
                    gate = Recognizer(m, 16000f, "[\"hey strike\", \"[unk]\"]")
                }
            }
        } catch (t: Throwable) {
            // Errors (UnsatisfiedLinkError, ExceptionInInitializerError, ...)
            // must NOT kill the gate thread silently — route to onError so the
            // controller retries instead of the mic going permanently dead.
            Log.e(StrikeVoiceController.TAG, "gate failed (Throwable)", t)
            if (running.get()) onError((t as? Exception)?.message ?: t.toString())
        } finally {
            closeAll()
        }
    }

    /**
     * Beep + orb, then capture ONE command from the same stream:
     * replay the ring-buffer tail (wake word + first words of the command),
     * then continue with live mic audio. Endpointing with a grace state
     * machine — a single endpoint signal does NOT end the command.
     */
    private fun doCommand(tail: ByteArray, beep: Boolean = true) {
        if (beep) {
            try {
                ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
                    .startTone(ToneGenerator.TONE_PROP_BEEP)
            } catch (_: Exception) {}
        }
        onWake()
        if (!trySherpaCommand(tail)) voskCommand(tail)
        ring.clear()
    }

    // ------------------------------------------------------------------
    // Primary command ASR: sherpa-onnx streaming Zipformer 20M int8
    // ------------------------------------------------------------------
    private fun trySherpaCommand(tail: ByteArray): Boolean {
        val rec = loadSherpa() ?: return false
        return try {
            val stream = rec.createStream()
            try {
                if (tail.isNotEmpty()) {
                    stream.acceptWaveform(bytesToFloats(tail, tail.size), 16000)
                    decodeAll(rec, stream)
                }
                StrikeVoiceController.noteCommandListening()

                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 15_000
                var phase = Phase.WAITING
                var speechStarted = false
                var lastPartial = ""
                var graceUntil = 0L

                while (running.get() && System.currentTimeMillis() < deadline) {
                    val n = audio?.read(buf, 0, buf.size) ?: -1
                    if (n <= 0) {
                        if (n < 0) Thread.sleep(50)
                        continue
                    }
                    stream.acceptWaveform(bytesToFloats(buf, n), 16000)
                    decodeAll(rec, stream)
                    val p = rec.getResult(stream).text.orEmpty().trim()
                    val endpointed = rec.isEndpoint(stream)

                    when {
                        phase == Phase.POSSIBLE_END -> {
                            if (p.isNotEmpty() && p != lastPartial) {
                                lastPartial = p
                                phase = Phase.SPEAKING
                                onSpeechStart()
                                onPartial(p)
                            } else if (System.currentTimeMillis() >= graceUntil) {
                                phase = Phase.FINALIZED
                            }
                        }
                        else -> { // WAITING or SPEAKING
                            if (p.isNotEmpty() && p != lastPartial) {
                                lastPartial = p
                                onPartial(p)
                            }
                            if (p.isNotEmpty() && !speechStarted) {
                                speechStarted = true
                                phase = Phase.SPEAKING
                                onSpeechStart()
                            }
                            if (endpointed && speechStarted) {
                                onEndpoint()
                                phase = Phase.POSSIBLE_END
                                // 1600ms like Vosk: mid-sentence thinking pauses
                                // ("Open WhatsApp … and message…") must not truncate
                                graceUntil = System.currentTimeMillis() + 1600
                            }
                        }
                    }
                    if (phase == Phase.FINALIZED) break
                }

                // trailing padding so the last words fully decode
                stream.acceptWaveform(FloatArray((0.3 * 16000).toInt()), 16000)
                decodeAll(rec, stream)
                StrikeVoiceController.noteFinalized()
                val fin = rec.getResult(stream).text.orEmpty().trim()
                onCommand(fin)
            } finally {
                stream.release()
            }
            true
        } catch (t: Throwable) {
            Log.e(StrikeVoiceController.TAG, "sherpa command failed — falling back to Vosk", t)
            try { sherpa?.release() } catch (_: Throwable) {}
            sherpa = null
            sherpaTried = true
            false
        }
    }

    private fun sherpaFilesPresent(): Boolean {
        val dir = sherpaDir ?: return false
        if (dir.isEmpty()) return false
        return File(dir, "encoder-epoch-99-avg-1.int8.onnx").isFile &&
            File(dir, "decoder-epoch-99-avg-1.onnx").isFile &&
            File(dir, "joiner-epoch-99-avg-1.int8.onnx").isFile &&
            File(dir, "tokens.txt").isFile
    }

    private fun loadSherpa(): OnlineRecognizer? {
        sherpa?.let { return it }
        if (sherpaTried) return null
        val dir = sherpaDir
        if (dir.isNullOrEmpty()) return null
        val enc = File(dir, "encoder-epoch-99-avg-1.int8.onnx")
        val dec = File(dir, "decoder-epoch-99-avg-1.onnx")
        val joi = File(dir, "joiner-epoch-99-avg-1.int8.onnx")
        val tok = File(dir, "tokens.txt")
        if (!enc.isFile || !dec.isFile || !joi.isFile || !tok.isFile) return null
        val am = assets ?: return null
        sherpaTried = true
        return try {
            // 1.13.8 API: data classes with defaults, no builders; the Android
            // constructor takes an AssetManager (native lib loads via clinit).
            val encCfg = OnlineTransducerModelConfig().apply {
                encoder = enc.absolutePath
                decoder = dec.absolutePath
                joiner = joi.absolutePath
            }
            val mc = OnlineModelConfig().apply {
                transducer = encCfg
                tokens = tok.absolutePath
                modelType = "zipformer"
                numThreads = 2
                debug = false
            }
            // rule1: 2.4s bare silence; rule2: 1.0s trailing silence WITH
            // speech (+0.4s app grace = 1.4s total); rule3: 15s utterance cap
            val endpoint = EndpointConfig(
                EndpointRule(false, 2.4f, 0f),
                EndpointRule(true, 1.0f, 0f),
                EndpointRule(false, 0f, 15f)
            )
            val config = OnlineRecognizerConfig().apply {
                modelConfig = mc
                endpointConfig = endpoint
                enableEndpoint = true
            }
            val r = OnlineRecognizer(am, config)
            sherpa = r
            Log.i(StrikeVoiceController.TAG, "sherpa streaming Zipformer 20M int8 loaded (VmRSS=${vmRssKb()}KB)")
            r
        } catch (t: Throwable) {
            Log.e(StrikeVoiceController.TAG, "sherpa load failed — using Vosk command stage", t)
            null
        }
    }

    private fun decodeAll(rec: OnlineRecognizer, stream: OnlineStream) {
        var guard = 0
        while (rec.isReady(stream) && guard++ < 500) rec.decode(stream)
    }

    // ------------------------------------------------------------------
    // Fallback command ASR: Vosk full-grammar recognizer
    // ------------------------------------------------------------------
    private fun voskCommand(tail: ByteArray) {
        var cmd: Recognizer? = null
        try {
            val m = model ?: return
            cmd = Recognizer(m, 16000f)
            // t1=5s max silence-to-finalize chain, t2=1.2s endpoint silence,
            // t3=15s hard cap (safety only — endpointing decides, not a timer)
            cmd.setEndpointerDelays(5.0f, 1.2f, 15.0f)
            // replay recent audio: words immediately after "hey strike"
            // would otherwise be swallowed together with the wake recognizer
            if (tail.isNotEmpty()) cmd.acceptWaveForm(tail, tail.size)
            StrikeVoiceController.noteCommandListening()

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 15_000
            val segs = mutableListOf<String>()
            var phase = Phase.WAITING
            var speechStarted = false
            var lastPartial = ""
            var graceUntil = 0L

            while (running.get() && System.currentTimeMillis() < deadline) {
                val n = audio?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) {
                    if (n < 0) Thread.sleep(50)
                    continue
                }
                val endpointed = cmd.acceptWaveForm(buf, n)
                val p = try {
                    JSONObject(cmd.partialResult).optString("partial", "")
                } catch (_: Exception) { "" }.trim()

                when {
                    phase == Phase.POSSIBLE_END -> {
                        // speech resumed after a short pause -> keep listening
                        if (p.isNotEmpty() && p != lastPartial) {
                            lastPartial = p
                            phase = Phase.SPEAKING
                            onSpeechStart()
                            onPartial(p)
                        } else if (endpointed || System.currentTimeMillis() >= graceUntil) {
                            phase = Phase.FINALIZED
                        }
                    }
                    else -> { // WAITING or SPEAKING
                        if (p.isNotEmpty() && p != lastPartial) {
                            lastPartial = p
                            onPartial(p)
                        }
                        if (p.isNotEmpty() && !speechStarted) {
                            speechStarted = true
                            phase = Phase.SPEAKING
                            onSpeechStart()
                        }
                        if (endpointed) {
                            if (speechStarted) {
                                val s = textOf(cmd.result)
                                if (s.isNotEmpty()) segs.add(s)
                                onEndpoint()
                                phase = Phase.POSSIBLE_END
                                graceUntil = System.currentTimeMillis() + 1600
                            }
                            // endpoint with no speech (noise): stay WAITING
                        }
                    }
                }
                if (phase == Phase.FINALIZED) break
            }

            StrikeVoiceController.noteFinalized()
            val fin = textOf(cmd.finalResult)
            if (fin.isNotEmpty() && fin != segs.lastOrNull()) segs.add(fin)
            onCommand(segs.joinToString(" ").trim())
        } catch (e: Exception) {
            Log.e(StrikeVoiceController.TAG, "command failed", e)
            onCommand("")
        } finally {
            try { cmd?.close() } catch (_: Exception) {}
        }
    }

    private fun textOf(json: String): String = try {
        JSONObject(json).optString("text", "").trim()
    } catch (_: Exception) { "" }

    private fun bytesToFloats(b: ByteArray, n: Int): FloatArray {
        val out = FloatArray(n / 2)
        var i = 0
        var o = 0
        while (i + 1 < n) {
            val s = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort()
            out[o] = s / 32768.0f
            i += 2
            o++
        }
        return out
    }

    /** RMS of 16-bit PCM (0..1) for the barge-in energy gate. */
    private fun rms(b: ByteArray, n: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s * s).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0.0
        return kotlin.math.sqrt(sum / count) / 32768.0
    }

    private fun vmRssKb(): Long = try {
        java.io.File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: -1
    } catch (_: Exception) { -1 }

    /** Simple fixed-capacity rolling PCM ring (gate thread only). */
    private class Ring(private val cap: Int) {
        private val a = ByteArray(cap)
        private var w = 0L

        fun push(b: ByteArray, n: Int) {
            var i = 0
            while (i < n) {
                a[(w % cap).toInt()] = b[i]
                w++
                i++
            }
        }

        /** Last min(w, cap) bytes in chronological order. */
        fun tail(): ByteArray {
            val len = minOf(w, cap.toLong()).toInt()
            val out = ByteArray(len)
            val start = w - len
            for (i in 0 until len) out[i] = a[((start + i) % cap).toInt()]
            return out
        }

        fun clear() { w = 0 }
    }
}
