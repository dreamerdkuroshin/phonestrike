package com.heystrike.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Persistent conversation state for follow-ups and LLM context.
 * SharedPreferences-backed (ponytail: no JSON store for 6 fields).
 * The enriched text is what goes to the LLM — the executor only ever
 * receives the FINAL transcript, never intermediate partials.
 */
object ConversationManager {

    private const val MAX_TURNS = 8
    private const val TURN_CHARS = 240
    private const val GAP_MS = 30 * 60 * 1000L
    // long-term memory of past queries: survives the 30-min gap + clear()
    private const val MEM_MAX = 12
    private const val MEM_CHARS = 160

    private lateinit var sp: SharedPreferences
    private fun prefs(c: Context): SharedPreferences {
        if (!::sp.isInitialized) sp = c.getSharedPreferences("conversation", Context.MODE_PRIVATE)
        return sp
    }

    /** UI repaint hook (task card / chat) — set by the foreground activity. */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Fresh conversation id when the gap since last activity > 30 min. */
    fun conversationId(c: Context): String {
        val now = SystemClock.elapsedRealtime()
        val last = prefs(c).getLong("last_ts", 0L)
        val id = prefs(c).getString("id", null)
        if (id == null || (last > 0 && now - last > GAP_MS)) {
            val newId = "c${System.currentTimeMillis()}"
            prefs(c).edit().putString("id", newId).putLong("last_ts", now).apply()
            clear(c)
            return newId
        }
        prefs(c).edit().putLong("last_ts", now).apply()
        return id
    }

    fun topic(c: Context): String = prefs(c).getString("topic", "") ?: ""

    fun lastUser(c: Context): String = prefs(c).getString("last_user", "") ?: ""

    fun lastReply(c: Context): String = prefs(c).getString("last_reply", "") ?: ""

    fun activeTask(c: Context): String = prefs(c).getString("task", "") ?: ""

    fun lastToolResult(c: Context): String =
        (prefs(c).getString("tool", "") ?: "").lines().lastOrNull { it.isNotBlank() } ?: ""

    /** All recorded steps this task, oldest first — the Tasks screen reads this. */
    fun toolSteps(c: Context): List<String> =
        (prefs(c).getString("tool", "") ?: "").lines().filter { it.isNotBlank() }

    /** Recent turns for the home chat list: (who, text), oldest first. */
    fun turns(c: Context): List<Pair<String, String>> =
        (prefs(c).getString("turns", "") ?: "").lines().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
        }

    fun recordUser(c: Context, text: String) {
        conversationId(c) // refresh gap timer + id
        val line = "Q: " + text.take(MEM_CHARS).replace('\n', ' ')
        val mem = (prefs(c).getString("mem", "") ?: "").lines().filter { it.isNotBlank() }
        prefs(c).edit()
            .putString("last_user", text.take(TURN_CHARS))
            .putString("turns", push(prefs(c).getString("turns", "") ?: "", "user", text))
            .putString("mem", (mem + line).takeLast(MEM_MAX).joinToString("\n"))
            .apply()
        if (topic(c).isEmpty() && text.isNotBlank()) {
            prefs(c).edit().putString("topic", topicOf(text)).apply()
        }
    }

    fun recordAnswer(c: Context, text: String) {
        // one record per turn: callers' onDone is the single writer (the old
        // stream+caller dedupe also swallowed legitimately repeated answers)
        if (text.isBlank()) return
        prefs(c).edit()
            .putString("last_reply", text.take(TURN_CHARS))
            .putString("turns", push(prefs(c).getString("turns", "") ?: "", "strike", text))
            .apply()
    }

    fun setTask(c: Context, text: String) {
        prefs(c).edit().putString("task", text.take(TURN_CHARS)).apply()
        onChange?.invoke()
    }

    /** Task finished/stopped: drop it so the task card + Stop button don't go stale. */
    fun clearTask(c: Context) {
        prefs(c).edit().remove("task").remove("tool").apply()
        onChange?.invoke()
    }

    fun recordToolResult(c: Context, text: String) {
        // append, not overwrite: the Tasks screen shows the real step list
        val steps = ((prefs(c).getString("tool", "") ?: "").lines()
            .filter { it.isNotBlank() } + text.take(TURN_CHARS)).takeLast(8)
        prefs(c).edit().putString("tool", steps.joinToString("\n")).apply()
        onChange?.invoke()
    }

    /** True for short contextless follow-ups ("again", "what about tomorrow"). */
    fun isFollowUp(c: Context, text: String): Boolean {
        if (topic(c).isEmpty()) return false
        val words = text.trim().split(Regex("\\s+"))
        if (words.size > 8) return false
        val startsCommand = Regex("^(open|launch|call|message|text|send|search|play|tap|go)\\b")
        return !startsCommand.containsMatchIn(text.lowercase())
    }

    /**
     * Wrap the final transcript with conversation context for the LLM.
     * Unchanged when the conversation is empty (first turn).
     */
    fun enrich(c: Context, text: String): String {
        val turns = prefs(c).getString("turns", "") ?: ""
        if (turns.isBlank()) return text
        val ctx = buildString {
            append("[Conversation context. ")
            append("id=${conversationId(c)}")
            if (Prefs.voiceLang(c) == "hi") append("; user speaks Hindi/Hinglish — reply in the same language")
            topic(c).takeIf { it.isNotBlank() }?.let { append("; topic=$it") }
            lastUser(c).takeIf { it.isNotBlank() }?.let { append("; last user said=\"$it\"") }
            lastReply(c).takeIf { it.isNotBlank() }?.let { append("; your last reply=\"$it\"") }
            activeTask(c).takeIf { it.isNotBlank() }?.let { append("; active task=\"$it\"") }
            lastToolResult(c).takeIf { it.isNotBlank() }?.let { append("; last tool result=\"$it\"") }
            // long-term memory: survives sessions — "memory of past queries"
            (prefs(c).getString("mem", "") ?: "").lines().filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.let { append("; past queries from earlier sessions: [" + it.joinToString("; ") + "]") }
            // recent turns verbatim: the header alone lost multi-turn context
            turns.lines().filter { it.contains('|') }.takeLast(4).takeIf { it.isNotEmpty() }
                ?.let { tl ->
                    append("; recent turns: [" + tl.joinToString(" / ") { line ->
                        val i = line.indexOf('|')
                        (if (line.startsWith("user|")) "U: " else "S: ") +
                            line.substring(i + 1).take(160)
                    } + "]")
                }
            if (isFollowUp(c, text)) append("; the user is following up on this topic — interpret in that context")
            append("]\n")
        }
        return ctx + text
    }

    /** Clears the current conversation only — "mem" (past-query memory) is kept on purpose. */
    fun clear(c: Context) {
        prefs(c).edit()
            .remove("turns").remove("topic").remove("last_user")
            .remove("last_reply").remove("task").remove("tool")
            .apply()
    }

    // ponytail: "user|text\n" lines instead of a JSON array — parsed with split
    private fun push(turns: String, who: String, text: String): String {
        val line = "$who|${text.take(TURN_CHARS).replace('\n', ' ')}"
        val list = (turns.lines().filter { it.contains('|') } + line)
        return list.takeLast(MAX_TURNS).joinToString("\n")
    }

    private fun topicOf(text: String): String {
        val t = text.trim().lowercase()
        for (prefix in listOf("open ", "launch ", "search ", "play ", "message ", "call ", "text ", "send ")) {
            if (t.startsWith(prefix)) return t.removePrefix(prefix).take(40)
        }
        return t.take(40)
    }
}
