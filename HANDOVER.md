# Handover: deploy the server, build the website

For whoever takes over **slime.hyprnode.de**. The iOS app and the API server are both in this repo. What's left for you:

1. **Deploy** the server in `server/` behind HTTPS at `https://slime.hyprnode.de/api/v1`.
2. **Build the website** at `https://slime.hyprnode.de/` (English, same look as the app).

Read `server/README.md` first: it is the API contract, the configuration and the rules. Don't change the contract without changing `App/Online/HyprnodeAPI.swift` in the same pull request.

---

## 1. What Slimy is

- An iPhone app: a pixel-art pet slime that lives in the Dynamic Island (a Live Activity). You feed it, play ten arcade games with it, bathe it, pet it, dress it up and decorate its home. It reacts to phone events (battery, charger, Wi-Fi and so on), detected by the app itself whenever iOS lets it run. Friends' slimes sometimes meet it in the island. It grows from baby to grown-up and remembers things.
- Made by **hyprnode**. Home: **https://slime.hyprnode.de**.
- Installed by sideloading an IPA from GitHub Releases (`https://github.com/ItzSimooon/slimy-ios/releases`) with Sideloadly and a free Apple ID. No App Store yet.
- The slime itself (needs, coins, levels, items) lives **on the phone**. The server handles friends, love, gifts, visits, greetings, challenges, the co-op garden and the chat.

## 2. Deploy the server

```sh
cd server && npm ci && npm test && PORT=8080 SLIMY_DB=/var/lib/slimy/slimy.db ANTHROPIC_API_KEY=... npm start
```

- Node 22.13+; one SQLite file; migrations run on start. One dependency (`@anthropic-ai/sdk`).
- Put Caddy (or Traefik/nginx) in front for HTTPS; route `/api/*` to the server and everything else to the website (example Caddyfile in `server/README.md`). Set `TRUST_PROXY=1` behind your proxy so rate limits see real addresses.
- `ANTHROPIC_API_KEY` only as a server environment variable. Never in the app, the website or the repo. `CHAT_MODEL` picks the model (default `claude-opus-5-5`; `claude-haiku-5-5` is far cheaper per message, your call).
- Back up the SQLite file (WAL mode; copy with `sqlite3 slimy.db ".backup x.db"`).
- Monitor `GET /api/v1/health`.
- A small Dockerfile is enough if you want one: `node:22-slim`, copy `server/`, `npm ci --omit=dev`, `CMD ["node","--no-warnings","src/index.js"]`, volume for the database.

After deploying, check from a shell (needs `jq`):

```sh
B=https://slime.hyprnode.de/api/v1
curl -s $B/health                                     # {"ok":true,...}
TA=$(curl -s -XPOST $B/devices -H 'content-type: application/json' -d '{"ownerName":"Ana"}' | jq -r .token)
TB=$(curl -s -XPOST $B/devices -H 'content-type: application/json' -d '{"ownerName":"Ben"}' | jq -r .token)
CODE=$(curl -s -XPOST $B/pair-codes -H "authorization: Bearer $TA" -H 'content-type: application/json' -d '{}' | jq -r .code)
curl -s $B/invites/$CODE                              # Ana's invite, code still unused
REQ=$(curl -s -XPOST $B/friend-requests -H "authorization: Bearer $TB" -H 'content-type: application/json' -d "{\"code\":\"$CODE\"}" | jq -r .request.id)
curl -s -XPOST $B/friend-requests/$REQ/accept -H "authorization: Bearer $TA" -H 'content-type: application/json' -d '{}'
curl -s $B/inbox -H "authorization: Bearer $TB"       # an "accepted" event
```

Then open Slimy on two iPhones: Freunde → Code holen / Verbinden. Both should see each other, hugs should raise the shared love, and a gift should appear on the other phone.

## 3. Website (`/`)

All pages English, responsive, fast, no tracking, no cookies. Pages:

1. **Landing** `/`: a big animated pixel slime as the hero (draw frames from `Shared/Sprites/sprites.json` on a `<canvas>`, see section 5), one plain line about what Slimy is, what it does (Dynamic Island pet, needs, ten games, phone reactions, friends who can fall in love, talking to your slime), and a **Download** button to the newest GitHub release.
2. **Install** `/install`: the Windows + Sideloadly steps from `README.md`, the 7-day re-signing note for free Apple IDs, and "keeping it in the island all day" (open the app, "Summon Slime" in Control Center or on the Action Button, Background App Refresh on). Numbered steps. Mention both IPAs (`Slimy.ipa` with App Group for widgets, `Slimy-unsigned.ipa` as fallback).
3. **Invite** `/f/{code}`: call `GET /api/v1/invites/{code}`. Show "Anna wants to be slime friends with you", her slime drawn from the sprites (skin and stage are in the answer), the code big and copyable, "Open Slimy → Friends and enter the code", plus install links. On 404: "This code has expired. Ask for a new one." The app's share text already contains the code; a later app update can link here.
4. **Privacy** `/privacy`: what's stored and for how long (the Privacy section of `server/README.md`), that chat messages go to Anthropic to produce a reply and are not stored on the server, how to delete everything (in the app: Friends → Privacy and blocked → Delete server data), contact.
5. **Support** `/support`: FAQ. The island disappeared → open the app or use "Summon Slime" (iOS ends Live Activities after about 8 hours). The app won't open → re-sign in Sideloadly. Widgets stay empty → the App Group was not kept by the sideloading tool.

## 4. Brand

The app looks like a small cozy handheld game: cream "paper" windows with a dark ink border and notched pixel corners, a hard shadow under keys, plum paper at night. The Dynamic Island keeps a dark moss palette. No gradients, glows, blur, rounded app cards or emoji.

App UI (`App/UI/Kit/Theme.swift`):

| Token | Light | Dark | Use |
|---|---|---|---|
| paper | `#FFF7E8` | `#2B2336` | windows |
| paperShade | `#F1E2C4` | `#221C2B` | inputs, quiet keys |
| ink | `#3A2B2E` | `#F5ECDC` | text |
| inkSoft | `#7C6758` | `#B8A898` | secondary text |
| border | `#3A2B2E` | `#0D0A12` | 3px borders, shadows |
| backdrop | `#CFE6D2` | `#16121D` | page background |
| accent | `#4FB84E` | | primary keys |
| gold | `#E8A92A` | | coins |
| love | `#E0577F` | | hearts, love |
| sky | `#4FA3D9` | | info |
| danger | `#D9483B` | | errors |

Island and widgets (`Shared/Art/Brand.swift`): bg `#101411`, panel `#171D19`, raised `#212A24`, line `#2E3A32`, text `#E9F1E4`, muted `#8D9D91`, slime `#7DE67A`, hypr `#9B7BFF` (wordmark only), warm `#F07A9A`, cool `#7FD6F0`, gold `#FFD166`.

Shapes: notched corners (cut one 3px step), 3px borders, keys with a hard 3px shadow straight down that they sink into when pressed.

Type: **Pixelify Sans** (OFL, in `Shared/Fonts` with its license) for headings, keys and numbers; a rounded system sans for running text. Wordmark: `slimy` in Pixelify Sans Bold with `by hyprnode` in violet.

Voice: English. The slime speaks in short lowercase fragments ("hungry. very hungry.", "squeaky clean"). The UI around it is plain English. Never guilt-trip ("you forgot me" is wrong; "there you are" is right).

## 5. Sprites

`Shared/Sprites/sprites.json` (generated by `tools/make_slime.py`):

- `frames["<stage>.<anim>"]`: list of frames, each 20 rows of 24 characters. Stages: `baby`, `young`, `grown`. Animations: idle, sad, sleep, eat, play, love, bath, melt, zap, confused, wave, celebrate, stretch, sulk, hide, curious, blush. Characters: `b` body, `s` shade, `k` ink, `w` shine, `r` warm, `c` cool, `.` empty.
- Skin colours per layer are in `Shared/Art/Skin.swift` (`color(for:)`).
- `items[name]`: `{palette, rows}` for food, coins, hearts, furniture and accessories.
- Play at about 2.5 frames per second, scaled by whole pixels with smoothing off (`image-rendering: pixelated`).

App icon: `Resources/Assets.xcassets/AppIcon.appiconset/icon.png`.

## 6. Where the app touches the server

| App file | What it does |
|---|---|
| `App/Online/HyprnodeAPI.swift` | every request and response type, error mapping, idempotency keys |
| `App/Online/FriendsStore.swift` | registration, profile upload, friends, requests, blocks, privacy, inbox handling, interactions, greetings, challenges, co-op, offline queue, demo friend |
| `App/Online/ChatStore.swift` | chat and the offline "blub" fallback |
| `App/Arcade/ArcadeView.swift` | challenge rounds (same seed) and co-op points after a round |
