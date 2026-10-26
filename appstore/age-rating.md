# Age rating

Target: **13+**, because Slimy has an AI chat that writes free text. Everything else in the app (a cute pet, arcade games without violence, no purchases, no ads) would rate lower.

Answer every question in App Store Connect → your app → **Age Rating** honestly (Guideline 2.3.6). The wording in App Store Connect may differ slightly from the list below; the answer to each topic is what matters.

## Answers

| Topic | Answer | Why |
|---|---|---|
| Parental controls | No | The app has no parental control features. |
| Age assurance | No | No age check in the app. |
| Unrestricted web access | No | Links only open our own website (privacy, terms, support) in Safari. There is no in-app browser. |
| User-generated content | **Yes** | Owner names and slime names are visible to friends. They are filtered on the server, and users can report and block. |
| Messaging and chat | **Yes** | The Talk screen is a free-text conversation with an AI character. People cannot send free text to each other (greetings between friends are fixed presets). |
| Advertising | No | No ads of any kind. |
| Profanity or crude humour | None | The slime's own lines are clean; chat input and output are filtered. |
| Horror or fear themes | None | |
| Alcohol, tobacco or drug use or references | None | |
| Medical or treatment information | None | The chat is told not to give health advice and points people to someone they trust. |
| Health or wellness topics | None | Needs like "energy" and "clean" belong to the pet, not to the user. |
| Mature or suggestive themes | None | Slimes can "fall in love" and give a "kiss": a heart animation between two blobs, nothing more. |
| Sexual content or nudity | None | |
| Cartoon or fantasy violence | None | No fighting; obstacles in games are rocks and puddles. |
| Realistic violence | None | |
| Guns or other weapons | None | |
| Simulated gambling | None | Treasure Dig is a deduction game; the daily gift is free and cannot be bought. |
| Gambling (real money) | No | |
| Contests | No | |
| Loot boxes (paid random items) | No | Nothing can be bought with real money. |
| Made for Kids | No | Slimy is not in the Kids category and must not use "for kids" wording in metadata (Guideline 2.3.8). |

## Getting to 13+

- If App Store Connect calculates a lower rating from these answers, use its option to set a **higher age rating** and choose 13+.
- If it calculates a higher rating than 13+, do not change honest answers to get there. Read which answer raised it and decide whether the feature is described correctly.
- Regional ratings (for example in Germany, Brazil or Korea) are derived by Apple from the same answers.

## Why 13+ fits

- The AI chat is the only part of the app that produces open text. Even with filters on both sides, a model can say unexpected things, so the app should not be presented to younger children.
- The Terms for going online (`https://slime.hyprnode.de/terms`) should name the same minimum age, 13. It is also the age at which most of the platforms used for marketing (TikTok, Instagram, Discord) allow accounts.
- Store screenshots, the preview video and the icon must still be suitable for everyone (Guideline 2.3.8): no chat replies with edgy content in marketing material.
