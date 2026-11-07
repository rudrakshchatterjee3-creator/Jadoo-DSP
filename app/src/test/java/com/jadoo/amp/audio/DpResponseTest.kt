package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DpResponseTest {
    private fun binsPerBand(block: Int, rate: Int): IntArray {
        val counts = IntArray(EqBands.count)
        var next = 0
        for (b in 0 until EqBands.count) {
            val stop = (0.5f + EqBands.cutoffFrequencies[b] * block / rate).toInt()
            counts[b] = maxOf(0, stop - next + 1)
            next = maxOf(next, stop + 1)
        }
        return counts
    }

    @Test
    fun frameRoundsTo2048AtCommonRates() {
        assertEquals(2048, DpResponse.blockSize(48_000))
        assertEquals(2048, DpResponse.blockSize(44_100))
    }

    @Test
    fun androidDefaultFrameStarvedTheBassBands() {
        // 10 ms at 48 kHz = 512: 63 Hz and 100 Hz get no bins at all.
        val counts = binsPerBand(512, 48_000)
        assertEquals(0, counts[2])
        assertEquals(0, counts[3])
    }

    @Test
    fun everyBandOwnsBinsAtTheChosenFrame() {
        for (rate in listOf(44_100, 48_000)) {
            val counts = binsPerBand(DpResponse.blockSize(rate), rate)
            assertTrue("$rate: ${counts.toList()}", counts.all { it >= 1 })
        }
    }

    @Test
    fun bandStepLandsOnItsOwnBins() {
        val block = 2048; val rate = 48_000
        val bins = FloatArray(block / 2 + 1)
        val gains = FloatArray(EqBands.count).also { it[3] = 6f }  // 100 Hz
        DpResponse.addBandSteps(bins, EqBands.count, { EqBands.cutoffFrequencies[it] }, { gains[it] }, { true }, block, rate)
        val r = DpResponse.sample(bins, floatArrayOf(100f, 1_000f), block, rate)
        assertEquals(6f, r[0], 0.5f)
        assertEquals(0f, r[1], 0.001f)
    }

    @Test
    fun disabledBandAddsNothing() {
        val bins = FloatArray(1025)
        DpResponse.addBandSteps(bins, 1, { 20_000f }, { 5f }, { false }, 2048, 48_000)
        assertTrue(bins.all { it == 0f })
    }

    @Test
    fun displayGridSpans20HzTo20kHz() {
        val f = DpResponse.DISPLAY_FREQUENCIES_HZ
        assertEquals(20f, f.first(), 0.01f)
        assertEquals(20_000f, f.last(), 1f)
    }
}
