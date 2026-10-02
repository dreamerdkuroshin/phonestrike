package com.heystrike.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Categorized settings: ASSISTANT / VOICE / AI MODEL / PHONE CONTROL /
 * SECURITY / ABOUT. Logic ported verbatim from the old MainActivity.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var assistantStatus: TextView
    private lateinit var wakeStatus: TextView
    private lateinit var micStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var psStatus: TextView
    private lateinit var llmStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: TextView
    private lateinit var serverBox: EditText

    // ponytail: startActivityForResult is deprecated but is the only
    // role-request path that compiles on every API level without extra deps.
    private val assistantRoleRequest = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        assistantStatus = findViewById(R.id.assistantStatus)
        wakeStatus = findViewById(R.id.wakeStatus)
        micStatus = findViewById(R.id.micStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        a11yStatus = findViewById(R.id.a11yStatus)
        psStatus = findViewById(R.id.psStatus)
        llmStatus = findViewById(R.id.llmStatus)
        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        serverBox = findViewById(R.id.serverBox)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        serverBox.setText(Prefs.server(this))

        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.versionStatus).text = "v${pi.versionName}"
        } catch (_: Exception) {}

        modelProgress.text =
            if (ModelManager.ready(this) && ModelManager.sherpaReady(this)) "Voice models ready"
            else "Not downloaded — tap Download"

        findViewById<Button>(R.id.assistantBtn).setOnClickListener { requestAssistantRole() }
        findViewById<Button>(R.id.startBtn).setOnClickListener {
            Prefs.saveServer(this, serverBox.text.toString().trim())
            if (isAssistantHeld()) {
                // restart the mic service: it re-attempts the gate (revives a
                // dead session, or starts one if assistant boot failed)
                try {
                    val s = Intent(this, VoiceService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
                } catch (_: Exception) {}
                note(wakeStatus, "Restarting gate…", R.color.strike_text2)
            } else {
                Prefs.setAlwaysListen(this, true)
                startService(Intent(this, VoiceService::class.java))
                note(wakeStatus, "ON — say “Hey Strike”", R.color.strike_ok)
            }
            // service onCreate runs async — re-read the real state shortly after
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ refreshStatus() }, 900)
            refreshStatus()
        }
        findViewById<Button>(R.id.talkBtn).setOnClickListener {
            Prefs.saveServer(this, serverBox.text.toString().trim())
            // push-to-talk: orb + one-shot gate capture (orb alone had no STT
            // consumer and froze the screen behind it)
            val running = StrikeVoiceController.isRunning()
            if (!running) {
                try {
                    val s = Intent(this, VoiceService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
                } catch (_: Exception) {}
            }
            OverlayService.show(this)
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ StrikeVoiceController.requestCapture() }, if (running) 0 else 1800)
            finish()
        }
        findViewById<Button>(R.id.grantBtn).setOnClickListener {
            val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
        }
        findViewById<Button>(R.id.overlayBtn).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                Toast.makeText(this, "Allow “Display over other apps”, then come back", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Overlay already allowed", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.modelBtn).setOnClickListener {
            modelProgress.text = "Downloading voice models (~43MB)…"
            Thread {
                try {
                    // always run: tapping again is the repair path for a
                    // truncated/corrupt model (force re-fetch)
                    ModelManager.download(this) { done, total ->
                        runOnUiThread {
                            modelProgress.text =
                                "Vosk: ${done / 1048576}MB / ${total / 1048576}MB"
                        }
                    }
                    ModelManager.downloadSherpa(this, { done, total ->
                        runOnUiThread {
                            modelProgress.text =
                                "Streaming ASR: ${done / 1048576}MB / ${total / 1048576}MB"
                        }
                    }, force = true)
                    runOnUiThread {
                        modelProgress.text = "Voice models ready"
                        refreshStatus()
                        // models landed — (re)start the gate now; assistant boot
                        // may have given up while they were missing
                        try {
                            val s = Intent(this@SettingsActivity, VoiceService::class.java)
                            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s)
                            else startService(s)
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        modelProgress.text = "Download failed: ${e.message} — tap to retry"
                    }
                }
            }.start()
        }
        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            Prefs.saveServer(this, serverBox.text.toString().trim())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        findViewById<Button>(R.id.a11yBtn).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this, "Open Settings → Accessibility → Strike Tap", Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            ConversationManager.clear(this)
            Toast.makeText(this, "Conversation cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun isAssistantHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } catch (_: Exception) { false }
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            openDefaultAppsSettings()
            return
        }
        val rm: RoleManager = try {
            getSystemService(RoleManager::class.java) ?: return openDefaultAppsSettings()
        } catch (_: Exception) { return openDefaultAppsSettings() }
        if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            note(assistantStatus, "Role unavailable", R.color.strike_err)
            return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            note(assistantStatus, "Active", R.color.strike_ok)
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), assistantRoleRequest)
        } catch (_: Exception) { openDefaultAppsSettings() }
    }

    private fun openDefaultAppsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            Toast.makeText(this, "Pick Hey Strike as digital assistant, then come back", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this, "Settings → Apps → Default apps → Digital assistant", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("role request result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == assistantRoleRequest) {
            if (resultCode == Activity.RESULT_OK) note(assistantStatus, "Active", R.color.strike_ok)
            else note(assistantStatus, "Not granted", R.color.strike_err)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != 100) return
        val ok = res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }
        if (ok) note(micStatus, "Active", R.color.strike_ok)
        else note(micStatus, "Denied — voice won't work", R.color.strike_err)
    }

    private fun refreshStatus() {
        setStatus(assistantStatus, isAssistantHeld())
        val running = StrikeVoiceController.isRunning()
        wakeStatus.text = if (running) "Listening (${StrikeVoiceController.stateName()})" else "Stopped"
        wakeStatus.setTextColor(getColor(if (running) R.color.strike_ok else R.color.strike_err))

        setStatus(
            micStatus,
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
        setStatus(overlayStatus, Settings.canDrawOverlays(this))
        setStatus(a11yStatus, a11yOn())

        val have = ModelManager.ready(this) && ModelManager.sherpaReady(this)
        modelStatus.text = if (have) "Ready" else "Missing"
        modelStatus.setTextColor(getColor(if (have) R.color.strike_ok else R.color.strike_err))

        Thread {
            val ps = ping(Prefs.server(this))
            val llm = ping("http://127.0.0.1:8081/health")
            runOnUiThread {
                setStatus(psStatus, ps)
                setStatus(llmStatus, llm)
            }
        }.start()
    }

    private fun setStatus(v: TextView, ok: Boolean) {
        v.text = if (ok) "Active" else "Off"
        v.setTextColor(getColor(if (ok) R.color.strike_ok else R.color.strike_err))
    }

    private fun note(v: TextView, text: String, colorRes: Int) {
        v.text = text
        v.setTextColor(getColor(colorRes))
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun ping(url: String): Boolean = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        c.requestMethod = "GET"
        val code = c.responseCode
        c.disconnect()
        code in 100..599
    } catch (_: Exception) { false }
}
