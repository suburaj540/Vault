# Vault - Android

An offline, encrypted personal record store. The whole app is one HTML page
(`app/src/main/assets/vault.html`) running inside a WebView shell.

## Why the shell exists

- **No INTERNET permission is declared.** The app cannot transmit data even if
  it wanted to. Check `AndroidManifest.xml` yourself.
- **FLAG_SECURE** blocks screenshots, screen recording and the recents thumbnail.
- **Fingerprint unlock** wraps your master password with a hardware keystore key
  that requires a verified fingerprint and self-destructs if the phone's
  enrolled biometrics change.
- Leaving the app locks the vault immediately.
- Backups and attachments are written to `Downloads/Vault/`.

## Build it without installing anything

1. Create a **private** repository on GitHub and push this folder to it.
2. Open the **Actions** tab. The `Build APK` workflow runs on push, or press
   *Run workflow*.
3. When it finishes, download the `vault-apk` artifact, unzip it, move
   `app-debug.apk` to your phone and open it. Allow installs from unknown
   sources when prompted.

## Build it locally instead

Open the folder in Android Studio (Koala or newer) and press Run. Or from a
terminal with the Android SDK installed: `./gradlew assembleDebug`.

## Updating the app later

Edit `app/src/main/assets/vault.html`, bump `versionCode` and `versionName` in
`app/build.gradle.kts`, push, install the new APK over the old one. Your data
survives because it lives in the app's private storage, not in the APK.

## Before you rely on it

Make a backup from Settings on day one. A debug-signed APK is fine for personal
use, but if you ever uninstall the app or reset the phone, the vault goes with
it. The backup file is encrypted, so keeping it in Drive is safe.
