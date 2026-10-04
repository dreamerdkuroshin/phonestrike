package com.heystrike.app

import android.content.Context

/**
 * Spec 16/32 — HITL confirmation barrier. The planner pauses before a
 * CONFIRM/CRITICAL tap; the next utterance ("yes"/"no") or the task-card
 * Yes/No buttons release it. In-memory only: 2-minute TTL and process death
 * cancel the action (a stale yes can never fire an old action).
 */
object PendingConfirm {

    enum class Answer { YES, NO, OTHER }

    data class Pending(val tool: String, val arg: String, val level: String, val ts: Long)

    private const val TTL_MS = 120_000L

    @Volatile
    private var pending: Pending? = null

    fun set(tool: String, arg: String, level: AgentPermissions.Level): Pending {
        val p = Pending(tool, arg, level.name, System.currentTimeMillis())
        pending = p
        ConversationManager.onChange?.invoke() // repaint the confirm card now
        return p
    }

    fun active(): Pending? {
        val p = pending ?: return null
        if (System.currentTimeMillis() - p.ts > TTL_MS) {
            pending = null
            return null
        }
        return p
    }

    fun clear(ctx: Context? = null) {
        pending = null
        ctx?.let { ConversationManager.clearTask(it) }
    }

    /** Pure voice classification — JVM-tested. */
    fun classify(text: String): Answer {
        val s = text.lowercase().trim().trim('!', '?', '.', ',', ' ', '\t')
        return when (s) {
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm",
            "do it", "go ahead", "proceed", "send", "send it", "yes please",
            "confirm it", "yes do it", "haan", "haa", "han", "ji",
            "theek hai", "kar do", "bhej do" -> Answer.YES
            "no", "nope", "cancel", "stop", "stop it", "don't", "dont",
            "never mind", "nevermind", "abort", "don't send", "dont send",
            "no dont", "nahi", "nahin", "na", "mat karo", "mat kar" -> Answer.NO
            else -> Answer.OTHER
        }
    }

    /**
     * Voice/text path: consumes the utterance only when a confirmation is
     * pending. null = route normally. ANY non-yes retires the pending action
     * so a later stray "yes" can never send something stale.
     */
    fun consume(text: String, ctx: Context): String? {
        val p = active() ?: return null
        return when (classify(text)) {
            Answer.YES -> execute(ctx, p)
            Answer.NO -> {
                clear(ctx)
                "Cancelled — I did not tap ${p.arg}."
            }
            Answer.OTHER -> {
                clear(ctx)
                null
            }
        }
    }

    /** Task-card "Yes" button path. */
    fun execute(ctx: Context): String {
        val p = active() ?: return "Nothing to confirm."
        return execute(ctx, p)
    }

    private fun execute(ctx: Context, p: Pending): String {
        // capture the plan BEFORE clear() wipes it: a mid-plan confirm must
        // resume the planner afterwards, or every retry restarts from zero
        // and re-taps Send (the duplicate-send class)
        val taskBefore = ConversationManager.activeTask(ctx)
        val stepsBefore = ConversationManager.toolSteps(ctx)
        clear(ctx)
        return try {
            val res = StrikeAgent(ctx).tool(p.tool, p.arg)
            if (!res.startsWith("ok")) return "Failed — $res"
            if (taskBefore.isNotBlank() && stepsBefore.isNotEmpty()) {
                return StrikeApi.continueAfterConfirm(
                    ctx, taskBefore, stepsBefore, "${p.tool}(${p.arg}) -> $res")
            }
            "Done — tapped ${p.arg}."
        } catch (e: Exception) {
            "Confirmation failed: ${e.message}"
        }
    }

    /** Task-card "No" button path. */
    fun cancel(ctx: Context): String {
        val p = active()
        clear(ctx)
        return if (p != null) "Cancelled — I did not tap ${p.arg}." else "Nothing to cancel."
    }

    fun question(p: Pending): String =
        if (p.level == "CRITICAL")
            "High-risk action: ready to tap ${p.arg}? Say yes to confirm or no to cancel."
        else
            "Ready to tap ${p.arg}? Say yes to confirm, no to cancel."
}
