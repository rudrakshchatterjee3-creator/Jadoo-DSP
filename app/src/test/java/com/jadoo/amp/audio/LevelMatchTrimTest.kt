package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMatchTrimTest {
    private val engine = DspEngine()
    private val flat = FloatArray(EqBands.count)

    private fun trim(left: FloatArray, right: FloatArray = left, stage: FloatArray = flat, headroom: Float = 0f) =
        engine.levelMatchTrimDb(left, right, stage, headroom)

    @Test
    fun flatCurveNeedsNoTrim() {
        assertEquals(0f, trim(flat), 0.001f)
    }

    @Test
    fun broadbandBoostIsTakenBackInFull() {
        assertEquals(-3f, trim(FloatArray(EqBands.count) { 3f }), 0.05f)
    }

    @Test
    fun cutsNeverEarnGainBack() {
        assertEquals(0f, trim(FloatArray(EqBands.count) { -6f }), 0.001f)
    }

    @Test
    fun airShelfCostsFarLessThanItsPeak() {
        // HiRes-style +5.5 dB in the top band: a classic preamp would trim
        // 5.5 dB of everything; real music has little energy up there.
        val air = FloatArray(EqBands.count).also { it[14] = 5.5f }
        val t = trim(air)
        assertTrue("trim $t", t < 0f && t > -0.5f)
    }

    @Test
    fun bassBoostIsWeightedHeavierThanTreble() {
        val bass = FloatArray(EqBands.count).also { it[2] = 6f }
        val treble = FloatArray(EqBands.count).also { it[12] = 6f }
        assertTrue(trim(bass) < trim(treble))
    }

    @Test
    fun stageBoostsCountLikePreEqBoosts() {
        val pre = FloatArray(EqBands.count).also { it[3] = 4f }
        assertEquals(trim(pre), trim(flat, flat, pre), 0.001f)
    }

    @Test
    fun stereoSplitIsAveragedAcrossChannels() {
        val l = FloatArray(EqBands.count).also { it[13] = 3f }
        val r = FloatArray(EqBands.count).also { it[13] = -3f }
        assertTrue(trim(l, r) <= 0f)
    }

    @Test
    fun volumeHeadroomCoversTheBoostOnWiredRoutes() {
        // Half volume on a wired route: the stream already sits ~12 dB down.
        val boost = FloatArray(EqBands.count) { 4f }
        assertEquals(0f, trim(boost, headroom = 12f), 0.001f)
    }

    @Test
    fun partialHeadroomLeavesPartialTrim() {
        val boost = FloatArray(EqBands.count) { 4f }
        assertEquals(-1.5f, trim(boost, headroom = 2.5f), 0.05f)
    }

    @Test
    fun bluetoothFullScaleGetsFullTrim() {
        val boost = FloatArray(EqBands.count) { 4f }
        assertEquals(trim(boost), trim(boost, headroom = 0f), 0.001f)
        assertEquals(-4f, trim(boost), 0.05f)
    }
}
