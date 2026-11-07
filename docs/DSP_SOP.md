# JadOO DSP — Engine rules and feature SOPs

The reference for changing anything that touches sound. `CLAUDE.md` holds the
short version and points here. Every rule below exists because breaking it
already shipped a bug once; the "Found" line says when and how.

---

## 1. How the engine really works

JadOO is a non-root app. Android lets it configure Google's built-in effects
on the output mix (session 0) and nothing else. It cannot run its own code on
other apps' audio. Everything is built from two effects:

| Effect | What it is (AOSP source) |
|---|---|
| **DynamicsProcessing (DP)** | FFT engine (`media/libeffects/dynamicsproc/dsp/DPFrequency.cpp`). Per channel: input gain → PreEQ → MBC → PostEQ → limiter → output. |
| **LoudnessEnhancer (LE)** | Gain + per-sample peak compressor, knee -8 dBFS, 7:1, 1 ms / 15 ms, hard clip at full scale (`media/libeffects/loudness/dsp/core/dynamic_range_compression.cpp`). Used only by Tube Warmth. |

### 1.1 Frequency resolution — bins, not filters

- DP multiplies whole FFT bins. Frame = `DpResponse.FRAME_DURATION_MS` (42 ms) → 2048-sample block at 44.1 and 48 kHz → ~21 Hz per bin. Costs ~43 ms latency.
- A band covers bins from the previous band's stop + 1 up to `round(cutoff × block / sampleRate)`. Its gain is a **step** over those bins.
- If a cutoff rounds to a bin an earlier band already took, the band gets **no bins and does nothing**.
- AOSP sets the next start to `stop + 1` **even when that moves backwards**, so a cutoff lower than the previous one makes the next band **re-process** bins (compressed and boosted twice).
- **Found 2026-10-26:** Android's 10 ms default (512 block, 94 Hz bins) left the 25 Hz band on the DC bin and gave the 63 Hz and 100 Hz bands zero bins since the app existed.
- **Measured 2026-11-04:** DP's EQ is not a clean filter: with no zero padding, any non-flat curve aliases in time. Against an ideal filter on a loud master the error is -38 dB at 512 and -45 to -52 dB at 2048 in the bass and low mids. 2048 is the cleaner EQ as well as the more accurate one; don't go back to 10 ms to chase the old sound.
- **Found 2026-10-30:** so every feature curve voiced before the frame change had *silent* 63/100 Hz entries, and moving to 2048 switched them on unheard. SBC Classic's +1.2/+0.8 dB there made it clip on Bluetooth ("harsh and tinny") and was zeroed. Surround and Tube Warmth's bloom still carry theirs (Surround: ~1 dB more at 40-64 Hz than its old 10 ms response). When restoring an old curve, restore what the old frame played, not the array.

### 1.2 Dynamics detect RMS, not peaks

- MBC bands and the limiter measure each block's **RMS** energy (`fEnergySum`), with attack/release applied per block (hop = 1024 samples ≈ 21 ms). Attack times under ~21 ms all behave as "instant".
- The limiter **cannot stop clipping**: music's RMS sits 8–14 dB under its peaks, so a -2.3 dBFS threshold almost never engages.
- MBC `preGain` is used **only for detection** — it makes the compressor engage earlier. It adds no level and no harmonics.
- A block's gain is computed from that block and applied to that block, so with attack under the hop a compressor sees a hit before the hit plays: a 46 ms look-ahead at the 2048 frame. It measures that block's RMS, so a 1 ms click inside it is not seen. Enough for bass, which is where the peaks come from.
- Nothing in DP or LE generates harmonics or saturation, and nothing mixes left into right. No real crossfeed, HRTF, upsampling, or "harmonic excitation" is possible.

### 1.3 Where clipping happens

- DP is float inside, but its output is converted to fixed point after the mixer — 16-bit for the Bluetooth encoder. Anything over 0 dBFS hard-clips there.
- Wired/speaker: Android attenuates each stream by the volume **before** the session-0 effects, so below full volume the boosts have room.
- Bluetooth with absolute volume (the default): the phone sends **full scale** and the earbuds turn it down. JadOO always sees full scale.
- Heavy clipping is heard as a level drop: the rails chop the mids riding on a clipped bass wave. Earbud or phone protection pumping the level down on top of that is a guess, never measured. Users report the result as "turning a feature on makes it quieter".
- **Found 2026-10-26** from user voice notes: Loudness + Hi-Res and Surround on Bluetooth. v1.6.4 reached +4 dBFS (≈2% of samples clipped) on loud masters. With a V-shaped EQ: +5.6 dBFS, 3.9%.
- **Compressors see a different world on Bluetooth.** On a loud master at full scale, bass zones sit at -20 to -30 dBFS on the median and -8 to -14 dBFS at the 99th percentile. Every threshold in `planMbc` was voiced by ear on a stream 10-20 dB lower. **Found 2026-11-04:** Analog Bass measured *below* bypass in the bass on loud masters: ~5 dB off each hit, held for its 280 ms release. That was the "most dip".

### 1.4 Gain staging (current design)

- No gain after the limiter except the user's post-gain (the SBC pre-encoder trim slot is 0 dB since 2026-10-30). The old +2 dB "headroom makeup" was removed; never reintroduce it.
- **Wired and speaker:** `DspEngine.levelMatchTrimDb` = the combined curve's gain on a bass-weighted music spectrum (`LEVEL_MATCH_WEIGHTS`), minus the room the system volume already gives (`measureVolumeHeadroom`), applied at the input. Result: effects-on is as loud as effects-off and does not clip.
- The curve is the full PreEQ per channel (`preEqCurve`) plus `stageBoostDb` (MBC post-gains and PostEQ shelves).
- **Bluetooth: no trim for boosts** (`DspEngine.planBluetoothMbc`, 2026-11-04, replaces every Bluetooth trim charge before it). A trim pays for a boost with the voice, and four rounds of "volume drops" reports said that is the one thing listeners won't accept. A loud master lacks room only on its hottest moments, so:
  - **Fixed zones** (60, 120, 300, 2000, 5200, 9600, 14500, 20000 Hz, plus SBC's subband edge when crest control is on), strictly ascending. Features add post-gain into them (`bluetoothFeatureGainDb`), so band order can't go wrong and Analog Bass + DBFB simply sum.
  - **Each zone's boost** = its mean PreEQ + post-gain + PostEQ. So Graphic EQ, AutoEQ, Loudness, Surround and every feature are covered without knowing about each other.
  - **Level-aware:** whole up to `BT_BOOST_SPAN_DB` (6 dB) below the zone's hot level (`BT_ZONE_HOT_DB`), zero at it. Bass zones sit at the loudest of five masters' p99: lower and kick hits fall under bypass and lose their punch over the voice (found on the first cut, -11 dBFS: hits at -1.2 dB). Above 2 kHz they sit at the typical p99 plus 2 dB: treble peaks are brief and masked, and lower only cost Hi-Res its air (+3.0 dB against the last build's +4.2). Ratio follows from boost / span, capped at 3:1 by widening the span. Attack 1 ms (look-ahead, see 1.2), release 80 ms bass, 60 ms above.
  - **Vocal zone (300-2000 Hz)** has no guard for EQ: ducking the voice is the pumping this removes. `bluetoothTrimDb` trims for bands 400 Hz-1.6 kHz only, which still leaves the voice above where it started.
  - **Level lift** `BT_LEVEL_LIFT_DB` (+1 dB, every zone, faded like any boost). Bypass is the reference listeners compare with, and level-matched to it reads as quieter. Off for HDR Pure and in SBC's crest bands.
  - **Tube Warmth:** `BT_TUBE_MAKEUP_DB` (+1 dB input gain). LE at full scale takes more off a loud master than its target gain adds; it has lowered the peaks, so the makeup fits.
  - **Limiter:** threshold not lowered by the budget, so it never ducks the mix.
  - **AutoEQ's preamp is not applied** (`applyAutoEqProfile`; a saved one is cleared on load): it makes room the plan already makes, and it cost the whole song several dB.
  - **Stack balance** (`bluetoothStackScale`): tone, not level. Features stacked past +8 dB bass or +7 dB treble scale back (floor 0.6), each end on its own, so Surround + Hi-Res running out of treble costs DBFB no bass. Computed from features only, so never on an EQ drag. Single features at default are never scaled.
  - **Live:** `updateHeadroom` re-plans and writes only bands that moved (`writeBluetoothPlan`); a cutoff never changes, so the DP keeps its envelopes. The wired `update*` writers must not touch bands by index while `bluetoothPlanActive`.
  - **Chain model, loudest 25 s of five masters at full scale, Kotlin plans through the AOSP port, against the 2026-10-30 build:** every feature, preset and stack lands above bypass with the voice at or above it. V-shaped EQ: voice -2.4 -> +0.5 dB, bass hits +1.4 -> +2.9, body +1.3 -> +4.2. Analog Bass: voice -1.1 -> +0.8, hits -0.6 -> +2.8, body +1.1 -> +5.1. All features + EQ: voice -4.1 -> +0.4, hits -0.8 -> +3.5. On the hottest master hits stay 0.4-0.7 dB above bypass, where Analog Bass was -2.8. Survives real SBC, aptX and AAC, with SBC's midrange SNR equal or better than that build.
  - **Cost:** peaks reach +2.6 to +3.6 dBFS on the hottest master and clipped energy rises to -32 to -36 dB (-29 dB with Loudness at low volume), against -37 and better before and -17 on v1.6.4. Over the masking check that is 0-3.7% of frames at worst, against up to 44% on v1.6.4. At -14 LUFS it is under -40 dB, mostly nil. Punch over the voice is 1-1.5 dB less than the trim gave on plain EQ. Voicing change: needs ears.
  - Precise gain staging off stays on the wired plan and legacy limiter, for A/B.
- Wired and speaker: the limiter threshold still drops by up to `MAX_HEADROOM_DB` (2 dB) for dense, loud material. That is all it does.
- "Precise gain staging" off = legacy: no trim, the limiter takes the whole budget.

### 1.5 The home-screen curve is the truth

`DspEngine.staticResponseDb` reads the live DP back and applies it bin by bin with `DpResponse` (same mapping as AOSP), plus input gain, limiter post-gain and LE gain. Any feature added correctly appears on it automatically. If a change does not show on the curve, the change is not reaching the engine.

---

## 2. Rules for every DSP change

1. **Check behaviour against AOSP source, not assumptions or comments.** Several comments in this codebase stated engine behaviour that the source disproves (limiter "catches overs", MBC "saturation", "bands go inert"). Link the file you checked.
2. **Cutoffs strictly ascending, every band ≥ 1 bin** at 44.1 and 48 kHz. `MbcPlanTest` checks every MBC combination; `DpResponseTest` checks the PreEQ bands. Add any new band layout to those tests.
3. **Every boost must be paid for.** A new boosting feature goes into `stageBoostDb` (where in the spectrum it lands) **and** `calculateGainBudget` (limiter threshold) for wired, and into the PreEQ or `bluetoothFeatureGainDb` for Bluetooth, or it clips there.
4. **Never add gain after the limiter** to compensate for anything.
5. **Test the Bluetooth case**: full scale, loud master (peaks near 0 dBFS). Use the offline chain model (§5) before claiming it doesn't clip.
6. **A boost must stay inside its own range.** A band's post-gain applies from wherever the previous band stopped. When a feature's first band needs to start at frequency X, something must end at X first (see the Hi-Res safety band).
7. **Voicing changes need ears.** Any change that moves bins (frame size, cutoffs, band order) re-voices the features. Say so to the user and ask them to A/B — never claim "sounds better" untested.
8. **Words must match the DSP.** Help text, subtitles, README and store copy describe what the engine actually does. No "recovers detail", "saturation", "upscaling", "surgical notches" unless it is literally true.
9. **No new feature that needs cross-channel mixing, raw samples or look-ahead.** Not possible without root; say so instead of faking it with an EQ curve under that name (Crossfeed was removed for this).
10. **Build, test both flavors, install release-signed, check logcat** (see CLAUDE.md) before calling it done.

---

## 3. Feature reference

Stage key: **Pre** = PreEQ (15 bands, `EqBands`), **MBC** = multiband compressor, **Post** = PostEQ (4 bands), **Lim** = limiter, **LE** = LoudnessEnhancer. All PreEQ contributors are summed per band in `JadooDspService.combinedCenteredGain`.

| Feature | Stages | What it really does | Rules / gotchas |
|---|---|---|---|
| Graphic EQ | Pre | 15 band steps, ±15 dB, 60 ms glide per write | Band cutoffs are geometric means (`EqBands.cutoffFrequencies`). Bass bands only work at the 2048 frame. |
| Parametric EQ / AutoEQ | Pre | Biquads evaluated over each PreEQ band's span (`DigitalFilterEngine.evaluateBandGainDb`) and folded into the 15 bands | Not surgical: anything narrower than ~2/3 octave is smeared. AutoEQ profiles are approximated the same way. A finer PreEQ (more bands) is the open fix. |
| Loudness Contour | Pre | ISO 226:2003 delta between reference and current phon, ≤12 dB, 0 dB at 1 kHz | Volume polled once a second. Bluetooth absolute volume falls back to the index ratio. Its bass leg is the biggest Bluetooth clipping source — it must stay in the trim. |
| Surround+ | Pre (per channel) | Bass/treble "smile" + alternating L/R treble gain differential. Front Stage = tonal curve only | Pure EQ, no mixing. Differential sums to zero across the treble bands. |
| Tube Warmth | Pre + LE + Lim | 63/100 Hz bloom, 10/16 kHz roll-off, LE gain 0–4 dB (its -8 dBFS 7:1 peak compressor), limiter -4 dBFS 5:1 | No saturation exists. LE is inserted before DP. Device may refuse LE → `loudnessEnhancerUnavailable`. |
| DBFB | MBC 72 / 145 / 260 Hz | Sub +1.5/+2.5, punch +0.8/+1.4, mid -0.4/-0.6, gentle compression | Ends at 260 Hz — never assume it covers more. |
| Analog Bass | MBC 60 / 120 / 300 Hz + Post (Pultec) | Bass compression (Drive = earlier/harder compression via preGain + ratio 1.8–5), Warmth = post-gain, Drift = knee width; Pultec boost/dip in PostEQ. On Bluetooth Drive moves the zones' hot level instead and Drift their knee | **Open item (wired and speaker):** with DBFB also on, the band order goes 60,120,300,72,145,260 — DBFB 72 gets no bins and 145/260 re-process Analog Bass's bins. Needs a merged layout re-voiced by ear. Excluded from `MbcPlanTest` until then. |
| Mobile Bass | MBC (90 +) 300–350 Hz leveler + Post shelf | +0–6 dB punch band with 1.1–1.3 leveler, +2.5 dB PostEQ shelf. Speaker route only | Leveler cutoff moves above DBFB/Analog Bass bands. |
| HDR Dynamics | MBC expander band(s); Pure: Lim 2:1 at -0.1 | Restoration: 1.12:1 expander below -38 dBFS + small 10/16 kHz Pre air boost. Pure: lighter limiter | Pure's limiter wins over Tube Warmth's. Ends at 5.2/8 kHz or 20 kHz depending on Hi-Res/Exciter. |
| Harmonic Exciter | MBC 2 kHz + 5.2/8 kHz lift | +0–5 dB on 2–8 kHz (to 5.2 kHz when Hi-Res is on), 1.3–1.7 compression above -12 | Generates no harmonics — it is a presence lift. |
| Hi-Res Upscaler | MBC 5.2 / 9.6 / 14.5 / 20 kHz | +2.5 / +4.0 / +5.5 dB static treble lift (scaled by driver treble extension; SBC scales the top two down) | Restores nothing. Needs the 5.2 kHz safety band unless HDR or Exciter ends there (**Found 2026-10-26:** with DBFB on, its +2.5 dB ran from 280 Hz). |
| SBC Enhancement | Pre curve + MBC crest band (New only) | Classic: treble lift. New: conditioning curve + crest compression above ~11 kHz. Bluetooth SBC only | Receiver never de-emphasizes: the curve is heard as EQ. |
| Device type | Pre correction + extension factors | Class curve + scales bass/treble feature boosts to what the driver can reproduce | Mobile Bass always uses the compact-speaker extension. |
| Pre / post gain | DP input / Lim post | User level, ±12 dB | Input gain = user pre-gain + level-match trim, clamped together. |
| Precise gain staging | Trim + Lim, Bluetooth plan | See §1.4 | Off = legacy, can clip, and Bluetooth drops to the wired plan. |
| Bluetooth headroom tip | UI | Shown when Bluetooth is on absolute volume and the trim is ≥ 1 dB; opens Developer options | Detection: slider below max but no measured attenuation. |

---

## 4. Checklists

**Changing a feature's sound**
- [ ] Read the feature's code and its AOSP stage behaviour (§1).
- [ ] Bands ascending, ≥ 1 bin, boosts stay in range — run `MbcPlanTest`, `DpResponseTest`.
- [ ] Boost added or changed? Update `stageBoostDb`, `calculateGainBudget` and `bluetoothFeatureGainDb`.
- [ ] Run the offline model (§5) on a loud master at full scale: no overs, loudness ≈ bypass.
- [ ] Help text, subtitle, README and store copy still true.
- [ ] Tell the user exactly what changed and what to listen for.

**Adding a feature**
- [ ] Possible without root, cross-channel mixing, raw samples or look-ahead? If not, stop and explain.
- [ ] Which stage, which bins, how many MBC bands — update `mbcBandCounts` and `planMbc` together.
- [ ] Add it to the PreEQ sum or `stageBoostDb`, the gain budget, backup/restore (`BackupCodec`), per-device session prefs, and the inspector's stage list.
- [ ] Check it on the home-screen curve.

**Before a release**
- [ ] All unit tests, both flavors; `compilePlayReleaseKotlin`.
- [ ] Release-signed build installed, logcat clean (`DspEngine Attached …` shows `budget` and `trim`).
- [ ] By-ear pass: Bluetooth loud master with Loudness/Hi-Res/Surround; wired at full volume; speaker with Mobile Bass; a video for lip-sync.
- [ ] Signer SHA-256 matches previous release; release body ends with `versionCode: N`.

---

## 5. Offline chain model

Use this to check level and clipping before touching the phone. `tools/dspsim/dp.py` is a line-by-line port of `DPFrequency.cpp` and the LE compressor; use it, not a new approximation. An earlier model used Hann analysis with no synthesis window, which gets smearing wrong.

1. Python venv with `av`, `numpy`, `pyloudnorm`. Loud masters from the phone (`MSYS_NO_PATHCONV=1 adb pull /sdcard/...`) or any FLAC.
2. Take each track's loudest 25 s with the file's peak at -0.1 dBFS: that is Bluetooth full scale at its worst. Repeat at -14 LUFS: that is what streaming apps send.
3. Feed real plans, not hand-copied numbers: dump `planBluetoothMbc` / `planMbc` from a throwaway unit test as JSON and run those.
4. `dp.metrics` reports loudness vs bypass, bass/voice/treble band level, peak, overs, clipped energy (dB under signal) and the 5th percentile of bass gain per 50 ms (hits ducked under bypass).

5. `tools/dspsim/dp.py` stops at the DP. For the Bluetooth stage, PyAV ships real `sbc`, `aptx` and `aac` encoders: run the 16-bit output through one and back, and compare against bypass through the same codec.

Bluetooth targets on a hot master: voice ≥ bypass, loudness ≥ bypass, bass hits ≥ bypass on average, clipped energy under about -35 dB. `overs %` alone misleads: -37 dB of clipped energy was 0.3% of samples, v1.6.4's -17 dB was 3.9%. What counts is whether the clipping is masked: a Bark-band check (spreading -25/-10 dB per Bark, mask 14 dB under it) put v1.6.4's manual EQ over the mask on up to 32% of frames and the Bluetooth plan under 1%.
