import { after, beforeEach, describe, test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../src/app.js";
import { createChat } from "../src/chat.js";
import { openDatabase } from "../src/db.js";
import { groupId, hashPassphrase, parseHash, passphraseProblem, verifyPassphrase, writeHashFile } from "../src/passphrase.js";
import { inNetworks, parseNetworks } from "../src/admin.js";

// The admin API over real HTTP, with a passphrase file in a temp folder.

const PASSPHRASE = "correct horse battery staple";
const dir = mkdtempSync(join(tmpdir(), "slimy-admin-"));
const hashFile = join(dir, "admin.hash");
const hashLine = await hashPassphrase(PASSPHRASE);
after(() => rmSync(dir, { recursive: true, force: true }));

let server, base, db, app, now;

async function start({ file = hashFile, trustProxy = false, networks = null } = {}) {
  if (server) await new Promise((r) => server.close(r));
  now = Date.UTC(2026, 9, 25, 12, 0, 0);
  db = openDatabase(":memory:");
  const chat = createChat({ env: { GROQ_API_KEY: "k", CF_ACCOUNT_ID: "a", CF_AI_TOKEN: "t" }, fetch: async () => new Response("{}"), log: () => {} });
  app = createApp({ db, clock: () => now, chat, trustProxy, adminHashFile: file, adminNetworks: networks, serverVersion: "1.2.3" });
  server = createServer(app.listener);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}/api/v1`;
}

async function call(method, path, { body, cookie, csrf = method !== "GET", headers = {}, token } = {}) {
  const h = { "Content-Type": "application/json", ...headers };
  if (csrf) h["X-Slimy-Admin"] = "1";
  if (cookie) h.Cookie = cookie;
  if (token) h.Authorization = `Bearer ${token}`;
  const res = await fetch(base + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, headers: res.headers, body: text ? JSON.parse(text) : null };
}

async function login(password = PASSPHRASE, extra = {}) {
  const r = await call("POST", "/admin/login", { body: { password }, ...extra });
  const setCookie = r.headers.get("set-cookie");
  return { ...r, setCookie, cookie: setCookie ? setCookie.split(";")[0] : null };
}

async function device(name = "Simon") {
  const r = await call("POST", "/devices", { body: { ownerName: name }, csrf: false });
  await call("PUT", "/me/slime", { token: r.body.token, csrf: false, body: { name: `${name}s Blob`, skin: "ocean", level: 13, mood: "idle", happiness: 80 } });
  now += 1000;
  return r.body;
}

async function befriend(x, y) {
  const code = (await call("POST", "/pair-codes", { token: x.token, csrf: false, body: {} })).body.code;
  const join = await call("POST", "/friend-requests", { token: y.token, csrf: false, body: { code } });
  await call("POST", `/friend-requests/${join.body.request.id}/accept`, { token: x.token, csrf: false, body: {} });
}

const report = (from, to, reason = "name", extra = {}) =>
  call("POST", "/reports", { token: from.token, csrf: false, body: { deviceId: to?.deviceId, reason, ...extra } });

beforeEach(async () => {
  writeFileSync(hashFile, `${hashLine}\n`);
  await start();
});
after(() => server?.close());

describe("passphrase file", () => {
  test("hash lines use scrypt N=2^15, r=8, p=1 and a 64-byte key", async () => {
    const parsed = parseHash(hashLine);
    assert.equal(parsed.N, 32768);
    assert.equal(parsed.r, 8);
    assert.equal(parsed.p, 1);
    assert.equal(parsed.key.length, 64);
    assert.equal(parsed.salt.length, 16);
    assert.ok(await verifyPassphrase(PASSPHRASE, hashLine));
    assert.equal(await verifyPassphrase("wrong", hashLine), false);
    assert.notEqual(await hashPassphrase(PASSPHRASE), hashLine);
    assert.equal(parseHash("plain text"), null);
    assert.equal(parseHash("scrypt$3$8$1$AAAA$BBBB"), null);
  });

  test("the CLI writes the file atomically with mode 0640", async () => {
    const target = join(dir, "nested", "admin.hash");
    const line = await hashPassphrase("a brand new passphrase");
    const { mode } = writeHashFile(target, line);
    assert.equal(readFileSync(target, "utf8"), `${line}\n`);
    if (process.platform !== "win32") assert.equal(mode, "0640");
    writeHashFile(target, hashLine);
    assert.equal(readFileSync(target, "utf8").trim(), hashLine);
    assert.deepEqual(readdirSync(join(dir, "nested")), ["admin.hash"]);
  });

  test("group ids come from the group file", () => {
    const groups = join(dir, "group");
    writeFileSync(groups, "root:x:0:\nslimy:x:997:\nbroken\n");
    assert.equal(groupId("slimy", groups), 997);
    assert.equal(groupId("nobody", groups), null);
    assert.equal(groupId("slimy", join(dir, "no-such-file")), null);
  });

  test("new passphrases need 12 characters", () => {
    assert.match(passphraseProblem("short"), /12 characters/);
    assert.match(passphraseProblem(" padded passphrase "), /space/);
    assert.equal(passphraseProblem(PASSPHRASE), null);
  });
});

describe("admin auth", () => {
  test("no hash file means 503 everywhere", async () => {
    await start({ file: join(dir, "missing.hash") });
    for (const [method, path] of [["GET", "/admin/session"], ["POST", "/admin/login"], ["GET", "/admin/stats"]]) {
      const r = await call(method, path, { body: method === "POST" ? { password: PASSPHRASE } : undefined });
      assert.equal(r.status, 503, path);
      assert.equal(r.body.error, "Admin isn't set up. Run: node src/admin-cli.js passwd");
    }
  });

  test("a broken hash file counts as not set up", async () => {
    writeFileSync(hashFile, "nonsense\n");
    assert.equal((await call("GET", "/admin/session")).status, 503);
  });

  test("a wrong passphrase is 401", async () => {
    const r = await login("not the passphrase");
    assert.equal(r.status, 401);
    assert.equal(r.setCookie, null);
    assert.equal((await call("POST", "/admin/login", { body: {} })).status, 400);
  });

  test("five wrong tries in 15 minutes lock the address out", async () => {
    for (let i = 0; i < 5; i++) assert.equal((await login("nope nope nope")).status, 401);
    const locked = await login();
    assert.equal(locked.status, 429);
    now += 15 * 60_000;
    assert.equal((await login()).status, 204);
  });

  test("parallel wrong guesses can't slip past the lockout", async () => {
    const results = await Promise.all(Array.from({ length: 12 }, () => login("nope nope nope")));
    const statuses = results.map((r) => r.status);
    const checked = statuses.filter((s) => s === 401).length;
    assert.ok(checked >= 1 && checked <= 5, statuses.join(","));
    assert.ok(statuses.every((s) => s === 401 || s === 429), statuses.join(","));
    // Only guesses that were really checked count; five of them lock the address.
    for (let i = checked; i < 5; i++) assert.equal((await login("nope nope nope")).status, 401);
    assert.equal((await login()).status, 429);
  });

  test("a right passphrase doesn't count as a wrong try", async () => {
    for (let i = 0; i < 4; i++) assert.equal((await login("nope nope nope")).status, 401);
    assert.equal((await login()).status, 204);
    assert.equal((await login()).status, 204);
    assert.equal((await login("nope nope nope")).status, 401);
    assert.equal((await login()).status, 429);
  });

  test("behind the proxy the lockout is per client address", async () => {
    await start({ trustProxy: true });
    const from = (ip) => ({ headers: { "X-Forwarded-For": ip } });
    for (let i = 0; i < 5; i++) await login("nope nope nope", from("192.168.1.20"));
    assert.equal((await login(PASSPHRASE, from("192.168.1.20"))).status, 429);
    assert.equal((await login(PASSPHRASE, from("192.168.1.21"))).status, 204);
  });

  test("login sets a session cookie that opens the admin", async () => {
    const r = await login();
    assert.equal(r.status, 204);
    assert.match(r.setCookie, /^slimy_admin=[A-Za-z0-9_-]{43}; HttpOnly; SameSite=Strict; Path=\/; Max-Age=43200$/);
    assert.doesNotMatch(r.setCookie, /Secure/);
    assert.equal(r.headers.get("cache-control"), "no-store");
    const session = await call("GET", "/admin/session", { cookie: r.cookie });
    assert.equal(session.status, 200);
    assert.equal(session.body.ok, true);
    assert.equal((await call("GET", "/admin/session")).status, 401);
    assert.equal((await call("GET", "/admin/session", { cookie: "slimy_admin=forged" })).status, 401);
    assert.equal((await call("GET", "/admin/stats", { cookie: "slimy_admin=forged" })).status, 401);
  });

  test("the cookie is Secure when the proxy says https", async () => {
    await start({ trustProxy: true });
    const r = await login(PASSPHRASE, { headers: { "X-Forwarded-Proto": "https", "X-Forwarded-For": "192.168.1.5" } });
    assert.match(r.setCookie, /; Secure$/);
  });

  test("sessions end after 12 hours, on logout and when the passphrase changes", async () => {
    let { cookie } = await login();
    now += 12 * 3600_000;
    assert.equal((await call("GET", "/admin/session", { cookie })).status, 401);

    ({ cookie } = await login());
    const out = await call("POST", "/admin/logout", { cookie });
    assert.equal(out.status, 204);
    assert.match(out.headers.get("set-cookie"), /^slimy_admin=; .*Max-Age=0/);
    assert.equal((await call("GET", "/admin/session", { cookie })).status, 401);

    ({ cookie } = await login());
    writeFileSync(hashFile, `${await hashPassphrase("another long passphrase")}\n`);
    assert.equal((await call("GET", "/admin/session", { cookie })).status, 401);
  });

  test("writes need the X-Slimy-Admin header", async () => {
    assert.equal((await call("POST", "/admin/login", { body: { password: PASSPHRASE }, csrf: false })).status, 403);
    const { cookie } = await login();
    const d = await device();
    const r = await call("POST", `/admin/devices/${d.deviceId}/ban`, { cookie, csrf: false });
    assert.equal(r.status, 403);
    assert.match(r.body.error, /X-Slimy-Admin/);
    assert.equal((await call("DELETE", `/admin/devices/${d.deviceId}`, { cookie, csrf: false })).status, 403);
    assert.equal((await call("POST", "/admin/logout", { cookie, csrf: false })).status, 403);
  });

  test("ADMIN_NETWORKS keeps everyone else out", async () => {
    await start({ trustProxy: true, networks: "192.168.1.0/24, 100.64.0.0/10" });
    const tunnel = await login(PASSPHRASE, { headers: { "X-Forwarded-For": "203.0.113.9" } });
    assert.equal(tunnel.status, 403);
    assert.equal((await login(PASSPHRASE, { headers: { "X-Forwarded-For": "100.101.102.103" } })).status, 204);
    assert.equal((await login(PASSPHRASE, { headers: { "X-Forwarded-For": "192.168.1.50" } })).status, 204);
  });

  test("network lists parse and match IPv4, IPv6 and mapped addresses", () => {
    const list = parseNetworks("192.168.1.0/24, 100.64.0.0/10, ::1, fd00::/8");
    assert.ok(inNetworks(list, "192.168.1.7"));
    assert.ok(inNetworks(list, "::ffff:192.168.1.7"));
    assert.ok(inNetworks(list, "100.127.255.255"));
    assert.ok(inNetworks(list, "::1"));
    assert.ok(inNetworks(list, "fd12::5"));
    assert.equal(inNetworks(list, "127.0.0.1"), false);
    assert.equal(inNetworks(list, "100.128.0.1"), false);
    assert.equal(inNetworks(list, "?"), false);
    assert.equal(parseNetworks(""), null);
    assert.throws(() => parseNetworks("not-an-ip"), /ADMIN_NETWORKS/);
    assert.throws(() => parseNetworks("10.0.0.0/33"), /prefix/);
  });
});

describe("admin endpoints", () => {
  let cookie;
  beforeEach(async () => {
    ({ cookie } = await login());
  });

  test("stats", async () => {
    const a = await device("Anna"), b = await device("Ben"), c = await device("Cleo");
    await befriend(a, b);
    db.prepare("UPDATE friendships SET in_love = 1").run();
    db.prepare("UPDATE devices SET banned_at = ? WHERE id = ?").run(now, c.deviceId);
    db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(now - 3 * 24 * 3600_000, b.deviceId);
    await report(a, b);
    db.prepare("INSERT INTO chat_usage (day, provider, requests, failures) VALUES ('2026-10-25', 'cloudflare', 10, 2), ('2026-10-25', 'groq', 4, 0), ('2026-10-20', 'groq', 7, 1), ('2026-10-01', 'groq', 99, 9)").run();
    const r = await call("GET", "/admin/stats", { cookie });
    assert.equal(r.status, 200);
    const s = r.body;
    assert.deepEqual(s.devices, { total: 3, active1d: 2, active7d: 3, active30d: 3, new7d: 3, banned: 1 });
    assert.deepEqual(s.friendships, { total: 1, inLove: 1 });
    assert.ok(s.events24h >= 1);
    assert.equal(s.pairCodes24h, 1);
    assert.deepEqual(s.reports, { open: 1, total: 1 });
    assert.deepEqual(s.chat.today, {
      requests: 14, failures: 2, byProvider: { cloudflare: { requests: 10, failures: 2 }, groq: { requests: 4, failures: 0 } },
    });
    assert.equal(s.chat.last7.length, 7);
    assert.deepEqual(s.chat.last7[0], { day: "2026-10-19", requests: 0, failures: 0 });
    assert.deepEqual(s.chat.last7[1], { day: "2026-10-20", requests: 7, failures: 1 });
    assert.deepEqual(s.chat.last7[6], { day: "2026-10-25", requests: 14, failures: 2 });
    assert.deepEqual(s.games, { plays7d: null });
    assert.ok(s.db.bytes > 0);
    assert.equal(s.server.version, "1.2.3");
    assert.equal(s.server.node, process.version);
    assert.equal(typeof s.server.uptimeSec, "number");
    assert.deepEqual(s.providers.map((p) => [p.name, p.configured]), [["cloudflare", true], ["groq", true], ["custom", false]]);
  });

  test("chat usage per day and provider", async () => {
    db.prepare("INSERT INTO chat_usage (day, provider, requests, failures) VALUES ('2026-10-25', 'groq', 4, 0), ('2026-10-24', 'cloudflare', 3, 1), ('2026-09-01', 'groq', 9, 9)").run();
    const r = await call("GET", "/admin/chat-usage?days=30", { cookie });
    assert.deepEqual(r.body.days, [
      { day: "2026-10-24", provider: "cloudflare", requests: 3, failures: 1 },
      { day: "2026-10-25", provider: "groq", requests: 4, failures: 0 },
    ]);
    assert.equal((await call("GET", "/admin/chat-usage?days=400", { cookie })).body.days.length, 3);
    assert.equal((await call("GET", "/admin/chat-usage?days=1", { cookie })).body.days.length, 1);
  });

  test("reports: list, filter, page", async () => {
    const a = await device("Anna"), b = await device("Ben");
    await report(a, b, "name", { note: "rude" });
    now += 1000;
    await report(a, null, "chat", { text: "a slime reply" });
    now += 1000;
    await report(b, a, "spam");
    const all = await call("GET", "/admin/reports?status=all&limit=2", { cookie });
    assert.equal(all.status, 200);
    assert.equal(all.body.reports.length, 2);
    assert.equal(all.body.reports[0].reason, "spam");
    assert.ok(all.body.next);
    const rest = await call("GET", `/admin/reports?status=all&limit=2&before=${all.body.next}`, { cookie });
    assert.equal(rest.body.reports.length, 1);
    assert.equal(rest.body.next, null);
    const first = rest.body.reports[0];
    assert.deepEqual(Object.keys(first).sort(), ["createdAt", "id", "note", "reason", "reported", "reporter", "resolution", "resolvedAt", "status", "text"]);
    assert.deepEqual(first.reporter, { id: a.deviceId, ownerName: "Anna" });
    assert.deepEqual(first.reported, { id: b.deviceId, ownerName: "Ben", slimeName: "Bens Blob", banned: false });
    assert.equal(first.note, "rude");
    assert.equal(first.status, "open");
    const chat = all.body.reports[1];
    assert.equal(chat.reported, null);
    assert.equal(chat.text, "a slime reply");
    assert.equal((await call("GET", "/admin/reports", { cookie })).body.reports.length, 3);
    assert.equal((await call("GET", "/admin/reports?status=resolved", { cookie })).body.reports.length, 0);
    assert.equal((await call("GET", "/admin/reports?status=maybe", { cookie })).status, 400);
    assert.equal((await call("GET", "/admin/reports?before=abc", { cookie })).status, 400);
  });

  test("reports: dismiss", async () => {
    const a = await device(), b = await device();
    await report(a, b, "spam");
    const id = db.prepare("SELECT id FROM reports").get().id;
    const r = await call("POST", `/admin/reports/${id}/resolve`, { cookie, body: { action: "dismiss" } });
    assert.equal(r.status, 200);
    assert.equal(r.body.report.status, "resolved");
    assert.equal(r.body.report.resolution, "dismiss");
    assert.match(r.body.report.resolvedAt, /Z$/);
    assert.equal((await call("POST", `/admin/reports/${id}/resolve`, { cookie, body: { action: "ban" } })).status, 409);
    assert.equal((await call("POST", `/admin/reports/9999/resolve`, { cookie, body: { action: "dismiss" } })).status, 404);
    assert.equal((await call("POST", `/admin/reports/${id}/resolve`, { cookie, body: { action: "explode" } })).status, 400);
  });

  test("reports: rename resets both names and the old ones can't come back", async () => {
    const a = await device("Anna"), b = await device("Rudename");
    await report(a, b, "name");
    const id = db.prepare("SELECT id FROM reports").get().id;
    const r = await call("POST", `/admin/reports/${id}/resolve`, { cookie, body: { action: "rename" } });
    assert.equal(r.body.report.resolution, "rename");
    assert.deepEqual(r.body.report.reported, { id: b.deviceId, ownerName: "Slime friend", slimeName: "Slime", banned: false });
    const profile = { skin: "lime", level: 2, mood: "idle", happiness: 50 };
    const again = await call("PUT", "/me/slime", { token: b.token, csrf: false, body: { ...profile, name: "Rudenames Blob" } });
    assert.equal(again.status, 400);
    assert.equal(again.body.error, "Please choose a different name.");
    assert.equal((await call("PUT", "/me/slime", { token: b.token, csrf: false, body: { ...profile, name: "Pebble", ownerName: "RUDENAME" } })).status, 400);
    assert.equal((await call("PUT", "/me/slime", { token: b.token, csrf: false, body: { ...profile, name: "Pebble", ownerName: "Rosa" } })).status, 204);
  });

  test("reports: ban hides the device and closes its other open reports", async () => {
    const a = await device("Anna"), b = await device("Ben"), c = await device("Cleo");
    await befriend(a, b);
    await report(a, b, "harassment");
    await report(c, b, "spam");
    await report(a, null, "chat", { text: "reply" });
    const id = db.prepare("SELECT id FROM reports WHERE reason = 'harassment'").get().id;
    const r = await call("POST", `/admin/reports/${id}/resolve`, { cookie, body: { action: "ban" } });
    assert.equal(r.body.report.reported.banned, true);
    assert.deepEqual(db.prepare("SELECT reason, status, resolution FROM reports ORDER BY id").all().map((x) => [x.reason, x.status, x.resolution]),
      [["harassment", "resolved", "ban"], ["spam", "resolved", "ban"], ["chat", "open", null]]);
    assert.equal((await call("GET", "/me", { token: b.token, csrf: false })).status, 403);
    assert.equal((await call("GET", "/friends", { token: a.token, csrf: false })).body.length, 0);
    const chatId = db.prepare("SELECT id FROM reports WHERE reason = 'chat'").get().id;
    assert.equal((await call("POST", `/admin/reports/${chatId}/resolve`, { cookie, body: { action: "ban" } })).status, 409);
  });

  test("devices: list, search, page", async () => {
    const anna = await device("Anna"), ben = await device("Ben"), cleo = await device("Cleo");
    await befriend(anna, ben);
    await report(cleo, ben, "spam");
    const r = await call("GET", "/admin/devices?limit=2", { cookie });
    assert.equal(r.status, 200);
    assert.deepEqual(r.body.devices.map((d) => d.ownerName), ["Cleo", "Ben"]);
    const benRow = r.body.devices[1];
    assert.deepEqual(Object.keys(benRow).sort(),
      ["banned", "bannedAt", "createdAt", "friends", "id", "lastSeenAt", "level", "ownerName", "reportsAgainst", "skin", "slimeName", "stage"]);
    assert.equal(benRow.slimeName, "Bens Blob");
    assert.equal(benRow.skin, "ocean");
    assert.equal(benRow.level, 13);
    assert.equal(benRow.stage, "grown");
    assert.equal(benRow.friends, 1);
    assert.equal(benRow.reportsAgainst, 1);
    assert.equal(benRow.banned, false);
    const next = await call("GET", `/admin/devices?limit=2&before=${encodeURIComponent(r.body.next)}`, { cookie });
    assert.deepEqual(next.body.devices.map((d) => d.ownerName), ["Anna"]);
    assert.equal(next.body.next, null);
    assert.deepEqual((await call("GET", "/admin/devices?q=bEn", { cookie })).body.devices.map((d) => d.ownerName), ["Ben"]);
    assert.deepEqual((await call("GET", "/admin/devices?q=annas%20blob", { cookie })).body.devices.map((d) => d.ownerName), ["Anna"]);
    assert.deepEqual((await call("GET", `/admin/devices?q=${cleo.deviceId.slice(0, 8)}`, { cookie })).body.devices.map((d) => d.id), [cleo.deviceId]);
    assert.equal((await call("GET", "/admin/devices?q=%25", { cookie })).body.devices.length, 0);
    assert.equal((await call("GET", "/admin/devices?before=nonsense", { cookie })).status, 400);
  });

  test("devices: detail", async () => {
    const anna = await device("Anna"), ben = await device("Ben");
    await befriend(anna, ben);
    await report(anna, ben, "name");
    await call("PUT", "/me/records", { token: ben.token, csrf: false, body: { records: { jellyDash: 42 } } });
    const r = await call("GET", `/admin/devices/${ben.deviceId}`, { cookie });
    assert.equal(r.status, 200);
    assert.equal(r.body.device.id, ben.deviceId);
    assert.deepEqual(r.body.friends, [{ id: anna.deviceId, ownerName: "Anna", slimeName: "Annas Blob", love: 0, inLove: false, banned: false }]);
    assert.equal(r.body.reports.length, 1);
    assert.equal(r.body.reports[0].reason, "name");
    assert.deepEqual(r.body.records, [{ game: "jellyDash", best: 42 }]);
    assert.equal((await call("GET", "/admin/devices/dev_nobody", { cookie })).status, 404);
  });

  test("devices: ban, unban, rename, delete", async () => {
    const anna = await device("Anna"), ben = await device("Ben");
    await befriend(anna, ben);
    await report(anna, ben, "spam");
    const code = (await call("POST", "/pair-codes", { token: ben.token, csrf: false, body: {} })).body.code;

    const banned = await call("POST", `/admin/devices/${ben.deviceId}/ban`, { cookie });
    assert.equal(banned.status, 200);
    assert.equal(banned.body.device.banned, true);
    assert.match(banned.body.device.bannedAt, /Z$/);
    assert.equal((await call("GET", `/invites/${code}`, { csrf: false })).status, 404);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM pair_codes WHERE device_id = ?").get(ben.deviceId).n, 0);
    assert.equal((await call("GET", "/me", { token: ben.token, csrf: false })).status, 403);
    // Banning doesn't resolve reports by itself; resolving through a report does.
    assert.equal(db.prepare("SELECT status FROM reports").get().status, "open");

    const unbanned = await call("POST", `/admin/devices/${ben.deviceId}/unban`, { cookie });
    assert.equal(unbanned.body.device.banned, false);
    assert.equal((await call("GET", "/me", { token: ben.token, csrf: false })).status, 200);
    assert.equal((await call("GET", "/friends", { token: anna.token, csrf: false })).body.length, 1);

    const renamed = await call("POST", `/admin/devices/${ben.deviceId}/rename`, { cookie });
    assert.equal(renamed.body.device.ownerName, "Slime friend");
    assert.equal(renamed.body.device.slimeName, "Slime");

    assert.equal((await call("POST", "/admin/devices/dev_nobody/ban", { cookie })).status, 404);

    assert.equal((await call("DELETE", `/admin/devices/${ben.deviceId}`, { cookie })).status, 204);
    assert.equal((await call("GET", "/me", { token: ben.token, csrf: false })).status, 401);
    assert.equal((await call("GET", "/friends", { token: anna.token, csrf: false })).body.length, 0);
    const kept = db.prepare("SELECT reporter, reported FROM reports").get();
    assert.deepEqual({ ...kept }, { reporter: anna.deviceId, reported: null });
    assert.equal((await call("DELETE", `/admin/devices/${ben.deviceId}`, { cookie })).status, 404);
  });

  test("every admin route needs a session and answers no-store", async () => {
    const d = await device();
    const routes = [
      ["GET", "/admin/stats"], ["GET", "/admin/reports"], ["POST", "/admin/reports/1/resolve"], ["GET", "/admin/devices"],
      ["GET", `/admin/devices/${d.deviceId}`], ["POST", `/admin/devices/${d.deviceId}/ban`], ["POST", `/admin/devices/${d.deviceId}/unban`],
      ["POST", `/admin/devices/${d.deviceId}/rename`], ["DELETE", `/admin/devices/${d.deviceId}`], ["GET", "/admin/chat-usage"],
    ];
    for (const [method, path] of routes) {
      const r = await call(method, path, { body: method === "POST" ? { action: "dismiss" } : undefined });
      assert.equal(r.status, 401, `${method} ${path}`);
      assert.equal(r.headers.get("cache-control"), "no-store");
      assert.equal(r.body.error, "Please sign in.");
    }
    const ok = await call("GET", "/admin/stats", { cookie });
    assert.equal(ok.headers.get("cache-control"), "no-store");
    assert.equal(ok.headers.get("x-robots-tag"), "noindex");
  });

  test("expired sessions are swept by the cleanup", async () => {
    const before = await call("GET", "/admin/session", { cookie });
    assert.equal(before.status, 200);
    now += 13 * 3600_000;
    app.cleanup();
    assert.equal((await call("GET", "/admin/session", { cookie })).status, 401);
  });
});

describe("admin and caring together", () => {
  let cookie;
  beforeEach(async () => {
    ({ cookie } = await login());
  });

  async function share(host, joiner) {
    const code = (await call("POST", "/care/codes", { token: host.token, csrf: false, body: {} })).body.code;
    const r = await call("POST", "/care/join", { token: joiner.token, csrf: false, body: { code } });
    assert.equal(r.status, 200);
  }

  const careOf = (d) => call("GET", "/care", { token: d.token, csrf: false });

  test("stats count the pairs", async () => {
    const a = await device("Anna"), b = await device("Ben"), c = await device("Cleo"), d = await device("Dora");
    assert.deepEqual((await call("GET", "/admin/stats", { cookie })).body.care, { pairs: 0 });
    await share(a, b);
    await share(c, d);
    assert.deepEqual((await call("GET", "/admin/stats", { cookie })).body.care, { pairs: 2 });
  });

  test("device detail names the care partner from either side", async () => {
    const anna = await device("Anna"), ben = await device("Ben"), cleo = await device("Cleo");
    await share(anna, ben);
    const host = await call("GET", `/admin/devices/${anna.deviceId}`, { cookie });
    assert.deepEqual(host.body.carePartner, { id: ben.deviceId, ownerName: "Ben" });
    const joiner = await call("GET", `/admin/devices/${ben.deviceId}`, { cookie });
    assert.deepEqual(joiner.body.carePartner, { id: anna.deviceId, ownerName: "Anna" });
    assert.equal((await call("GET", `/admin/devices/${cleo.deviceId}`, { cookie })).body.carePartner, null);
  });

  test("a ban unpairs the partner and removes the banned device's care codes", async () => {
    const anna = await device("Anna"), ben = await device("Ben"), cleo = await device("Cleo");
    await share(anna, ben);
    await call("PUT", "/care/snapshot", { token: anna.token, csrf: false, body: { snapshot: { name: "Blob" }, version: 1 } });
    await call("POST", "/care/events", { token: anna.token, csrf: false, body: { kind: "feed", at: "2026-10-25T12:00:00Z" } });
    const r = await call("POST", `/admin/devices/${anna.deviceId}/ban`, { cookie });
    assert.equal(r.status, 200);
    assert.equal((await careOf(ben)).body.partner, null);
    for (const table of ["care_pairs", "care_events"]) assert.equal(db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n, 0, table);
    assert.equal((await call("GET", `/admin/devices/${ben.deviceId}`, { cookie })).body.carePartner, null);
    // An unban doesn't bring the pair back.
    await call("POST", `/admin/devices/${anna.deviceId}/unban`, { cookie });
    assert.equal((await careOf(anna)).body.partner, null);
    // Banning through a report does the same, and the banned device's open code goes away.
    await share(ben, cleo);
    const code = (await call("POST", "/care/codes", { token: anna.token, csrf: false, body: {} })).body.code;
    await call("POST", "/reports", { token: ben.token, csrf: false, body: { deviceId: cleo.deviceId, reason: "harassment" } });
    const id = db.prepare("SELECT id FROM reports").get().id;
    await call("POST", `/admin/reports/${id}/resolve`, { cookie, body: { action: "ban" } });
    assert.equal((await careOf(ben)).body.partner, null);
    await call("POST", `/admin/devices/${anna.deviceId}/ban`, { cookie });
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM care_codes WHERE code = ?").get(code).n, 0);
  });

  test("deleting a device unpairs the partner", async () => {
    const anna = await device("Anna"), ben = await device("Ben");
    await share(anna, ben);
    assert.equal((await call("DELETE", `/admin/devices/${ben.deviceId}`, { cookie })).status, 204);
    assert.equal((await careOf(anna)).body.partner, null);
    assert.deepEqual((await call("GET", "/admin/stats", { cookie })).body.care, { pairs: 0 });
  });
});
