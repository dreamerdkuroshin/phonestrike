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

    fun ready(c: Context): Boolean =
        File(dir(c), "am/final.mdl").exists()

    private val downloadLock = java.util.concurrent.locks.ReentrantLock()

    fun download(c: Context, onProgress: (done: Long, total: Long) -> Unit) = downloadLock.withLock {
        val out = dir(c).apply { mkdirs() }
        val zip = File(c.cacheDir, "vosk-model.zip")
        val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
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
        if (!ready(c)) throw RuntimeException("Model unpack verify failed")
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
        SHERPA_FILES.all { f -> File(sherpaDir(c), f).length() > 0 }

    /** Resumable per-file download: HF primary, hf-mirror fallback.
     *  Lock + content-length verify: a truncated file must never be renamed
     *  into place (a corrupt int8 encoder crashes native load on-device). */
    fun downloadSherpa(c: Context, onProgress: (done: Long, total: Long) -> Unit) = downloadLock.withLock {
        val out = sherpaDir(c).apply { mkdirs() }
        val bases = listOf(
            "https://huggingface.co/",
            "https://hf-mirror.com/"
        )
        for (name in SHERPA_FILES) {
            val dest = File(out, name)
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
