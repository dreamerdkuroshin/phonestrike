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
    // whisper-tiny multilingual (sherpa-onnx offline) covers Gujarati.
    // UNVERIFIED on-device (needs the download + a Gujarati speaker test);
    // until then plan("gu") falls back to English and status() says so.
    const val EXPECTED_URL: String =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny"

    fun dir(c: Context): java.io.File = ModelManager.whisperDir(c)

    fun ready(c: Context): Boolean = ModelManager.whisperReady(c)

    /** Pure status text (JVM-tested); status() feeds it live readiness. */
    fun statusText(ready: Boolean): String = if (ready)
        "whisper-tiny multilingual ready (UNVERIFIED decode - needs Gujarati speaker test)"
    else "whisper-tiny not downloaded - Gujarati falls back to English ASR + LLM"

    fun status(c: Context): String = statusText(ready(c))
}
