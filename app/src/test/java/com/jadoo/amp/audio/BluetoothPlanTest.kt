package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth band plan. Bands must stay ascending and real for every
 * feature combination, and every boost must be gone at its zone's hot level
 * so that nothing has to be trimmed for it.
 */
class BluetoothPlanTest {
    private val engine = DspEngine()
    private val flat = FloatArray(EqBands.count)
    private val voicing = AnalogBassVoicing()

    private fun plan(
        hiRes: Boolean = false, dbfb: DbfbMode = DbfbMode.Off, analog: Boolean = false, exciter: Boolean = false,
        pre: FloatArray = flat, crest: Float? = null, pure: Boolean = false, v: AnalogBassVoicing = voicing
    ) = engine.planBluetoothMbc(hiRes, dbfb, analog, v, exciter, 0.5f, 1f, 1f, pre, pre, crest, pure)

    /** A compressor's gain at [level] dBFS, knee ignored: the plan's hot levels sit above the knee. */
    private fun netDb(band: MbcBandSpec, level: Float): Float {
        val reduction = if (band.ratio > 1f && level > band.threshold) (1f / band.ratio - 1f) * (level - band.threshold) else 0f
        return band.postGain + reduction
    }

    @Test
    fun everyCombinationIsAscendingAndOwnsItsBins() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val block = DpResponse.blockSize(rate)
            for (hiRes in listOf(false, true)) for (dbfb in DbfbMode.values()) for (analog in listOf(false, true))
                for (exciter in listOf(false, true)) for (crest in listOf(null, SbcEngine.crestBandCutoffHz(rate.toFloat()))) {
                    val bands = plan(hiRes, dbfb, analog, exciter, crest = crest)
                    var next = 0
                    bands.forEach { b ->
                        val stop = (0.5f + b.cutoffFrequency * block / rate).toInt()
                        assertTrue("${b.cutoffFrequency} Hz has no bins at $rate", stop >= next)
                        next = stop + 1
                    }
                    assertEquals(20_000f, bands.last().cutoffFrequency, 0.01f)
                }
        }
    }

    @Test
    fun analogBassAndDbfbNowShareTheBassZones() {
        val both = plan(analog = true, dbfb = DbfbMode.High)
        val analog = plan(analog = true)
        assertEquals(analog.map { it.cutoffFrequency }, both.map { it.cutoffFrequency })
        assertTrue(both[0].postGain > analog[0].postGain)
    }

    @Test
    fun bassBoostIsGoneAtTheHotLevel() {
        val pre = FloatArray(EqBands.count).also { for (i in 0..2) it[i] = 5f }
        val band = plan(pre = pre)[0]
        // The compressor reads after the PreEQ, the boost is PreEQ + post-gain.
        val hot = DspEngine.BT_ZONE_HOT_DB[0] + 5f
        assertEquals(-5f, netDb(band, hot), 0.05f)
    }

    @Test
    fun boostIsWholeBelowItsSpan() {
        val pre = FloatArray(EqBands.count).also { for (i in 0..2) it[i] = 3f }
        val band = plan(pre = pre)[0]
        val belowSpan = DspEngine.BT_ZONE_HOT_DB[0] + 3f - DspEngine.BT_BOOST_SPAN_DB - 1f
        assertEquals(band.postGain, netDb(band, belowSpan), 0.001f)
    }

    @Test
    fun aSmallBoostBarelyTouchesAHit() {
        val small = plan(dbfb = DbfbMode.Normal)[0]
        val big = plan(pre = FloatArray(EqBands.count) { if (it < 3) 8f else 0f })[0]
        // DBFB Normal's +1.5 dB and the lift over the fixed 6 dB span.
        assertEquals(1.71f, small.ratio, 0.01f)
        assertTrue(big.ratio <= DspEngine.BT_BOOST_MAX_RATIO + 0.001f)
    }

    @Test
    fun nothingCompressesUnderACut() {
        val cut = FloatArray(EqBands.count) { -6f }
        // The lift is smaller than the cut everywhere but the vocal zone.
        plan(pre = cut).forEachIndexed { i, b ->
            if (i != DspEngine.BT_VOCAL_ZONE) assertEquals(1f, b.ratio, 0.001f)
        }
    }

    @Test
    fun liftReachesEveryZoneButHdrPureStaysTransparent() {
        plan().forEach { assertEquals(DspEngine.BT_LEVEL_LIFT_DB, it.postGain, 0.001f) }
        plan(pure = true).forEach {
            assertEquals(0f, it.postGain, 0.001f)
            assertEquals(1f, it.ratio, 0.001f)
        }
    }

    @Test
    fun sbcCrestKeepsItsFixedCompressionAndNoMakeup() {
        val crest = 11_025f
        val top = plan(crest = crest).last()
        assertEquals(SbcEngine.CREST_RATIO, top.ratio, 0.001f)
        assertEquals(SbcEngine.CREST_THRESHOLD_DB, top.threshold, 0.001f)
        assertEquals(0f, top.postGain, 0.001f)
        assertTrue(plan(crest = crest).any { it.cutoffFrequency == crest })
    }

    @Test
    fun hiResAirStaysAboveFiveKilohertz() {
        val bands = plan(hiRes = true)
        val below = bands.filter { it.cutoffFrequency <= 5200f }
        below.forEach { assertEquals(DspEngine.BT_LEVEL_LIFT_DB, it.postGain, 0.001f) }
        assertEquals(5.5f + DspEngine.BT_LEVEL_LIFT_DB, bands.last().postGain, 0.001f)
    }

    @Test
    fun driveMakesAnalogBassHarder() {
        val soft = plan(analog = true, v = AnalogBassVoicing(drive = 0.2f))[1]
        val hard = plan(analog = true, v = AnalogBassVoicing(drive = 0.8f))[1]
        assertTrue(hard.threshold < soft.threshold)
    }

    @Test
    fun vocalZoneBoostIsTheOnlyEqThatTrims() {
        val smile = FloatArray(EqBands.count) { if (it < 6 || it > 9) 6f else 0f }
        assertEquals(0f, engine.bluetoothTrimDb(smile, smile), 0.001f)
        val vocal = FloatArray(EqBands.count) { if (it in 6..9) 3f else 0f }
        assertTrue(engine.bluetoothTrimDb(vocal, vocal) < 0f)
    }

    @Test
    fun volumeHeadroomStillCoversTheVocalTrim() {
        val vocal = FloatArray(EqBands.count) { if (it in 6..9) 3f else 0f }
        assertEquals(0f, engine.bluetoothTrimDb(vocal, vocal, volumeHeadroomDb = 6f), 0.001f)
    }

    private val wide = floatArrayOf(2.0f, 3.2f, 3.0f, 1.5f, 0.5f, 0f, 0f, -0.5f, 0f, 0f, 0f, 1.5f, 3.0f, 3.8f, 4.5f)

    private fun stack(surround: FloatArray = flat, hiRes: Boolean = false, dbfb: DbfbMode = DbfbMode.Off, analog: Boolean = false) =
        engine.bluetoothStackScale(surround, hiRes, dbfb, analog, voicing, 1f, 1f)

    @Test
    fun singleFeaturesAreNotScaled() {
        assertEquals(StackScale(), stack(surround = wide))
        assertEquals(StackScale(), stack(hiRes = true))
        assertEquals(StackScale(), stack(dbfb = DbfbMode.High))
        assertEquals(StackScale(), stack(analog = true))
    }

    @Test
    fun stacksAreScaledToTheCapsAndNoFurtherThanTheFloor() {
        // 4.5 + 5.5 at 16 kHz against the 7 dB cap, stepped down to 0.05.
        assertEquals(0.7f, stack(surround = wide, hiRes = true).treble, 0.001f)
        // 3.0 + 7.15 + 2.5 at 63 Hz against the 8 dB cap.
        assertEquals(0.6f, stack(surround = wide, dbfb = DbfbMode.High, analog = true).bass, 0.001f)
    }

    @Test
    fun runningOutOfTrebleCostsNoBass() {
        val customer = stack(surround = wide, hiRes = true, dbfb = DbfbMode.High)
        assertEquals(1f, customer.bass, 0.001f)
        assertTrue(customer.treble < 1f)
    }

    @Test
    fun sameAsIgnoresRoundingButNotAMove() {
        val a = plan(dbfb = DbfbMode.High)[0]
        assertTrue(a.sameAs(plan(dbfb = DbfbMode.High)[0]))
        assertFalse(a.sameAs(plan(dbfb = DbfbMode.Normal)[0]))
    }
}
