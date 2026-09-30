package com.heystrike.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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

    fun download(c: Context, onProgress: (done: Long, total: Long) -> Unit) {
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
}
