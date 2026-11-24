package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class HearPrintTest {
    private val n = HearPrint.TEST_FREQS_HZ.size

    /** Thresholds (dBFS) of a listener whose hearing departs from normal by [lossDb]. */
    private fun ear(base: Float = -60f, lossDb: FloatArray = FloatArray(n)) =
        FloatArray(n) { base + HearPrint.NORMAL_THRESHOLD_DB[it] + lossDb[it] }

    private fun profile(t: FloatArray) = HearPrint.Profile(t, 1f, 1L)

    @Test
    fun normalHearingGetsAFlatCurveAtAnyVolume() {
        for (base in listOf(-80f, -60f, -35f)) {
            HearPrint.compensationAtTestPoints(profile(ear(base)), 1f).forEach { assertEquals(0f, it, 1e-4f) }
        }
    }

    @Test
    fun halfGainWithCapsAndCuts() {
        val loss = FloatArray(n)
        loss[5] = 8f    // 4 kHz: 8 dB less -> +4
        loss[8] = 30f   // 8 kHz: far less -> capped at +6
        loss[10] = 30f  // 12 kHz: capped at +4
        loss[4] = -10f  // 3 kHz: unusually sensitive -> -5, capped at -3
        val c = HearPrint.rawCompensation(profile(ear(lossDb = loss)), HearPrint.DEFAULT_STRENGTH)
        assertEquals(4f, c[5], 1e-4f)
        assertEquals(6f, c[8], 1e-4f)
        assertEquals(4f, c[10], 1e-4f)
        assertEquals(-3f, c[4], 1e-4f)
        assertEquals(0f, c[2], 1e-4f)
    }

    @Test
    fun strengthScalesAndZeroIsFlat() {
        val loss = FloatArray(n).also { it[5] = 8f }
        val p = profile(ear(lossDb = loss))
        assertEquals(0f, HearPrint.rawCompensation(p, 0f)[5], 1e-4f)
        assertEquals(2f, HearPrint.rawCompensation(p, 0.25f)[5], 1e-4f)
        assertEquals(6f, HearPrint.rawCompensation(p, 1f)[5], 1e-4f)
    }

    @Test
    fun smoothingSoftensAnIsolatedSpikeButKeepsTheCaps() {
        val loss = FloatArray(n).also { it[5] = 12f; it[10] = 40f }  // 4 kHz and 12 kHz
        val raw = HearPrint.rawCompensation(profile(ear(lossDb = loss)), 1f)
        val c = HearPrint.compensationAtTestPoints(profile(ear(lossDb = loss)), 1f)
        assertEquals(6f, raw[5], 1e-4f)
        assertTrue("spike softened: ${c[5]}", c[5] < raw[5] && c[5] > 0.5f * raw[5])
        assertTrue("neighbours pick up a little", c[4] > 0f && c[6] > 0f)
        for (i in 0 until n) assertTrue("cap at $i", c[i] <= HearPrint.MAX_BOOST_DB[i] + 1e-4f && c[i] >= -HearPrint.MAX_CUT_DB - 1e-4f)
        assertTrue(c[10] <= 4f + 1e-4f)
    }

    @Test
    fun aFlatShapeStaysFlatUnderSmoothing() {
        val c = HearPrint.compensationAtTestPoints(profile(ear(lossDb = FloatArray(n) { 10f })), 1f)
        c.forEach { assertEquals(0f, it, 1e-4f) }
    }

    @Test
    fun neverCutsPresenceOrAir() {
        // Hears 3-8 kHz unusually well: the raw rule would cut there.
        val loss = FloatArray(n).also { for (i in 4..7) it[i] = -12f }
        val p = profile(ear(lossDb = loss))
        assertTrue(HearPrint.rawCompensation(p, 1f)[5] < 0f)
        val c = HearPrint.compensationAtTestPoints(p, 1f)
        for (i in 0 until n) if (HearPrint.TEST_FREQS_HZ[i] >= 1000f) assertTrue("no cut at $i: ${c[i]}", c[i] >= 0f)
    }

    @Test
    fun lowMidsNeverOutLiftTheTreble() {
        // Poor seal: 250 / 500 Hz heard far less, treble normal. Lifting the
        // low-mids alone is what makes a curve sound dark.
        val loss = FloatArray(n).also { it[0] = 20f; it[1] = 12f }
        val c = HearPrint.compensationAtTestPoints(profile(ear(lossDb = loss)), 1f)
        assertEquals(0f, c[0], 1e-4f)
        assertEquals(0f, c[1], 1e-4f)
        // With a treble lift the low-mids may follow, but only up to its average and their 2 dB cap.
        val both = FloatArray(n).also { it[0] = 20f; it[1] = 12f; for (i in 5..8) it[i] = 12f }
        val d = HearPrint.compensationAtTestPoints(profile(ear(lossDb = both)), 1f)
        assertTrue(d[0] <= 2f + 1e-4f && d[0] > 0f)
    }

    @Test
    fun anyProfileTiltsLevelOrBrighter() {
        val rnd = Random(7)
        repeat(500) {
            val loss = FloatArray(n) { (rnd.nextGaussian() * 10).toFloat() }
            val c = HearPrint.compensationAtTestPoints(profile(ear(lossDb = loss)), rnd.nextFloat())
            val low = (0 until n).filter { HearPrint.TEST_FREQS_HZ[it] < 1000f }.map { c[it] }.average()
            val high = (0 until n).filter { HearPrint.TEST_FREQS_HZ[it] >= 2000f }.map { c[it] }.average()
            assertTrue("tilt ${high - low}", high - low >= -1e-4)
            for (i in 0 until n) if (HearPrint.TEST_FREQS_HZ[i] >= 1000f) assertTrue(c[i] >= 0f)
        }
    }

    @Test
    fun autoEqShareIsNotCorrectedTwice() {
        // Headphone dips 6 dB at 8 kHz: the test hears it as 6 dB "less".
        val loss = FloatArray(n).also { it[7] = 6f }
        val p = profile(ear(lossDb = loss))
        assertEquals(3f, HearPrint.rawCompensation(p, 0.5f)[7], 1e-4f)
        // AutoEQ already lifts 8 kHz by 6 dB: nothing left for HearPrint to do there.
        val autoEq = FloatArray(n).also { it[7] = 6f }
        assertEquals(0f, HearPrint.rawCompensation(p, 0.5f, autoEq)[7], 1e-4f)
    }

    @Test
    fun bandMappingFadesBassAndHoldsTheTop() {
        val atTest = FloatArray(n) { it.toFloat() }  // 0 at 250 Hz … 10 at 12 kHz
        val bands = HearPrint.toBands(atTest)
        val f = EqBands.frequencies
        for (b in f.indices) if (f[b] <= 100f) assertEquals(0f, bands[b], 0f)
        assertEquals(2f, bands[f.indexOfFirst { it == 1000f }], 1e-4f)
        assertEquals(atTest.last(), bands[f.indexOfFirst { it == 16000f }], 1e-4f)
        val i16 = f.indexOfFirst { it == 1600f }  // between 1 k (2) and 2 k (3)
        assertTrue(bands[i16] > 2f && bands[i16] < 3f)
        val atTest2 = FloatArray(n).also { it[0] = 4f }
        val b160 = HearPrint.toBands(atTest2)[f.indexOfFirst { it == 160f }]
        assertTrue("160 Hz fades toward 0: $b160", b160 > 0f && b160 < 4f)
    }

    @Test
    fun noProfileOrIncompleteMeansZeros() {
        assertTrue(HearPrint.bandCurve(null, 0.5f).all { it == 0f })
        val partial = profile(ear().also { it[3] = Float.NaN })
        assertFalse(partial.complete)
        assertTrue(HearPrint.bandCurve(partial, 0.5f).all { it == 0f })
    }

    @Test
    fun codecRoundTripsAndRejectsGarbage() {
        val p = HearPrint.Profile(ear(lossDb = FloatArray(n) { 3f }), 2.5f, 1_700_000_000_000L)
        val back = HearPrint.decode(HearPrint.encode(p))!!
        for (i in 0 until n) assertEquals(p.thresholds[i], back.thresholds[i], 0.051f)
        assertEquals(2.5f, back.retestDb, 1e-4f)
        assertEquals(p.takenAtMillis, back.takenAtMillis)
        assertTrue(HearPrint.decode(HearPrint.encode(HearPrint.Profile(ear(), Float.NaN, 1L)))!!.retestDb.isNaN())
        assertEquals("", HearPrint.encode(null))
        assertNull(HearPrint.decode(""))
        assertNull(HearPrint.decode("v2|x|y"))
        assertNull(HearPrint.decode("v1|1|1|1|1,2|3,4"))
        assertNull(HearPrint.decode("v2|1|1|1,2,3"))
    }

    @Test
    fun oldNineToneResultsAreReadOntoTheNewGrid() {
        // v2: 250, 500, 1k, 2k, 3k, 4k, 6k, 8k, 12k. A flat 4 dB loss everywhere
        // must stay a flat 4 dB loss at the new 5 and 10 kHz points too.
        val oldNormal = floatArrayOf(11.4f, 4.4f, 2.4f, -1.3f, -5.6f, -5.4f, 4.4f, 12.6f, 12.6f)
        val old = oldNormal.joinToString(",") { ((-60f + it + 4f) * 10).toInt().toString() }
        val p = HearPrint.decode("v2|5|10|$old")!!
        assertTrue(p.complete)
        assertEquals(1f, p.retestDb, 1e-4f)
        for (i in 0 until n) assertEquals(-56f + HearPrint.NORMAL_THRESHOLD_DB[i], p.thresholds[i], 0.11f)
        assertEquals(p, HearPrint.decode(HearPrint.encode(p)))
    }

    @Test
    fun tonesStartJustAboveThePredictedThreshold() {
        val measured = FloatArray(n) { Float.NaN }
        assertEquals(HearPrint.START_LEVEL_DB, HearPrint.startLevelDb(2, measured), 0f)
        measured[2] = -62f  // 1 kHz
        // 2 kHz: nearest is 1 kHz; normal threshold is 3.7 dB lower there.
        assertEquals(-62f - 3.7f + HearPrint.START_MARGIN_DB, HearPrint.startLevelDb(3, measured), 1e-3f)
        measured[3] = -70f
        // 3 kHz takes 2 kHz, the nearest in log frequency.
        assertEquals(-70f + (-5.6f + 1.3f) + HearPrint.START_MARGIN_DB, HearPrint.startLevelDb(4, measured), 1e-3f)
        // Never below the floor plus the margin, never above the ceiling.
        measured.fill(Float.NaN); measured[2] = -200f
        assertEquals(HearPrint.MIN_LEVEL_DB + HearPrint.START_MARGIN_DB, HearPrint.startLevelDb(2, measured), 0f)
    }

    @Test
    fun reliabilityNeedsTheRetestWithinTolerance() {
        assertTrue(HearPrint.Profile(ear(), 8f).reliable)
        assertFalse(HearPrint.Profile(ear(), 9f).reliable)
        assertFalse(HearPrint.Profile(ear(), Float.NaN).reliable)
    }

    // ── Level tracker ─────────────────────────────────────────────────

    /** A simulated listener: hears above [trueDb] (± [noiseDb] per decision), reacts after [delaySec]. */
    private fun run(trueDb: Float, delaySec: Float = 0.35f, noiseDb: Float = 1.5f, seed: Long = 1,
                    startDb: Float = HearPrint.START_LEVEL_DB): LevelTracker {
        val rnd = Random(seed)
        val t = LevelTracker(startDb = startDb)
        val dt = 0.02f
        val history = ArrayDeque<Boolean>()
        val lag = (delaySec / dt).toInt()
        var steps = 0
        while (!t.isDone && steps < 10_000) {
            val hears = t.levelDb > trueDb + (rnd.nextGaussian() * noiseDb).toFloat()
            history.addLast(hears)
            val decided = if (history.size > lag) history.removeFirst() else false
            t.step(decided, dt)
            steps++
        }
        return t
    }

    @Test
    fun trackerFindsThresholdWithReactionTimeAndNoise() {
        for (truth in listOf(-80f, -62f, -45f)) for (seed in 1L..20L) {
            val t = run(truth, seed = seed)
            assertTrue(t.isDone)
            val est = t.thresholdDb()
            assertTrue("truth $truth seed $seed est $est", abs(est - truth) <= 3f)
        }
    }

    @Test
    fun predictedStartKeepsAccuracyEvenWhenTheGuessIsOff() {
        // Guess off by up to 8 dB either way, including a start below threshold.
        for (truth in listOf(-75f, -60f)) for (miss in listOf(-18f, -8f, 0f, 8f)) for (seed in 1L..10L) {
            val t = run(truth, seed = seed, startDb = truth + HearPrint.START_MARGIN_DB + miss)
            assertTrue(t.isDone)
            assertTrue("truth $truth miss $miss seed $seed est ${t.thresholdDb()}", abs(t.thresholdDb() - truth) <= 3f)
        }
    }

    @Test
    fun firstPressIsTheOnsetAndABounceCountsForNothing() {
        val t = LevelTracker(startDb = -40f)
        repeat(15) { t.step(false, 0.02f) }   // reaction time: nothing pressed yet
        repeat(50) { t.step(true, 0.02f) }    // first press: no reversal, still fast
        assertEquals(0, t.reversalCount)
        repeat(3) { t.step(false, 0.02f) }    // a 60 ms bounce...
        repeat(20) { t.step(true, 0.02f) }    // ...and back down
        assertEquals(0, t.reversalCount)
        repeat(20) { t.step(false, 0.02f) }   // a real release
        assertEquals(1, t.reversalCount)
    }

    @Test
    fun steadyListenerNeverTriggersARepeat() {
        for (seed in 1L..30L) {
            val t = run(-60f, seed = seed, startDb = -50f)
            assertTrue("spread ${t.spreadDb}", t.spreadDb < HearPrint.REPEAT_SPREAD_DB)
        }
    }

    @Test
    fun trackerDetectsOutOfRangeListeners() {
        val never = LevelTracker()
        repeat(2000) { if (!never.isDone) never.step(false, 0.02f) }
        assertTrue(never.notHeard)
        assertEquals(HearPrint.MAX_LEVEL_DB, never.thresholdDb(), 0f)

        val always = LevelTracker()
        repeat(5000) { if (!always.isDone) always.step(true, 0.02f) }
        assertTrue(always.belowRange)
        assertEquals(HearPrint.MIN_LEVEL_DB, always.thresholdDb(), 0f)
    }

    @Test
    fun trackerFinishesInReasonableTime() {
        // Starting near the prediction keeps a tone near 5 s, so the whole run
        // (12 tones) stays near 90 s even with a slow listener.
        val rnd = Random(3)
        var total = 0f
        repeat(10) {
            val truth = -55f - rnd.nextFloat() * 25f
            val t = LevelTracker(startDb = truth + HearPrint.START_MARGIN_DB + (rnd.nextGaussian() * 6).toFloat())
            var secs = 0f
            var hist = ArrayDeque<Boolean>()
            while (!t.isDone) {
                hist.addLast(t.levelDb > truth); val d = if (hist.size > 17) hist.removeFirst() else false
                t.step(d, 0.02f); secs += 0.02f
            }
            total += secs
        }
        assertTrue("average ${total / 10} s", total / 10 < 7f)
    }
}
