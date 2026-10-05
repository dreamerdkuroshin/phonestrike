package com.heystrike.app

/**
 * 5-level permission tiers for agent tool actions (spec §38):
 * READ_ONLY observes; LOW_RISK acts reversibly; USER_CONFIRMED needs an
 * explicit yes; SENSITIVE (calls, messages, secrets) same barrier, louder
 * audit; CRITICAL (payments, irreversible ops) loudest wording.
 * Levels are code-side defaults (always on). Keyword tables cover English +
 * Hindi/Gujarati in NORMALIZED (romanized) form — Transliterate first.
 */
object AgentPermissions {
    enum class Level { READ_ONLY, LOW_RISK, USER_CONFIRMED, SENSITIVE, CRITICAL }

    private val confirmWords = listOf(
        "send", "submit", "post", "publish", "delete", "remove",
        "trash", "discard", "share", "forward"
    )
    private val criticalWords = listOf(
        "pay", "purchase", "buy", "checkout", "transfer",
        "uninstall", "format", "wipe", "restore", "factory reset"
    )
    // normalized Hinglish/Gujarati: bhejo (send), hatao/mitao (delete),
    // mokalo (send, Gujarati), bhugtan (pay)
    private val confirmWordsHi = listOf("bhejo", "bheje", "hatao", "mitao", "mita", "mokalo", "mokli")
    private val criticalWordsHi = listOf("bhugtan")
    private val sensitiveWords = listOf(
        "call", "message", "sms", "password", "otp", "pin", "bhejo", "bheje"
    )

    private fun hasWord(text: String, word: String): Boolean =
        Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(text)

    fun levelFor(tool: String, arg: String, drafted: Boolean = false): Level {
        val t = tool.lowercase()
        // read-only tools never act
        if (t == "verify" || t == "wait" || t == "done") return Level.READ_ONLY
        // enter() after a typed draft SUBMITS it (send/search) — gate it;
        // bare enter() with no draft (e.g. search field only) stays quick
        if (t == "enter") return if (drafted) Level.USER_CONFIRMED else Level.LOW_RISK
        if (t != "tap" && t != "type" && t != "launch" && t != "back" && t != "home" &&
            t != "swipeup" && t != "swipedown"
        ) return Level.USER_CONFIRMED // default-deny unknown tools
        if (t != "tap") return Level.LOW_RISK // typing drafts is safe; the tap sends
        val a = Transliterate.normalize(arg)
        if (criticalWords.any { hasWord(a, it) } || criticalWordsHi.any { hasWord(a, it) }) {
            return Level.CRITICAL
        }
        if (sensitiveWords.any { hasWord(a, it) }) return Level.SENSITIVE
        if (confirmWords.any { hasWord(a, it) } || confirmWordsHi.any { hasWord(a, it) }) {
            return Level.USER_CONFIRMED
        }
        return Level.LOW_RISK
    }

    fun shouldPrompt(level: Level): Boolean = level >= Level.USER_CONFIRMED

    fun needsConfirmation(tool: String, arg: String): Boolean =
        shouldPrompt(levelFor(tool, arg))
}
