package com.heystrike.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Bottom nav shared by Home/Tasks/Device (More = SettingsActivity).
 * REORDER_TO_FRONT: no duplicate instances, Back still works. Task/agent
 * state lives in ConversationManager prefs + services, never in activities,
 * so navigating never disturbs a running task.
 */
object Nav {
    fun wire(a: AppCompatActivity, bar: BottomNavigationView, current: Int) {
        bar.selectedItemId = current
        bar.setOnItemSelectedListener { item ->
            if (item.itemId == current) return@setOnItemSelectedListener true
            val cls = when (item.itemId) {
                R.id.nav_home -> MainActivity::class.java
                R.id.nav_tasks -> TasksActivity::class.java
                R.id.nav_device -> DeviceActivity::class.java
                R.id.nav_more -> SettingsActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            a.startActivity(Intent(a, cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            true
        }
    }

    /** Same cheap reachability probe MainActivity uses for its dot. */
    fun ping(url: String): Boolean = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        c.requestMethod = "GET"
        val code = c.responseCode
        c.disconnect()
        code in 100..599
    } catch (_: Exception) { false }
}
