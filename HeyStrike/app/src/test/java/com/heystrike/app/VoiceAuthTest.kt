package com.heystrike.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Voice authorization state machine: only WAKE/GESTURE/TYPED turns run
 * anything downstream. Regression anchor for "answered without wake".
 */
class VoiceAuthTest {

    @Test
    fun authorizeRevokeCycle() {
        VoiceAuth.resetForTest()
        assertFalse(VoiceAuth.authorized)
        VoiceAuth.authorize(VoiceAuth.Source.WAKE)
        assertTrue(VoiceAuth.authorized)
        assertEquals(1, VoiceAuth.wakeTriggers)
        assertEquals(1, VoiceAuth.commandsAuthorized)
        VoiceAuth.revoke()
        assertFalse(VoiceAuth.authorized)
        VoiceAuth.resetForTest()
    }

    @Test
    fun gestureAndTyped() {
        VoiceAuth.resetForTest()
        VoiceAuth.authorize(VoiceAuth.Source.GESTURE)
        assertTrue(VoiceAuth.authorized)
        assertEquals(0, VoiceAuth.wakeTriggers) // not a wake
        assertEquals(1, VoiceAuth.commandsAuthorized)
        VoiceAuth.authorize(VoiceAuth.Source.TYPED)
        assertEquals(2, VoiceAuth.commandsAuthorized)
        VoiceAuth.resetForTest()
    }

    @Test
    fun counters() {
        VoiceAuth.resetForTest()
        VoiceAuth.noteJunkDropped()
        VoiceAuth.noteJunkDropped()
        VoiceAuth.noteRecovery()
        assertEquals(2, VoiceAuth.junkDropped)
        assertEquals(1, VoiceAuth.recoveryRuns)
        val s = VoiceAuth.snapshot()
        assertTrue("junk=2" in s && "rec=1" in s)
        VoiceAuth.resetForTest()
    }

    @Test
    fun wakeThresholdSane() {
        // single-frame blips must never confirm (multi-frame rule)
        assertTrue(GateEngine.WAKE_MIN_STREAK >= 2)
    }
}
