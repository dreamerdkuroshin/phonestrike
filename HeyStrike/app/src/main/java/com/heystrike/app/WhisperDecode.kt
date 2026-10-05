package com.heystrike.app

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline whisper-tiny multilingual decode (Gujarati/Hindi/English) via the
 * existing sherpa-onnx runtime. Utterance-at-once: the gate buffers command
 * audio (same endpointing as streaming), then decodes once. Slower than
 * streaming zipformer (~seconds on F23-class CPU) but actually multilingual.
 * Loaded once per process; UNVERIFIED on-device as of wiring.
 */
object WhisperDecode {

    @Volatile
    private var rec: OfflineRecognizer? = null
    private var loadedDir: String? = null
    private var loadedLang: String? = null
    private val lock = Any()

    fun pcmToFloats(buf: ByteArray, n: Int): FloatArray {
        val count = n / 2
        val out = FloatArray(count)
        val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out[i] = bb.short / 32768.0f
        return out
    }

    private fun load(dirPath: String, assets: AssetManager?, language: String): OfflineRecognizer? =
        synchronized(lock) {
            if (rec != null && loadedDir == dirPath && loadedLang == language) return rec
            if (rec != null) release()
        val dir = File(dirPath)
        val enc = File(dir, "tiny-encoder.int8.onnx")
        val dec = File(dir, "tiny-decoder.int8.onnx")
        val tok = File(dir, "tiny-tokens.txt")
        val am = assets ?: return null
        if (!enc.isFile || !dec.isFile || !tok.isFile) return null
        return try {
            val mc = OfflineModelConfig().apply {
                whisper = OfflineWhisperModelConfig(
                    enc.absolutePath,
                    dec.absolutePath,
                    // pref hint ("gu"/"hi"/"en"); "" would auto-detect
                    // (slower, less reliable on short commands)
                    language,
                    "transcribe",
                    300
                )
                tokens = tok.absolutePath
                provider = "cpu"
                numThreads = 2
                debug = false
            }
            val config = OfflineRecognizerConfig().apply {
                modelConfig = mc
                decodingMethod = "greedy_search"
            }
            OfflineRecognizer(am, config).also {
                rec = it
                loadedDir = dirPath
                loadedLang = language
                Log.i(StrikeVoiceController.TAG, "whisper-tiny offline loaded ($language)")
            }
        } catch (t: Throwable) {
            Log.e(StrikeVoiceController.TAG, "whisper load failed", t)
            null
        }
    }

    /**
     * Decode one buffered utterance. Null = unavailable/failed (caller falls
     * back). Language hint ("gu"/"hi"/"en") steers the multilingual decoder;
     * "" would auto-detect (slower) — prefer the known pref.
     */
    fun decode(whisperDirPath: String, assets: AssetManager?, pcm: ByteArray, language: String): String? {
        if (pcm.size < 3200) return null // <0.1s: nothing to decode
        // language is fixed at load (config); reload if it changed
        val r = load(whisperDirPath, assets, language) ?: return null
        return try {
            val stream = r.createStream()
            try {
                // whisper wants the whole utterance; 30s cap keeps RAM bounded
                val cap = minOf(pcm.size, 16000 * 2 * 30)
                stream.acceptWaveform(pcmToFloats(pcm, cap), 16000)
                r.decode(stream)
                r.getResult(stream).text.orEmpty().trim().ifBlank { null }
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.e(StrikeVoiceController.TAG, "whisper decode failed", t)
            null
        }
    }

    fun release() = synchronized(lock) {
        try { rec?.release() } catch (_: Throwable) {}
        rec = null
        loadedDir = null
    }
}
