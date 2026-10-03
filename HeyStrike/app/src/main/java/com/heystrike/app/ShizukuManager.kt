package com.heystrike.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * Shizuku capability layer. Honest states only: without the Shizuku API
 * dependency only package presence is checkable, so READY is unreachable
 * until the API is integrated + permission granted on-device. Privileged
 * ops route through run(): Shizuku path when READY, otherwise the labeled
 * fallback — never silently pretending the fallback was privileged.
 */
object ShizukuManager {
    enum class State { NOT_INSTALLED, INSTALLED_NO_API, READY }

    const val PKG = "moe.shizuku.privileged.api"

    fun installed(c: Context): Boolean = try {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Exception) { false }

    fun state(c: Context): State =
        if (installed(c)) State.INSTALLED_NO_API else State.NOT_INSTALLED
    // READY requires the Shizuku API lib + user grant — not yet integrated.

    /** Route a privileged op; caller records which path ran. */
    fun <T> run(shizuku: () -> T, fallback: () -> T, available: Boolean): Pair<String, T> =
        if (available) "shizuku" to shizuku() else "fallback" to fallback()

    fun permissionHint(): String =
        "Install Shizuku, pair via wireless debugging, then grant Hey Strike in the Shizuku app."
}
