# App Privacy answers

What to enter in App Store Connect → your app → **App Privacy** for Slimy 1.1, and why. 1.1 adds no new data type: Care Together falls under Gameplay Content, and the slime's feelings sent with a chat under Other User Content. The answers describe the whole app, so they include what happens after someone goes online, even though nothing is sent before that.

The rules behind these answers: Apple counts data as "collected" when it leaves the device and is kept longer than it takes to answer the request. We answer conservatively, so a reviewer or a user never finds data we didn't mention.

## Summary

| Question | Answer |
|---|---|
| Do you or your third-party partners collect data from this app? | **Yes** |
| Is any data used to track users? | **No** (no advertising, no data brokers, no SDKs, no IDFA, no App Tracking Transparency prompt) |
| Privacy Policy URL | `https://slime.hyprnode.de/privacy` |

## Data types

Select exactly these five. Everything else stays unselected.

| Data type (category) | What it is in Slimy | Purposes | Linked to the user | Tracking |
|---|---|---|---|---|
| **Name** (Contact Info) | The owner name a person types when going online, shown to their friends next to their slime. It can be a nickname. | App Functionality | Yes | No |
| **User ID** (Identifiers) | The random device id the server assigns on "Go Online" (plus a token of which only a hash is stored). It is not derived from any hardware or Apple identifier. | App Functionality | Yes | No |
| **Gameplay Content** (User Content) | The slime profile friends see (slime name, colour, level, stage, mood, happiness, worn items), friendships, hugs, visits, gifts, preset greetings, challenges and their scores, shared-garden points, best scores per game for the friends leaderboard. With Care Together: the pairing, a copy of the shared slime (name, colour, level, needs, bond, personality, outfit, feelings, asleep or awake, favourite food and game, hatch date) and the care actions both people take (feed, play, bath, pet, sleep, wake, gift). | App Functionality | Yes | No |
| **Other User Content** (User Content) | Reports a person sends (reason, optional note, and for a reported chat reply the reply's text), kept for moderation. Chat messages pass through our server to the AI provider to produce the reply and are not stored by us. | App Functionality | Yes | No |
| **Product Interaction** (Usage Data) | When a device last used an online feature (updated at most every 10 minutes), used to count active devices in the admin dashboard and to keep the service healthy. | Analytics, App Functionality | Yes | No |

Notes on single choices:

- **Name vs. User ID.** The owner name is free text the person picks, so it is declared as Name even though most people use a nickname.
- **Chat.** The message, the recent chat and a few facts about the slime (its name, age, level, mood, needs, favourite food, local time, and how it feels right now in plain words such as "very happy, a little sleepy") are sent to Cloudflare Workers AI or Groq to write a reply. The reply names the feeling it expresses, which goes back to the app. Our server keeps no message content, no feelings and no logs of either; it only counts requests per provider and day. Because the providers process the text on our behalf, it is covered under Other User Content.
- **Care Together.** Only the two people in a pair can read its data. Each sees the other's owner name next to the care actions. When either stops sharing or deletes their server data, the snapshot and all care actions are deleted at once. Coins, items, records and settings are not part of the pair and are never shared with the partner.
- **Crates** are opened on the device. Nothing about them is sent.
- **Reports** stay after the reporting person deletes their server data, but the link to their device is removed at that moment.
- **Not collected:** location (IP addresses are held in memory for rate limiting for at most 24 hours and never written to disk), contacts, photos, health, financial info, purchases, browsing or search history, diagnostics (no crash or analytics SDK), sensitive info, device identifiers, and anything about phone events: battery, charger, network and Low Power Mode are read on the device to animate the slime and never sent.
- **Before going online** the app sends nothing at all. The slime, its feelings, coins, crates, items, records and chat history live only on the iPhone (and in the App Group shared with the widgets).

## Retention on the server

| Data | Kept |
|---|---|
| Device, owner name, slime profile, friendships, records | Until the person deletes their server data in the app, or we delete a device in the admin area |
| Inbox events (hugs, gifts, greetings …) | 30 days |
| Challenges | 14 days after they expire (a challenge is open for 3 days) |
| Used or expired pair codes and care codes | 1 day |
| Care Together: the pairing and the shared snapshot | Until either person stops sharing or deletes their data; then deleted at once |
| Care actions | 30 days, and deleted at once when the pairing ends |
| Idempotency receipts (retry protection) | 2 days |
| Reports | Open reports until an admin resolves them, resolved reports 90 days after that; the device ids are removed when a reporting or reported device is deleted |
| Chat messages | Not stored |
| Chat usage counters (requests and failures per provider and day, no content) | 400 days |
| Rate-limit counters (device ids, IP addresses) | At most 24 hours, in memory only |
| Database backups | 14 days, on the same server |

## Keep in sync

The same facts appear in three more places. Change all of them together:

1. `App/PrivacyInfo.xcprivacy` (`NSPrivacyCollectedDataTypes`): `NSPrivacyCollectedDataTypeName`, `NSPrivacyCollectedDataTypeUserID`, `NSPrivacyCollectedDataTypeGameplayContent`, `NSPrivacyCollectedDataTypeOtherUserContent` (linked, App Functionality) and `NSPrivacyCollectedDataTypeProductInteraction` (linked, Analytics and App Functionality); `NSPrivacyTracking` false, no tracking domains.
2. The privacy policy at `https://slime.hyprnode.de/privacy` (`site/privacy.html`).
3. The in-app texts: "Go Online" (Terms), the chat consent sheet (`App/UI/Screens/ChatConsentView.swift`) and the Privacy footer in Settings (`App/UI/Screens/SettingsView.swift`), which both name Cloudflare Workers AI and Groq.

If a new AI provider is added through `CHAT_BASE_URL` and it is run by a third party, add it to the chat consent text and the privacy policy before turning it on. A model on your own hardware is not a third party.
