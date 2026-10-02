package com.heystrike.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM regression tests for permission tiers, confirm voice words, corrections. */
class AgentPermissionsTest {

    // ---- spec 31/32 permission levels ----

    @Test
    fun sendAndDeleteTapsNeedConfirm() {
        assertEquals(AgentPermissions.Level.CONFIRM, AgentPermissions.levelFor("tap", "Send"))
        assertEquals(AgentPermissions.Level.CONFIRM, AgentPermissions.levelFor("tap", "send message"))
        assertEquals(AgentPermissions.Level.CONFIRM, AgentPermissions.levelFor("TAP", "Delete"))
        assertEquals(AgentPermissions.Level.CONFIRM, AgentPermissions.levelFor("tap", "Remove"))
    }

    @Test
    fun paymentsAreCritical() {
        assertEquals(AgentPermissions.Level.CRITICAL, AgentPermissions.levelFor("tap", "Pay now"))
        assertEquals(AgentPermissions.Level.CRITICAL, AgentPermissions.levelFor("tap", "Buy"))
        assertEquals(AgentPermissions.Level.CRITICAL, AgentPermissions.levelFor("tap", "Checkout"))
    }

    @Test
    fun wordBoundaryPreventsFalsePositives() {
        assertEquals(AgentPermissions.Level.SAFE, AgentPermissions.levelFor("tap", "sender"))
        assertEquals(AgentPermissions.Level.SAFE, AgentPermissions.levelFor("tap", "Settings"))
        assertEquals(AgentPermissions.Level.CONFIRM, AgentPermissions.levelFor("tap", "Send location"))
    }

    @Test
    fun typingDraftsIsSafe() {
        assertEquals(AgentPermissions.Level.SAFE, AgentPermissions.levelFor("type", "Send the report"))
        assertEquals(AgentPermissions.Level.SAFE, AgentPermissions.levelFor("enter", "Send"))
        assertEquals(AgentPermissions.Level.SAFE, AgentPermissions.levelFor("launch", "whatsapp"))
    }

    // ---- confirmation voice words ----

    @Test
    fun confirmVoiceWords() {
        assertEquals(PendingConfirm.Answer.YES, PendingConfirm.classify("yes"))
        assertEquals(PendingConfirm.Answer.YES, PendingConfirm.classify("Yes!"))
        assertEquals(PendingConfirm.Answer.YES, PendingConfirm.classify("yeah"))
        assertEquals(PendingConfirm.Answer.YES, PendingConfirm.classify("send it"))
        assertEquals(PendingConfirm.Answer.NO, PendingConfirm.classify("no"))
        assertEquals(PendingConfirm.Answer.NO, PendingConfirm.classify("never mind."))
        assertEquals(PendingConfirm.Answer.NO, PendingConfirm.classify("cancel"))
        assertEquals(PendingConfirm.Answer.OTHER, PendingConfirm.classify("what's the weather"))
    }

    // ---- correction re-routes ----

    @Test
    fun actuallyPrefixStrips() {
        assertEquals("telegram", Correction.strip("actually telegram"))
        assertEquals("open gmail", Correction.strip("no, open gmail"))
        assertEquals("open youtube", Correction.strip("i mean open youtube"))
    }

    @Test
    fun insteadSuffixStrips() {
        assertEquals("open youtube", Correction.strip("open youtube instead"))
        assertEquals("telegram", Correction.strip("telegram, instead"))
    }

    @Test
    fun plainCommandsAreNotCorrections() {
        assertNull(Correction.strip("hello there"))
        assertNull(Correction.strip("actually"))
        assertNull(Correction.strip("no"))
        assertNull(Correction.strip("open whatsapp and message beru"))
    }
}
