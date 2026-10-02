package com.heystrike.app

/**
 * Correction follow-ups: "actually telegram", "no, open gmail",
 * "open youtube instead". Strips the correction wrapper so the re-routed
 * command re-enters the normal pipeline (open / contact / LLM).
 */
object Correction {

    private val prefix = Regex(
        "^(actually|in fact|i mean|no,?|nope,?|wait,?|scratch that|instead)[,\\s]+"
    )
    private val suffix = Regex("[,\\s]+(instead|instead of that|not that)[.!?]*$")

    /** null = not a correction; otherwise the cleaned re-route target. */
    fun strip(raw: String): String? {
        var s = raw.trim().replace(Regex("\\s+"), " ")
        val before = s
        s = prefix.replaceFirst(s, "").trim()
        s = suffix.replaceFirst(s, "").trim().trim('!', '?', '.', ',')
        if (s.isEmpty() || s == before) return null
        return s
    }
}
