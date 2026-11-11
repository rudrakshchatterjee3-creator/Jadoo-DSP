package com.jadoo.amp.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Analog Bass's selectable Pultec low-shelf frequencies (the UI's 20/30/60/100 Hz chips). */
val PULTEC_FREQUENCIES_HZ = floatArrayOf(20f, 30f, 60f, 100f)

/** Analog Bass's slider settings, as the Bluetooth band plan reads them (see DspEngine.planBluetoothMbc). */
data class AnalogBassVoicing(
    val drive: Float = 0.4f,
    val warmth: Float = 0.7f,
    val drift: Float = 0.2f,
    val pultecBoost: Float = 0.5f,
    val pultecCut: Float = 0.3f,
    val pultecFreqIndex: Int = 2
)

/** How far each end of the spectrum's stacked boosts are scaled back; see DspEngine.bluetoothStackScale. */
data class StackScale(val bass: Float = 1f, val treble: Float = 1f)

/**
 * One MBC band's settings, planned in plain Kotlin before being written to a
 * DynamicsProcessing.MbcBand — so the band layout can be unit-tested for
 * every feature combination (see MbcPlanTest). Property names match MbcBand.
 */
class MbcBandSpec {
    var cutoffFrequency = 20_000f
    var attackTime = 1f
    var releaseTime = 60f
    var ratio = 1f
    var threshold = 0f
    var kneeWidth = 0f
    var noiseGateThreshold = -90f
    var expanderRatio = 1f
    var preGain = 0f
    var postGain = 0f

    fun applyTo(band: DynamicsProcessing.MbcBand) {
        band.cutoffFrequency = cutoffFrequency
        band.attackTime = attackTime
        band.releaseTime = releaseTime
        band.ratio = ratio
        band.threshold = threshold
        band.kneeWidth = kneeWidth
        band.noiseGateThreshold = noiseGateThreshold
        band.expanderRatio = expanderRatio
        band.preGain = preGain
        band.postGain = postGain
    }

    fun toBand() = DynamicsProcessing.MbcBand(
        true, cutoffFrequency, attackTime, releaseTime, ratio, threshold,
        kneeWidth, noiseGateThreshold, expanderRatio, preGain, postGain
    )

    /** True when writing [other] over this band would change nothing audible. */
    fun sameAs(other: MbcBandSpec): Boolean {
        fun close(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.01f
        return close(cutoffFrequency, other.cutoffFrequency) && close(threshold, other.threshold) &&
            close(ratio, other.ratio) && close(postGain, other.postGain) &&
            close(kneeWidth, other.kneeWidth) && close(releaseTime, other.releaseTime)
    }
}

/** Beyond this the volume stage's room is more than any boost stack can use. */
const val MAX_VOLUME_HEADROOM_DB = 24f

class DspEngine {
    var dynamicsProcessing: DynamicsProcessing? = null
        private set

    private var preGainDb = 0f
    private var postGainDb = 0f
    // Cached limiter reference so setPostGain can update postGain reliably
    // without a read-modify-write that may return stale state on some devices.
    private var currentLimiter: DynamicsProcessing.Limiter? = null
    // The limiter threshold for the active mode BEFORE the gain-budget headroom
    // offset is applied. Cached so updateHeadroom() can recompute
    // `baseLimiterThreshold + headroomDb` live, without re-running attach().
    private var baseLimiterThreshold = -0.3f

    /**
     * The automatic input attenuation the gain-staging model is currently
     * asking for (0 or negative dB). Summed with the user's pre-gain in
     * applyInputGain(). See [levelMatchTrimDb].
     */
    private var autoTrimDb = 0f

    /** Analog Bass's sliders as last written to the DP; the Bluetooth band plan reads them. */
    private var analogBassVoicing = AnalogBassVoicing()

    /**
     * The attached DP runs [planBluetoothMbc] rather than [planMbc]: a
     * Bluetooth route with precise gain staging on. Its bands are rewritten
     * from the plan, never by index from the wired layout.
     */
    private var bluetoothPlanActive = false

    /** The Bluetooth plan as last written, so a refresh only touches bands that moved. */
    private var liveBluetoothPlan: List<MbcBandSpec> = emptyList()

    /** SBC crest band's lower edge while it runs on the Bluetooth plan; null when off. */
    private var bluetoothSbcCrestHz: Float? = null

    /** HDR Pure on the Bluetooth plan: no level lift, its promise is transparency. */
    private var bluetoothHdrPure = false

    /** Input makeup for Tube Warmth on Bluetooth (0 or [BT_TUBE_MAKEUP_DB]). */
    private var tubeMakeupDb = 0f

    /**
     * Pre-encoder headroom trim for SBC (0 or negative dB). Separate from
     * [autoTrimDb] because it is not a gain-budget correction at all — it is
     * protection against the SBC decoder reconstructing peaks ABOVE what we
     * sent and clipping at the receiver's DAC. See SbcEngine.HEADROOM_TRIM_DB.
     *
     * Lives in the limiter's postGain rather than the input stage so it
     * applies after everything else in the chain, which is where the peak
     * that reaches the encoder is actually determined.
     */
    private var sbcTrimDb = 0f

    /** Last computed gain budget in dB, surfaced for the UI readout. */
    @Volatile var gainBudgetDb = 0f
        private set

    /** Live automatic input trim in dB (0 or negative), surfaced for the UI. */
    @Volatile var autoTrimReadoutDb = 0f
        private set

    // Tube Warmth: a soft-knee compander stage providing the subtle 2nd-order-ish
    // saturation character that DynamicsProcessing's bands cannot produce on their own.
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = -1
    // Set when this device refuses a LoudnessEnhancer on the session, so
    // attach() stops releasing the DP ahead of an insert that can't happen.
    private var loudnessEnhancerUnavailable = false

    // ── PreEQ gain glide ─────────────────────────────────────────────
    // DynamicsProcessing.EqBand has no attack/release of its own (unlike
    // Mbc bands) — every gain write is an instant, un-ramped step. Dragging
    // a manual-EQ slider now calls setPreEqBandGainAllChannels on every
    // pointer-move, which used to mean every move was its own instant jump
    // — individually small, but with no interpolation between them the ear
    // hears a string of little steps/clicks rather than a smooth glide.
    // Each band index gets its own glide job so dragging one band never
    // interferes with another band's in-flight glide; a new target for the
    // SAME band cancels and restarts from wherever the glide currently is.
    private var glideScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val allChannelGlideJobs = arrayOfNulls<Job?>(EqBands.count)
    private val perChannelGlideJobs = Array(2) { arrayOfNulls<Job?>(EqBands.count) }
    private val currentAllChannelGain = FloatArray(EqBands.count)
    private val currentPerChannelGain = Array(2) { FloatArray(EqBands.count) }

    internal companion object {
        const val GLIDE_DURATION_MS = 60L
        const val GLIDE_STEP_MS = 12L

        /**
         * Ceiling on how far the gain budget may pull the limiter threshold
         * down.
         *
         * ── This number was 6.0, and before that unbounded. Both were wrong,
         * and the reasoning that produced them was wrong. ──────────────────
         *
         * The premise was: features add N dB, so give the limiter N dB of
         * warning. The flaw is that the limiter is BROADBAND and the boosts
         * are NARROWBAND. Adding 6 dB at 90-300 Hz and then pulling a 10:1
         * ceiling down 6 dB across the whole spectrum leaves the tonal
         * balance almost exactly where it started and keeps only the
         * compression artefacts. Reported, accurately, as "enabling any
         * feature makes it quieter, less clear and smeared".
         *
         * The limiter does not need the warning — but not for the reason
         * this comment used to give ("it catches overs at whatever threshold
         * it sits at"). It cannot catch overs at all: AOSP's limiter
         * (DPFrequency.cpp) measures each FFT block's RMS, not its peaks, and
         * music's RMS sits 8-14 dB under its peaks. A -2.3 dBFS threshold
         * almost never engages, so sample peaks pass straight through. Peak
         * safety therefore has to come from the INPUT, before the boosts —
         * see [levelMatchTrimDb]. The offset only decides how early it
         * starts working on genuinely dense, loud material.
         *
         * 2 dB keeps it a peak catcher: it engages in the top couple of dB
         * where a boosted transient can actually run out of room, and never
         * touches sustained material. Larger stacks get more limiting ACTION
         * on peaks, which is correct and inaudible, instead of a lower
         * ceiling, which is neither.
         */
        const val MAX_HEADROOM_DB = 2.0f

        /**
         * Long-term spectrum of mastered music on the 15 PreEQ bands, used to
         * weigh each band's boost in [levelMatchTrimDb]. Pink (equal power
         * per band) through the mids, heavier in the bass where a mix's
         * peaks actually come from, falling away in the top octave where
         * there is little energy to raise. Tuned offline against real
         * masters with a model of this exact chain: with these weights the
         * stacked features land within ~0.7 LU of bypass and sample overs
         * drop from ~2% of samples to under 0.02%.
         */
        val LEVEL_MATCH_WEIGHTS = floatArrayOf(
            1.0f, 1.4f, 1.6f, 1.5f, 1.2f, 1.0f, 1.0f, 0.9f,
            0.9f, 0.8f, 0.7f, 0.5f, 0.35f, 0.2f, 0.08f
        ).let { w -> val sum = w.sum(); FloatArray(w.size) { w[it] / sum } }

        /**
         * History of the old split-budget input trim, kept because the
         * reasoning is still the right warning for anyone tempted to bring
         * it back. It was zero, deliberately. Trimming the input is transparent in the sense
         * that it adds no dynamics artefacts, but it still pays for a
         * narrowband boost with broadband level, which is the same error as
         * above wearing a different hat. If the user wants the whole mix
         * quieter they have a pre-gain slider and a volume rocker.
         *
         * This was briefly raised to 10, on the theory that budget past
         * MAX_HEADROOM_DB was "unpaid" and would distort. That theory was
         * wrong on its own terms: DynamicsProcessing is float internally, so
         * an over-budget boost does not clip on the way to the limiter — the
         * limiter catches it, which is its entire job. What the change
         * actually did was silently drop broadband level by up to 10 dB the
         * instant any feature was enabled (DBFB High alone: -1.9 dB;
         * Surround Wide: -3.5 dB), on every route. Reported, accurately, as
         * the app being quieter and less open than doing nothing at all.
         *
         * What that trim got wrong was its SIZE: it charged the nominal
         * worst-case budget (a narrowband peak) as broadband level. The trim
         * that replaced it, [levelMatchTrimDb], charges what the curve does
         * to the level of real music, which is a fraction of the peak boost
         * — so the mix comes out as loud as bypass rather than 10 dB under.
         * Without any trim at all (v1.6.4) the boosts pushed loud masters
         * up to +4 dBFS: hard clipping on Bluetooth, and earbud/phone
         * limiters pumping the level down — the "turning on Surround or
         * Loudness makes it quieter" reports.
         */
        const val MAX_AUTO_TRIM_DB = 12.0f

        // ── Bluetooth band plan — see planBluetoothMbc ──────────────────
        // The fixed zones, as cutoffs. 300-2000 Hz is the vocal zone, which
        // takes no guard for a boost: see BT_VOCAL_ZONE.
        val BT_ZONE_CUTOFFS_HZ = floatArrayOf(60f, 120f, 300f, 2000f, 5200f, 9600f, 14500f, 20000f)

        // Each zone's hot level: in-band block RMS at which a boost has
        // faded to nothing. Bass zones: the loudest of five masters' 99th
        // percentile. Any lower and its kick hits fall under bypass and lose
        // their punch over the voice; 3 dB higher and clipping climbs fast.
        // Above 2 kHz: the typical 99th percentile plus 2 dB. Treble peaks are
        // brief and masked, so any lower only costs Hi-Res its air.
        val BT_ZONE_HOT_DB = floatArrayOf(-8f, -9f, -10f, -12f, -16f, -19f, -26f, -33f)
        const val BT_VOCAL_ZONE = 3
        val BT_TRIMMED_BANDS = 6..9
        const val BT_ZONE_FLOOR_HZ = 20f
        const val BT_BASS_ZONE_TOP_HZ = 300f
        const val BT_ZONE_MEAN_POINTS = 16
        const val HI_RES_SILK_TOP_HZ = 14500f

        // A boost is whole up to BT_BOOST_SPAN_DB below the hot level and
        // gone at it. Fixed span: a small boost gets a ratio near 1, so a
        // hit beyond the hot level is barely touched. A big boost widens the
        // span instead of passing BT_BOOST_MAX_RATIO, which is what keeps a
        // hit from being ducked far below where the track put it.
        const val BT_BOOST_SPAN_DB = 6f
        const val BT_BOOST_MAX_RATIO = 3f
        const val BT_KNEE_DB = 4f
        const val BT_ATTACK_MS = 1f
        const val BT_BASS_RELEASE_MS = 80f
        const val BT_TOP_RELEASE_MS = 60f

        // Under this a zone's boost is not worth a compressor.
        const val BT_MIN_BOOST_DB = 0.05f

        // Analog Bass's Drive on the bass zones' hot level: harder as it
        // rises, neutral at its default, so Drive stays an audible control.
        const val BT_DRIVE_NEUTRAL = 0.4f
        const val BT_DRIVE_RANGE_DB = 6f
        const val BT_DRIFT_KNEE_DB = 8f

        /**
         * Lift on every zone at normal levels, faded out at the hot level
         * like any boost. Bypass is the reference every listener compares
         * against: level-matched to it, "no louder" is heard as "quieter"
         * (Hi-Res at -0.2 dB was reported as still lowering the volume). On
         * the chain model +1 dB is free at streaming-normalised levels.
         */
        const val BT_LEVEL_LIFT_DB = 1f

        /**
         * Input gain after Tube Warmth's LoudnessEnhancer. On Bluetooth the
         * LE sees full scale and its -8 dBFS 7:1 compressor takes more off a
         * loud master than its own target gain puts on (-1.4 LU at intensity
         * 0.5), which is what "Tube amp not so prominent" was. Its compressor
         * has lowered the peaks, so this has room.
         */
        const val BT_TUBE_MAKEUP_DB = 1f

        /**
         * Most a feature stack may lift either end of the spectrum on
         * Bluetooth before [bluetoothStackScale] scales it back. Tone, not
         * level: nothing is trimmed for these, but past this the balance
         * goes (Surround's +6 dB at 16 kHz was already reported as painful).
         * The floor keeps a stack recognisably itself.
         */
        const val STACK_BASS_CAP_DB = 8f
        const val STACK_TREBLE_CAP_DB = 7f
        const val STACK_TREBLE_FROM_HZ = 4000f
        const val STACK_SCALE_FLOOR = 0.6f
        const val STACK_SCALE_STEP = 0.05f

        // DBFB's sub (≤72 Hz) and punch (≤145 Hz) bands. Shared by
        // configureMbc and stageBoostDb so the trim charges exactly what
        // the bands do.
        const val DBFB_SUB_THRESHOLD_DB = -20f
        const val DBFB_SUB_RATIO = 1.3f
        const val DBFB_SUB_KNEE_DB = 8f
        const val DBFB_PUNCH_THRESHOLD_DB = -18f
        const val DBFB_PUNCH_RATIO = 1.5f
        const val DBFB_PUNCH_KNEE_DB = 6f
        fun dbfbSubPostGainDb(normal: Boolean, bassExtension: Float) = (if (normal) 1.5f else 2.5f) * bassExtension
        fun dbfbPunchPostGainDb(normal: Boolean, bassExtension: Float) = (if (normal) 0.8f else 1.4f) * bassExtension
    }

    /**
     * How many MBC bands each feature contributes, given which features are
     * active — the additive band-count model documented at length in
     * attach() and configureMbc(). Every place that needs to know a
     * feature's MBC band INDEX (attach()'s own bandCount tally, and the four
     * updateXxx live-update helpers below, which have to locate a band
     * without a full topology rebuild) re-derived these same five booleans'
     * worth of arithmetic independently until now — five copies of logic
     * that all have to change together the moment a new feature is
     * inserted into the band order. One mismatched copy doesn't crash; it
     * writes a live slider update onto the WRONG band, silently. Verified
     * against the previous five independent implementations across all 96
     * feature-combination cases before consolidating (bass features x
     * DbfbMode x hiRes x mobileBass x hdr x exciter) — identical indices in
     * every case.
     */
    private data class MbcBandCounts(
        val analogBass: Int,
        val dbfb: Int,
        val mobileBass: Int,
        val hdr: Int,
        val harmonicExciter: Int,
        val preHiResSafety: Int,
        val hiRes: Int,
        val sbcCrest: Int
    ) {
        val total: Int get() = analogBass + dbfb + mobileBass + hdr + harmonicExciter + preHiResSafety + hiRes + sbcCrest
    }

    private fun mbcBandCounts(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean,
        mobileBassEnabled: Boolean,
        hdrDynamicsEnabled: Boolean,
        harmonicExciterEnabled: Boolean,
        // SBC crest control appends its band at the very END of the array, so
        // it never shifts any existing feature's index. That is why the four
        // updateXxx live-update helpers below can keep calling this with the
        // default and still locate their band correctly.
        sbcConditioningEnabled: Boolean = false
    ): MbcBandCounts {
        val analogBassBands = if (analogBassEnabled) 3 else 0
        val dbfbBands = if (dbfbMode != DbfbMode.Off) 3 else 0
        val mobileBassBands = if (mobileBassEnabled) {
            if (!analogBassEnabled && dbfbMode == DbfbMode.Off) 2 else 1
        } else 0
        val hdrBands = if (hdrDynamicsEnabled) 1 else 0
        val harmonicExciterBands = if (harmonicExciterEnabled) {
            if (hdrDynamicsEnabled) 1 else 2
        } else 0
        // HiRes's first band must start at 5.2 kHz, so something has to end
        // there first. Only HDR and the exciter do; DBFB ends at 260 Hz.
        val preHiResSafetyBand =
            if (hiResEnabled && !hdrDynamicsEnabled && !harmonicExciterEnabled) 1 else 0
        val hiResBands = if (hiResEnabled) 3 else 0
        // When HiRes is on, crest control rides on HiRes's own top two air
        // bands (which already split at 14.5kHz) instead of adding one — see
        // configureMbc. Only the HiRes-off paths need a band of their own,
        // and they need it in BOTH cases that would otherwise close the array
        // themselves: the plain fallback closing band, and "HDR alone", which
        // returns early after closing at 20000Hz. Each of those emits the
        // transparent lower half plus the crest band, i.e. one band more.
        val sbcCrestBands = if (sbcConditioningEnabled && !hiResEnabled) 1 else 0
        return MbcBandCounts(analogBassBands, dbfbBands, mobileBassBands, hdrBands, harmonicExciterBands, preHiResSafetyBand, hiResBands, sbcCrestBands)
    }

    fun attach(
        sessionId: Int,
        initialGains: FloatArray = FloatArray(EqBands.count),
        initialPreGainDb: Float = preGainDb,
        initialPostGainDb: Float = postGainDb,
        hiResEnabled: Boolean = false,
        dbfbMode: DbfbMode = DbfbMode.Off,
        surroundMode: SurroundMode = SurroundMode.Off,
        hdrDynamicsEnabled: Boolean = false,
        hdrMode: HdrMode = HdrMode.Restoration,
        analogBassEnabled: Boolean = false,
        analogBassDrive: Float = 0.4f,
        analogBassWarmth: Float = 0.7f,
        analogBassDrift: Float = 0.2f,
        analogBassPultecBoost: Float = 0.5f,
        analogBassPultecCut: Float = 0.3f,
        analogBassPultecFreqIndex: Int = 2,
        tubeWarmthEnabled: Boolean = false,
        tubeWarmthIntensity: Float = 0.5f,
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f,
        harmonicExciterEnabled: Boolean = false,
        harmonicExciterIntensity: Float = 0.5f,
        // Pay the gain budget across the limiter AND the input stage rather
        // than dumping all of it on the limiter threshold — see levelMatchTrimDb.
        preciseGainStaging: Boolean = true,
        // Wired/speaker only: skip the input trim (the volume drop at full
        // phone volume) and keep the capped limiter. Bluetooth ignores it.
        keepFullLoudness: Boolean = false,
        // How much bass/treble boost the current output device's driver can actually
        // reproduce, 0..1 (see DeviceType) — 1 reproduces every feature's original,
        // pre-device-aware behavior exactly (the "General" device type).
        bassExtension: Float = 1f,
        trebleExtension: Float = 1f,
        // Mobile Bass always runs on the phone's own built-in speaker — a tiny
        // driver — regardless of which DeviceType the user has manually
        // selected. Scaling it by the selected type's bassExtension (e.g.
        // HomeSpeaker's, if that's what's picked) would credit it with a
        // driver it isn't actually running on, so it always uses
        // CompactSpeaker's extension instead. See JadooDspService.mobileBassExtension().
        mobileBassExtension: Float = 1f,
        // Peak PreEQ boost currently sitting in the bass/treble regions from
        // Graphic EQ + Loudness Contour combined (see
        // JadooDspService.preEqBassPeakDb/preEqTreblePeakDb). Real static
        // gain like every other boosting feature, so it has to be paid for
        // out of the same gain budget — see calculateGainBudget.
        preEqBassPeakDb: Float = 0f,
        preEqTreblePeakDb: Float = 0f,
        // The full PreEQ curve per channel (every tonal contributor summed,
        // Surround's L/R split included) — what levelMatchTrimDb weighs.
        preEqLeftDb: FloatArray = initialGains,
        preEqRightDb: FloatArray = initialGains,
        // Digital attenuation the system volume applies before this effect —
        // room the boosts already have. See levelMatchTrimDb.
        volumeHeadroomDb: Float = 0f,
        // Output is a Bluetooth route — selects planBluetoothMbc.
        bluetoothRoute: Boolean = false,
        // HDR Restoration expander shape. Defaults reproduce the tuned-by-ear
        // values exactly; they are parameters rather than constants so the
        // content channel can retune them without an APK (see RemoteTuning).
        // Already clamped to sane bounds by RemoteContent.parseTuning.
        hdrRestorationThreshold: Float = -38f,
        hdrRestorationKnee: Float = 14f,
        hdrRestorationExpanderRatio: Float = 1.12f,
        // SBC encoder-input conditioning — see SbcEngine. Only ever true on a
        // Bluetooth route with the feature explicitly enabled; the tonal half
        // of it lives in JadooDspService's PreEQ sum, this is the dynamics
        // half plus the decoder-overshoot trim.
        sbcConditioningEnabled: Boolean = false,
        sbcCrestCutoffHz: Float = 11_025f
    ): Boolean = synchronized(this) {
        // Any in-flight glide is targeting the OLD DynamicsProcessing
        // instance this attach() is about to replace — cancel rather than
        // let it keep stepping toward a now-stale target on the new one.
        allChannelGlideJobs.forEachIndexed { i, job -> job?.cancel(); allChannelGlideJobs[i] = null }
        perChannelGlideJobs.forEach { channelJobs ->
            channelJobs.forEachIndexed { i, job -> job?.cancel(); channelJobs[i] = null }
        }
        var newDynamicsProcessing: DynamicsProcessing? = null
        try {
            preGainDb = initialPreGainDb.coerceIn(-12f, 12f)
            postGainDb = initialPostGainDb.coerceIn(-12f, 12f)
            // Set before the limiter is built below — effectivePostGainDb()
            // folds this in, and the limiter is constructed from it.
            sbcTrimDb = if (sbcConditioningEnabled) SbcEngine.HEADROOM_TRIM_DB else 0f

            // Bluetooth runs its own band plan. Precise gain staging off is
            // the legacy A/B path, so it stays on the wired plan and limiter.
            bluetoothPlanActive = bluetoothRoute && preciseGainStaging
            bluetoothSbcCrestHz = if (bluetoothPlanActive && sbcConditioningEnabled) sbcCrestCutoffHz else null
            bluetoothHdrPure = bluetoothPlanActive && hdrDynamicsEnabled && hdrMode == HdrMode.Pure
            analogBassVoicing = AnalogBassVoicing(
                analogBassDrive, analogBassWarmth, analogBassDrift,
                analogBassPultecBoost, analogBassPultecCut, analogBassPultecFreqIndex
            )

            // JadOO Mobile Bass now has its own MBC band (a leveler in the
            // 0-400Hz "overtone" region — see configureMbc) plus a small
            // PostEQ shelf, so it shares the PostEQ slot with Analog Bass's
            // Pultec curve but never shares MBC bands with it. If both are
            // on, Mobile Bass's PostEQ shape wins; Analog Bass's own MBC
            // saturation/warmth still applies fully and independently.
            val postEqActive = analogBassEnabled || mobileBassEnabled

            // Additive band-count model: each active feature contributes a
            // fixed number of MBC bands, plus exactly one extra "closing"
            // band IF nothing already configured reaches all the way to
            // 20kHz on its own. Only HDR's own band (when HiRes is off) and
            // HiRes's own last band ever reach 20kHz directly — every other
            // combination needs that trailing band appended (see the
            // Fallback section at the end of configureMbc). HiRes's own 3
            // bands only span 5.2-20kHz, so it additionally needs 1 "safety"
            // band to cover whatever's below 5.2kHz when neither DBFB nor
            // HDR already extends up that far.
            // Tube Warmth's LoudnessEnhancer must come BEFORE the DP in the
            // session's effect chain: its 0-4 dB is part of the gain budget the
            // limiter below is voiced against. Effects run in the order they
            // were added, and a DP handle created while one already exists
            // shares that engine in place — so when the enhancer is new, the
            // old DP is released first and the enhancer inserted ahead of the
            // new one. Previously the enhancer landed AFTER the DP on first
            // enable (its boost unlimited) and BEFORE it after the next
            // rebuild, so the same setting sounded different by history.
            if (tubeWarmthEnabled && !loudnessEnhancerUnavailable &&
                (loudnessEnhancer == null || loudnessEnhancerSessionId != sessionId)) {
                releaseDynamicsProcessingOnly()
                configureTubeWarmthSaturation(sessionId, true, tubeWarmthIntensity)
            }
            tubeMakeupDb = if (bluetoothPlanActive && tubeWarmthEnabled && !loudnessEnhancerUnavailable) BT_TUBE_MAKEUP_DB else 0f

            val counts = mbcBandCounts(hiResEnabled, dbfbMode, analogBassEnabled, mobileBassEnabled, hdrDynamicsEnabled, harmonicExciterEnabled, sbcConditioningEnabled)
            // A closing band is needed unless something already reaches
            // 20000Hz on its own: HiRes's last band always does; HDR's own
            // band does too, but ONLY when HiRes is off AND the exciter is
            // off (HDR's band is no longer the final word once the exciter
            // needs its own bands after it — see configureMbc, where HDR
            // stops short at 8000f/5200f instead of 20000f in that case).
            // Missing this case previously left a gap in the band array —
            // the array's last band wouldn't actually cover up to Nyquist —
            // whenever Harmonic Exciter was on together with HDR and HiRes
            // was off.
            //
            // SBC crest control does not change this calculation. Whichever
            // band would have closed the array at 20000Hz — HDR's own in the
            // "HDR alone" case, or the fallback closing band otherwise — is
            // split into a transparent lower half plus the crest band, and
            // the extra band that costs is counted by MbcBandCounts.sbcCrest
            // rather than by flipping this flag.
            val hdrAloneClosesSpectrum = hdrDynamicsEnabled && !hiResEnabled && !harmonicExciterEnabled
            val needsFinalClosingBand = !hiResEnabled && !hdrAloneClosesSpectrum
            val bluetoothPlan = if (bluetoothPlanActive) planBluetoothMbc(
                hiResEnabled, dbfbMode, analogBassEnabled, analogBassVoicing,
                harmonicExciterEnabled, harmonicExciterIntensity, bassExtension, trebleExtension,
                preEqLeftDb, preEqRightDb, bluetoothSbcCrestHz, bluetoothHdrPure
            ) else emptyList()
            val mbcBandCount = if (bluetoothPlanActive) bluetoothPlan.size
                else counts.total + (if (needsFinalClosingBand) 1 else 0)

            val postEqBandCount = if (postEqActive) 4 else 0
            val configBuilder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true,
                EqBands.count,
                true,
                mbcBandCount,
                postEqActive,  // postEqInUse: enabled for Pultec-style EQ or Mobile Bass's overtone shelf
                postEqBandCount,
                true
            ).setPreferredFrameDuration(DpResponse.FRAME_DURATION_MS)

            val preEq = DynamicsProcessing.Eq(true, true, EqBands.count)
            for (i in 0 until EqBands.count) {
                val gain = initialGains.getOrNull(i)?.coerceIn(-15f, 15f) ?: 0f
                preEq.getBand(i).cutoffFrequency = EqBands.cutoffFrequencies[i]
                preEq.getBand(i).gain = gain
                // Keep the glide cache in sync with what's actually written here —
                // otherwise the first glide after this attach() would start from a
                // stale cached value (likely 0) instead of the real current gain,
                // producing an audible jump before the glide even begins.
                currentAllChannelGain[i] = gain
                currentPerChannelGain[0][i] = gain
                currentPerChannelGain[1][i] = gain
            }
            configBuilder.setPreEqAllChannelsTo(preEq)

            val mbc = DynamicsProcessing.Mbc(true, true, mbcBandCount)
            val mbcPlan = if (bluetoothPlanActive) bluetoothPlan else planMbc(hiResEnabled, dbfbMode, hdrDynamicsEnabled, hdrMode, analogBassEnabled, analogBassDrive, analogBassWarmth, analogBassDrift = analogBassDrift, mobileBassEnabled = mobileBassEnabled, mobileBassIntensity = mobileBassIntensity, harmonicExciterEnabled = harmonicExciterEnabled, harmonicExciterIntensity = harmonicExciterIntensity, bassExtension = bassExtension, trebleExtension = trebleExtension, mobileBassExtension = mobileBassExtension, hdrRestorationThreshold = hdrRestorationThreshold, hdrRestorationKnee = hdrRestorationKnee, hdrRestorationExpanderRatio = hdrRestorationExpanderRatio, sbcConditioningEnabled = sbcConditioningEnabled, sbcCrestCutoffHz = sbcCrestCutoffHz)
            if (mbcPlan.size != mbcBandCount) Log.e("DspEngine", "MBC plan has ${mbcPlan.size} bands, config expects $mbcBandCount")
            mbcPlan.take(mbcBandCount).forEachIndexed { i, spec -> spec.applyTo(mbc.getBand(i)) }
            configBuilder.setMbcAllChannelsTo(mbc)
            liveBluetoothPlan = bluetoothPlan

            // ── PostEQ: Pultec-style Analog Bass curve, or Mobile Bass's
            // overtone-emphasis shelf if enabled (takes priority — see above) ──
            if (postEqActive) {
                val postEq = DynamicsProcessing.Eq(true, true, postEqBandCount)
                if (mobileBassEnabled) {
                    configureMobileBassPostEq(postEq, mobileBassIntensity)
                } else {
                    configureAnalogBassPostEq(postEq, analogBassPultecFreqIndex, analogBassPultecBoost, analogBassPultecCut, analogBassWarmth, bassExtension)
                }
                configBuilder.setPostEqAllChannelsTo(postEq)
            }

            // ── Gain staging: calculate headroom offset ────────────────
            // When multiple features boost signal (HiRes, DBFB, HDR), the limiter
            // threshold must drop to prevent inter-modulation distortion.
            val budgetDb = calculateGainBudget(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity, mobileBassEnabled, mobileBassIntensity, surroundMode, harmonicExciterEnabled, harmonicExciterIntensity, bassExtension, trebleExtension, mobileBassExtension, preEqBassPeakDb, preEqTreblePeakDb)
            // Tube Warmth used to be forced onto the legacy split here, on the
            // stated grounds that precise staging's input trim would change
            // what level reached its compander. That was wrong: MAX_AUTO_TRIM_DB
            // is 0, so NEITHER path trims the input — the only difference
            // between them is how far the limiter threshold drops.
            //
            // Forcing legacy had a real and much worse effect. Legacy drops the
            // threshold by the ENTIRE uncapped budget, so Tube Warmth's glue
            // character was a function of the gain budget — and v1.6 began
            // crediting graphic EQ (up to 15 dB) to that budget, which v1.5.2
            // did not. Dial in some EQ and the same feature swung from a -4 dBFS
            // threshold to -14 dBFS: from gentle rounding to heavy compression,
            // and far quieter with it. A voiced character must not depend on an
            // unrelated clip-safety number.
            //
            // So Tube Warmth now takes the same precise split as everything
            // else, and its glue comes from its own limiter threshold below,
            // where it is fixed and predictable.
            val usePreciseStaging = preciseGainStaging
            val headroomDb = if (bluetoothPlanActive) 0f else limiterOffsetDb(budgetDb, usePreciseStaging)
            val trimDb = when {
                !usePreciseStaging -> 0f
                bluetoothPlanActive -> bluetoothTrimDb(preEqLeftDb, preEqRightDb, volumeHeadroomDb)
                // "Keep full loudness": no input trim on wired, DAC and speaker.
                // Boosts on a hot master can clip; that is the user's trade.
                keepFullLoudness -> 0f
                else -> levelMatchTrimDb(
                    preEqLeftDb, preEqRightDb,
                    stageBoostDb(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity,
                                 mobileBassEnabled, mobileBassIntensity, harmonicExciterEnabled, harmonicExciterIntensity,
                                 bassExtension, trebleExtension),
                    volumeHeadroomDb
                )
            }
            autoTrimDb = trimDb
            gainBudgetDb = budgetDb
            autoTrimReadoutDb = trimDb

            // Real safety limiter for all modes: a 10:1 ratio engaging only within
            // 0.3 dB of full scale. At normal program levels this never engages —
            // it exists purely to catch peaks introduced by HiRes/DBFB/AnalogBass
            // gain stages (see calculateGainBudget) before they clip.
            //
            // NOTE — the branches below are ordered, not combined. Pure HDR
            // wins over Tube Warmth: with both enabled you get Pure's 2:1
            // ceiling at -0.1 dBFS and NOT the softer tube-style glue limiter,
            // because Pure's entire promise is transparency and a slower,
            // earlier-engaging limiter would break it. This is deliberate, but
            // it is invisible in the UI — Tube Warmth stays on and simply
            // stops contributing its limiter character.
            // Pure HDR mode trades this for an even lighter 2:1 net, ceiling at
            // -0.1 dBFS, for maximum transparency on sources the user trusts.
            // Restoration HDR no longer compresses peaks (that was the cause of
            // "squashed, trashy" HDR audio) — its character now comes entirely
            // from the gentle multiband expander in configureMbc() and the
            // air-shelf boost in JadooDspService.applyAllBands().
            // Tube Warmth "glue": a softer, slower limiter than the standard
            // transparent safety net — lower ratio and an earlier threshold so
            // peaks are gently rounded rather than caught at the last instant,
            // echoing how a tube output stage compresses as it nears its rails.
            // Skipped under Pure HDR, which prioritises maximum transparency.
            //
            // The default branch's attack was 3ms — fast enough that, combined
            // with Mobile Bass's 90-300Hz punch band (up to +8.5dB on a bass
            // transient — see calculateHeadroomOffset), the limiter slammed the
            // ENTIRE broadband signal shut on every bass hit, audible as
            // "limiter attacking when the bass drops." Slowed to 12ms/90ms,
            // matching the gentler character already used for Tube Warmth/HDR,
            // so it rides bass transients instead of snapping at them — it's
            // still a real safety net against clipping, just one that no
            // longer fights Mobile Bass's own (already gentle) dynamics.
            val effectivePostGain = effectivePostGainDb()
            val limiter = when {
                hdrDynamicsEnabled && hdrMode == HdrMode.Pure -> {
                    baseLimiterThreshold = -0.1f
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        20f, 40f, 2f, baseLimiterThreshold + headroomDb, effectivePostGain
                    )
                }
                tubeWarmthEnabled -> {
                    // -4.0, not the -1.0 this used to be. The glue that makes
                    // this branch sound like a tube output stage nearing its
                    // rails needs the limiter actually working on program
                    // material, not just clipping the odd peak. It used to get
                    // that depth accidentally, from the legacy split dragging
                    // the threshold down by the whole gain budget (typically
                    // 3-5 dB in v1.5.2, where this was last voiced). Now that
                    // Tube Warmth is on the same capped split as every other
                    // feature, that depth has to be stated here — where it is
                    // a fixed part of the feature's character and no longer
                    // moves when an unrelated EQ band does.
                    baseLimiterThreshold = -4.0f
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        5f, 100f, 5f, baseLimiterThreshold + headroomDb, effectivePostGain
                    )
                }
                else -> {
                    baseLimiterThreshold = -0.3f
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        12f, 90f, 10f, baseLimiterThreshold + headroomDb, effectivePostGain
                    )
                }
            }
            currentLimiter = limiter   // cache for reliable postGain updates
            configBuilder.setLimiterAllChannelsTo(limiter)

            // Build the new DP BEFORE releasing the old one.
            // If construction throws (device band-count limit, session conflict, etc.)
            // the old DP — which still has DBFB/HiRes/HDR configured — stays alive
            // and keeps processing. Previously the old was released first, so any
            // failure silently killed all DSP while the UI still showed features as on.
            newDynamicsProcessing = DynamicsProcessing(0, sessionId, configBuilder.build())

            // Success — swap and clean up old instance.
            // Android auto-releases the old DP when a new one of the same type is
            // created on the same session, so enabled=false may throw. Use a nested
            // try-catch so that cleanup errors don't cascade to the outer catch
            // (which would release the brand-new DP and break everything).
            // The new handle usually shares the old one's engine (same effect
            // type, same session). Disabling the old handle first therefore
            // switched that shared engine OFF — every feature toggle let a
            // few ms of unprocessed audio through until the new handle turned
            // it back on. Release without disabling, then enable the new one.
            val oldDynamicsProcessing = dynamicsProcessing
            dynamicsProcessing = newDynamicsProcessing
            try {
                oldDynamicsProcessing?.release()
            } catch (cleanupEx: Exception) {
                Log.w("DspEngine", "Old DP cleanup skipped (likely auto-released by Android): ${cleanupEx.message}")
            }

            newDynamicsProcessing.enabled = true
            setPreGain(preGainDb)
            setPostGain(postGainDb)
            configureTubeWarmthSaturation(sessionId, tubeWarmthEnabled, tubeWarmthIntensity)
            Log.i("DspEngine", "Attached session=$sessionId hiRes=$hiResEnabled dbfb=$dbfbMode hdr=$hdrDynamicsEnabled surroundMode=$surroundMode analogBass=$analogBassEnabled mobileBass=$mobileBassEnabled harmonicExciter=$harmonicExciterEnabled mbcBands=$mbcBandCount sbcConditioning=$sbcConditioningEnabled bassExtension=$bassExtension trebleExtension=$trebleExtension mobileBassExtension=$mobileBassExtension budget=$budgetDb trim=$autoTrimDb btPlan=$bluetoothPlanActive")
            true
        } catch (e: Exception) {
            Log.e("DspEngine", "Failed to attach DynamicsProcessing — old DP preserved if present", e)
            try { newDynamicsProcessing?.release() } catch (cleanupEx: Exception) {
                Log.w("DspEngine", "Failed to release partially-constructed DP (OEM HAL issue?): ${cleanupEx.message}")
            }
            currentLimiter = null
            // The old DP that survives has its own layout; stop writing plans at it.
            bluetoothPlanActive = false
            liveBluetoothPlan = emptyList()
            false
        }
    }

    /**
     * Attaches/detaches the LoudnessEnhancer used for Tube Warmth's saturation
     * character. LoudnessEnhancer's internal compander applies a soft-knee gain
     * curve, which at modest target gains behaves like gentle program-dependent
     * saturation rather than a hard ceiling.
     */
    private fun configureTubeWarmthSaturation(sessionId: Int, enabled: Boolean, intensity: Float) {
        try {
            if (enabled) {
                val targetGainMb = (intensity.coerceIn(0f, 1f) * 400).toInt() // 0-4 dB
                // If the session changed, release the old LoudnessEnhancer and create a
                // new one bound to the new session. Without this, Tube Warmth silently
                // stays attached to the previous track's session and has no effect on
                // the new one (the effect is still "running" on a session that no longer
                // exists, so it processes nothing and wastes the headroom budget).
                if (loudnessEnhancer != null && loudnessEnhancerSessionId != sessionId) {
                    loudnessEnhancer?.enabled = false
                    loudnessEnhancer?.release()
                    loudnessEnhancer = null
                }
                val enhancer = loudnessEnhancer ?: LoudnessEnhancer(sessionId).also {
                    loudnessEnhancer = it
                    loudnessEnhancerSessionId = sessionId
                }
                enhancer.setTargetGain(targetGainMb)
                enhancer.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null
                loudnessEnhancerSessionId = -1
            }
        } catch (e: Exception) {
            if (enabled) loudnessEnhancerUnavailable = true
            Log.e("DspEngine", "Error configuring Tube Warmth saturation", e)
        }
    }

    /**
     * Live-update both of Mobile Bass's stages without a full topology
     * rebuild: the small static PostEQ baseline, and the 90-300Hz punch
     * band. The MBC band's index depends on whether Analog Bass and/or
     * DBFB are also active (their bands always come first — see
     * configureMbc), so the caller passes their current state to locate it.
     */
    fun updateMobileBassIntensity(intensity: Float, analogBassEnabled: Boolean, dbfbMode: DbfbMode) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val clamped = intensity.coerceIn(0f, 1f)
            // No sub-bass cut — only ever adds to what's already audible.
            val band0 = dp.getPostEqByChannelIndex(0).getBand(0).apply {
                cutoffFrequency = 90f
                gain = 0f
            }
            val band1 = dp.getPostEqByChannelIndex(0).getBand(1).apply {
                cutoffFrequency = 300f
                // Not extension-scaled — see configureMbc's punch band.
                gain = clamped * 2.5f
            }
            val band2 = dp.getPostEqByChannelIndex(0).getBand(2).apply {
                cutoffFrequency = 800f
                gain = 0f
            }
            dp.setPostEqBandAllChannelsTo(0, band0)
            dp.setPostEqBandAllChannelsTo(1, band1)
            dp.setPostEqBandAllChannelsTo(2, band2)

            // Mobile Bass is enabled here by construction (this function only
            // runs while it's on) — its leveler band is always the LAST of
            // its own 1-2 bands, so this is analogBass+dbfb+(mobileBass-1).
            val counts = mbcBandCounts(hiResEnabled = false, dbfbMode, analogBassEnabled, mobileBassEnabled = true, hdrDynamicsEnabled = false, harmonicExciterEnabled = false)
            val mbcIndex = counts.analogBass + counts.dbfb + counts.mobileBass - 1
            val mbcBand = dp.getMbcByChannelIndex(0).getBand(mbcIndex).apply {
                ratio = 1.1f + clamped * 0.2f
                postGain = clamped * 6f
            }
            dp.setMbcBandAllChannelsTo(mbcIndex, mbcBand)
            Log.d("DspEngine", "Mobile Bass updated: postGain=${clamped * 6f}dB shelf=${clamped * 2.5f}dB")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating Mobile Bass intensity", e)
        }
    }

    /**
     * Live-update the Harmonic Exciter's presence-lift MBC band (the second
     * of its two bands — the first is a transparent 0-2000Hz guard band
     * that never needs updating) without a full topology rebuild. The
     * band's index depends on how many bands every feature configured
     * BEFORE it in configureMbc() contributed — Analog Bass, DBFB, Mobile
     * Bass, then HDR, then the exciter's own guard band — replicating that
     * same additive counting here to locate it. HDR always contributes
     * exactly 1 band whether or not HiRes is active (HiRes itself is
     * configured AFTER the exciter's bands, so it never affects this index).
     */
    fun updateHarmonicExciterIntensity(
        intensity: Float,
        analogBassEnabled: Boolean,
        dbfbMode: DbfbMode,
        mobileBassEnabled: Boolean,
        hdrDynamicsEnabled: Boolean,
        trebleExtension: Float = 1f
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            // The caller's refreshHeadroom rewrites the Bluetooth plan with it.
            if (bluetoothPlanActive) return@synchronized
            val clamped = intensity.coerceIn(0f, 1f)
            // Exciter is enabled here by construction (only runs while it's
            // on) — its lift band is always the LAST of its own 1-2 bands
            // (guard band skipped when HDR's own band already covers
            // 0-2000Hz — see configureMbc).
            val counts = mbcBandCounts(hiResEnabled = false, dbfbMode, analogBassEnabled, mobileBassEnabled, hdrDynamicsEnabled, harmonicExciterEnabled = true)
            val mbcIndex = counts.analogBass + counts.dbfb + counts.mobileBass + counts.hdr + counts.harmonicExciter - 1
            // Not scaled by trebleExtension: 2-8kHz presence/clarity is
            // reproducible by essentially any driver, unlike HiRes's true
            // air-band (9.6-20kHz), where extension genuinely varies by
            // driver quality. Scaling this down the same way made the
            // exciter barely audible on IEM/headphone device types for no
            // acoustically-grounded reason.
            val mbcBand = dp.getMbcByChannelIndex(0).getBand(mbcIndex).apply {
                ratio = 1.3f + clamped * 0.4f
                postGain = clamped * 5f
            }
            dp.setMbcBandAllChannelsTo(mbcIndex, mbcBand)
            Log.d("DspEngine", "Harmonic Exciter updated: lift=${clamped * 5f}dB")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating Harmonic Exciter intensity", e)
        }
    }

    /** Live-update the Tube Warmth saturation amount without a full topology rebuild. */
    fun updateTubeWarmthIntensity(intensity: Float) = synchronized(this) {
        try {
            loudnessEnhancer?.setTargetGain((intensity.coerceIn(0f, 1f) * 400).toInt())
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating Tube Warmth intensity", e)
        }
    }

    /**
     * Recompute the gain-budget headroom offset and re-apply it to the live
     * limiter, without a full topology rebuild. Needed because Tube Warmth's
     * intensity slider (see updateTubeWarmthIntensity) changes its broadband
     * contribution to the gain budget after attach() has already run — without
     * this, the limiter ceiling would stay based on the intensity at the time
     * the feature was enabled, drifting out of sync with the slider.
     */
    fun updateHeadroom(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean,
        tubeWarmthEnabled: Boolean,
        tubeWarmthIntensity: Float,
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f,
        surroundMode: SurroundMode = SurroundMode.Off,
        harmonicExciterEnabled: Boolean = false,
        harmonicExciterIntensity: Float = 0.5f,
        bassExtension: Float = 1f,
        trebleExtension: Float = 1f,
        mobileBassExtension: Float = 1f,
        preEqBassPeakDb: Float = 0f,
        preEqTreblePeakDb: Float = 0f,
        preciseGainStaging: Boolean = true,
        keepFullLoudness: Boolean = false,
        preEqLeftDb: FloatArray = FloatArray(EqBands.count),
        preEqRightDb: FloatArray = preEqLeftDb,
        volumeHeadroomDb: Float = 0f
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        val limiter = currentLimiter ?: return@synchronized
        try {
            val budgetDb = calculateGainBudget(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity, mobileBassEnabled, mobileBassIntensity, surroundMode, harmonicExciterEnabled, harmonicExciterIntensity, bassExtension, trebleExtension, mobileBassExtension, preEqBassPeakDb, preEqTreblePeakDb)
            // Must match attach()'s split exactly — Tube Warmth included, see
            // the long note there on why it is no longer excluded. If these two
            // disagree, a live refresh silently re-voices the feature.
            val usePreciseStaging = preciseGainStaging
            val headroomDb = if (bluetoothPlanActive) 0f else limiterOffsetDb(budgetDb, usePreciseStaging)
            val trimDb = when {
                !usePreciseStaging -> 0f
                bluetoothPlanActive -> bluetoothTrimDb(preEqLeftDb, preEqRightDb, volumeHeadroomDb)
                // "Keep full loudness": no input trim on wired, DAC and speaker.
                // Boosts on a hot master can clip; that is the user's trade.
                keepFullLoudness -> 0f
                else -> levelMatchTrimDb(
                    preEqLeftDb, preEqRightDb,
                    stageBoostDb(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity,
                                 mobileBassEnabled, mobileBassIntensity, harmonicExciterEnabled, harmonicExciterIntensity,
                                 bassExtension, trebleExtension),
                    volumeHeadroomDb
                )
            }
            if (bluetoothPlanActive) writeBluetoothPlan(dp, planBluetoothMbc(
                hiResEnabled, dbfbMode, analogBassEnabled, analogBassVoicing,
                harmonicExciterEnabled, harmonicExciterIntensity, bassExtension, trebleExtension,
                preEqLeftDb, preEqRightDb, bluetoothSbcCrestHz, bluetoothHdrPure
            ))
            gainBudgetDb = budgetDb
            limiter.threshold = baseLimiterThreshold + headroomDb
            limiter.postGain = effectivePostGainDb()
            dp.setLimiterAllChannelsTo(limiter)
            // Only re-write the input stage when the trim actually moved —
            // setInputGainAllChannelsTo on every headroom refresh (which
            // happens on every slider tick) would be a pointless DP write.
            if (trimDb != autoTrimDb) {
                autoTrimDb = trimDb
                autoTrimReadoutDb = trimDb
                applyInputGain()
            }
            Log.d("DspEngine", "Headroom: budget=${budgetDb}dB threshold=${limiter.threshold}dB trim=${trimDb}dB")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating headroom", e)
        }
    }

    /**
     * Configures one MBC band as SBC's HF crest-control stage — the part of
     * SbcEngine that attacks the watery-highs artifact at its source.
     *
     * The artifact is not tonal. SBC redraws its bit allocation every 2.9ms
     * frame from each subband's peak-derived scale factor, so a high subband
     * sitting near the zero-bit threshold flickers on and off ~344 times a
     * second. Compressing the peaks across subbands 4-7 lowers that scale
     * factor, which both shrinks the quantisation step and lifts the subband
     * clear of the threshold it was oscillating around.
     *
     * Deliberately no makeup gain: restoring the level would restore the
     * scale factor this band exists to lower.
     */
    private fun configureSbcCrestBand(band: MbcBandSpec, cutoffHz: Float) {
        band.cutoffFrequency = cutoffHz
        band.attackTime = SbcEngine.CREST_ATTACK_MS
        band.releaseTime = SbcEngine.CREST_RELEASE_MS
        band.ratio = SbcEngine.CREST_RATIO
        band.threshold = SbcEngine.CREST_THRESHOLD_DB
        band.kneeWidth = SbcEngine.CREST_KNEE_DB
        band.noiseGateThreshold = -90f
        band.expanderRatio = 1f
        band.preGain = 0f
        band.postGain = 0f
    }

    internal fun planMbc(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        hdrDynamicsEnabled: Boolean = false,
        hdrMode: HdrMode = HdrMode.Restoration,
        analogBassEnabled: Boolean = false,
        analogBassDrive: Float = 0.4f,
        analogBassWarmth: Float = 0.7f,
        analogBassDrift: Float = 0.2f,
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f,
        harmonicExciterEnabled: Boolean = false,
        harmonicExciterIntensity: Float = 0.5f,
        bassExtension: Float = 1f,
        trebleExtension: Float = 1f,
        mobileBassExtension: Float = 1f,
        hdrRestorationThreshold: Float = -38f,
        hdrRestorationKnee: Float = 14f,
        hdrRestorationExpanderRatio: Float = 1.12f,
        sbcConditioningEnabled: Boolean = false,
        sbcCrestCutoffHz: Float = 11_025f
    ): List<MbcBandSpec> {
        // ═══════════════════════════════════════════════════════════════
        // DO NOT "FIX" THE ANALOG BASS + DBFB BAND ORDER. READ THIS FIRST.
        // ═══════════════════════════════════════════════════════════════
        // With both features on, this function emits cutoffs in the order
        //
        //     60, 120, 300,   72, 145, 260,   ...
        //      └ Analog Bass ┘ └─ DBFB ─────┘
        //
        // which is NOT strictly ascending, and DynamicsProcessing.Mbc
        // documents that it should be. Every static reading of this code
        // concludes it is a bug. It has been "found" more than once.
        //
        // It was changed once, to a correctly merged ascending layout. The
        // result sounded materially WORSE and the change was reverted.
        //
        // What actually happens (AOSP DPFrequency.cpp walks bins with
        // binNext = binStop + 1, even backwards): at the 2048 frame, DBFB's
        // 72Hz band gets no bins, and its 145/260Hz bands RE-PROCESS bins
        // Analog Bass already compressed and boosted (93-257Hz). At the old
        // 512 frame the accident was different again — so the voicing this
        // comment protected changed when the frame did. See docs/DSP_SOP.md.
        //
        // The original theory: with the array out of order, some of the two
        // features' overlapping bands end up with degenerate bin ranges and
        // go inert. Sorting them makes Analog Bass AND DBFB both fully apply
        // across the same 60-300Hz region, and they stack into an
        // over-boosted, muddy low end. The two features were each voiced by
        // ear assuming they own the low end; making them both real at once
        // is a different, worse tuning — not a correction.
        //
        // So this ordering is load-bearing. If it is ever revisited, it is a
        // deliberate RE-VOICING (merge the layout AND retune the combined
        // gains by ear, A/B against the current build), never a refactor.
        // The regression test is simply: Analog Bass + DBFB, both on, must
        // sound exactly as they do today.
        // ═══════════════════════════════════════════════════════════════
        val bands = mutableListOf<MbcBandSpec>()
        fun band() = MbcBandSpec().also { bands += it }

        // ── Analog Bass Engine: Drive-controlled saturation simulation (20-300Hz) ──
        // Drive controls preGain (0→9dB drive), warmth controls postGain and ratio.
        // These bands produce clearly audible compression-saturation effects.
        if (analogBassEnabled) {
            // Drive scaled with ^0.75 power curve: low-to-mid drive is more expressive
            // and "analog" feeling; extreme drive tapers to avoid the slamming complaint
            // that prompted the earlier halving. Total range unchanged (0–9 dB) on a
            // "General"/full-extension device; bassExtension scales it down on devices
            // whose driver can't move that much air without distorting.
            val driveGain  = Math.pow(analogBassDrive.toDouble(), 0.75).toFloat() * 9f * bassExtension
            val warmthGain = analogBassWarmth * 3f * bassExtension    // 0–3 dB warmth output
            val compRatio  = 1.8f + analogBassDrive * 3.2f  // ratio 1.8–5.0
            // Drift: widens the compression knee from tight/digital (8dB) to
            // loose/vintage (28dB). A wider knee means the compressor eases in
            // gradually over a broader range below threshold rather than snapping
            // in at a fixed point — this softer onset is the audible "looseness"
            // associated with analog hardware. At drift=0 the knee is narrow and
            // the onset is precise (modern/digital feel); at drift=1 it's wide and
            // forgiving (worn/vintage feel).
            val driftKnee  = 8f + analogBassDrift * 20f  // 8–28 dB knee

            // Sub-bass (20-60Hz): threshold rides the BODY of bass notes, not just peaks.
            // At moderate drive the compressor is engaged most of the time a bass note
            // plays — that continuous gentle gain reduction + release bloom IS the
            // "mesmerizing" analog saturation character. Threshold range: -24 to -12 dBFS
            // (modern bass content averages -18 to -24 dBFS RMS, so this starts engaging
            // at the heart of every note rather than waiting for an occasional peak).
            band().apply {
                cutoffFrequency = 60f
                attackTime = 8f
                releaseTime = 280f   // long release = bloom/sustain after each note
                ratio = compRatio
                threshold = -24f + analogBassDrive * 12f  // -24 to -12 dBFS: rides the body
                kneeWidth = driftKnee
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain  = driveGain * 0.8f              // restored — saturation needs input drive
                postGain = warmthGain + driveGain * 0.2f
            }
            // Low bass (60-120Hz): warmth body — threshold lowered so it too rides note body
            band().apply {
                cutoffFrequency = 120f
                attackTime = 12f
                releaseTime = 240f
                ratio = 1.2f + analogBassWarmth * 0.8f  // 1.2–2.0
                threshold = -24f
                kneeWidth = driftKnee
                noiseGateThreshold = -88f
                expanderRatio = 1f
                preGain  = warmthGain * 0.5f
                postGain = warmthGain * 1.5f
            }
            // Upper bass (120-300Hz): harmonic injection + mud control
            band().apply {
                cutoffFrequency = 300f
                attackTime = 16f
                releaseTime = 200f
                ratio = 1.3f + analogBassDrive * 0.3f
                threshold = -20f
                kneeWidth = driftKnee
                noiseGateThreshold = -86f
                expanderRatio = 1f
                preGain  = warmthGain * 0.3f   // small drive injection adds harmonics in punch band
                postGain = warmthGain * 0.6f
            }
        }

        // ── DBFB: Dynamic Bass Feedback (bands 0-260Hz) ──────────────
        // Thresholds were -8.5 to -12.5 dBFS — music averages -12 to -20 dBFS,
        // so the compressor barely engaged. Set to -18/-20 dBFS so compression
        // triggers on the body of normal program material without constant gain
        // reduction that would cause pumping or dynamics loss.
        if (dbfbMode != DbfbMode.Off) {
            // Reduced from 2.2/4.0 and 1.2/2.2 — those levels pushed the bass
            // region close enough to full scale that the limiter (now a real
            // 10:1 safety net, see attach()) was constantly squashing it,
            // which is what caused DBFB to sound distorted at high volume.
            val normal = dbfbMode == DbfbMode.Normal
            val subPostGain = dbfbSubPostGainDb(normal, bassExtension)
            val punchPostGain = dbfbPunchPostGainDb(normal, bassExtension)

            // Attack/release slowed and ratios reduced from the original
            // 6-10ms / 90-140ms / 1.6-2.2:1 settings, which reacted to
            // individual bass notes fast enough to cause audible per-note
            // "pumping"/breathing. Slower envelopes make the bands act as a
            // gentle overall leveler instead, while postGain (the actual
            // amount of boost) is unchanged.
            band().apply {
                cutoffFrequency = 72f
                attackTime = 22f
                releaseTime = 280f
                ratio = DBFB_SUB_RATIO
                threshold = DBFB_SUB_THRESHOLD_DB
                kneeWidth = DBFB_SUB_KNEE_DB
                noiseGateThreshold = -80f
                expanderRatio = 1.1f
                preGain = 0f
                postGain = subPostGain
            }
            band().apply {
                cutoffFrequency = 145f
                attackTime = 22f
                releaseTime = 280f
                ratio = DBFB_PUNCH_RATIO
                threshold = DBFB_PUNCH_THRESHOLD_DB
                kneeWidth = DBFB_PUNCH_KNEE_DB
                noiseGateThreshold = -82f
                expanderRatio = 1.05f
                preGain = 0f
                postGain = punchPostGain
            }
            band().apply {
                cutoffFrequency = 260f
                attackTime = 22f
                releaseTime = 280f
                ratio = 1.2f
                threshold = -18f
                kneeWidth = 6f
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain = 0f
                postGain = if (normal) -0.4f else -0.6f
            }
        }

        // ── JadOO Mobile Bass: punch band (90-300Hz) ────────────────────
        // Vivo/iQOO (and most modern phones generally) drive the speaker
        // through a "smart PA" amplifier chip (e.g. Awinic/Goodix-class)
        // with its OWN on-chip DSP doing real-time excursion/thermal
        // protection and dynamic range control, downstream of everything
        // Android-side — including us. The cone excursion needed for a
        // given loudness roughly doubles per octave down, so that
        // protection clamps hardest in the 40-90Hz range. An earlier
        // version added a small 40-90Hz "thump" band there; it almost
        // certainly fought that hardware protection for little audible
        // gain while still spending the headroom budget. Consolidating
        // everything into the one range that actually reaches the ear
        // without a hardware fight — 90-300Hz, where excursion demands are
        // far lower — both removes that wasted/fought stage and lets the
        // remaining boost be generous enough to be unmistakable when toggled.
        //
        // 250-800Hz is the "boxy/nasal/mud" region — boosting it reads as
        // congestion, not bass — so 300Hz is the upper edge, not higher.
        //
        // Dynamics are deliberately gentler than earlier attempts (ratio
        // 1.1-1.3, threshold -16dBFS, slow 60ms attack): the smart PA chip
        // is already doing its own compression/limiting downstream, so
        // stacking a second aggressive compressor on top is what was
        // reading as "processed"/"doesn't sound like music" rather than
        // like a cleaner boost. Most program material now just gets the
        // postGain lift directly; only genuinely loud passages get gently
        // reined in. The slow attack still protects transients ("beat")
        // from being clamped before they're heard.
        //
        // Skipped/repositioned when Analog Bass/DBFB already own part of
        // this range with their own shaping (see levelerCutoff) — stacking
        // another bass stage on top of theirs is what caused mud/distortion
        // complaints earlier.
        if (mobileBassEnabled) {
            val clamped = mobileBassIntensity.coerceIn(0f, 1f)
            if (!analogBassEnabled && dbfbMode == DbfbMode.Off) {
                band().apply {
                    cutoffFrequency = 90f
                    attackTime = 5f
                    releaseTime = 65f
                    ratio = 1f
                    threshold = 0f
                    kneeWidth = 0f
                    noiseGateThreshold = -90f
                    expanderRatio = 1f
                    preGain = 0f
                    postGain = 0f
                }
            }
            val levelerCutoff = when {
                analogBassEnabled -> 350f          // above Analog Bass's 300Hz top band
                dbfbMode != DbfbMode.Off -> 320f    // above DBFB's 260Hz top band
                else -> 300f                        // the actual target
            }
            band().apply {
                cutoffFrequency = levelerCutoff
                attackTime = 60f
                releaseTime = 180f
                ratio = 1.1f + clamped * 0.2f       // 1.1-1.3 — gentle, avoids stacking with the smart PA's own limiter
                threshold = -16f
                kneeWidth = 8f
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain = 0f
                // ── NOT scaled by mobileBassExtension. Read this before
                // "restoring" the scaling. ────────────────────────────────
                // This used to be `clamped * 6f * mobileBassExtension`, and
                // that made the whole feature inaudible. mobileBassExtension is
                // CompactSpeaker.bassExtension(deviceQualityTier), and on the
                // phone-speaker route deviceQualityTier is stuck at its 0.5
                // default (setDeviceType early-returns on that route, so it
                // never reaches the 0.85 it sets elsewhere). CompactSpeaker's
                // bass range is 0.2..0.55, so the scale factor was 0.375. At
                // the default 0.5 intensity that left:
                //
                //     MBC postGain  0.5 * 6.0 * 0.375 = 1.13 dB
                //     PostEQ shelf  0.5 * 2.5 * 0.375 = 0.47 dB
                //     total                             1.60 dB  (90-300Hz)
                //
                // against a broadband headroom charge of 1.41 dB. A 1.6 dB
                // narrowband lift paid for with a 1.4 dB broadband cut is, to
                // the ear, nothing at all — which is exactly how the feature
                // was reported: "Mobile Bass does nothing."
                //
                // The scaling was also backwards on its own terms. Every other
                // feature is extension-scaled because a small driver cannot
                // physically deliver deep bass, so asking for it wastes
                // headroom. Mobile Bass is the feature built FOR that speaker:
                // it deliberately works at 90-300Hz, where a tiny driver has no
                // excursion problem (see the band comment above), and it exists
                // to compensate for exactly the limitation the scaling assumes.
                // Scaling it down in proportion to how small the speaker is
                // inverts its purpose.
                postGain = clamped * 6f   // 0-6 dB
            }
        }

        // ── HDR: dynamics character band ──────────────────────────────
        //
        // Pure mode: this band is fully transparent (ratio/expanderRatio = 1,
        // no gain change). Combined with the near-unity safety limiter in
        // attach(), Pure HDR adds nothing to the signal — true acoustic
        // transparency, as its description promises.
        //
        // Restoration mode: a gentle downward expander (expanderRatio 1.15)
        // around -32 dBFS widens the gap between quiet passages and the music
        // itself, restoring a sense of dynamic range to over-compressed masters.
        // Crucially it does NOT touch peaks (ratio = 1, ie. no compression), so
        // it can no longer "squash" already-loud material — that compression
        // was the root cause of HDR sounding trashy. The other half of
        // Restoration's character is the air-shelf boost applied in
        // JadooDspService.applyAllBands().
        if (hdrDynamicsEnabled) {
            val isRestoration = hdrMode == HdrMode.Restoration
            // When HiRes is off AND Harmonic Exciter is off, HDR's band is the
            // final word — it can safely close out the array at 20000f and
            // return immediately. If Harmonic Exciter IS on, it still needs
            // its own band after this one, so HDR must stop short (5200f if
            // HiRes is also on, 8000f to hand off to the exciter's range
            // otherwise) and fall through instead of returning — the
            // previous version always returned here whenever HiRes was off,
            // silently skipping the exciter's entire band (and corrupting
            // the MBC band array, since attach() had already reserved a slot
            // for it that never got configured) any time HDR was active
            // without HiRes — exactly why the exciter "did nothing" in that
            // combination.
            // Restoration expander parameters:
            // threshold -38dBFS: targets reverb tails, breaths, room ambience — the
            //   actual sub-floor of a brickwalled master, without engaging on soft
            //   musical notes and making them quieter instead of restoring range.
            // kneeWidth 14f: gentle onset so the transition from "expanded" to
            //   "normal" is inaudible rather than a noticeable step.
            // expanderRatio 1.12f: subtle dynamic widening at the floor, restrained
            //   enough to stay inaudible on well-mastered sources.
            // releaseTime 320ms: longer release avoids per-note pumping on
            //   modulated/quiet-heavy content.
            // The three shape values below now arrive as parameters so the
            // content channel can retune them without shipping an APK — the
            // defaults are exactly the values documented above.
            val restorationThreshold = hdrRestorationThreshold
            val restorationKnee = hdrRestorationKnee
            val restorationExpanderRatio = hdrRestorationExpanderRatio
            val restorationAttack = 30f
            val restorationRelease = 320f

            if (!hiResEnabled && !harmonicExciterEnabled) {
                // Normally HDR's band closes the array at 20000Hz on its own.
                // With SBC conditioning on it stops at the subband 3/4 edge
                // instead and hands the rest to the crest band, which then
                // closes the array — the extra slot is reserved by
                // MbcBandCounts.sbcCrest.
                band().apply {
                    cutoffFrequency = if (sbcConditioningEnabled) sbcCrestCutoffHz else 20000f
                    attackTime = if (isRestoration) restorationAttack else 15f
                    releaseTime = if (isRestoration) restorationRelease else 180f
                    ratio = 1.0f
                    threshold = if (isRestoration) restorationThreshold else 0f
                    kneeWidth = if (isRestoration) restorationKnee else 0f
                    noiseGateThreshold = -90f
                    expanderRatio = if (isRestoration) restorationExpanderRatio else 1.0f
                    preGain = 0f
                    postGain = 0f
                }
                if (sbcConditioningEnabled) {
                    configureSbcCrestBand(band(), 20000f)
                }
                return bands  // fully configured: [AnalogBass?] + [DBFB?] + [MobileBass?] + HDR(1) [+ SBC crest] = done
            }
            if (harmonicExciterEnabled) {
                // Harmonic Exciter is on: HDR's band must stop at the
                // exciter's lower edge (2000Hz, the exciter's own guard
                // cutoff) regardless of HiRes, so the array stays strictly
                // ascending and HDR's own band doubles as the exciter's
                // 0-2000Hz guard (no separate guard band needed — see the
                // exciter block below).
                band().apply {
                    cutoffFrequency = 2000f
                    attackTime = if (isRestoration) restorationAttack else 15f
                    releaseTime = if (isRestoration) restorationRelease else 180f
                    ratio = 1.0f
                    threshold = if (isRestoration) restorationThreshold else 0f
                    kneeWidth = if (isRestoration) restorationKnee else 0f
                    noiseGateThreshold = -90f
                    expanderRatio = if (isRestoration) restorationExpanderRatio else 1.0f
                    preGain = 0f
                    postGain = 0f
                }
            } else {
                // Exciter off, HiRes on: hand off cleanly at the HiRes crossover (5.2 kHz)
                band().apply {
                    cutoffFrequency = 5200f
                    attackTime = if (isRestoration) restorationAttack else 15f
                    releaseTime = if (isRestoration) restorationRelease else 180f
                    ratio = 1.0f
                    threshold = if (isRestoration) restorationThreshold else 0f
                    kneeWidth = if (isRestoration) restorationKnee else 0f
                    noiseGateThreshold = -90f
                    expanderRatio = if (isRestoration) restorationExpanderRatio else 1.0f
                    preGain = 0f
                    postGain = 0f
                }
            }
        }

        // ── Harmonic Exciter: presence/clarity lift (2-8kHz) ─────────
        // DynamicsProcessing.Mbc is a real gain-vs-level compressor, not a
        // waveshaper — it cannot literally synthesize new harmonic content
        // the way analog saturation hardware does. The earlier "drive into
        // a compressor's knee" design assumed it could: at threshold=-22dB
        // and ratio<=1.5, a +4dB preGain drive on typical program material
        // (which already sits above -22dBFS most of the time) gets almost
        // entirely compressed back out before the +2dB makeup gain is
        // applied, netting under 1dB of real change — inaudible. Replaced
        // with an honest mechanism: a direct postGain lift on the 2-8kHz
        // presence band, restrained by a moderate downward-compression
        // ratio so only genuinely loud peaks in that range are reined in
        // (protects against sibilance/harshness on bright masters) while
        // normal program material gets the full lift. This is a clarity/
        // presence boost, not true harmonic generation — but it's the
        // honest, audible version of what this API can actually do.
        //
        // A transparent guard band first carves out 0-2000Hz so the lift
        // is actually confined to presence frequencies regardless of what
        // ran before it in the chain — without it, this band would start
        // at 0Hz (or wherever the previous feature's last band ended) and
        // apply across all of bass/midrange too, which is why the feature
        // sounded like "nothing happened": the gain was real but diluted
        // across the whole spectrum instead of concentrated where it's
        // audible.
        //
        // When HiRes is enabled, the lift band stops at 5200Hz to hand off
        // cleanly to HiRes's own first band (which already covers
        // 5200Hz+). When HiRes is off, it covers up to 8000Hz — short of
        // 20kHz, so the Fallback section's closing band still runs after
        // this to cover the rest of the spectrum.
        if (harmonicExciterEnabled) {
            val clamped = harmonicExciterIntensity.coerceIn(0f, 1f)
            // When HDR is on, its own band just above already covers
            // 0-2000Hz transparently, so skip this separate guard band —
            // writing it too would duplicate HDR's 2000Hz cutoff and
            // corrupt the MBC array's required ascending order.
            if (!hdrDynamicsEnabled) {
                band().apply {
                    cutoffFrequency = 2000f
                    attackTime = 5f
                    releaseTime = 65f
                    ratio = 1f
                    threshold = 0f
                    kneeWidth = 0f
                    noiseGateThreshold = -90f
                    expanderRatio = 1f
                    preGain = 0f
                    postGain = 0f
                }
            }
            band().apply {
                cutoffFrequency = if (hiResEnabled) 5200f else 8000f
                attackTime = 20f   // slow attack preserves transient snap; instant attack caused IMD
                releaseTime = 150f // smooth release avoids pumping on sustained presence content
                ratio = 1.3f + clamped * 0.4f   // 1.3-1.7 — reins in loud peaks only
                threshold = -12f                 // engages only on genuinely loud presence content
                kneeWidth = 8f
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain = 0f
                // Not scaled by trebleExtension — see updateHarmonicExciterIntensity.
                postGain = clamped * 5f  // 0-5dB
            }
        }

        // ── HiRes: Air-band expansion (5200Hz–20kHz) ─────────────────
        if (hiResEnabled) {
            // Unless HDR or the exciter already ends at 5200Hz, add a 0 dB band
            // that does. DBFB used to count here, but it ends at 260Hz: with
            // DBFB on, HiRes's +2.5 dB presence band ran from ~280Hz to 9.6kHz
            // — a broadband mid boost (found by MbcPlanTest).
            // Harmonic Exciter's lift band ends at 5200Hz when HiRes is on — skip the safety band
            // to avoid two consecutive bands with the same cutoff, which corrupts the MBC array.
            if (!hdrDynamicsEnabled && !harmonicExciterEnabled) {
                band().apply {
                    cutoffFrequency = 5200f
                    attackTime = 9f
                    releaseTime = 70f
                    ratio = 1f
                    threshold = 0f
                    kneeWidth = 0f
                    noiseGateThreshold = -90f
                    expanderRatio = 1f
                    preGain = 0f
                    postGain = 0f
                }
            }
            // 5.2–9.6 kHz: presence/clarity — pure linear boost, no compression
            // Threshold-based compression here caused the presence band to be
            // constantly squeezed on loud BT/SBC sources (which sit well above -14dBFS),
            // making HiRes sound "closed" rather than open.
            band().apply {
                cutoffFrequency = 9600f
                attackTime = 2f
                releaseTime = 50f
                ratio = 1.0f
                threshold = 0f
                kneeWidth = 0f
                noiseGateThreshold = -90f
                expanderRatio = 1.0f
                preGain = 0f
                postGain = 2.5f * trebleExtension
            }
            // 9.6–14.5 kHz: silk — pure linear boost
            //
            // With SBC conditioning also on, these top two bands ARE the crest
            // control: they already split at 14.5kHz, so compressing them in
            // place costs no extra band (which is why MbcBandCounts.sbcCrest
            // is 0 whenever HiRes is on). Their boost is scaled down at the
            // same time — a +4/+5.5dB air lift is exactly the bit-stealing
            // move SbcEngine exists to undo, since SBC hands bits out in
            // proportion to each subband's level and the top octave is both
            // the least audible and the most expensive place to spend them.
            band().apply {
                cutoffFrequency = 14500f
                attackTime = if (sbcConditioningEnabled) SbcEngine.CREST_ATTACK_MS else 2f
                releaseTime = if (sbcConditioningEnabled) SbcEngine.CREST_RELEASE_MS else 50f
                ratio = if (sbcConditioningEnabled) 2.4f else 1.0f
                threshold = if (sbcConditioningEnabled) -24f else 0f
                kneeWidth = if (sbcConditioningEnabled) SbcEngine.CREST_KNEE_DB else 0f
                noiseGateThreshold = -90f
                expanderRatio = 1.0f
                preGain = 0f
                postGain = 4.0f * trebleExtension *
                    (if (sbcConditioningEnabled) SbcEngine.AIR_SCALE_SILK else 1f)
            }
            // 14.5–20 kHz: pure air — pure linear boost
            band().apply {
                cutoffFrequency = 20000f
                attackTime = if (sbcConditioningEnabled) SbcEngine.CREST_ATTACK_MS else 2f
                releaseTime = if (sbcConditioningEnabled) SbcEngine.CREST_RELEASE_MS else 50f
                ratio = if (sbcConditioningEnabled) SbcEngine.CREST_RATIO else 1.0f
                threshold = if (sbcConditioningEnabled) SbcEngine.CREST_THRESHOLD_DB else 0f
                kneeWidth = if (sbcConditioningEnabled) SbcEngine.CREST_KNEE_DB else 0f
                noiseGateThreshold = -90f
                expanderRatio = 1.0f
                preGain = 0f
                postGain = 5.5f * trebleExtension *
                    (if (sbcConditioningEnabled) SbcEngine.AIR_SCALE_TOP else 1f)
            }
            return bands  // fully configured: [AnalogBass?] + DBFB(opt) + [MobileBass?] + HDR(opt) + HiRes(4) = done
        }

        // ── Fallback: transparent closing band up to 20000Hz ──────────
        // Needed whenever nothing else already reached Nyquist on its own
        // (HiRes always returns before here; only "HDR alone, no exciter,
        // no HiRes" reaches 20000Hz via its own early return above). This
        // used to also gate on `!hdrDynamicsEnabled`, which meant HDR+
        // Harmonic Exciter (with HiRes off) skipped this band entirely even
        // though HDR's band now stops at 8000f in that combination instead
        // of closing the spectrum itself — leaving a real gap in coverage
        // and an unconfigured trailing band in the array.
        //
        // With SBC conditioning on, this closing band is split in two: the
        // transparent part stops at the subband 3/4 edge and the crest band
        // covers 4-7 up to Nyquist. MbcBandCounts.sbcCrest reserves the slot.
        val closingAttack = if (dbfbMode == DbfbMode.Off) 5f else 6f
        val closingRelease = if (dbfbMode == DbfbMode.Off) 65f else 80f
        band().apply {
            cutoffFrequency = if (sbcConditioningEnabled) sbcCrestCutoffHz else 20000f
            attackTime = closingAttack
            releaseTime = closingRelease
            ratio = 1f
            threshold = 0f
            kneeWidth = 0f
            noiseGateThreshold = -90f
            expanderRatio = 1f
            preGain = 0f
            postGain = 0f
        }
        if (sbcConditioningEnabled) {
            configureSbcCrestBand(band(), 20000f)
        }
        return bands
    }

    /**
     * Configure PostEQ bands for Pultec-style boost/cut on the analog bass path.
     * Band 0: Low-shelf boost at/below pultec frequency (Pultec boost)
     * Band 1: Slight dip above pultec frequency (the Pultec simultaneous cut trick)
     * Band 2: Warmth zone body boost
     * Band 3: Full-spectrum endpoint (flat, required to cover Nyquist)
     */
    private fun configureAnalogBassPostEq(
        postEq: DynamicsProcessing.Eq,
        pultecFreqIndex: Int,
        pultecBoost: Float,
        pultecCut: Float,
        warmth: Float,
        bassExtension: Float = 1f
    ) {
        val pultecFreqs = PULTEC_FREQUENCIES_HZ
        val pultecFreq = pultecFreqs[pultecFreqIndex.coerceIn(0, pultecFreqs.size - 1)]
        // Band 0: below/at pultec freq — the Pultec BOOST
        postEq.getBand(0).apply {
            cutoffFrequency = (pultecFreq * 1.5f).coerceIn(25f, 200f)
            gain = pultecBoost * 8f * bassExtension        // 0-8 dB boost, scaled by driver capability
        }
        // Band 1: just above pultec freq — the Pultec simultaneous CUT (creates the resonant dip)
        postEq.getBand(1).apply {
            cutoffFrequency = (pultecFreq * 4f).coerceIn(80f, 500f)
            gain = -(pultecCut * 5f)       // 0–5 dB dip
        }
        // Band 2: warmth zone (mid-bass body)
        postEq.getBand(2).apply {
            cutoffFrequency = 500f
            gain = warmth * 2.5f * bassExtension           // 0-2.5 dB warmth, scaled by driver capability
        }
        // Band 3: full-range endpoint (flat)
        postEq.getBand(3).apply {
            cutoffFrequency = 20000f
            gain = 0f
        }
    }

    /**
     * Configure PostEQ for JadOO Mobile Bass: a small static baseline shelf,
     * layered UNDER the dynamic MBC leveler band (see configureMbc) which
     * supplies most of the actual lift. Real bass content is almost never a
     * pure sine wave — its 2nd/3rd harmonics are already faintly present in
     * the recording. This brings those overtones (roughly 90-350Hz,
     * reproducible by even tiny speakers) forward a little while quietly
     * cutting the sub-bass below that the speaker can't move air at, so the
     * ear leans on the overtones it can actually hear to "fill in" the
     * missing fundamental. Kept deliberately small here — see the MBC band
     * for where most of the boost actually comes from — so the two stages
     * don't stack into the same kind of over-boosted, distorted result the
     * original static-only version had. Never cuts anything — only ever
     * adds to what's already audible, so this can't end up sounding
     * weaker than having the feature off. Targets 90-300Hz to match the MBC
     * band's target — 300-800Hz is the "boxy/nasal" region, and an earlier
     * version boosting up there was exactly what read as mud instead of
     * bass, so there's no boost above 300Hz here at all.
     * Band 0: no-op boundary marker (kept flat — see above)
     * Band 1: small overtone baseline (90-300Hz)
     * Band 2: no-op boundary marker (kept flat — was a "mud" contributor)
     * Band 3: full-spectrum endpoint (flat, required to cover Nyquist)
     */
    private fun configureMobileBassPostEq(postEq: DynamicsProcessing.Eq, intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        postEq.getBand(0).apply { cutoffFrequency = 90f;  gain = 0f }
        // Deliberately not extension-scaled — see the MBC punch band in
        // configureMbc for why that scaling made the feature inaudible.
        postEq.getBand(1).apply { cutoffFrequency = 300f; gain = clamped * 2.5f }
        postEq.getBand(2).apply { cutoffFrequency = 800f; gain = 0f }
        postEq.getBand(3).apply { cutoffFrequency = 20000f; gain = 0f }
    }

    /** Live-update the analog bass PostEQ bands without a full topology rebuild. */
    fun updateAnalogBassPostEq(
        pultecBoost: Float,
        pultecCut: Float,
        pultecFreqIndex: Int,
        warmth: Float,
        bassExtension: Float = 1f
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val pultecFreqs = PULTEC_FREQUENCIES_HZ
            val pultecFreq = pultecFreqs[pultecFreqIndex.coerceIn(0, pultecFreqs.size - 1)]
            val band0 = dp.getPostEqByChannelIndex(0).getBand(0).apply {
                cutoffFrequency = (pultecFreq * 1.5f).coerceIn(25f, 200f)
                gain = pultecBoost * 8f * bassExtension
            }
            val band1 = dp.getPostEqByChannelIndex(0).getBand(1).apply {
                cutoffFrequency = (pultecFreq * 4f).coerceIn(80f, 500f)
                gain = -(pultecCut * 5f)
            }
            val band2 = dp.getPostEqByChannelIndex(0).getBand(2).apply {
                cutoffFrequency = 500f
                gain = warmth * 2.5f * bassExtension
            }
            dp.setPostEqBandAllChannelsTo(0, band0)
            dp.setPostEqBandAllChannelsTo(1, band1)
            dp.setPostEqBandAllChannelsTo(2, band2)
            analogBassVoicing = analogBassVoicing.copy(
                warmth = warmth, pultecBoost = pultecBoost, pultecCut = pultecCut, pultecFreqIndex = pultecFreqIndex
            )
            Log.d("DspEngine", "Analog bass PostEQ updated: boost=${pultecBoost * 8f}dB cut=${-(pultecCut * 5f)}dB warmth=${warmth * 2.5f}dB")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating analog bass PostEQ", e)
        }
    }

    /** Live-update the analog bass MBC bands (drive + warmth + drift) without full rebuild. */
    fun updateAnalogBassMbc(drive: Float, warmth: Float, drift: Float, bassExtension: Float = 1f) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        if (bluetoothPlanActive) {
            // The caller's refreshHeadroom rewrites the plan from this.
            analogBassVoicing = analogBassVoicing.copy(drive = drive, warmth = warmth, drift = drift)
            return@synchronized
        }
        try {
            val driveGain  = Math.pow(drive.toDouble(), 0.75).toFloat() * 9f * bassExtension
            val warmthGain = warmth * 3f * bassExtension
            val compRatio  = 1.8f + drive * 3.2f
            val driftKnee  = 8f + drift * 20f

            val band0 = dp.getMbcByChannelIndex(0).getBand(0).apply {
                ratio     = compRatio
                threshold = -24f + drive * 12f
                kneeWidth = driftKnee
                preGain   = driveGain * 0.8f
                postGain  = warmthGain + driveGain * 0.2f
            }
            val band1 = dp.getMbcByChannelIndex(0).getBand(1).apply {
                ratio     = 1.2f + warmth * 0.8f
                threshold = -24f
                kneeWidth = driftKnee
                preGain   = warmthGain * 0.5f
                postGain  = warmthGain * 1.5f
            }
            val band2 = dp.getMbcByChannelIndex(0).getBand(2).apply {
                ratio     = 1.3f + drive * 0.3f
                kneeWidth = driftKnee
                preGain   = warmthGain * 0.3f
                postGain  = warmthGain * 0.6f
            }
            dp.setMbcBandAllChannelsTo(0, band0)
            dp.setMbcBandAllChannelsTo(1, band1)
            dp.setMbcBandAllChannelsTo(2, band2)
            analogBassVoicing = analogBassVoicing.copy(drive = drive, warmth = warmth, drift = drift)
            Log.d("DspEngine", "Analog bass MBC updated: drive=$drive warmth=$warmth")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating analog bass MBC", e)
        }
    }

    /**
     * Calculate limiter headroom offset based on active features — a "gain
     * budget" model.
     *
     * The limiter is a single BROADBAND stage: one global ceiling for the
     * whole signal. That ceiling only needs to be low enough to cover the
     * worst-hit frequency zone, not the sum of every active feature's gain
     * regardless of where it lands. Two features that boost different,
     * non-overlapping zones don't compound — a single global ceiling that
     * already covers the louder of the two automatically covers the other.
     *
     * Zones:
     *  - Bass (<300Hz): DBFB + AnalogBass genuinely overlap here (60-150Hz),
     *    so they remain additive within this zone.
     *  - Treble (>2kHz): HiRes air-band expansion + Harmonic Exciter presence.
     *  - Broadband: Tube Warmth's LoudnessEnhancer compander raises the level
     *    of the WHOLE signal post-MBC, so it stacks on top of whichever zone
     *    is worst rather than being zone-limited itself.
     */
    private fun calculateGainBudget(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean = false,
        tubeWarmthEnabled: Boolean = false,
        tubeWarmthIntensity: Float = 0.5f,
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f,
        surroundMode: SurroundMode = SurroundMode.Off,
        harmonicExciterEnabled: Boolean = false,
        harmonicExciterIntensity: Float = 0.5f,
        bassExtension: Float = 1f,
        trebleExtension: Float = 1f,
        mobileBassExtension: Float = 1f,
        // Peak static PreEQ gain the caller currently has sitting in the bass/
        // treble zone from Graphic EQ + Loudness Contour combined (see
        // JadooDspService.preEqBassPeakDb/preEqTreblePeakDb — the two are
        // summed per-band there, same as the live PreEQ write, before the
        // peak is taken, so a graphic boost and a loudness boost landing on
        // the SAME band correctly add rather than double-crediting two
        // different bands' separate peaks).
        //
        // Parametric EQ and SBC pre-emphasis are NOT included here, on
        // purpose: getting their real per-band value needs the biquad
        // evaluator (DigitalFilterEngine.evaluateBandGainDb), and this
        // function's result is recomputed on every tick of a live drag. Given
        // MAX_HEADROOM_DB already clamps the credit to 2dB regardless of how
        // large the true peak is, the only case this omission changes
        // anything is "PEQ/SBC boost with nothing else active" — a real but
        // narrow gap, not worth risking the exact per-tick recompute jank
        // this codebase already fixed once (see applySingleBand's own
        // comment) for that little payoff.
        preEqBassPeakDb: Float = 0f,
        preEqTreblePeakDb: Float = 0f
    ): Float {
        var bassZone = 0f
        // DBFB's 72Hz/145Hz bands (postGain up to 2.5dB + 1.4dB) can both be
        // driven by a single bass note that spans their adjacent cutoffs —
        // worst case ~3.9dB on High, ~2.3dB on Normal — vs. the previous flat
        // 1.8/1.0dB credit, which under-padded by ~2dB and let the same
        // limiter-slam issue fixed for Mobile Bass happen (more mildly) here too.
        // Scaled by bassExtension since the actual postGain in configureMbc is too.
        if (dbfbMode == DbfbMode.High) bassZone += 3.9f * bassExtension
        else if (dbfbMode == DbfbMode.Normal) bassZone += 2.3f * bassExtension
        // Analog Bass's 60-120Hz band alone can reach ~3.6dB postGain at max
        // warmth — the previous flat 1.5dB credit under-padded that peak by
        // ~2dB for the same reason as DBFB above.
        if (analogBassEnabled) bassZone += 5.0f * bassExtension
        // Mobile Bass: PostEQ shelf (up to +2.5dB) plus the 90-300Hz punch
        // band's postGain (up to +6dB), stacking in the same region.
        //
        // The naive sum is 8.5dB, but that ignores the punch band's own
        // leveler, which runs at threshold -16dBFS, ratio 1.1-1.3:1 and
        // therefore gives part of the postGain back on exactly the loud
        // passages where the limiter matters. Worst case, in-band input at
        // 0 dBFS and full intensity:
        //
        //     compressed  = -16 + 16/1.3        = -3.7 dBFS
        //     + postGain  = -3.7 + 6.0          = +2.3 dBFS
        //     + PostEQ    = +2.3 + 2.5          = +4.8 dBFS
        //
        // so ~4.8dB at intensity 1.0, near enough linear in intensity. The
        // old 7.5 figure over-charged by ~55%, and the mobileBassExtension
        // factor (0.375 on the speaker route) then rescaled BOTH the charge
        // and the boost so the two almost exactly cancelled — a narrowband
        // lift bought with an equal broadband cut, which is silence to the
        // ear. The boost is no longer extension-scaled (see configureMbc),
        // so neither is the charge.
        if (mobileBassEnabled) bassZone += mobileBassIntensity.coerceIn(0f, 1f) * 4.8f
        // Surround mode's own bass "smile" (see surroundBandProfile in
        // JadooDspService) was never accounted for here at all — its 63Hz/
        // 100Hz boost stacks with every other bass feature's gain, and on
        // Wide it's large enough to push the limiter into audible
        // distortion once something else (e.g. Mobile Bass) is also
        // boosting the same region. Padding by the smile's peak in that
        // overlap (63Hz+100Hz) closes that gap.
        bassZone += when (surroundMode) {
            SurroundMode.Off -> 0f
            SurroundMode.Front -> 1.8f         // 160Hz(+1.8) crossfeed low-mid bloom peak
            SurroundMode.Traditional -> 3.5f   // 63Hz(+2.0) + 100Hz(+1.0) + 160Hz(+0.3) bridge
            SurroundMode.Wide -> 5.5f          // 63Hz(+3.0) + 100Hz(+1.5) + 160Hz(+0.5) bridge; actual sub-bass peak exceeds 4.5f budget on energetic material
        }
        // Graphic EQ + Loudness Contour combined bass leg. Both are PreEQ —
        // static gain, not compressor bands — so the peak really is the
        // peak, and it stacks directly on top of every other bass boost in
        // the same region. Deliberately NOT scaled by bassExtension: neither
        // is a request for extra driver output (Graphic EQ is the user's own
        // explicit ask; Loudness Contour corrects the EAR's response), so the
        // same dB of gain applies regardless of DeviceType.
        bassZone += preEqBassPeakDb.coerceAtLeast(0f)

        var trebleZone = 0f
        // HiRes's "air band" (14.5-20kHz) alone reaches +4.5dB postGain with
        // a fast 0.6ms attack — the previous flat 0.8dB credit under-padded
        // that peak by ~3.7dB, the same under-padding pattern that caused
        // Mobile Bass's limiter-slam bug, just milder here since the air
        // band's energy is narrower-band and less consistently present in
        // program material than a 90-300Hz bass thump is.
        // Surround mode's smile curve also has a treble leg (4kHz/6.3kHz/
        // 10kHz/16kHz — see surroundBandProfile in JadooDspService) that was
        // never credited here at all — only its bass leg (25-100Hz, above)
        // was. This is PreEQ — pure static gain, not a compressor band.
        // Summing all four bands' peaks (as if a single transient hit 4kHz,
        // 6.3kHz, 10kHz AND 16kHz simultaneously at full amplitude) was
        // wrong — real treble energy isn't flat across that whole span, and
        // that summed credit (10-15dB) dropped the limiter ceiling so far
        // it made Wide mode sound flat/lifeless, the opposite problem.
        // Crediting only the single highest band (16kHz, where the smile
        // peaks) is the realistic worst case for what one bright transient
        // can actually push.
        trebleZone += when (surroundMode) {
            SurroundMode.Off -> 0f
            SurroundMode.Front -> 0f           // treble is cut not boosted — no limiter impact
            SurroundMode.Traditional -> 4.0f   // 16kHz peak
            SurroundMode.Wide -> 4.5f          // 16kHz peak (reduced from 6.0f after treble pullback)
        }
        // Harmonic Exciter (2-8kHz) and HiRes (9.6kHz+) are non-overlapping bands —
        // no single transient peaks both simultaneously. When both active, credit only
        // the larger; summing both (up to 10.5dB) was dropping the limiter ceiling far
        // enough to make the combined mode sound compressed on bright masters.
        // Not scaled by trebleExtension — see updateHarmonicExciterIntensity.
        val exciterCredit = if (harmonicExciterEnabled) harmonicExciterIntensity.coerceIn(0f, 1f) * 5f else 0f
        trebleZone += if (hiResEnabled) maxOf(5.5f * trebleExtension, exciterCredit) else exciterCredit
        // Graphic EQ + Loudness Contour combined treble leg — see the bass
        // credit above. Lands in the same 4-16kHz region as HiRes and the
        // exciter, so it has to be added rather than max'd against them.
        trebleZone += preEqTreblePeakDb.coerceAtLeast(0f)

        var broadband = 0f
        if (tubeWarmthEnabled) broadband += (0.5f + tubeWarmthIntensity.coerceIn(0f, 1f) * 1.0f)
        // (clamped at the return — see MAX_HEADROOM_DB)
        // Front Stage's vocal presence lift (1kHz-2.5kHz, peak +1.2dB) sits in the midband,
        // outside both bass and treble zones. Without crediting it here the limiter ceiling
        // is too close to the lifted midband signal, causing audible gain reduction on vocals.
        if (surroundMode == SurroundMode.Front) broadband += 1.2f

        return (broadband + maxOf(bassZone, trebleZone)).coerceAtLeast(0f)
    }

    /**
     * How the gain budget from [calculateGainBudget] is actually paid for.
     *
     * The budget is real: it is dB of gain the feature stack adds on top of
     * program material, and something has to absorb it or the output clips.
     * There are only two places it can come from, and the original code used
     * exactly one of them for the whole amount.
     *
     *  - **Limiter threshold.** Cheap and free-sounding for small amounts,
     *    because a peak catcher that only engages in the top couple of dB
     *    never touches program material. Ruinous for large amounts: it is a
     *    10:1 brickwall, so pulling it down 15 dB means everything above
     *    -15 dBFS is permanently compressed 10:1. The budget was unbounded,
     *    and a realistic speaker setup (Mobile Bass + Loudness Contour at low
     *    volume) reached -19.8 dBFS. That is the "sounds strange / squashed /
     *    Mobile Bass does nothing" report: the limiter was removing precisely
     *    the gain the features were adding.
     *
     *  - **Input trim.** Costs level and nothing else. No dynamics artefacts
     *    at all — the whole mix is simply quieter, and the feature's boost
     *    survives intact RELATIVE to everything around it, which is the part
     *    the ear actually judges. The user turns the volume up and gets what
     *    they asked for.
     *
     * So: spend the first [MAX_HEADROOM_DB] on the threshold, where it is
     * free, and let the input pay for the rest — by [levelMatchTrimDb], not
     * by the nominal budget, which is a narrowband peak and grossly
     * overstates what the boosts do to broadband level.
     *
     * Returns the limiter offset (negative dB, added to the base threshold).
     */
    private fun limiterOffsetDb(budgetDb: Float, preciseGainStaging: Boolean): Float =
        // Legacy: the entire budget goes to the threshold, uncapped, and the
        // input is never touched. Reproduces the pre-1.2 numbers exactly —
        // including the pathological ones.
        if (!preciseGainStaging) -budgetDb else -budgetDb.coerceIn(0f, MAX_HEADROOM_DB)

    /**
     * Per-band boost (dB, on the PreEQ band grid) that the MBC feature bands
     * and their PostEQ shelves add after the PreEQ, placed where in the
     * spectrum each one actually lands. Bands owned by different MBC
     * features never overlap, so a band takes the larger of two neighbours
     * rather than their sum.
     *
     * Wired and speaker routes only: they rarely run at full scale, where
     * the volume headroom absorbs most of the trim anyway. Bluetooth does
     * not trim for these at all — see [planBluetoothMbc].
     */
    internal fun stageBoostDb(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean,
        tubeWarmthEnabled: Boolean,
        tubeWarmthIntensity: Float,
        mobileBassEnabled: Boolean,
        mobileBassIntensity: Float,
        harmonicExciterEnabled: Boolean,
        harmonicExciterIntensity: Float,
        bassExtension: Float,
        trebleExtension: Float
    ): FloatArray {
        val boost = FloatArray(EqBands.count)
        fun lift(range: IntRange, db: Float) {
            for (i in range) boost[i] = maxOf(boost[i], db)
        }
        // Bass: DBFB (≤260 Hz), Analog Bass (≤300 Hz) and Mobile Bass
        // (90-300 Hz) genuinely stack in the same region — summed, as in
        // calculateGainBudget's bass zone.
        val bass = FloatArray(EqBands.count)
        if (dbfbMode != DbfbMode.Off) {
            val dbfb = (if (dbfbMode == DbfbMode.High) 3.9f else 2.3f) * bassExtension
            for (i in 0..3) bass[i] += dbfb
            bass[4] += dbfb / 2f
        }
        if (analogBassEnabled) {
            for (i in 0..4) bass[i] += 5.0f * bassExtension
            bass[5] += 2.5f * bassExtension
        }
        if (mobileBassEnabled) {
            val mb = mobileBassIntensity.coerceIn(0f, 1f) * 4.8f
            for (i in 3..5) bass[i] += mb
        }
        for (i in bass.indices) lift(i..i, bass[i])
        if (harmonicExciterEnabled) {
            val ex = harmonicExciterIntensity.coerceIn(0f, 1f) * 5f
            lift(10..11, ex)
            if (!hiResEnabled) lift(12..12, ex)
        }
        if (hiResEnabled) {
            // MBC splits at 5.2 / 9.6 / 14.5 kHz (+2.5 / +4.0 / +5.5 dB);
            // bands 13 and 14 each straddle one split.
            lift(12..12, 2.5f * trebleExtension)
            lift(13..13, 3.5f * trebleExtension)
            lift(14..14, 5.0f * trebleExtension)
        }
        if (tubeWarmthEnabled) {
            val tw = 0.5f + tubeWarmthIntensity.coerceIn(0f, 1f) * 1.0f
            for (i in boost.indices) boost[i] += tw
        }
        return boost
    }

    /**
     * Input trim (0 or negative dB) that keeps the processed mix as loud as
     * the unprocessed one: the curve's gain on [LEVEL_MATCH_WEIGHTS], the
     * long-term spectrum of real music, taken back off the input.
     *
     * Why the input: the limiter cannot stop peaks (see MAX_HEADROOM_DB),
     * and DynamicsProcessing's output is converted to fixed point after the
     * mixer — on Bluetooth, 16-bit for the encoder — so every boost a loud
     * master has no room for hard-clips there, and earbuds or the phone's own
     * protection then pump the level down. Trimming before the boosts gives
     * them the room.
     *
     * Why this size and not the peak boost: trimming by the largest boost
     * (what a classic EQ preamp does) makes a +5 dB air shelf cost 5 dB of
     * everything, which is the "quieter than doing nothing" failure. The
     * shape still changes exactly as asked; only the overall level is held.
     * Cuts never earn gain back: a curve that is quieter than bypass stays
     * quieter.
     *
     * [volumeHeadroomDb] is the room the system volume already made. On a
     * wired or speaker route Android attenuates each stream digitally BEFORE
     * the output-mix effect chain, so at half volume the signal reaching
     * this DP sits 10-20 dB under full scale and the boosts fit without any
     * trim — trimming there would only make effects-on quieter than v1.6.4
     * for nothing. On Bluetooth with absolute volume the phone sends full
     * scale and the earbuds turn it down, so the headroom is 0: that route
     * trims only its vocal bands ([bluetoothTrimDb]) and pays for every other
     * boost in [planBluetoothMbc].
     */
    internal fun levelMatchTrimDb(
        preEqLeftDb: FloatArray,
        preEqRightDb: FloatArray,
        stageBoostDb: FloatArray,
        volumeHeadroomDb: Float = 0f
    ): Float {
        var power = 0.0
        for (i in 0 until EqBands.count) {
            val w = LEVEL_MATCH_WEIGHTS[i]
            val extra = stageBoostDb.getOrElse(i) { 0f }
            val l = preEqLeftDb.getOrElse(i) { 0f } + extra
            val r = preEqRightDb.getOrElse(i) { 0f } + extra
            power += w * (Math.pow(10.0, l / 10.0) + Math.pow(10.0, r / 10.0)) / 2.0
        }
        val gainDb = (10.0 * kotlin.math.log10(power)).toFloat()
        // 0.1 dB steps: a live EQ drag re-runs this every tick, and the input
        // stage has no glide of its own.
        val needed = (gainDb - volumeHeadroomDb.coerceAtLeast(0f)).coerceIn(0f, MAX_AUTO_TRIM_DB)
        return -kotlin.math.round(needed * 10f) / 10f
    }

    /**
     * Bluetooth's input trim: [levelMatchTrimDb] on the vocal bands alone.
     * Every other boost is level-aware in [planBluetoothMbc] and needs no
     * room made for it, so a smile curve costs no level at all. A boost in
     * the vocal zone takes no guard, because ducking the voice is exactly
     * the pumping that plan exists to remove; a small trim is the cheaper
     * way to pay for it, and the voice still ends up louder than it was.
     */
    internal fun bluetoothTrimDb(
        preEqLeftDb: FloatArray,
        preEqRightDb: FloatArray,
        volumeHeadroomDb: Float = 0f
    ): Float {
        fun vocalBands(curve: FloatArray) = FloatArray(EqBands.count) { i ->
            if (i in BT_TRIMMED_BANDS) curve.getOrElse(i) { 0f } else 0f
        }
        return levelMatchTrimDb(vocalBands(preEqLeftDb), vocalBands(preEqRightDb), FloatArray(EqBands.count), volumeHeadroomDb)
    }

    /**
     * The MBC on a Bluetooth route: fixed zones that every feature adds its
     * gain into, and every boost made level-aware.
     *
     * Why Bluetooth gets its own plan. With absolute volume JadOO sees full
     * scale at every volume step, while on a wired route the stream arrives
     * 10-20 dB down. Two things follow, and together they were every
     * "volume drops" report from 2026-10-25 on:
     *
     *  - The wired plan's compressors are voiced for that lower level. At
     *    full scale bass hits sit at -10 dBFS in-band, far past Analog
     *    Bass's -19 dBFS threshold: it took ~5 dB off each hit and held the
     *    bass down for its 280 ms release, so the "bass boost" measured
     *    below bypass on loud masters.
     *  - A static boost has no room, so it was paid for by trimming the
     *    whole song ([levelMatchTrimDb]). That holds peaks but costs the
     *    voice 1.5-4 dB, which is the level the ear judges. No trim at all
     *    (v1.6.4) hard-clips instead, to +5.6 dBFS and 3.9% of samples.
     *
     * What a loud master actually lacks is room on its hottest moments, not
     * everywhere. So each zone's gain is applied by a compressor that fades
     * it out on those moments: the boost is whole up to [BT_BOOST_SPAN_DB]
     * below the zone's hot level and nothing at it, which leaves a hit where
     * the track put it, the bass body lifted, the voice untouched and
     * nothing to trim. The DP compresses on the FFT block it then applies
     * the gain to, so at a 1 ms attack this works as a 46 ms look-ahead,
     * which is enough for bass, where the peaks are.
     *
     * The zone boost is what the PreEQ, MBC post-gain and PostEQ add up to
     * in that zone, so Graphic EQ, AutoEQ, Loudness, Surround and every
     * feature are covered without knowing about each other. Fixed ascending
     * zones also retire the Analog Bass + DBFB band-order accident on this
     * route (see [planMbc]): both simply add into the same three bass zones.
     *
     * [analogBass]'s Drive still means harder: it moves the bass zones' hot
     * level. Drift still means looser: it widens their knee. Warmth and
     * Pultec set the gain. [sbcCrestHz] splits a zone at SBC's subband edge
     * and everything above it stays [SbcEngine]'s fixed crest compression,
     * with no lift (see its note on makeup gain). HDR's expander does
     * nothing at any level a song reaches, so it needs no band here.
     */
    internal fun planBluetoothMbc(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean,
        analogBass: AnalogBassVoicing,
        harmonicExciterEnabled: Boolean,
        harmonicExciterIntensity: Float,
        bassExtension: Float,
        trebleExtension: Float,
        preEqLeftDb: FloatArray,
        preEqRightDb: FloatArray,
        sbcCrestHz: Float? = null,
        hdrPure: Boolean = false
    ): List<MbcBandSpec> {
        val cutoffs = (BT_ZONE_CUTOFFS_HZ.toList() + listOfNotNull(sbcCrestHz)).distinct().sorted()
        val sbc = sbcCrestHz != null
        var low = BT_ZONE_FLOOR_HZ
        return cutoffs.map { high ->
            val zone = BT_ZONE_CUTOFFS_HZ.indexOfFirst { it >= high }
            val bassZone = high <= BT_BASS_ZONE_TOP_HZ
            val crest = sbcCrestHz != null && low >= sbcCrestHz
            val feature = zoneMeanDb(low, high) { hz ->
                bluetoothFeatureGainDb(
                    hz, hiResEnabled, dbfbMode, analogBassEnabled, analogBass,
                    harmonicExciterEnabled, harmonicExciterIntensity, bassExtension, trebleExtension, sbc
                )
            }
            val post = feature + if (crest || hdrPure) 0f else BT_LEVEL_LIFT_DB
            val pre = zoneMeanDb(low, high) { hz -> (preEqGainAt(preEqLeftDb, hz) + preEqGainAt(preEqRightDb, hz)) / 2f }
            val postEq = if (analogBassEnabled) zoneMeanDb(low, high) { pultecGainDb(it, analogBass, bassExtension) } else 0f
            // The vocal zone's PreEQ is paid for by bluetoothTrimDb instead.
            val boost = if (zone == BT_VOCAL_ZONE) post else pre + post + postEq
            val drive = if (analogBassEnabled && bassZone) (BT_DRIVE_NEUTRAL - analogBass.drive) * BT_DRIVE_RANGE_DB else 0f
            val hot = BT_ZONE_HOT_DB[zone] + drive
            MbcBandSpec().apply {
                cutoffFrequency = high
                attackTime = BT_ATTACK_MS
                releaseTime = if (bassZone) BT_BASS_RELEASE_MS else BT_TOP_RELEASE_MS
                noiseGateThreshold = -90f
                expanderRatio = 1f
                preGain = 0f
                postGain = post
                when {
                    crest -> {
                        val silk = hiResEnabled && high <= HI_RES_SILK_TOP_HZ
                        attackTime = SbcEngine.CREST_ATTACK_MS
                        releaseTime = SbcEngine.CREST_RELEASE_MS
                        ratio = if (silk) 2.4f else SbcEngine.CREST_RATIO
                        threshold = if (silk) -24f else SbcEngine.CREST_THRESHOLD_DB
                        kneeWidth = SbcEngine.CREST_KNEE_DB
                    }
                    boost > BT_MIN_BOOST_DB -> {
                        val span = maxOf(BT_BOOST_SPAN_DB, boost / (1f - 1f / BT_BOOST_MAX_RATIO))
                        ratio = 1f / (1f - boost / span)
                        // The compressor measures after the PreEQ.
                        threshold = hot + pre - span
                        kneeWidth = BT_KNEE_DB + if (analogBassEnabled && bassZone) analogBass.drift * BT_DRIFT_KNEE_DB else 0f
                    }
                    else -> {
                        ratio = 1f
                        threshold = 0f
                        kneeWidth = 0f
                    }
                }
            }.also { low = high }
        }
    }

    /** Writes the bands of [plan] that moved. Live: the DP keeps its envelopes unless a cutoff changes. */
    private fun writeBluetoothPlan(dp: DynamicsProcessing, plan: List<MbcBandSpec>) {
        if (plan.size != liveBluetoothPlan.size) return
        plan.forEachIndexed { i, spec ->
            if (!spec.sameAs(liveBluetoothPlan[i])) dp.setMbcBandAllChannelsTo(i, spec.toBand())
        }
        liveBluetoothPlan = plan
    }

    /** Mean over [lowHz]..[highHz], evenly spaced in log frequency, as the EQ bands are. */
    private inline fun zoneMeanDb(lowHz: Float, highHz: Float, gainAt: (Float) -> Float): Float {
        val steps = BT_ZONE_MEAN_POINTS
        val ratio = (highHz / lowHz).toDouble()
        var sum = 0f
        for (i in 0 until steps) sum += gainAt(lowHz * Math.pow(ratio, (i + 0.5) / steps).toFloat())
        return sum / steps
    }

    private fun preEqGainAt(curve: FloatArray, hz: Float): Float {
        val band = EqBands.cutoffFrequencies.indexOfFirst { hz <= it }
        return if (band < 0) 0f else curve.getOrElse(band) { 0f }
    }

    /** Analog Bass's PostEQ at [hz]; mirrors configureAnalogBassPostEq. */
    private fun pultecGainDb(hz: Float, v: AnalogBassVoicing, bassExtension: Float): Float {
        val freq = PULTEC_FREQUENCIES_HZ[v.pultecFreqIndex.coerceIn(0, PULTEC_FREQUENCIES_HZ.size - 1)]
        return when {
            hz <= (freq * 1.5f).coerceIn(25f, 200f) -> v.pultecBoost * 8f * bassExtension
            hz <= (freq * 4f).coerceIn(80f, 500f) -> -(v.pultecCut * 5f)
            hz <= 500f -> v.warmth * 2.5f * bassExtension
            else -> 0f
        }
    }

    /** The MBC post-gain the features ask for at [hz]; mirrors each feature's bands in [planMbc]. */
    private fun bluetoothFeatureGainDb(
        hz: Float,
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean,
        analogBass: AnalogBassVoicing,
        harmonicExciterEnabled: Boolean,
        harmonicExciterIntensity: Float,
        bassExtension: Float,
        trebleExtension: Float,
        sbc: Boolean
    ): Float {
        var gain = 0f
        if (analogBassEnabled) {
            val driveGain = Math.pow(analogBass.drive.toDouble(), 0.75).toFloat() * 9f * bassExtension
            val warmthGain = analogBass.warmth * 3f * bassExtension
            gain += when {
                hz <= 60f -> warmthGain + driveGain * 0.2f
                hz <= 120f -> warmthGain * 1.5f
                hz <= 300f -> warmthGain * 0.6f
                else -> 0f
            }
        }
        if (dbfbMode != DbfbMode.Off) {
            val normal = dbfbMode == DbfbMode.Normal
            gain += when {
                hz <= 72f -> dbfbSubPostGainDb(normal, bassExtension)
                hz <= 145f -> dbfbPunchPostGainDb(normal, bassExtension)
                hz <= 260f -> if (normal) -0.4f else -0.6f
                else -> 0f
            }
        }
        if (harmonicExciterEnabled && hz > 2000f && hz <= (if (hiResEnabled) 5200f else 8000f)) {
            gain += harmonicExciterIntensity.coerceIn(0f, 1f) * 5f
        }
        if (hiResEnabled) {
            gain += when {
                hz <= 5200f -> 0f
                hz <= 9600f -> 2.5f * trebleExtension
                hz <= HI_RES_SILK_TOP_HZ -> 4.0f * trebleExtension * (if (sbc) SbcEngine.AIR_SCALE_SILK else 1f)
                else -> 5.5f * trebleExtension * (if (sbc) SbcEngine.AIR_SCALE_TOP else 1f)
            }
        }
        return gain
    }

    /**
     * Scales (STACK_SCALE_FLOOR..1) for the feature boosts that stack on
     * Bluetooth: Surround's centred smile, and DBFB, Analog Bass and Hi-Res
     * through the bass/treble extensions they already take. Each end has its
     * own, so Surround + Hi-Res running out of treble costs DBFB no bass.
     *
     * Nothing here is about level: [planBluetoothMbc] keeps a stack from
     * clipping or costing the voice anything. This is tone. Stacked at full
     * strength the bass can reach +12 dB and the top +10 dB, past where the
     * balance holds, so the stack is scaled back to [STACK_BASS_CAP_DB] and
     * [STACK_TREBLE_CAP_DB]. A single feature at its default sits under both.
     *
     * Computed from the features alone (no user EQ), so it only moves when a
     * feature changes and never on an EQ drag. The Surround L/R differential
     * is the width itself and is not scaled. The Exciter takes no extension,
     * so it neither counts nor scales.
     */
    fun bluetoothStackScale(
        surroundCenteredDb: FloatArray,
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean,
        analogBass: AnalogBassVoicing,
        bassExtension: Float,
        trebleExtension: Float
    ): StackScale {
        var bassPeak = 0f
        var treblePeak = 0f
        for (i in 0 until EqBands.count) {
            val hz = EqBands.frequencies[i]
            val body = surroundCenteredDb.getOrElse(i) { 0f } +
                bluetoothFeatureGainDb(hz, hiResEnabled, dbfbMode, analogBassEnabled, analogBass, false, 0f, bassExtension, trebleExtension, false) +
                if (analogBassEnabled) pultecGainDb(hz, analogBass, bassExtension) else 0f
            if (hz <= BT_BASS_ZONE_TOP_HZ) bassPeak = maxOf(bassPeak, body)
            else if (hz >= STACK_TREBLE_FROM_HZ) treblePeak = maxOf(treblePeak, body)
        }
        // Stepped, so a slider doesn't rewrite the PreEQ on every tick.
        fun scale(peak: Float, cap: Float) =
            if (peak <= cap) 1f
            else (kotlin.math.floor(cap / peak / STACK_SCALE_STEP) * STACK_SCALE_STEP).coerceAtLeast(STACK_SCALE_FLOOR)
        return StackScale(scale(bassPeak, STACK_BASS_CAP_DB), scale(treblePeak, STACK_TREBLE_CAP_DB))
    }

    /**
     * The engine's static response at [freqsHz], in dB, averaged over both
     * channels: input gain, PreEQ, every MBC band's post-gain, PostEQ, the
     * limiter's post-gain and Tube Warmth's enhancer gain, read back from the
     * live DynamicsProcessing and applied bin by bin as DP does (see
     * DpResponse), then smoothed in log frequency for display
     * ([DpResponse.smoothed]; the raw read-back is a staircase). The
     * quiet-signal response: compressors and expanders only move it on loud
     * or very soft material. Null when nothing is attached.
     */
    fun staticResponseDb(freqsHz: FloatArray, sampleRateHz: Int): FloatArray? = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized null
        try {
            val block = DpResponse.blockSize(sampleRateHz)
            val broadband = (preGainDb + autoTrimDb + tubeMakeupDb).coerceIn(-12f, 12f) +
                (currentLimiter?.takeIf { it.isEnabled }?.let { effectivePostGainDb() } ?: 0f) +
                (loudnessEnhancer?.takeIf { it.enabled }?.let { it.targetGain / 100f } ?: 0f)
            val sum = FloatArray(freqsHz.size)
            for (ch in 0..1) {
                val bins = FloatArray(block / 2 + 1) { broadband }
                val pre = dp.getPreEqByChannelIndex(ch)
                if (pre.isInUse && pre.isEnabled) DpResponse.addBandSteps(bins, pre.bandCount,
                    { pre.getBand(it).cutoffFrequency }, { pre.getBand(it).gain }, { pre.getBand(it).isEnabled },
                    block, sampleRateHz)
                val mbc = dp.getMbcByChannelIndex(ch)
                if (mbc.isInUse && mbc.isEnabled) DpResponse.addBandSteps(bins, mbc.bandCount,
                    { mbc.getBand(it).cutoffFrequency }, { mbc.getBand(it).postGain }, { mbc.getBand(it).isEnabled },
                    block, sampleRateHz)
                val post = dp.getPostEqByChannelIndex(ch)
                if (post.isInUse && post.isEnabled) DpResponse.addBandSteps(bins, post.bandCount,
                    { post.getBand(it).cutoffFrequency }, { post.getBand(it).gain }, { post.getBand(it).isEnabled },
                    block, sampleRateHz)
                val sampled = DpResponse.sample(bins, freqsHz, block, sampleRateHz)
                for (i in sum.indices) sum[i] += sampled[i] / 2f
            }
            DpResponse.smoothed(sum, freqsHz)
        } catch (e: Exception) {
            Log.w("DspEngine", "Response read-back failed: ${e.message}")
            null
        }
    }

    /** Releases only the DP handle (not Tube Warmth's enhancer) — see attach(). */
    private fun releaseDynamicsProcessingOnly() {
        try {
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            Log.w("DspEngine", "DP release before enhancer insert failed: ${e.message}")
        }
        dynamicsProcessing = null
    }

    fun release() = synchronized(this) {
        tubeMakeupDb = 0f
        bluetoothPlanActive = false
        liveBluetoothPlan = emptyList()
        autoTrimDb = 0f
        autoTrimReadoutDb = 0f
        sbcTrimDb = 0f
        gainBudgetDb = 0f
        allChannelGlideJobs.forEachIndexed { i, job -> job?.cancel(); allChannelGlideJobs[i] = null }
        perChannelGlideJobs.forEach { channelJobs ->
            channelJobs.forEachIndexed { i, job -> job?.cancel(); channelJobs[i] = null }
        }
        glideScope.cancel()
        glideScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            Log.e("DspEngine", "Error releasing DynamicsProcessing", e)
        } finally {
            dynamicsProcessing = null
        }
        currentLimiter = null
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            Log.e("DspEngine", "Error releasing Tube Warmth saturation", e)
        }
        loudnessEnhancer = null
        loudnessEnhancerSessionId = -1
    }

    /**
     * Sets the same gain on ALL channels (left + right) for a PreEQ band,
     * gliding from whatever the band is currently at rather than jumping
     * instantly — a 60ms glide, retriggered on every call, so a stream of
     * drag updates reads as one continuous motion instead of a string of
     * small audible steps.
     */
    fun setPreEqBandGainAllChannels(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until EqBands.count) return
        val target = gainDb.coerceIn(-15f, 15f)
        // applyAllBands rewrites all 15 bands on every feature change; most
        // haven't moved. Skipping those saves 5 binder writes per band.
        if (allChannelGlideJobs[bandIndex]?.isActive != true &&
            perChannelGlideJobs.none { it[bandIndex]?.isActive == true } &&
            kotlin.math.abs(currentPerChannelGain[0][bandIndex] - target) < 0.01f &&
            kotlin.math.abs(currentPerChannelGain[1][bandIndex] - target) < 0.01f
        ) return
        allChannelGlideJobs[bandIndex]?.cancel()
        // A per-channel glide may still be in flight on this band from a
        // previous Surround mode (writeCombinedBand switches a band between
        // the all-channel and per-channel paths whenever the mode changes).
        // Leaving it running lets two glides write the same band, and the
        // loser's frames land after the winner's, producing an audible step.
        perChannelGlideJobs.forEach { channelJobs ->
            channelJobs[bandIndex]?.cancel()
            channelJobs[bandIndex] = null
        }
        allChannelGlideJobs[bandIndex] = glideScope.launch {
            glideAllChannels(bandIndex, target)
        }
    }

    private suspend fun glideAllChannels(bandIndex: Int, target: Float) {
        val start = currentAllChannelGain[bandIndex]
        val steps = (GLIDE_DURATION_MS / GLIDE_STEP_MS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            if (!kotlin.coroutines.coroutineContext.isActive) return
            val progress = step.toFloat() / steps
            val frame = start + (target - start) * progress
            writeAllChannelGain(bandIndex, frame)
            if (step < steps) delay(GLIDE_STEP_MS)
        }
    }

    private fun writeAllChannelGain(bandIndex: Int, gainDb: Float) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            // Built, not read back: the band's cutoff never changes, and
            // getPreEqByChannelIndex fetched all 15 bands over binder on
            // every 12 ms glide frame.
            dp.setPreEqBandAllChannelsTo(bandIndex,
                DynamicsProcessing.EqBand(true, EqBands.cutoffFrequencies[bandIndex], gainDb))
            currentAllChannelGain[bandIndex] = gainDb
            // Both channels genuinely hold this value now. Keeping the
            // per-channel cache in sync means a later switch INTO a
            // per-channel Surround mode glides from the real current gain
            // instead of a stale one, which was an audible jump on the four
            // treble bands every time the mode changed.
            currentPerChannelGain[0][bandIndex] = gainDb
            currentPerChannelGain[1][bandIndex] = gainDb
        } catch (e: Exception) {
            Log.e("DspEngine", "Error setting EQ band", e)
        }
    }

    /**
     * Sets the gain for a PreEQ band on a SINGLE channel (0 = left, 1 = right),
     * gliding the same way as [setPreEqBandGainAllChannels]. Used only for the
     * small, balanced left/right treble differentials in Surround+'s
     * Traditional/Wide modes (see JadooDspService.applyAllBands) — every
     * other feature uses [setPreEqBandGainAllChannels].
     */
    fun setPreEqBandGainByChannel(channelIndex: Int, bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until EqBands.count || channelIndex !in 0..1) return
        val target = gainDb.coerceIn(-15f, 15f)
        perChannelGlideJobs[channelIndex][bandIndex]?.cancel()
        // Cancel any all-channel glide still running on this band — the
        // mirror of the guard in setPreEqBandGainAllChannels. Without it,
        // switching from a centred Surround mode into a per-channel one lets
        // the outgoing all-channel glide keep overwriting both channels while
        // the new per-channel glide is trying to separate them.
        allChannelGlideJobs[bandIndex]?.cancel()
        allChannelGlideJobs[bandIndex] = null
        perChannelGlideJobs[channelIndex][bandIndex] = glideScope.launch {
            glidePerChannel(channelIndex, bandIndex, target)
        }
    }

    private suspend fun glidePerChannel(channelIndex: Int, bandIndex: Int, target: Float) {
        val start = currentPerChannelGain[channelIndex][bandIndex]
        val steps = (GLIDE_DURATION_MS / GLIDE_STEP_MS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            if (!kotlin.coroutines.coroutineContext.isActive) return
            val progress = step.toFloat() / steps
            val frame = start + (target - start) * progress
            writePerChannelGain(channelIndex, bandIndex, frame)
            if (step < steps) delay(GLIDE_STEP_MS)
        }
    }

    private fun writePerChannelGain(channelIndex: Int, bandIndex: Int, gainDb: Float) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            dp.setPreEqBandByChannelIndex(channelIndex, bandIndex,
                DynamicsProcessing.EqBand(true, EqBands.cutoffFrequencies[bandIndex], gainDb))
            currentPerChannelGain[channelIndex][bandIndex] = gainDb
            // L and R now differ, so there is no single "all channel" value.
            // Cache their mean: it's what the band is perceptually centred on,
            // so a later switch back to a centred Surround mode glides from
            // roughly where the ear already is rather than from a stale value.
            currentAllChannelGain[bandIndex] =
                (currentPerChannelGain[0][bandIndex] + currentPerChannelGain[1][bandIndex]) / 2f
        } catch (e: Exception) {
            Log.e("DspEngine", "Error setting EQ band for channel $channelIndex", e)
        }
    }

    /**
     * What actually goes into the limiter's postGain: the user's post-gain
     * plus the SBC decoder-overshoot trim. Single source of truth so the three places
     * that write postGain — attach, updateHeadroom and setPostGain — cannot
     * disagree and silently drop or double it.
     */
    private fun effectivePostGainDb(): Float =
        (postGainDb + sbcTrimDb).coerceIn(-12f, 12f)

    fun setPreGain(gainDb: Float) = synchronized(this) {
        preGainDb = gainDb.coerceIn(-12f, 12f)
        applyInputGain()
    }

    /**
     * Writes the user's pre-gain PLUS the automatic gain-staging trim (see
     * [autoTrimDb]) to the DP's input stage. The two are summed rather than
     * fought over: the trim is the app's own correction, the pre-gain is the
     * user's, and both are simply level at the same point in the chain.
     *
     * Clamped to the same ±12 dB the input stage accepts. The clamp is on the
     * SUM, so a user running +12 dB pre-gain gets no trim headroom left — which
     * is correct, they have explicitly asked for that level.
     */
    private fun applyInputGain() {
        try {
            dynamicsProcessing?.setInputGainAllChannelsTo(
                (preGainDb + autoTrimDb + tubeMakeupDb).coerceIn(-12f, 12f)
            )
        } catch (e: Exception) {
            Log.e("DspEngine", "Error setting pre gain", e)
        }
    }

    fun setPostGain(gainDb: Float) {
        synchronized(this) {
            postGainDb = gainDb.coerceIn(-12f, 12f)
            val dp = dynamicsProcessing ?: return@synchronized
            val limiter = currentLimiter ?: return@synchronized
            try {
                // Update the cached limiter object and re-apply.
                // Avoids the fragile getLimiterByChannelIndex read-modify-write which
                // can return stale inUse/enabled state on some Android versions,
                // causing the postGain update to silently have no effect.
                limiter.postGain = effectivePostGainDb()
                dp.setLimiterAllChannelsTo(limiter)
                Log.d("DspEngine", "Post gain set to ${postGainDb}dB")
            } catch (e: Exception) {
                Log.e("DspEngine", "Error setting post gain", e)
            }
        }
    }


}
