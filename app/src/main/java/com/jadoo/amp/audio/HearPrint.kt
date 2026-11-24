package com.jadoo.amp.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * HearPrint: a personal PreEQ curve, one for both ears, from a short tone test.
 *
 * What it measures, honestly: the quietest level of each test tone the user
 * still hears, in dBFS, through THEIR phone, volume and headphones. Nothing
 * is calibrated, so this is not a hearing test and never reports dB HL. What
 * it can do is compare frequencies with each other: after the
 * normal-hearing threshold shape (ISO 226:2003 Tf) is taken out, what is left
 * is how this listener, with these headphones, hears each region relative to
 * their own midrange. Both ears hear each tone together, so the curve is one
 * and the same for left and right: it can never pull the image to one side. That combined "ear + headphone" picture is why a
 * HearPrint is saved per output device, like AutoEQ.
 *
 * The curve lifts what is heard less by a fraction of the difference (the
 * hearing-aid rule of thumb: about half; full compensation sounds harsh
 * because loudness grows faster above threshold), allows small cuts where
 * this ear/headphone pair is unusually sensitive, and is capped. It feeds
 * the PreEQ sum like every other tonal contributor, so gain staging (wired
 * trim, Bluetooth plan, limiter budget) and the home-screen curve cover it.
 *
 * Pure Kotlin, no Android types: unit tested in HearPrintTest.
 */
object HearPrint {

    /**
     * Test tones. 250 Hz – 12 kHz: below that an earbud's seal dominates, above
     * it no driver is consistent. Denser from 4 kHz up (5 and 10 kHz), where the
     * ear canal's resonance with an earbud in it sits and differs most between
     * people: its first peak can move by up to an octave from ear to ear.
     */
    val TEST_FREQS_HZ = floatArrayOf(250f, 500f, 1000f, 2000f, 3000f, 4000f, 5000f, 6000f, 8000f, 10000f, 12000f)

    /**
     * Normal threshold of hearing at [TEST_FREQS_HZ], dB SPL — ISO 226:2003
     * Table 1 (Tf), log-frequency interpolated at 3, 6 and 12 kHz (the same
     * table LoudnessContour uses). Subtracted so normal ears get a flat curve.
     */
    val NORMAL_THRESHOLD_DB = floatArrayOf(11.4f, 4.4f, 2.4f, -1.3f, -5.6f, -5.4f, -1.5f, 4.4f, 12.6f, 13.9f, 12.6f)

    /** Test order: 1 kHz first (easy, sets the pace), up, then down; the screen adds 1 kHz again as a retest. */
    val ORDER = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 0)

    /**
     * Largest lift per test point. 250 and 500 Hz only 2 dB: at threshold
     * they mostly measure the earbud's seal, and lifting them makes the
     * sound thick and dark. 12 kHz 4 dB, since drivers and fit vary most there.
     */
    val MAX_BOOST_DB = floatArrayOf(2f, 2f, 6f, 6f, 6f, 6f, 6f, 6f, 6f, 5f, 4f)

    /** Largest cut, for regions this ear/headphone pair hears unusually well. Below [NO_CUT_FROM_HZ] only. */
    const val MAX_CUT_DB = 3f

    /** From here up the curve never cuts: presence and air are never taken away. */
    const val NO_CUT_FROM_HZ = 1000f

    /** The low test points (below this) may never be lifted more than the treble (from [TREBLE_FROM_HZ]) on average. */
    const val LOW_BELOW_HZ = 1000f
    const val TREBLE_FROM_HZ = 2000f

    /** Default strength: the half-gain rule. */
    const val DEFAULT_STRENGTH = 0.5f

    /** 1 kHz retest further than this from the first run: the result is shaky. */
    const val RETEST_TOLERANCE_DB = 8f

    /** Tone level limits (dBFS) and where the test starts. */
    const val MIN_LEVEL_DB = -100f
    const val MAX_LEVEL_DB = -6f
    const val START_LEVEL_DB = -30f

    /** How far above the predicted threshold a tone starts, so it is heard at once and tracked down. */
    const val START_MARGIN_DB = 10f

    /**
     * Where a tone starts. The first starts at [START_LEVEL_DB]; every later one
     * starts [START_MARGIN_DB] above a prediction: the nearest pitch already
     * measured, moved by the normal threshold's difference between the two.
     * That skips the long slide down from a fixed start, which in simulation
     * cut a tone from about 11 s to about 4.5 s for the same accuracy; the
     * first reversals are discarded anyway, so a wrong guess costs time, not
     * accuracy.
     */
    fun startLevelDb(point: Int, measured: FloatArray): Float {
        val f = ln(TEST_FREQS_HZ[point])
        val near = measured.indices.filter { !measured[it].isNaN() }
            .minByOrNull { abs(ln(TEST_FREQS_HZ[it]) - f) } ?: return START_LEVEL_DB
        val predicted = measured[near] + NORMAL_THRESHOLD_DB[point] - NORMAL_THRESHOLD_DB[near]
        return (predicted + START_MARGIN_DB).coerceIn(MIN_LEVEL_DB + START_MARGIN_DB, MAX_LEVEL_DB)
    }

    /** Tracks whose used reversals spread wider than this are repeated once: one confused moment, not the ear. */
    const val REPEAT_SPREAD_DB = 12f

    /** Level of the volume-setting tone: set "soft but clear" there, thresholds land near -60 dBFS. */
    const val VOLUME_TONE_DB = -20f

    /** Raw result: the threshold per test frequency (dBFS), both ears together, NaN where not measured. */
    data class Profile(
        val thresholds: FloatArray,
        /** |first 1 kHz - retest|, NaN when there was no retest. */
        val retestDb: Float = Float.NaN,
        val takenAtMillis: Long = 0L
    ) {
        /** The 1 kHz repeat landed within tolerance. */
        val reliable: Boolean get() = !retestDb.isNaN() && retestDb <= RETEST_TOLERANCE_DB

        val complete: Boolean
            get() = thresholds.size == TEST_FREQS_HZ.size && thresholds.none { it.isNaN() }

        override fun equals(other: Any?) = other is Profile && thresholds.contentEquals(other.thresholds) &&
            retestDb.equals(other.retestDb) && takenAtMillis == other.takenAtMillis
        override fun hashCode() = thresholds.contentHashCode()
    }

    /**
     * The rule before smoothing, per test point (dB). [strength] 0..1 is the
     * fraction of the measured difference that is made up (0.5 = half gain).
     * [preEqAtTestDb] is any headphone correction already in the chain at the
     * test points (AutoEQ): the test ran without it, so its share of the
     * measured shape is taken back out instead of corrected twice.
     */
    internal fun rawCompensation(profile: Profile, strength: Float, preEqAtTestDb: FloatArray? = null): FloatArray {
        val n = TEST_FREQS_HZ.size
        // A correction that lifts a region by g would have let the user hear
        // it g dB quieter: the threshold through it is t - g.
        val loss = FloatArray(n) { i ->
            profile.thresholds[i] - NORMAL_THRESHOLD_DB[i] - (preEqAtTestDb?.getOrNull(i) ?: 0f)
        }
        // Anchor: the median at 500 Hz, 1 kHz and 2 kHz, where hearing is
        // usually best preserved and every headphone is most neutral.
        // Everything is lifted or cut relative to that.
        val anchor = median(floatArrayOf(loss[1], loss[2], loss[3]))
        val s = strength.coerceIn(0f, 1f)
        return FloatArray(n) { i ->
            if (loss[i].isNaN()) 0f else (s * (loss[i] - anchor)).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB[i])
        }
    }

    /**
     * Compensation at each test point (dB), one curve for both ears.
     *
     * The raw rule is smoothed lightly (0.2 / 0.6 / 0.2 across neighbouring
     * test points) so one noisy tone cannot carve a spike into the music,
     * then clamped to the caps again. Then the "never darker" guard:
     *  - no cut from [NO_CUT_FROM_HZ] up, so presence and air are never
     *    reduced (on Bluetooth a treble lift fades out on loud passages while
     *    a cut would stay, which is darker than bypass);
     *  - the points below [LOW_BELOW_HZ] are lifted no more than the treble
     *    from [TREBLE_FROM_HZ] up is on average, so the tilt can only stay
     *    level or lean brighter.
     */
    fun compensationAtTestPoints(profile: Profile, strength: Float, preEqAtTestDb: FloatArray? = null): FloatArray {
        val raw = rawCompensation(profile, strength, preEqAtTestDb)
        val n = raw.size
        val c = FloatArray(n) { i ->
            val prev = raw[(i - 1).coerceAtLeast(0)]
            val next = raw[(i + 1).coerceAtMost(n - 1)]
            val v = (SMOOTH_SIDE * prev + (1f - 2f * SMOOTH_SIDE) * raw[i] + SMOOTH_SIDE * next)
                .coerceIn(-MAX_CUT_DB, MAX_BOOST_DB[i])
            if (TEST_FREQS_HZ[i] >= NO_CUT_FROM_HZ) v.coerceAtLeast(0f) else v
        }
        val treble = TEST_FREQS_HZ.indices.filter { TEST_FREQS_HZ[it] >= TREBLE_FROM_HZ }.map { c[it] }.average().toFloat()
        val lowCeiling = treble.coerceAtLeast(0f)
        for (i in 0 until n) if (TEST_FREQS_HZ[i] < LOW_BELOW_HZ) c[i] = c[i].coerceAtMost(lowCeiling)
        return c
    }

    private const val SMOOTH_SIDE = 0.2f

    /**
     * Maps test-point gains onto the 15 PreEQ band centres: linear in log
     * frequency between test points; below 250 Hz it fades to nothing at
     * 100 Hz (bass at threshold through earbuds is mostly seal), above 12 kHz
     * it holds the 12 kHz value.
     */
    fun toBands(atTestDb: FloatArray, centresHz: FloatArray = EqBands.frequencies): FloatArray {
        val lf = TEST_FREQS_HZ.map { ln(it) }
        return FloatArray(centresHz.size) { b ->
            val f = centresHz[b]
            when {
                f <= 100f -> 0f
                f < TEST_FREQS_HZ[0] -> atTestDb[0] * ((ln(f) - ln(100f)) / (lf[0] - ln(100f)))
                f >= TEST_FREQS_HZ.last() -> atTestDb.last()
                else -> {
                    val x = ln(f)
                    val i = (0 until lf.size - 1).first { x < lf[it + 1] }
                    val t = (x - lf[i]) / (lf[i + 1] - lf[i])
                    atTestDb[i] + (atTestDb[i + 1] - atTestDb[i]) * t
                }
            }
        }
    }

    /** The 15-band curve for the live chain, same for both ears. Zeros when there is no usable profile. */
    fun bandCurve(profile: Profile?, strength: Float, preEqAtTestDb: FloatArray? = null): FloatArray {
        if (profile == null || !profile.complete) return FloatArray(EqBands.count)
        return toBands(compensationAtTestPoints(profile, strength, preEqAtTestDb))
    }

    private fun median(v: FloatArray): Float {
        val s = v.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return 0f
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    // ── Storage ───────────────────────────────────────────────────────
    // "v3|takenAt|retest|t0,t1,…" (11 tones), tenths of a dB. "" = none.
    // v2 had 9 tones (no 5 or 10 kHz); it is read back with those two filled in.

    private val V2_FREQS_HZ = floatArrayOf(250f, 500f, 1000f, 2000f, 3000f, 4000f, 6000f, 8000f, 12000f)

    fun encode(p: Profile?): String {
        if (p == null) return ""
        fun one(v: Float) = if (v.isNaN()) "x" else (v * 10f).roundToInt().toString()
        return "v3|${p.takenAtMillis}|${one(p.retestDb)}|${p.thresholds.joinToString(",") { one(it) }}"
    }

    fun decode(s: String): Profile? = runCatching {
        if (s.isBlank()) return null
        val f = s.split('|')
        if (f.size != 4 || (f[0] != "v3" && f[0] != "v2")) return null
        fun one(v: String) = if (v == "x") Float.NaN else v.toInt() / 10f
        val parts = f[3].split(',').map(::one).toFloatArray()
        val thresholds = if (f[0] == "v3") {
            require(parts.size == TEST_FREQS_HZ.size); parts
        } else {
            require(parts.size == V2_FREQS_HZ.size); fromV2(parts)
        }
        Profile(thresholds, one(f[2]), f[1].toLong())
    }.getOrNull()

    /**
     * A 9-tone result on the 11-tone grid. The new points get the loss
     * (threshold minus normal) interpolated in log frequency from their
     * neighbours, plus their own normal threshold, so the curve it gives is
     * the one it gave before.
     */
    private fun fromV2(old: FloatArray): FloatArray {
        val oldNormal = FloatArray(V2_FREQS_HZ.size) { i -> NORMAL_THRESHOLD_DB[TEST_FREQS_HZ.indexOfFirst { it == V2_FREQS_HZ[i] }] }
        return FloatArray(TEST_FREQS_HZ.size) { i ->
            val hz = TEST_FREQS_HZ[i]
            val exact = V2_FREQS_HZ.indexOfFirst { it == hz }
            if (exact >= 0) old[exact] else {
                val hi = V2_FREQS_HZ.indexOfFirst { it > hz }
                val lo = hi - 1
                val t = (ln(hz) - ln(V2_FREQS_HZ[lo])) / (ln(V2_FREQS_HZ[hi]) - ln(V2_FREQS_HZ[lo]))
                val loss = (old[lo] - oldNormal[lo]) + ((old[hi] - oldNormal[hi]) - (old[lo] - oldNormal[lo])) * t
                loss + NORMAL_THRESHOLD_DB[i]
            }
        }
    }

    /** 1 kHz retest difference, for [Profile.retestLeftDb]. */
    fun retestDifference(first: Float, retest: Float): Float =
        if (first.isNaN() || retest.isNaN()) Float.NaN else abs(first - retest)
}

/**
 * Békésy tracking for one tone: while the user holds the button (hears it)
 * the level falls, while they let go it rises. Each change of direction is a
 * reversal; the threshold is the mean of the reversal levels after the first
 * [discard]. Moves fast until the first reversal after the first press, then slowly.
 *
 * Pure and time-stepped ([step] with the elapsed seconds), so the audio
 * thread only reads [levelDb].
 */
class LevelTracker(
    startDb: Float = HearPrint.START_LEVEL_DB,
    private val minDb: Float = HearPrint.MIN_LEVEL_DB,
    private val maxDb: Float = HearPrint.MAX_LEVEL_DB,
    private val fastRateDbPerSec: Float = 10f,
    private val slowRateDbPerSec: Float = 4f,
    private val discard: Int = 2,
    private val reversalsNeeded: Int = 4,
    private val maxSeconds: Float = 45f
) {
    @Volatile var levelDb: Float = startDb
        private set
    private var falling: Boolean? = null
    private var pressed = false
    private val reversals = ArrayList<Float>(16)
    private val reversalTimes = ArrayList<Float>(16)
    private var elapsed = 0f
    /** Seconds spent pinned at the top with the button up: tone never heard. */
    private var secondsAtCeiling = 0f
    private var secondsAtFloor = 0f

    val reversalCount: Int get() = reversals.size

    /** Spread (max - min, dB) of the reversals the threshold is built from; 0 with fewer than two. */
    val spreadDb: Float
        get() {
            if (reversals.size <= discard + 1) return 0f
            val used = reversals.subList(discard, reversals.size)
            return used.max() - used.min()
        }

    val isDone: Boolean
        get() = reversals.size >= discard + reversalsNeeded || elapsed >= maxSeconds ||
            secondsAtCeiling >= 4f || secondsAtFloor >= 4f

    /** Never heard even at [maxDb]. */
    val notHeard: Boolean get() = secondsAtCeiling >= 4f

    /** Still held at [minDb]: the volume is too high to find this threshold. */
    val belowRange: Boolean get() = secondsAtFloor >= 4f

    fun step(heard: Boolean, dtSec: Float) {
        if (isDone || dtSec <= 0f) return
        elapsed += dtSec
        val nowFalling = heard
        // The first press is the onset, not a reversal: before it the level
        // only crept up through the user's reaction time. Counting it switched
        // to the slow rate for the whole way down from the start level.
        if (falling != null && falling != nowFalling && (pressed || !nowFalling)) {
            // Two changes closer than a finger can mean them (a bounce, a
            // brushed screen) cancel instead of counting as two reversals.
            if (reversals.isNotEmpty() && elapsed - reversalTimes.last() < MIN_REVERSAL_GAP_SEC) {
                reversals.removeAt(reversals.size - 1)
                reversalTimes.removeAt(reversalTimes.size - 1)
            } else {
                reversals.add(levelDb)
                reversalTimes.add(elapsed)
            }
        }
        if (heard) pressed = true
        falling = nowFalling
        val rate = if (reversals.isEmpty()) fastRateDbPerSec else slowRateDbPerSec
        levelDb = (levelDb + if (nowFalling) -rate * dtSec else rate * dtSec).coerceIn(minDb, maxDb)
        secondsAtCeiling = if (!heard && levelDb >= maxDb) secondsAtCeiling + dtSec else 0f
        secondsAtFloor = if (heard && levelDb <= minDb) secondsAtFloor + dtSec else 0f
    }

    /** dBFS threshold; [maxDb] when never heard, [minDb] when below range, NaN with too few reversals. */
    fun thresholdDb(): Float = when {
        notHeard -> maxDb
        belowRange -> minDb
        reversals.size <= discard -> Float.NaN
        else -> reversals.subList(discard, reversals.size).average().toFloat()
    }

    private companion object {
        const val MIN_REVERSAL_GAP_SEC = 0.15f
    }
}
