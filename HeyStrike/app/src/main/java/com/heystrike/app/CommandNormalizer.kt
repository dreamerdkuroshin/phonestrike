package com.heystrike.app

/**
 * Reusable command normalization: deterministic fixes for the ASR errors the
 * LLM should never have to guess ("what's app" -> "whatsapp").
 *
 * Rules:
 *  - unambiguous phonetic spellings are fixed everywhere
 *  - ambiguous ones ("what's up", "what app") only after an action verb,
 *    so "what's up?" stays a normal conversation
 *  - wake-word prefix stripped, duplicates/punctuation/spacing cleaned
 */
object CommandNormalizer {

    private val wakePrefix = Regex("^(hey\\s*strike|hi\\s*strike|ok\\s*strike|strike)[,\\s]*")

    // phrase (already lowercased) -> canonical app name. Safe to apply always.
    private val unambiguous = listOf(
        "whats app" to "whatsapp", "what's app" to "whatsapp", "wats app" to "whatsapp",
        "watts app" to "whatsapp", "vats app" to "whatsapp", "vots app" to "whatsapp",
        "watsap" to "whatsapp", "whatsap" to "whatsapp",
        "you tube" to "youtube", "utube" to "youtube", "u tube" to "youtube",
        "you toob" to "youtube",
        "g mail" to "gmail", "google mail" to "gmail",
        "chorme" to "chrome", "crome" to "chrome", "chrom e" to "chrome",
        "insta gram" to "instagram", "instagam" to "instagram",
        "tele gram" to "telegram",
        "face book" to "facebook",
        "goo gle" to "google", "googol" to "google", "googal" to "google",
        "se things" to "settings", "celtings" to "settings",
        "spot i fy" to "spotify", "spotfy" to "spotify",
        // observed ASR garble of "open WhatsApp and message Beru" →
        // "open WhatsApp and history and massage Beru". Never real speech.
        "history and massage" to "and message",
        "mesage" to "message", "messge" to "message", "mess age" to "message"
    )

    // Only after an action verb (see ACTION): what's up / what app -> whatsapp
    private val actionVerb = Regex("\\b(open|launch|run|start|go to|find|kill|close|exit|play|search|get)\\b")
    private val ambiguousInAction = listOf(
        "what's up" to "whatsapp", "whats up" to "whatsapp", "what s up" to "whatsapp",
        "wats up" to "whatsapp", "what up" to "whatsapp", "what app" to "whatsapp",
        "what'sapp" to "whatsapp"
    )

    // contextual: ASR hears "message Beru" as "massage Beru". Never rewrite
    // genuine massage requests ("book a massage" survives untouched).
    private val bookingCue =
        Regex("\\b(book|booking|appointment|spa|therap\\w*|deep tissue|full body|relax\\w*|sore)\\b")
    private val messageCue =
        Regex("\\b(messages?|send\\w*|text|whatsapp|reply|forward|tell)\\b")
    private val massageNamePosition =
        Regex("(^|\\band\\s)massage\\s+[a-z]+$")

    /** Full command normalization (wake strip, aliases, cleanup). */
    fun normalize(raw: String): String {
        var s = raw.lowercase().trim().replace(wakePrefix, "")
        val question = s.trimEnd().endsWith("?")
        s = s.replace(Regex("\\s+"), " ").trim()

        s = applyPhrases(s, unambiguous)
        s = massageToMessage(s)

        // ambiguous fixes only inside an action context, never in questions
        if (!question && actionVerb.containsMatchIn(s)) {
            s = applyPhrases(s, ambiguousInAction)
        }

        // drop duplicate consecutive words (ASR stutter: "open open whatsapp")
        val parts = s.split(" ").filter { it.isNotEmpty() }
        s = parts.foldIndexed("") { i, acc, w ->
            if (i > 0 && parts[i - 1] == w) acc else (if (acc.isEmpty()) w else "$acc $w")
        }

        // strip sentence punctuation (keeps inner apostrophes)
        s = s.trim().trim('\'', '"', '‘', '’', '.', ',', '!', ';', ':', '“', '”')
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** App-name-only normalization (resolver input / ambiguity matching). */
    fun normalizeName(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace(Regex("\\s+"), " ")
        s = applyPhrases(s, unambiguous + ambiguousInAction)
        return s.trim().trim('.', ',', '!', '?', ';', ':')
    }

    private fun applyPhrases(s: String, list: List<Pair<String, String>>): String {
        var out = s
        for ((bad, good) in list) {
            out = out.replace(Regex("(?<![a-z'])" + Regex.escape(bad) + "(?![a-z'])"), good)
        }
        return out
    }

    /** "massage beru" -> "message beru", but "book a massage" is untouched. */
    private fun massageToMessage(s: String): String {
        if (!s.contains("massage")) return s
        if (bookingCue.containsMatchIn(s)) return s
        if (messageCue.containsMatchIn(s) || massageNamePosition.containsMatchIn(s)) {
            return s.replace("massage", "message")
        }
        return s
    }
}
