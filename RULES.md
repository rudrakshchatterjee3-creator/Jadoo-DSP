# RULES.md — pre-ship checks for JadOO DSP

Every rule below exists because the exact bug it describes was shipped in this
project, usually more than once. This is not general advice. It is a list of
mistakes with a way to catch each one.

Read this before shipping any change to `audio/`, and run the checklist at the
bottom before any release.

---

## 1. A new PreEQ contributor must be registered in FOUR places, not one

The PreEQ is a single summed write per band in `writeCombinedBand()`. Adding a
new tonal shape to that sum is only 25% of the work. The other three are silent
if you forget them — nothing crashes, the feature just does nothing, or the
limiter mis-accounts for it.

When adding any new per-band contributor:

1. **`writeCombinedBand()`** — sum it into `baseGain`.
2. **`hasActiveDspFeatures()`** — return true when it is non-zero. If you skip
   this, the engine is released to transparent bypass whenever your feature is
   the ONLY thing enabled, and it is silently inert.
3. **`preEqBassPeakDb()` / `preEqTreblePeakDb()`** — include it, or the limiter
   is not told about gain that is really there.
4. **Every setter that can change it** — call `applyAllBands()` +
   `refreshHeadroom()`, and `attachGlobalSession()` if the engine may currently
   be in bypass.

**Incidents:** Crossfeed shipped inert (documented in `hasActiveDspFeatures`).
SBC Enhancement shipped inert (same). Then device tuning shipped inert — with
both prior failures already written in comments directly above the line I
needed to edit. Three times, same function.

**Check:** enable your new feature with *everything else off* and confirm it is
audible.

---

## 2. Set state BEFORE calling anything that reads it

`setDeviceType()` triggers `rebuildDspTopology()`, which asks
`hasActiveDspFeatures()` whether to keep the engine attached. Assigning the
curve *after* that call meant the rebuild saw no active feature, released the
engine, and the correction was never written.

**Check:** for any function that both sets state and calls a rebuild/refresh,
confirm the state is assigned first.

---

## 3. Do not change a constant whose comment explains why it has that value

`MAX_AUTO_TRIM_DB = 0` carried a comment stating that trimming the input pays
for a narrowband boost with broadband level. I raised it to 10 anyway, on a
theory the comment had already answered. Result: every feature silently cut up
to 10 dB of broadband level on every output. Reported as the app being ruined.

If a comment gives a reason, you must **disprove the reason** before changing
the value — not just have a new idea.

**Check:** `git log -S<CONSTANT>` and read why it was last set.

---

## 4. A change applied to a branching path must be checked against EVERY branch

`splitGainBudget()` has two paths: precise (headroom capped at
`MAX_HEADROOM_DB`) and legacy (headroom = the entire uncapped budget). I added
makeup gain as `-headroomDb` for both. On the precise path that is ≤2 dB. On
the legacy path it was 5–13 dB — and Tube Warmth is pinned to legacy, so its
glue limiter became a loudness maximiser.

**Check:** for each branch, write down the actual numeric range your change
produces. If the ranges differ by an order of magnitude, the change does not
belong in shared code.

---

## 5. Parallel routes must be checked for parity

`computeOutputDeviceKey()` re-initialises the biquad engines at the route's
sample rate for USB and for the speaker — but not for Bluetooth. Coefficients
stayed solved for the previous route's rate, so Parametric EQ and Analog Bass
sat at the wrong frequencies.

**Check:** when touching any route branch, diff it against the sibling
branches. Anything one does and another does not is either a bug or needs a
comment saying why.

---

## 6. Platform APIs do not behave the same on every route

`getStreamVolumeDb()` returns the same dB at every index on A2DP with absolute
volume, because the sink owns the volume. Loudness Contour computed zero
attenuation and silently did nothing on all Bluetooth.

**Check:** for any platform audio query, ask what it returns on speaker, wired,
USB, and Bluetooth. Handle the degenerate answer explicitly.

---

## 7. Async handoffs need a terminal state on the SUCCESS path

`ApkUpdater.install()` returns `null` when the PackageInstaller session is
*committed*, not when the install finishes. The UI treated that as "still
working" and stayed on `Installing`, which hides every button. Declining the
system prompt reports `STATUS_FAILURE_ABORTED` to a separate BroadcastReceiver
with no path back to the dialog, so the screen read "Opening installer…"
forever.

**Check:** for every state machine, ask what leaves each state on the *happy*
path, and what happens if the external actor never reports back.

---

## 8. Never invent data that looks measured

Six device profiles (WH-1000XM4, AirPods Pro, Galaxy Buds, …) were fabricated
as plumbing examples, with `qualityTier` values that were pure guesses. They
looked exactly like a real device database. Once the schema gained per-band
curves, shipping guesses became actively harmful.

**Rule:** anything presented as device data must cite a source. If it is
inferred from prose rather than a measurement, say so in the same breath.

---

## 9. Respect "don't touch this" — voiced values are not yours

"Tube warmth is already good, don't touch it now" was stated explicitly. Its
limiter threshold was changed anyway, twice. Values tuned by ear are load-
bearing even when the code around them looks wrong.

**Rule:** if the user has approved how something sounds, changing any number
that affects it requires asking first — even when refactoring something else.

---

## 10. Verify version identifiers actually compare as newer

`UpdateChecker.parseVersion()` truncated each dot-segment at its first
non-digit, so `1.6.0-r1` parsed identically to `1.6.0` and no existing user
would ever have been offered the hotfix. Caught before shipping, but only
barely. The same latent bug had already shipped once as `v1.5-r1`.

**Check:** before tagging, evaluate `isNewer(newTag, currentVersionName)`
by hand. It must be true.

---

## 11. "Compiles" is not "works". "I fixed it" requires evidence

Repeatedly in this project a fix was announced after a clean compile and before
any device test. Several were wrong. Two were the *opposite* of a fix.

**Rule:** say "installed, untested" until it has been heard on a device. If it
cannot be verified, say which part is unverified and why.

---

## Pre-ship checklist

**Any change under `audio/`:**

- [ ] New PreEQ contributor registered in all four places (rule 1)
- [ ] Tested with the new feature as the ONLY thing enabled (rule 1)
- [ ] State assigned before any rebuild/refresh that reads it (rule 2)
- [ ] Numeric range checked on every branch of every path touched (rule 4)
- [ ] Route parity checked: speaker / wired / USB / Bluetooth (rules 5, 6)
- [ ] No constant changed without disproving its comment (rule 3)
- [ ] No ear-tuned value changed without asking (rule 9)

**Level safety — the recurring failure in this project:**

- [ ] Toggle master power with the feature on. Broadband level must not drop.
- [ ] Enabling a feature must never make the app quieter than bypass.
- [ ] Confirm on Bluetooth specifically, not just the speaker.

**Any release:**

- [ ] `isNewer(tag, installedVersionName)` evaluates true (rule 10)
- [ ] `versionCode` incremented
- [ ] Release APK built and its `versionName`/`versionCode` verified with
      `aapt dump badging`
- [ ] Installed and listened to on a device (rule 11)
- [ ] Changelog states what a user would notice, not what changed internally

---

## Standing project facts

- No raw PCM access anywhere. Everything is `DynamicsProcessing` band
  parameters plus one `LoudnessEnhancer`. Techniques needing sample access
  (HRTF, convolution, FFT, LUFS normalisation) are out of reach by
  architecture, not by effort.
- `DynamicsProcessing` is float internally. Nothing clips before the limiter,
  so "unpaid gain budget will distort" is a false premise — it was the
  reasoning behind the worst regression in this project.
- The MBC band ordering in `configureMbc()` for Analog Bass + DBFB is
  deliberately non-ascending. It is voiced. Do not "correct" it.
- Device tunings ship over the air only; the bundled content carries presets
  and DSP constants but no device profiles.
