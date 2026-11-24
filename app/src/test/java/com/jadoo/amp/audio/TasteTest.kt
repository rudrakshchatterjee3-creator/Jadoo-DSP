package com.jadoo.amp.audio

import com.jadoo.amp.audio.Taste.Choice
import com.jadoo.amp.audio.Taste.Dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

class TasteTest {

    @Test
    fun everyCurveKeepsTheVoiceAtBypass() {
        val rnd = Random(1)
        val oneK = EqBands.frequencies.indexOfFirst { it == 1000f }
        repeat(200) {
            val g = FloatArray(4) { i -> Taste.DIMS[i].minDb + rnd.nextFloat() * (Taste.DIMS[i].maxDb - Taste.DIMS[i].minDb) }
            assertEquals(0f, Taste.curve(g)[oneK], 1e-4f)
        }
        Taste.curve(FloatArray(4)).forEach { assertEquals(0f, it, 1e-4f) }
    }

    /** Found 2026-11-24: clip-weighted normalisation turned "more bass" into "quieter voice". */
    @Test
    fun aBassTasteAddsBassAndCostsNoVoice() {
        val c = Taste.curve(floatArrayOf(8f, 0f, 0f, 0f))
        val f = EqBands.frequencies
        assertTrue("100 Hz lifted: ${c[3]}", c[3] > 3.5f)
        for (i in f.indices) if (f[i] >= 400f) assertTrue("no voice cut at ${f[i]}: ${c[i]}", c[i] > -0.3f)
    }

    @Test
    fun shapesSitWhereTheyShould() {
        val f = EqBands.frequencies
        val bass = Taste.curve(floatArrayOf(6f, 0f, 0f, 0f))
        assertTrue(bass[0] - bass.last() > 5f)
        val air = Taste.curve(floatArrayOf(0f, 0f, 0f, 6f))
        assertTrue(air.last() - air[0] > 4f)
        val clarity = Taste.curve(floatArrayOf(0f, 0f, 6f, 0f))
        val peak = clarity.indices.maxBy { clarity[it] }
        assertTrue("clarity peaks near 3 kHz: ${f[peak]}", f[peak] in 2000f..4500f)
        val warmth = Taste.curve(floatArrayOf(0f, 6f, 0f, 0f))
        val wpeak = warmth.indices.maxBy { warmth[it] }
        assertTrue("warmth peaks near 300 Hz: ${f[wpeak]}", f[wpeak] in 200f..450f)
    }

    @Test
    fun answersMoveTheStartingPoint() {
        val (m, sd) = Taste.prior(Taste.Answers(bass = Taste.Level.High, treble = Taste.Level.Low,
            bothers = setOf(Taste.Bother.Muddy)))
        assertTrue(m[Dim.Bass.ordinal] > 1.5f)
        assertTrue(m[Dim.Air.ordinal] < 0f)
        assertTrue(m[Dim.Warmth.ordinal] < 0f)
        assertTrue(sd[Dim.Bass.ordinal] < Dim.Bass.priorSdDb)
        val (m0, _) = Taste.prior(Taste.Answers())
        m0.forEach { assertEquals(0f, it, 0f) }
    }

    /** A listener with ideal [theta], answering by the model's own rule plus [extraNoise]. */
    private fun simulate(theta: FloatArray, prior: Pair<FloatArray, FloatArray>, seed: Int,
                         extraNoise: Float = 0.5f, flipRepeat: Boolean = false): Pair<Taste.Estimator, Int> {
        val rnd = Random(seed)
        val est = Taste.Estimator(prior.first, prior.second, Random(seed + 1000))
        var n = 0
        while (true) {
            val c = est.next() ?: break
            val t = theta[c.dim.ordinal] + (rnd.nextDouble() * 2 - 1).toFloat() * extraNoise
            val diff = abs(c.bDb - t) - abs(c.aDb - t)
            val pA = Taste.LAPSE / 2 + (1 - Taste.LAPSE) * Taste.phi(diff / Taste.SENSE_DB)
            var choice = when {
                abs(diff) < 0.4f && rnd.nextFloat() < 0.4f -> Choice.Tie
                rnd.nextFloat() < pA -> Choice.A
                else -> Choice.B
            }
            if (c.repeat && flipRepeat) choice = if (choice == Choice.A) Choice.B else Choice.A
            est.answer(c, choice)
            n++
            if (n > 40) break
        }
        return est to n
    }

    @Test
    fun learnsATasteFromTheChoices() {
        val rnd = Random(5)
        var within = 0; var total = 0; var maxQ = 0
        repeat(200) { trial ->
            val theta = FloatArray(4) { i -> val d = Taste.DIMS[i]; d.minDb + 1f + rnd.nextFloat() * (d.maxDb - d.minDb - 2f) }
            val (est, n) = simulate(theta, Taste.prior(Taste.Answers()), trial)
            maxQ = maxOf(maxQ, n)
            val r = est.result()
            for (d in Taste.DIMS) {
                total++
                if (abs(r[d] - theta[d.ordinal]) <= 1.5f) within++
            }
        }
        assertTrue("questions $maxQ", maxQ <= Taste.MAX_TOTAL + 1)
        assertTrue("within 1.5 dB: $within / $total", within >= total * 0.85)
    }

    @Test
    fun aGoodStartingPointNeedsFewerChoices() {
        val theta = floatArrayOf(4f, -1f, 2f, 3f)
        val good = Taste.prior(Taste.Answers(bass = Taste.Level.High, vocals = Taste.Level.High, treble = Taste.Level.High))
        var withPrior = 0; var without = 0
        repeat(40) {
            withPrior += simulate(theta, good, it).second
            without += simulate(theta, Taste.prior(Taste.Answers()), it).second
        }
        assertTrue("$withPrior vs $without", withPrior <= without)
    }

    @Test
    fun aContradictedRepeatPullsTowardNeutral() {
        val theta = floatArrayOf(5f, 0f, 3f, 4f)
        val (est, _) = simulate(theta, Taste.prior(Taste.Answers()), 3, flipRepeat = true)
        assertFalse(est.consistent)
        val (ok, _) = simulate(theta, Taste.prior(Taste.Answers()), 3)
        assertTrue(abs(est.result()[Dim.Bass]) < abs(ok.result()[Dim.Bass]) + 0.01f)
    }

    @Test
    fun codecRoundTrips() {
        val p = Taste.Profile(floatArrayOf(3.5f, -1f, 2f, 0.5f), consistent = false, takenAtMillis = 42L)
        assertEquals(p, Taste.decode(Taste.encode(p)))
        assertNull(Taste.decode(""))
        assertNull(Taste.decode("t1|1|1|1,2"))
        assertNull(Taste.decode("x"))
    }

    @Test
    fun libraryKeepsSeveralNamedTastes() {
        val a = Taste.Profile(floatArrayOf(3f, 0f, 1f, 2f), takenAtMillis = 1L)
        val b = Taste.Profile(floatArrayOf(-1f, 1f, 2f, 0f), takenAtMillis = 2L)
        var lib = Taste.Library().save(a, "Gym").save(b, "Night | “calm”")
        assertEquals(2, lib.items.size)
        assertEquals(1, lib.active)
        assertEquals(b, lib.current)
        lib = lib.select(0)
        assertEquals(a, lib.current)
        lib = lib.select(-1)
        assertNull(lib.current)
        // A retake replaces in place and keeps the name.
        val a2 = Taste.Profile(floatArrayOf(4f, 0f, 0f, 1f), takenAtMillis = 3L)
        lib = lib.save(a2, "", replace = 0)
        assertEquals("Gym", lib.items[0].name)
        assertEquals(a2, lib.current)
        lib = lib.rename(1, "  Night  ")
        assertEquals("Night", lib.items[1].name)
        val back = Taste.decodeLibrary(Taste.encodeLibrary(lib))
        assertEquals(lib, back)
        // Deleting the active one falls back to hearing only; indices shift.
        lib = lib.delete(0)
        assertEquals(1, lib.items.size)
        assertEquals(-1, lib.active)
        assertEquals("", Taste.encodeLibrary(Taste.Library()))
        assertEquals(Taste.Library(), Taste.decodeLibrary("garbage"))
    }

    @Test
    fun aSingleTasteFromBeforeTheLibraryCarriesOver() {
        val old = Taste.Profile(floatArrayOf(2f, 0f, 1f, 1f), takenAtMillis = 9L)
        val lib = Taste.decodeLibrary(Taste.encode(old))
        assertEquals(1, lib.items.size)
        assertEquals(0, lib.active)
        assertEquals(old, lib.current)
    }
}
