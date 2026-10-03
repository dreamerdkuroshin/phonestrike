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
    // "opan"/"oph" are faithful renderings of Devanagari ओपन/ऑफ़ (no written e)
    private val OPEN_VERBS = listOf("open", "opan", "oph", "launch", "start", "khol", "kholo", "kholna")
    private val MSG_VERBS = listOf(
        "message", "msg", "maisej", "mesej", "mesij", "send", "text",
        "bhej", "bhejo", "bhejna", "likh", "likho"
    )
    private val AND_WORDS = listOf("and", "aur", "ane", "then", "tatha", "tato", "ki", "ke")
    private val FILLER = setOf(
        "ko", "ke", "ne", "to", "please", "kripya", "kro", "karo", "kar",
        "the", "a", "an", "me", "mein", "par", "pe", "per", "se", "ki", "ke"
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

    /** Edit distance for fuzzy keyword hits on noisy transcripts. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) { 0 } }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(
                dp[i - 1][j] + 1, dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return dp[a.length][b.length]
    }

    private val FUZZY_BANK = OPEN_VERBS + MSG_VERBS + APP_ALIASES.values.flatten()

    /** Any word close to a known keyword. Thresholds scale with length so
     *  short words ("today"~"opan" = 3) can't false-fire while mangled long
     *  words ("maich"~"maisej" = 3) still count: only len>=5 anchors are
     *  fuzzy-matched (short verbs already match exactly). One hit suffices —
     *  recovery validates strictly afterwards, so a false hit only costs one
     *  local LLM call that returns {"action":"none"}. */
    fun fuzzyHit(t: String): Boolean {
        for (w in t.split(" ").filter { it.length >= 5 && it.all { c -> c.isLetter() } }) {
            for (k in FUZZY_BANK) {
                if (k.length < 5 || " " in k) continue
                if (distance(w, k) <= 3) return true
            }
        }
        return false
    }

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
            // recipient search zones in priority order: before the app
            // ("tushar ko whatsapp…"), between app and verb ("…whatsapp…
            // berune mesej"), and only then right after the verb.
            // Message words sitting before the verb ("…par hi bhejo") must
            // never be eaten as the name.
            val ai = words.indexOfFirst { w -> APP_ALIASES.values.flatten().any { it == w } }
            val zones = mutableListOf<List<String>>()
            if (ai in 0 until vi) {
                zones.add(words.take(ai))
                zones.add(words.drop(ai + 1).take(vi - ai - 1))
            } else {
                zones.add(words.take(vi))
            }
            fun runOf(list: List<String>): List<String> {
                val f = list.filter { it !in FILLER }
                return f.reversed()
                    .takeWhile { it !in stop && it.all { c -> c.isLetter() } }
                    .reversed().toList()
            }
            var nameParts: List<String> = emptyList()
            for (z in zones) {
                val run = runOf(z)
                // a lone 1-2 letter token ("hi", "ok") is message text,
                // not a name — the message pool below recovers it
                if (run.size == 1 && run[0].length <= 2) continue
                nameParts = run
                if (nameParts.isNotEmpty()) break
            }
            if (nameParts.isEmpty()) {
                // name right after the verb ("maisej beru xxx"):
                // FIRST name-like token only — the rest is message text,
                // never part of the name (same 1-2 letter guard)
                val first = after.firstOrNull()
                if (first != null && first !in stop && first.all { c -> c.isLetter() } &&
                    first.length > 2
                ) {
                    nameParts = listOf(first)
                }
            }
            if (nameParts.isNotEmpty()) recipient = sanitizeName(nameParts.joinToString(" "))
            // message = pre-verb leftovers + post-verb tail, minus everything
            // already consumed (verbs, app, fillers, the recipient itself)
            val skipMsg = (AND_WORDS + MSG_VERBS + OPEN_VERBS +
                APP_ALIASES.values.flatten() + FILLER + nameParts).toSet()
            val preMsg = words.take(vi).filter { it !in skipMsg }
            val postMsg = after.filter { it !in AND_WORDS && it !in nameParts }
            val combined = (preMsg + postMsg).filter { it.isNotBlank() }
            if (combined.isNotEmpty()) message = combined.joinToString(" ")
        }
        return MsgIntent("message", app, recipient, message?.ifBlank { null })
    }

    /**
     * Validate one LLM recovery-JSON blob. Strict gates: action must be
     * message|open (else null = not a command), app must be a KNOWN
     * canonical alias (else null — the LLM can never invent an action).
     * Pure function, unit-tested with canned model outputs.
     */
    fun parseRecoveryJson(resp: String): MsgIntent? {
        val json = Regex("\\{[^}]*\\}").find(resp)?.value ?: return null
        fun field(name: String): String? {
            val v = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
            return if (v.isNullOrBlank() || v == "?") null else v
        }
        val action = field("action") ?: return null
        if (action != "message" && action != "open") return null
        val app = field("app")?.lowercase()?.let { a ->
            APP_ALIASES.keys.firstOrNull { k -> k == a || APP_ALIASES[k]?.contains(a) == true }
        }
        if (action == "open" && app == null) return null
        val recipient = field("recipient")?.let { sanitizeName(it) }
        val message = field("message")
        return MsgIntent(action, app, recipient, message)
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

// touch to force recompile
