package com.heystrike.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Crash channel: Termux cannot read other-uid logcat on this device and adb
 * is not enabled, so uncaught stack traces are POSTed to PocketStrike
 * /api/crashlog -> ~/PocketStrike-AI/agent/crash.log (Termux-readable), plus
 * logcat with the CrashLog tag for the app's own uid.
 * The server is often down when a crash happens (or the crash IS the
 * network path), so a local backup is always written first and resent on
 * the next launch — crashes must never vanish silently.
 */
class StrikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

object CrashLog {

    private const val FILE = "crash-last.txt"

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { send(ctx.applicationContext, t, e) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Last crash summary for Settings, or null when clean. */
    fun lastCrash(ctx: Context): String? = try {
        val f = java.io.File(ctx.filesDir, FILE)
        if (!f.isFile) null
        else f.bufferedReader().readLine()?.take(160)
    } catch (_: Exception) { null }

    fun clearLast(ctx: Context) {
        try { java.io.File(ctx.filesDir, FILE).delete() } catch (_: Exception) {}
    }

    /** Best-effort resend of a backed-up crash (call off the main thread). */
    fun resendLast(ctx: Context): Boolean {
        if (!Prefs.crashUpload(ctx)) return false
        if (Prefs.serverBlockedReason(ctx) != null) return false
        val f = java.io.File(ctx.filesDir, FILE)
        if (!f.isFile) return true
        return try {
            val lines = f.readLines()
            if (lines.size < 3) return false
            val ver = lines[0]
            val thread = lines[1]
            val stack = lines.drop(2).joinToString("\n")
            val body = JSONObject()
                .put("app", ver)
                .put("thread", thread)
                .put("stack", stack.take(8000))
                .toString().toByteArray()
            val c = (URL(Prefs.server(ctx) + "/api/crashlog").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 2000
                readTimeout = 4000
                doOutput = true
            }
            c.outputStream.use { it.write(body) }
            val ok = try {
                c.inputStream.readBytes()
                true
            } catch (_: Exception) { false }
            if (ok) f.delete()
            ok
        } catch (_: Exception) { false }
    }

    /** P2-3: strip app paths + anything token-shaped before the stack
     *  leaves the device. Pure function, JVM-testable via send path. */
    fun sanitize(stack: String): String {
        var s = stack
        s = s.replace("/data/data/com.heystrike.app/", "<app>/")
        s = Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*\\S+")
            .replace(s, "$1=***")
        s = Regex("\\b(sk-[A-Za-z0-9-_]{8,}|ghp_[A-Za-z0-9]{8,}|xox[bap]-[A-Za-z0-9-]{8,})\\b")
            .replace(s, "***KEY***")
        return s
    }

    private fun send(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stack = sanitize(sw.toString())
        Log.e("CrashLog", "uncaught on ${t.name}: ${stack.take(300)}")
        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})"
        } catch (_: Exception) { "?" }
        // local backup FIRST (network below may fail); resent next launch
        try {
            java.io.File(ctx.filesDir, FILE).writeText(
                "$ver\n${t.name}\n${stack.take(8000)}")
        } catch (_: Exception) {}
        if (!Prefs.crashUpload(ctx)) return // opted out: local backup only
        if (Prefs.serverBlockedReason(ctx) != null) return // offline mode: keep local
        val body = JSONObject()
            .put("app", ver)
            .put("thread", t.name)
            .put("stack", stack.take(8000))
            .toString().toByteArray()
        val c = (URL(Prefs.server(ctx) + "/api/crashlog").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 2000
            readTimeout = 4000
            doOutput = true
        }
        c.outputStream.use { it.write(body) }
        try {
            c.inputStream.readBytes()
            clearLast(ctx) // server got it — backup no longer needed
        } catch (_: Exception) {
            try { c.errorStream?.close() } catch (_: Exception) {}
        }
    }
}
