package com.heystrike.app

import org.junit.Assert.*
import org.junit.Test

class RouterTest {

    @Test
    fun scriptDetection() {
        // Gujarati block U+0A80–0AFF
        assertEquals(AsrRouter.Lang.GU, AsrRouter.scriptOf("તમે કેમ છો"))
        // Devanagari block U+0900–U+097F
        assertEquals(AsrRouter.Lang.HI, AsrRouter.scriptOf("तुम कैसे हो"))
        // Latin + numbers
        assertEquals(AsrRouter.Lang.EN, AsrRouter.scriptOf("open whatsapp 123"))
        assertEquals(AsrRouter.Lang.EN, AsrRouter.scriptOf(""))
        // mixed GU+HI resolves to the majority script
        assertEquals(AsrRouter.Lang.GU, AsrRouter.scriptOf("તમે કેમ हो"))
    }

    @Test
    fun planOrder() {
        assertEquals(listOf(AsrRouter.Lang.HI, AsrRouter.Lang.EN), AsrRouter.plan("hi"))
        // Gujarati falls back to English until a native model lands
        assertEquals(listOf(AsrRouter.Lang.GU, AsrRouter.Lang.EN), AsrRouter.plan("gu"))
        assertEquals(listOf(AsrRouter.Lang.EN), AsrRouter.plan("en"))
        assertEquals(listOf(AsrRouter.Lang.EN), AsrRouter.plan("xx"))
    }

    @Test
    fun guSlotHonest() {
        // no upstream artifact: never reports ready, says why
        assertNull(GuModel.EXPECTED_URL)
        assertTrue(GuModel.status().contains("no native Gujarati model"))
    }

    @Test
    fun shizukuRoutesLabeledPath() {
        val (viaS, r1) = ShizukuManager.run({ "priv" }, { "fb" }, available = true)
        val (viaF, r2) = ShizukuManager.run({ "priv" }, { "fb" }, available = false)
        assertEquals("shizuku" to "priv", viaS to r1)
        assertEquals("fallback" to "fb", viaF to r2)
        assertEquals(ShizukuManager.State.NOT_INSTALLED,
            ShizukuManager.State.valueOf("NOT_INSTALLED"))
    }
}
