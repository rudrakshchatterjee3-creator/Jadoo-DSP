package com.jadoo.amp.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * ISO 226:2003 equal-loudness-contour compensation.
 *
 * The ear's frequency response is level-dependent: at low listening levels the
 * bass (and, less dramatically, the extreme treble) falls away far faster than
 * the midrange. A mix balanced at, say, 80 dB SPL therefore sounds thin and
 * mid-forward at 55 dB SPL — not because anything changed in the signal, but
 * because hearing itself changed. This is the Fletcher-Munson effect, updated
 * and standardised as ISO 226.
 *
 * This object turns that into a per-band PreEQ correction:
 *
 *  1. [splAtIsoIndex] implements the ISO 226:2003 model directly — given a
 *     loudness level Ln (phon) and one of the standard's 29 frequencies, it
 *     returns the sound pressure level Lp needed at that frequency to be
 *     perceived as equally loud as Ln at 1 kHz.
 *  2. [compensationDb] takes the difference between the contour SHAPE at the
 *     reference level (where the mix sounds "right") and the shape at the
 *     current level, then interpolates it onto the app's 15 EQ band centres.
 *
 * Because both shapes are expressed relative to 1 kHz, the correction is
 * exactly 0 dB at 1 kHz by construction — this only ever redistributes
 * perceived balance, it never acts as a volume control.
 *
 * Reference: ISO 226:2003, "Acoustics — Normal equal-loudness-level contours",
 * Table 1 (af, Lu, Tf) and the model in clause 4.1.
 */
object LoudnessContour {

    // ── ISO 226:2003 Table 1 ────────────────────────────────────────────
    // The standard is defined only at these 29 one-third-octave frequencies
    // (20 Hz – 12.5 kHz). Everything else is interpolated (see below).
    private val ISO_FREQ = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0,
        1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0,
        10000.0, 12500.0
    )

    /** Exponent of loudness perception (af in the standard). */
    private val ISO_AF = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330,
        0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246,
        0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254,
        0.271, 0.301
    )

    /** Magnitude of the linear transfer function normalised at 1 kHz (Lu). */
    private val ISO_LU = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5,
        -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7,
        -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2,
        -10.7, -3.1
    )

    /** Threshold of hearing (Tf). */
    private val ISO_TF = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
        14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5,
        1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6,
        13.9, 12.3
    )

    /** Index of 1000 Hz in the ISO tables — the reference frequency. */
    private const val REF_INDEX = 17

    /**
     * The ISO 226:2003 model is specified for loudness levels of 20–90 phon.
     * Outside that range the published af/Lu/Tf coefficients no longer
     * describe measured data, so both ends are clamped rather than
     * extrapolated into fiction.
     */
    const val MIN_PHON = 20f
    const val MAX_PHON = 90f

    /**
     * Default assumed SPL at maximum system volume. 80 dB SPL sits close to
     * typical mastering/monitoring reference and is a realistic "loud but not
     * damaging" headphone level. Users whose gear is louder or quieter than
     * this correct it with the Reference Level control — the whole system is
     * relative to this one number.
     */
    const val DEFAULT_REFERENCE_PHON = 80f

    /**
     * Hard ceiling on any single band's correction, before the user's Amount
     * scaling. At very low levels the raw ISO delta at 25 Hz exceeds +20 dB,
     * which no phone driver can deliver and which would eat the entire
     * limiter headroom budget. Capping keeps the curve musical and keeps
     * DspEngine.calculateHeadroomOffset's credit bounded.
     */
    const val MAX_BOOST_DB = 12f

    /**
     * Band indices treated as the "bass" leg of the correction (25–250 Hz).
     * Also reused by JadooDspService.preEqBassPeakDb() to keep the gain-
     * budget credit's zone split identical to this feature's own.
     */
    val BASS_BANDS = 0..5

    /** Band indices treated as the "treble" leg of the correction (4–16 kHz). See BASS_BANDS. */
    val TREBLE_BANDS = 11..14

    /**
     * ISO 226:2003 clause 4.1: the SPL (dB) required at ISO frequency
     * [index] for a tone to be perceived at loudness level [phon].
     *
     *   Af = 4.47e-3 * (10^(0.025*Ln) - 1.15) + [0.4 * 10^(((Tf+Lu)/10) - 9)]^af
     *   Lp = (10/af) * log10(Af) - Lu + 94
     */
    private fun splAtIsoIndex(index: Int, phon: Double): Double {
        val af = ISO_AF[index]
        val lu = ISO_LU[index]
        val tf = ISO_TF[index]
        val term1 = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15)
        val term2 = (0.4 * 10.0.pow(((tf + lu) / 10.0) - 9.0)).pow(af)
        val aF = term1 + term2
        return (10.0 / af) * log10(aF) - lu + 94.0
    }

    /**
     * The contour's SHAPE at [phon]: SPL at each ISO frequency expressed
     * relative to 1 kHz. Subtracting the 1 kHz value is what makes the final
     * correction level-independent — two shapes differ only in how the ear's
     * balance changed, not in overall loudness.
     */
    private fun contourShape(phon: Double): DoubleArray {
        val ref = splAtIsoIndex(REF_INDEX, phon)
        return DoubleArray(ISO_FREQ.size) { splAtIsoIndex(it, phon) - ref }
    }

    /**
     * Per-band gain (dB) to apply so that music mixed at [referencePhon]
     * keeps its perceived tonal balance when actually played back at
     * [currentPhon]. Returns [EqBands.count] values aligned to
     * [EqBands.frequencies], 0 dB at 1 kHz by construction.
     *
     * [amount] scales the whole curve (0 = off, 1 = full ISO-derived
     * correction) so users who find full compensation too dramatic can dial
     * it back without losing the level-tracking behaviour.
     *
     * Playing back LOUDER than the reference is deliberately a no-op rather
     * than an inverted (bass-cutting) correction. The maths would happily
     * invert, and doing so is arguably "correct", but a DSP that quietly
     * removes bass when the user turns the volume up is indistinguishable
     * from a bug. [currentPhon] is therefore clamped to the reference.
     */
    fun compensationDb(
        referencePhon: Float,
        currentPhon: Float,
        amount: Float,
        driverExtension: Float = 1f
    ): FloatArray {
        val amt = amount.coerceIn(0f, 1f)
        if (amt <= 0f) return FloatArray(EqBands.count)

        val refPhon = referencePhon.coerceIn(MIN_PHON, MAX_PHON).toDouble()
        // Never above the reference — see the note above.
        val curPhon = currentPhon.coerceIn(MIN_PHON, MAX_PHON)
            .coerceAtMost(referencePhon.coerceIn(MIN_PHON, MAX_PHON))
            .toDouble()

        if (curPhon >= refPhon) return FloatArray(EqBands.count)

        val refShape = contourShape(refPhon)
        val curShape = contourShape(curPhon)
        // Positive where the ear has lost sensitivity at the lower level.
        val delta = DoubleArray(ISO_FREQ.size) { curShape[it] - refShape[it] }

        val ext = driverExtension.coerceIn(0f, 1f)
        return FloatArray(EqBands.count) { band ->
            val freq = EqBands.frequencies[band]
            val raw = interpolateLogF(freq.toDouble(), delta)
            // ── Two legs, scaled differently, on purpose ──────────────────
            // The correction above 100 Hz is NOT driver-scaled. It corrects
            // the EAR's level-dependent response, not the transducer's — the
            // ear's sensitivity loss at low level is the same whatever is
            // playing, so asking for less of it on a small speaker would be
            // correcting the wrong thing.
            //
            // Below 100 Hz that reasoning stops holding, because the request
            // stops being deliverable. Cone excursion for a given SPL roughly
            // doubles per octave down, so +12 dB at 25 Hz on a phone speaker
            // buys excursion distortion and intermodulation across everything
            // above it — and the smart-PA protection chip clamps it back down
            // anyway, so the gain is spent twice for nothing. Scaling this leg
            // by what the driver can actually move keeps the correction honest
            // where it can be delivered and stops asking where it cannot.
            //
            // The two legs meet at 100 Hz, where ext scaling is a no-op, so
            // there is no discontinuity in the curve.
            val scaled = if (freq < 100f) raw.toFloat() * ext else raw.toFloat()
            (scaled * amt).coerceIn(-MAX_BOOST_DB, MAX_BOOST_DB)
        }
    }

    /**
     * Linear interpolation of [values] (one per ISO frequency) at [freqHz],
     * done in log-frequency space because the ISO grid is one-third-octave —
     * linear-in-Hz interpolation between, say, 10 kHz and 12.5 kHz would
     * badly misplace everything in between.
     *
     * Above 12.5 kHz the standard simply stops. The app's top EQ band is
     * 16 kHz, so that band holds the 12.5 kHz value rather than extrapolating
     * a curve the standard never measured. Holding under-corrects slightly at
     * 16 kHz; inventing data there would be worse.
     */
    private fun interpolateLogF(freqHz: Double, values: DoubleArray): Double {
        if (freqHz <= ISO_FREQ.first()) return values.first()
        if (freqHz >= ISO_FREQ.last()) return values.last()
        var hi = 1
        while (hi < ISO_FREQ.size && ISO_FREQ[hi] < freqHz) hi++
        val lo = hi - 1
        val logLo = log10(ISO_FREQ[lo])
        val logHi = log10(ISO_FREQ[hi])
        val t = (log10(freqHz) - logLo) / (logHi - logLo)
        return values[lo] + (values[hi] - values[lo]) * t
    }
}
