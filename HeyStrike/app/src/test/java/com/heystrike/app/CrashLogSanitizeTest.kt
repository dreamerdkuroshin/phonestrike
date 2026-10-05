package com.heystrike.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for crash-log sanitization (P2-3) and permission helpers. */
class CrashLogSanitizeTest {

    @Test
    fun stripsAppPaths() {
        val s = CrashLog.sanitize("at foo(/data/data/com.heystrike.app/files/x.kt:10)")
        assertFalse(s.contains("/data/data/com.heystrike.app/"))
        assertTrue(s.contains("<app>/"))
    }

    @Test
    fun redactsKeyValues() {
        val s = CrashLog.sanitize("Caused by api_key=sk-abc123xyzQWE and token: ghp_abcdefgh1234")
        assertFalse(s.contains("sk-abc123xyzQWE"))
        assertFalse(s.contains("ghp_abcdefgh1234"))
    }

    @Test
    fun redactsBareTokens() {
        val s = CrashLog.sanitize("header sk-5f238e76072d7926-92f903-faf6b924xE sent")
        assertFalse(s.contains("sk-5f238e76072d7926"))
        assertTrue(s.contains("***KEY***"))
    }

    @Test
    fun keepsOrdinaryStacks() {
        val s = CrashLog.sanitize("java.lang.NullPointerException: at StrikeApi.handle(StrikeApi.kt:31)")
        assertEquals("java.lang.NullPointerException: at StrikeApi.handle(StrikeApi.kt:31)", s)
    }

    @Test
    fun promptThresholds() {
        assertTrue(AgentPermissions.shouldPrompt(AgentPermissions.Level.USER_CONFIRMED))
        assertTrue(AgentPermissions.shouldPrompt(AgentPermissions.Level.SENSITIVE))
        assertTrue(AgentPermissions.shouldPrompt(AgentPermissions.Level.CRITICAL))
        assertFalse(AgentPermissions.shouldPrompt(AgentPermissions.Level.READ_ONLY))
        assertFalse(AgentPermissions.shouldPrompt(AgentPermissions.Level.LOW_RISK))
    }

    @Test
    fun unknownToolsDefaultDeny() {
        assertEquals(
            AgentPermissions.Level.USER_CONFIRMED,
            AgentPermissions.levelFor("frobnicate", "anything")
        )
    }
}
