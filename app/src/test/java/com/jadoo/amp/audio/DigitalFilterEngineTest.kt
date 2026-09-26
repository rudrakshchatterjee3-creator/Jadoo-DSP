package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalFilterEngineTest {
    private fun state(
        type: DigitalFilterEngine.FilterType,
        frequency: Float = 1_000f,
        gain: Float = 0f,
        q: Float = 1f,
        enabled: Boolean = true
    ) = DigitalFilterEngine.BiquadBandState(0, type, frequency, gain, q, enabled)

    @Test
    fun defaultBankContainsSixteenUsefulFrequencies() {
        assertEquals(16, DigitalFilterEngine.MAX_BANDS)
        assertEquals(16, DigitalFilterEngine.DEFAULT_FREQUENCIES_HZ.size)
        assertEquals(16, DigitalFilterEngine.DEFAULT_FREQUENCIES_HZ.distinct().size)
        assertEquals(25f, DigitalFilterEngine.defaultBand(0).frequencyHz)
        assertEquals(20_000f, DigitalFilterEngine.defaultBand(15).frequencyHz)
    }

    @Test
    fun peakGainMatchesRequestedGainAtCenter() {
        val response = DigitalFilterEngine.evaluateBandMagnitudeResponseDb(
            state(DigitalFilterEngine.FilterType.Peak, gain = 6f, q = 2f),
            1_000f
        )
        assertEquals(6f, response, 0.05f)
    }

    @Test
    fun notchIsDeepAtCenterAndBandPassIsFinite() {
        val notch = DigitalFilterEngine.evaluateBandMagnitudeResponseDb(
            state(DigitalFilterEngine.FilterType.Notch, q = 8f),
            1_000f
        )
        val bandPass = DigitalFilterEngine.evaluateBandMagnitudeResponseDb(
            state(DigitalFilterEngine.FilterType.BandPass, q = 2f),
            1_000f
        )
        assertTrue("notch=$notch", notch < -40f)
        assertTrue(bandPass.isFinite())
    }

    @Test
    fun allPassHasFlatMagnitude() {
        val band = state(DigitalFilterEngine.FilterType.AllPass, q = 4f)
        listOf(30f, 500f, 1_000f, 8_000f, 18_000f).forEach { frequency ->
            assertEquals(
                "frequency=$frequency",
                0f,
                DigitalFilterEngine.evaluateBandMagnitudeResponseDb(band, frequency),
                0.01f
            )
        }
    }

    @Test
    fun importedNonFiniteValuesAreSanitized() {
        val sanitized = DigitalFilterEngine.sanitizeBand(
            DigitalFilterEngine.FilterBand(
                enabled = true,
                frequencyHz = Float.NaN,
                gainDb = Float.POSITIVE_INFINITY,
                q = Float.NaN
            ),
            index = 4
        )
        assertEquals(160f, sanitized.frequencyHz)
        assertEquals(0f, sanitized.gainDb)
        assertEquals(1f, sanitized.q)
    }
}
