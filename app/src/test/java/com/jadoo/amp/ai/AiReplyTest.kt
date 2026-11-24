package com.jadoo.amp.ai

import com.jadoo.amp.audio.Taste.Dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyTest {

    @Test
    fun readsACleanReply() {
        val r = AiReply.parse("""{"bass":2,"warmth":-1,"clarity":3,"air":0,"stereo":true,"summary":"More punch, clearer voices."}""")!!
        assertEquals(2f, r.deltasDb[Dim.Bass.ordinal], 0f)
        assertEquals(-1f, r.deltasDb[Dim.Warmth.ordinal], 0f)
        assertEquals(3f, r.deltasDb[Dim.Clarity.ordinal], 0f)
        assertTrue(r.stereo)
        assertEquals("More punch, clearer voices.", r.summary)
        assertEquals(listOf(Dim.Bass to 1, Dim.Warmth to -1, Dim.Clarity to 1), r.understood)
    }

    @Test
    fun toleratesWhatASmallModelDoes() {
        val messy = """<think>user wants less harshness</think>
            Sure! Here is the JSON: {"bass": "1", "warmth":0, "clarity": -7, "air":-2.0, "summary":"Smoother \"top\"."} Hope that helps."""
        val r = AiReply.parse(messy)!!
        assertEquals(1f, r.deltasDb[Dim.Bass.ordinal], 0f)
        assertEquals(-3f, r.deltasDb[Dim.Clarity.ordinal], 0f)   // clamped
        assertEquals(-2f, r.deltasDb[Dim.Air.ordinal], 0f)
        assertFalse(r.stereo)
        assertEquals("Smoother \"top\".", r.summary)
    }

    @Test
    fun missingKeysAreZeroAndNoKeysIsNothing() {
        val r = AiReply.parse("""{"air":2}""")!!
        assertEquals(0f, r.deltasDb[Dim.Bass.ordinal], 0f)
        assertEquals(2f, r.deltasDb[Dim.Air.ordinal], 0f)
        assertEquals("", r.summary)
        assertNull(AiReply.parse("I don't know"))
        assertNull(AiReply.parse(""))
    }

    @Test
    fun examplesAreValidReplies() {
        AiReply.EXAMPLES.forEach { (_, answer) -> assertTrue(answer, AiReply.parse(answer) != null) }
    }
}
