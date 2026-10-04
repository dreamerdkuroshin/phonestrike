package com.heystrike.app

/**
 * Authoritative voice-session authorization. The mic stays on for passive
 * wake detection, but NOTHING downstream (command ASR beyond the wake
 * detector, LLM, agent, tools, TTS answers to voice turns) runs without a
 * valid authorization: a confirmed wake event, an explicit gesture
 * (mic tap / assistant key), or typed input. Counter + structured log per
 * turn make "answered without wake" provable instead of anecdotal.
 *
 * Note on §8-style purity: the wake detector is a constrained-grammar Vosk
 * pass (tiny model, wake words only), not full ASR — full command decoding
 * starts only after authorize(). That IS the small-detector-then-full-ASR
 * shape, sharing one mic stream.
 */
object VoiceAuth {
    enum class Source { NONE, WAKE, GESTURE, TYPED }

    @Volatile
    var source: Source = Source.NONE
        private set

    // process-lifetime counters (§15 acceptance metrics)
    @Volatile var wakeTriggers = 0; private set
    @Volatile var junkDropped = 0; private set
    @Volatile var commandsAuthorized = 0; private set
    @Volatile var recoveryRuns = 0; private set

    // ponytail: no Log here — this object is JVM-unit-tested (android.util.Log
    // crashes unit tests); callers log snapshot() on the hot paths.
    fun authorize(s: Source) {
        source = s
        if (s == Source.WAKE) wakeTriggers++
        if (s != Source.NONE) commandsAuthorized++
    }

    fun revoke() {
        source = Source.NONE
    }

    val authorized: Boolean get() = source != Source.NONE

    fun noteJunkDropped() {
        junkDropped++
    }

    fun noteRecovery() {
        recoveryRuns++
    }

    fun snapshot(): String =
        "auth=$source wakes=$wakeTriggers junk=$junkDropped cmd=$commandsAuthorized rec=$recoveryRuns"

    /** Test seam: reset without logging. */
    fun resetForTest() {
        source = Source.NONE
        wakeTriggers = 0
        junkDropped = 0
        commandsAuthorized = 0
        recoveryRuns = 0
    }
}
