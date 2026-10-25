# Slimy

your slimy best buddy · by **hyprnode** · [slime.hyprnode.de](https://slime.hyprnode.de)

Slimy is a pet slime that lives in your iPhone's Dynamic Island. Feed it, play with it, give it bubble baths, and team up with a friend so your slimes can fall in love.

<p align="center"><img src="Resources/Assets.xcassets/AppIcon.appiconset/icon.png" width="160" alt="Slimy app icon"></p>

## What it does

**In the Dynamic Island**
- The slime is animated right in the island: it breathes, blinks, bounces and sleeps. iOS does the animation, so the app doesn't need to run (see [How the animation works](#how-the-animation-works)).
- The right side of the compact island shows what it wants (a ring for food, fun, energy, cleanliness or love), the latest phone event, or your partner's slime when the two are in love.
- **Hold the island** for the expanded view: speech bubble, needs bars, and **Feed / Play / Bath / Pet** buttons that work without opening the app.
- A Lock Screen card with a sky that follows the time of day. It also shows in StandBy and on the Apple Watch Smart Stack.
- Control Center buttons: **Summon Slime** and **Feed Slime**. Either can go on the Action Button too.

**In the app**
- Five needs that drain over time: Food, Fun, Energy, Clean and Love. The slime asks for whatever it wants most, and looks hungry or sad when you neglect it.
- **Feed** it five foods (one is its secret favorite). **Play Snack Catch** to earn coins. **Scrub the mud off** in the bath. **Rub it** to pet it. **Tap it** to poke it.
- Levels unlock new jelly colors, up to Hypr Void.
- It sleeps at night and wakes up by itself once rested.
- Local notifications when it's about to need something.

**Phone events.** The slime reacts to what your phone does:

| Event | Reaction |
|---|---|
| Battery low or almost empty | melts into a puddle 🫠 |
| Charger connected, battery full | gets zapped with energy ⚡️ |
| Wi-Fi connected | happy bounce |
| Wi-Fi lost, mobile data, offline, Airplane Mode | confused ❓ |
| Morning, alarm, every hour | waves 👋 |
| Good night | love |

While the app is open, Slimy detects these on its own. While it's closed, Shortcuts automations send them through the **Slime Reacts** action (set up under *Settings → React while the app is closed*). Important events pop the island open with a sound.

**Friends and love** (needs the server, see [HANDOVER.md](HANDOVER.md))
- Pair with a friend using a 6-character code.
- Hug, visit, send gifts and (from 50% love) kiss. At 100% the slimes are **in love**: they show up together in your island and your friend's slime visits your room.
- Until the server is live, a clearly labeled **demo friend** (Mochi) lets you try the whole flow on your own phone.

**Chat** (needs the server). Talk to your slime. It knows its mood, needs, age, favorite food and partner. Until the server is live it only answers "blub".

## Install on your iPhone (Windows, free Apple ID)

You don't need a Mac or a paid developer account.

1. Open the [Releases](https://github.com/ItzSimooon/slimy-ios/releases) page and download `Slimy.ipa` from the newest build.
2. Install [Sideloadly](https://sideloadly.io) on your PC, along with iTunes and iCloud from Apple's website (not the Microsoft Store versions).
3. Connect your iPhone with a cable and open Sideloadly. Drag `Slimy.ipa` in, enter your Apple ID and click **Start**.
4. On the iPhone, open **Settings → General → VPN & Device Management**, tap your Apple ID and choose **Trust**.
5. If iOS asks, turn on **Developer Mode** under **Settings → Privacy & Security** and restart.
6. Open Slimy, hatch your slime and tap **Put in island**.

A free Apple ID signs apps for 7 days. Re-sign before then (Sideloadly can do it automatically over Wi-Fi), or the app stops opening. Your slime is kept as long as you don't delete the app.

## Keep it in the island all day

iOS ends every Live Activity after about 8 hours. Push notifications could restart it remotely, but they need a paid developer account. Slimy restarts it in these ways instead:

- **Whenever you open the app.**
- **Summon Slime** in Control Center, on the Lock Screen or on the Action Button.
- **Shortcuts automations**, which run without asking: create *Time of Day* automations (for example 08:00, 15:00 and 22:00) that run **Summon Slime**.
- **Background refresh**, about every 30 minutes when iOS allows it. It keeps the needs and the island up to date.

## How the animation works

A Live Activity can't run code, but iOS keeps `Text(timerInterval:)` ticking every second by itself. `tools/make_slime.py` draws every frame as pixel art and builds fonts in which every digit **is** a whole animation frame and every glyph is exactly one frame wide. The island draws the timer right-aligned and clipped to a single glyph, so only the seconds digit shows, and the slime changes frame every second in a ten-step loop. Six timers, one per color layer (body, shade, outline, shine, warm and cool effects), are stacked and tinted with the slime's skin.

Each animation (idle, sad, sleep, eat, play, love, bath, melt, zap, confused, wave) has its own set of fonts. After an action, the island plays the reaction for 8 seconds, then the app sends one more update to switch back to the mood animation.

An earlier version picked frames with font ligatures. iOS doesn't apply ligatures to timer text in the island, so the slime was invisible there; the current fonts don't use any.

`tools/verify_fonts.py` uses HarfBuzz to check that every glyph is one frame wide, nothing gets substituted, and the last glyph of every timer string is the right frame. CI runs it on every push. In the app, **Settings → Diagnose** shows a live preview drawn exactly like the island.

## Project layout

```
App/            the iOS app (SwiftUI)
  Core/         SlimeStore (state), IslandController (Live Activity), PhoneMonitor, Notifier
  Online/       HyprnodeAPI client, FriendsStore, ChatStore
  UI/           screens: Home, Hatch, Feed, Bath, Snack Catch, Wardrobe, Friends, Chat, Settings
Shared/         used by app and widget: model + game rules, brand, skins, intents, sprites
Widget/         Live Activity, Control Center controls, generated animation fonts
Resources/      asset catalog and app icon
tools/          make_slime.py (art, fonts), verify_fonts.py, make_icon.py
HANDOVER.md     spec for the slime.hyprnode.de server and website
```

## Building

- **Cloud (no Mac):** every push to `main` runs `.github/workflows/build.yml`. It checks the fonts on Linux, builds an unsigned IPA on a macOS runner, and publishes it as a release.
- **On a Mac:** `brew install xcodegen && xcodegen generate`, open `Slimy.xcodeproj`, pick your team under *Signing & Capabilities* for both targets, and run.
- **Changing the art:** edit `tools/make_slime.py`, then run `pip install fonttools uharfbuzz pillow`, `python3 tools/make_slime.py` and `python3 tools/verify_fonts.py`. Commit the regenerated `Widget/Fonts`, `Widget/Info.plist` and `Shared/SlimeSprites.swift`.

Requires iOS 18 and an iPhone with a Dynamic Island (iPhone 14 Pro or later). On other iPhones it still works as a Lock Screen card.

## Not tested on a device yet

These depend on iOS behavior that only a real iPhone can confirm:

- The font animation trick in the island. It's verified with HarfBuzz on Linux, not yet with iOS's own text engine.
- Whether Summon Slime and Slime Reacts can start or update the activity from Control Center and Shortcuts automations while the app is closed.
- The Apple Watch Smart Stack view.
