package com.jadoo.amp.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * HearPrint's optional taste profile: how much bass, warmth, clarity and air
 * this listener likes, learned from a short questionnaire (the starting
 * point) and voice-matched A/B choices on their own music (the answer).
 *
 * Why A/B and not more tones: a threshold test finds what an ear misses, and
 * a good ear misses little, so it gives a nearly flat curve. Taste is where
 * people differ most. Harman's headphone preference studies (Olive, Welti
 * et al., 2013-2019) found wide individual spread exactly in preferred bass
 * and treble level, and groups of listeners who want more or less of both.
 *
 * Every candidate curve is held at 0 dB at 1 kHz ([curve]), so A and B
 * keep the voice at exactly the bypass level: the version with more bass
 * has more bass, not a quieter voice.
 *
 * Pure Kotlin, no Android types: unit tested in TasteTest.
 */
object Taste {

    enum class Dim(val minDb: Float, val maxDb: Float, val priorSdDb: Float) {
        /** Low shelf, half gain at 110 Hz. */
        Bass(-4f, 8f, 3f),
        /** Bell at 300 Hz, about 1.5 octaves wide: body. */
        Warmth(-4f, 4f, 2f),
        /** Bell at 3 kHz, about 1.5 octaves wide: voices, attack, detail. */
        Clarity(-4f, 5f, 2.5f),
        /** High shelf, half gain at 9 kHz: cymbals, air, space. */
        Air(-4f, 6f, 2.5f);

        /** This dimension's shape at [hz], 0..1. */
        fun shape(hz: Float): Float = when (this) {
            Bass -> 1f / (1f + (hz / 110f).pow(2))
            Warmth -> bell(hz, 300f, 0.7f)
            Clarity -> bell(hz, 3000f, 0.75f)
            Air -> 1f / (1f + (9000f / hz).pow(2))
        }

        private fun bell(hz: Float, centre: Float, sigmaOct: Float): Float {
            val o = (ln(hz / centre) / ln(2f)) / sigmaOct
            return exp(-0.5f * o * o)
        }
    }

    val DIMS = Dim.entries

    /** A taste: gain per dimension (dB, before loudness normalisation). */
    data class Profile(val gainsDb: FloatArray, val consistent: Boolean = true, val takenAtMillis: Long = 0L) {
        operator fun get(d: Dim) = gainsDb[d.ordinal]
        val isNeutral: Boolean get() = gainsDb.all { abs(it) < 0.25f }
        override fun equals(other: Any?) = other is Profile && gainsDb.contentEquals(other.gainsDb) &&
            consistent == other.consistent && takenAtMillis == other.takenAtMillis
        override fun hashCode() = gainsDb.contentHashCode()
    }

    /**
     * The 15-band PreEQ curve for [gainsDb], shifted so it is 0 dB at
     * [ANCHOR_HZ]: the voice stays exactly where bypass has it, and a taste
     * only adds or takes away around it (the rule Loudness Contour uses).
     *
     * It used to be shifted to 0 dB on DspEngine.LEVEL_MATCH_WEIGHTS. Those
     * weights are bass-heavy on purpose (they estimate clipping, and peaks
     * come from the bass), so a bass taste paid for itself with the voice:
     * Bass +8 came out as +0.4 dB at 100 Hz and -3.9 dB from 160 Hz up,
     * which is "quieter and darker", not "more bass", and on Bluetooth put
     * the voice under bypass. It also made the bassier side of an A/B the
     * quieter one, so the test leaned against bass.
     */
    fun curve(gainsDb: FloatArray, centresHz: FloatArray = EqBands.frequencies): FloatArray {
        fun at(hz: Float) = DIMS.sumOf { (gainsDb[it.ordinal] * it.shape(hz)).toDouble() }.toFloat()
        val offset = at(ANCHOR_HZ)
        return FloatArray(centresHz.size) { b -> at(centresHz[b]) - offset }
    }

    /** Where every taste curve is 0 dB: the middle of the voice. */
    const val ANCHOR_HZ = 1000f

    // ── Questionnaire → starting point ─────────────────────────────────

    enum class Genre { Pop, EdmHipHop, RockMetal, Acoustic, VocalPodcast, FilmRegional }
    enum class Bother { Harsh, Dull, Muddy, Thin, Tiring }
    enum class Level { Low, Natural, High }
    enum class Volume { Quiet, Medium, Loud }

    data class Answers(
        val genres: Set<Genre> = emptySet(),
        val bothers: Set<Bother> = emptySet(),
        val bass: Level? = null,
        val vocals: Level? = null,
        val treble: Level? = null,
        val volume: Volume? = null,
        val wordsDb: FloatArray = FloatArray(DIMS.size)
    )

    /** Prior mean and standard deviation per dimension, from [a]. */
    fun prior(a: Answers): Pair<FloatArray, FloatArray> {
        val mean = FloatArray(DIMS.size)
        val sd = FloatArray(DIMS.size) { DIMS[it].priorSdDb }
        fun add(d: Dim, db: Float, narrow: Boolean = true) {
            mean[d.ordinal] += db
            if (narrow) sd[d.ordinal] *= 0.85f
        }
        when (a.bass) { Level.Low -> add(Dim.Bass, -1.5f); Level.Natural -> add(Dim.Bass, 0f); Level.High -> add(Dim.Bass, 3f); null -> {} }
        when (a.vocals) { Level.Low -> add(Dim.Clarity, -1.5f); Level.Natural -> add(Dim.Clarity, 0f); Level.High -> add(Dim.Clarity, 2f); null -> {} }
        when (a.treble) { Level.Low -> add(Dim.Air, -1.5f); Level.Natural -> add(Dim.Air, 0f); Level.High -> add(Dim.Air, 2.5f); null -> {} }
        for (b in a.bothers) when (b) {
            Bother.Harsh -> { add(Dim.Clarity, -1f); add(Dim.Air, -0.5f, false) }
            Bother.Dull -> { add(Dim.Clarity, 1f); add(Dim.Air, 1.5f) }
            Bother.Muddy -> { add(Dim.Bass, -1f, false); add(Dim.Warmth, -1.5f) }
            Bother.Thin -> { add(Dim.Bass, 1.5f, false); add(Dim.Warmth, 1f) }
            Bother.Tiring -> { add(Dim.Air, -0.5f, false); add(Dim.Clarity, -0.5f, false) }
        }
        if (Genre.EdmHipHop in a.genres) add(Dim.Bass, 1f, false)
        if (Genre.Acoustic in a.genres) { add(Dim.Bass, -0.5f, false); add(Dim.Warmth, 0.5f, false) }
        if (Genre.VocalPodcast in a.genres) add(Dim.Clarity, 0.5f, false)
        // Quiet listening hears bass and the top octave less (ISO 226 equal-loudness).
        when (a.volume) { Volume.Quiet -> { add(Dim.Bass, 1f, false); add(Dim.Air, 0.5f, false) }; Volume.Loud -> add(Dim.Bass, -0.5f, false); else -> {} }
        for (d in DIMS) if (a.wordsDb[d.ordinal] != 0f) add(d, a.wordsDb[d.ordinal].coerceIn(-3f, 3f))
        for (d in DIMS) {
            mean[d.ordinal] = mean[d.ordinal].coerceIn(d.minDb + 1f, d.maxDb - 1f)
            sd[d.ordinal] = sd[d.ordinal].coerceAtLeast(1.4f)
        }
        return Pair(mean, sd)
    }

    // ── A/B estimation ────────────────────────────────────────────────

    enum class Choice { A, B, Tie }

    /** One comparison: dimension [dim] at [aDb] (A) or [bDb] (B), every other dimension at [baseDb]. */
    data class Comparison(val dim: Dim, val aDb: Float, val bDb: Float, val baseDb: FloatArray, val repeat: Boolean = false) {
        fun gains(sideA: Boolean) = baseDb.copyOf().also { it[dim.ordinal] = if (sideA) aDb else bDb }
    }

    /**
     * Bayesian ideal-point estimation, one grid posterior per dimension. A
     * listener prefers the version closer to their ideal θ, with a soft edge
     * ([SENSE_DB]: closer by this much is chosen ~84% of the time) and a
     * lapse rate; "can't tell" is likely when the two are about equally far
     * from θ. Each question puts the two versions symmetrically around the
     * current estimate, at a spacing that shrinks as it gets surer, which
     * is the most informative pair for this model. Near the end one earlier
     * question is asked again with A and B swapped; a different answer marks
     * the result less certain and pulls it toward neutral.
     */
    class Estimator(priorMean: FloatArray, priorSd: FloatArray, private val random: Random = Random.Default) {
        private val grids = DIMS.map { d -> FloatArray(((d.maxDb - d.minDb) / STEP_DB).roundToInt() + 1) { d.minDb + it * STEP_DB } }
        private val logPost = DIMS.map { d ->
            val g = grids[d.ordinal]; val m = priorMean[d.ordinal]; val s = priorSd[d.ordinal]
            DoubleArray(g.size) { -0.5 * ((g[it] - m) / s).toDouble().let { x -> x * x } }
        }
        private val asked = IntArray(DIMS.size)
        private var lastDim: Dim? = null
        private val history = ArrayList<Pair<Comparison, Choice>>()
        private var repeatDone = false
        var consistent = true
            private set
        val answered: Int get() = history.size

        fun mean(d: Dim): Float {
            val p = posterior(d); val g = grids[d.ordinal]
            return p.indices.sumOf { p[it] * g[it] }.toFloat()
        }

        fun sd(d: Dim): Float {
            val p = posterior(d); val g = grids[d.ordinal]; val m = mean(d)
            return sqrt(p.indices.sumOf { p[it] * (g[it] - m).toDouble().pow(2) }).toFloat()
        }

        private fun posterior(d: Dim): DoubleArray {
            val lp = logPost[d.ordinal]
            val max = lp.max()
            val e = DoubleArray(lp.size) { exp(lp[it] - max) }
            val sum = e.sum()
            return DoubleArray(e.size) { e[it] / sum }
        }

        private fun done(d: Dim) = asked[d.ordinal] >= MAX_PER_DIM ||
            (asked[d.ordinal] >= MIN_PER_DIM && sd(d) < STOP_SD_DB)

        val isDone: Boolean
            get() = (history.size >= MAX_TOTAL || DIMS.all(::done)) && (repeatDone || history.isEmpty() || history.size >= MAX_TOTAL + 1)

        fun next(): Comparison? {
            if (isDone) return null
            if (DIMS.all(::done) || history.size >= MAX_TOTAL) return repeatQuestion()
            val open = DIMS.filter { !done(it) }
            val pick = open.filter { it != lastDim }.ifEmpty { open }
                .maxWith(compareBy<Dim> { sd(it) }.thenBy { -asked[it.ordinal] })
            val m = mean(pick)
            val delta = (0.9f * sd(pick)).coerceIn(MIN_DELTA_DB, MAX_DELTA_DB)
            var lo = m - delta; var hi = m + delta
            if (lo < pick.minDb) { hi += pick.minDb - lo; lo = pick.minDb }
            if (hi > pick.maxDb) { lo -= hi - pick.maxDb; hi = pick.maxDb }
            lo = lo.coerceAtLeast(pick.minDb)
            val base = FloatArray(DIMS.size) { mean(DIMS[it]) }
            return if (random.nextBoolean()) Comparison(pick, lo, hi, base) else Comparison(pick, hi, lo, base)
        }

        /** The most decisive earlier question, A and B swapped. */
        private fun repeatQuestion(): Comparison? {
            val candidate = history.filter { it.second != Choice.Tie && !it.first.repeat }
                .maxByOrNull { abs(it.first.aDb - it.first.bDb) } ?: run { repeatDone = true; return null }
            val c = candidate.first
            return Comparison(c.dim, c.bDb, c.aDb, c.baseDb, repeat = true)
        }

        fun answer(c: Comparison, choice: Choice) {
            if (c.repeat) {
                repeatDone = true
                val original = history.firstOrNull { !it.first.repeat && it.first.dim == c.dim && it.first.aDb == c.bDb && it.first.bDb == c.aDb }
                val expected = when (original?.second) { Choice.A -> Choice.B; Choice.B -> Choice.A; else -> null }
                if (expected != null && choice != expected) consistent = false
            } else {
                asked[c.dim.ordinal]++
                lastDim = c.dim
            }
            history.add(c to choice)
            val g = grids[c.dim.ordinal]
            val lp = logPost[c.dim.ordinal]
            for (i in g.indices) {
                val diff = abs(c.bDb - g[i]) - abs(c.aDb - g[i]) // > 0: A is closer to θ
                val pA = LAPSE / 2 + (1 - LAPSE) * phi(diff / SENSE_DB)
                val like = when (choice) {
                    Choice.A -> pA
                    Choice.B -> 1 - pA
                    Choice.Tie -> TIE_FLOOR + exp(-0.5 * (diff / TIE_WIDTH_DB).toDouble().pow(2))
                }
                lp[i] += ln(like.toDouble().coerceAtLeast(1e-9))
            }
        }

        /** The learned taste; pulled 30% toward neutral when the repeated question disagreed. */
        fun result(takenAtMillis: Long = 0L): Profile {
            val shrink = if (consistent) 1f else 0.7f
            return Profile(FloatArray(DIMS.size) { i -> (mean(DIMS[i]) * shrink * 2f).roundToInt() / 2f }, consistent, takenAtMillis)
        }

        /** Rough number of questions still to come, for the progress label. */
        fun remainingEstimate(): Int {
            if (isDone) return 0
            val perDim = DIMS.sumOf { d -> if (done(d)) 0 else (MIN_PER_DIM - asked[d.ordinal]).coerceAtLeast(1) }
            return (perDim + if (repeatDone) 0 else 1).coerceAtMost(MAX_TOTAL + 1 - history.size)
        }
    }

    const val STEP_DB = 0.25f
    const val SENSE_DB = 1.2f
    const val LAPSE = 0.08f
    const val TIE_WIDTH_DB = 1.2f
    const val TIE_FLOOR = 0.05f
    const val MIN_DELTA_DB = 1.0f
    const val MAX_DELTA_DB = 3.0f
    const val STOP_SD_DB = 1.0f
    const val MIN_PER_DIM = 2
    const val MAX_PER_DIM = 6
    const val MAX_TOTAL = 18

    /** Standard normal CDF (Abramowitz-Stegun 7.1.26, |error| < 1.5e-7). */
    internal fun phi(x: Float): Float {
        val z = abs(x) / sqrt(2f)
        val t = 1f / (1f + 0.3275911f * z)
        val y = 1f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t * exp(-z * z)
        return if (x >= 0) 0.5f * (1f + y) else 0.5f * (1f - y)
    }

    // ── Library: several named tastes per output, one active ─────────────

    data class Named(val name: String, val profile: Profile)

    /** The saved tastes for one output; [active] -1 = hearing only. */
    data class Library(val items: List<Named> = emptyList(), val active: Int = -1) {
        val current: Profile? get() = items.getOrNull(active)?.profile

        fun select(index: Int) = copy(active = if (index in items.indices) index else -1)

        /** Saves [profile]: over entry [replace] (keeping its name) or as a new entry; either way it becomes active. */
        fun save(profile: Profile, name: String, replace: Int? = null): Library =
            if (replace != null && replace in items.indices) {
                copy(items = items.toMutableList().also { it[replace] = Named(name.ifBlank { it[replace].name }, profile) }, active = replace)
            } else {
                copy(items = items + Named(name, profile), active = items.size)
            }

        fun rename(index: Int, name: String) =
            if (index !in items.indices || name.isBlank()) this
            else copy(items = items.toMutableList().also { it[index] = it[index].copy(name = name.trim().take(MAX_NAME)) })

        fun delete(index: Int): Library {
            if (index !in items.indices) return this
            val left = items.toMutableList().also { it.removeAt(index) }
            val nowActive = when {
                active == index -> -1
                active > index -> active - 1
                else -> active
            }
            return Library(left, nowActive)
        }
    }

    const val MAX_NAME = 24
    const val MAX_PROFILES = 8

    /**
     * "tl1|active" then one line per taste: base64(name) TAB [encode]. A
     * plain "t1|…" string (one unnamed taste, before the library) reads as a
     * library holding it, active.
     */
    fun encodeLibrary(lib: Library): String {
        if (lib.items.isEmpty()) return ""
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
        return "tl1|${lib.active}\n" + lib.items.joinToString("\n") {
            b64.encodeToString(it.name.toByteArray(Charsets.UTF_8)) + "\t" + encode(it.profile)
        }
    }

    fun decodeLibrary(s: String): Library = runCatching {
        if (s.isBlank()) return Library()
        if (s.startsWith("t1|")) return decode(s)?.let { Library(listOf(Named("", it)), 0) } ?: Library()
        val lines = s.split('\n')
        val head = lines[0].split('|')
        require(head.size == 2 && head[0] == "tl1")
        val b64 = java.util.Base64.getUrlDecoder()
        val items = lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val name = String(b64.decode(line.substring(0, tab)), Charsets.UTF_8)
            decode(line.substring(tab + 1))?.let { Named(name, it) }
        }
        Library(items, head[1].toInt().takeIf { it in items.indices } ?: -1)
    }.getOrDefault(Library())

    // ── Storage ───────────────────────────────────────────────────────
    // "t1|takenAt|consistent(1/0)|bass,warmth,clarity,air" in tenths of a dB. "" = none.

    fun encode(p: Profile?): String {
        if (p == null) return ""
        return "t1|${p.takenAtMillis}|${if (p.consistent) 1 else 0}|" + p.gainsDb.joinToString(",") { (it * 10f).roundToInt().toString() }
    }

    fun decode(s: String): Profile? = runCatching {
        if (s.isBlank()) return null
        val f = s.split('|')
        if (f.size != 4 || f[0] != "t1") return null
        val g = f[3].split(',').map { it.toInt() / 10f }
        require(g.size == DIMS.size)
        Profile(FloatArray(g.size) { i -> g[i].coerceIn(DIMS[i].minDb, DIMS[i].maxDb) }, f[2] == "1", f[1].toLong())
    }.getOrNull()
}
