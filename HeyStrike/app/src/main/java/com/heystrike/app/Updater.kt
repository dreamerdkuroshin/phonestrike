package com.heystrike.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-tap self-update — no GitHub website visits ever again.
 * Checks latest public release, downloads via system DownloadManager,
 * fires the installer with our own FileProvider (Hey Strike appears in
 * the install-sources list because we hold REQUEST_INSTALL_PACKAGES).
 */
object Updater {
    private const val REPO = "dreamerdkuroshin/phonestrike"
    private const val FILE = "HeyStrike-update.apk"
    private var dlId: Long = -1

    data class Info(val tag: String, val url: String, val size: Long)

    fun latest(): Info? {
        return try {
            val c = (URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
            }
            if (c.responseCode !in 200..299) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name", "")
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk")) {
                    return Info(tag, a.getString("browser_download_url"), a.optLong("size", 0))
                }
            }
            null
        } catch (_: Exception) { null }
    }

    fun isNewer(ctx: Context, tag: String): Boolean {
        return try {
            val cur = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val curV = cur.versionName ?: "v0"
            tag.trimStart('v') != curV.trimStart('v')
        } catch (_: Exception) { false }
    }

    fun download(ctx: Context, info: Info): Long {
        val req = DownloadManager.Request(Uri.parse(info.url)).apply {
            setTitle("Hey Strike ${info.tag}")
            setDescription("Downloading update…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FILE)
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dlId = dm.enqueue(req)
        ctx.registerReceiver(InstallReceiver(), IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        return dlId
    }

    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != dlId) return
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(c, c.packageName + ".provider", file)
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                c.startActivity(open)
            } catch (_: Exception) {}
        }
    }
}
