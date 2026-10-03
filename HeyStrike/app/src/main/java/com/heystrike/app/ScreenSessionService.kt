package com.heystrike.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * Persistent screen-capture session inside ONE OS grant. Android demands
 * the system consent dialog per grant — never bypassed. Once granted, this
 * service holds the MediaProjection and captures frames on demand (agent
 * observe loops) until stop/revoke, with explicit states:
 * WAITING (grant in flight) / ACTIVE / STOPPED / DENIED.
 * No caller yet — the agent observe loop wires to requestFrame() next.
 */
class ScreenSessionService : Service() {

    enum class State { IDLE, WAITING, ACTIVE, STOPPED, DENIED }

    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                state = State.WAITING
                notifyState("Waiting for permission…")
                startFg()
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    state = State.DENIED
                    notifyState("Capture unavailable — no grant")
                    stopSelf()
                    return START_NOT_STICKY
                }
                openSession(data)
            }
            ACTION_STOP -> {
                state = State.STOPPED
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun openSession(data: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(Activity.RESULT_OK, data) ?: run {
                state = State.DENIED
                notifyState("Capture denied")
                stopSelf()
                return
            }
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // OS revoked mid-session (screen lock, policy)
                    state = State.STOPPED
                    notifyState("Capture stopped by system")
                    stopSelf()
                }
            }, main)
            val dm = resources.displayMetrics
            worker = HandlerThread("strike-session").also { it.start() }
            reader = ImageReader.newInstance(
                dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
            display = proj.createVirtualDisplay(
                "strike-session", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, null)
            state = State.ACTIVE
            notifyState("Capture active")
            inst = this
            Log.i(TAG, "screen session active")
        } catch (e: SecurityException) {
            state = State.DENIED
            notifyState("Capture denied: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            state = State.DENIED
            notifyState("Capture unavailable: ${e.message}")
            stopSelf()
        }
    }

    /**
     * Grab the latest frame as JPEG bytes (<=1280px, q75). Null when no
     * session/frame — callers must handle null, never a fake frame.
     */
    fun captureFrame(): ByteArray? {
        val r = reader ?: return null
        if (state != State.ACTIVE) return null
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return null
        return try {
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rowBytes = w * plane.pixelStride
            val buf = plane.buffer
            val all = ByteArray(buf.remaining())
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
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
            img.close()
            val max = 1280
            val s = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (s < 0.999f)
                Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun startFg() {
        val ch = "strike_session"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(ch, "Screen session", NotificationManager.IMPORTANCE_LOW))
        }
        startFgWith("Screen session starting…")
    }

    private fun notifyState(text: String) {
        try {
            startFgWith(text)
        } catch (_: Exception) {}
    }

    private fun startFgWith(text: String) {
        val n: Notification = NotificationCompat.Builder(this, "strike_session")
            .setContentTitle("Hey Strike")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(4, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(4, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "session foreground declined: ${e.message} — stopping")
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker?.quitSafely()
        worker = null
        if (state == State.ACTIVE) state = State.STOPPED
        super.onDestroy()
    }

    companion object {
        const val TAG = "HeyStrikeAssistant"
        const val ACTION_START = "session_start"
        const val ACTION_STOP = "session_stop"
        const val EXTRA_DATA = "data"

        @Volatile
        var state: State = State.IDLE

        @Volatile
        var inst: ScreenSessionService? = null

        fun startSession(c: Context, grant: Intent) {
            state = State.WAITING
            val i = Intent(c, ScreenSessionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DATA, grant)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i)
                else c.startService(i)
            } catch (_: Exception) {
                state = State.DENIED
            }
        }

        fun stopSession(c: Context) {
            try {
                c.startService(Intent(c, ScreenSessionService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {}
        }
    }
}
