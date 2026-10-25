# Slimy API (slime.hyprnode.de)

The backend for the Slimy iOS app: friends, love between slimes, gifts, visits, greetings, async challenges, the co-op garden, and the chat proxy to Claude. Everything about the slime itself (needs, coins, levels, items) stays on the phone.

- Node.js 22.13 or newer, built-in `node:sqlite` and `node:test`.
- One dependency: the official Anthropic SDK (`@anthropic-ai/sdk`), only for the chat.
- One SQLite file. Migrations run on start (`src/db.js`, table `schema_migrations`).

**Status:** the code and its tests are in this folder. It has **not** been deployed anywhere yet. Until it runs at `https://slime.hyprnode.de/api/v1`, the app shows "noch nicht online" for friends and chat, and offers a local demo friend.

## Run

```sh
cd server
npm ci
npm test                      # 34 tests against the real HTTP handler, in-memory DB
PORT=8080 SLIMY_DB=data/slimy.db ANTHROPIC_API_KEY=... npm start
```

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port. Put HTTPS in front (Caddy, Traefik, nginx). iOS refuses plain HTTP. |
| `SLIMY_DB` | `data/slimy.db` | SQLite file. Back it up; WAL mode is on. |
| `ANTHROPIC_API_KEY` | unset | Enables `/chat`. Without it `/chat` answers 503 with a German note. **Only here, never in the app.** |
| `CHAT_MODEL` | `claude-opus-5-5` | Model for the chat. Set e.g. `claude-haiku-5-5` for a much cheaper chat. |
| `TRUST_PROXY` | unset | `1` to take the client address from `X-Forwarded-For` (rate limits). Only behind your own proxy. |

The API lives under `/api/v1`. Serve the website from the same domain by routing everything else to it in the reverse proxy.

Example Caddyfile:

```
slime.hyprnode.de {
  handle /api/* { reverse_proxy localhost:8080 }
  handle { root * /srv/slimy-site; file_server }
}
```

Health check: `GET /api/v1/health` → `{"ok": true, "version": "1", "schema": 1, "chat": true}`.

## Conventions

- JSON in and out. Bodies over 32 KB → 413.
- Dates: ISO 8601 UTC **without milliseconds** (`2026-10-25T12:00:00Z`). Swift's `.iso8601` rejects fractions.
- Every error is `{"error": "<short German sentence>"}`. The app shows it as is. A 404 without `error` makes the app think the server is not live, so the server always sends one.
- Auth: `Authorization: Bearer <token>` on everything except `POST /devices` and `GET /health`. Tokens are 32 random bytes; only their SHA-256 is stored.
- **Idempotency:** any write may carry `Idempotency-Key: <≤64 chars>`. A repeat with the same key returns the first answer (status and body) and does nothing else. The app's offline queue relies on this. Keys are kept for two days. Reusing a key for another path → 422.
- Rate limits (per device unless noted) answer 429 with a German message: registration 10/h per address, pair codes 20/h, wrong codes 8/h per device and 30/h per address, joins 30/h, interactions 120/h, greetings 30/day (6 per friend), challenges 20/day, chat 8/min and 60/day, everything 1200/h.

## Endpoints

| Method and path | Body | Answer |
|---|---|---|
| `GET /health` | | `{ok, version, schema, chat}` |
| `POST /devices` | `{ownerName?}` | `{deviceId, token}` |
| `GET /me` | | `{deviceId, ownerName, privacy: {acceptRequests, shareMood}, friends}` |
| `DELETE /me` | | 204. Deletes the device, its slime, friendships, requests, events sent and received, challenges, co-op points, memories. |
| `PUT /me/slime` | `SlimeProfile` | 204 |
| `PUT /me/privacy` | `{acceptRequests?, shareMood?}` | `{acceptRequests, shareMood}` |
| `POST /pair-codes` | `{}` | `{code, expiresAt}`; 6 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, 10 minutes, single use. 409 if requests are switched off. |
| `GET /invites/:code` (no auth) | | `{ownerName, slime: {name, skin, stage}, expiresAt}` for the website's invite page; does not use the code up. 404 when unknown, used or expired; limited per address. |
| `POST /friend-requests` | `{code}` | `{status: "pending", request}` or `{status: "friends", friend}` (already friends, or both asked each other). 404 unknown/used, 410 expired, 400 own code, 409 at 20 friends. |
| `GET /friend-requests` | | `{incoming: [Request], outgoing: [Request]}` |
| `POST /friend-requests/:id/accept` | `{}` | `{friend}` |
| `POST /friend-requests/:id/decline` | `{}` | 204 (also withdraws your own) |
| `GET /friends` | | `[Friend]`, in love first |
| `GET /friends/:id` | | `{friend, memories: [{kind, detail, at}], coop: Coop, today: {hug, visit, gift, kiss}}` (interactions that still count today) |
| `DELETE /friends/:id` | | 204 |
| `POST /friends/:id/interactions` | `{kind, food?}` | `{love, inLove, gained, fellInLove, capped, message}` |
| `POST /friends/:id/messages` | `{preset}` | `{sent: true}` |
| `POST /friends/:id/coop` | `{points}` | `Coop` plus `added` |
| `GET /blocks` · `POST /blocks {deviceId}` · `DELETE /blocks/:deviceId` | | list / 204 / 204 |
| `GET /inbox?after=<cursor>` | | `{events: [Event], cursor}` (50 at a time, kept 30 days) |
| `POST /challenges` | `{friendId, game, seed, score}` | `Challenge` (seed is a decimal string; max 3 open per friend; 3 days to answer) |
| `GET /challenges` | | `[Challenge]` from the last 14 days |
| `POST /challenges/:id/answer` | `{score}` | `Challenge`; only the challenged friend, only once |
| `POST /chat` | `{message, history: [{role: user|slime, text}], slime: ChatContext}` | `{reply, reaction}` |

### Shapes

```jsonc
// SlimeProfile (what friends see of your slime)
{ "ownerName": "Simon", "name": "Blob", "skin": "lime", "level": 5, "stage": "young",
  "mood": "idle", "happiness": 78, "equipped": {"hat": "hat.party"}, "updatedAt": "..." }
// happiness is null and mood "idle" when the owner hides their mood.

// Friend
{ "id": "dev_…", "ownerName": "Simon", "slime": SlimeProfile, "love": 42, "inLove": false, "since": "..." }

// Request
{ "id": "req_…", "deviceId": "dev_…", "ownerName": "Anna", "slime": SlimeProfile, "createdAt": "..." }

// Event (inbox)
{ "id": "17", "friendId": "dev_…", "friendName": "Mochi", "kind": "gift", "food": "cake",
  "preset": null, "challengeId": null, "requestId": null, "createdAt": "..." }
// kinds: request, accepted, hug, visit, gift, kiss, inLove, message, challenge, challengeResult, coopDone

// Coop
{ "week": "2026-W43", "goal": 1000, "mine": 300, "theirs": 200, "total": 500, "done": false }

// Challenge
{ "id": "ch_…", "game": "bubblePop", "seed": "12345", "friendId": "dev_…", "friendName": "Mochi",
  "sentByMe": true, "myScore": 300, "theirScore": null, "createdAt": "...", "expiresAt": "...", "answeredAt": null }
```

### Enums

| Field | Values |
|---|---|
| skin | lime, bubblegum, ocean, grape, lava, gold, ghost, void |
| mood | idle, sad, sleep |
| stage | baby, young, grown |
| food | apple, carrot, berry, mushroom, cookie, soup, honey, pudding, cake, starJelly |
| interaction kind | hug, visit, gift, kiss |
| message preset | hello, missYou, goodNight, goodMorning, letsPlay, thanks, congrats, proud |
| game | snackCatcher, slimeJump, bubblePop, memoryGarden, slimeRacing, rhythmBounce, gardenPuzzle, treasureDig, basketball, colorMatch |
| reaction | none, eat, play, love, bath, melt, zap, confused, wave, celebrate, stretch, sulk, hide, curious, blush |

## Rules the server enforces

- **Love** per friendship, 0 to 100, shared by both. Per sender, friend and UTC day: hug +3 (3×), visit +5 (1×), gift +6 (3×), kiss +8 (2×, only from 50 %). Past the daily count a hug, visit or kiss still answers 200 with `gained: 0, capped: true` and a German `message`; a gift answers 429 so no coins are spent. At 100 the pair is in love for good; both get an `inLove` event and a memory.
- **Requests:** a code makes a request; the code's owner accepts or declines. If both asked each other, they become friends at once. Max 20 friends.
- **Blocking** ends the friendship, removes requests and the blocked device's events from your inbox, closes open challenges, and makes your future codes look unknown to them (404, same message as a typo).
- **Greetings** are presets only, so nobody can send free text through Slimy.
- **Events never control the other phone.** The app shows a toast, an island moment, gives the food of a gift, or lists a challenge. Nothing else.
- **Challenges** carry the game's seed; both play the same board. The answer is final.
- **Co-op garden:** points (the app sends the round's quality, 0–150) add up per friendship and ISO week, at most 300 per call and 900 per device per day. Reaching 1000 tells both once.
- **Chat:** the app sends what the slime knows (name, age, level, mood, needs, favourite food, partner, local time). The server builds a German in-character system prompt, asks Claude for `{reply, reaction}` as structured JSON at low effort with server-side refusal fallbacks, and turns a refusal into a gentle line. The API key never leaves the server.

## Privacy

Stored per device: a random id, the token hash, the owner name the user typed, the slime profile above, friendships, love, memories, requests, blocks, events for 30 days, challenges for 14 days, co-op points, idempotency receipts for 2 days. No contacts, location, device identifiers or phone events. Chat messages are not stored on the server; they are sent to Anthropic to produce the reply. `DELETE /me` removes everything for that device.

## Tests

`npm test` starts the HTTP listener on a random port with an in-memory database, a controllable clock and a fake Anthropic client. It covers registration and auth, validation, date format, token hashing, rate limits, codes (single use, expiry, own code, brute force), requests, privacy, love caps and falling in love, gifts, idempotent retries, inbox cursors and cleanup, blocking, challenges, the co-op garden, chat (including refusals) and account deletion.
