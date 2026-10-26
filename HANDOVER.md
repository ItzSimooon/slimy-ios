# Handover

How Slimy runs today, how to change and deploy each part, and what is still open. For whoever operates **slime.hyprnode.de** next, including future you.

State: **26 October 2026**, work on branch `website` (not yet merged into `main`).

| Part | Where | Status |
|---|---|---|
| iOS app 1.0 | `App/`, `Shared/`, `Widget/` | Built by CI, sideloaded beta on GitHub Releases. App Store submission is next (`appstore/`). |
| API server | `server/` → CT 108 | Live at `https://slime.hyprnode.de/api/v1` (pre-1.0 version). Deploy 1.0 and add the AI keys (sections 2 and 4). |
| Website | `site/` → CT 108 | Built from this repo, served by Caddy on CT 108. |
| Admin area | `site/admin/` + `/api/v1/admin/*` | Needs the passphrase and the LAN-only Caddyfile (section 3). |
| Store listing | `appstore/` | Texts, review notes, privacy and age rating ready. |

Read `server/README.md` before touching the API; it is the contract between server, app and admin. Change it only together with `App/Online/HyprnodeAPI.swift` and, for admin routes, `site/admin/`. **`server/DEPLOY.md`** is the step-by-step for the container: environment file, AI tokens, admin passphrase, the complete Caddyfile, the systemd unit and a smoke test. This file gives the overview and the decisions around it.

---

## 1. How the pieces fit

- **The app is offline first.** The slime, its needs, coins, items, records and chat history live on the iPhone (shared with the widgets through the App Group `group.de.hyprnode.slimy`). Nothing is sent until the user taps **Go Online** in Friends or Talk and accepts the Terms.
- **The server** (Node, one SQLite file, zero npm dependencies) handles devices, friends, love, gifts, visits, greetings, challenges, the shared garden, records and the friends leaderboard, reports and bans, the chat proxy to free AI providers, and the admin API.
- **The website** is static: landing page, install guide, invite pages (`/f/<CODE>`), privacy, terms, support, press kit, 404, and the admin area at `/admin`.
- **CI** (GitHub Actions) builds every branch and pull request: it checks the pixel fonts and puzzles, tests the server, runs the iOS unit tests in a simulator, builds the IPAs and takes screenshots of every screen in light and dark. Only a push to `main` publishes a release (section 5).

## 2. Production: CT 108

Everything for Slimy runs in its own LXC container on the Proxmox host `pve`, behind its own Cloudflare tunnel. Nothing is exposed through the router.

| What | Where |
|---|---|
| Container | CT 108 `slimy`: Debian 13, 2 cores, 1 GB RAM, starts on boot. LAN address `192.168.1.234` (DHCP; reserve it in UniFi). |
| Access | SSH with the admin PC's key. There is no root password. |
| Server code | `/opt/slimy/server` (copied from this repo, not a git checkout) |
| Service | `slimy.service` (systemd, user `slimy`, `ReadWritePaths=/var/lib/slimy`) |
| Configuration | `/etc/slimy/slimy.env` (mode 640, `root:slimy`) |
| Database | `/var/lib/slimy/slimy.db` (SQLite, WAL) |
| Backups | `/etc/cron.daily/slimy-backup`: `sqlite3 .backup` to `/var/backups/slimy/slimy-YYYY-MM-DD.db`, kept 14 days |
| Web server | Caddy on `:80`: `/api/*` → `127.0.0.1:8080`, everything else from `/srv/slimy-site` |
| Tunnel | `cloudflared.service`, tunnel `slimy`, hostname `slime.hyprnode.de` → `http://localhost:80` |
| TLS | Terminated by Cloudflare. The tunnel carries traffic to the container; Caddy speaks plain HTTP locally. |

Logs: `journalctl -u slimy -f` (the server logs only startup and errors, never request contents), `journalctl -u caddy`, `journalctl -u cloudflared`. Caddy has no access log on purpose.

Health: `curl -s https://slime.hyprnode.de/api/v1/health` → `{"ok":true,…,"chat":true}`.

Secrets are never in this repo: AI keys live only in `/etc/slimy/slimy.env`, the admin passphrase only as a hash in `/etc/slimy/admin.hash`, the tunnel token only in cloudflared's service config.

### Deploying a new server version

Follow `server/DEPLOY.md`, section 1. In short: copy `server/` to `/opt/slimy/server` with `tar` over SSH, run `npm test` there, back up the database, `systemctl restart slimy`, and check the log line, which names the chat providers in use. The server has no npm dependencies, so there is nothing to install. Migrations run on start; 1.0 adds reports, bans, records, chat usage and `last_seen_at`.

### Deploying the website

Preview it on the PC first: `node site/build.mjs && node site/tools/dev-server.mjs`, then open http://localhost:4310. The preview server copies the Caddyfile's routing and security headers, answers a sample invite (`/f/ABC123`) and a sample `release.json`, and loads the admin mock (`site/admin/dev-mock.mjs`) automatically, so `/admin` works without the real API. `site/dist/` is build output and is gitignored.

Page and asset URLs: `/`, `/install`, `/support`, `/privacy`, `/terms`, `/press`, `/f/<CODE>`, `/404.html`, `/assets/*`, `/fonts/*`, `/sprites.json`, `/release.json`, `/admin`. Links in the app, the App Store listing and the press kit point at these, so keep them stable.

```sh
node site/build.mjs                    # writes site/dist
ssh root@192.168.1.234 'rm -rf /srv/slimy-site.new && mkdir -p /srv/slimy-site.new'
tar -C site/dist -cf - . | ssh root@192.168.1.234 'tar --no-same-owner -C /srv/slimy-site.new -xf - && rm -rf /srv/slimy-site.old && mv /srv/slimy-site /srv/slimy-site.old && mv /srv/slimy-site.new /srv/slimy-site'
```

The download buttons read `/release.json`. `site/release.mjs` writes it from the GitHub API (all builds are prereleases, so GitHub's `/releases/latest` doesn't work). Run it on CT 108 from a systemd timer or cron every 15 minutes: `node /opt/slimy/site-release.mjs /srv/slimy-site/release.json` (copy `site/release.mjs` there first). Run it once by hand after every site deploy, since the deploy replaces the folder.

The routing for the site (clean URLs, `/f/*` to the invite page, the 404 page, cache headers, the content security policy) is part of the Caddyfile in `server/DEPLOY.md`, section 6.

## 3. Admin area: LAN only

The admin pages (`/admin`) and the admin API (`/api/v1/admin/*`) are never reachable from the internet. There are two locks. Copy both from `server/DEPLOY.md` as they are; this section only explains them.

1. **Caddy** (`server/DEPLOY.md`, section 6, the complete Caddyfile) answers 404 for those paths unless the client address is in the home network (`192.168.1.0/24`) or Tailscale (`100.64.0.0/10`). Tunnel traffic reaches Caddy from `127.0.0.1` or `::1`, which is in neither range, so everything from the internet is refused. Never add loopback addresses to that Caddy rule: cloudflared runs on the same container.
2. **The server** (`server/DEPLOY.md`, section 2, the environment file) checks again with `ADMIN_NETWORKS`. The Caddyfile sets `X-Forwarded-For` from `Cf-Connecting-Ip` only when that header is present and the request comes from cloudflared on the container itself; with `TRUST_PROXY=1` the server then sees the visitor's real address for tunnel requests, so even a broken Caddy rule would not let them in. From the home network Caddy passes the client's own address, so a PC there can't fake its address with that header.

How the Caddy lock is built matters. The Caddyfile defines one matcher, `@admin_outside` (admin paths and not a home or Tailscale address), and puts `error @admin_outside 404` **inside each** `handle` block: the one for `/api/*` and the one for the site. Don't replace it with a separate `handle @admin { … }` block. CT 108 runs Caddy 2.6.2, which sorts `handle /api/*` (a single path) ahead of a named matcher with several paths, so admin API requests from the tunnel would go to the `/api/*` block and skip the admin block, leaving only `ADMIN_NETWORKS` to stop them.

After any Caddy change, check from mobile data (outside the home network) that `https://slime.hyprnode.de/admin` and `https://slime.hyprnode.de/api/v1/admin/session` both answer 404.

Open the admin at `http://192.168.1.234/admin` from the home network, or through Tailscale if the container or a subnet router is on the tailnet. On plain HTTP the passphrase crosses the home network unencrypted; Tailscale encrypts it.

### Setting the passphrase

On CT 108, as root, in a real terminal: `cd /opt/slimy/server && node src/admin-cli.js passwd`. It asks twice with hidden input (at least 12 characters; a few random words work well), stores only an scrypt hash in `/etc/slimy/admin.hash` for `root:slimy`, and needs no restart. `node --env-file=/etc/slimy/slimy.env src/admin-cli.js status` shows whether the admin is set up and which chat providers are configured, without printing any key. Without the file, every admin route answers 503. Five wrong tries in 15 minutes lock an address out for a while; a session lasts 12 hours.

### What the admin does

- **Overview:** devices (total, active 1/7/30 days, new this week, banned), friendships and couples in love, events and pair codes in the last 24 hours, open reports, chat requests and failures per provider today and for 7 days, database size, server version and uptime, which AI providers are configured.
- **Reports:** the queue with reason, note, reported text, reporter and reported device. Resolve with Dismiss, Rename (resets names to "Slime friend" and "Slime") or Ban.
- **Devices:** search by id prefix or name; see friends and reports; ban, unban, rename, delete (same cascade as a user deleting their data).
- **Chat usage:** requests and failures per provider and day.
- **About:** how access works, how to set the passphrase, light or dark appearance.

Moderation promise (App Review Guideline 1.2): look at open reports **every day**, and resolve them within 24 hours. A banned device gets 403 on every online feature and disappears from its friends' lists.

## 4. AI chat: free providers

The server asks up to three OpenAI-compatible providers in order and uses the first that answers. No SDK, no message is stored or logged, the server only counts requests and failures per provider and day. Moderation runs on the user's message before any provider is called and on the reply before it is returned.

Add the keys to `/etc/slimy/slimy.env` (template and click-by-click instructions for both tokens in `server/DEPLOY.md`, sections 2 to 4), then `systemctl restart slimy` and check `/api/v1/health` for `"chat": true`.

| Order | Provider | Variables | Where to get them |
|---|---|---|---|
| 1 | Cloudflare Workers AI | `CF_ACCOUNT_ID`, `CF_AI_TOKEN`, optional `CF_CHAT_MODEL` (default `@cf/meta/llama-3.3-70b-instruct-fp8-fast`) | Cloudflare dashboard: account id from the account home page; a custom API token (*Create Custom Token*) with the single permission **Account · Workers AI · Read** for your account. Click by click in `server/DEPLOY.md`, section 3. |
| 2 | Groq | `GROQ_API_KEY`, optional `GROQ_CHAT_MODEL` (default `openai/gpt-oss-20b`; on a rate limit it retries once with `openai/gpt-oss-120b`) | console.groq.com → API Keys. The free plan needs no card. |
| 3 | Any OpenAI-compatible server, e.g. Ollama | `CHAT_BASE_URL` (for example `http://<host>:11434/v1`), `CHAT_MODEL`, optional `CHAT_API_KEY` | Your own machine; CT 108 must reach it. |

Also remove the old lines `ANTHROPIC_API_KEY=` and `CHAT_MODEL=claude-…` from the file. The Anthropic SDK is gone, and `CHAT_MODEL` now belongs to the third provider only.

Each provider gets 12 seconds. If all fail or the free quota is used up, the app shows "Your slime is sleepy from all the talking. Try again later." Per device the server allows a few messages per minute and a daily maximum (see `server/README.md`).

**Free capacity:** together roughly 600 chats a day. Workers AI includes 10,000 neurons per day, which is about 250 to 300 short chats with Llama 3.3 70B. Groq's free plan allows 1,000 requests and 200,000 tokens per day per model, about 200 to 250 chats with gpt-oss-20b, plus what the 120b fallback adds when 20b is rate-limited. Watch the chat panel in the admin.

**When it isn't enough** (check the providers' current price lists before switching):

1. Groq Developer plan, pay as you go: gpt-oss-20b costs about 0.075 USD per million input tokens and 0.30 USD per million output tokens, roughly 0.01 cents per chat, so 1,000 extra chats a day cost about 3 USD a month. Higher rate limits too.
2. Workers AI on the Workers Paid plan (5 USD a month): usage beyond the free 10,000 neurons a day costs 0.011 USD per 1,000 neurons, about 0.04 cents per chat.
3. A model on your own hardware through `CHAT_BASE_URL` (no per-chat cost; needs a machine with a GPU that is always on).
4. Lower the per-device daily limit in the server.

If a provider run by a third party is added through `CHAT_BASE_URL`, the app (the chat consent sheet in `App/UI/Screens/ChatConsentView.swift` and the Privacy footer in `App/UI/Screens/SettingsView.swift`) and the privacy policy must name it before it goes live.

## 5. CI and releases

`.github/workflows/build.yml` builds and tests every branch (every push except to `ci-screenshots`) and every pull request, and can be started by hand. Releases come only from pushes to `main`. A newer push cancels a running build of the same branch, except on `main`, where every push becomes a release.

| Job | What |
|---|---|
| Check slime fonts and puzzles | Rebuilds sprites and fonts with `tools/make_slime.py`, verifies every timer frame with HarfBuzz, checks every Garden Puzzle level is solvable |
| Server tests | `npm test` in `server/` on Node 22 (the server has no dependencies) |
| Unit tests (simulator) | XcodeGen, then `xcodebuild test` on an iPhone simulator |
| Build IPA | Release build without signing, privacy manifest check, `Slimy-unsigned.ipa` plus `Slimy.ipa` ad-hoc signed with the App Group entitlement. Only a push to `main` publishes them as prerelease `build-<run number>`. |
| Screenshots (simulator) | Debug build, then every screen in light and dark (below). Never fails the run. |

There is no Mac here: Swift only compiles in CI. Compile errors show up as annotations on the run (`tools/ci_report.sh`).

### Screenshots

Screenshot mode is Debug-only and driven by launch arguments: `-SlimyScreenshot`, plus `-SlimySheet <name>` (kitchen, shop, journal, friends, settings, chat, gift) or `-SlimyCover <name>` (arcade, bath). It opens that screen with a demo slime called Mochi, without onboarding, What's New, toasts, celebrations, the Live Activity or network (`App/Core/ScreenshotMode.swift`). In Release builds the arguments do nothing. `tools/ci_screenshots.sh` shoots the home screen and every sheet and cover in light and dark on the newest iPhone Pro simulator with a 9:41 status bar. Every run keeps the set as its **`screenshots`** artifact; on pushes, `tools/ci_publish_screenshots.sh` also force-pushes it to the **`ci-screenshots`** branch with a README that shows light and dark side by side. Files: `light-<screen>.png`, `dark-<screen>.png`.

Use the branch to review UI changes without a phone, and as the raw material for the App Store images: `appstore/screenshots.md` says which file goes where and how to compose the 6.9" and 6.5" uploads.

## 6. App Store

Everything is prepared in `appstore/`: listing texts in English and German (`node appstore/check.mjs` validates them), review notes, privacy answers, age rating and the screenshot plan. The order of work is in `appstore/README.md`. Before submitting, the server must be live with chat on, the admin must be set up, and the website's privacy, terms and support pages must be deployed.

## 7. Smoke test after a deploy

Needs `jq`. Creates two throwaway devices and deletes them again:

```sh
B=https://slime.hyprnode.de/api/v1
curl -s $B/health
TA=$(curl -s -XPOST $B/devices -H 'content-type: application/json' -d '{"ownerName":"Ana"}' | jq -r .token)
TB=$(curl -s -XPOST $B/devices -H 'content-type: application/json' -d '{"ownerName":"Ben"}' | jq -r .token)
CODE=$(curl -s -XPOST $B/pair-codes -H "authorization: Bearer $TA" -H 'content-type: application/json' -d '{}' | jq -r .code)
curl -s $B/invites/$CODE
REQ=$(curl -s -XPOST $B/friend-requests -H "authorization: Bearer $TB" -H 'content-type: application/json' -d "{\"code\":\"$CODE\"}" | jq -r .request.id)
curl -s -XPOST $B/friend-requests/$REQ/accept -H "authorization: Bearer $TA" -H 'content-type: application/json' -d '{}'
curl -s $B/friends/leaderboard -H "authorization: Bearer $TA"
curl -s -XPOST $B/chat -H "authorization: Bearer $TA" -H 'content-type: application/json' \
  -d '{"message":"hi","history":[],"slime":{"name":"Blob","ageDays":3,"level":2,"mood":"idle","needs":{"hunger":60,"fun":60,"energy":60,"clean":60,"love":60},"favorite":"cake","localTime":"12:00"}}'
curl -s -XDELETE $B/me -H "authorization: Bearer $TA" -o /dev/null -w '%{http_code}\n'
curl -s -XDELETE $B/me -H "authorization: Bearer $TB" -o /dev/null -w '%{http_code}\n'
```

Then on two iPhones: Friends → **Go Online** on both, **Get My Code** on one, type the code and **Connect** on the other, accept, hug and send a gift. The love goes up on both phones and the gift arrives on the other one.

## 8. Where the app talks to the server

| App file | What it does |
|---|---|
| `App/Online/HyprnodeAPI.swift` | every request and response type, error mapping, idempotency keys |
| `App/Online/FriendsStore.swift` | opt-in **Go Online** (only after the user accepts the Terms) and Go Offline (stops syncing, keeps friends on the phone); the device token in the Keychain, this device only (`TokenVault`); profile, friends, requests, blocks, reports, privacy, inbox, interactions, greetings, challenges, shared garden, records upload and the friends leaderboard, offline queue. There is no demo friend: the betas' test friend and its saved state are removed on first launch of 1.0. |
| `App/Online/ChatStore.swift` | AI chat with consent (`slimy.chat.aiConsent`, withdrawable), gated on Go Online, retry after errors, reporting a reply |
| `App/Arcade/ArcadeView.swift`, `App/Arcade/GameHost.swift` | challenge rounds (same seed), shared-garden points and record uploads after a round |

## 9. Open items

For the owner:

1. **AI keys:** create the Cloudflare Workers AI token and the Groq key, put them with `CF_ACCOUNT_ID` into `/etc/slimy/slimy.env` using the template in `server/DEPLOY.md` (which also drops the old Anthropic lines), restart (section 4).
2. **Admin passphrase:** run `node src/admin-cli.js passwd` on CT 108 (section 3).
3. **Caddyfile** from `server/DEPLOY.md`, section 6, copied as it is (LAN-only admin, invite pages, security headers), `ADMIN_NETWORKS` and `TRUST_PROXY=1` from the env template in section 2, and the check from mobile data (section 3 here).
4. **Reserve** `192.168.1.234` for CT 108 in UniFi.
5. **Deploy** server 1.0 (with a backup first) and the website from this branch, then run the smoke test.
6. **Push** the `website` branch (CI builds and tests it and publishes screenshots to `ci-screenshots`), then merge into `main` so CI publishes the 1.0 IPAs.
7. **Legal pages:** the website needs an imprint (Impressum, § 5 DDG) with your name, a postal address and an email, and the privacy policy needs the same controller details. Only you can supply them.
8. **Contact address:** decide on one address for support and press (for example on hyprnode.de) and put it on the support page.
9. **Apple Developer Program** and the first submission (`appstore/README.md`).
10. **Moderation routine:** check reports daily once real users are online.

Known limits:

- iOS ends Live Activities after about 8 hours. Slimy starts or renews one only when the app is opened or Summon Slime runs; background refresh can only update a running activity, and nothing uses push.
- A `.slimysave` backup moves the slime, not the online identity: the device token never leaves the phone's Keychain and friends live outside the save. On a new iPhone the user restores the save, goes online again as a new device and reconnects with friends by code.
- `https://slime.hyprnode.de/f/CODE` opens the website's invite page, which shows the code to type in. The app also understands `slimy://f/CODE` (`FriendsStore.openInvite`) and fills in the code. Opening the app straight from the https link needs Associated Domains, which needs the paid developer account.
- The server is a single container at home: if power or internet at home fail, friends and chat pause and the app keeps working offline. The offline queue sends everything once the server is back.
