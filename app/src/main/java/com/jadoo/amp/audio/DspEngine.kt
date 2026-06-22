package com.jadoo.amp.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

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
        analogBassPultecBoost: Float = 0.5f,
        analogBassPultecCut: Float = 0.3f,
        analogBassPultecFreqIndex: Int = 2,
        tubeWarmthEnabled: Boolean = false,
        tubeWarmthIntensity: Float = 0.5f,
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f
    ): Boolean = synchronized(this) {
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
            val preHiResSafetyBand = if (hiResEnabled && !hdrDynamicsEnabled && dbfbMode == DbfbMode.Off) 1 else 0
            val hiResBands = if (hiResEnabled) 3 else 0
            val needsFinalClosingBand = !hdrDynamicsEnabled && !hiResEnabled
            val mbcBandCount = analogBassBands + dbfbBands + mobileBassBands + hdrBands +
                preHiResSafetyBand + hiResBands + (if (needsFinalClosingBand) 1 else 0)

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
                preEq.getBand(i).cutoffFrequency = EqBands.cutoffFrequencies[i]
                preEq.getBand(i).gain = initialGains.getOrNull(i)?.coerceIn(-15f, 15f) ?: 0f
            }
            configBuilder.setPreEqAllChannelsTo(preEq)

            val mbc = DynamicsProcessing.Mbc(true, true, mbcBandCount)
            configureMbc(mbc, hiResEnabled, dbfbMode, hdrDynamicsEnabled, hdrMode, analogBassEnabled, analogBassDrive, analogBassWarmth, mobileBassEnabled, mobileBassIntensity)
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
            val headroomDb = calculateHeadroomOffset(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity, mobileBassEnabled, mobileBassIntensity, surroundMode)

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
            val limiter = when {
                hdrDynamicsEnabled && hdrMode == HdrMode.Pure -> {
                    baseLimiterThreshold = -0.1f
                    DynamicsProcessing.Limiter(
                        true, true, 0,
                        1f, 60f, 2f, baseLimiterThreshold + headroomDb, postGainDb
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
                        3f, 60f, 10f, baseLimiterThreshold + headroomDb, postGainDb
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
            Log.d("DspEngine", "Attached session=$sessionId hiRes=$hiResEnabled dbfb=$dbfbMode hdr=$hdrDynamicsEnabled surroundMode=$surroundMode analogBass=$analogBassEnabled mobileBass=$mobileBassEnabled mbcBands=$mbcBandCount")
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
                val enhancer = loudnessEnhancer ?: LoudnessEnhancer(sessionId).also { loudnessEnhancer = it }
                enhancer.setTargetGain(targetGainMb)
                enhancer.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null
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
        surroundMode: SurroundMode = SurroundMode.Off
    ) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        val limiter = currentLimiter ?: return@synchronized
        try {
            val headroomDb = calculateHeadroomOffset(hiResEnabled, dbfbMode, analogBassEnabled, tubeWarmthEnabled, tubeWarmthIntensity, mobileBassEnabled, mobileBassIntensity, surroundMode)
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
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f
    ) {
        var index = 0

        // ── Analog Bass Engine: Drive-controlled saturation simulation (20-300Hz) ──
        // Drive controls preGain (0→9dB drive), warmth controls postGain and ratio.
        // These bands produce clearly audible compression-saturation effects.
        if (analogBassEnabled) {
            val driveGain  = analogBassDrive  * 9f    // 0–9 dB input drive
            val warmthGain = analogBassWarmth * 3f    // 0–3 dB warmth output
            val compRatio  = 1.8f + analogBassDrive * 3.2f  // ratio 1.8–5.0

            // Sub-bass (20-60Hz): heavy saturation drive
            mbc.getBand(index++).apply {
                cutoffFrequency = 60f
                attackTime = 8f
                releaseTime = 200f
                ratio = compRatio
                threshold = -35f + analogBassDrive * 15f  // -35 to -20 dB
                kneeWidth = 12f
                noiseGateThreshold = -85f
                expanderRatio = 1f
                preGain  = driveGain                          // drive into compressor
                postGain = warmthGain - driveGain * 0.45f     // net: warmth minus drive bleed
            }
            // Low bass (60-120Hz): warmth body
            mbc.getBand(index++).apply {
                cutoffFrequency = 120f
                attackTime = 12f
                releaseTime = 180f
                ratio = 1.4f + analogBassWarmth * 1.2f
                threshold = -30f
                kneeWidth = 15f
                noiseGateThreshold = -88f
                expanderRatio = 1f
                preGain  = warmthGain * 0.8f
                postGain = warmthGain * 1.2f                  // warm body boost
            }
            // Upper bass (120-300Hz): mud control + Pultec dip character
            mbc.getBand(index++).apply {
                cutoffFrequency = 300f
                attackTime = 15f
                releaseTime = 160f
                ratio = 1.5f + analogBassDrive * 0.5f
                threshold = -24f
                kneeWidth = 10f
                noiseGateThreshold = -86f
                expanderRatio = 1.05f
                preGain  = -(analogBassWarmth * 1.5f)         // Pultec simultaneous dip
                postGain = 1.8f                               // body makeup gain
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
            if (!hiResEnabled) {
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

        // ── HiRes: Air-band expansion (5200Hz–20kHz) ─────────────────
        if (hiResEnabled) {
            // If neither DBFB nor HDR provided bands below, add a safety band
            if (!hdrDynamicsEnabled && dbfbMode == DbfbMode.Off) {
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
            // 5.2–9.6 kHz: presence/clarity band
            mbc.getBand(index++).apply {
                cutoffFrequency = 9600f
                attackTime = 1.5f
                releaseTime = 34f
                ratio = 1.08f
                threshold = -14f
                kneeWidth = 9f
                noiseGateThreshold = -86f
                expanderRatio = 1.18f
                preGain = 0.5f
                postGain = 2.2f
            }
            // 9.6–14.5 kHz: the "silk" band
            mbc.getBand(index++).apply {
                cutoffFrequency = 14500f
                attackTime = 0.9f
                releaseTime = 24f
                ratio = 1.04f
                threshold = -18f
                kneeWidth = 10f
                noiseGateThreshold = -88f
                expanderRatio = 1.24f
                preGain = 0.6f
                postGain = 3.5f             // reduced from 4.2 to prevent harshness with HDR
            }
            // 14.5–20 kHz: pure air band
            mbc.getBand(index).apply {
                cutoffFrequency = 20000f
                attackTime = 0.6f
                releaseTime = 18f
                ratio = 1.02f
                threshold = -22f
                kneeWidth = 12f
                noiseGateThreshold = -90f
                expanderRatio = 1.28f
                preGain = 0.8f
                postGain = 4.5f             // reduced from 5.5 to prevent intermod with HDR
            }
            return  // fully configured: [AnalogBass?] + DBFB(opt) + [MobileBass?] + HDR(opt) + HiRes(4) = done
        }

        // ── Fallback: no features enabled, single transparent passthrough ──
        if (dbfbMode == DbfbMode.Off && !hdrDynamicsEnabled) {
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
        } else if (dbfbMode != DbfbMode.Off && !hdrDynamicsEnabled) {
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

    /** Live-update the analog bass MBC bands (drive + warmth) without full rebuild. */
    fun updateAnalogBassMbc(drive: Float, warmth: Float) = synchronized(this) {
        val dp = dynamicsProcessing ?: return@synchronized
        try {
            val driveGain  = drive  * 9f
            val warmthGain = warmth * 3f
            val compRatio  = 1.8f + drive * 3.2f

            val band0 = dp.getMbcByChannelIndex(0).getBand(0).apply {
                ratio     = compRatio
                threshold = -35f + drive * 15f
                preGain   = driveGain
                postGain  = warmthGain - driveGain * 0.45f
            }
            val band1 = dp.getMbcByChannelIndex(0).getBand(1).apply {
                ratio    = 1.4f + warmth * 1.2f
                preGain  = warmthGain * 0.8f
                postGain = warmthGain * 1.2f
            }
            val band2 = dp.getMbcByChannelIndex(0).getBand(2).apply {
                ratio   = 1.5f + drive * 0.5f
                preGain = -(warmth * 1.5f)
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
     *    so they remain additive within this zone, exactly as before.
     *  - Treble (>5kHz): HiRes air-band expansion.
     *  - Broadband: Tube Warmth's LoudnessEnhancer compander raises the level
     *    of the WHOLE signal post-MBC, so it stacks on top of whichever zone
     *    is worst rather than being zone-limited itself.
     *
     * For any single feature alone, or for combos confined to one zone, this
     * produces the exact same offset as the old purely-additive model
     * (verified case-by-case). It only differs — by giving a higher ceiling —
     * when active features are confined to *different* zones, where the old
     * model over-penalised by stacking penalties that could never coincide
     * in the same band.
     */
    private fun calculateHeadroomOffset(
        hiResEnabled: Boolean,
        dbfbMode: DbfbMode,
        analogBassEnabled: Boolean = false,
        tubeWarmthEnabled: Boolean = false,
        tubeWarmthIntensity: Float = 0.5f,
        mobileBassEnabled: Boolean = false,
        mobileBassIntensity: Float = 0.5f,
        surroundMode: SurroundMode = SurroundMode.Off
    ): Float {
        var bassZone = 0f
        if (dbfbMode == DbfbMode.High) bassZone += 1.8f
        else if (dbfbMode == DbfbMode.Normal) bassZone += 1.0f
        if (analogBassEnabled) bassZone += 1.5f
        // Mobile Bass combines a small known PostEQ boost (up to +2.5dB)
        // with its 90-300Hz punch band (up to +6dB, only lightly tempered
        // by its now-gentle compression) — both known, fixed-shape gain
        // stages we configured ourselves, not a guess about opaque vendor
        // behavior, so padding scales with them.
        if (mobileBassEnabled) bassZone += mobileBassIntensity.coerceIn(0f, 1f) * 2.2f
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
        if (hiResEnabled) trebleZone += 0.8f

        var broadband = 0f
        if (tubeWarmthEnabled) broadband += (0.5f + tubeWarmthIntensity.coerceIn(0f, 1f) * 1.0f)

        return -(broadband + maxOf(bassZone, trebleZone))
    }

    fun release() = synchronized(this) {
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
    }

    /**
     * Sets the same gain on ALL channels (left + right) for a PreEQ band.
     */
    fun setPreEqBandGainAllChannels(bandIndex: Int, gainDb: Float) {
        synchronized(this) {
            if (bandIndex !in 0 until EqBands.count) return

            val dp = dynamicsProcessing ?: return
            try {
                val preEq = dp.getPreEqByChannelIndex(0)
                val band = preEq.getBand(bandIndex)
                band.gain = gainDb.coerceIn(-15f, 15f)
                dp.setPreEqBandAllChannelsTo(bandIndex, band)
            } catch (e: Exception) {
                Log.e("DspEngine", "Error setting EQ band", e)
            }
        }
    }

    /**
     * Sets the gain for a PreEQ band on a SINGLE channel (0 = left, 1 = right).
     * Used only for the small, balanced left/right treble differentials in
     * Surround+'s Traditional/Wide modes (see JadooDspService.applyAllBands) —
     * every other feature uses [setPreEqBandGainAllChannels].
     */
    fun setPreEqBandGainByChannel(channelIndex: Int, bandIndex: Int, gainDb: Float) {
        synchronized(this) {
            if (bandIndex !in 0 until EqBands.count) return

            val dp = dynamicsProcessing ?: return
            try {
                val band = dp.getPreEqBandByChannelIndex(channelIndex, bandIndex)
                band.gain = gainDb.coerceIn(-15f, 15f)
                dp.setPreEqBandByChannelIndex(channelIndex, bandIndex, band)
            } catch (e: Exception) {
                Log.e("DspEngine", "Error setting EQ band for channel $channelIndex", e)
            }
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
