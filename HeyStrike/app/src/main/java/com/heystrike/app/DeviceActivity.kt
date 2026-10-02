package com.heystrike.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Device: live phone/server status + quick actions. Every row is read at
 * open — no placeholders. Shizuku/Termux rows report package presence,
 * which is what's honestly checkable without their APIs.
 */
class DeviceActivity : AppCompatActivity() {

    private lateinit var rows: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_device)
        rows = findViewById(R.id.devRows)
        Nav.wire(this, findViewById<BottomNavigationView>(R.id.bottomNav), R.id.nav_device)
        findViewById<Button>(R.id.actApps).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
        findViewById<Button>(R.id.actFiles).setOnClickListener {
            launchPkg("com.google.android.documentsui", "Files app not found")
        }
        findViewById<Button>(R.id.actScreen).setOnClickListener {
            startActivity(Intent(this, ScreenShotActivity::class.java)
                .putExtra("q", "Describe what's on screen."))
        }
        findViewById<Button>(R.id.actCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java)
                .putExtra("q", "What am I looking at?"))
        }
        findViewById<Button>(R.id.actTermux).setOnClickListener {
            launchPkg("com.termux", "Termux not installed")
        }
        findViewById<Button>(R.id.actMic).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Grant Microphone first (Home asks on launch)",
                    Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val i = Intent(this, VoiceService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                Toast.makeText(this, "Listening — say Hey Strike", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't start mic service", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Thread { val t = statusText(); runOnUiThread { rows.text = t } }.start()
    }

    private fun launchPkg(pkg: String, missing: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) Toast.makeText(this, missing, Toast.LENGTH_SHORT).show()
        else startActivity(i)
    }

    private fun statusText(): String {
        val tick = { ok: Boolean -> if (ok) "\u2713" else "\u2717" }
        val bm = getSystemService(BatteryManager::class.java)
        val pct = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString() + "%"
        } catch (_: Exception) { "?" }
        val sf = StatFs(Environment.getDataDirectory().path)
        val freeGb = "%.1f".format(sf.availableBytes / 1e9)
        val cm = getSystemService(ConnectivityManager::class.java)
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            cap == null -> "offline"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "connected"
        }
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val a11y = StrikeAccessibilityService.instance != null
        return listOf(
            "Battery          $pct",
            "Storage free     ${freeGb} GB",
            "Network          $net",
            "Server           ${tick(Nav.ping(Prefs.server(this)))} ${Prefs.server(this)}",
            "Local AI         ${tick(Nav.ping("http://127.0.0.1:8081/health"))} llama :8081",
            "Microphone       ${tick(mic)}",
            "Strike Tap       ${tick(a11y)} accessibility",
            "Shizuku app      ${tick(hasPkg("moe.shizuku.privileged.api"))}",
            "Termux           ${tick(hasPkg("com.termux"))}"
        ).joinToString("\n")
    }

    private fun hasPkg(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) { false }
}
