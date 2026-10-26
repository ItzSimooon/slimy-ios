#!/bin/bash
# Slimy: chat keys and admin passphrase. Run as root on CT 108:  slimy-setup
# Keys are typed hidden, checked against Cloudflare and Groq, then written to
# /etc/slimy/slimy.env (0640 root:slimy). Nothing is printed or logged.
set -u
ENV=/etc/slimy/slimy.env
ACCOUNT=$(grep -E '^CF_ACCOUNT_ID=' "$ENV" | cut -d= -f2-)
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; }

set_key() { # name value
  local tmp; tmp=$(mktemp)
  grep -v -E "^$1=" "$ENV" > "$tmp"
  printf '%s=%s\n' "$1" "$2" >> "$tmp"
  cat "$tmp" > "$ENV"; rm -f "$tmp"
  chown root:slimy "$ENV"; chmod 0640 "$ENV"
}

ask_key() { # prompt pattern -> REPLY_KEY (empty = skip)
  local v
  while true; do
    read -rsp "$1 (leer lassen = überspringen): " v; echo
    v=$(printf '%s' "$v" | tr -d '[:space:]')
    [ -z "$v" ] && { REPLY_KEY=""; return; }
    if [[ "$v" =~ $2 ]]; then REPLY_KEY="$v"; return; fi
    bad "Das sieht nicht wie ein gültiger Key aus. Nochmal einfügen."
  done
}

echo
echo "== 1/3  Cloudflare Workers AI =="
ask_key "Cloudflare API-Token" '^[A-Za-z0-9_-]{30,}$'
if [ -n "$REPLY_KEY" ]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
    "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/ai/v1/chat/completions" \
    -H "Authorization: Bearer $REPLY_KEY" -H 'Content-Type: application/json' \
    -d '{"model":"@cf/meta/llama-3.3-70b-instruct-fp8-fast","messages":[{"role":"user","content":"say hi"}],"max_tokens":5}')
  if [ "$code" = 200 ]; then set_key CF_AI_TOKEN "$REPLY_KEY"; ok "Token gespeichert, Modell antwortet."
  else bad "Cloudflare antwortet mit $code. Token nicht gespeichert (Rechte: Vorlage \"Workers AI\", richtiges Konto?)."; fi
fi

echo
echo "== 2/3  Groq =="
ask_key "Groq API-Key" '^gsk_[A-Za-z0-9]{20,}$'
if [ -n "$REPLY_KEY" ]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
    https://api.groq.com/openai/v1/models -H "Authorization: Bearer $REPLY_KEY")
  if [ "$code" = 200 ]; then set_key GROQ_API_KEY "$REPLY_KEY"; ok "Key gespeichert."
  else bad "Groq antwortet mit $code. Key nicht gespeichert."; fi
fi
unset REPLY_KEY

systemctl restart slimy; sleep 2
echo
echo "== Chat-Test über den Server =="
API=http://127.0.0.1:8080/api/v1
if curl -s "$API/health" | grep -q '"chat":true'; then
  T=$(curl -s -X POST "$API/devices" -H 'Content-Type: application/json' -d '{"ownerName":"Setup test"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  R=$(curl -s --max-time 45 -X POST "$API/chat" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
      -d '{"message":"hi! how are you?","history":[],"slime":{"name":"Blob"}}')
  curl -s -o /dev/null -X DELETE "$API/me" -H "Authorization: Bearer $T"
  echo "  Blob sagt: $R"
  sqlite3 /var/lib/slimy/slimy.db "select '  heute: '||provider||' '||requests||' Anfragen, '||failures||' Fehler' from chat_usage where day=date('now');" 2>/dev/null
else
  bad "Chat ist aus: kein gültiger Key gespeichert."
fi

echo
echo "== 3/3  Admin-Passphrase =="
if [ -f /etc/slimy/admin.hash ]; then
  read -rp "Es gibt schon eine Passphrase. Ändern? [j/N] " a
  [[ "$a" =~ ^[jJyY] ]] || { echo; echo "Admin: http://192.168.1.234/admin/  (nur Heimnetz/Tailscale)"; exit 0; }
fi
(cd /opt/slimy/server && node src/admin-cli.js passwd)
echo
echo "Admin: http://192.168.1.234/admin/  (nur Heimnetz/Tailscale)"
