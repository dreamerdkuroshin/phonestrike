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

    /** Voice language: "en" (default) or "hi" (Hindi/Hinglish ASR + TTS).
     *  Gujarati/Hinglish in Latin script needs no model switch — the English
     *  ASR transcribes Latin script and the LLM understands it. */
    fun voiceLang(c: Context): String =
        c.getSharedPreferences(F, Context.MODE_PRIVATE).getString("voiceLang", "en") ?: "en"

    fun setVoiceLang(c: Context, v: String) {
        c.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putString("voiceLang", if (v == "hi") "hi" else "en").apply()
    }
}
