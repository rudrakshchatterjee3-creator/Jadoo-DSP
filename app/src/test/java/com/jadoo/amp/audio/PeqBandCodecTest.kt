package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeqBandCodecTest {
    @Test
    fun oldEightBandProfileMigratesWithoutChangingItsBands() {
        val legacy = (0 until 8).joinToString("|") { index ->
            "Peak,${100f * (index + 1)},${index - 4}.0,1.5,true"
        }

        val decoded = PeqBandCodec.decode(legacy)

        assertEquals(16, decoded.size)
        assertEquals(100f, decoded[0].frequencyHz)
        assertEquals(800f, decoded[7].frequencyHz)
        assertTrue(decoded.take(8).all { it.enabled })
        assertTrue(decoded.drop(8).all { !it.enabled })
        assertEquals(1_000f, decoded[8].frequencyHz)
        assertEquals(20_000f, decoded[15].frequencyHz)
    }

    @Test
    fun sixteenBandRoundTripPreservesValues() {
        val source = List(DigitalFilterEngine.MAX_BANDS) { index ->
            DigitalFilterEngine.defaultBand(index).copy(
                enabled = index % 2 == 0,
                type = if (index == 3) DigitalFilterEngine.FilterType.Notch
                    else DigitalFilterEngine.FilterType.Peak,
                gainDb = (index - 8).coerceIn(-15, 15).toFloat(),
                q = 0.5f + index / 10f
            )
        }

        val decoded = PeqBandCodec.decode(PeqBandCodec.encode(source))

        assertEquals(source, decoded)
    }

    @Test
    fun blankAndMalformedProfilesProduceSafeCompleteBank() {
        val blank = PeqBandCodec.decode("")
        val malformed = PeqBandCodec.decode("Peak,NaN,Infinity,NaN,true|broken")

        assertEquals(16, blank.size)
        assertTrue(blank.all { !it.enabled })
        assertEquals(16, malformed.size)
        assertTrue(malformed.all { it.frequencyHz.isFinite() && it.gainDb.isFinite() && it.q.isFinite() })
        assertFalse(malformed[1].enabled)
    }
}
