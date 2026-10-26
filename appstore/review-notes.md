# App Review notes

What goes into App Store Connect → your app → the version → **App Review Information** for Slimy 1.1.

| Field | Value |
|---|---|
| Sign-in required | **No** (there is no account, so no demo account) |
| Contact | Your name, phone and email for Apple's reviewers. They are only visible to Apple; they are not stored in this repo. |
| Notes | The block below, as plain text |
| Attachment | A screen recording of two iPhones pairing as friends and starting Care Together (see the checklist) |

## Before you press Submit

1. `https://slime.hyprnode.de/api/v1/health` answers `"ok": true` and `"chat": true` (at least one AI provider key is set, see `HANDOVER.md`), and the server is 1.1 (it answers `POST /care/codes`; the admin Overview shows the Caring together tile with a number, not a dash).
2. The website pages that App Review opens all load: `/`, `/privacy`, `/terms`, `/support`, and an invite page such as `/f/ABC234` (shows "expired" for an unknown code, which is correct).
3. Record a screen recording of 90 to 150 seconds with two iPhones side by side: Go Online on both, Get My Code, Connect with the code, accept, hug, send a gift; then Care Together on both, Get a Care Code, Join, feed the slime on one phone and show it arriving on the other, and Stop Sharing. Upload it as the **Attachment**. The notes below say it is attached.
4. Install the exact build you submit from TestFlight and walk through the notes once, including opening a crate and the Drop rates sheet. Every button name in quotes below must match what the build shows; fix the notes if a label changed.
5. Open the admin area (`/admin`, LAN only) and make sure the report queue is empty. During review, check it at least once a day.

## Notes for App Review

Paste everything inside the box. It is just under the 4,000 characters the field takes. `node appstore/check.mjs` counts it after every change (line breaks count twice, in case they are sent as CR LF).

```text
Slimy is a virtual pet: a pixel slime that lives in the Dynamic Island as a Live Activity. No account, no in-app purchase, no ads. The slime lives on the device and the app works offline. Online features (Friends, Care Together, Talk) are optional.

GETTING STARTED
1. Tap the egg, name the slime, pick a colour, tap "Hatch", then "Next" and "Give an Apple".
2. Tap "Bring It to the Island": the slime appears in the Dynamic Island.
3. Touch and hold the island: the five needs are buttons (Feed, Play, Sleep, Bath, Pet) that work without opening the app.
4. Optional: "Summon Slime", "Feed Slime", "Pet Slime" and "Slime Bedtime" for Control Center, the Lock Screen and the Action Button, plus Siri and Shortcuts.

LIVE ACTIVITY
- Started only by the user; can be hidden in Settings > Dynamic Island.
- Shows the pet's live state (mood, needs, a short line), animated with Text(timerInterval:) and a bundled font in which every digit is one frame. No push.
- iOS ends it after about 8 hours; Slimy renews it when the app opens or on "Summon Slime".

CRATES (NEW IN 1.1)
Wooden (80 coins) and golden (300 coins) crates are bought with coins earned in the game; mystery crates are earned and never sold. Coins can't be bought, so no real money is involved anywhere. "Drop rates" in the shop shows the chances. One legendary reward reads "???" on purpose: a secret to discover.

FRIENDS AND CARE TOGETHER (OPTIONAL, TWO DEVICES)
Nothing is sent to our server until the user taps "Go Online" and accepts the Terms.
1. Use two devices (two iPhones, or an iPhone and an iPad). On both, open Friends (two-person icon) and tap "Go Online".
2. Friends: device A "Get My Code" (6 characters, 10 minutes, single use), device B types it and taps "Connect", A accepts. Try Hug, Visit, Gift, Greet and the Leaderboard.
3. Care Together (in Friends or Settings): A "Get a Care Code", B enters it and taps "Join". Both now show A's slime; feeding it on one shows up on the other. "Stop Sharing" ends it and B gets its own slime back.
A recording of both flows on two iPhones is attached.

SAFETY (1.2)
- The only text users write for others is their name and their slime's name (max 24 characters), filtered on the server: profanity and slurs in English and German, links, emails, phone numbers, @handles.
- Friends can't send free text; greetings are fixed presets.
- People connect only through a short-lived code one of them shares. No directory, search or random matching.
- Report and Block on a friend or a request. We review reports daily and can rename or ban a device.
- Contact: https://slime.hyprnode.de/support

TALK (AI CHAT, OPTIONAL)
- Our server asks an AI model for a short in-character reply. Before the first message a consent screen names the AI services (Cloudflare Workers AI and Groq), says what is sent (the message, recent chat, facts about the slime including how it feels; never the user's name or a device ID) and links to the privacy policy. Consent can be withdrawn.
- The reply names the feeling it expresses, which changes the slime's mood on the device.
- Input and output are filtered. Touch and hold a reply > "Report Reply". Messages are not stored on our server.
- When the free AI capacity is used up, the slime is "sleepy from all the talking", as intended.

ACCOUNT DELETION
Going online creates an anonymous device record (no email, no password). Settings > Privacy > "Delete My Data on the Server" removes it and everything linked to it, including a Care Together pairing.

PERMISSIONS AND DATA
- Notifications are optional and local, a few a day at most, never at night.
- No location, contacts, camera, microphone, tracking or advertising identifier.
- Battery, charger, network and Low Power Mode are read on the device to animate the slime and never sent.

OTHER
Rated 13+ because of the AI chat. The app is in English. The slime talks on its own while the app is open (Settings > "Talks on its own").
```

## If App Review asks

**"Live Activities are meant for tasks with a start and an end."**
The slime's Live Activity is the app's core feature and shows live state that changes all the time (needs, sleep, visits from friends, reactions to the phone). The user starts it on purpose, can stop it in Settings, and iOS ends it after 8 hours like any other activity. It never shows advertising or promotional content.

**"Crates look like loot boxes."**
They are opened with coins, and coins are only earned in the game: Slimy has no in-app purchases, so nothing random is ever paid for with real money (Guideline 3.1.1 asks for odds on loot boxes that are bought; Slimy shows them anyway, in "Drop rates"). Duplicates turn into coins. The legendary row shows "???" instead of a percentage because what it is stays a surprise; the other rows show their exact chances, so the legendary's is what is left (0.6 % in golden crates, 2 % in mystery crates, never in wooden ones). If asked, give these numbers.

**"We could not test Friends or Care Together."**
Point to the attached recording and offer a call. If needed, explain that pairing works between an iPhone and an iPad, so a single reviewer with both devices can test it.

**"The chat did not answer."**
Check `/api/v1/health` (`chat: true`) and the admin dashboard's chat panel for provider failures, then reply with the time it was fixed. The app shows a friendly message instead of failing when no AI capacity is left.

**"Where can users report content or get help?"**
In the app: Report on a friend, a request, or a chat reply; Block on a friend or a request. On the web: https://slime.hyprnode.de/support. Reports land in the moderation queue of the admin area.

**"What data leaves the device?"**
See `appstore/privacy-labels.md`. In short: nothing until the user goes online; then a random device id, the names they chose, their slime's profile, friendships and game records, and while two people care for one slime together, a copy of that slime and their care actions (30 days). Chat text and how the slime feels pass through to the AI provider and are not stored.
