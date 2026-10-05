package com.heystrike.app

import android.content.Context

object Prefs {
    private const val F = "strike"
    fun server(c: Context): String =
        c.getSharedPreferences(F, Context.MODE_PRIVATE)
            .getString("server", "http://127.0.0.1:5000")!!.trimEnd('/')

    fun saveServer(c: Context, v: String) {
        c.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putString("server", v.ifBlank { "http://127.0.0.1:5000" }).apply()
    }

    /** Standalone always-on listening (boot auto-start honors this). */
    fun alwaysListen(c: Context): Boolean =
        c.getSharedPreferences(F, Context.MODE_PRIVATE).getBoolean("alwaysListen", false)

    fun setAlwaysListen(c: Context, v: Boolean) {
        c.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putBoolean("alwaysListen", v).apply()
    }

    /** Voice language: "en" (default), "hi" (Hindi/Hinglish), "gu"
     *  (Gujarati via whisper-tiny, UNVERIFIED decode).
     *  Gujarati/Hinglish in Latin script needs no model switch — the English
     *  ASR transcribes Latin script and the LLM understands it. */
    fun voiceLang(c: Context): String =
        c.getSharedPreferences(F, Context.MODE_PRIVATE).getString("voiceLang", "en") ?: "en"

    fun setVoiceLang(c: Context, v: String) {
        c.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putString("voiceLang", if (v == "hi") "hi" else if (v == "gu") "gu" else "en").apply()
    }

    /** Crash upload to the LOCAL server only (127.0.0.1 crash.log).
     *  Default ON: the endpoint is loopback (no remote exfil), and without
     *  it crashes are invisible (no adb/logcat on this device). The stack is
     *  sanitized client-side before send; turn off in Settings anytime. */
    fun crashUpload(c: Context): Boolean =
        c.getSharedPreferences(F, Context.MODE_PRIVATE).getBoolean("crashUpload", true)

    fun setCrashUpload(c: Context, v: Boolean) {
        c.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putBoolean("crashUpload", v).apply()
    }

    /** Offline-first enforcement (A-06): refuse cloud backends. */
    fun offlineRequired(c: Context): Boolean =
        c.getSharedPreferences(F, Context.MODE_PRIVATE).getBoolean("offlineRequired", true)

    fun setOfflineRequired(c: Context, v: Boolean) {
        c.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putBoolean("offlineRequired", v).apply()
    }

    /** F-06 full close: non-null reason when this server URL must not be
     *  contacted (offline_required + non-loopback host). Every POST site
     *  checks this so a mistyped/poisoned server URL fails LOUD instead of
     *  silently exfiltrating voice/vision/crashes. */
    fun serverBlockedReason(c: Context): String? {
        if (!offlineRequired(c)) return null
        val host = try {
            java.net.URL(server(c)).host.lowercase()
        } catch (_: Exception) { return "bad server URL" }
        return if (host in setOf("127.0.0.1", "localhost", "::1")) null
        else "⛔ OFFLINE_REQUIRED: server is '$host' — use 127.0.0.1 or turn offline mode off in Settings"
    }

    /** OOM-safe body read: a malicious/compromised server must not be able
     *  to OOM the app with a giant response. Truncates at max chars. */
    fun readCapped(conn: java.net.HttpURLConnection, max: Int = 200_000): String {
        val out = java.io.ByteArrayOutputStream()
        try {
            conn.inputStream.use { inp ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    val take = minOf(n, max - total)
                    if (take <= 0) break
                    out.write(buf, 0, take)
                    total += take
                }
            }
        } catch (_: Exception) { }
        return out.toString("UTF-8")
    }
}
