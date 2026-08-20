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
     * applyInputGain(). See splitGainBudget for why this exists.
     */
    private var autoTrimDb = 0f

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

    // Crossfeed used to be the Virtualizer (Android's vendor-implemented
    // HRTF/virtual-surround effect) — dropped entirely. It's not a real
    // crossfeed circuit (BS2B/Meier-style: low-passed, delayed cross-mix)
    // and no amount of tuning made it sound like one; users correctly heard
    // it as "muddy/smeared" regardless of mode/strength. Crossfeed is now a
    // static tonal-EQ curve applied through the ordinary PreEQ path, the
    // same technique JadooDspService's SurroundMode.Front already uses —
    // see JadooDspService.crossfeedShape(). This engine has no
    // Crossfeed-specific code left at all; it's just another PreEQ
    // contributor like Loudness Contour or SBC pre-emphasis.

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

    private companion object {
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
         * The limiter does not need the warning. `DynamicsProcessing` is
         * float internally, so nothing clips before the limiter, and the
         * limiter catches overs at whatever threshold it sits at — that is
         * the entire job. The offset only decides how EARLY it starts
         * working, which is to say how much program material it damages.
         *
         * 2 dB keeps it a peak catcher: it engages in the top couple of dB
         * where a boosted transient can actually run out of room, and never
         * touches sustained material. Larger stacks get more limiting ACTION
         * on peaks, which is correct and inaudible, instead of a lower
         * ceiling, which is neither.
         */
        const val MAX_HEADROOM_DB = 2.0f

        /**
         * Automatic input trim — see splitGainBudget.
         *
         * Zero, deliberately. Trimming the input is transparent in the sense
         * that it adds no dynamics artefacts, but it still pays for a
         * narrowband boost with broadband level, which is the same error as
         * above wearing a different hat. If the user wants the whole mix
         * quieter they have a pre-gain slider and a volume rocker.
         *
         * Kept as a named constant rather than deleted because the mechanism
         * in splitGainBudget is sound and worth having if a future feature
         * ever adds genuinely BROADBAND gain, where paying broadband is the
         * right answer.
         */
        const val MAX_AUTO_TRIM_DB = 0.0f
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
        val hiRes: Int
    ) {
        val total: Int get() = analogBass + dbfb + mobileBass + hdr + harmonicExciter + preHiResSafety + hiRes
    }

    private fun mbcBandCounts(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean,
        mobileBassEnabled: Boolean,
        hdrDynamicsEnabled: Boolean,
        harmonicExciterEnabled: Boolean
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
        val preHiResSafetyBand =
            if (hiResEnabled && !hdrDynamicsEnabled && dbfbMode == DbfbMode.Off && !harmonicExciterEnabled) 1 else 0
        val hiResBands = if (hiResEnabled) 3 else 0
        return MbcBandCounts(analogBassBands, dbfbBands, mobileBassBands, hdrBands, harmonicExciterBands, preHiResSafetyBand, hiResBands)
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
        // than dumping all of it on the limiter threshold — see splitGainBudget.
        preciseGainStaging: Boolean = true,
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
        // HDR Restoration expander shape. Defaults reproduce the tuned-by-ear
        // values exactly; they are parameters rather than constants so the
        // content channel can retune them without an APK (see RemoteTuning).
        // Already clamped to sane bounds by RemoteContent.parseTuning.
        hdrRestorationThreshold: Float = -38f,
        hdrRestorationKnee: Float = 14f,
        hdrRestorationExpanderRatio: Float = 1.12f
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
            val counts = mbcBandCounts(hiResEnabled, dbfbMode, analogBassEnabled, mobileBassEnabled, hdrDynamicsEnabled, harmonicExciterEnabled)
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
            val hdrAloneClosesSpectrum = hdrDynamicsEnabled && !hiResEnabled && !harmonicExciterEnabled
            val needsFinalClosingBand = !hiResEnabled && !hdrAloneClosesSpectrum
            val mbcBandCount = counts.total + (if (needsFinalClosingBand) 1 else 0)

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
            )

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
            configureMbc(mbc, hiResEnabled, dbfbMode, hdrDynamicsEnabled, hdrMode, analogBassEnabled, analogBassDrive, analogBassWarmth, analogBassDrift = analogBassDrift, mobileBassEnabled = mobileBassEnabled, mobileBassIntensity = mobileBassIntensity, harmonicExciterEnabled = harmonicExciterEnabled, harmonicExciterIntensity = harmonicExciterIntensity, bassExtension = bassExtension, trebleExtension = trebleExtension, mobileBassExtension = mobileBassExtension, hdrRestorationThreshold = hdrRestorationThreshold, hdrRestorationKnee = hdrRestorationKnee, hdrRestorationExpanderRatio = hdrRestorationExpanderRatio)
            configBuilder.setMbcAllChannelsTo(mbc)

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
            // Tube Warmth's LoudnessEnhancer compander is voiced against an
            // untrimmed input — pulling the input stage down to pay the gain
            // budget (the precise-staging path) quietly changes what level
            // hits its compander, turning its glue character into pumping.
            // Always give it the legacy split (full budget on the limiter,
            // zero input trim) regardless of the toggle.
            val (headroomDb, trimDb) = splitGainBudget(budgetDb, preciseGainStaging && !tubeWarmthEnabled)
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
            val limiter = when {
                hdrDynamicsEnabled && hdrMode == HdrMode.Pure -> {
                    baseLimiterThreshold = -0.1f
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        20f, 40f, 2f, baseLimiterThreshold + headroomDb, postGainDb
                    )
                }
                tubeWarmthEnabled -> {
                    baseLimiterThreshold = -1.0f
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        5f, 100f, 5f, baseLimiterThreshold + headroomDb, postGainDb
                    )
                }
                else -> {
                    baseLimiterThreshold = -0.3f
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        12f, 90f, 10f, baseLimiterThreshold + headroomDb, postGainDb
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
            val oldDynamicsProcessing = dynamicsProcessing
            dynamicsProcessing = newDynamicsProcessing
            try {
                oldDynamicsProcessing?.enabled = false
                oldDynamicsProcessing?.release()
            } catch (cleanupEx: Exception) {
                Log.w("DspEngine", "Old DP cleanup skipped (likely auto-released by Android): ${cleanupEx.message}")
            }

            newDynamicsProcessing.enabled = true
            setPreGain(preGainDb)
            setPostGain(postGainDb)
            configureTubeWarmthSaturation(sessionId, tubeWarmthEnabled, tubeWarmthIntensity)
            Log.i("DspEngine", "Attached session=$sessionId hiRes=$hiResEnabled dbfb=$dbfbMode hdr=$hdrDynamicsEnabled surroundMode=$surroundMode analogBass=$analogBassEnabled mobileBass=$mobileBassEnabled harmonicExciter=$harmonicExciterEnabled mbcBands=$mbcBandCount bassExtension=$bassExtension trebleExtension=$trebleExtension mobileBassExtension=$mobileBassExtension")
            true
        } catch (e: Exception) {
            Log.e("DspEngine", "Failed to attach DynamicsProcessing — old DP preserved if present", e)
            newDynamicsProcessing?.release()
            currentLimiter = null
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
     * Live-update DBFB's two bass MBC bands' postGain (sub/punch) without a
     * full topology rebuild. Only runs when DBFB is active; its bands
     * always sit immediately after Analog Bass's (see configureMbc).
     */
    fun updateDbfbGain(dbfbMode: DbfbMode, analogBassEnabled: Boolean, bassExtension: Float = 1f) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        if (dbfbMode == DbfbMode.Off) return@synchronized
        try {
            val normal = dbfbMode == DbfbMode.Normal
            val subPostGain = (if (normal) 1.5f else 2.5f) * bassExtension
            val punchPostGain = (if (normal) 0.8f else 1.4f) * bassExtension
            // DBFB's bands always sit right after Analog Bass's (see configureMbc).
            val baseIndex = mbcBandCounts(hiResEnabled = false, dbfbMode, analogBassEnabled, mobileBassEnabled = false, hdrDynamicsEnabled = false, harmonicExciterEnabled = false).analogBass
            val subBand = dp.getMbcByChannelIndex(0).getBand(baseIndex).apply { postGain = subPostGain }
            dp.setMbcBandAllChannelsTo(baseIndex, subBand)
            val punchBand = dp.getMbcByChannelIndex(0).getBand(baseIndex + 1).apply { postGain = punchPostGain }
            dp.setMbcBandAllChannelsTo(baseIndex + 1, punchBand)
            // The 260Hz mud-control band. It was omitted here, so after a
            // device-quality-tier drag the first two DBFB bands were scaled by
            // bassExtension while this one kept its attach-time value — the
            // three bands drifted out of their intended ratio. Matches the
            // postGain configureMbc() writes for this band.
            val mudBand = dp.getMbcByChannelIndex(0).getBand(baseIndex + 2).apply {
                postGain = if (normal) -0.4f else -0.6f
            }
            dp.setMbcBandAllChannelsTo(baseIndex + 2, mudBand)
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating DBFB gain", e)
        }
    }

    /**
     * Live-update HiRes's three treble MBC bands' postGain without a full
     * topology rebuild. Replicates the same additive band-index counting
     * used everywhere else in this file to locate HiRes's bands (they're
     * always the last MBC bands, after an optional safety band — see
     * configureMbc).
     */
    fun updateHiResGain(
        trebleExtension: Float,
        analogBassEnabled: Boolean,
        dbfbMode: DbfbMode,
        mobileBassEnabled: Boolean,
        hdrDynamicsEnabled: Boolean,
        harmonicExciterEnabled: Boolean
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            // HiRes is enabled here by construction (only runs while it's
            // on) — its own 3 bands come right after everything else.
            val counts = mbcBandCounts(hiResEnabled = true, dbfbMode, analogBassEnabled, mobileBassEnabled, hdrDynamicsEnabled, harmonicExciterEnabled)
            val baseIndex = counts.analogBass + counts.dbfb + counts.mobileBass + counts.hdr +
                counts.harmonicExciter + counts.preHiResSafety
            val gains = floatArrayOf(2.5f, 4.0f, 5.5f)
            for (i in gains.indices) {
                val band = dp.getMbcByChannelIndex(0).getBand(baseIndex + i).apply {
                    postGain = gains[i] * trebleExtension
                }
                dp.setMbcBandAllChannelsTo(baseIndex + i, band)
            }
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating HiRes gain", e)
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
        preciseGainStaging: Boolean = true
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        val limiter = currentLimiter ?: return@synchronized
        try {
            val budgetDb = calculateGainBudget(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity, mobileBassEnabled, mobileBassIntensity, surroundMode, harmonicExciterEnabled, harmonicExciterIntensity, bassExtension, trebleExtension, mobileBassExtension, preEqBassPeakDb, preEqTreblePeakDb)
            // See the matching comment in attach() — Tube Warmth always gets
            // the legacy split so its compander's input level never drifts.
            val (headroomDb, trimDb) = splitGainBudget(budgetDb, preciseGainStaging && !tubeWarmthEnabled)
            gainBudgetDb = budgetDb
            limiter.threshold = baseLimiterThreshold + headroomDb
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

    private fun configureMbc(
        mbc: DynamicsProcessing.Mbc,
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
        hdrRestorationExpanderRatio: Float = 1.12f
    ) {
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
        // The likely reason: with the array out of order, some of the two
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
        var index = 0

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
            mbc.getBand(index++).apply {
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
            mbc.getBand(index++).apply {
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
            mbc.getBand(index++).apply {
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
            val subPostGain = (if (normal) 1.5f else 2.5f) * bassExtension
            val punchPostGain = (if (normal) 0.8f else 1.4f) * bassExtension

            // Attack/release slowed and ratios reduced from the original
            // 6-10ms / 90-140ms / 1.6-2.2:1 settings, which reacted to
            // individual bass notes fast enough to cause audible per-note
            // "pumping"/breathing. Slower envelopes make the bands act as a
            // gentle overall leveler instead, while postGain (the actual
            // amount of boost) is unchanged.
            mbc.getBand(index++).apply {
                cutoffFrequency = 72f
                attackTime = 22f
                releaseTime = 280f
                ratio = 1.3f
                threshold = -20f
                kneeWidth = 8f
                noiseGateThreshold = -80f
                expanderRatio = 1.1f
                preGain = 0f
                postGain = subPostGain
            }
            mbc.getBand(index++).apply {
                cutoffFrequency = 145f
                attackTime = 22f
                releaseTime = 280f
                ratio = 1.5f
                threshold = -18f
                kneeWidth = 6f
                noiseGateThreshold = -82f
                expanderRatio = 1.05f
                preGain = 0f
                postGain = punchPostGain
            }
            mbc.getBand(index++).apply {
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
                mbc.getBand(index++).apply {
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
            mbc.getBand(index++).apply {
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
                mbc.getBand(index).apply {
                    cutoffFrequency = 20000f
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
                return  // fully configured: [AnalogBass?] + [DBFB?] + [MobileBass?] + HDR(1) = done
            }
            if (harmonicExciterEnabled) {
                // Harmonic Exciter is on: HDR's band must stop at the
                // exciter's lower edge (2000Hz, the exciter's own guard
                // cutoff) regardless of HiRes, so the array stays strictly
                // ascending and HDR's own band doubles as the exciter's
                // 0-2000Hz guard (no separate guard band needed — see the
                // exciter block below).
                mbc.getBand(index++).apply {
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
                mbc.getBand(index++).apply {
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
                mbc.getBand(index++).apply {
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
            mbc.getBand(index++).apply {
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
            // If neither DBFB nor HDR nor Harmonic Exciter already covers 0-5200Hz, add a safety band.
            // Harmonic Exciter's lift band ends at 5200Hz when HiRes is on — skip the safety band
            // to avoid two consecutive bands with the same cutoff, which corrupts the MBC array.
            if (!hdrDynamicsEnabled && dbfbMode == DbfbMode.Off && !harmonicExciterEnabled) {
                mbc.getBand(index++).apply {
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
            mbc.getBand(index++).apply {
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
            mbc.getBand(index++).apply {
                cutoffFrequency = 14500f
                attackTime = 2f
                releaseTime = 50f
                ratio = 1.0f
                threshold = 0f
                kneeWidth = 0f
                noiseGateThreshold = -90f
                expanderRatio = 1.0f
                preGain = 0f
                postGain = 4.0f * trebleExtension
            }
            // 14.5–20 kHz: pure air — pure linear boost
            mbc.getBand(index).apply {
                cutoffFrequency = 20000f
                attackTime = 2f
                releaseTime = 50f
                ratio = 1.0f
                threshold = 0f
                kneeWidth = 0f
                noiseGateThreshold = -90f
                expanderRatio = 1.0f
                preGain = 0f
                postGain = 5.5f * trebleExtension
            }
            return  // fully configured: [AnalogBass?] + DBFB(opt) + [MobileBass?] + HDR(opt) + HiRes(4) = done
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
        if (dbfbMode == DbfbMode.Off) {
            mbc.getBand(index).apply {
                cutoffFrequency = 20000f
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
        } else {
            mbc.getBand(index).apply {
                cutoffFrequency = 20000f
                attackTime = 6f
                releaseTime = 80f
                ratio = 1f
                threshold = 0f
                kneeWidth = 0f
                noiseGateThreshold = -90f
                expanderRatio = 1f
                preGain = 0f
                postGain = 0f
            }
        }
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
        val pultecFreqs = AnalogBassEngine.PULTEC_FREQUENCIES
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
            val pultecFreqs = AnalogBassEngine.PULTEC_FREQUENCIES
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
            Log.d("DspEngine", "Analog bass PostEQ updated: boost=${pultecBoost * 8f}dB cut=${-(pultecCut * 5f)}dB warmth=${warmth * 2.5f}dB")
        } catch (e: Exception) {
            Log.e("DspEngine", "Error updating analog bass PostEQ", e)
        }
    }

    /** Live-update the analog bass MBC bands (drive + warmth + drift) without full rebuild. */
    fun updateAnalogBassMbc(drive: Float, warmth: Float, drift: Float, bassExtension: Float = 1f) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
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
        // purpose: getting their real per-band peak needs the biquad
        // evaluator (DigitalFilterEngine.evaluateBandPeakDb), and this
        // function's result is recomputed on every tick of a live drag (the
        // Device Quality Tier slider — see setDeviceQualityTier). Given
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
     * free, and take the entire remainder out of the input, where it is
     * transparent. Neither stage is asked to do the thing it is bad at.
     *
     * Returns limiter offset (negative dB, added to the base threshold) and
     * input trim (negative dB, summed into pre-gain).
     */
    fun splitGainBudget(budgetDb: Float, preciseGainStaging: Boolean): Pair<Float, Float> {
        if (!preciseGainStaging) {
            // Legacy: the entire budget goes to the threshold, uncapped, and
            // the input is never touched. Reproduces the pre-1.2 numbers
            // exactly — including the pathological ones.
            return -budgetDb to 0f
        }
        val onLimiter = budgetDb.coerceIn(0f, MAX_HEADROOM_DB)
        val excess = (budgetDb - onLimiter).coerceIn(0f, MAX_AUTO_TRIM_DB)
        return -onLimiter to -excess
    }

    fun release() = synchronized(this) {
        autoTrimDb = 0f
        autoTrimReadoutDb = 0f
        gainBudgetDb = 0f
        allChannelGlideJobs.forEachIndexed { i, job -> job?.cancel(); allChannelGlideJobs[i] = null }
        perChannelGlideJobs.forEach { channelJobs ->
            channelJobs.forEachIndexed { i, job -> job?.cancel(); channelJobs[i] = null }
        }
        glideScope.cancel()
        glideScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        dynamicsProcessing?.enabled = false
        dynamicsProcessing?.release()
        dynamicsProcessing = null
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
            val preEq = dp.getPreEqByChannelIndex(0)
            val band = preEq.getBand(bandIndex)
            band.gain = gainDb
            dp.setPreEqBandAllChannelsTo(bandIndex, band)
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
            val band = dp.getPreEqBandByChannelIndex(channelIndex, bandIndex)
            band.gain = gainDb
            dp.setPreEqBandByChannelIndex(channelIndex, bandIndex, band)
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
                (preGainDb + autoTrimDb).coerceIn(-12f, 12f)
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
                limiter.postGain = postGainDb
                dp.setLimiterAllChannelsTo(limiter)
                Log.d("DspEngine", "Post gain set to ${postGainDb}dB")
            } catch (e: Exception) {
                Log.e("DspEngine", "Error setting post gain", e)
            }
        }
    }


}
