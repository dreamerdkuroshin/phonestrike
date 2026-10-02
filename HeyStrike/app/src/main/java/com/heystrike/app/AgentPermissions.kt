package com.heystrike.app

/**
 * Spec 31/32 — permission levels for agent tool actions.
 * SAFE runs as-is; CONFIRM needs an explicit yes; CRITICAL is the same
 * barrier with louder wording (payments, irreversible ops). Levels are
 * code-side defaults (always on) — a Settings toggle can come later.
 */
object AgentPermissions {
    enum class Level { SAFE, CONFIRM, CRITICAL }

    private val confirmWords = listOf(
        "send", "submit", "post", "publish", "delete", "remove",
        "trash", "discard", "share", "forward"
    )
    private val criticalWords = listOf(
        "pay", "purchase", "buy", "checkout", "transfer",
        "uninstall", "format", "wipe", "restore", "factory reset"
    )

    private fun hasWord(text: String, word: String): Boolean =
        Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(text)

    fun levelFor(tool: String, arg: String, drafted: Boolean = false): Level {
        // enter() after a typed draft SUBMITS it (send/search) — gate it;
        // bare enter() with no draft (e.g. search field only) stays quick
        if (tool.equals("enter", true)) return if (drafted) Level.CONFIRM else Level.SAFE
        if (!tool.equals("tap", true)) return Level.SAFE // typing drafts is safe; the tap sends
        val a = arg.lowercase()
        if (criticalWords.any { hasWord(a, it) }) return Level.CRITICAL
        if (confirmWords.any { hasWord(a, it) }) return Level.CONFIRM
        return Level.SAFE
    }

    fun needsConfirmation(tool: String, arg: String): Boolean =
        levelFor(tool, arg) != Level.SAFE
}
