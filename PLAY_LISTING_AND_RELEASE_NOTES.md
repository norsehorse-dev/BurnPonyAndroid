# BurnPony — Play Store listing copy & release notes

Covers tester-feedback items **2 (screenshots)** and **6 (ASO)**, which are store-console
work rather than code, plus the "What's new" text for both releases.

Nothing here has been submitted anywhere — paste it into Play Console when you're ready.

---

## 1. Release notes ("What's new")

### Update 1 — 1.1.0 (versionCode 3)

```
An interactive walkthrough for new users.

• Five-step tour on first launch, with a skip button on every screen
• The tour ends by sending a real burn note — encrypted on your device,
  real link, one view, five minutes — so you have used BurnPony before
  you have finished setting it up
• Replay the walkthrough any time from Settings
```

*(Play caps this at 500 characters — the above is ~330.)*

### Update 2 — 1.2.0 (versionCode 4)

```
Pick your language, and read the legal documents without leaving the app.

• New: a language picker, nine languages, on the first walkthrough screen and in Settings
• The walkthrough is now translated in all of them
• Privacy Policy and Terms of Use are bundled and readable offline, linked from Settings
• The F-Droid build states its own facts: no push messaging, no Google libraries
```

*(386 characters, under Play's 500 cap.)*

**Also worth a store-listing pass with this release:** the app now ships nine
languages with a first-run picker. Play localizes the listing separately from
the app, so the short and full descriptions are still English-only. Translating
at least the short description for de, es, fr, it, ja, pt-BR, ru and zh-CN is the
cheapest remaining ASO win, and the store will now match what the app does.

---

## 2. Short description (80 char max)

Pick one. All three are within the limit and lead with the highest-volume search term.

| # | Text | Chars |
|---|------|-------|
| **A** *(recommended)* | `Self-destructing encrypted notes. Burn after reading. No account, no tracking.` | 77 |
| B | `Send a private note that burns after reading. Encrypted on your phone.` | 69 |
| C | `Encrypted burn-after-reading notes. One-time links, no signup, open source.` | 74 |

**A** carries the most distinct keyword stems — *self-destructing*, *encrypted*, *notes*,
*burn after reading*, *account*, *tracking* — without reading like a keyword list.

---

## 3. Full description (4000 char max)

Play indexes the full description, so the terms people actually type need to appear in
running prose. The draft below places each of them at least twice and lands at ~2,700
characters, leaving room to grow.

```
Send a private message that deletes itself after it has been read.

BurnPony turns a note into a one-time link. You choose how many views it
allows and when it expires; when either runs out, the note is deleted from
the relay and nobody — including us — can bring it back.

WHY NOT JUST TEXT IT
A password, an address, a recovery code or a difficult piece of news sent
over chat sits in that conversation forever, on their phone, in their cloud
backup, and in whatever app they screenshot it into. A self-destructing note
gives it an expiry date.

ENCRYPTED ON YOUR PHONE
Your note is encrypted with AES-256-GCM before anything is uploaded. The
decryption key travels only in the part of the link after the "#", which
browsers never send to any server. The relay holds ciphertext it cannot read.
This is end-to-end encryption in the plain sense: the server is not trusted,
because it is never given anything to be trusted with.

YOU SET THE RULES
• Views allowed — from a single one-time view up to 100
• Expiry — five minutes to thirty days
• Auto-hide — the text disappears again seconds after it is revealed
• Passphrase — an optional second factor, stretched with PBKDF2 at 600,000
  iterations, that you share through a different channel than the link
• Read receipts — know the moment a note is opened, with the recipient told
  up front. No silent read tracking.

NO ACCOUNT, NO TRACKING
There is no sign-up, no email address, no phone number and no contact list
access. No analytics SDK, no advertising SDK, no profile. The Sent tab lives
on your device. There is nothing to delete because there was never anything
to collect.

WORKS WITH ANYONE
Your recipient needs no app and no account. The link opens in any browser on
any device. Hand it over in person with the built-in QR code and the note
transfers without touching the network at all.

WATCH IT BURN
The Sent tab shows which notes are still alive, how many views are left and
when each one expires — and lets you burn any note early, before it has been
read at all.

HONEST LIMITS
We would rather tell you this than have you find out: whoever has the full
link, and the passphrase if you set one, can read the note. A reader can
always copy or photograph what is on screen. Self-destruction limits future
access; it does not control the moment of reading.

OPEN SOURCE AND SELF-HOSTABLE
The app and the relay are both open source and auditable. Point the app at
your own server in Settings and BurnPony never talks to ours.

GOOD FOR
Sharing passwords and API keys • sending a Wi-Fi password to a guest •
recovery codes and 2FA backup codes • addresses and personal details •
anything you would not want sitting in a chat history forever

BurnPony is part of the pony family of privacy tools: encryption you can
inspect instead of taking a developer's word for it.
```

**Keyword stems covered:** self-destructing note, private message, one-time link,
burn after reading, encrypted note, end-to-end encryption, AES-256, passphrase,
read receipt, no account, no tracking, QR code, share password securely, temporary
note, disappearing message, open source, self-hosted.

**Deliberately absent:** "military-grade", "unhackable", "100% secure", "anonymous".
Overclaiming is both an ASO risk and off-brand for an app with an *Honest limits*
section.

---

## 4. Screenshot specs

The current set shows bare screens with no explanation, which is the exact note the
testers raised. Play shows 2–3 screenshots above the fold on phone — those three do
nearly all the conversion work.

**Format:** 1080 × 1920 (9:16), PNG. Ember gradient background (#F67529 → #EB2F33)
or the near-black #050408, device frame inset, caption in the top ~22% at ~64px
semibold, screen below.

Caption rule: state the **benefit**, not the screen name. "New Note" is a label;
"Nobody can read it twice" is a reason to install.

| # | Screen to capture | Caption (headline / subhead) | Annotation |
|---|---|---|---|
| 1 | Compose screen with a short note typed and the Views / Expiry chips visible | **Notes that burn after reading** / *One view. Five minutes. Then it is gone.* | Circle the two chips, arrow to the caption |
| 2 | Options sheet, self-destruction section open | **You set the rules** / *1–100 views, 5 minutes to 30 days, auto-hide on reveal* | Callout each of the three controls |
| 3 | Result screen with link card + QR | **A link anyone can open. Once.** / *No app, no account for your recipient* | Highlight the `#` in the link, small label "the key lives here, and never reaches the server" |
| 4 | Options sheet, passphrase toggle on | **Add a passphrase they receive elsewhere** / *PBKDF2, 600,000 iterations* | Two-channel diagram: link one way, passphrase the other |
| 5 | Sent Notes with a mix of active / burned rows | **Watch it, or burn it early** / *See views remaining, kill a note before it is read* | Point at a green Active row and the Burn action |
| 6 | Onboarding slide 3, send CTA visible | **Send your first note during setup** / *The walkthrough sends a real one* | — (new in 1.1.0; add with that release) |
| 7 | Settings, How BurnPony works + Honest limits | **We tell you the limits too** / *Open source, self-hostable, no analytics* | Underline the Honest limits paragraph |

**Feature graphic (1024 × 500):** ember gradient, the pony mark left, "Burn after
reading." set large, and a small note card visibly dissolving into embers at the right
edge. No screenshot content — it renders too small to read.

---

## 5. Console checklist for these two releases

- [ ] 1.1.0 — upload AAB (versionCode 3), paste Update 1 release notes
- [ ] 1.1.0 — add screenshot 6, refresh 1–3 with captions
- [ ] 1.1.0 — promote through closed testing, wait out the required window
- [ ] Run `bash apply_update2_legal.sh` in the Android repo **after** 1.1.0 has shipped
- [ ] 1.2.0 — upload AAB (versionCode 4), paste Update 2 release notes
- [ ] Data safety form — re-check it still matches the bundled Privacy Policy wording
- [ ] Store listing — swap in the new short + full description (do this once, with 1.1.0)
- [ ] Privacy policy URL — confirm it still points at `https://burnpony.app/privacy.php`

Hold the F-Droid update until the merge request is fully approved, as planned. The
`foss` flavor is untouched by update 1, and update 2 gives it its own accurate
privacy text — so neither release forces an F-Droid change before you are ready.
