package com.heystrike.app

/**
 * Wake-word matcher on NORMALIZED (romanized, lowercased) text.
 * Accepts realistic ASR variants of "hey strike" — including the Hindi-model
 * rendering "air/eyar strike" seen on real F23 transcripts.
 * ponytail: "air strike" can false-fire on the military phrase; accepted
 * because the observed cost of missing real wakes is higher, and the gate
 * still needs a following command (a lone wake just shows the orb).
 */
object WakeMatcher {
    private val PREFIX = listOf("hey", "he", "hay", "air", "eyar", "eyr", "aay")
    // "straik" is what romanize+collapse yields for स्ट्राइक (raa+i -> rai)
    private val CORE = listOf("strike", "straik", "strick", "strik", "stike", "stryke", "shtrike")

    /** True if text contains "<prefix> <core>" with a word boundary after. */
    fun isWake(normalized: String): Boolean {
        val t = " " + normalized.lowercase().replace(Regex("[^a-z ]"), " ") + " "
        for (p in PREFIX) {
            for (c in CORE) {
                val needle = " $p $c"
                var i = t.indexOf(needle)
                while (i >= 0) {
                    val after = i + needle.length
                    if (after >= t.length || !t[after].isLetter()) return true
                    i = t.indexOf(needle, i + 1)
                }
            }
        }
        return false
    }

    /** Strip a leading wake phrase, returning the command remainder (may be blank). */
    fun stripWake(normalized: String): String {
        var s = " $normalized "
        for (p in PREFIX) {
            for (c in CORE) {
                s = s.replace(Regex(" $p $c(?![a-z])"), " ")
            }
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
