package com.heystrike.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * Spec 11 — camera capture: opens on a voice cue, auto-captures one frame
 * (voice-command-triggered, 1.4s settle — no shutter tap needed), sends it to
 * PocketStrike /api/vision, hands the answer back to the blocked voice thread.
 */
class CameraActivity : AppCompatActivity() {

    private var question = "Describe what you see."
    private var myGen = -1
    private var shot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        question = intent.getStringExtra("q") ?: question
        myGen = intent.getIntExtra(Vision.EXTRA_GEN, -1)
        setContentView(R.layout.activity_camera)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        code: Int, permissions: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Vision.deliver(myGen,
                "I need Camera permission — grant it to Hey Strike in Settings, then ask again.")
            finish()
        }
    }

    private fun startCamera() {
        val status = findViewById<TextView>(R.id.camStatus)
        status.text = "Opening camera…"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                status.text = "Looking…"
                findViewById<PreviewView>(R.id.previewView).postDelayed({ shoot(capture, status) }, 1400)
            } catch (e: Exception) {
                Vision.deliver(myGen, "Camera failed: ${e.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shoot(capture: ImageCapture, status: TextView) {
        if (shot) return
        shot = true
        status.text = "Capturing…"
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // default output format is JPEG: plane 0 = compressed bytes
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    image.close()
                    Thread {
                        val answer = try {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: throw IllegalStateException("empty photo")
                            val scaled = scaleDown(bmp)
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
                            if (scaled !== bmp) scaled.recycle()
                            bmp.recycle()
                            Vision.askServer(
                                applicationContext,
                                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                                question
                            )
                        } catch (e: Exception) {
                            "Photo failed: ${e.message}"
                        }
                        Vision.deliver(myGen, answer)
                        runOnUiThread { finish() }
                    }.start()
                }

                override fun onError(e: ImageCaptureException) {
                    Vision.deliver(myGen, "Photo failed: ${e.message}")
                    finish()
                }
            })
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val max = 1280
        val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        return if (s < 0.999f)
            Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
        else b
    }
}
