# Deploying the Slimy API

How the server runs on **CT 108** (`slimy`, 192.168.1.234) behind Caddy and the Cloudflare tunnel `slimy`, and what the owner sets up by hand: the chat keys and the admin passphrase. Nothing here needs a password or key in the repo.

```
iPhone / browser ──HTTPS──> Cloudflare ──tunnel──> cloudflared (CT 108) ──> Caddy :80 ──/api/*──> node 127.0.0.1:8080
home network / Tailscale ──HTTP──> Caddy :80 (192.168.1.234) ──/admin, /api/v1/admin/*──> same
```

| What | Where |
|---|---|
| Server code | `/opt/slimy/server` (this folder) |
| Service | `slimy.service`, user `slimy` |
| Settings and keys | `/etc/slimy/slimy.env` (0640, root:slimy) |
| Admin passphrase hash | `/etc/slimy/admin.hash` (0640, root:slimy) |
| Database | `/var/lib/slimy/slimy.db` (SQLite, WAL) |
| Backups | `/var/backups/slimy/`, daily, kept 14 days |
| Website | `/srv/slimy-site` (built by `site/build.mjs`) |
| Caddy | `/etc/caddy/Caddyfile` |

## 1. Update the code

The server has no npm dependencies, so there is no `npm ci`. From the PC (Git Bash):

```sh
cd slimy-ios
tar -cf - --exclude=data --exclude=node_modules server | ssh root@192.168.1.234 'cd /opt/slimy && tar --no-same-owner -xf -'
ssh root@192.168.1.234 'cd /opt/slimy/server && rm -rf node_modules && npm test'
```

Before the first start of 1.0, back up the database once. Version 1.0 adds migration 2 (reports, bans, records, chat usage, `last_seen_at`); it runs by itself on start.

```sh
sqlite3 /var/lib/slimy/slimy.db ".backup /var/backups/slimy/before-1.0.db"
systemctl restart slimy
journalctl -u slimy -n 20 --no-pager   # "slimy api 1.0.0 on 127.0.0.1:8080 (chat via cloudflare, groq)"
```

## 2. The environment file

Replace `/etc/slimy/slimy.env` with this template and fill in the keys (sections 3 and 4). Empty values mean "not configured". The old `ANTHROPIC_API_KEY` and `CHAT_MODEL=claude-…` lines must go: `CHAT_MODEL` now belongs to the custom provider.

```ini
# Slimy API. Read by slimy.service. Keep it 0640 root:slimy; it holds keys.
PORT=8080
HOST=127.0.0.1
SLIMY_DB=/var/lib/slimy/slimy.db
TRUST_PROXY=1

# Admin (section 5)
ADMIN_HASH_FILE=/etc/slimy/admin.hash
ADMIN_NETWORKS=192.168.1.0/24,100.64.0.0/10,127.0.0.1/32,::1/128

# Chat providers, tried in this order. A provider with empty keys is skipped.
# 1. Cloudflare Workers AI
CF_ACCOUNT_ID=
CF_AI_TOKEN=
CF_CHAT_MODEL=
# 2. Groq (on 429 the server retries once with openai/gpt-oss-120b)
GROQ_API_KEY=
GROQ_CHAT_MODEL=
# 3. Any OpenAI-compatible server, e.g. Ollama at http://127.0.0.1:11434/v1
CHAT_BASE_URL=
CHAT_API_KEY=
CHAT_MODEL=
```

```sh
chown root:slimy /etc/slimy/slimy.env && chmod 0640 /etc/slimy/slimy.env
systemctl restart slimy
```

`ADMIN_NETWORKS` is a second lock behind Caddy's: the server itself refuses admin requests from other addresses. With `TRUST_PROXY=1` and the Caddyfile below, a request through the tunnel carries the visitor's real address (from `Cf-Connecting-Ip`), so it can't pass even if Caddy were misconfigured. `127.0.0.1` lets you use the admin API directly on the container (`curl http://127.0.0.1:8080/...`).

## 3. Cloudflare Workers AI token (owner)

1. Sign in at <https://dash.cloudflare.com> with the account that owns `hyprnode.de`.
2. **Account ID:** on the account's home page, open the menu next to the account name and choose *Copy account ID* (it is also shown on *Workers & Pages* → right column). Put it into `CF_ACCOUNT_ID=`.
3. **Token:** profile icon (top right) → *My Profile* → *API Tokens* → *Create Token* → *Create Custom Token* → *Get started*.
   - Token name: `slimy-workers-ai`
   - Permissions: **Account** · **Workers AI** · **Read**
   - Account Resources: *Include* · your account
   - Client IP Address Filtering and TTL: leave empty
   - *Continue to summary* → *Create Token*, then copy the token. Cloudflare shows it only once.
4. Put it into `CF_AI_TOKEN=` in `/etc/slimy/slimy.env`. Leave `CF_CHAT_MODEL=` empty for the default `@cf/meta/llama-3.3-70b-instruct-fp8-fast`.
5. Check the token without printing it:
   ```sh
   set -a; . /etc/slimy/slimy.env; set +a
   curl -s https://api.cloudflare.com/client/v4/user/tokens/verify -H "Authorization: Bearer $CF_AI_TOKEN" | jq -r '.messages[0].message'
   # This API Token is valid and active
   ```
6. Check that the token can actually run a model. This is one real request, the same kind the server sends:
   ```sh
   curl -s "https://api.cloudflare.com/client/v4/accounts/$CF_ACCOUNT_ID/ai/v1/chat/completions" \
     -H "Authorization: Bearer $CF_AI_TOKEN" -H 'Content-Type: application/json' \
     -d '{"model":"@cf/meta/llama-3.3-70b-instruct-fp8-fast","messages":[{"role":"user","content":"Say hello in three words."}],"max_tokens":20}' \
     | jq -r '.choices[0].message.content // .errors[0].message // .'
   # A short greeting means the token works.
   ```
   If it prints `Authentication error` (Cloudflare code 10000), Read alone isn't enough for this account: *API Tokens* → `slimy-workers-ai` → *Edit*, add a second permission row **Account** · **Workers AI** · **Edit**, *Continue to summary* → *Update Token*, and run the check again. Editing keeps the token value, so `slimy.env` stays as it is. Without this check a broken Cloudflare token goes unnoticed, because the server quietly falls back to Groq.

Workers AI includes a free daily allocation. When it is used up, Cloudflare answers with an error and the server moves on to Groq for the rest of the day.

## 4. Groq API key (owner)

1. Sign in at <https://console.groq.com>.
2. *API Keys* → *Create API Key*, name `slimy`, submit, copy the key (shown once).
3. Put it into `GROQ_API_KEY=`. Leave `GROQ_CHAT_MODEL=` empty for `openai/gpt-oss-20b`.

Then `systemctl restart slimy`. The log line names the providers it uses, and `node --env-file=/etc/slimy/slimy.env src/admin-cli.js status` lists them without showing keys.

## 5. Admin passphrase (owner)

The service runs as `slimy` and reads `/etc/slimy/admin.hash` itself, so the folder must let that group in (otherwise the log says `can't read /etc/slimy/admin.hash (EACCES)` and the admin stays "not set up"):

```sh
chgrp slimy /etc/slimy && chmod 0750 /etc/slimy
```

The easy way for keys and passphrase together: copy `server/tools/slimy-setup.sh` to `/usr/local/sbin/slimy-setup` (mode 0700) and run `slimy-setup`. It asks for the Cloudflare token and the Groq key (hidden), checks both against the providers, saves them, restarts the service, tests the chat, then asks for the passphrase.

Or only the passphrase, on CT 108, as root, in a real terminal (the input is hidden; it refuses pipes):

```sh
cd /opt/slimy/server
node src/admin-cli.js passwd
# New admin passphrase:
# Type it again:
# Saved to /etc/slimy/admin.hash (mode 0640). Everyone signed in to the admin is signed out.
node --env-file=/etc/slimy/slimy.env src/admin-cli.js status
```

- At least 12 characters; a few random words work well. Only the scrypt hash is stored.
- A new file is given to `root:slimy` so the service can read it; an existing one keeps its owner. If the status line shows another group: `chown root:slimy /etc/slimy/admin.hash`.
- No restart needed: the server reads the file on every admin request. Running `passwd` again changes the passphrase and signs everyone out.
- To switch the admin off, delete the file. Admin routes then answer 503.

Open the admin at `http://192.168.1.234/admin` from the home network, or through Tailscale if the container (or a subnet router) is on your tailnet. Through `https://slime.hyprnode.de/admin` it is a 404 on purpose. On plain HTTP the passphrase crosses the home network unencrypted; Tailscale encrypts it.

## 6. Caddyfile

`/etc/caddy/Caddyfile` (Caddy 2.6 or newer). Cloudflare terminates HTTPS and `cloudflared` sends tunnel traffic to `localhost:80`, so tunnel requests reach Caddy from `127.0.0.1` or `::1` and never pass the admin check. There is no `log` directive: Caddy writes no request log.

```caddyfile
:80 {
	root * /srv/slimy-site
	encode zstd gzip

	# The admin, its API and nothing else, only from the home network and Tailscale.
	@admin_outside {
		path /admin* /api/v1/admin*
		not remote_ip 192.168.1.0/24 100.64.0.0/10
	}

	header {
		Content-Security-Policy "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
		X-Content-Type-Options "nosniff"
		Referrer-Policy "no-referrer"
		Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()"
		X-Frame-Options "DENY"
		Strict-Transport-Security "max-age=31536000"
		-Server
	}

	@fonts path /fonts/*
	header @fonts Cache-Control "public, max-age=31536000, immutable"
	@assets path /assets/*
	header @assets Cache-Control "public, max-age=604800, stale-while-revalidate=86400"
	@pages not path /api/* /assets/* /fonts/*
	header @pages Cache-Control "no-cache"

	handle /api/* {
		error @admin_outside 404
		# Through the tunnel the visitor's address is in Cf-Connecting-Ip. Only
		# cloudflared (on this container) may set it; from the LAN it is ignored.
		@tunnel {
			header Cf-Connecting-Ip *
			remote_ip 127.0.0.1/8 ::1
		}
		reverse_proxy @tunnel 127.0.0.1:8080 {
			header_up X-Forwarded-For {http.request.header.Cf-Connecting-Ip}
		}
		# From the home network Caddy sets X-Forwarded-For to the client itself.
		reverse_proxy 127.0.0.1:8080
	}

	handle {
		rewrite /f/* /f/index.html
		try_files {path} {path}.html {path}/index.html
		error @admin_outside 404
		file_server
	}

	handle_errors {
		@api_missing {
			path /api/*
			expression {http.error.status_code} == 404
		}
		handle @api_missing {
			header Content-Type "application/json; charset=utf-8"
			respond `{"error":"This address doesn't exist."}` 404
		}
		@api path /api/*
		handle @api {
			header Content-Type "application/json; charset=utf-8"
			respond `{"error":"The server is taking a short break. Try again in a moment."}` 503
		}
		handle {
			rewrite * /404.html
			file_server
		}
	}
}
```

Notes:

- Inside each `handle`, Caddy orders `error` before `reverse_proxy` and `file_server`, so blocked admin requests never reach Node or the admin files. `try_files` turns `/admin` into `/admin/index.html` first; the matcher `/admin*` still covers it.
- `Cf-Connecting-Ip` only counts when the request comes from cloudflared on the container itself. A PC on the home network that sends the header gets its real address passed on, so it can't dodge the per-address limits (registrations, wrong codes, the admin lockout).
- Errors from Node (JSON bodies) pass through untouched; `handle_errors` only answers when Caddy itself fails, for example while the service restarts.
- `/fonts/*` never change and are cached for a year. `/assets/*` is cached for a week, so a changed stylesheet or script doesn't stick around for long; if `site/build.mjs` puts content hashes into asset file names, give `/assets/*` the fonts line too. Pages and `release.json` are revalidated on every load.
- The CSP allows no inline scripts, inline styles or `style` attributes, and no other origins. Downloads from GitHub are plain links and not affected.

Apply and test:

```sh
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && systemctl reload caddy
curl -s -o /dev/null -w '%{http_code}\n' -H 'Cf-Connecting-Ip: 203.0.113.9' http://127.0.0.1/api/v1/admin/session   # 404 (from the container itself)
curl -s http://192.168.1.234/api/v1/admin/session   # from a PC at home: {"error":"Please sign in."}
curl -s https://slime.hyprnode.de/api/v1/admin/session -o /dev/null -w '%{http_code}\n'   # 404
curl -sI https://slime.hyprnode.de/ | grep -i -E 'content-security|referrer|permissions'
```

## 7. The service

For reference, the unit at `/etc/systemd/system/slimy.service`:

```ini
[Unit]
Description=Slimy API
After=network-online.target
Wants=network-online.target

[Service]
User=slimy
Group=slimy
WorkingDirectory=/opt/slimy/server
EnvironmentFile=/etc/slimy/slimy.env
ExecStart=/usr/bin/node --no-warnings src/index.js
Restart=on-failure
RestartSec=2
UMask=0027
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ReadWritePaths=/var/lib/slimy
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=yes
LockPersonality=yes
SystemCallArchitectures=native
CapabilityBoundingSet=

[Install]
WantedBy=multi-user.target
```

`ProtectSystem=strict` keeps `/etc/slimy` read-only for the service, which is all it needs. Node only logs its start line and errors, never request or chat content.

## 8. Smoke test

From any PC (needs `jq`). It registers a throwaway device, talks once, and deletes it again:

```sh
API=https://slime.hyprnode.de/api/v1
curl -s $API/health | jq                                   # "chat": true
T=$(curl -s -X POST $API/devices -H 'Content-Type: application/json' -d '{"ownerName":"Smoke test"}' | jq -r .token)
curl -s -X POST $API/chat -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"message":"hello!","slime":{"name":"Blob","favorite":"cake"}}' | jq
curl -s -X POST $API/devices -H 'Content-Type: application/json' -d '{"ownerName":"f u c k"}' | jq   # "Please choose a different name."
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $API/me -H "Authorization: Bearer $T"   # 204
```

The admin's *Chat* numbers show the request under the provider that answered. A failure counted under `cloudflare` usually means the token from section 3 can't run models; repeat the check in section 3, step 6.
