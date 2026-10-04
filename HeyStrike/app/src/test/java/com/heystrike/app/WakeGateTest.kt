package com.heystrike.app

import org.junit.Assert.*
import org.junit.Test

class WakeGateTest {

    @Test
    fun singleBlipNeverFires() {
        val g = WakeGate()
        assertFalse(g.update(true, 0))
        assertFalse(g.update(false, 128))
        assertFalse(g.update(true, 256))
        assertFalse(g.update(false, 384))
    }

    @Test
    fun twoConsecutiveFireOnce() {
        val g = WakeGate()
        assertFalse(g.update(true, 0))
        assertTrue(g.update(true, 128))
        // sustained hold without a clean frame: no repeat fire
        assertFalse(g.update(true, 256))
        assertFalse(g.update(true, 384))
        assertFalse(g.update(true, 10_000))
    }

    @Test
    fun rearmNeedsCleanFrame() {
        val g = WakeGate()
        g.update(true, 0)
        assertTrue(g.update(true, 128))
        assertFalse(g.update(true, 256)) // still holding: silent
        assertFalse(g.update(false, 384)) // clean frame re-arms
        assertFalse(g.update(true, 512))
        assertTrue(g.update(true, 640)) // new onset fires
    }

    @Test
    fun stormSuppressesThenRecovers() {
        val g = WakeGate(stormMax = 3, suppressMs = 60_000)
        var t = 0L
        repeat(3) {
            g.update(true, t); assertTrue(g.update(true, t + 128))
            g.update(false, t + 256) // gap re-arms: simulates repeated bursts
            t += 5_000
        }
        // 4th burst inside the storm window: suppressed
        assertFalse(g.update(true, t))
        assertFalse(g.update(true, t + 128))
        assertTrue(g.isSuppressed(t + 200))
        assertFalse(g.isSuppressed(t + 61_000))
        // after suppression expires, a fresh onset fires again
        assertFalse(g.update(false, t + 62_000))
        assertFalse(g.update(true, t + 62_128))
        assertTrue(g.update(true, t + 62_256))
    }
}
