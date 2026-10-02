package com.heystrike.app

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec 11/12 — vision bridge. The voice flow blocks on askCamera/askScreen
 * while CameraActivity / ScreenShotActivity captures a frame and PocketStrike's
 * /api/vision answers; the answer then continues as the normal spoken reply.
 * One capture at a time; stale delivers from an older activity are dropped.
 */
object Vision {

    const val EXTRA_GEN = "vision_gen"

    @Volatile private var gen = 0
    @Volatile private var busy = false
    @Volatile private var result: String? = null
    @Volatile private var latch = CountDownLatch(1)

    fun askCamera(ctx: Context, q: String): String = launch(ctx, CameraActivity::class.java, q)
    fun askScreen(ctx: Context, q: String): String = launch(ctx, ScreenShotActivity::class.java, q)

    private fun launch(ctx: Context, cls: Class<*>, q: String): String {
        if (busy) return "I'm already looking at something — one capture at a time."
        busy = true
        try {
            gen++
            val myGen = gen
            result = null
            latch = CountDownLatch(1)
            val i = Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("q", q)
                .putExtra(EXTRA_GEN, myGen)
            ctx.startActivity(i)
            if (!latch.await(45, TimeUnit.SECONDS)) return "Vision timed out — try again."
            return result ?: "No vision result."
        } catch (e: Exception) {
            return "Couldn't start capture: ${e.message}"
        } finally {
            busy = false
        }
    }

    fun deliver(myGen: Int, answer: String) {
        if (myGen != gen) return // stale activity from an older capture
        result = answer
        latch.countDown()
    }

    /** POST a base64 JPEG to PocketStrike /api/vision; plain answer or honest error. */
    fun askServer(ctx: Context, b64: String, question: String): String {
        return try {
            val c = (URL(Prefs.server(ctx) + "/api/vision").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
            }
            val body = JSONObject().put("image", b64).put("question", question.take(300))
                .toString().toByteArray()
            c.outputStream.use { it.write(body) }
            val resp = JSONObject(c.inputStream.bufferedReader().readText())
            if (resp.has("error")) "Vision unavailable: ${resp.optString("error")}"
            else resp.optString("answer", "I couldn't read that.").take(600)
        } catch (e: Exception) {
            "Vision server unreachable — in Termux run start-all.sh."
        }
    }
}
