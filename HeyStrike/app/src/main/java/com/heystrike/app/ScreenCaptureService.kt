package com.heystrike.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Spec 12 — mediaProjection foreground service: takes the user-granted
 * projection, grabs the first frame from an ImageReader (10s watchdog),
 * scales to <=1280px, answers through PocketStrike /api/vision, delivers
 * back to the waiting voice thread, stops itself.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private val main = Handler(Looper.getMainLooper())
    private var delivered = false
    private var watchdog: Runnable? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        startFg()
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        val question = intent?.getStringExtra("q") ?: "Describe what is on this screen."
        val myGen = intent?.getIntExtra(Vision.EXTRA_GEN, -1) ?: -1
        if (data == null) {
            finishWith(myGen, "Screen capture data missing.")
            return START_NOT_STICKY
        }
        worker = HandlerThread("strike-capture").also { it.start() }
        Thread { capture(data, question, myGen) }.start()
        return START_NOT_STICKY
    }

    private fun capture(data: Intent, question: String, myGen: Int) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(Activity.RESULT_OK, data)
            if (proj == null) {
                finishWith(myGen, "Screen capture denied.")
                return
            }
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {}
            }, main)
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            display = proj.createVirtualDisplay(
                "strike-capture", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            watchdog = Runnable {
                if (!delivered) {
                    finishWith(myGen, "Screen capture timed out.")
                    cleanup()
                }
            }
            main.postDelayed(watchdog!!, 10_000)
            val wh = worker?.looper?.let { Handler(it) } ?: Handler(Looper.getMainLooper())
            r.setOnImageAvailableListener({ rr -> handleFrame(rr, question, myGen) }, wh)
        } catch (e: SecurityException) {
            finishWith(myGen, "Screen capture not allowed: ${e.message}")
        } catch (e: Exception) {
            finishWith(myGen, "Screen capture failed: ${e.message}")
        }
    }

    private fun handleFrame(r: ImageReader, question: String, myGen: Int) {
        if (delivered) return
        val img = try {
            r.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        delivered = true
        try {
            val bmp = imageToBitmap(img)
            img.close()
            cleanup()
            val out = ByteArrayOutputStream()
            val max = 1280
            val s = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (s < 0.999f)
                Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            else bmp
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            if (scaled !== bmp) bmp.recycle()
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val answer = Vision.askServer(applicationContext, b64, question)
            finishWith(myGen, answer)
        } catch (e: Exception) {
            finishWith(myGen, "Capture failed: ${e.message}")
        }
    }

    /** RGBA_8888 first plane -> bitmap, tolerating row-stride padding. */
    private fun imageToBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val buf = plane.buffer
        val w = img.width
        val h = img.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowBytes = w * plane.pixelStride
        val remaining = buf.remaining()
        if (plane.rowStride == rowBytes && remaining >= rowBytes * h) {
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            return bmp
        }
        val all = ByteArray(remaining)
        buf.rewind()
        buf.get(all)
        val pixels = ByteArray(rowBytes * h)
        if (plane.rowStride == rowBytes) {
            System.arraycopy(all, 0, pixels, 0, minOf(pixels.size, all.size))
        } else {
            for (y in 0 until h) {
                val src = y * plane.rowStride
                val dst = y * rowBytes
                if (src + rowBytes > all.size) break
                System.arraycopy(all, src, pixels, dst, rowBytes)
            }
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        return bmp
    }

    private fun finishWith(myGen: Int, answer: String) {
        Vision.deliver(myGen, answer)
        stopSelf()
    }

    private fun cleanup() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker?.quitSafely()
        worker = null
    }

    private fun startFg() {
        val ch = "strike_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(ch, "Screen capture", NotificationManager.IMPORTANCE_LOW)
                )
        }
        val n: Notification = NotificationCompat.Builder(this, ch)
            .setContentTitle("Hey Strike")
            .setContentText("Capturing screen…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(3, n)
            }
        } catch (e: Exception) {
            // startForegroundService() obligation: stop instead of letting the
            // system fire RemoteServiceException for a missed startForeground
            Log.e("HeyStrikeAssistant", "capture foreground declined: ${e.message} — stopping")
            stopSelf()
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
