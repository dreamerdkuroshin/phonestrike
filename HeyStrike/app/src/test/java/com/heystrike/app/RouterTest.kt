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

    @Test
    fun modelIntegrity() {
        val dir = createTempDir("model")
        try {
            // empty dir: not ready (would have native-crashed the gate before)
            assertFalse(ModelManager.verifyFiles(dir, ModelManager.voskRequired()))
            val amf = java.io.File(dir, "am/final.mdl").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(150_000))
            }
            // partial model (one file of three): still not ready
            assertFalse(ModelManager.verifyFiles(dir, ModelManager.voskRequired()))
            java.io.File(dir, "graph/HCLG.fst").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(150_000))
            }
            java.io.File(dir, "conf/model.conf").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(100))
            }
            assertTrue(ModelManager.verifyFiles(dir, ModelManager.voskRequired()))
            // truncated file: not ready
            amf.writeBytes(ByteArray(10))
            assertFalse(ModelManager.verifyFiles(dir, ModelManager.voskRequired()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hindiGraphLayout() {
        // small-hi ships Gr.fst + HCLr.fst and NO HCLG.fst (verified against
        // the real upstream zip) — demanding HCLG broke Hindi downloads forever
        val dir = createTempDir("modelhi")
        try {
            fun put(rel: String, n: Int) {
                java.io.File(dir, rel).apply {
                    parentFile!!.mkdirs()
                    writeBytes(ByteArray(n))
                }
            }
            put("am/final.mdl", 150_000)
            put("conf/model.conf", 100)
            put("graph/Gr.fst", 150_000)
            put("graph/HCLr.fst", 150_000)
            assertTrue(ModelManager.verifyFiles(dir, ModelManager.voskRequired("hi")))
            assertFalse(ModelManager.verifyFiles(dir, ModelManager.voskRequired("en")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
