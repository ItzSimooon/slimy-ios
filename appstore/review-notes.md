# App Review notes

What goes into App Store Connect → your app → the version → **App Review Information** for Slimy 1.0.

| Field | Value |
|---|---|
| Sign-in required | **No** (there is no account, so no demo account) |
| Contact | Your name, phone and email for Apple's reviewers. They are only visible to Apple; they are not stored in this repo. |
| Notes | The block below, as plain text |
| Attachment | A screen recording of two iPhones pairing (see the checklist) |

## Before you press Submit

1. `https://slime.hyprnode.de/api/v1/health` answers `"ok": true` and `"chat": true` (at least one AI provider key is set, see `HANDOVER.md`).
2. The website pages that App Review opens all load: `/`, `/privacy`, `/terms`, `/support`, and an invite page such as `/f/ABC234` (shows "expired" for an unknown code, which is correct).
3. Record a screen recording of 60 to 120 seconds with two iPhones side by side: Go Online on both, Get My Code, Connect with the code, accept, hug, send a gift, and the friend's slime appearing in the expanded Dynamic Island. Upload it as the **Attachment**. The notes below say it is attached.
4. Install the exact build you submit from TestFlight and walk through the notes once. Every button name in quotes below must match what the build shows; fix the notes if a label changed.
5. Open the admin area (`/admin`, LAN only) and make sure the report queue is empty. During review, check it at least once a day.

## Notes for App Review

Paste everything inside the box. It is about 3,950 characters; the field takes 4,000. `node appstore/check.mjs` counts it after every change (line breaks count twice, in case they are sent as CR LF).

```text
Slimy is a virtual pet: a pixel slime that lives in the Dynamic Island as a Live Activity. No account, no in-app purchase, no ads. The slime lives on the device; the app works offline. Online features (Friends and Talk) are optional.

GETTING STARTED
1. Tap the egg, name the slime, pick a colour, tap "Hatch", then "Next" and "Give an Apple".
2. Tap "Bring It to the Island": the slime appears in the Dynamic Island.
3. Touch and hold the island: the five needs are buttons (Feed, Play, Sleep, Bath, Pet) that work without opening the app.
4. Optional: controls "Summon Slime", "Feed Slime", "Pet Slime" and "Slime Bedtime" for Control Center, the Lock Screen and the Action Button; Siri and Shortcuts offer the same actions plus Play and Bath.

LIVE ACTIVITY
- Started only by the user (introduction, Settings > Dynamic Island, "Summon Slime") and can be hidden there ("Hide"; "Always bring it back" turns off renewing).
- Shows the pet's live state: mood, needs, a short line. It animates with Text(timerInterval:) and a bundled font in which every digit is one frame. No push notifications are used.
- iOS ends Live Activities after about 8 hours. While the user keeps the slime in the island, Slimy renews it when the app opens or on "Summon Slime". Background refresh only updates the running activity.
- iPhones without a Dynamic Island show it on the Lock Screen. iPad has no Live Activities, so there the slime stays in the app.

FRIENDS (OPTIONAL, TWO DEVICES)
Nothing is sent to our server until the user taps "Go Online" (in Friends or Talk) and accepts the Terms.
1. Use two devices: two iPhones, or an iPhone and an iPad (the iPad runs the iPhone app).
2. On both, open Friends (two-person icon) and tap "Go Online".
3. Device A: "Get My Code" (6 characters, 10 minutes, single use). Device B: type it in and tap "Connect". Device A: accept.
4. Try Hug, Visit, Gift and Greet, the friend's page (memories, shared garden), and Leaderboard in Friends after an arcade round. The friend's slime also appears in the expanded island.
A recording of this flow on two iPhones is attached.

SAFETY (1.2)
- The only text users write for others is their own name and their slime's name (at most 24 characters), filtered on the server: profanity and slurs in English and German, links, emails, phone numbers, @handles.
- Friends cannot send free text to each other; greetings are fixed presets.
- People connect only through a short-lived code one of them shares. No directory, search or random matching.
- Report and Block: on a friend (swipe, long press or the friend's page) or on a request. We review reports daily and can rename or ban a device.
- Contact: https://slime.hyprnode.de/support

TALK (AI CHAT, OPTIONAL)
- Messages go to our server, which asks an AI model for a short in-character reply. Before the first message a consent screen names the AI services (Cloudflare Workers AI and Groq), says what is sent (the message, recent chat, facts about the slime; never the user's name or a device ID) and links to the privacy policy. Consent can be withdrawn in the app.
- Input and output are filtered. Touch and hold a reply > "Report Reply".
- Chat messages are not stored on our server.
- If the day's free AI capacity is used up, the slime says it is "sleepy from all the talking", as intended.

ACCOUNT DELETION
Going online creates an anonymous device record (no email, no password). Settings > Privacy > "Delete Server Data" (also in Friends > Privacy & blocked) removes it and everything linked to it.

PERMISSIONS AND DATA
- Notifications are optional and local: a few per day at most, never at night.
- No location, contacts, camera, microphone, tracking or advertising identifier.
- Battery, charger, network and Low Power Mode states are read on the device to animate the slime and never sent.

OTHER
Coins are earned in the app; nothing is sold for real money. Rated 13+ because of the AI chat. The app is in English.
```

## If App Review asks

**"Live Activities are meant for tasks with a start and an end."**
The slime's Live Activity is the app's core feature and shows live state that changes all the time (needs, sleep, visits from friends, reactions to the phone). The user starts it on purpose, can stop it in Settings, and iOS ends it after 8 hours like any other activity. It never shows advertising or promotional content.

**"We could not test Friends."**
Point to the attached recording and offer a call. If needed, explain that pairing works between an iPhone and an iPad, so a single reviewer with both devices can test it.

**"The chat did not answer."**
Check `/api/v1/health` (`chat: true`) and the admin dashboard's chat panel for provider failures, then reply with the time it was fixed. The app shows a friendly message instead of failing when no AI capacity is left.

**"Where can users report content or get help?"**
In the app: Report on a friend, a request, or a chat reply; Block on a friend or a request. On the web: https://slime.hyprnode.de/support. Reports land in the moderation queue of the admin area.

**"What data leaves the device?"**
See `appstore/privacy-labels.md`. In short: nothing until the user goes online; then a random device id, the names they chose, their slime's profile, friendships and game records. Chat text passes through to the AI provider and is not stored.
