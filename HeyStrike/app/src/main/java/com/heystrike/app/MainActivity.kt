package com.heystrike.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Calendar

/**
 * Home: top bar + connection dot, time-aware greeting with live voice-state
 * subtitle, quick-action chips, chat bubbles (ConversationManager turns),
 * task card with Stop, pinned mic, text input. Settings live in SettingsActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var greeting: TextView
    private lateinit var subtitle: TextView
    private lateinit var connDot: View
    private lateinit var chipScroll: View
    private lateinit var chatList: ListView
    private lateinit var taskCard: View
    private lateinit var taskTitle: TextView
    private lateinit var taskStep: TextView
    private lateinit var statusLine: TextView
    private lateinit var textBox: EditText

    private val items = mutableListOf<Pair<String, String>>()
    private var liveIdx = -1 // index of the in-flight Strike bubble
    private lateinit var adapter: BubbleAdapter
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val api by lazy { StrikeApi(applicationContext, null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ACTION_ASSIST: launched as system assistant — show the orb, not home
        if (intent?.action == Intent.ACTION_ASSIST) {
            OverlayService.show(this)
            StrikeVoiceController.requestCapture()
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        greeting = findViewById(R.id.greeting)
        subtitle = findViewById(R.id.subtitle)
        connDot = findViewById(R.id.connDot)
        chipScroll = findViewById(R.id.chipScroll)
        chatList = findViewById(R.id.chatList)
        taskCard = findViewById(R.id.taskCard)
        taskTitle = findViewById(R.id.taskTitle)
        taskStep = findViewById(R.id.taskStep)
        statusLine = findViewById(R.id.statusLine)
        textBox = findViewById(R.id.textBox)

        greeting.text = greet()

        adapter = BubbleAdapter()
        chatList.adapter = adapter
        reloadTurns()

        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.micBtn).setOnClickListener {
            // push-to-talk: show the orb AND ask the gate for a one-shot capture
            // (before: the orb showed with no STT consumer and blocked all input)
            val running = StrikeVoiceController.isRunning()
            if (!running) {
                try {
                    val s = Intent(this, VoiceService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
                } catch (_: Exception) {}
            }
            OverlayService.show(this)
            main.postDelayed({ StrikeVoiceController.requestCapture() }, if (running) 0 else 1800)
        }
        findViewById<ImageButton>(R.id.sendBtn).setOnClickListener {
            send(textBox.text.toString())
        }
        textBox.setOnEditorActionListener { _, _, _ ->
            send(textBox.text.toString()); true
        }
        findViewById<TextView>(R.id.chipChrome).setOnClickListener { send("open chrome") }
        findViewById<TextView>(R.id.chipWhatsApp).setOnClickListener { send("open whatsapp") }
        findViewById<TextView>(R.id.chipYouTube).setOnClickListener { send("open youtube") }
        findViewById<TextView>(R.id.chipSettings).setOnClickListener { send("open settings") }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            // emergency stop (spec 58): agent + TTS (both layers) + pending confirm
            StrikeAgent.stopRequested = true
            PendingConfirm.clear(this)
            OverlayService.inst?.interruptAnswer()
            AssistantSessionService.active?.interruptSpeaking()
            StrikeVoiceController.notifyIdle()
            ConversationManager.clearTask(this)
            taskCard.visibility = View.GONE
        }
        findViewById<Button>(R.id.confirmYesBtn).setOnClickListener {
            Thread {
                val ans = PendingConfirm.execute(this)
                ConversationManager.recordAnswer(this, ans)
                runOnUiThread { reloadTurns(); refreshTaskCard() }
            }.start()
        }
        findViewById<Button>(R.id.confirmNoBtn).setOnClickListener {
            Thread {
                val ans = PendingConfirm.cancel(this)
                ConversationManager.recordAnswer(this, ans)
                runOnUiThread { reloadTurns(); refreshTaskCard() }
            }.start()
        }

        renderState(StrikeVoiceController.state)
        refreshTaskCard()
        refreshConnectionDot()
        requestMissingPermissions()
        Nav.wire(this, findViewById<BottomNavigationView>(R.id.bottomNav), R.id.nav_home)
    }

    // First run: mic permission was previously only requestable from
    // Settings → Grant, so a fresh install had no way to start the gate.
    private fun requestMissingPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 101 && res.isNotEmpty() && res.all {
                it == android.content.pm.PackageManager.PERMISSION_GRANTED
            }) refreshVoiceService()
    }

    private fun greet(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    // ---- chat ----

    private fun reloadTurns() {
        val t = ConversationManager.turns(this)
        items.clear()
        items.addAll(t)
        liveIdx = -1
        adapter.notifyDataSetChanged()
        chipScroll.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        scrollChat()
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        textBox.setText("")
        chipScroll.visibility = View.GONE
        items.add("user" to text)
        items.add("strike" to "")
        liveIdx = items.size - 1
        adapter.notifyDataSetChanged()
        scrollChat()
        Thread {
            api.handleStream(text,
                onToken = { tok ->
                    runOnUiThread {
                        if (liveIdx < 0 || liveIdx >= items.size) return@runOnUiThread
                        items[liveIdx] = "strike" to (items[liveIdx].second + tok)
                        adapter.notifyDataSetChanged()
                        scrollChat()
                    }
                },
                onDone = {
                    runOnUiThread {
                        // local intents are not recorded by StrikeApi; recordAnswer is
                        // idempotent for AI answers that already were (stream + voice).
                        val done = if (liveIdx in items.indices) items[liveIdx].second else ""
                        if (done.isNotBlank()) ConversationManager.recordAnswer(this, done)
                        liveIdx = -1
                        reloadTurns()
                        refreshTaskCard()
                        renderState(StrikeVoiceController.state)
                    }
                })
        }.start()
    }

    private fun scrollChat() {
        main.post { chatList.setSelection(items.size - 1) }
    }

    private fun refreshTaskCard() {
        val pc = PendingConfirm.active()
        if (pc != null) {
            taskCard.visibility = View.VISIBLE
            findViewById<View>(R.id.confirmRow).visibility = View.VISIBLE
            taskTitle.text = "Confirmation needed"
            taskStep.text = "Ready to tap \u201C${pc.arg}\u201D?"
            return
        }
        findViewById<View>(R.id.confirmRow).visibility = View.GONE
        val task = ConversationManager.activeTask(this)
        val step = ConversationManager.lastToolResult(this)
        if (task.isBlank() && step.isBlank()) {
            taskCard.visibility = View.GONE
            return
        }
        taskCard.visibility = View.VISIBLE
        taskTitle.text = task.ifBlank { "Task running" }
        taskStep.text = step.ifBlank { "Working…" }
    }

    // ---- voice state -> subtitle / mic / task card ----

    private val stateListener: (StrikeVoiceController.State) -> Unit = { s ->
        renderState(s)
        // a live chat stream owns liveIdx — reloading here would silently
        // drop its remaining tokens (onDone reloads instead)
        if (liveIdx < 0 && (s == StrikeVoiceController.State.PROCESSING ||
                s == StrikeVoiceController.State.COOLDOWN ||
                s == StrikeVoiceController.State.IDLE ||
                s == StrikeVoiceController.State.ASSISTANT_SPEAKING)
        ) {
            reloadTurns()
            refreshTaskCard()
        }
    }

    private fun renderState(s: StrikeVoiceController.State) {
        subtitle.text = when (s) {
            StrikeVoiceController.State.IDLE, StrikeVoiceController.State.COOLDOWN ->
                if (StrikeVoiceController.isRunning()) "Ready — say Hey Strike" else "Tap the mic to start"
            StrikeVoiceController.State.WAKE_DETECTED,
            StrikeVoiceController.State.LISTENING,
            StrikeVoiceController.State.USER_SPEAKING,
            StrikeVoiceController.State.POSSIBLE_END,
            StrikeVoiceController.State.INTERRUPTED -> "Listening…"
            StrikeVoiceController.State.PROCESSING -> "Thinking…"
            StrikeVoiceController.State.ASSISTANT_SPEAKING -> "Speaking…"
            StrikeVoiceController.State.ERROR -> "Mic error — open Settings"
        }
        statusLine.text = when {
            s == StrikeVoiceController.State.ERROR -> "Gate error — ⚙ Settings → retry"
            StrikeVoiceController.isRunning() -> "“Hey Strike” · ${StrikeVoiceController.stateName()}"
            else -> "Setup needed — tap ⚙"
        }
    }

    private fun refreshConnectionDot() {
        Thread {
            val ps = ping(Prefs.server(this))
            val llm = ping("http://127.0.0.1:8081/health")
            runOnUiThread {
                connDot.setBackgroundResource(
                    when {
                        ps && llm -> R.drawable.dot_green
                        ps || llm -> R.drawable.dot_yellow
                        else -> R.drawable.dot_red
                    }
                )
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (!::greeting.isInitialized) return // ACTION_ASSIST early-finish path
        StrikeVoiceController.addStateListener(stateListener)
        // live task-card repaint while the agent records tool steps
        ConversationManager.onChange = { runOnUiThread { refreshTaskCard() } }
        renderState(StrikeVoiceController.state)
        reloadTurns()
        refreshTaskCard()
        refreshConnectionDot()
        refreshVoiceService()
    }

    /**
     * Wake word without a button: assistant-role OR standalone always-listen
     * starts/revives the mic service on every foreground visit (OEM kills,
     * swipe-away, process death). Consent-gated — never starts for users who
     * didn't opt in via Settings → Start / Set as assistant.
     */
    private fun refreshVoiceService() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!mic || !(isAssistantHeld() || Prefs.alwaysListen(this))) return
        try {
            val s = Intent(this, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        if (::greeting.isInitialized) {
            StrikeVoiceController.removeStateListener(stateListener)
            ConversationManager.onChange = null
        }
    }

    private fun isAssistantHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } catch (_: Exception) { false }
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

    // ---- chat bubble adapter ----

    private inner class BubbleAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bubble, parent, false)
            val bubble = v.findViewById<TextView>(R.id.bubbleText)
            val (who, text) = items[position]
            bubble.text = text
            val lp = bubble.layoutParams as FrameLayout.LayoutParams
            if (who == "user") {
                bubble.setBackgroundResource(R.drawable.bubble_user)
                lp.gravity = Gravity.END
                bubble.setTextColor(Color.WHITE)
            } else {
                bubble.setBackgroundResource(R.drawable.bubble_strike)
                lp.gravity = Gravity.START
                bubble.setTextColor(getColor(R.color.strike_text))
            }
            bubble.layoutParams = lp
            return v
        }
    }
}
