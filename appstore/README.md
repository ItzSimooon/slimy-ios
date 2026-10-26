# App Store listing

Everything App Store Connect asks for Slimy 1.1, ready to paste. The folder layout follows fastlane `deliver` (`<locale>/<field>.txt`), so it can also be uploaded with `fastlane deliver --metadata_path appstore` later.

| File | What |
|---|---|
| `en-US/`, `de-DE/` | Name, subtitle, promotional text, description, keywords, release notes and the three URLs per language |
| `review-notes.md` | App Review Information: notes to paste, attachment, answers to likely questions |
| `screenshots.md` | Shot list with captions (EN/DE) and the 6.9" and 6.5" sizes |
| `privacy-labels.md` | App Privacy answers and server retention |
| `age-rating.md` | Age rating answers (target 13+) |
| `check.mjs` | Checks lengths, keywords, banned words and the length of the review notes: `node appstore/check.mjs` |

Run the check after every text change. It fails on anything App Store Connect would reject and on our own rules (no other brands' trademarks, no emoji, no stock phrases).

## App information

| Field | Value |
|---|---|
| Name | Slimy: Island Pet |
| Subtitle | Pixel slime for Dynamic Island (de-DE: Pixel-Slime für Dynamic Island) |
| Bundle ID | `de.hyprnode.slimy` (widgets: `de.hyprnode.slimy.widgets`, App Group `group.de.hyprnode.slimy`) |
| SKU | `slimy-ios` |
| Primary language | English (U.S.) |
| Primary category | Games, subcategories Simulation and Casual |
| Secondary category | Entertainment |
| Price | Free, no in-app purchases |
| Age rating | 13+ (see `age-rating.md`) |
| Copyright | `2026 hyprnode` |
| Support URL | https://slime.hyprnode.de/support |
| Marketing URL | https://slime.hyprnode.de |
| Privacy Policy URL | https://slime.hyprnode.de/privacy |
| Content rights | Does not contain, show or access third-party content |
| Encryption | Uses only standard HTTPS; `ITSAppUsesNonExemptEncryption` is already `NO` in `project.yml`, so no export compliance questions per build |
| Availability | All territories except China mainland, where games and apps with generative AI need a government approval number; the app is English, the German store text says so |

The home screen name stays **Slimy** (`CFBundleDisplayName`). "Dynamic Island" is kept out of the app name and only used descriptively in the subtitle and text.

## Things App Store Connect handles differently than you'd expect

- **What's New** is not shown for an app's first version. If 1.1 is the first version on the store (1.0 was only a sideloaded beta), paste `release_notes.txt` as the TestFlight "What to Test" text and keep it as the starting point for 1.1.1.
- **Promotional text** can be changed at any time without a new review. Seasonal versions are in the marketing plan.
- **Keywords** are a single comma-separated field of 100 characters. Words already in the name or subtitle are indexed anyway, so they are not repeated. Never add other apps' or companies' names (Guideline 2.3.7).
- **EU trader status** (Digital Services Act) must be declared before the app is available in the EU. A trader's address, phone and email are shown on the product page in the EU.
- **Seller name** on the product page is the Apple Developer account holder: your legal name for an individual membership, the company for an organization membership (needs a D-U-N-S number).

## Writing about 1.1

- **The legendary stays a secret.** Store texts, screenshots, previews and review notes say that golden crates hide a secret legendary and never say what it is. The review notes explain the "???" row in Drop rates.
- **No love between slimes.** 1.1 removed the love meter, kisses and falling in love. Friends hug, visit, send gifts and greetings; the love need (petting) stays.
- **Crates never cost money.** Always say they are bought with coins earned in the game or earned outright.

## Order of work for the first submission

1. Enroll in the Apple Developer Program (99 USD per year).
2. Register the bundle IDs and the App Group in Certificates, Identifiers & Profiles; turn on App Groups for both IDs.
3. Create the app record with the values above.
4. Fill in App Privacy (`privacy-labels.md`) and Age Rating (`age-rating.md`).
5. Paste name, subtitle, promotional text, description and keywords for en-US, then add the German localization and paste de-DE.
6. Upload screenshots (`screenshots.md`).
7. Upload a build (Xcode Organizer or `xcodebuild -exportArchive` with your team), test it in TestFlight on a real iPhone.
8. Fill in App Review Information (`review-notes.md`), attach the recording, choose "Manually release this version", submit.
