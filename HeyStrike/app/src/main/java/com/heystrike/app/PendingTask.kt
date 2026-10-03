package com.heystrike.app

/**
 * Clarification task state: when an intent arrives with missing slots
 * (app/recipient/message), Strike asks ONE question and this object holds
 * the partial goal. The next utterance fills, confirms, or cancels it.
 * TTL + process death retire it — a stale "haan" can never fire old work.
 */
object PendingTask {

    data class Task(
        val action: String,
        val app: String?,
        val recipient: String?,
        val message: String?,
        val ts: Long
    ) {
        fun missing(): List<String> =
            IntentParser.MsgIntent(action, app, recipient, message).missing()

        /** Planner-ready goal with everything known so far. */
        fun goal(): String = if (action == "open") {
            "open ${app ?: "app"}"
        } else {
            buildString {
                append("open ${app ?: "app"} and message ${recipient ?: "them"}")
                if (!message.isNullOrBlank()) append(" $message")
            }
        }
    }

    sealed interface Outcome {
        data class Say(val text: String) : Outcome
        data class Run(val goal: String) : Outcome
    }

    private const val TTL_MS = 180_000L

    @Volatile
    private var task: Task? = null

    fun set(intent: IntentParser.MsgIntent) {
        task = Task(intent.action, intent.app, intent.recipient, intent.message,
            System.currentTimeMillis())
    }

    fun active(): Task? {
        val t = task ?: return null
        if (System.currentTimeMillis() - t.ts > TTL_MS) {
            task = null
            return null
        }
        return t
    }

    fun clear() {
        task = null
    }

    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "do it",
        "haan", "haa", "han", "ji", "theek hai", "kar do", "bhej do"
    )
    private val NO = setOf(
        "no", "nope", "cancel", "stop", "nahi", "nahin", "na",
        "mat karo", "mat kar", "rehn do"
    )

    /**
     * Returns an Outcome when a task is pending, null to route normally.
     * A fresh COMPLETE command replaces the pending task and runs.
     */
    fun consume(t: String, hindi: Boolean): Outcome? {
        val cur = active() ?: return null
        val s = t.trim()
        // user re-said a complete command — replace and run it
        IntentParser.parseMessage(s)?.let { intent ->
            if (intent.complete) {
                clear()
                return Outcome.Run(
                    if (intent.action == "open") "open ${intent.app}" else s)
            }
        }
        if (s in YES) {
            val m = cur.missing()
            if (m.isEmpty()) {
                clear()
                return Outcome.Run(cur.goal())
            }
            // "haan" to "what should I send?" still needs the text — say what
            // to do next instead of looping the same question silently
            return Outcome.Say(
                if (m == listOf("message")) {
                    if (hindi) "Message text bolo — kya likhu?"
                    else "Tell me the message text."
                } else IntentParser.clarify(
                    IntentParser.MsgIntent(cur.action, cur.app, cur.recipient, cur.message),
                    hindi)
            )
        }
        if (s in NO) {
            clear()
            return Outcome.Say(if (hindi) "Theek hai, cancel kar diya." else "OK, cancelled.")
        }
        // short reply fills the first missing slot; anything longer re-parses
        // as a fresh command below (returns null -> normal routing)
        val words = s.split(" ").filter { it.isNotBlank() }
        val m = cur.missing()
        if (m.isNotEmpty() && words.size <= 4) {
            val filled = when (m.first()) {
                "recipient" -> cur.copy(recipient = IntentParser.sanitizeName(s))
                "message" -> cur.copy(message = s)
                else -> {
                    val app = IntentParser.findApp(s)
                    if (app != null) cur.copy(app = app) else return null
                }
            }
            task = filled.copy(ts = System.currentTimeMillis())
            val still = filled.missing()
            return if (still.isEmpty()) {
                clear()
                Outcome.Run(filled.goal())
            } else Outcome.Say(
                IntentParser.clarify(
                    IntentParser.MsgIntent(filled.action, filled.app, filled.recipient, filled.message),
                    hindi)
            )
        }
        return null
    }
}
