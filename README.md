<p align="center"><img src="Resources/Assets.xcassets/AppIcon.appiconset/icon.png" width="128" alt="Slimy app icon"></p>

<h1 align="center">Slimy</h1>

<p align="center"><b>A tiny slime that lives in your Dynamic Island.</b><br>
An iPhone app by hyprnode · <a href="https://slime.hyprnode.de">slime.hyprnode.de</a></p>

<p align="center">
<a href="https://slime.hyprnode.de/install">Install the beta</a> ·
<a href="https://slime.hyprnode.de/support">Support</a> ·
<a href="https://slime.hyprnode.de/privacy">Privacy</a>
</p>

---

Slimy is a pixel-art pet that sits next to your iPhone's camera all day. It wobbles, naps, gets hungry and reacts to what your phone is doing. Touch and hold the Dynamic Island to feed it, play with it, give it a bath or pet it without opening the app. Open the app for its home, twelve arcade games, a new game every day, a wardrobe, a journal, and, if you like, friends and a chat with your slime.

Slimy has no account, no ads, no tracking and nothing to buy. The slime lives on your iPhone and works offline. On the App Store it will be called **Slimy: Island Pet**; on your home screen it is just **Slimy**.

## Features in 1.0

**In the Dynamic Island and around iOS**
- The slime sits on the left of the island and moves; a small pixel emote on the right shows how it feels or what it needs.
- Touch and hold the island for its speech line and five need rings. Each ring is also a button that works without opening the app: Feed, Play, Sleep (Wake while the slime is asleep), Bath and Pet. On the dimmed Lock Screen only the rings remain.
- Friends' slimes join in: up to two stand beside yours in the expanded island and on the Lock Screen, and now and then one drops by for a meetup. Hugs, gifts, playing, falling in love and good night get their own shared moments.
- Lock Screen and StandBy show the Live Activity.
- Two widgets. **Slime:** Home Screen small, and medium with Feed and Pet buttons; Lock Screen circular, rectangular and inline. **Needs:** Home Screen small with all five needs, Lock Screen rectangular, and a circular gauge for the lowest need.
- Control Center, the Lock Screen and the Action Button: Summon Slime, Feed Slime, Pet Slime and Slime Bedtime. Siri and Shortcuts: Summon, Feed, Pet, Play, Bath, Bedtime.
- It reacts to your iPhone with no setup: battery low or almost empty, battery full, charger in or out, Low Power Mode, Wi-Fi, mobile data, no internet, back online, morning and night.
- No Dynamic Island? The slime lives on the Lock Screen and in widgets instead.

**The slime**
- Five needs: food, fun, energy, clean and love. They never drop below a resting floor, even after weeks away, and the slime never guilt-trips you.
- A personality (playful, cuddly, foodie, sleepy, curious) that drifts over time, plus a secret favourite food and favourite game to discover.
- Grows from baby to young to big, with its own sprites and 18 animations at every stage, eight jelly colours that unlock with levels, and a journal of memories and achievements.
- A bedtime you can set; changing the clock doesn't confuse it.
- A daily gift calendar: coins every day, a bigger gift on every seventh day (a piece of furniture the first three times), and a visit streak whose milestones go into the memory book. Missing a day never takes a gift away.
- The year shows in its home: pumpkins, bats and spooky lines in October, snow and a little tree with lights in December, flowers in spring, fireworks at New Year, plus a gift on Halloween, the holidays, New Year and every anniversary of the slime's hatch day.
- Back up your slime to a `.slimysave` file and restore it on any iPhone.

**Arcade**
- Twelve games, unlocked step by step as the slime levels up: Snack Catcher, Slime Jump, Bubble Pop, Memory Garden, Slime Racing, Rhythm Bounce, Garden Puzzle, Treasure Dig, Slime Basketball, Color Match, Slime Stack (drop jelly blocks into a tower) and Jelly Dash (an endless run with jumps).
- Today's game: a new game every day built from blocks (controls, theme, things to catch and dodge, power-ups), with a small quest.
- Every game has a tutorial, pauses when you leave the app and keeps your records. A round pays out exactly once.
- Daily and weekly challenges.

**Collecting**
- Coins are earned in the app (games, daily gifts, quests, achievements) and can't be bought. Hats, glasses, scarves, effects and furniture; five homes (room, garden, beach, space, crystal cave) with weather outdoors; a pumpkin hat that is only for sale in October.
- Photo booth: pose your slime and share the picture.

**Friends** (optional, online)
- Opt-in: nothing is registered until you tap **Go Online** (in Friends or Talk) and accept the Terms. The device token is kept in the iPhone's Keychain for this device only; it is never synced or restored to another phone. Going offline stops all syncing and keeps your friends on the phone.
- Invite with a code or a link (`slime.hyprnode.de/f/CODE`), accept requests, then hug, visit, send food and preset greetings, kiss from 50 % love, fall in love at 100 %. There are no demo friends: everyone in the list is someone you connected with.
- Challenges on the same board, a shared garden every week, and a friends leaderboard for every game. Your best scores are uploaded after a round while you're online.
- Privacy controls, block, report (a name, harassment, spam or something else), and deleting your server data from the app. Names are filtered on the server.
- Without a connection, actions wait and are sent exactly once later.

**Talk** (optional, online)
- Your slime answers briefly and in character. Before the first message the app asks for consent and explains what is sent and to whom (Cloudflare Workers AI and Groq). Replies can be reported. Nothing you write is stored on the server.

**Everywhere:** light and dark mode, Dynamic Type, VoiceOver labels, Reduce Motion, in English.

## Install the beta

Slimy is heading to the App Store. Until then you can sideload the beta:

1. Download the newest `Slimy.ipa` from [Releases](https://github.com/ItzSimooon/slimy-ios/releases). It carries the App Group that lets the app and its widgets share the save. If your sideloading tool rejects it, use `Slimy-unsigned.ipa`: everything works except the Home Screen and Lock Screen widgets.
2. Install it with [Sideloadly](https://sideloadly.io) (Windows or macOS) and your Apple ID.
3. On the iPhone, trust your Apple ID under Settings → General → VPN & Device Management, and turn on Developer Mode under Privacy & Security if asked.
4. Open Slimy, hatch the egg and tap **Bring It to the Island**.

With a free Apple ID the app has to be re-signed every 7 days. Step-by-step instructions: [slime.hyprnode.de/install](https://slime.hyprnode.de/install).

**Requirements:** iOS 18 or later. The island needs an iPhone with a Dynamic Island; every other iPhone on iOS 18 gets the Lock Screen and widgets.

### Keeping it in the island all day

iOS ends every Live Activity after about 8 hours. Opening Slimy brings the slime back (and renews an activity that is close to the limit), and **Summon Slime** (Control Center, Action Button, Siri) brings the slime back without opening it. iOS doesn't let a background run start a Live Activity, so background refresh only keeps the running one up to date; leave Background App Refresh on for Slimy for that.

## Privacy

- **Offline first.** No account and no sign-in. Until you tap **Go Online** in Friends or Talk and accept the Terms, Slimy sends nothing anywhere.
- **Online** means a random device id, the names you choose, your slime's profile, your friendships and your best scores are stored on `slime.hyprnode.de`, run by hyprnode in Germany. Delete it all in the app at any time; your slime on the phone stays.
- **Talk** sends your message, the recent chat and a few facts about your slime to an AI provider to write the reply. It is not stored on our server.
- **Never collected:** location, contacts, photos, advertising identifiers, or anything about your phone's battery, charger or network. Those are read on the device to animate the slime.
- No analytics SDKs, no ads, no tracking, and no cookies on the public website.

Full policy: [slime.hyprnode.de/privacy](https://slime.hyprnode.de/privacy).

## How the island animation works

A Live Activity can't run code, but iOS keeps `Text(timerInterval:)` ticking every second by itself. `tools/make_slime.py` builds fonts in which every digit is a whole frame of the slime and every glyph is exactly one frame wide. The island draws the timer right-aligned in a wide box and shows only the last character, the seconds, so the picture changes every second. Six timers stacked, one per colour layer, make the coloured slime. Shared moments end on their own through the activity's stale date, so a meetup never gets stuck when the app is suspended.

`tools/verify_fonts.py` checks with HarfBuzz that every glyph is one frame wide and that the last glyph of every time string is the right frame. CI runs it on every push. The animation can be turned off under Settings → Dynamic Island.

## Building

- **Without a Mac:** `.github/workflows/build.yml` builds and tests every branch and every pull request. Fonts are rebuilt and verified on Linux, Garden Puzzle levels are checked for solvability, the server is tested, and a cloud Mac runs the unit tests in the simulator and builds the IPAs. Only a push to `main` publishes them, as prerelease `build-<run number>`.
- **Screenshots:** every run takes every screen in light and dark and keeps them as the run's `screenshots` artifact; pushes also put them on the `ci-screenshots` branch. Screenshot mode exists only in Debug builds and is driven by launch arguments: `-SlimyScreenshot`, plus `-SlimySheet <name>` (kitchen, shop, journal, friends, settings, chat, gift) or `-SlimyCover <name>` (arcade, bath). Details in `HANDOVER.md`.
- **With a Mac:** `brew install xcodegen && xcodegen generate`, open `Slimy.xcodeproj`, choose your team under Signing & Capabilities for both targets, run. The project is generated from `project.yml`; there is no `.xcodeproj` in the repo.
- **Pixel art:** edit `tools/make_slime.py`, then `pip install fonttools uharfbuzz pillow`, run `python3 tools/make_slime.py` and `python3 tools/verify_fonts.py`, and commit the generated files.
- **Server:** `cd server && npm test` (Node 22.13 or later; there are no dependencies, so nothing to install). See [`server/README.md`](server/README.md).
- **Website:** `node site/build.mjs && node site/tools/dev-server.mjs`, then open http://localhost:4310. The preview server uses the same routing and security headers as production, answers sample invites and releases, and loads the admin mock automatically. `site/dist/` is build output and not committed. See [`HANDOVER.md`](HANDOVER.md).

## Project layout

```
App/              the iOS app (SwiftUI, iOS 18)
  Core/           GameStore (state, saves), IslandController (Live Activity, meetups),
                  PhoneMonitor, Notifier, DailyStore, Feedback, ScreenshotMode
  Arcade/         game host, Engines (pure game logic, tested), Games (views)
  Online/         HyprnodeAPI, FriendsStore (offline queue), ChatStore
  UI/             Kit (shared controls and Theme), World (home, habitat), Screens
Shared/           code and assets shared by app and widgets: Model, Art, Activity, Sprites, Fonts
Widget/           Live Activity, Home and Lock Screen widgets, Control Center, animation fonts
Tests/            XCTest: needs, time, levels, rewards, challenges, games, daily plan, API
Resources/        app icon, colours, sounds
server/           Node API for slime.hyprnode.de, with tests (server/README.md)
site/             the website and the admin area (static, built by site/build.mjs)
appstore/         App Store listing in English and German, review notes, privacy and age rating
tools/            pixel art and font builders and checks, CI helpers
DESIGN.md         design language for the app, the island and the website
HANDOVER.md       how everything runs today and what is still open
```

## Credits

Made by hyprnode. Pixel font: [Pixelify Sans](https://github.com/eifetx/Pixelify-Sans), SIL Open Font License, license in `Shared/Fonts/PixelifySans-OFL.txt`.
