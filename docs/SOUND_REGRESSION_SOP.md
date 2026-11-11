# Sound regression SOP (Bluetooth)

Purpose: no change to JadOO may quietly undo the sound the user accepted in
v1.6.9. Read this before any change that can touch sound. It is the gate;
`DSP_SOP.md` holds the engine facts and feature rules.

Applies to: Claude and the user, for every commit that touches the audio path
(`DspEngine.kt`, `JadooDspService.kt` DSP code, `SbcEngine.kt`, `DeviceType.kt`,
`LoudnessContour.kt`, feature curves, limiter, gains, frame size, LE/DP setup).

## 1. The reference

- Reference build: commit `dd6d127` on branch `release/v1.6.9` (versionName
  1.6.9, versionCode 15). Release APK SHA-256
  `36c0b0703d6bd113fb41901840de227834286e9c7b2b2914632e1d6c7265581d`, signer
  SHA-256 `ddb8508b…36e7`. Not published yet; the user has it installed and
  judged it good by ear on 2026-11-07 ("all ok in this version for sound quality").
- Reference files: `tools/dspsim/reference/plans_v169.json` (Bluetooth MBC plans
  for four feature sets), `guard_v169_hot.out` and `guard_v169_codec.out` (the
  measured tables), and `tools/dspsim/guard.py` (the script that makes them).
- Known open issue in the reference, not a pass: with JadOO on, loud masters get
  extra clipping that the same songs do not show with JadOO off. Measured on the
  heavy set: full-scale SBC samples per 25 s, bypass ~230 mean, reference
  ~2075 mean. A change may reduce this, but only through a voicing change
  that passes §5. It must not make it worse.

### Protected parameters

These are what the user approved. Changing any of them is a voicing change (§2 C).

- Bluetooth zones: cutoffs 60, 120, 300, 2000, 5200, 9600, 14500, 20000 Hz.
- Hot levels: -8, -9, -10, -12, -16, -19, -26, -33 dBFS (`BT_ZONE_HOT_DB`).
- Boost span 6 dB, level lift +1 dB (`BT_LEVEL_LIFT_DB`), knee 4 dB.
- Attack 1 ms; release 80 ms (bass) and 60 ms (top).
- Vocal trim on bands 6..9 only (`bluetoothTrimDb`); no trim on boosts.
- Frame: 2048-sample block (42 ms). Limiter on Bluetooth: threshold -0.3 dBFS,
  ratio 10, attack 12 ms, release 90 ms, headroom 0, post gain 0.
- Tube Warmth makeup +1 dB on Bluetooth; Analog Bass Drive/Drift mapping.

## 2. Classify the change first

- **A. No sound effect**: UI text, docs, release metadata, tests, refactor with
  identical plans. Needs G1 (plans identical) and G2.
- **B. Sound-neutral restructure**: plans identical, same numbers. Needs G1 and G2.
- **C. Voicing change**: any change to a plan number, curve, cutoff, gain, frame,
  limiter, LE, SBC, device curve, or any protected parameter. Needs G1 through
  G5. The user's ears (G5) are the only pass for a C change.

A change that can't be classified is class C.

## 3. Gates

**G1. Plan identity.** The Bluetooth plans must be dumped from the real Kotlin
code (`DspEngine.planBluetoothMbc`) and diffed against `plans_v169.json`. Any
difference must be in the change's stated scope. Note: the dump test is not
in the repo yet (see §6). Until it is, no C change can pass G3, so it cannot
ship.

**G2. Tests.** `./gradlew … :app:testGithubDebugUnitTest :app:assembleGithubRelease` (JDK 17, see CLAUDE.md).
`MbcPlanTest`, `DpResponseTest`, `BluetoothPlanTest` and `LevelMatchTrimTest`
must pass.

**G3. Offline gate.** Run the script and compare with the reference tables.

```
set JADOO_MUSIC_DIR=<folder with through.flac, tobu.flac, monica.flac, ishq.flac, gehra.flac>
python tools/dspsim/guard.py > current_hot.out
python tools/dspsim/guard.py --codec > current_codec.out
```

Needs Python with numpy, scipy, av and pyloudnorm. Do not reuse the old
scratchpad venv: it is damaged.

Thresholds are for variant A (the shipped plan) against the reference. "mean" and
"worst" are the two numbers in each table cell.

| Set (variant A) | Metric | Pass if | Reference |
|---|---|---|---|
| Heavy, Analog+DBFB N, BassEQ4 (hot) | L, loudness vs bypass (mean) | within ±0.3 LU | 2.54 / 1.93 / 1.35 |
| same | mid, 500 Hz–4 kHz voice (mean) | ≥ reference − 0.2 dB | 0.22 / 0.64 / 0.64 |
| same | bass_p5, bass hits (mean) | ≥ reference − 0.5 dB | 2.98 / 1.49 / 1.81 |
| same | pk, output peak (worst) | ≤ reference + 0.3 dB | 3.56 / 2.66 / 1.67 |
| same | over, overs % (mean) | ≤ reference × 1.2 | 0.16 / 0.12 / 0.08 |
| same | over (worst) | ≤ reference + 0.1 pp | 0.60 / 0.47 / 0.35 |
| same | clip, clipped energy (worst) | ≤ reference + 2 dB | −32.4 / −34.1 / −37.8 |
| same | mask, unmasked clipping (worst) | ≤ reference + 0.5 pp | 2.91 / 2.72 / 0.47 |
| Heavy, -14 LUFS | over (worst) | ≤ 0.05 % | 0.01 |
| same | mask (worst) | ≤ reference + 0.3 pp | 0.19 |
| Codec, hot, Heavy | SBC full-scale samples (mean) | ≤ 2,280 (×1.1) | 2,075 |
| same | aptX full-scale samples (mean) | ≤ 2,210 (×1.1) | 2,010 |
| same | AAC full-scale samples (mean) | ≤ 290 (×1.2) | 241 |

Reference codec numbers are in `guard_v169_codec.out`; bypass through the same
codecs is listed there too. Default (lift-only) rows are not gated: the app
bypasses when no feature is on, so that plan is never heard.

A change that moves a metric past its threshold fails G3. A change must never
move a threshold to make it pass. If a change fails G3 on purpose (for example
to cut clipping), it must go to the user as a C change with the numbers, and
only G5 can approve it.

**G4. Device.** Install the release-signed APK (`adb install -r`, keeps settings),
check the signer digest, `am start -n com.jadoo.amp/.MainActivity`. In logcat,
on the Bluetooth route: `Attached session=0 … btPlan=true`, the features shown
match the test settings, and there is no FATAL or AndroidRuntime line. Never tap
the phone; the user plays the music.

**G5. Ears (user only).** A/B on the same route (SBC to laptop as now), same
phone volume, same tracks:

1. The five golden tracks, plus any song the user reports as distorting.
2. JadOO off, then JadOO on, about 20 s each, same point in each song.
3. Per song, the user answers: louder or quieter, distorted yes/no, which
   setting was on.

A C change passes only when the user says it is no worse on the reference set
and better where it was intended. Claude must not write "sounds better" or "fixed"
without G5.

## 4. Rules

- Never add gain after the limiter.
- Never change a threshold, a reference number, or a reference file to make a
  change pass. Reference updates need the user's approval and a new version.
- Never call a change an improvement on numbers alone. Only G5 makes that claim.
- Keep the voice rule in `DSP_SOP.md` (voice never below bypass). Any change that
  breaks it is a C change and needs the user's explicit decision.
- Feature curves and boosts go into the budget and the plan together. See
  `DSP_SOP.md` §2.

## 5. Checklist (copy into the commit notes)

- [ ] Class stated: A, B or C.
- [ ] G1: plans identical, or every difference is in scope and listed.
- [ ] G2: tests pass.
- [ ] G3: all table rows within threshold (C only).
- [ ] G4: release APK installed, `btPlan=true` on the Bluetooth route, no crash.
- [ ] G5: user ear result recorded (C only).
- [ ] `DSP_SOP.md` and in-app help text still match the sound.

## 6. Known gaps (to close before relying on the gate)

1. **Feature settings of the four reference sets are not recorded.** `plans_v169.json`
   has the plans, but not the exact feature settings behind "Heavy",
   "Analog+DBFB N" and "BassEQ4". Fix: write each set as an explicit definition
   in a Kotlin test, re-dump, and re-baseline with the user's approval.
2. **The plan dump test is not in the repo.** Add it under `app/src/test` (temporary
   pattern: dump to `app/build`, copy out, delete). Until then G1 and G3 cannot run.
3. **The offline model cannot hear.** Codec bitrates are assumed (SBC 328k, aptX
   352.8k, AAC 256k). Phone DAC, earbud DSP and absolute-volume scaling are not
   modelled. G5 is the only real test.
4. **Known open issue** (§1) stays open. Any fix goes through G5.
5. **Not in the gate:** wired and speaker routes, Mobile Bass, HDR Pure and tube
   Warmth variants. Changes there need their own table before they ship.

## 7. Change log

- 2026-11-08: created from the v1.6.9 reference. Thresholds are proposals until the
  user approves them.
