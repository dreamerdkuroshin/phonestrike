package com.heystrike.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

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
    private lateinit var crashStatus: TextView
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
        crashStatus = findViewById(R.id.crashStatus)
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
            // toggle: running -> stop the gate (mic off). In assistant-role
            // mode the OS owns the mic — stopping our service can't silence
            // it, so say so instead of pretending.
            if (StrikeVoiceController.isRunning()) {
                if (isAssistantHeld()) {
                    Toast.makeText(this,
                        "System-assistant role holds the mic — unset the role to silence fully",
                        Toast.LENGTH_LONG).show()
                } else {
                    try { stopService(Intent(this, VoiceService::class.java)) } catch (_: Exception) {}
                }
                Prefs.setAlwaysListen(this, false)
                note(wakeStatus, "OFF", R.color.strike_text2)
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ refreshStatus() }, 900)
                refreshStatus()
                return@setOnClickListener
            }
            // Start must never silently no-op: mic and the language's model
            // are preconditions, so handle them HERE with guidance.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS),
                    100
                )
                Toast.makeText(this, "Grant Microphone, then tap Start again", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!ModelManager.readyFor(this, Prefs.voiceLang(this))) {
                // corrupt counts as missing: purge first so a half-dead model
                // can never reach the gate (native crash, no stack trace)
                val purged = ModelManager.purgeIfCorrupt(this, Prefs.voiceLang(this))
                Toast.makeText(this,
                    if (purged) "Corrupt voice model removed — downloading fresh…"
                    else "Downloading voice model first…",
                    Toast.LENGTH_SHORT).show()
                downloadModels()
                return@setOnClickListener
            }
            // stop-then-start: applies a language/model change to a live gate
            // (stop is refused when the assistant service owns it — safe)
            try { stopService(Intent(this, VoiceService::class.java)) } catch (_: Exception) {}
            if (isAssistantHeld()) {
                // restart the mic service: it re-attempts the gate (revives a
                // dead session, or starts one if assistant boot failed)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val s = Intent(this, VoiceService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
                    } catch (_: Exception) {}
                    note(wakeStatus, "Restarting gate…", R.color.strike_text2)
                }, 500)
            } else {
                Prefs.setAlwaysListen(this, true)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        startService(Intent(this, VoiceService::class.java))
                    } catch (_: Exception) {}
                    note(wakeStatus, "ON — say “Hey Strike”", R.color.strike_ok)
                }, 500)
            }
            // service onCreate runs async — re-read the real state shortly after
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ refreshStatus() }, 1500)
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
        findViewById<Button>(R.id.modelBtn).setOnClickListener { downloadModels() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { micTest() }
        findViewById<Button>(R.id.ttsBtn).setOnClickListener { ttsTest() }
        findViewById<Button>(R.id.langBtn).setOnClickListener {
            val next = if (Prefs.voiceLang(this) == "hi") "en" else "hi"
            Prefs.setVoiceLang(this, next)
            refreshLangRow()
            if (next == "hi" && !ModelManager.hiReady(this)) {
                Toast.makeText(this, "Downloading Hindi voice model (~50MB)…", Toast.LENGTH_SHORT).show()
                downloadModels()
            } else {
                Toast.makeText(this, "Tap Start to apply the new language", Toast.LENGTH_SHORT).show()
            }
        }
        refreshLangRow()
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

    private fun refreshLangRow() {
        findViewById<TextView>(R.id.langStatus).text =
            if (Prefs.voiceLang(this) == "hi") "Hindi / Hinglish" else "English"
        OverlayService.lastTtsError?.let {
            findViewById<TextView>(R.id.ttsStatus).text = it
        }
    }

    /**
     * Voice-output self-test: checks the #1 silent-answer cause (media volume
     * at zero) BEFORE touching TTS, then plays a test line. If you hear
     * nothing and the volume is up, the status line names the engine error.
     */
    private fun ttsTest() {
        val st = findViewById<TextView>(R.id.ttsStatus)
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        if (vol == 0) {
            st.text = "Media volume is 0 — raise it, then Test again"
            Toast.makeText(this, "Media volume is 0 — raise volume, then retry",
                Toast.LENGTH_LONG).show()
            return
        }
        st.text = "Playing test… did you hear it?"
        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(this) { code ->
            if (code != android.speech.tts.TextToSpeech.SUCCESS) {
                runOnUiThread { st.text = "TTS engine failed ($code)" }
                return@TextToSpeech
            }
            val want = if (Prefs.voiceLang(this) == "hi") java.util.Locale("hi")
            else java.util.Locale.US
            tts?.language = want
            if (tts?.isLanguageAvailable(want) ?: -1 < 0) tts?.language = java.util.Locale.US
            val rc = tts?.speak("Strike voice test. Can you hear me?",
                android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "strike-test")
            runOnUiThread {
                st.text = if (rc == android.speech.tts.TextToSpeech.ERROR)
                    "speak() rejected — engine busy or broken"
                else "Playing test… did you hear it?"
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { tts?.shutdown() } catch (_: Exception) {}
        }, 8000)
    }

    /**
     * Mic self-test: 3s capture through the REAL wake pipeline (same audio
     * source, same Vosk model + grammar). Reports mic level + what the model
     * heard — turns "wake word doesn't work" into evidence.
     */
    private fun micTest() {
        val st = findViewById<TextView>(R.id.testStatus)
        st.text = "Listening 3s — say “Hey Strike”…"
        Thread {
            val msg = try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) throw RuntimeException("mic permission denied — tap Grant")
                val min = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min * 2, 8192))
                if (ar.state != AudioRecord.STATE_INITIALIZED)
                    throw RuntimeException("AudioRecord init failed (mic held by another app?)")
                ar.startRecording()
                val buf = ByteArray(4096)
                val rec = ByteArrayOutputStream()
                var peak = 0.0
                var frames = 0
                var speechFrames = 0
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 3000) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) {
                        rec.write(buf, 0, n)
                        var sum = 0.0
                        var c = 0
                        var i = 0
                        while (i + 1 < n) {
                            val s = (((buf[i + 1].toInt() shl 8) or
                                (buf[i].toInt() and 0xFF))) / 32768.0
                            sum += s * s
                            c++
                            i += 2
                        }
                        val r = sqrt(sum / max(c, 1))
                        peak = max(peak, r)
                        frames++
                        if (r > 0.0056) speechFrames++ // -45 dB voice floor
                    }
                }
                try { ar.stop() } catch (_: Exception) {}
                ar.release()
                val db = 20 * log10(max(peak, 1e-4))
                val speechPct = if (frames > 0) speechFrames * 100 / frames else 0
                val dir = if (Prefs.voiceLang(this) == "hi") ModelManager.hiDir(this)
                else ModelManager.dir(this)
                val heard = try {
                    val m = Model(dir.absolutePath)
                    // FULL grammar here, not the wake grammar: a constrained
                    // decode would (correctly) return empty for non-wake speech
                    // and hide a working model behind a false ASR_EMPTY
                    val r = Recognizer(m, 16000f)
                    val bytes = rec.toByteArray()
                    var off = 0
                    while (off < bytes.size) {
                        val n = minOf(4096, bytes.size - off)
                        r.acceptWaveForm(bytes.copyOfRange(off, off + n), n)
                        off += n
                    }
                    val fin = JSONObject(r.finalResult).optString("text", "")
                    try { r.close() } catch (_: Exception) {}
                    try { m.close() } catch (_: Exception) {}
                    fin.ifBlank { "(nothing decoded)" }
                } catch (e: Exception) {
                    "model error: ${e.message}"
                }
                // staged verdict: NEVER one generic line — each stage named
                val micStage = if (db < -45) "MIC: SILENCE (-inf dB, mic blocked/held?)"
                else "MIC: OK (peak %.0f dB)".format(db)
                val vadStage = if (db < -45) "VAD: n/a (no audio)"
                else "VAD: $speechPct% speech frames" +
                    if (speechPct < 5) " (NO SPEECH — spoke too late/soft?)" else " (OK)"
                val asrStage = if (heard.startsWith("model error")) "ASR: ERROR ($heard)"
                else if (heard == "(nothing decoded)") "ASR: EMPTY (audio reached model, no words out)"
                else "ASR: OK (heard “$heard”)"
                "$micStage\n$vadStage\n$asrStage"
            } catch (e: Exception) {
                "FAILED: ${e.message}"
            }
            runOnUiThread {
                st.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Shared by Download button + Start-button precondition + Hindi switch. */
    private fun downloadModels() {
        val lang = Prefs.voiceLang(this)
        modelProgress.text = "Downloading voice models…"
        Thread {
            try {
                // always run: tapping again is the repair path for a
                // truncated/corrupt model (force re-fetch)
                if (lang == "hi") {
                    ModelManager.downloadHi(this) { done, total ->
                        runOnUiThread {
                            modelProgress.text =
                                "Hindi: ${done / 1048576}MB / ${total / 1048576}MB"
                        }
                    }
                } else {
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
                }
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
        // Stopped must say WHY — "Stopped" alone sent users in circles.
        val why = when {
            running -> null
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED -> "Stopped — mic denied, tap Grant"
            !ModelManager.readyFor(this, Prefs.voiceLang(this)) ->
                "Stopped — voice model missing, tap Download"
            StrikeVoiceController.lastError != null ->
                "Stopped — ${StrikeVoiceController.lastError}"
            else -> "Stopped"
        }
        if (why == null) {
            wakeStatus.text = "Listening (${StrikeVoiceController.stateName()})"
            wakeStatus.setTextColor(getColor(R.color.strike_ok))
        } else {
            note(wakeStatus, why, R.color.strike_err)
        }
        findViewById<Button>(R.id.startBtn).text = if (running) "Stop" else "Start"

        setStatus(
            micStatus,
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
        setStatus(overlayStatus, Settings.canDrawOverlays(this))
        setStatus(a11yStatus, a11yOn())

        val have = ModelManager.readyFor(this, Prefs.voiceLang(this))
        modelStatus.text = if (have) "Ready" else "Missing"
        modelStatus.setTextColor(getColor(if (have) R.color.strike_ok else R.color.strike_err))

        CrashLog.lastCrash(this)?.let {
            crashStatus.text = it
            crashStatus.setTextColor(getColor(R.color.strike_err))
        } ?: run {
            crashStatus.text = "none"
            crashStatus.setTextColor(getColor(R.color.strike_text2))
        }

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
