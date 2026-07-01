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

    // Tube Warmth: a soft-knee compander stage providing the subtle 2nd-order-ish
    // saturation character that DynamicsProcessing's bands cannot produce on their own.
    private var loudnessEnhancer: LoudnessEnhancer? = null
    // Track which session the LoudnessEnhancer is bound to so we can detect
    // stale bindings and re-create it when the session changes.
    private var loudnessEnhancerSessionId: Int = -1

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
    private val currentAllChannelGain = FloatArray(EqBands.count) { 0f }
    private val currentPerChannelGain = Array(2) { FloatArray(EqBands.count) { 0f } }

    private companion object {
        const val GLIDE_DURATION_MS = 60L
        const val GLIDE_STEP_MS = 12L
    }

    fun attach(
        sessionId: Int,
        initialGains: FloatArray = FloatArray(EqBands.count) { 0f },
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
        harmonicExciterIntensity: Float = 0.5f
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
            val analogBassBands = if (analogBassEnabled) 3 else 0
            val dbfbBands = if (dbfbMode != DbfbMode.Off) 3 else 0
            // 2 bands (transparent sub-bass guard + the leveler) when Mobile
            // Bass alone owns the low end; just 1 (the leveler) when Analog
            // Bass/DBFB already claim 0-90Hz with their own bands — see the
            // matching guard-band logic in configureMbc.
            val mobileBassBands = if (mobileBassEnabled) {
                if (!analogBassEnabled && dbfbMode == DbfbMode.Off) 2 else 1
            } else 0
            val hdrBands = if (hdrDynamicsEnabled) 1 else 0
            // Harmonic Exciter: a transparent 0-2000Hz guard band plus the
            // actual 2-8kHz presence lift band. Sits between HDR and HiRes
            // in band order (see configureMbc). When HiRes is on, the lift
            // band hands off cleanly at 5200Hz (HiRes's own crossover);
            // when HiRes is off, it covers up to 8000Hz, which means it
            // does NOT reach 20kHz on its own, so the final closing band is
            // still needed in that case.
            val harmonicExciterBands = if (harmonicExciterEnabled) 2 else 0
            // Safety band covers 0-5200Hz when HiRes is on but nothing else already
            // closes that gap. Harmonic Exciter's lift band already ends at 5200Hz
            // when HiRes is also on (see configureMbc), so the safety band must be
            // skipped when the exciter is active — two bands with the same cutoff
            // frequency would corrupt the MBC array.
            val preHiResSafetyBand = if (hiResEnabled && !hdrDynamicsEnabled && dbfbMode == DbfbMode.Off && !harmonicExciterEnabled) 1 else 0
            val hiResBands = if (hiResEnabled) 3 else 0
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
            val mbcBandCount = analogBassBands + dbfbBands + mobileBassBands + hdrBands +
                harmonicExciterBands + preHiResSafetyBand + hiResBands + (if (needsFinalClosingBand) 1 else 0)

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
            configureMbc(mbc, hiResEnabled, dbfbMode, hdrDynamicsEnabled, hdrMode, analogBassEnabled, analogBassDrive, analogBassWarmth, analogBassDrift = analogBassDrift, mobileBassEnabled = mobileBassEnabled, mobileBassIntensity = mobileBassIntensity, harmonicExciterEnabled = harmonicExciterEnabled, harmonicExciterIntensity = harmonicExciterIntensity)
            configBuilder.setMbcAllChannelsTo(mbc)

            // ── PostEQ: Pultec-style Analog Bass curve, or Mobile Bass's
            // overtone-emphasis shelf if enabled (takes priority — see above) ──
            if (postEqActive) {
                val postEq = DynamicsProcessing.Eq(true, true, postEqBandCount)
                if (mobileBassEnabled) {
                    configureMobileBassPostEq(postEq, mobileBassIntensity)
                } else {
                    configureAnalogBassPostEq(postEq, analogBassPultecFreqIndex, analogBassPultecBoost, analogBassPultecCut, analogBassWarmth)
                }
                configBuilder.setPostEqAllChannelsTo(postEq)
            }

            // ── Gain staging: calculate headroom offset ────────────────
            // When multiple features boost signal (HiRes, DBFB, HDR), the limiter
            // threshold must drop to prevent inter-modulation distortion.
            val headroomDb = calculateHeadroomOffset(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity, mobileBassEnabled, mobileBassIntensity, surroundMode, harmonicExciterEnabled, harmonicExciterIntensity)

            // Real safety limiter for all modes: a 10:1 ratio engaging only within
            // 0.3 dB of full scale. At normal program levels this never engages —
            // it exists purely to catch peaks introduced by HiRes/DBFB/AnalogBass
            // gain stages (see calculateHeadroomOffset) before they clip.
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
            Log.d("DspEngine", "Attached session=$sessionId hiRes=$hiResEnabled dbfb=$dbfbMode hdr=$hdrDynamicsEnabled surroundMode=$surroundMode analogBass=$analogBassEnabled mobileBass=$mobileBassEnabled harmonicExciter=$harmonicExciterEnabled mbcBands=$mbcBandCount")
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
                gain = clamped * 2.5f
            }
            val band2 = dp.getPostEqByChannelIndex(0).getBand(2).apply {
                cutoffFrequency = 800f
                gain = 0f
            }
            dp.setPostEqBandAllChannelsTo(0, band0)
            dp.setPostEqBandAllChannelsTo(1, band1)
            dp.setPostEqBandAllChannelsTo(2, band2)

            val guardBand = if (!analogBassEnabled && dbfbMode == DbfbMode.Off) 1 else 0
            val mbcIndex = (if (analogBassEnabled) 3 else 0) + (if (dbfbMode != DbfbMode.Off) 3 else 0) + guardBand
            val mbcBand = dp.getMbcByChannelIndex(0).getBand(mbcIndex).apply {
                ratio = 1.1f + clamped * 0.2f
                postGain = clamped * 6f
            }
            dp.setMbcBandAllChannelsTo(mbcIndex, mbcBand)
            Log.d("DspEngine", "Mobile Bass updated: leveler=${clamped * 5f}dB")
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
        hdrDynamicsEnabled: Boolean
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val clamped = intensity.coerceIn(0f, 1f)
            val analogBassBands = if (analogBassEnabled) 3 else 0
            val dbfbBands = if (dbfbMode != DbfbMode.Off) 3 else 0
            val mobileBassBands = if (mobileBassEnabled) {
                if (!analogBassEnabled && dbfbMode == DbfbMode.Off) 2 else 1
            } else 0
            val hdrBands = if (hdrDynamicsEnabled) 1 else 0
            val guardBand = 1  // the exciter's own transparent 0-2000Hz band
            val mbcIndex = analogBassBands + dbfbBands + mobileBassBands + hdrBands + guardBand
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
        harmonicExciterIntensity: Float = 0.5f
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        val limiter = currentLimiter ?: return@synchronized
        try {
            val headroomDb = calculateHeadroomOffset(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity, mobileBassEnabled, mobileBassIntensity, surroundMode, harmonicExciterEnabled, harmonicExciterIntensity)
            limiter.threshold = baseLimiterThreshold + headroomDb
            dp.setLimiterAllChannelsTo(limiter)
            Log.d("DspEngine", "Headroom updated: threshold=${limiter.threshold}dB")
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
        harmonicExciterIntensity: Float = 0.5f
    ) {
        var index = 0

        // ── Analog Bass Engine: Drive-controlled saturation simulation (20-300Hz) ──
        // Drive controls preGain (0→9dB drive), warmth controls postGain and ratio.
        // These bands produce clearly audible compression-saturation effects.
        if (analogBassEnabled) {
            val driveGain  = analogBassDrive  * 9f    // 0–9 dB input drive
            val warmthGain = analogBassWarmth * 3f    // 0–3 dB warmth output
            val compRatio  = 1.8f + analogBassDrive * 3.2f  // ratio 1.8–5.0
            // Drift: widens the compression knee from tight/digital (8dB) to
            // loose/vintage (28dB). A wider knee means the compressor eases in
            // gradually over a broader range below threshold rather than snapping
            // in at a fixed point — this softer onset is the audible "looseness"
            // associated with analog hardware. At drift=0 the knee is narrow and
            // the onset is precise (modern/digital feel); at drift=1 it's wide and
            // forgiving (worn/vintage feel).
            val driftKnee  = 8f + analogBassDrift * 20f  // 8–28 dB knee

            // Sub-bass (20-60Hz): saturation drive — threshold raised to sit above
            // typical program material so the compressor only engages on real peaks,
            // not on the entire body of every bass note.
            mbc.getBand(index++).apply {
                cutoffFrequency = 60f
                attackTime = 10f
                releaseTime = 220f
                ratio = compRatio
                threshold = -18f + analogBassDrive * 8f  // -18 to -10 dBFS: only clips peaks
                kneeWidth = driftKnee
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain  = driveGain * 0.5f              // halved drive — was slamming too hard
                postGain = warmthGain + driveGain * 0.2f // always additive
            }
            // Low bass (60-120Hz): warmth body — no negative postGain path
            mbc.getBand(index++).apply {
                cutoffFrequency = 120f
                attackTime = 14f
                releaseTime = 200f
                ratio = 1.2f + analogBassWarmth * 0.8f  // gentler: 1.2–2.0
                threshold = -20f
                kneeWidth = driftKnee
                noiseGateThreshold = -88f
                expanderRatio = 1f
                preGain  = warmthGain * 0.5f
                postGain = warmthGain * 1.5f             // warm body boost
            }
            // Upper bass (120-300Hz): mud control — preGain removed (was causing net cut)
            mbc.getBand(index++).apply {
                cutoffFrequency = 300f
                attackTime = 18f
                releaseTime = 180f
                ratio = 1.3f + analogBassDrive * 0.3f
                threshold = -18f
                kneeWidth = driftKnee
                noiseGateThreshold = -86f
                expanderRatio = 1f
                preGain  = 0f
                postGain = warmthGain * 0.6f             // gentle warmth, never a cut
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
            val subPostGain = if (normal) 1.5f else 2.5f
            val punchPostGain = if (normal) 0.8f else 1.4f

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
                postGain = clamped * 6f             // 0-6 dB — the full budget lives in this one safe band now
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
            if (!hiResEnabled && !harmonicExciterEnabled) {
                mbc.getBand(index).apply {
                    cutoffFrequency = 20000f
                    attackTime = if (isRestoration) 30f else 15f
                    releaseTime = if (isRestoration) 250f else 180f
                    ratio = 1.0f
                    threshold = if (isRestoration) -32f else 0f
                    kneeWidth = if (isRestoration) 6f else 0f
                    noiseGateThreshold = -90f
                    expanderRatio = if (isRestoration) 1.15f else 1.0f
                    preGain = 0f
                    postGain = 0f
                }
                return  // fully configured: [AnalogBass?] + [DBFB?] + [MobileBass?] + HDR(1) = done
            }
            if (!hiResEnabled) {
                // Harmonic Exciter is on: HDR's band must stop at the
                // exciter's lower edge instead of closing the whole array.
                mbc.getBand(index++).apply {
                    cutoffFrequency = 8000f
                    attackTime = if (isRestoration) 30f else 15f
                    releaseTime = if (isRestoration) 250f else 180f
                    ratio = 1.0f
                    threshold = if (isRestoration) -32f else 0f
                    kneeWidth = if (isRestoration) 6f else 0f
                    noiseGateThreshold = -90f
                    expanderRatio = if (isRestoration) 1.15f else 1.0f
                    preGain = 0f
                    postGain = 0f
                }
            } else {
                // With HiRes: same band, but only covers up to the HiRes crossover at 5.2 kHz
                mbc.getBand(index++).apply {
                    cutoffFrequency = 5200f
                    attackTime = if (isRestoration) 30f else 15f
                    releaseTime = if (isRestoration) 250f else 180f
                    ratio = 1.0f
                    threshold = if (isRestoration) -32f else 0f
                    kneeWidth = if (isRestoration) 6f else 0f
                    noiseGateThreshold = -90f
                    expanderRatio = if (isRestoration) 1.15f else 1.0f
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
            mbc.getBand(index++).apply {
                cutoffFrequency = if (hiResEnabled) 5200f else 8000f
                attackTime = 6f
                releaseTime = 80f
                ratio = 1.3f + clamped * 0.4f   // 1.3-1.7 — reins in loud peaks only
                threshold = -12f                 // engages only on genuinely loud presence content
                kneeWidth = 8f
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain = 0f
                postGain = clamped * 5f     // 0-5dB direct, audible presence lift
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
                postGain = 2.5f
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
                postGain = 4.0f
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
                postGain = 5.5f
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
        warmth: Float
    ) {
        val pultecFreqs = AnalogBassEngine.PULTEC_FREQUENCIES
        val pultecFreq = pultecFreqs[pultecFreqIndex.coerceIn(0, pultecFreqs.size - 1)]
        // Band 0: below/at pultec freq — the Pultec BOOST
        postEq.getBand(0).apply {
            cutoffFrequency = (pultecFreq * 1.5f).coerceIn(25f, 200f)
            gain = pultecBoost * 8f        // 0–8 dB boost
        }
        // Band 1: just above pultec freq — the Pultec simultaneous CUT (creates the resonant dip)
        postEq.getBand(1).apply {
            cutoffFrequency = (pultecFreq * 4f).coerceIn(80f, 500f)
            gain = -(pultecCut * 5f)       // 0–5 dB dip
        }
        // Band 2: warmth zone (mid-bass body)
        postEq.getBand(2).apply {
            cutoffFrequency = 500f
            gain = warmth * 2.5f           // 0–2.5 dB warmth
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
        postEq.getBand(1).apply { cutoffFrequency = 300f; gain = clamped * 2.5f }
        postEq.getBand(2).apply { cutoffFrequency = 800f; gain = 0f }
        postEq.getBand(3).apply { cutoffFrequency = 20000f; gain = 0f }
    }

    /** Live-update the analog bass PostEQ bands without a full topology rebuild. */
    fun updateAnalogBassPostEq(
        pultecBoost: Float,
        pultecCut: Float,
        pultecFreqIndex: Int,
        warmth: Float
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val pultecFreqs = AnalogBassEngine.PULTEC_FREQUENCIES
            val pultecFreq = pultecFreqs[pultecFreqIndex.coerceIn(0, pultecFreqs.size - 1)]
            val band0 = dp.getPostEqByChannelIndex(0).getBand(0).apply {
                cutoffFrequency = (pultecFreq * 1.5f).coerceIn(25f, 200f)
                gain = pultecBoost * 8f
            }
            val band1 = dp.getPostEqByChannelIndex(0).getBand(1).apply {
                cutoffFrequency = (pultecFreq * 4f).coerceIn(80f, 500f)
                gain = -(pultecCut * 5f)
            }
            val band2 = dp.getPostEqByChannelIndex(0).getBand(2).apply {
                cutoffFrequency = 500f
                gain = warmth * 2.5f
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
    fun updateAnalogBassMbc(drive: Float, warmth: Float, drift: Float) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val driveGain  = drive  * 9f
            val warmthGain = warmth * 3f
            val compRatio  = 1.8f + drive * 3.2f
            val driftKnee  = 8f + drift * 20f

            val band0 = dp.getMbcByChannelIndex(0).getBand(0).apply {
                ratio     = compRatio
                threshold = -18f + drive * 8f
                kneeWidth = driftKnee
                preGain   = driveGain * 0.5f
                postGain  = warmthGain + driveGain * 0.2f
            }
            val band1 = dp.getMbcByChannelIndex(0).getBand(1).apply {
                ratio     = 1.2f + warmth * 0.8f
                kneeWidth = driftKnee
                preGain   = warmthGain * 0.5f
                postGain  = warmthGain * 1.5f
            }
            val band2 = dp.getMbcByChannelIndex(0).getBand(2).apply {
                ratio     = 1.3f + drive * 0.3f
                kneeWidth = driftKnee
                preGain   = 0f
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
    private fun calculateHeadroomOffset(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean = false,
        tubeWarmthEnabled: Boolean = false,
        tubeWarmthIntensity: Float = 0.5f,
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f,
        surroundMode: SurroundMode = SurroundMode.Off,
        harmonicExciterEnabled: Boolean = false,
        harmonicExciterIntensity: Float = 0.5f
    ): Float {
        var bassZone = 0f
        // DBFB's 72Hz/145Hz bands (postGain up to 2.5dB + 1.4dB) can both be
        // driven by a single bass note that spans their adjacent cutoffs —
        // worst case ~3.9dB on High, ~2.3dB on Normal — vs. the previous flat
        // 1.8/1.0dB credit, which under-padded by ~2dB and let the same
        // limiter-slam issue fixed for Mobile Bass happen (more mildly) here too.
        if (dbfbMode == DbfbMode.High) bassZone += 3.9f
        else if (dbfbMode == DbfbMode.Normal) bassZone += 2.3f
        // Analog Bass's 60-120Hz band alone can reach ~3.6dB postGain at max
        // warmth — the previous flat 1.5dB credit under-padded that peak by
        // ~2dB for the same reason as DBFB above.
        if (analogBassEnabled) bassZone += 5.0f
        // Mobile Bass combines a small known PostEQ boost (up to +2.5dB)
        // with its 90-300Hz punch band's postGain (up to +6dB) — both known,
        // fixed-shape gain stages we configured ourselves. The two stack
        // directly on bass transients (same 90-300Hz region, PostEQ sits
        // downstream of the MBC band), so the real worst case is close to
        // their sum (~8.5dB), not a fraction of it. Underestimating this
        // (the previous *2.2f scale only padded ~2.2dB against an 8.5dB
        // peak) left the global limiter's ceiling far too close to what
        // Mobile Bass actually produces on a bass hit, so the limiter's
        // fast attack/high ratio slammed the whole signal on every
        // transient — audible as "limiter attacking when the bass drops."
        if (mobileBassEnabled) bassZone += mobileBassIntensity.coerceIn(0f, 1f) * 7.5f
        // Surround mode's own bass "smile" (see surroundBandProfile in
        // JadooDspService) was never accounted for here at all — its 63Hz/
        // 100Hz boost stacks with every other bass feature's gain, and on
        // Wide it's large enough to push the limiter into audible
        // distortion once something else (e.g. Mobile Bass) is also
        // boosting the same region. Padding by the smile's peak in that
        // overlap (63Hz+100Hz) closes that gap.
        bassZone += when (surroundMode) {
            SurroundMode.Off, SurroundMode.Front -> 0f
            SurroundMode.Traditional -> 3.0f  // 63Hz(+2.0) + 100Hz(+1.0)
            SurroundMode.Wide -> 4.5f          // 63Hz(+3.0) + 100Hz(+1.5)
        }

        var trebleZone = 0f
        // HiRes's "air band" (14.5-20kHz) alone reaches +4.5dB postGain with
        // a fast 0.6ms attack — the previous flat 0.8dB credit under-padded
        // that peak by ~3.7dB, the same under-padding pattern that caused
        // Mobile Bass's limiter-slam bug, just milder here since the air
        // band's energy is narrower-band and less consistently present in
        // program material than a 90-300Hz bass thump is.
        if (hiResEnabled) trebleZone += 5.5f
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
            SurroundMode.Off, SurroundMode.Front -> 0f
            SurroundMode.Traditional -> 4.0f  // 16kHz peak
            SurroundMode.Wide -> 6.0f          // 16kHz peak
        }
        // Harmonic Exciter's presence-lift band applies up to +5dB of direct
        // postGain (see configureMbc). Sits in the treble zone (2-8kHz) but
        // doesn't overlap much with HiRes (9.6kHz+), so they don't literally
        // stack in the same narrow band — however both contribute to the
        // broadband limiter's worst-case peak, so both are counted here.
        if (harmonicExciterEnabled) trebleZone += harmonicExciterIntensity.coerceIn(0f, 1f) * 5f

        var broadband = 0f
        if (tubeWarmthEnabled) broadband += (0.5f + tubeWarmthIntensity.coerceIn(0f, 1f) * 1.0f)

        return -(broadband + maxOf(bassZone, trebleZone))
    }

    fun release() = synchronized(this) {
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
        } catch (e: Exception) {
            Log.e("DspEngine", "Error setting EQ band for channel $channelIndex", e)
        }
    }

    fun setPreGain(gainDb: Float) = synchronized(this) {
        preGainDb = gainDb.coerceIn(-12f, 12f)
        try {
            dynamicsProcessing?.setInputGainAllChannelsTo(preGainDb)
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
