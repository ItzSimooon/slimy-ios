# Slimy API (slime.hyprnode.de)

The backend for the Slimy iOS app: friends, gifts, visits, greetings, async challenges, the co-op garden, the friends leaderboard, caring for one slime together, reports, and the chat proxy to hosted AI models. Everything about the slime itself (needs, coins, levels, items, emotions) stays on the phone; when two people care for one slime, the server passes their care actions and the shared snapshot between the two phones. A small admin API behind the home network handles reports and bans.

Love between slimes (love meter, kisses, "in love") is gone from the 1.1 app. The server keeps it working unchanged for older app builds.

- Node.js 22.13 or newer. Built-in `node:sqlite`, `node:test` and `fetch`; **no npm dependencies**.
- One SQLite file. Migrations run on start (`src/db.js`, table `schema_migrations`).
- Deployment, the environment file, the Caddyfile and the admin passphrase: see [DEPLOY.md](DEPLOY.md).

## Run

```sh
cd server
npm test                      # node:test against the real HTTP handler, in-memory DB, fake AI providers
PORT=8080 SLIMY_DB=data/slimy.db GROQ_API_KEY=... npm start
node src/admin-cli.js passwd  # set the admin passphrase (asks twice, hidden)
node src/admin-cli.js status  # admin set up? which chat providers are configured?
```

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port. Put HTTPS in front (Caddy, Cloudflare). iOS refuses plain HTTP. |
| `HOST` | `127.0.0.1` | Address to listen on. The reverse proxy runs on the same machine; use `0.0.0.0` only inside a container. |
| `SLIMY_DB` | `data/slimy.db` | SQLite file. Back it up; WAL mode is on. |
| `TRUST_PROXY` | unset | `1` to take the client address from the first `X-Forwarded-For` entry and the scheme from `X-Forwarded-Proto`. Needed behind Caddy, for rate limits, the admin login lockout and the admin cookie's `Secure` flag. Only behind your own proxy. |
| `CF_ACCOUNT_ID` | unset | Cloudflare account id for Workers AI (first chat provider). |
| `CF_AI_TOKEN` | unset | Cloudflare API token with **Workers AI: Read**. Both Cloudflare values set = provider on. |
| `CF_CHAT_MODEL` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Workers AI model. |
| `GROQ_API_KEY` | unset | Groq API key (second chat provider). |
| `GROQ_CHAT_MODEL` | `openai/gpt-oss-20b` | Groq model. On 429 the server retries once with `openai/gpt-oss-120b`. |
| `CHAT_BASE_URL` | unset | Any OpenAI-compatible server (third provider), e.g. `http://127.0.0.1:11434/v1` for Ollama. |
| `CHAT_API_KEY` | unset | Bearer key for `CHAT_BASE_URL`, if it needs one. |
| `CHAT_MODEL` | unset | Model for `CHAT_BASE_URL`. URL and model set = provider on. |
| `ADMIN_HASH_FILE` | `/etc/slimy/admin.hash` | The admin passphrase hash, written by `node src/admin-cli.js passwd`. Missing = admin routes answer 503. |
| `ADMIN_NETWORKS` | unset | Optional second lock for the admin: comma-separated addresses or CIDR networks (`192.168.1.0/24,100.64.0.0/10`). Others get 403. Unset = the reverse proxy decides alone. |

Keys live only in the server's environment file. Never in the app, the website or the repo. Without any chat provider, `/chat` answers 503 "Talking isn't set up on the server right now."

The API lives under `/api/v1`. The website is served from the same domain by the reverse proxy (see DEPLOY.md).

Health check: `GET /api/v1/health` → `{"ok": true, "version": "1", "schema": 3, "chat": true}` (`chat` is true when at least one provider is configured).

## Conventions

- JSON in and out. Bodies over 32 KB → 413; only `PUT /care/snapshot` may be larger (its snapshot up to 64 KB).
- Dates: ISO 8601 UTC **without milliseconds** (`2026-10-25T12:00:00Z`). Swift's `.iso8601` rejects fractions.
- Every error is `{"error": "<short English sentence>"}`. The app shows it as is. A few errors carry more fields next to `error` (the 409 of `PUT /care/snapshot` brings the current state). A 404 without `error` makes the app think the server is not live, so the server always sends one. A path with a broken `%` escape → 400.
- Auth: `Authorization: Bearer <token>` on everything except `POST /devices`, `GET /health`, `GET /invites/:code` and the admin routes. Tokens are 32 random bytes; only their SHA-256 is stored.
- **Unknown devices:** a missing or unknown token (for example after the device was deleted) gets 401 `{"error": "This device isn't connected to Slimy's server anymore. Go online again in Friends."}`. The app then registers a fresh device once.
- **Banned devices** get 403 `{"error": "This device can't use online features anymore."}` on every authenticated route except `DELETE /me`, so they can still delete their data.
- **Idempotency:** any write may carry `Idempotency-Key: <≤64 chars>`. A repeat with the same key returns the first answer (status and body) and does nothing else. The app's offline queue relies on this. Keys are kept for two days. Reusing a key for another path → 422. Not stored, so a retry is answered fresh: 5xx, 429, and the version conflict (409) of `PUT /care/snapshot`.
- Rate limits (per device unless noted) answer 429 with a short message: registration 10/h per address, pair codes 20/h, care codes 20/h, wrong codes (friend and care codes together) 8/h per device and 30/h per address, joins 30/h, care joins 30/h, interactions 120/h, greetings 30/day (6 per friend), challenges 20/day, profile and records 120/h each, care events 600/day, care snapshots 240/h, reports 10/day, chat 8/min and 60/day, everything 1200/h.
- `Cache-Control: no-store` on every answer.

## Endpoints

| Method and path | Body | Answer |
|---|---|---|
| `GET /health` | | `{ok, version, schema, chat}` |
| `POST /devices` | `{ownerName?}` | `{deviceId, token}`. A blank or missing name becomes "Someone". 400 "Please choose a different name." if the name fails the filter. |
| `GET /me` | | `{deviceId, ownerName, privacy: {acceptRequests, shareMood}, friends}` |
| `DELETE /me` | | 204. Deletes the device, its slime, friendships, requests, events sent and received, challenges, co-op points, memories, records and care codes. A care pair it is part of ends (the partner just becomes unpaired), and the pair's snapshot and care events are deleted. Reports it filed or that were filed about it stay, without the device id. |
| `PUT /me/slime` | `SlimeProfile` (+ `ownerName?`) | 204. 400 "Please choose a different name." if `name` or `ownerName` fails the filter or is a name an admin reset. |
| `PUT /me/privacy` | `{acceptRequests?, shareMood?}` | `{acceptRequests, shareMood}` |
| `PUT /me/records` | `{records: {"<game>": <best>}}` | 204. Only raises a record, never lowers it. Unknown game ids (and the app-only `daily`) are ignored, scores above 1 000 000 are capped, 0 is ignored, negative or non-integer scores are 400. |
| `POST /pair-codes` | `{}` | `{code, expiresAt}`; 6 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, 10 minutes, single use. 409 if requests are switched off. |
| `GET /invites/:code` (no auth) | | `{ownerName, slime: {name, skin, stage}, expiresAt}` for the website's invite page; does not use the code up. 404 when unknown, used, expired or the owner is banned; limited per address. |
| `POST /friend-requests` | `{code}` | `{status: "pending", request}` or `{status: "friends", friend}` (already friends, or both asked each other). 404 unknown/used/banned, 410 expired, 400 own code, 409 at 20 friends. |
| `GET /friend-requests` | | `{incoming: [Request], outgoing: [Request]}` |
| `POST /friend-requests/:id/accept` | `{}` | `{friend}` |
| `POST /friend-requests/:id/decline` | `{}` | 204 (also withdraws your own) |
| `GET /friends` | | `[Friend]`, in love first. Banned friends are left out. |
| `GET /friends/leaderboard` | | `{games: [{game, entries: [LeaderboardEntry]}]}`: you and your friends, best first (ties: who got there first), at most 21 entries per game, only games with at least one entry, in the game enum's order. |
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
| `POST /reports` | `{deviceId?, reason, note?, text?}` | 204. See [Reports](#reports). |
| `POST /care/codes` | `{}` | `{code, expiresAt}`; 6 chars from the pair code alphabet, 10 minutes, single use; a new one replaces your unused one. 409 "You're already caring for a slime together." when paired. See [Caring together](#caring-together). |
| `POST /care/join` | `{code}` | `{partner: {deviceId, ownerName}, snapshot: <object\|null>, version}`. The code's owner becomes the host. 404 unknown/used/banned/blocked, 410 expired, 400 own code, 409 when either side is already paired. |
| `GET /care?after=<cursor>` | | `CareState`: the partner, the shared snapshot and its version, and the partner's care events after the cursor (100 at a time). |
| `POST /care/events` | `{kind, food?, game?, amount?, at}` | `{event: CareEvent}`. 409 "You're not caring for a slime together right now." when unpaired. |
| `PUT /care/snapshot` | `{snapshot: <object ≤ 64 KB>, version}` | `{version}`. `version` must be the current one + 1, otherwise 409 `{error, version, snapshot}` with the current state. 409 `{error}` without `version` when unpaired. 400 "Please choose a different name." when `snapshot.name` fails the filter. 413 over 64 KB. |
| `DELETE /care` | | 204. Ends caring together for both sides; the snapshot and care events are deleted. Also drops your unused care code. 204 when not paired, too. |
| `POST /chat` | `{message, history: [{role: user\|slime, text}], slime: ChatContext}` | `{reply, reaction, emotion?}`. See [Chat](#chat). |

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

// LeaderboardEntry
{ "deviceId": "dev_…", "ownerName": "Anna", "slimeName": "Pebble", "skin": "ocean", "stage": "grown",
  "best": 4200, "me": false }

// ChatContext (all optional; out-of-range values are clamped, names that fail the filter become "Slime")
{ "name": "Blob", "ageDays": 3, "level": 2, "mood": "idle",
  "needs": {"hunger": 40, "fun": 60, "energy": 80, "clean": 70, "love": 90},
  "favorite": "cake", "partnerName": "Mochi", "localTime": "14:05",
  "emotions": {"joy": 0.62, "curiosity": 0.3, "sleepiness": 0.12} }
// emotions: any of joy, sadness, anger, fear, love, curiosity, boredom, excitement, sleepiness,
// numbers 0 to 1 (clamped); other keys and non-numbers are dropped.
// partnerName is only sent by apps before 1.1; without it the prompt says nothing about friends.

// ChatReply
{ "reply": "hehe, that tickles!", "reaction": "blush", "emotion": {"name": "joy", "intensity": 0.8} }
// emotion is left out when the model gave none or one that isn't usable. Older apps ignore it.

// CareEvent (CareEventWire)
{ "id": "57", "deviceId": "dev_…", "ownerName": "Anna", "kind": "feed", "food": "cake", "game": null,
  "amount": 25, "at": "2026-10-25T11:58:00Z", "createdAt": "2026-10-25T12:00:00Z" }
// food, game and amount are null when the event had none. at = when it happened on the phone,
// createdAt = when the server got it.

// CareState (GET /care)
{ "partner": {"deviceId": "dev_…", "ownerName": "Anna", "role": "host"},  // null when not caring together
  "snapshot": { … },      // the object last uploaded with PUT /care/snapshot; null before the first upload
  "version": 4,           // 0 before the first upload
  "events": [CareEvent],  // only the partner's, oldest first, after the cursor
  "cursor": "57" }        // pass as ?after= next time; stays the same when nothing new came
// partner.role is the PARTNER's role ("host" or "joiner"); your own role is the other one.
// Not caring together: {"partner": null, "snapshot": null, "version": 0, "events": [], "cursor": "<after, or 0>"}.
```

### Enums

| Field | Values |
|---|---|
| skin | lime, bubblegum, ocean, grape, lava, gold, ghost, void, axolotl (the secret legendary skin; to the server a skin like any other) |
| mood | idle, sad, sleep |
| stage | baby, young, grown |
| food | apple, carrot, berry, mushroom, cookie, soup, honey, pudding, cake, starJelly |
| interaction kind | hug, visit, gift, kiss |
| message preset | hello, missYou, goodNight, goodMorning, letsPlay, thanks, congrats, proud |
| game | snackCatcher, slimeJump, bubblePop, memoryGarden, slimeRacing, rhythmBounce, gardenPuzzle, treasureDig, basketball, colorMatch, slimeStack, jellyDash (the app's `daily` is app-only) |
| reaction | none, eat, play, love, bath, melt, zap, confused, wave, celebrate, stretch, sulk, hide, curious, blush |
| report reason | name, harassment, spam, chat, other |
| care kind | feed, play, bath, pet, sleep, wake, gift |
| care game | the game enum plus the app's `daily` |
| emotion | joy, sadness, anger, fear, love, curiosity, boredom, excitement, sleepiness |

## Rules the server enforces

- **Love** (kept for older app builds; the 1.1 app doesn't use it) per friendship, 0 to 100, shared by both. Per sender, friend and UTC day: hug +3 (3×), visit +5 (1×), gift +6 (3×), kiss +8 (2×, only from 50 %). Past the daily count a hug, visit or kiss still answers 200 with `gained: 0, capped: true` and a `message` to show; a gift answers 429 so no coins are spent. At 100 the pair is in love for good; both get an `inLove` event and a memory.
- **Requests:** a code makes a request; the code's owner accepts or declines. If both asked each other, they become friends at once. Max 20 friends.
- **Blocking** ends the friendship, removes requests and the blocked device's events from your inbox, closes open challenges, ends caring together if you shared a slime with them, and makes your future friend and care codes look unknown to them (404, same message as a typo).
- **Greetings** are presets only, so nobody can send free text through Slimy.
- **Events never control the other phone.** The app shows a toast, an island moment, gives the food of a gift, or lists a challenge. Nothing else.
- **Challenges** carry the game's seed; both play the same board. The answer is final.
- **Co-op garden:** points (the app sends the round's quality, 0–150) add up per friendship and ISO week, at most 300 per call and 900 per device per day. Reaching 1000 tells both once.
- **Records** only go up. The leaderboard shows you and your (not banned) friends.
- **Last seen:** each device's `last_seen_at` is updated on authenticated requests, at most every 10 minutes. The admin uses it for active-device counts.

### Caring together

Two people care for one slime: the host's. Each device is in at most one pair.

- **Pairing:** the host fetches a care code (`POST /care/codes`), the other person types it (`POST /care/join`) and becomes the joiner. Care codes are separate from friend codes: a care code doesn't work as a friend code or on the website's invite page, and the other way round. Wrong care codes count against the same lockout as wrong friend codes. Joining removes both sides' other unused care codes. The same join sent again (a retry) answers like the first time. Friendship isn't needed; a block in either direction makes the code look unknown.
- **Care events:** each care action on one phone (`feed`, `play`, `bath`, `pet`, `sleep`, `wake`, `gift`) is sent with `POST /care/events`; the other phone gets it from `GET /care` and applies it. `food` must be a known food, `game` a known game (or `daily`), `amount` a number from 0 to 200, `at` an ISO 8601 date with a time zone. A time in the future becomes now; one older than 30 days becomes 30 days ago. `GET /care` only lists the partner's events. Events are kept 30 days. Like friend events, they never do more on the other phone than the app does with them.
- **Snapshot:** the shared slime as one JSON object the server doesn't interpret, at most 64 KB. Either phone uploads it with the next version number (current + 1); a stale version gets 409 with the newest version and snapshot, so the phone reconciles and tries again. If the object has a string field `name`, it passes the slime name filter (also against names an admin reset on either device); a name longer than 100 characters is refused the same way (400 "Please choose a different name.").
- **Stopping:** `DELETE /care` from either side ends it for both; `GET /care` then shows `partner: null`. Deleting your data, an admin deleting or banning either device, and a block between the two end it the same way. When a pair ends, its snapshot and care events are deleted at once (both sides have left by then), and so are the saved Idempotency-Key answers of `POST /care/join`, which can hold a copy of the snapshot. An unban doesn't bring a pair back.
- A banned partner counts as gone: the other side is unpaired and can start again with someone else.

### Moderation

`src/moderation.js` (pure functions, unit-tested in `test/moderation.test.js`):

- **Names** (`ownerName` on `POST /devices` and `PUT /me/slime`, the slime's `name`, and `name` in a shared care snapshot): control characters and bidi overrides are removed, whitespace collapsed, owner names cut to 24 characters and slime names to 16. A name is refused with 400 `{"error": "Please choose a different name."}` when it contains
  - profanity, slurs or hate symbols in English or German, also as leetspeak (`sh1t`, `a$$`), with repeated letters (`fuuuck`), accents or look-alike letters from other alphabets, split by spaces or dots (`f u c k`, `f.u.c.k`). Short or ambiguous words only count as whole words, so "Cassandra", "Titan", "Therapist", "Scunthorpe" or "Kasse" stay fine. A real first name that is also a swear word ("Dick") is fine as an owner name, not as a slime name;
  - a web address (also `name (dot) com`), an email address (also `(at)`), a phone number (7 or more digits), an `@handle`, or the name of a messenger or mail service ("snapchat", "discord", "gmail", …).
- **Chat input:** swearing, email addresses and phone numbers (9+ digits, or 7+ starting with `+` or `0`) are answered by the slime itself with a gentle line and reaction `confused`; nothing is sent to an AI provider. History turns that fail the filter are dropped before a request. Runs of real short words ("let's go ok", "up or no") are not glued together, and plain numbers ("a $5 bill", "a 55") are not read as leetspeak.
- **Chat output:** a reply with swearing, a link, an email address, a phone number or a handle is replaced by a gentle line.
- **Admin rename** (see below) resets both names and remembers the old ones, so the app can't upload them again.

### Reports

`POST /reports` (auth) `{deviceId?, reason, note?, text?}` → 204.

- `reason`: `name`, `harassment`, `spam`, `chat` or `other`.
- `deviceId` is required unless `reason` is `chat`; then `text` (the slime reply being reported) is required and no device is involved.
- `note` is cut to 200 characters, `text` to 500.
- 10 reports per device per 24 hours, then 429 "You've sent a lot of reports today. We'll look at them soon." The same open report (same reporter, device and reason) is stored once.
- 404 when the device doesn't exist, 400 for yourself.
- Reporting doesn't block anyone; the app offers Block separately.

### Bans

An admin can ban a device. Then:

- it gets 403 "This device can't use online features anymore." on every authenticated route except `DELETE /me`;
- its pair codes and care codes are deleted, its open friend requests too, and any code it still had looks unknown (404);
- if it cared for a slime together with someone, that ends: the partner just becomes unpaired, and the pair's snapshot and care events are deleted (an unban doesn't bring the pair back);
- it disappears from friends lists, requests, inboxes, challenge lists and leaderboards; interactions with it answer 404 like with a stranger. Friendships are kept, so an unban brings them back.

### Chat

The app sends what the slime knows (name, age, level, mood, needs, favourite food, partner, local time, emotions) plus the last 12 turns. The server builds an in-character English system prompt and asks the configured providers in order, each through its OpenAI-compatible `/chat/completions` endpoint with plain `fetch`:

1. **Cloudflare Workers AI** (`https://api.cloudflare.com/client/v4/accounts/<CF_ACCOUNT_ID>/ai/v1`), `max_tokens: 300`.
2. **Groq** (`https://api.groq.com/openai/v1`), `reasoning_effort: "low"`, `include_reasoning: false`, `max_completion_tokens: 300`. A 429 on `openai/gpt-oss-20b` gets one retry with `openai/gpt-oss-120b`.
3. **Custom** OpenAI-compatible server (`CHAT_BASE_URL`), `max_tokens: 300`.

Each request asks for `response_format: json_schema` (strict) `{reply: string, reaction: <reaction enum>, emotion: {name: <emotion enum>, intensity: number}}`. Strict structured output needs every key, so the model always names a feeling; the range of `intensity` (0 to 1) is in the descriptions because not every provider accepts `minimum`/`maximum`. A server that rejects structured output (400/422) is asked once more without it; the prompt asks for the same JSON. Answers are read tolerantly (code fences, trailing commas, an already parsed object, or plain text). Replies are capped at 240 characters and moderated; an unknown reaction becomes `none`. An explicit model refusal becomes a gentle line.

**Emotions.** When the app sends `emotions`, the prompt says how the slime feels right now in plain words, strongest first, at most four (`very happy, curious, a little sleepy`: below 0.1 is left out, 0.7 and up is "very", under 0.35 "a little"; when all are faint it says "calm"). It asks the model to speak in that mood consistently, to let the human's words shift the mood naturally, and to stay gentle and never blame the human even when sad, grumpy or scared. Every prompt asks for the one feeling the reply expresses. The answer's `emotion` is `{name, intensity}` with `intensity` from 0 to 1, rounded to two decimals. It is read tolerantly as well (a flat `"emotion": "joy", "intensity": 0.7`, everyday words such as "happy" or "sleepy", percentages; a missing intensity counts as 0.5) and left out when unusable, when the reply was replaced by a gentle line, and for moderated input.

Each provider gets 12 seconds (`AbortController`), including Groq's retry. A timeout, network error, error status or unusable answer moves on to the next provider. If none answers: 503 `{"error": "Your slime is sleepy from all the talking. Try again later."}`.

Per UTC day and provider the server counts requests and failures (table `chat_usage`, no content) for the admin. Nothing anyone says is stored or logged; on failures the log names only the provider and the HTTP status.

## Admin API

Under `/api/v1/admin`, used by the website's admin page at `/admin`. The reverse proxy only lets the home network (`192.168.1.0/24`) and Tailscale (`100.64.0.0/10`) reach `/admin*` and `/api/v1/admin/*`; `ADMIN_NETWORKS` can enforce the same in the server.

- **Set up:** `node src/admin-cli.js passwd` asks for a passphrase twice (hidden, at least 12 characters) and writes an scrypt hash (N=2^15, r=8, p=1, 16-byte salt, 64-byte key) to `ADMIN_HASH_FILE` with mode 0640. No (valid) file → every admin route answers 503 `{"error": "Admin isn't set up. Run: node src/admin-cli.js passwd"}`. The file is read on every request: a new passphrase works at once and ends every existing session.
- **Bans and deletes** end caring together: the partner just becomes unpaired, and the pair's snapshot and care events are deleted.
- **Sign in:** `POST /admin/login {password}` → 204 and `Set-Cookie: slimy_admin=<random>; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200` (plus `Secure` when `TRUST_PROXY=1` and `X-Forwarded-Proto: https`). Wrong passphrase → 401. Five wrong tries per address in 15 minutes → 429; each try counts before the check, so parallel guesses can't get past it, and at most four checks run at once (429 otherwise). The passphrase is compared in constant time. Sessions live in memory for 12 hours (a restart signs everyone out).
- **CSRF:** every admin `POST` and `DELETE`, the login included, needs the header `X-Slimy-Admin: 1`, otherwise 403.
- Without a valid session: 401 `{"error": "Please sign in."}`. All admin answers carry `Cache-Control: no-store` and `X-Robots-Tag: noindex`.

| Method and path | Answer |
|---|---|
| `POST /admin/login {password}` | 204 + cookie |
| `POST /admin/logout` | 204, clears the cookie |
| `GET /admin/session` | `{ok: true, expiresAt}` or 401 |
| `GET /admin/stats` | see below |
| `GET /admin/reports?status=open\|resolved\|all&limit=50&before=<id>` | `{reports: [Report], next: <id>\|null}`, newest first, `limit` 1–100, default status `open` |
| `POST /admin/reports/:id/resolve {action}` | `{report}`. `dismiss`; `rename` resets the reported device's owner name to "Slime friend" and slime name to "Slime" (and resolves its other open `name` reports); `ban` bans it (and resolves all its other open reports). 409 when already resolved, or for rename/ban when the report isn't about a device. |
| `GET /admin/devices?q=&limit=50&before=<cursor>&banned=1` | `{devices: [AdminDevice], next: <cursor>\|null}`, newest first. `q` matches an id prefix, owner or slime name (case-insensitive); `banned=1` lists only banned devices. |
| `GET /admin/devices/:id` | `{device: AdminDevice, carePartner: {id, ownerName}\|null, friends: [{id, ownerName, slimeName, love, inLove, banned}], reports: [Report] (about it, newest 50), reportsFiled, records: [{game, best}]}` |
| `POST /admin/devices/:id/ban` · `/unban` · `/rename` | `{device: AdminDevice}` |
| `DELETE /admin/devices/:id` | 204, same cascade as `DELETE /me` |
| `GET /admin/chat-usage?days=30` | `{days: [{day, provider, requests, failures}]}`, oldest first, `days` 1–400 |

```jsonc
// GET /admin/stats
{ "devices": {"total": 120, "active1d": 40, "active7d": 80, "active30d": 110, "new7d": 12, "banned": 1},
  "friendships": {"total": 64, "inLove": 9},
  "care": {"pairs": 5},                // pairs caring for one slime together
  "events24h": 530, "pairCodes24h": 14,
  "reports": {"open": 2, "total": 7},
  "chat": { "today": {"requests": 310, "failures": 4, "byProvider": {"cloudflare": {"requests": 300, "failures": 4}, "groq": {"requests": 10, "failures": 0}}},
            "last7": [{"day": "2026-10-19", "requests": 280, "failures": 2}, …] },
  "games": {"plays7d": null},          // the server doesn't see single rounds
  "db": {"bytes": 1302528},
  "server": {"version": "1.0.0", "uptimeSec": 86400, "node": "v22.23.4"},
  "providers": [{"name": "cloudflare", "model": "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "configured": true}, …] }

// Report
{ "id": 7, "createdAt": "...", "reason": "name", "note": "rude name", "text": null,
  "status": "open", "resolution": null, "resolvedAt": null,
  "reporter": {"id": "dev_…", "ownerName": "Anna"},          // both null once the reporter deleted their data
  "reported": {"id": "dev_…", "ownerName": "Ben", "slimeName": "Pebble", "banned": false} }  // null for chat reports

// AdminDevice
{ "id": "dev_…", "ownerName": "Ben", "slimeName": "Pebble", "skin": "ocean", "stage": "grown", "level": 13,
  "createdAt": "...", "lastSeenAt": "...", "friends": 3, "banned": false, "bannedAt": null, "reportsAgainst": 1 }
```

## Privacy

Stored per device: a random id, the token hash, the owner name the user typed, the slime profile above, friendships, love, memories, requests, blocks, best scores per game, events for 30 days, challenges for 14 days, co-op points, idempotency receipts for 2 days, the time of the last request (to the nearest 10 minutes), and whether it is banned. No contacts, location, device identifiers, IP addresses or phone events.

**Caring together** stores, per pair, the two device ids, which one is the host, the latest shared snapshot with its version number, and the care events of the last 30 days. The snapshot is the slime's shared state as the app sends it (name, skin, level, XP, needs, bond, personality, outfit, emotions, asleep or awake, favourite food and game, hatch date, when it changed). A care event says who did what (feed, play, bath, pet, sleep, wake or gift), with the food, game and amount, and when. Only the two people in the pair can read them. Everything of a pair is deleted when it ends; care codes go after a day.

**Chat:** messages are sent to the configured AI providers, **Cloudflare Workers AI** and, when Cloudflare is busy or its free quota is used up, **Groq**, to produce a reply: the message, the recent conversation and the slime's facts (name, age, level, mood, needs, favourite food, its friend's slime name, local time, and how the slime feels right now as plain words such as "very happy, a little sleepy"). No owner name and no device id are sent. The provider's answer names the feeling of the reply, which goes back to the app. The server doesn't store or log any message or emotion. It only counts requests and failures per provider and day.

**Reports** store the reporter's device id, the reported device id, the reason, the optional note and, for chat reports, the reported reply.

**Retention:**

| What | How long |
|---|---|
| Inbox events | 30 days |
| Care events | 30 days; all of a pair's at once when the pair ends |
| Care snapshot | while the pair lasts; deleted when it ends (both sides have left) |
| Used or expired care codes | 1 day |
| Challenges | 14 days after they expire |
| Used or expired pair codes | 1 day |
| Idempotency receipts | 2 days |
| Open reports | until an admin resolves them |
| Resolved reports | 90 days after resolving |
| Chat usage counters (no content) | 400 days |
| Admin sessions | 12 hours, in memory only |
| Rate-limit counters (device ids, IP addresses) | at most 24 hours, in memory only |

Cleanup runs at start and every 15 minutes (it also ends pairs with a banned device). `DELETE /me` removes everything for that device at once and ends its care pair; reports about it or by it stay until their retention ends, without the device id. A banned device keeps its data until it deletes it or an admin does.

## Tests

`npm test` runs five files with `node:test`:

- `test/api.test.js`: the HTTP listener on a random port with an in-memory database, a controllable clock and a fake Groq. Registration and auth, validation, date format, token hashing, rate limits, codes (single use, expiry, own code, brute force), requests, privacy, love caps and falling in love, gifts, idempotent retries, inbox cursors and cleanup, blocking, challenges, the co-op garden, chat (refusals, moderated input, usage counters), chat emotions in and out, account deletion, name filtering, last seen, reports, bans, records, the leaderboard, the new games, the axolotl skin, retention, migration 2 on a version 1 database and migration 3 on a version 2 database.
- `test/care.test.js`: caring together over HTTP: care codes (alphabet, expiry, single use, replacing, separate from friend codes, rate limit), joining (roles, own code, 409s, retries, blocks and bans, the shared wrong-code lockout), care events (partner only, cursor and pages, validation, times, idempotency, 600 a day, 30 days), the snapshot (versions, 409 with the current state and never replayed, the name filter and its length cap, 64 KB, rate limit), stopping from either side, `DELETE /me`, bans, blocks, cleanup, and love still working for older apps.
- `test/chat.test.js`: the provider chain with a fake `fetch`: request shapes per provider, Cloudflare 429 → Groq, Groq 20b 429 → 120b, servers without structured output, timeouts, all failing → 503, tolerant parsing, the 240-character cap, output moderation, moderated input never sent, history filtering, the prompt, and emotions (schema, feelings in plain words, the reply's emotion, tolerant parsing).
- `test/moderation.test.js`: the filters, including leetspeak, spacing, look-alike letters and innocent names that must pass.
- `test/admin.test.js`: the passphrase file, no hash → 503, wrong passphrase → 401, lockout → 429 (per address behind the proxy), the cookie and its flags, session expiry, logout and passphrase change, the CSRF header, `ADMIN_NETWORKS`, every admin endpoint, and caring together in the admin (stats, care partner, ban, delete).
