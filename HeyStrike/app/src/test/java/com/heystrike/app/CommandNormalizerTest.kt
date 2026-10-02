package com.heystrike.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic regression tests for the ASR normalizer (runs on JVM, no device). */
class CommandNormalizerTest {

    @Test
    fun wakePrefixIsStripped() {
        assertEquals("open whatsapp", CommandNormalizer.normalize("Hey Strike, open WhatsApp"))
        assertEquals("open youtube", CommandNormalizer.normalize("ok strike open youtube"))
    }

    @Test
    fun observedAsrGarbleIsFixed() {
        assertEquals(
            "open whatsapp and message beru",
            CommandNormalizer.normalize("open WhatsApp and history and massage Beru")
        )
    }

    @Test
    fun duplicateStutterIsDropped() {
        assertEquals("open youtube", CommandNormalizer.normalize("open open youtube"))
    }

    @Test
    fun bookingMassageIsNotRewritten() {
        assertEquals("book a massage tomorrow", CommandNormalizer.normalize("book a massage tomorrow"))
    }

    @Test
    fun ambiguousWhatsUpOnlyInsideAction() {
        assertEquals("open whatsapp", CommandNormalizer.normalize("open what's up"))
        assertEquals("what's up?", CommandNormalizer.normalize("what's up?"))
    }

    @Test
    fun innerApostrophesSurvive() {
        assertEquals("i'll call him in 10 minutes", CommandNormalizer.normalize("I'll call him in 10 minutes."))
    }
}
