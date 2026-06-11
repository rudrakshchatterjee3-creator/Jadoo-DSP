<div align="center">

<img src="app/src/main/res/drawable/ic_jadoo_dsp.png" width="96" alt="JadOO DSP icon" />

# JadOO DSP

**Professional-grade audio processing for Android — built from first principles.**

*Because your music deserves better than what your phone gives it out of the box.*

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-Proprietary-red)](#)

</div>

---

## ⚠️ Before you install: a note about Google Play Protect

When you sideload this APK, **Google Play Protect may block it** with a warning like *"Unsafe app blocked"* or flag it as harmful. **JadOO DSP is not malware** — this is a known, explainable false positive.

Here's exactly why it happens: to do its job, JadOO needs `RECORD_AUDIO` (so it can attach a `Visualizer` and "see" the audio spectrum for Auto-EQ — nothing is ever recorded, saved, or sent anywhere), an always-on foreground service (so the DSP doesn't get killed mid-song), and the optional system `DUMP` permission (a fallback used to figure out which app is currently playing audio, on devices where that information isn't broadcast normally). **That combination — microphone access + a persistent background service + system-level audio session access — is the same fingerprint as audio-surveillance spyware**, so Play Protect's heuristics flag it automatically. It cannot tell the difference between "reading FFT data to run an equalizer" and "recording your microphone."

**To install anyway:**
1. When Play Protect blocks the install, tap **More details → Install anyway**.
2. Or, before installing: open **Play Store → profile icon (top right) → Play Protect → Settings (gear icon)** and turn off **"Scan apps with Play Protect"** temporarily. You can turn it back on right after installing.

Every permission and every audio API call JadOO makes is visible in this repository under [`app/src/main/java/com/jadoo/amp/`](app/src/main/java/com/jadoo/amp/) — see the [Permissions](#permissions) table below for what each one is actually used for.

---

## Download

Grab the latest signed release APK from the **[Releases page](https://github.com/rudrakshchatterjee3-creator/Jadoo-DSP/releases/latest)**.

```bash
adb install JadOO-DSP-release.apk
```

(See the Play Protect note above if your device blocks the install.)

---

## The story

There's a moment most audiophiles know well — you put on a great track, on great headphones, and something still feels *off*. The bass is muddy or missing. The vocals are pushed to one side. The mix sounds compressed, lifeless, glued-together.

JadOO DSP was built to fix that.

Not with simple bass-boost sliders, but with the same signal-processing ideas that live inside professional mixing consoles, mastering studios, and vintage analog gear — rebuilt from scratch on top of Android's audio framework, running entirely on your phone, in real time, on whatever app you're listening to.

---

## What it does

### 🎚 15-Band Graphic EQ
A full-spectrum equalizer spanning **25 Hz to 16 kHz**, with bands aligned to ISO standard centre frequencies. Every cutoff is set to the geometric mean between adjacent bands, so your adjustments land exactly where you intend — not half an octave away.

### 🎛 8-Band Parametric EQ
For the surgically precise. Each of the 8 bands is fully configurable:
- **8 filter types**: Peak, Low Shelf, High Shelf, Low-Pass, High-Pass, Band-Pass, Notch, All-Pass
- **Draggable frequency response graph** — tap to add a band, drag nodes to reshape your sound visually
- **Range**: 20 Hz – 20 kHz, ±15 dB gain, Q from 0.1 to 18.0
- **Per-band color coding** so you always know which band you're touching
- **Preamp gain** to compensate for boosts that push levels too hot

Coefficients come straight from Robert Bristow-Johnson's Audio EQ Cookbook.

### 🧠 Auto-EQ — PsychoacousticsBrain
This is the one that required the most care to get right.

Auto-EQ listens to whatever is playing through a real-time FFT visualizer, builds a rolling spectral picture over a 12-sample window, and every 3 seconds quietly nudges the EQ toward a correction target — without you having to do anything. Three target curves are available:

| Mode | Character |
|------|-----------|
| **Harman Curve** | Research-backed consumer preference target. More bass, gentle treble lift |
| **Balanced** | A gentle "fun" curve — lifted bass, vocal presence and air, with a small dip through the boxy 160-400 Hz region |
| **Exquisite Mids** | Forward mids, recessed bass and air. Ideal for vocals and acoustic |

Every correction glides in smoothly over 2 seconds so you never hear a sudden jump. It waits for at least 4 clean FFT frames before making any moves. It skips silence entirely. And it caps corrections at **±4.5 dB** — enough to fix problems, not enough to wreck a mix.

> *"Auto-EQ should correct actual problems, not push every song toward a template."*
> — source comment, `PsychoacousticsBrain.kt`

### 🔥 Tube Warmth
A tonal emulation of valve-amp playback, for thin or overly clinical-sounding digital tracks. Real tube saturation needs raw-sample access Android won't grant without root, so Tube Warmth instead recreates the *signature* of one: a gentle low-end "bloom" around 60–100 Hz (transformer coupling), a soft high-frequency roll-off above 10 kHz (output transformer / plate capacitance), and a slow soft-knee "glue" compander (via `LoudnessEnhancer`, 0–4 dB target gain) for gentle dynamic rounding. One Intensity slider controls all three together.

### 🎸 Analog Bass Engine
A vintage-console-style bass character module — built entirely from Android's `DynamicsProcessing` multiband compressor (MBC) and post-EQ stages, since JadOO intercepts audio at the OS session level and never has access to raw PCM.

**Three MBC bands shape the low end:**

| Band | Range | What it does |
|------|-------|--------------|
| Sub-bass | 20–60 Hz | Drive-controlled saturation/compression (ratio scales from 1.8:1 to 5:1 with the Drive slider) |
| Low bass | 60–120 Hz | Warmth body boost — gain and ratio scale with the Warmth slider |
| Upper bass | 120–300 Hz | Mud control, with a Pultec-style gain dip |

**Pultec EQP-1A-style EQ** layers a simultaneous boost/cut on top: a low-shelf boost (up to ~8 dB) at your chosen Pultec frequency, plus a gentle dip an octave or two above it — the classic "boost and cut at once" trick that makes the sub-bass swell while the muddy region just above it falls away.

| Control | Effect |
|---------|--------|
| **Drive** | Sub-bass compressor input gain & ratio — more drive, thicker and more saturated bass |
| **Warmth** | Low-bass body gain and the Pultec dip depth — more warmth, fuller and rounder |
| **Pultec frequency** | 20 / 30 / 60 / 100 Hz — where the boost/cut pair is centered |

### 🌟 Hi-Res Upscaler
Recovers treble detail that lossy compression throws away — without moving a single slider on your EQ graph. Three MBC bands spanning **5.2 kHz to 20 kHz** apply gentle makeup gain (+2.2 dB to +4.5 dB) to the air/presence region, paired with mild downward expansion (1.18:1 to 1.28:1) and fast attack times (0.6–1.5 ms) so cymbals, room ambience and "sparkle" come through without also dragging up hiss and noise floor. Transparent on already-bright material, noticeably airier on well-recorded tracks.

### 🎬 HDR Dynamics
Two ways to push back against the "loudness war":

- **Pure** — a near-transparent path. The MBC stage is fully linear (1:1, no compression or expansion) and the safety limiter relaxes to a gentle 2:1 ceiling at -0.1 dBFS — just enough to catch true-peak overs without coloring the sound.
- **Restoration** — for heavily brickwalled masters. A gentle 1.15:1 downward expander widens the gap between quiet and loud passages (no peak compression at all), paired with a small air-shelf lift (+0.6 dB at 10 kHz, +1.0 dB at 16 kHz) to recover detail lost to hyper-compression.

### 🔊 Surround+
Stereo widening with one non-negotiable rule: **vocals stay centered**.

Every mode applies a bass+treble "smile" EQ — identical in both ears — to make the mix feel bigger and more open. Bands 4–10 (160 Hz – 2.5 kHz, vocals and mids) always get **zero** extra gain from this curve.

| Mode | Smile shape | Stereo width |
|------|-------------|--------------|
| **Off** | None | Pass-through |
| **Traditional** | Broad smile, peaking at +4 dB at 25 Hz / 16 kHz | Small ±1.5 dB left/right swap at 10 kHz and 16 kHz |
| **Front Stage** | Smaller +2.5 dB smile, plus a forward vocal-presence lift at 1 / 1.6 / 2.5 kHz | None — dialogue stays dead center |
| **Ultra Wide** | Biggest smile, peaking at +6 dB at 25 Hz / 16 kHz | Stronger ±2.5 to ±3.5 dB swap across 4 / 6.3 / 10 / 16 kHz, for a "180°" image |

The left/right swaps **alternate sign band-to-band and always sum to zero** for a given mode — left leads at one treble band, right leads at the next — so the image gets wider without anything ever drifting permanently toward one ear.

### 🥁 DBFB — Dynamic Bass Feedback
Three modes (Off, Normal, High) that use a multiband compressor in the bass region (below ~260 Hz) to dynamically reinforce punch. When DBFB is active, Auto-EQ backs off its own bass corrections and lets DBFB handle the heavy lifting, avoiding double-correction.

---

## Architecture

```
MainActivity (Compose)
    │
    └── JadooDspService (Foreground Service)
            │
            ├── DspEngine            — builds/configures the DynamicsProcessing
            │                           PreEQ + MBC + PostEQ + Limiter chain
            ├── DigitalFilterEngine  — IIR biquad filter bank for the
            │                           Parametric EQ (8 independent bands)
            ├── AnalogBassEngine     — holds Drive/Warmth/Pultec parameters,
            │                           translated into MBC + PostEQ bands by DspEngine
            ├── PsychoacousticsBrain — Auto-EQ FFT analysis & glide loop
            └── SessionController    — per-app audio session resolution
```

The service runs as a **foreground service** with an ongoing notification, and applies effects via Android's `DynamicsProcessing`, `LoudnessEnhancer`, `Visualizer` and `AudioEffect` APIs — no root required. JadOO can register as the system's **External Equalizer** (`DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`), so apps like media players can hand their session to it directly; for everything else it falls back to `MediaSessionManager` and an optional `DumpSys`-based scan to find the active session.

All state is managed as `StateFlow` and observed by the Compose UI. The service exposes a local `IBinder` so the UI and service share the same process without IPC overhead.

---

## UI Screens

### Dashboard
The main screen. Every module — Graphic EQ, Auto-EQ, Tube Warmth, Analog Bass, Hi-Res Upscaler, HDR Dynamics, Surround+, DBFB — has its own card: toggle on/off, adjust intensity, tap to expand. The active app label and audio session ID are shown live at the top so you always know what's being processed, plus a help (ⓘ) button on every card explaining exactly what it does.

### Parametric EQ
A full-screen editor with:
- **Dark frequency response graph** — tap anywhere to add a band, drag nodes to tune
- **Band selector tabs** — jump between bands
- **Filter type pills** — Peak / Low Shelf / High Shelf / Low-Pass / High-Pass / Band-Pass / Notch / All-Pass
- **Per-band color picker**
- **Preamp slider** at the bottom
- **Save/load/overwrite/delete presets**

---

## How it's wired

JadOO never sees raw PCM samples. Instead, it attaches a single `DynamicsProcessing` effect to Android's **global audio session** — the shared mix bus that every app's audio passes through after the OS sums it. That one effect instance has up to four stages, and every feature you toggle is translated into gain/ratio/threshold numbers for one or more bands inside it:

1. **Pre-EQ** (15 bands) — your manual Graphic EQ, Auto-EQ's live corrections, and Surround+'s left/right treble swap, all summed per band
2. **Multiband Compressor (MBC)** — up to 10 bands allocated dynamically across Analog Bass saturation, DBFB bass reinforcement, HDR Dynamics, and the Hi-Res air bands, each running on its own frequency range
3. **Post-EQ** — Pultec-style boost/cut bands for Analog Bass
4. **Limiter** — a safety ceiling whose threshold is recalculated from a "headroom budget" every time a gain-adding feature is toggled, so stacking features doesn't silently start clipping

Tube Warmth runs as a separate `LoudnessEnhancer` effect alongside this chain, and Auto-EQ runs a `Visualizer` purely for FFT analysis — neither touches the DynamicsProcessing topology directly.

Because every feature shares one effect instance, turning any module on or off rebuilds the whole topology in one atomic step — there's no "stack of independent effects" to get out of sync.

---

## Building

Requirements: Android Studio Hedgehog or later, JDK 17, Android SDK 34.

```bash
git clone https://github.com/rudrakshchatterjee3-creator/Jadoo-DSP
cd Jadoo-DSP
./gradlew assembleRelease
```

The release APK will be at `app/build/outputs/apk/release/JadOO-DSP-release.apk`.

For debug builds with logging:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions

| Permission | Why |
|------------|-----|
| `RECORD_AUDIO` | Attaches a `Visualizer` to the audio session for Auto-EQ's FFT analysis. Audio is read in memory only — never recorded, stored, or transmitted. |
| `MODIFY_AUDIO_SETTINGS` | Required to create and configure `DynamicsProcessing` / `AudioEffect` / `LoudnessEnhancer` instances |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keeps the DSP service alive while music plays, with the media-playback foreground service type required on Android 14+ |
| `POST_NOTIFICATIONS` | Shows the persistent "DSP active" notification (required to post notifications on Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets you exempt JadOO from battery optimization so the DSP service isn't suspended mid-track |
| `DUMP` *(optional, system-level)* | Fallback session detection via `dumpsys` for apps that don't broadcast their audio session — see the [Play Protect note](#-before-you-install-a-note-about-google-play-protect) above for why this is flagged |

---

## Technical notes

- **Sample rate aware**: the engine detects the actual device sample rate via `AudioManager` on startup and reconfigures `DigitalFilterEngine`'s biquad coefficients accordingly. No hardcoded 48 kHz assumptions.
- **Thread safety**: parameters shared between the UI thread and the DSP thread are `@Volatile` or guarded by a mutex/synchronized block. Compound changes (e.g. moving the Pultec frequency) are applied atomically so you never hear a boost at the new frequency paired with a cut from the old one.
- **Headroom budgeting**: every feature that adds gain (Analog Bass drive, Tube Warmth, DBFB, Hi-Res) reports its worst-case contribution, and the Limiter's ceiling is recalculated from that total whenever a feature or its intensity changes.
- **Per-channel EQ**: almost every feature writes identical left/right gains. The one exception is Surround+'s Traditional/Ultra Wide treble swap, which writes independent left/right gains for a handful of bands to create real, balanced stereo width.

---

<div align="center">

*Built with obsessive attention to signal quality.*

</div>
