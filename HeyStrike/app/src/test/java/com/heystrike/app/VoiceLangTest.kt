package com.heystrike.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Voice gap-loop regressions: the exact real-device failure
 * ("eyr strik open aur beru" decoded, nothing executed) plus the
 * spec's Hindi/Hinglish/Gujarati phrase set.
 */
class VoiceLangTest {

    @Test
    fun transliterateHindi() {
        // ओपन has no written 'e' — faithful form is "opan" (an OPEN_VERBS alias);
        // स्ट्राइक collapses to "straik" (a WakeMatcher core)
        assertEquals("eyar straik opan aur beru",
            Transliterate.normalize("एयर स्ट्राइक ओपन और बेरु"))
        assertEquals("he straik vhatsaip kholo aur beru ko maisej karo",
            Transliterate.normalize("हे स्ट्राइक व्हाट्सऐप खोलो और बेरु को मैसेज करो"))
        assertEquals("tum kaise ho", Transliterate.normalize("तुम कैसे हो"))
        // collapse turns हाँ into "han" — already a YES affirmative everywhere
        assertEquals("han", Transliterate.normalize("हाँ"))
    }

    @Test
    fun transliterateGujarati() {
        assertEquals("khol ane mesej", Transliterate.normalize("ખોલ અને મેસેજ"))
        assertEquals("beru", Transliterate.normalize("બેરુ"))
        // Latin passes through untouched
        assertEquals("open whatsapp 123", Transliterate.normalize("Open WhatsApp 123"))
    }

    @Test
    fun wakeVariants() {
        assertTrue(WakeMatcher.isWake("hey strike open whatsapp"))
        assertTrue(WakeMatcher.isWake("eyr strik open aur beru"))
        assertTrue(WakeMatcher.isWake("he strik vhatsaip kholo"))
        assertTrue(WakeMatcher.isWake("air strike message beru"))
        // "he strikes" (plural verb) must NOT fire
        assertFalse(WakeMatcher.isWake("he strikes first"))
        assertFalse(WakeMatcher.isWake("i was watching strike yesterday"))
        assertFalse(WakeMatcher.isWake(""))
        assertEquals("open aur beru", WakeMatcher.stripWake("eyr strik open aur beru"))
    }

    @Test
    fun intentEnglish() {
        val i = IntentParser.parseMessage("open whatsapp and message beru xxx")!!
        assertEquals("message", i.action)
        assertEquals("whatsapp", i.app)
        assertEquals("beru", i.recipient)
        assertEquals("xxx", i.message)
        assertTrue(i.complete)
    }

    @Test
    fun intentHindiAsrStyle() {
        // the exact observed transcript, normalized
        val t = Transliterate.normalize("एयर स्ट्राइक ओपन और बेरु")
        val wakeStripped = WakeMatcher.stripWake(t)
        assertEquals("opan aur beru", wakeStripped)
        // app slot empty -> open intent is null here; routing falls through
        // to the contact-probe + clarify path (needs Context, tested live)
        assertNull(IntentParser.parseMessage(wakeStripped))
        val io = IntentParser.parseMessage("opan whatsapp")!!
        assertEquals("open", io.action)
        assertEquals("whatsapp", io.app)
        assertTrue(io.complete)
        assertTrue("Kaunsa app" in IntentParser.clarify(
            IntentParser.MsgIntent("message", null, "beru", null), true))
    }

    @Test
    fun intentHindiFull() {
        val t = Transliterate.normalize("हे स्ट्राइक व्हाट्सऐप खोलो और बेरु को मैसेज करो hello")
        val i = IntentParser.parseMessage(WakeMatcher.stripWake(t))!!
        assertEquals("whatsapp", i.app)
        assertEquals("beru", i.recipient)
        assertEquals("hello", i.message)
        assertTrue(i.complete)
    }

    @Test
    fun intentGujaratiGluedPostposition() {
        val t = Transliterate.normalize("हे स्ट्राइक वॉट्सएप खोलो अने बेरुने मेसेज करो test")
        val i = IntentParser.parseMessage(WakeMatcher.stripWake(t))!!
        assertEquals("beru", i.recipient) // berune -> beru
        assertEquals("test", i.message)
        assertTrue(i.complete)
    }

    @Test
    fun intentOpenOnly() {
        val i = IntentParser.parseMessage("vhatsaip kholo")!!
        assertEquals("open", i.action)
        assertEquals("whatsapp", i.app)
        assertTrue(i.complete)
        assertNull(IntentParser.parseMessage("what is the weather"))
    }

    @Test
    fun clarifyHindi() {
        val q = IntentParser.clarify(
            IntentParser.MsgIntent("message", "whatsapp", "beru", null), true)
        assertTrue("Beru" in q)
        val q2 = IntentParser.clarify(
            IntentParser.MsgIntent("message", null, null, null), false)
        assertTrue("Which app" in q2)
    }

    @Test
    fun confirmHindi() {
        assertEquals(PendingConfirm.Answer.YES, PendingConfirm.classify("haan"))
        assertEquals(PendingConfirm.Answer.YES, PendingConfirm.classify("theek hai"))
        assertEquals(PendingConfirm.Answer.NO, PendingConfirm.classify("nahi"))
        assertEquals(PendingConfirm.Answer.NO, PendingConfirm.classify("mat karo"))
        assertEquals(PendingConfirm.Answer.YES, PendingConfirm.classify("yes"))
        assertEquals(PendingConfirm.Answer.OTHER, PendingConfirm.classify("beru"))
    }
}
