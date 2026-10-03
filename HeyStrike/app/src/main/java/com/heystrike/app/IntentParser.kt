package com.heystrike.app

/**
 * Deterministic message/open intent parser on NORMALIZED text.
 * Never executes — produces a structured intent with explicit missing
 * slots so the caller clarifies instead of guessing (spec: no blind
 * execution, no pretending "eyr strik open aur beru" parsed fully).
 */
object IntentParser {

    data class MsgIntent(
        val action: String, // "message" | "open"
        val app: String?, // canonical lowercase ("whatsapp")
        val recipient: String?,
        val message: String?
    ) {
        val complete: Boolean
            get() = if (action == "open") app != null
            else app != null && recipient != null && !message.isNullOrBlank()

        /** Slots still needed, in ask order. */
        fun missing(): List<String> {
            if (action == "open") return if (app == null) listOf("app") else emptyList()
            val m = mutableListOf<String>()
            if (app == null) m.add("app")
            if (recipient == null) m.add("recipient")
            if (message.isNullOrBlank()) m.add("message")
            return m
        }
    }

    private val APP_ALIASES = mapOf(
        "whatsapp" to listOf(
            "whatsapp", "whats app", "watsap", "votsaip", "votsaipa",
            "vhatsaip", "vhaatsaip", "votsep", "votsaep", "watsapp", "whatsap"
        )
    )
    // "opan" is the faithful rendering of Devanagari ओपन (no written e)
    private val OPEN_VERBS = listOf("open", "opan", "launch", "start", "khol", "kholo", "kholna")
    private val MSG_VERBS = listOf(
        "message", "msg", "maisej", "mesej", "mesij", "send", "text",
        "bhej", "bhejo", "bhejna", "likh", "likho"
    )
    private val AND_WORDS = listOf("and", "aur", "ane", "then", "tatha", "tato")
    private val FILLER = setOf(
        "ko", "ke", "ne", "to", "please", "kripya", "kro", "karo", "kar",
        "the", "a", "an", "me", "mein", "par", "se"
    )
    // Gujarati/Hindi ergative glued to names (beru+ne) — stripped only when
    // something remains; bare postpositions never become the name
    private val NAME_SUFFIX = listOf("ne", "no", "nu", "ko", "ke")

    /** "berune" -> "beru"; "beru" stays "beru". */
    fun sanitizeName(raw: String): String {
        val s = raw.trim()
        for (suf in NAME_SUFFIX) {
            if (s.length > suf.length + 1 && s.endsWith(suf)) return s.dropLast(suf.length)
        }
        return s
    }

    private fun hasWord(t: String, w: String) = " $w " in " $t "

    fun findApp(t: String): String? {
        val spaced = " $t "
        for ((canon, aliases) in APP_ALIASES) {
            if (aliases.any { " $it " in spaced }) return canon
        }
        return null
    }

    fun hasOpenVerb(t: String) = OPEN_VERBS.any { hasWord(t, it) }

    fun hasMsgVerb(t: String) = MSG_VERBS.any { hasWord(t, it) }

    /**
     * Parse a message/open compound. Null = not a message/open command at
     * all (route normally). Non-null with missing slots = clarify.
     */
    fun parseMessage(t: String): MsgIntent? {
        val msgVerb = MSG_VERBS.firstOrNull { hasWord(t, it) }
        val app = findApp(t)
        val openVerb = hasOpenVerb(t)
        if (msgVerb == null && !(openVerb && app != null)) return null
        if (msgVerb == null) return MsgIntent("open", app, null, null)

        // recipient: after-verb ("message beru xxx") or before-verb ("beru ko message karo")
        val words = t.split(" ").filter { it.isNotBlank() }
        val vi = words.indexOfFirst { it == msgVerb }
        var recipient: String? = null
        var message: String? = null
        if (vi >= 0) {
            val after = words.drop(vi + 1).filter { it !in FILLER }
            val stop = AND_WORDS + MSG_VERBS + OPEN_VERBS + APP_ALIASES.values.flatten()
            // 1) name before the verb ("beru ko maisej karo hello")
            val before = words.take(vi).filter { it !in FILLER }
            val rev = before.reversed().takeWhile { it !in stop }.reversed().toList()
            if (rev.isNotEmpty() && rev.all { it.all { c -> c.isLetter() } }) {
                recipient = sanitizeName(rev.joinToString(" "))
                val rest = after.filter { it !in AND_WORDS }
                if (rest.isNotEmpty()) message = rest.joinToString(" ")
            } else {
                // 2) name right after the verb ("maisej beru xxx"):
                // FIRST name-like token only — the rest is message text,
                // never part of the name
                val first = after.firstOrNull()
                if (first != null && first !in stop && first.all { c -> c.isLetter() }) {
                    recipient = sanitizeName(first)
                    val rest = after.drop(1).filter { it !in AND_WORDS }
                    if (rest.isNotEmpty()) message = rest.joinToString(" ")
                }
            }
        }
        return MsgIntent("message", app, recipient, message?.ifBlank { null })
    }

    /** Language-aware clarification for the first missing slot. */
    fun clarify(intent: MsgIntent, hindi: Boolean): String {
        val appName = intent.app?.replaceFirstChar { it.uppercase() } ?: "app"
        val who = (intent.recipient ?: "them").replaceFirstChar { it.uppercase() }
        return when (intent.missing().firstOrNull()) {
            "app" -> if (hindi) "Kaunsa app kholu?" else "Which app should I open?"
            "recipient" -> if (hindi) "$appName me kise message karu?"
            else "Who should I message on $appName?"
            else -> if (hindi) "$who ko kya message bheju?"
            else "What should I tell $who?"
        }
    }
}
