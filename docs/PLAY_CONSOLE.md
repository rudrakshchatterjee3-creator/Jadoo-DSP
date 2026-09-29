# Google Play Console setup

Answers for each Play Console form, matching what the `play` flavor actually does. Update this file if the app's permissions or network use change.

## 1. Build the upload

```bash
./gradlew :app:bundlePlayRelease
```

Output: `app/build/outputs/bundle/playRelease/*.aab`, signed with `jadoo.jks`.

`versionCode` is shared with the GitHub builds, so it must always be higher than any previous upload to either channel.

## 2. Play App Signing: use the existing key

Do this **before** uploading the first AAB. If Google generates its own key instead, Play installs and GitHub APKs can never update each other.

1. Create app → Test and release → Setup → **App signing**.
2. Choose **Use existing app signing key from Java KeyStore**.
3. Download `pepk.jar` and the encryption public key from that page.
4. Run:
   ```bash
   java -jar pepk.jar --keystore=jadoo.jks --alias=jadoo --output=jadoo-signing-key.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   ```
5. Upload `jadoo-signing-key.zip`, then delete the zip.
6. Check that the app signing certificate's SHA-256 shown in the Console is `DD:B8:50:8B:58:37:5F:45:53:AE:59:3B:A7:59:A9:ED:D9:60:19:8C:C0:29:9A:BC:0D:68:60:22:54:0C:36:E7`.

Because the AAB is also signed with `jadoo.jks`, that key becomes the upload key too. You can register a separate upload key later under App signing → Request upload key reset.

## 3. Store listing

The text and graphics are in `fastlane/metadata/android/en-US/`:

| Console field | File |
|---|---|
| App name | `title.txt` |
| Short description | `short_description.txt` |
| Full description | `full_description.txt` |
| App icon (512×512) | `images/icon.png` |
| Feature graphic (1024×500) | `images/featureGraphic.png` |
| Phone screenshots (1080×1920) | `images/phoneScreenshots/1–6_en-US.png` |

The screenshots are generated from raw device captures in `fastlane/screenshots-raw/` by `python fastlane/make_screenshots.py`, which crops, frames and captions them. To swap one, replace its raw capture and edit its caption in `SHOTS`. The phone's native 1260×2800 is too tall for Play, whose longest side may be at most twice the shortest, which is why the raw captures can't be uploaded directly.

- **Category:** Music & Audio.
- **Contact email:** required, and shown publicly.

## 4. App content

**Privacy policy URL:** https://github.com/rudrakshchatterjee3-creator/Jadoo-DSP/blob/master/PRIVACY.md. It only works once `PRIVACY.md` is pushed to `master`.

**Ads:** No.

**App access:** All functionality is available without special access.

**Content rating:** Category "Utility, Productivity, Communication, or Other". Answer No to every content question, which gives a rating of Everyone / PEGI 3.

**Target audience:** 18 and over. Choosing ages under 13 triggers the Families policy, which isn't worth taking on for an audio tool.

**News app / Government app / Financial features / Health:** No.

**Data safety:**
- Does your app collect or share any of the required user data types? **No.** The Play build has no INTERNET permission, so nothing leaves the device through the app.
- Crash reports and backups leave the device only when the user shares them through the Android share sheet. That counts as user-initiated sharing, not collection.

**Foreground service permissions:** type `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- **Task description:** "JadOO DSP applies real-time equalizer and audio effects to music playing in the user's chosen player app. The foreground service keeps the audio-effect engine attached to playback while the user listens with the screen off or in another app. An ongoing notification shows the engine's status (active, waiting for playback, or off), and tapping it opens the app."
- **Reviewer risk:** the service currently stays in the foreground even while the engine shows "Waiting for playback" or "Engine off". A reviewer may object that `mediaPlayback` should only run during playback. If the declaration is rejected, stop the foreground service while nothing is playing.
- **User impact if deferred or interrupted:** "The effects would stop mid-song and the music would suddenly change sound."
- **Video:** a short screen recording showing music playing, JadOO's notification, the screen locking, and the effect still audible or toggleable from the notification. Upload it to YouTube as unlisted and paste the link.

## 5. Testing before production

New personal developer accounts must run a **closed test with at least 12 testers opted in for 14 consecutive days** before production access is granted.

1. Testing → Closed testing → create a track.
2. Upload the AAB.
3. Add the testers' Google-account emails.
4. Share the opt-in link with them.
5. After 14 days, apply for production.
