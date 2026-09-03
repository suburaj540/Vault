# Personal Document Vault

An offline Android app for the details you keep forgetting and keep looking up:
bank account numbers, IFSC codes, card expiry dates, policy numbers, Aadhaar,
PAN, passport and licence details, Wi-Fi passwords, and scans of the documents
themselves.

Everything stays on your phone. The app has **no internet permission**, so it
cannot send your data anywhere even if it wanted to. There is no account, no
server, no sync, and nobody to trust but yourself.

**[Download the latest APK](../../releases/latest)** - Android 10 or newer.

---

## Why this exists

Most password managers handle logins well and Indian paperwork badly. There is
nowhere sensible to put an IFSC code, a policy renewal date, a PUC expiry, or a
photo of your PAN card. So most of us keep them in Notes, or a WhatsApp message
to ourselves, or a photo buried in the gallery, all in plain readable text that
any app with storage access can read.

This is that pile, encrypted, on your own phone.

## What it does

- **Thirteen record types** shaped around real Indian documents: bank accounts
  with IFSC, MICR, CIF, UPI and net banking credentials; cards with CVV, ATM
  PIN, statement and due dates; ID documents covering Aadhaar, PAN, passport,
  driving licence, voter ID, ABHA, UAN and GSTIN; plus insurance, investments,
  vehicles, Wi-Fi, memberships, subscriptions, property and lockers, emergency
  and medical contacts, and free-form secure notes.
- **Expiry tracking.** Anything with a validity date lands in one list, so a
  lapsing PUC or a passport running out surfaces before it becomes a problem.
- **Document scans.** Attach photos or PDFs. Photos are shrunk automatically so
  the vault stays quick. They are encrypted along with everything else.
- **Fingerprint unlock**, backed by the phone's hardware keystore.
- **Search** that deliberately ignores secret fields, so nobody can fish for a
  CVV over your shoulder.
- **Password generator** for when you need a new one.
- **Encrypted backups** you can keep in Drive or on a pen drive safely.

## How the encryption works

Your master password is stretched with PBKDF2-SHA256 over 600,000 rounds into a
256-bit key, which encrypts the whole vault with AES-GCM. A fresh random IV is
used on every save, and AES-GCM detects tampering, so an edited or corrupted
vault file refuses to open rather than returning garbage.

The key is never written to disk. Your master password is never stored anywhere
in any form. **This means there is no recovery.** Forget it and the data is
gone, including for me. That is the trade-off for having no server.

Fingerprint unlock does not weaken this. It seals a copy of your master password
with a key inside the phone's hardware keystore that requires a verified
fingerprint to use, and that Android destroys automatically if the enrolled
fingerprints on the phone change.

## What it deliberately does not do

- **No sync.** Backups are a file you move yourself.
- **No autofill.** It will not type passwords into other apps.
- **No cloud, no account, no telemetry, no analytics, no ads.**
- **Not a replacement for Bitwarden or 1Password** for everyday logins. They do
  browser autofill and real sync, and they have been audited. Use both.

## Installing

1. Download the APK from the [latest release](../../releases/latest).
2. Optional but sensible: check the SHA-256 in the release notes matches your
   download.
3. Open the file. Android will warn you it is from an unknown source, and Play
   Protect will say it has not been scanned. Both are expected for an app that
   was never submitted to Google. Install anyway if you are comfortable.
4. On Samsung phones, **Auto Blocker** in Settings must be off during the
   install. Turn it back on afterwards.
5. Open the app, name your vault, and set a master password. Use a phrase of
   four or more unrelated words rather than something short and complicated.
   Length is what matters.

**Then, before you type in anything real: go to Settings, tap Back up now, and
check the file appears in `Downloads/Vault`.** Move it somewhere safe. If your
phone is lost or reset, that file is the only way back.

## Should you trust this?

Be sceptical. Installing an APK from a stranger to hold your Aadhaar number is
exactly the sort of thing you should hesitate over. What I can offer:

- The entire source is here. The app is one HTML file plus a small Android
  wrapper, small enough to actually read.
- No internet permission is declared, in `app/src/main/AndroidManifest.xml`. You
  can verify that in one glance.
- The APK is built by GitHub Actions from this repository, and the build log is
  public.
- It has not been independently audited, and I am not a security professional.

If that isn't enough for you, that is a completely reasonable conclusion.

## Built with AI assistance

I designed this, decided what it should do, tested it on my own phone and made
the calls on how it should behave. The code itself was written largely with
Claude, working through it together across several sessions, including some
genuinely nasty bug hunts. I am a PeopleSoft developer, not an Android
developer, and this would not exist without that help. Saying so seems more
useful than pretending otherwise.

## Building it yourself

Open the project in Android Studio and run it, or push a version tag to build a
signed release through GitHub Actions. `app/src/main/assets/vault.html` is the
whole application; the Kotlin around it only handles storage, the file picker,
biometrics, and blocking screenshots.

## Licence

MIT. Use it, fork it, change it. It comes with no warranty of any kind, which
for software holding your identity documents is worth reading literally.
