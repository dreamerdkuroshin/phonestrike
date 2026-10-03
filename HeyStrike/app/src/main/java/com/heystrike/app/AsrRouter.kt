package com.heystrike.app

import android.content.Context

/**
 * ASR language routing. English + Hindi have bundled streaming models;
 * Gujarati has NO small native model upstream (verified 404 on
 * alphacephei + no small-gu on known tracks) — its slot exists so a future
 * model plugs in without touching callers. Gujarati in Latin script decodes
 * through English ASR + LLM understanding today.
 */
object AsrRouter {
    enum class Lang { EN, HI, GU }

    /** Script detection for mixed-speech logging and fallback choice. */
    fun scriptOf(text: String): Lang {
        var gu = 0
        var dev = 0
        for (ch in text) {
            when (ch.code) {
                in 0x0A80..0x0AFF -> gu++
                in 0x0900..0x097F -> dev++
            }
        }
        return when {
            gu > 0 && gu >= dev -> Lang.GU
            dev > 0 -> Lang.HI
            else -> Lang.EN
        }
    }

    /** Ordered ASR attempts for a language pref (first ready wins). */
    fun plan(pref: String): List<Lang> = when (pref) {
        "hi" -> listOf(Lang.HI, Lang.EN)
        "gu" -> listOf(Lang.GU, Lang.EN) // GU falls back until a model lands
        else -> listOf(Lang.EN)
    }

    fun modelReady(c: Context, lang: Lang): Boolean = when (lang) {
        Lang.EN -> ModelManager.ready(c)
        Lang.HI -> ModelManager.hiReady(c)
        Lang.GU -> GuModel.ready(c)
    }
}

/** Gujarati model slot: path reserved, no upstream artifact yet. */
object GuModel {
    // whisper-multilingual via sherpa-offline is the candidate path when the
    // device half (download + offline decode + RAM) is actually tested.
    // const disallows null: plain val documents the missing artifact.
    val EXPECTED_URL: String? = null

    fun dir(c: Context): java.io.File = java.io.File(c.filesDir, "models/small-gu")

    fun ready(c: Context): Boolean =
        EXPECTED_URL != null &&
            java.io.File(dir(c), "am/final.mdl").let { it.isFile && it.length() > 0 }

    fun status(): String =
        "no native Gujarati model upstream — Latin-script Gujarati via English ASR + LLM"
}
