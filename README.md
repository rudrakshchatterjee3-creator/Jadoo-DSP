<div align="center">

<img src="app/src/main/res/drawable/ic_jadoo_dsp.png" width="96" alt="JadOO DSP icon" />

# JadOO DSP

**An equalizer and sound shaper for everything your Android phone plays, built from first principles.**

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-Proprietary-red)](#)

</div>

---

## Download

Get the latest signed APK from the **[Releases page](https://github.com/rudrakshchatterjee3-creator/Jadoo-DSP/releases/latest)**. The asset is called `JadOO-DSP-release.apk`.

```bash
adb install JadOO-DSP-release.apk
```

Once it's installed, the app checks GitHub for new versions and can install them for you. You can also run **Check for updates** in Settings.

If Android blocks the install, read [the Play Protect note](#play-protect-may-block-the-install) at the bottom. It's a false positive, and the note explains why.

---

## What this is

JadOO DSP is an Android app that changes how everything on your phone sounds: music, video, any app. It works on Android's own audio effects, so it needs no root.

I started it because bass boost sliders weren't enough. I wanted the ideas from mixing consoles and vintage gear (multiband compression, Pultec-style EQ, loudness curves) running on a phone, in real time.

**Built from first principles** means I didn't trust the comments or the docs. How Android's `DynamicsProcessing` really behaves was checked against its source (`DPFrequency.cpp` and the compressor code). Several things the code comments claimed turned out to be wrong, so those were fixed, not copied. `tools/dspsim` is a line-by-line port of that DSP, and changes are modeled there before they reach a phone. The filter math comes from the Audio EQ Cookbook, the loudness curves from ISO 226, and the Pultec and compressor behavior from how those units are documented to work, then checked against the model.

What it can't do matters just as much. JadOO never sees raw audio samples, because Android doesn't give them to apps like this. It sets gains, ratios and thresholds on Android's built-in `DynamicsProcessing` and `LoudnessEnhancer`. So it doesn't generate harmonics, it can't truly saturate a signal, and it doesn't mix one channel into the other. Where a feature is named after analog gear, it copies the tonal shape of that gear, not the circuit.

---

## Features

### Tone

**15-band graphic EQ.** Centers run from 25 Hz to 16 kHz on the usual ISO steps. Each band's cutoff is the geometric mean of its center and the next one, so a boost lands where you drew it and not half an octave away.

**16-band parametric EQ.** Every band has its own frequency (20 Hz to 20 kHz), gain (±15 dB) and Q (0.1 to 18). There are eight filter types: peak, low shelf, high shelf, low-pass, high-pass, band-pass, notch and all-pass. You tap the graph to add a band and drag to move it. Each band has its own color, and there's a preamp for compensating boosts. The filter math is from Robert Bristow-Johnson's Audio EQ Cookbook. Presets can be saved, overwritten and deleted.

**Headphone correction (AutoEQ).** Search a database of more than 8,000 measured headphones and load the matching correction into the parametric EQ. The list and each profile are downloaded the first time you use them, then cached. All searching happens on your phone, and your search text isn't sent anywhere. The correction applies to the output you're connected to.

**HearPrint.** A personal curve for you and the headphones you're wearing. A short tone test plays soft beeps at eleven pitches from 250 Hz to 12 kHz while you hold a button for as long as you hear them, and finds the quietest level you still hear at each one. That's compared with typical hearing (ISO 226), and the regions you hear less get about half the difference back: at most +6 dB, +4 dB at 12 kHz, smoothed so one shaky beep can't spike the curve. It never makes the sound darker: treble is never cut, bass and low mids get at most +2 dB and never more than the treble. Both ears hear every beep together, so left and right always get the same curve and the stereo image stays put. Nothing is calibrated, so it measures your ears and your headphones together, which is why it's saved per output. If an AutoEQ correction is on, HearPrint leaves the headphone part to it. It is a sound setting, not a hearing test, and it can't tell you anything medical. It's not available on the phone speaker.

**Taste profile.** HearPrint's second half: what you like, not what you miss. A few questions set a starting point, then you choose between two versions of your own music (or the built-in reference track) until the app has learned how much bass, warmth, clarity and air you prefer. Usually 9 to 19 choices, about three minutes. The two versions always keep the voice at exactly the same level, so you're comparing tone, not volume. You can keep up to eight named tastes per output and switch between them.

**JadOO AI (optional).** In the taste questions you can also describe the sound you want in your own words. If you install JadOO AI, a small language model (Qwen3 0.6B) reads that text on your phone and nudges the starting point by at most 3 dB per dimension. It isn't in the APK: it's a one-time download of about 365 MB from Google and Hugging Face, checked against fixed SHA-256 hashes before it's used, and it needs a 64-bit ARM phone. What you write never leaves the phone. You can remove it again from the same screen.

### Character

**Tube Warmth.** A valve-amp flavor, for thin or clinical tracks. Real tube saturation isn't possible here, so it recreates the tonal traits instead: a small bass bloom at 63 and 100 Hz, a gentle treble roll-off at 10 and 16 kHz, and `LoudnessEnhancer`, which adds 0 to 4 dB of gain and holds the peaks back with its own fast compressor (knee at -8 dBFS). One Intensity slider moves all three.

**Analog Bass.** A vintage bass character built from three multiband compressor bands (about 20 to 60 Hz, 60 to 120 Hz and 120 to 300 Hz) plus a post-EQ stage.

| Control | What it does |
|---------|--------------|
| Drive | Compresses the bass earlier and harder. The ratio goes from 1.8:1 to 5:1. |
| Warmth | Adds body gain in the low bass and a mild lift around 500 Hz. |
| Drift | Widens the compressor knee, so it eases in more gently. |
| Pultec boost and cut | A low-shelf boost (up to 8 dB) plus a dip of up to 5 dB just above it. This is the old "boost and cut at once" trick. It makes the lowest bass swell while the muddy region above it falls away. |
| Pultec frequency | 20, 30, 60 or 100 Hz. The boost reaches up to 1.5 times this frequency and the dip sits up to 4 times it. |

The Pultec controls don't apply while Mobile Bass is on, because both use the same post-EQ.

**Hi-Res Upscaler.** Lifts the top octaves for more air and sparkle without moving a slider on your EQ graph. It brightens what's already in the file. It can't restore detail that a lossy file threw away. Three bands from 5.2 kHz to 20 kHz add +2.5, +4.0 and +5.5 dB, scaled down on gear that can't reproduce that range.

**JadOO DBFB.** Three modes (Off, Normal, High). A compressor below about 260 Hz that reinforces bass punch based on level.

**Harmonic Exciter.** Lifts the 2 to 8 kHz presence range for clarity. It works on every output. It's a band boost, so it doesn't add harmonics the way a hardware exciter does.

**HDR Dynamics.** Two ways to push back on heavily compressed masters:

- **Pure** is close to transparent. The multiband stage is linear (1:1) and the limiter relaxes to a 2:1 ceiling at -0.1 dBFS, enough to catch overs without coloring the sound.
- **Restoration** is for brickwalled masters. A gentle 1.12:1 downward expander below -38 dBFS widens the gap between quiet and loud parts, with no peak compression. It's paired with a small treble lift (+0.6 dB at 10 kHz and +1.0 dB at 16 kHz). The expander ratio and threshold can be tuned from the project's content file without an app update.

With Pure HDR and Tube Warmth both on, Pure's limiter wins, and Tube Warmth's softer limiter isn't used.

### Space

**JadOO Surround+.** Stereo widening, in four modes. Each mode adds a bass and treble "smile" curve that's identical in both ears, which makes the mix feel bigger. Most of the 160 Hz to 2.5 kHz range gets no gain from it, so vocals aren't pushed around.

| Mode | Tone | Stereo width |
|------|------|--------------|
| Off | None | None |
| Traditional | Up to about +2 dB in the bass and +3.6 dB at 16 kHz | A small ±1.5 dB left and right swap at 10 and 16 kHz |
| Front Stage | A forward, speaker-like sound: a bloom around 160 Hz (+1.8 dB), a vocal lift of about +1 dB between 1 and 2.5 kHz, and a treble roll-off down to -2.5 dB at 16 kHz | None, so dialogue stays dead center |
| Ultra Wide | Up to about +3 dB in the bass and +4.5 dB at 16 kHz | A stronger ±2.5 to ±3.5 dB swap across 4, 6.3, 10 and 16 kHz |

The left and right swaps alternate sign from band to band and add up to zero, so the image gets wider without leaning toward one ear. Front Stage can't be combined with Analog Bass.

### Level

**Loudness Contour.** Quiet music loses bass and treble to the way ears work. This adds them back at low volume, following the ISO 226:2003 equal-loudness curves. You set the reference level of your gear at full volume, and it never boosts past that reference. The phone's volume is checked about once a second.

### Phone speaker and Bluetooth

**JadOO Mobile Bass.** Fuller bass from the phone's own small speaker. It appears only when audio is going to the phone speaker.

**SBC Enhancement.** For Bluetooth headphones that use the SBC codec. It shapes the signal so the encoder spends its limited data on what you actually hear, which helps with watery cymbals and harsh "s" sounds. It has two styles and is off for other codecs.

### Per device, per app

**Device type.** Tell JadOO what you're listening on: General, In-Ear Monitor, On-Ear, Over-Ear, Compact Speaker or Home Speaker. Bass and treble features are scaled to what that kind of driver can reproduce. The phone speaker is fixed to General.

**Settings are saved per output.** Your headphones, your speaker and your Bluetooth earbuds each keep their own settings.

**Per-app profiles.** Give one app its own settings on every output.

### Gain staging

Boosts need headroom, or loud songs clip. How JadOO makes room depends on the route:

- **Precise gain staging** (on by default). On wired and speaker outputs, the input is trimmed just enough to match the unprocessed level. Below full volume the phone's own volume already makes that room. On Bluetooth the boosts are made level-aware instead (see [How it works](#how-it-works)).
- **Keep full loudness** (off by default, in Settings). Skips that input trim on wired and speaker outputs, so turning features on doesn't lower the volume when your phone is at full volume. The catch is that boosts then have no room, so loud songs can clip. It doesn't affect Bluetooth.
- Turning Precise gain staging off goes back to the old behavior, where the limiter takes the whole boost. Use it only to compare.

### Other things

- **Live Chain** is a read-only view of the stages your audio passes through. The full signal path needs the optional `DUMP` grant (see Permissions).
- **Export and import** backs up every setting, including your EQ curve and saved presets, to one file. You can restore it later or move it to another phone.
- **Appearance** has color themes and a Liquid Glass look.
- **Languages:** English, Deutsch, Français, Polski and 简体中文. Pick one in Settings, or follow the system language.
- **Help** is behind the (i) button on each feature.
- **Crash reports** are saved so you can send them along.

---

## How it works

JadOO attaches one `DynamicsProcessing` effect to Android's global audio session, which is the shared mix that every app's sound passes through. Each feature you switch on becomes gain, ratio and threshold numbers on bands inside that one effect. There are four stages:

1. **Pre-EQ** (15 bands): your graphic EQ, the parametric EQ, HearPrint, your taste profile, Surround+ and the other tonal shapes, summed per band.
2. **Multiband compressor** (up to 12 bands): shared out between Analog Bass, DBFB, HDR Dynamics, Hi-Res and the Exciter, each on its own range.
3. **Post-EQ**: Analog Bass's Pultec boost and cut, or Mobile Bass's small overtone shelf.
4. **Limiter**: the safety ceiling. It measures average level, not peaks, so it can't stop clipping on its own.

Tube Warmth runs as a separate `LoudnessEnhancer` next to this chain.

Because everything shares one effect, switching a module on or off rebuilds the whole chain in one step.

The parametric EQ is separate: it runs as a bank of biquad filters (up to 16) in `DigitalFilterEngine` and is folded into the Pre-EQ.

**Frames and bins.** DynamicsProcessing works on FFT bins, not smooth filters. JadOO asks for a 42 ms frame (2048 samples, about 21 Hz per bin), which gives the bass bands real bins to work with. The cost is about 43 ms of extra latency. The curve on the home screen is read back from the running engine and smoothed for display.

**Bluetooth.** With absolute volume the phone sends full level at every volume step, so there's no spare room for a boost, and compressors tuned for a turned-down signal work far too hard. Bluetooth gets its own plan: fixed frequency zones that every feature adds its gain into, each with a compressor set so the boost stays whole at normal levels and fades out on a song's hottest moments. The voice isn't turned down to make room for bass, and a small level lift means switching a feature on never sounds like a volume drop.

```
MainActivity (Compose)
    |
    +-- JadooDspService (foreground service)
            |
            +-- DspEngine            builds and updates the DynamicsProcessing chain
            +-- DigitalFilterEngine  biquad filter bank for the parametric EQ
            +-- SbcEngine            SBC encoder conditioning for Bluetooth
            +-- SessionController    finds the active audio session
```

The service runs in the foreground with an ongoing notification. JadOO can register as the system's external equalizer, so media players can hand their session to it directly. For everything else it uses `MediaSessionManager`, with an optional `dumpsys` scan as a fallback. State is held in `StateFlow`s that the Compose UI observes, and the UI and service share one process through a local binder.

---

## Screens

- **Home:** the power button, a live tonal response graph, the signal path, and all features grouped as Tone, Character, Space and Level, plus Phone speaker or Bluetooth when they apply.
- **Parametric EQ:** a full-screen editor with the response graph, band tabs, filter type pills, per-band colors, a preamp slider and presets.
- **Headphone correction:** search and load an AutoEQ profile.
- **HearPrint and Taste:** the tone test, the A/B choices and your saved tastes.
- **Live Chain:** the path your audio takes, from the playing app to the output.
- **Settings:** device type, gain staging, per-app profiles, appearance, export and import, and updates.

---

## Building

You need a recent Android Studio, JDK 17 and Android SDK 36 (the app's minimum is Android 9, API 28).

```bash
git clone https://github.com/rudrakshchatterjee3-creator/Jadoo-DSP
cd Jadoo-DSP
./gradlew installGithubDebug
```

That builds a debug APK and installs it on a connected phone.

A release build is signed with your own key. Put `jadoo.jks` in the project root and a `keystore.properties` file next to it, then run:

```bash
./gradlew assembleGithubRelease
```

The APK lands at `app/build/outputs/apk/github/release/JadOO-DSP-github-release.apk`. There is one build flavor, `github`, and it includes the in-app updater.

---

## Permissions

| Permission | Why |
|------------|-----|
| `MODIFY_AUDIO_SETTINGS` | Create and configure the `DynamicsProcessing`, `AudioEffect` and `LoudnessEnhancer` instances |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep the DSP service alive while music plays (the media-playback type is required on Android 14 and up) |
| `POST_NOTIFICATIONS` | Show the ongoing "DSP active" notification (Android 13 and up) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Let you exempt JadOO from battery optimization so the service isn't suspended mid-track |
| `INTERNET` | Check GitHub for new versions, download updates you choose to install, download updated tuning content, fetch AutoEQ profiles, and download JadOO AI if you choose to install it. Nothing personal is sent. |
| `REQUEST_INSTALL_PACKAGES` | Install an update you approved from inside the app |
| `DUMP` (optional) | A fallback for finding the playing app's audio session through `dumpsys`. It isn't granted at install. You grant it with `adb shell pm grant com.jadoo.amp android.permission.DUMP`. |

The manifest also declares which apps JadOO may look up (launcher apps and media browser services), so it can show what's playing. That's a package-visibility setting, not a permission prompt.

---

## Technical notes

- **Sample rate:** the engine reads the device's real sample rate at startup and recalculates the biquad coefficients to match. Nothing assumes 48 kHz.
- **Thread safety:** values shared between the UI and DSP threads are `@Volatile` or synchronized. Compound changes, such as moving the Pultec frequency, are applied together, so you never hear a boost at the new frequency with a cut from the old one.
- **Headroom:** on wired and speaker outputs, every feature that adds gain reports its worst-case boost, and the input trim and limiter are set from the total. On Bluetooth the same boosts are level-aware instead.
- **Per-channel gains:** nearly every feature writes the same gain to left and right. The one exception is the Surround+ treble swap in Traditional and Ultra Wide, which writes different left and right gains.
- **Tests:** the DSP plans, the FFT-bin response model, the gain staging and the parsers have unit tests. An offline model of the engine lives in `tools/dspsim`.

---

## Play Protect may block the install

When you install this APK yourself, Google Play Protect may block it with a warning like "Unsafe app blocked". **JadOO DSP isn't malware.** This is a known false positive.

The reason: to do its job the app needs an always-on foreground service, so the DSP isn't killed mid-song, and an optional system `DUMP` permission, used as a fallback to find out which app is playing. Play Protect's heuristics can flag that combination, even though the app has no microphone or recording access.

To install anyway:

1. When Play Protect blocks it, tap **More details**, then **Install anyway**.
2. Or, before installing, open the Play Store, tap your profile icon, then **Play Protect**, then the gear icon, and turn off **Scan apps with Play Protect** for a moment. Turn it back on afterwards.

Every permission and audio call the app makes is in this repository under [`app/src/main/java/com/jadoo/amp/`](app/src/main/java/com/jadoo/amp/). The [Permissions](#permissions) table above says what each one is for.

## Credits

HearPrint's taste profile ships a 50-second excerpt of ["Come Inside"](https://ccmixter.org/files/snowflake/59564) by snowflake, featuring starfrosch, jlbrock44 and mindmapthat, as its reference track. It is licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). The excerpt runs from 1:36 to 2:26, with 1.5-second fades added, encoded to Ogg Vorbis (`app/src/main/assets/reference/taste_reference.ogg`).

JadOO AI is optional and is not part of the APK. When the user installs it, it downloads Qwen3 0.6B (Qwen team, [Apache 2.0](https://huggingface.co/Qwen/Qwen3-0.6B/blob/main/LICENSE)) and Google's LiteRT-LM runtime (Apache 2.0).
