# JadOO DSP Privacy Policy

_Last updated: 24 November 2026_

JadOO DSP is an audio equalizer and effects app for Android, developed by Rudraksh Chatterjee. This policy explains what the app does with information on your device.

## Summary

JadOO DSP does not collect, sell or share personal data. It has no accounts, no ads, no analytics and no tracking. All audio processing happens on your device, and the app never records or transmits audio.

## Information the app uses on your device

Everything below stays on your device unless you choose to share it yourself.

- **Your settings.** Equalizer curves, presets, effect settings and per-app / per-device profiles are stored in the app's private storage.
- **Which app is playing audio.** To apply per-app profiles and attach its effects to the right audio stream, the app reads the package name of the app currently playing audio (for example, your music player). This is used only on the device and is not stored anywhere except alongside the profile you created for that app.
- **Connected audio device names.** The name of your connected headphones or speaker is used to keep separate settings for each device and, in the GitHub version, to suggest searching the AutoEQ database for that model. It is not sent anywhere — the search runs on your device.
- **HearPrint and taste results.** The quietest level you heard at each test tone, the date of the test, and the tastes you saved (how much bass, warmth, clarity and air you chose, and the names you gave them) are stored in the app's private storage, per output device. They are a sound setting, not medical data, and they are never sent anywhere. Deleting a HearPrint or a taste in the app removes it. They are included in a settings backup only when you make one yourself.
- **What you type in the taste questions.** If you describe the sound you want and JadOO AI is installed, the text is read by the model on your phone. It is not stored after the taste test finishes and is never sent anywhere. Without JadOO AI the text isn't read at all.
- **Language choice.** The app language you pick is stored on your device.
- **Crash reports.** If the app crashes, a report containing the app version, device model, Android version and a technical stack trace is saved in the app's private storage. It is only sent anywhere if you tap "Share crash report" and pick where to send it yourself.
- **Settings backups.** "Backup & Restore" writes your settings to a file you choose, and reads files you choose to import. The app does not upload these files.
- **Optional DUMP permission.** If you grant this permission yourself via ADB, the app reads system audio-session information on the device to find the active audio stream. That information is not stored or transmitted.

## Network access

The app (downloaded from the project's GitHub Releases page) connects to GitHub to check for new app versions (`api.github.com`), download updates you choose to install, download updated tuning presets (`raw.githubusercontent.com`), and — only when you open Headphone correction — download the public AutoEQ headphone list and the profile you pick (`raw.githubusercontent.com/jaakkopasanen/AutoEq`). Searching happens on your device; your search text is never sent. These requests contain no personal information, but as with any web request GitHub receives your IP address and device's network details; GitHub's handling of that is covered by the [GitHub Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

**JadOO AI (optional).** Only if you tap Install on the JadOO AI screen, the app downloads two files once: Google's LiteRT-LM runtime from `dl.google.com` and the Qwen3 0.6B model from `huggingface.co`. Both are checked against fixed SHA-256 hashes before use and stored in the app's private storage until you remove them. These downloads contain no personal information; as with any web request, Google and Hugging Face receive your IP address and network details, under the [Google Privacy Policy](https://policies.google.com/privacy) and the [Hugging Face Privacy Policy](https://huggingface.co/privacy). After the download the model runs entirely on your phone; nothing you type is sent to Google, Hugging Face or anyone else.

## Permissions

- **Modify audio settings** — to apply equalizer and effects to audio playback.
- **Notifications and foreground service** — to keep the audio engine running while music plays and show that it is active.
- **Ignore battery optimizations** (GitHub version) — to request that the system doesn't stop the audio engine in the background.
- **Install packages** (GitHub version only) — to install app updates you choose to download.

## Children

JadOO DSP is not directed at children and does not knowingly collect any information from anyone, including children.

## Changes to this policy

Changes will be published on this page with a new "Last updated" date.

## Contact

Questions about this policy can be raised on the project's issue tracker: https://github.com/rudrakshchatterjee3-creator/Jadoo-DSP/issues
