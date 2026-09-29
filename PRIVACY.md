# JadOO DSP Privacy Policy

_Last updated: 29 September 2026_

JadOO DSP is an audio equalizer and effects app for Android, developed by Rudraksh Chatterjee. This policy explains what the app does with information on your device.

## Summary

JadOO DSP does not collect, sell or share personal data. It has no accounts, no ads, no analytics and no tracking. All audio processing happens on your device, and the app never records or transmits audio.

## Information the app uses on your device

Everything below stays on your device unless you choose to share it yourself.

- **Your settings.** Equalizer curves, presets, effect settings and per-app / per-device profiles are stored in the app's private storage.
- **Which app is playing audio.** To apply per-app profiles and attach its effects to the right audio stream, the app reads the package name of the app currently playing audio (for example, your music player). This is used only on the device and is not stored anywhere except alongside the profile you created for that app.
- **Connected audio device names.** The name of your connected headphones or speaker is used to keep separate settings for each device and, in the GitHub version, to suggest matching headphone tuning. It is not sent anywhere.
- **Crash reports.** If the app crashes, a report containing the app version, device model, Android version and a technical stack trace is saved in the app's private storage. It is only sent anywhere if you tap "Share crash report" and pick where to send it yourself.
- **Settings backups.** "Backup & Restore" writes your settings to a file you choose, and reads files you choose to import. The app does not upload these files.
- **Optional DUMP permission.** If you grant this permission yourself via ADB, the app reads system audio-session information on the device to find the active audio stream. That information is not stored or transmitted.

## Network access

**Google Play version:** the app does not request internet access and makes no network connections.

**GitHub version** (downloaded from the project's GitHub Releases page): the app connects to GitHub to check for new app versions (`api.github.com`), download updates you choose to install, and download updated tuning presets (`raw.githubusercontent.com`). These requests contain no personal information, but as with any web request GitHub receives your IP address and device's network details; GitHub's handling of that is covered by the [GitHub Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

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
