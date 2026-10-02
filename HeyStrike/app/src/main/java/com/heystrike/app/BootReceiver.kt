package com.heystrike.app

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Auto-start listening after reboot, mode-aware:
 *  - assistant role held -> only the mic shell FGS (system binds AssistantService itself)
 *  - standalone -> only if the user enabled always-listen
 * Never both engines: the shared controller owns the gate.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return

        val assistant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                c.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            } catch (_: Exception) { false }
        } else false

        when {
            assistant -> Log.i(TAG, "boot: assistant role held — starting mic shell only")
            !Prefs.alwaysListen(c) -> {
                Log.i(TAG, "boot: standalone always-listen OFF — skipping")
                return
            }
            else -> Log.i(TAG, "boot: starting standalone wake service")
        }

        try {
            val s = Intent(c, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s) else c.startService(s)
        } catch (e: Exception) {
            // background FGS start may be declined right after boot — degrade
            Log.w(TAG, "boot: service start declined: ${e.message}")
        }
    }

    companion object {
        const val TAG = "HeyStrikeAssistant"
    }
}
