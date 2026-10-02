package com.heystrike.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Spec 12 — screen-vision consent front-end: shows the system screen-capture
 * dialog (user grant every time, per platform policy), then hands the grant
 * to ScreenCaptureService which captures one frame and answers via /api/vision.
 */
class ScreenShotActivity : AppCompatActivity() {

    private var question = "Describe what is on this screen."
    private var myGen = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        question = intent.getStringExtra("q") ?: question
        myGen = intent.getIntExtra(Vision.EXTRA_GEN, -1)
        setContentView(R.layout.activity_vision)
        findViewById<TextView>(R.id.visionStatus).text =
            "Screen capture — approve the system dialog…"
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            Vision.deliver(myGen, "Screen capture failed: ${e.message}")
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .putExtra("data", data)
                .putExtra("q", question)
                .putExtra(Vision.EXTRA_GEN, myGen)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (e: Exception) {
                Vision.deliver(myGen, "Couldn't start capture: ${e.message}")
            }
        } else {
            Vision.deliver(myGen, "Screen capture cancelled.")
        }
        finish()
    }

    companion object {
        private const val REQ = 71
    }
}
