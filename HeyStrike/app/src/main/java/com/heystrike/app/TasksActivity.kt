package com.heystrike.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Tasks: the one place background/agent work stays visible. Reads only real
 * state — active task, appended tool steps, pending confirmation. Stop runs
 * the same emergency-stop sequence as Home.
 */
class TasksActivity : AppCompatActivity() {

    private lateinit var taskTitle: TextView
    private lateinit var taskStatus: TextView
    private lateinit var taskSteps: TextView
    private lateinit var stopBtn: Button

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_tasks)
        taskTitle = findViewById(R.id.taskTitle)
        taskStatus = findViewById(R.id.taskStatus)
        taskSteps = findViewById(R.id.taskSteps)
        stopBtn = findViewById(R.id.taskStopBtn)
        Nav.wire(this, findViewById<BottomNavigationView>(R.id.bottomNav), R.id.nav_tasks)
        stopBtn.setOnClickListener { stopAll(); refresh() }
    }

    override fun onResume() {
        super.onResume()
        ConversationManager.onChange = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        ConversationManager.onChange = null
    }

    private fun refresh() {
        val task = ConversationManager.activeTask(this)
        val pc = PendingConfirm.active()
        taskTitle.text = task.ifBlank { "No active task" }
        taskStatus.text = when {
            pc != null -> "Waiting: ready to ${pc.tool} \u201C${pc.arg}\u201D? Answer Yes/No on Home."
            task.isBlank() -> "Idle — tasks started by voice or chat appear here."
            else -> ConversationManager.lastToolResult(this).ifBlank { "Working…" }
        }
        val lines = ConversationManager.toolSteps(this)
        taskSteps.text = if (lines.isEmpty()) "Steps appear here while Strike works."
        else lines.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        stopBtn.visibility = if (task.isBlank() && pc == null) View.GONE else View.VISIBLE
    }

    private fun stopAll() {
        StrikeAgent.stopRequested = true
        PendingConfirm.clear(this)
        OverlayService.inst?.interruptAnswer()
        AssistantSessionService.active?.interruptSpeaking()
        StrikeVoiceController.notifyIdle()
        ConversationManager.clearTask(this)
    }
}
