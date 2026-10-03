package com.heystrike.app

/**
 * Devanagari + Gujarati romanization for ASR transcripts (Hinglish-style).
 * The pipeline normalizes FIRST, then all matching (wake, verbs, entities)
 * runs on Latin text — one monolingual downstream instead of N scripts.
 *
 * Inherent-'a' rule: emitted only when the consonant is followed by MORE
 * word material (start/medial); word-final bare consonants drop it
 * (ओपन→open, not opena). Matra vowels are always explicit and kept.
 * Faithful but lossy by design: for intent routing, not display.
 */
object Transliterate {

    private val DEV_CONS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
    )
    private val DEV_CONJUNCT = mapOf("क्ष" to "ksh", "त्र" to "tr", "ज्ञ" to "gy")
    private val DEV_VOWEL = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee",
        'उ' to "u", 'ऊ' to "oo", 'ए' to "e", 'ऐ' to "ai",
        'ओ' to "o", 'औ' to "au", 'ऋ' to "ri"
    )
    private val DEV_MATRA = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
        'ं' to "n", 'ः' to "ah", 'ँ' to "n", 'ॉ' to "o", 'ृ' to "ri"
    )
    private val DEV_HALANT = setOf('्')

    // Gujarati block as \u escapes (source encoding mangled literals before)
    private val GUJ_CONS = mapOf(
        'ક' to "k", 'ખ' to "kh", 'ગ' to "g", 'ઘ' to "gh", 'ઙ' to "ng",
        'ચ' to "ch", 'છ' to "chh", 'જ' to "j", 'ઝ' to "jh", 'ઞ' to "ny",
        'ટ' to "t", 'ઠ' to "th", 'ડ' to "d", 'ઢ' to "dh", 'ણ' to "n",
        'ત' to "t", 'થ' to "th", 'દ' to "d", 'ધ' to "dh", 'ન' to "n",
        'પ' to "p", 'ફ' to "ph", 'બ' to "b", 'ભ' to "bh", 'મ' to "m",
        'ય' to "y", 'ર' to "r", 'લ' to "l", 'વ' to "v",
        'શ' to "sh", 'ષ' to "sh", 'સ' to "s", 'હ' to "h"
    )
    private val GUJ_CONJUNCT = mapOf("ક્ષ" to "ksh", "ત્ર" to "tr", "જ્ઞ" to "gn")
    private val GUJ_VOWEL = mapOf(
        'અ' to "a", 'આ' to "aa", 'ઇ' to "i", 'ઈ' to "ee",
        'ઉ' to "u", 'ઊ' to "oo", 'એ' to "e", 'ઐ' to "ai",
        'ઓ' to "o", 'ઔ' to "au", 
    )
    private val GUJ_MATRA = mapOf(
        'ા' to "aa", 'િ' to "i", 'ી' to "ee", 'ુ' to "u", 'ૂ' to "oo",
        'ે' to "e", 'ૈ' to "ai", 'ો' to "o", 'ૌ' to "au",
        'ં' to "n", 'ઃ' to "ah"
    )
    private val GUJ_HALANT = setOf('્')

    private val DIGITS = mapOf(
        '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
        '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9",
        '૦' to "0", '૧' to "1", '૨' to "2", '૩' to "3", '૪' to "4",
        '૫' to "5", '૬' to "6", '૭' to "7", '૮' to "8", '૯' to "9"
    )

    private fun isWordChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '\'' ||
            DEV_CONS[ch] != null || GUJ_CONS[ch] != null ||
            DEV_MATRA[ch] != null || GUJ_MATRA[ch] != null ||
            ch in DEV_HALANT || ch in GUJ_HALANT

    /** Romanize; Latin/anything else passes through untouched. */
    fun romanize(text: String): String {
        var s = text
        for ((k, v) in DEV_CONJUNCT + GUJ_CONJUNCT) s = s.replace(k, v)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val cons = DEV_CONS[ch] ?: GUJ_CONS[ch]
            if (cons != null) {
                val next = if (i + 1 < s.length) s[i + 1] else null
                val matra = if (next != null) (DEV_MATRA[next] ?: GUJ_MATRA[next]) else null
                when {
                    matra != null -> {
                        out.append(cons).append(matra)
                        i += 2
                    }
                    next != null && (next in DEV_HALANT || next in GUJ_HALANT) -> {
                        out.append(cons)
                        i += 2
                    }
                    next != null && isWordChar(next) -> {
                        // medial: inherent 'a' spoken (kamal, karo)
                        out.append(cons).append("a")
                        i += 1
                    }
                    else -> {
                        // word-final bare consonant: schwa dropped (open, votsaip)
                        out.append(cons)
                        i += 1
                    }
                }
                continue
            }
            val vow = DEV_VOWEL[ch] ?: GUJ_VOWEL[ch]
            if (vow != null) {
                out.append(vow)
                i += 1
                continue
            }
            // stray matra/halant without a base: append vowel part, skip halant
            val m = DEV_MATRA[ch] ?: GUJ_MATRA[ch]
            if (m != null) {
                out.append(m)
                i += 1
                continue
            }
            if (ch in DEV_HALANT || ch in GUJ_HALANT) {
                i += 1
                continue
            }
            val d = DIGITS[ch]
            if (d != null) {
                out.append(d)
                i += 1
                continue
            }
            out.append(ch)
            i += 1
        }
        return out.toString()
    }

    /**
     * Pipeline entry: romanize + lowercase + collapse long vowels
     * (aa→a, ee→i, oo→u: स्ट्राइक→strik, not straaik) + drop word-final
     * schwa (ओपन→open, not opena). The len>3 guard protects short words
     * (kya, na). Contact names hit by the strip retry the raw form.
     */
    fun normalize(text: String): String {
        var s = romanize(text).lowercase()
        s = s.replace("aa", "a").replace("ee", "i").replace("oo", "u")
        s = s.split(" ").joinToString(" ") { w ->
            if (w.length > 3 && w.endsWith("a")) w.dropLast(1) else w
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
