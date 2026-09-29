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
}
