package com.heystrike.app

/**
 * Wake confirmation with re-arm + storm throttle. A sustained hallucination
 * (song holding "hey strike" partials for minutes) must fire AT MOST once
 * per clean-audio gap — never once per frame — and a wake storm (many
 * no-command fires per minute, i.e. background audio) suppresses
 * confirmation briefly instead of answering the TV all afternoon.
 *
 * Pure logic, JVM-tested. The gate loop feeds one update() per partial frame.
 */
class WakeGate(
    private val minStreak: Int = 2,
    private val stormWindowMs: Long = 60_000,
    private val stormMax: Int = 6,
    private val suppressMs: Long = 180_000
) {
    private var streak = 0
    private var armed = true
    private val fires = mutableListOf<Long>()
    private var suppressedUntil = 0L

    /**
     * Returns true when a CONFIRMED new wake fires. Re-arm requires at least
     * one clean (non-wake) frame — sustained audio fires once, then needs
     * silence-or-other-speech before it can fire again.
     */
    fun update(wake: Boolean, nowMs: Long): Boolean {
        if (wake) streak++ else {
            streak = 0
            armed = true
        }
        fires.removeAll { nowMs - it > stormWindowMs }
        if (nowMs < suppressedUntil) return false
        if (wake && streak >= minStreak && armed) {
            armed = false
            streak = 0
            fires.add(nowMs)
            if (fires.size >= stormMax) {
                // ponytail: 6/min then 3min quiet. A frustrated user repeating
                // the wake also trips this — mic tap always bypasses it.
                suppressedUntil = nowMs + suppressMs
                fires.clear()
            }
            return true
        }
        return false
    }

    fun isSuppressed(nowMs: Long): Boolean = nowMs < suppressedUntil
}
