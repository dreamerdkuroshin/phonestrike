package com.heystrike.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/** Home screen: permission setup + server address + tap-to-talk. */
class MainActivity : AppCompatActivity() {

    private lateinit var serverBox: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverBox = findViewById(R.id.serverBox)
        statusText = findViewById(R.id.statusText)
        serverBox.setText(Prefs.server(this))

        findViewById<Button>(R.id.grantBtn).setOnClickListener { askPermissions() }
        findViewById<Button>(R.id.overlayBtn).setOnClickListener { askOverlay() }
        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            Prefs.saveServer(this, serverBox.text.toString().trim())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.startBtn).setOnClickListener {
            Prefs.saveServer(this, serverBox.text.toString().trim())
            startService(Intent(this, VoiceService::class.java))
            statusText.text = "Listening service ON. Say \"Hey Strike\"."
        }
        findViewById<Button>(R.id.talkBtn).setOnClickListener {
            Prefs.saveServer(this, serverBox.text.toString().trim())
            OverlayService.show(this)
        }
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
    }

    private fun askOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Allow 'Display over other apps', then come back", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Overlay already allowed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }
        statusText.text = if (ok) "Mic granted. Start the service." else "Mic denied — voice won't work."
    }
}
