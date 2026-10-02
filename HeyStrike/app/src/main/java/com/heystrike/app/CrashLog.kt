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
 */
class StrikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

object CrashLog {

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { send(ctx.applicationContext, t, e) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun send(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()
        Log.e("CrashLog", "uncaught on ${t.name}: $stack")
        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})"
        } catch (_: Exception) { "?" }
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
        try { c.inputStream.readBytes() } catch (_: Exception) {
            try { c.errorStream?.close() } catch (_: Exception) {}
        }
    }
}
