package com.heystrike.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The correct wake-word architecture (ONE capture, cheap gate, expensive work only on wake):
 *
 * App starts -> ONE long-running AudioRecord (16kHz PCM)
 *   -> tiny-grammar Vosk gate ["hey strike","[unk]"] (cheap, runs always)
 *   -> "hey strike"? NO  -> discard audio, keep listening
 *   -> "hey strike"? YES -> beep + orb UI
 *        -> full-grammar command capture from the SAME stream until endpoint/timeout
 *        -> stop command recording -> LLM/agent -> back to gate
 *
 * Never start/stop the recorder in a loop. Never cloud STT for the gate.
 */
class GateEngine(
    private val modelDir: String,
    private val onWake: () -> Unit,
    private val onCommand: (text: String) -> Unit,
    private val onError: (msg: String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audio: AudioRecord? = null
    private var model: Model? = null
    private var gate: Recognizer? = null
    // Glitch guard: own speaker output re-entering the mic must not re-wake us
    @Volatile private var cooldownUntil = 0L

    fun start() {
        if (running.getAndSet(true)) return
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
        try { audio?.stop() } catch (_: Exception) {}
        try { audio?.release() } catch (_: Exception) {}
        audio = null
        try { gate?.close() } catch (_: Exception) {}
        gate = null
        try { model?.close() } catch (_: Exception) {}
        model = null
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
            // P3: bigger chunks + parse every 2nd frame = ~half the Vosk CPU.
            val buf = ByteArray(8192)
            var frame = 0
            while (running.get()) {
                val n = audio?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) continue
                val rec = gate ?: continue
                rec.acceptWaveForm(buf, n)
                frame++
                if (frame % 2 != 0) continue
                val partial = try {
                    JSONObject(rec.partialResult).optString("partial", "")
                } catch (_: Exception) { "" }.lowercase()
                if ("hey strike" in partial || "hey str" in partial) {
                    if (System.currentTimeMillis() < cooldownUntil) continue
                    doCommand()
                    cooldownUntil = System.currentTimeMillis() + 4000
                    // fresh gate after command (recognizer state consumed)
                    try { gate?.close() } catch (_: Exception) {}
                    val m = model ?: break
                    gate = Recognizer(m, 16000f, "[\"hey strike\", \"[unk]\"]")
                }
            }
        } catch (e: Exception) {
            Log.e("GateEngine", "gate failed", e)
            if (running.get()) onError(e.message ?: "gate error")
        } finally {
            closeAll()
        }
    }

    /** Beep + orb, then capture ONE command from the same stream. */
    private fun doCommand() {
        try {
            ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
                .startTone(ToneGenerator.TONE_PROP_BEEP)
        } catch (_: Exception) {}
        onWake()
        var cmd: Recognizer? = null
        try {
            val m = model ?: return
            cmd = Recognizer(m, 16000f)
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 9000
            var heard = ""
            while (running.get() && System.currentTimeMillis() < deadline) {
                val n = audio?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) continue
                if (cmd.acceptWaveForm(buf, n)) break // endpoint detected
            }
            heard = try {
                JSONObject(cmd.result).optString("text", "")
            } catch (_: Exception) { "" }.trim()
            if (heard.length > 1) onCommand(heard)
        } catch (e: Exception) {
            Log.e("GateEngine", "command failed", e)
        } finally {
            try { cmd?.close() } catch (_: Exception) {}
        }
    }
}
