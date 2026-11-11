package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboard curve is smoothed for display; it must stay faithful. */
class DpResponseSmoothingTest {

    private val freqs = DpResponse.DISPLAY_FREQUENCIES_HZ

    @Test
    fun flatCurveStaysFlatIncludingTheEdges() {
        val out = DpResponse.smoothed(FloatArray(freqs.size) { 3f }, freqs)
        for (v in out) assertEquals(3f, v, 0.001f)
    }

    @Test
    fun aStepBecomesASmoothMonotonicRamp() {
        val step = FloatArray(freqs.size) { if (freqs[it] < 1000f) 0f else 6f }
        val out = DpResponse.smoothed(step, freqs)
        for (i in 1 until out.size) assertTrue("monotonic at $i", out[i] >= out[i - 1] - 1e-4f)
        // Far from the edge it still reaches the plateaus; at the step it is halfway.
        assertEquals(0f, out[2], 0.05f)
        assertEquals(6f, out[freqs.size - 3], 0.05f)
        val below = freqs.indices.last { freqs[it] < 1000f }
        assertEquals(3f, (out[below] + out[below + 1]) / 2f, 0.3f)
    }

    @Test
    fun broadBumpsKeepMostOfTheirHeight() {
        // A one-octave-wide +6 dB bump around 1 kHz.
        val bump = FloatArray(freqs.size) { if (freqs[it] in 707f..1414f) 6f else 0f }
        val peak = DpResponse.smoothed(bump, freqs).max()
        assertTrue("peak $peak", peak > 4.5f)
    }

    @Test
    fun zeroWidthLeavesTheCurveUntouched() {
        val raw = FloatArray(freqs.size) { it % 7 - 3f }
        assertTrue(raw.contentEquals(DpResponse.smoothed(raw, freqs, 0f)))
    }
}
