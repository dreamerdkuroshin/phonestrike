package com.heystrike.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.withLock

/**
 * One-time 40MB offline voice model download.
 * vosk-model-small-en-us-0.15 (Apache 2.0) -> filesDir/models/small-en
 */
object ModelManager {
    const val URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun dir(c: Context): File = File(c.filesDir, "models/small-en")

    /**
     * Deep readiness: a corrupt/truncated model file kills Vosk NATIVELY
     * (segfault — no Java stack, instant process death, clear-data "fixes"
     * it). Existence alone is not enough; required files must have sane
     * sizes. Failure here routes to re-download, never to the gate.
     * Graph layout differs per model: small-en ships one HCLG.fst,
     * small-hi ships the Gr.fst + HCLr.fst pair (no HCLG.fst at all —
     * demanding it made Hindi downloads fail verification forever).
     */
    private val VOSK_COMMON = mapOf(
        "am/final.mdl" to 100_000L,
        "conf/model.conf" to 10L
    )
    private val VOSK_GRAPH_EN = mapOf("graph/HCLG.fst" to 100_000L)
    private val VOSK_GRAPH_HI = mapOf(
        "graph/Gr.fst" to 100_000L,
        "graph/HCLr.fst" to 100_000L
    )
    private val SHERPA_MIN = mapOf(
        "encoder-epoch-99-avg-1.int8.onnx" to 5_000_000L,
        "decoder-epoch-99-avg-1.onnx" to 100_000L,
        "joiner-epoch-99-avg-1.int8.onnx" to 100_000L,
        "tokens.txt" to 1_000L
    )

    /** Pure file check — JVM-tested. */
    fun verifyFiles(dir: File, required: Map<String, Long>): Boolean {
        for ((rel, min) in required) {
            val f = File(dir, rel)
            if (!f.isFile || f.length() < min) return false
        }
        return true
    }

    fun voskRequired(lang: String = "en"): Map<String, Long> =
        VOSK_COMMON + if (lang == "hi") VOSK_GRAPH_HI else VOSK_GRAPH_EN
    fun sherpaMin(): Map<String, Long> = SHERPA_MIN

    fun ready(c: Context): Boolean = verifyFiles(dir(c), voskRequired("en"))

    /** Corrupt model found: delete so the next Start re-downloads instead of
     *  native-crashing the process. Returns true if anything was removed. */
    fun purgeIfCorrupt(c: Context, lang: String): Boolean {
        val d = if (lang == "hi") hiDir(c) else dir(c)
        if (d.exists() && !verifyFiles(d, voskRequired(lang))) {
            try { d.deleteRecursively() } catch (_: Exception) {}
            return true
        }
        if (lang != "hi" && sherpaDir(c).exists() && !verifyFiles(sherpaDir(c), SHERPA_MIN)) {
            SHERPA_FILES.forEach { f ->
                try { File(sherpaDir(c), f).delete() } catch (_: Exception) {}
            }
            return true
        }
        return false
    }

    private val downloadLock = java.util.concurrent.locks.ReentrantLock()

    // ---------- Hindi (vosk-model-small-hi-0.22, Apache 2.0) ----------
    // Wake phrase stays "Hey Strike" (constrained grammar); commands decode
    // in Hindi/Hinglish. No small Gujarati model exists upstream — Gujarati
    // in Latin script goes through the English model + LLM understanding.
    private const val HI_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"

    fun hiDir(c: Context): File = File(c.filesDir, "models/small-hi")

    fun hiReady(c: Context): Boolean = verifyFiles(hiDir(c), voskRequired("hi"))

    /** Model required for the given language pref. */
    fun readyFor(c: Context, lang: String): Boolean =
        if (lang == "hi") hiReady(c) else ready(c)

    fun downloadHi(c: Context, onProgress: (done: Long, total: Long) -> Unit) =
        downloadZip(HI_URL, hiDir(c).apply { mkdirs() }, "vosk-model-hi.zip", c, onProgress).also {
            if (!hiReady(c)) throw RuntimeException("Model unpack verify failed")
        }

    fun download(c: Context, onProgress: (done: Long, total: Long) -> Unit) =
        downloadZip(URL, dir(c).apply { mkdirs() }, "vosk-model.zip", c, onProgress).also {
            if (!ready(c)) throw RuntimeException("Model unpack verify failed")
        }

    private fun downloadZip(
        url: String, out: File, zipName: String, c: Context,
        onProgress: (done: Long, total: Long) -> Unit
    ) = downloadLock.withLock {
        val zip = File(c.cacheDir, zipName)
        val conn = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            connect()
        }
        if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong.coerceAtLeast(1)
        var done = 0L
        conn.inputStream.use { inp ->
            FileOutputStream(zip).use { fos ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
        // integrity: a truncated zip must never be unpacked into "ready" state
        if (total > 1 && done != total) {
            zip.delete()
            throw RuntimeException("truncated download: $done/$total bytes")
        }
        if (done <= 0) {
            zip.delete()
            throw RuntimeException("empty download")
        }
        // zip contains top folder vosk-model-small-en-us-0.15/ -> strip it
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var e = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (e != null) {
                if (!e.isDirectory) {
                    val rel = e.name.substringAfter('/', e.name)
                    if (rel.isNotEmpty()) {
                        val f = File(out, rel)
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { fos ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        zip.delete()
        // unpack verify is the caller's job (ready vs hiReady differ)
    }

    // ---------- sherpa-onnx streaming command ASR ----------
    // sherpa-onnx-streaming-zipformer-en-20M-2023-02-17 (Apache 2.0)
    // int8 encoder + decoder + joiner + tokens -> filesDir/models/sherpa-en20m
    private const val SHERPA_REPO = "csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    private val SHERPA_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt"
    )

    fun sherpaDir(c: Context): File = File(c.filesDir, "models/sherpa-en20m")

    fun sherpaReady(c: Context): Boolean =
        verifyFiles(sherpaDir(c), SHERPA_MIN)

    /** Resumable per-file download: HF primary, hf-mirror fallback.
     *  Lock + content-length verify: a truncated file must never be renamed
     *  into place (a corrupt int8 encoder crashes native load on-device). */
    /**  force=true deletes existing files first (repair a corrupt model). */
    fun downloadSherpa(
        c: Context,
        onProgress: (done: Long, total: Long) -> Unit,
        force: Boolean = false
    ) = downloadLock.withLock {
        val out = sherpaDir(c).apply { mkdirs() }
        val bases = listOf(
            "https://huggingface.co/",
            "https://hf-mirror.com/"
        )
        for (name in SHERPA_FILES) {
            val dest = File(out, name)
            if (force && dest.exists()) dest.delete()
            if (dest.length() > 0) continue
            val part = File(out, "$name.part")
            var ok = false
            for (base in bases) {
                val url = "$base$SHERPA_REPO/resolve/main/$name"
                try {
                    fetchTo(url, part, onProgress)
                    if (part.length() > 0) {
                        part.renameTo(dest)
                        ok = dest.length() > 0
                    }
                    if (ok) break
                } catch (_: Exception) { part.delete() }
            }
            if (!ok) throw RuntimeException("sherpa model download failed: $name")
        }
        if (!sherpaReady(c)) throw RuntimeException("sherpa model verify failed")
    }

    private fun fetchTo(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            connect()
        }
        if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { inp ->
            FileOutputStream(dest).use { fos ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    done += n
                    onProgress(done, total.coerceAtLeast(1))
                }
            }
        }
        if (total > 1 && done != total) {
            dest.delete()
            throw RuntimeException("truncated download: $done/$total bytes")
        }
        if (done <= 0) throw RuntimeException("empty download")
    }
}
