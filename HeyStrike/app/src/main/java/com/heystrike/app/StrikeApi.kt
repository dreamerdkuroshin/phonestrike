package com.heystrike.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Talks to PocketStrike server (same box as Termux: http://127.0.0.1:5000).
 * Mobile tasks that need zero server round-trip (app launch) run here with
 * PackageManager — reliable, no am/monkey hacks, normal permissions only.
 */
class StrikeApi(private val ctx: Context, private val tts: TextToSpeech?) {

    private val appNames = mapOf(
        "whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps", "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android", "spotify" to "com.spotify.music",
        "camera" to "com.android.camera2", "clock" to "com.google.android.deskclock",
        "settings" to "com.android.settings", "photos" to "com.google.android.apps.photos"
    )

    fun handle(text: String): String {
        val t = text.lowercase().trim()
        // Phone-UI layer (Accessibility): tap by visible text, back/home
        if (t.startsWith("tap ")) {
            val target = t.removePrefix("tap ").trim()
            if (target.isEmpty()) return "Tap what? Say tap followed by the button name."
            return if (StrikeAccessibilityService.tapText(target)) "Tapped $target."
            else "Couldn't find $target on screen. Enable Strike Tap in Accessibility settings."
        }
        if (t in listOf("go back", "back", "press back")) {
            return if (StrikeAccessibilityService.goBack()) "Went back."
            else "Back unavailable. Enable Strike Tap in Accessibility settings."
        }
        if (t in listOf("go home", "home screen", "press home")) {
            return if (StrikeAccessibilityService.goHome()) "Home."
            else "Home unavailable. Enable Strike Tap in Accessibility settings."
        }
        // Local instant intents (no network): open app
        for ((name, pkg) in appNames) {
            if (t.contains("open $name") || t == "open $name") {
                return if (launch(pkg)) "Opened $name." else "Couldn't open $name. Is it installed?"
            }
        }
        // Everything else -> PocketStrike brain (local pocket-qwen3 or cloud)
        return askServer(text)
    }

    private fun launch(pkg: String): Boolean {
        return try {
            val pm: PackageManager = ctx.packageManager
            val intent: Intent? = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                true
            } else false
        } catch (_: Exception) { false }
    }

    private fun askServer(text: String): String {
        return try {
            val url = URL(Prefs.server(ctx) + "/api/voice/command")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 120000
                doOutput = true
            }
            val body = JSONObject().put("text", text).toString().toByteArray()
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream.bufferedReader().readText()
            JSONObject(resp).optString("response", "Sorry, empty reply.")
        } catch (e: Exception) {
            "Strike server unreachable. In Termux run: bash ~/PocketStrike-AI/start-all.sh"
        }
    }

    fun speak(s: String) {
        val clean = s.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF☀-➿➕➖]"), "").trim().take(450)
        tts?.speak(clean.ifBlank { "Done." }, TextToSpeech.QUEUE_FLUSH, null, "strike")
    }
}
