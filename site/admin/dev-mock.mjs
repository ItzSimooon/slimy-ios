// Development-only stand-in for /api/v1/admin/* with made-up data in memory.
// The site build leaves dev-mock.mjs files out of dist; the dev server
// (site/tools/dev-server.mjs) calls handle() before serving static files.
//
//   Passphrase:  slime-admin-dev   (or SLIMY_ADMIN_MOCK_PASSWORD)
//   SLIMY_ADMIN_MOCK=unset         every admin route answers 503 "isn't set up"
//   SLIMY_ADMIN_MOCK=forbidden     every admin route answers 403, like a client outside ADMIN_NETWORKS
//   SLIMY_ADMIN_MOCK_DELAY=<ms>    fixed latency (default 120-360 ms random)
//
// Shapes and status codes follow server/src/admin.js: opaque device cursors,
// bannedAt, resolvedAt, reporter { id: null, ownerName: null } once deleted,
// 409 on resolving twice, games.plays7d always null, care.pairs and
// carePartner (1.1). Banning or deleting a device ends its care pair.

import { randomBytes } from "node:crypto";

const PASSPHRASE = process.env.SLIMY_ADMIN_MOCK_PASSWORD || "slime-admin-dev";
const NOT_SET_UP = process.env.SLIMY_ADMIN_MOCK === "unset";
const FORBIDDEN = process.env.SLIMY_ADMIN_MOCK === "forbidden";
const FIXED_DELAY = process.env.SLIMY_ADMIN_MOCK_DELAY;
const PREFIX = "/api/v1/admin";
const DAY = 86400000;
const STARTED = Date.now();

/* ---------- Deterministic fake data ---------- */

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(20261026);
const pick = (list) => list[Math.floor(rand() * list.length)];
const int = (min, max) => min + Math.floor(rand() * (max - min + 1));

const ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
const newId = () => {
  let out = "dev_";
  for (let i = 0; i < 12; i++) out += ID_ALPHABET[Math.floor(rand() * ID_ALPHABET.length)];
  return out;
};

const iso = (ms) => (ms == null ? null : new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z"));
const dayKey = (ms) => new Date(ms).toISOString().slice(0, 10);
const stageFor = (level) => (level < 4 ? "baby" : level < 12 ? "young" : "grown");

const OWNERS = [
  "Mia", "Jonas", "Lea", "Finn", "Emma", "Noah", "Sofia", "Ben", "Hannah", "Paul", "Lina", "Elias", "Clara", "Luis",
  "Ida", "Felix", "Marie", "Leon", "Emilia", "Theo", "Mila", "Anton", "Ella", "Oskar", "Frieda", "Karl", "Nora",
  "Jakob", "Lotta", "Moritz", "Zoe", "Samuel", "Amelie", "Henry", "Leni", "David", "Mara", "Julian", "Sara", "Tim",
  "Aylin", "Can", "Yusuf", "Elif", "Mateo", "Lucia", "Kenji", "Aiko", "Priya", "Arjun", "Olivia", "Liam", "Chloe",
  "Ethan", "Grace", "Jack", "Ruby", "Leo", "Isla", "Max",
];
const SLIMES = [
  "Mochi", "Blobby", "Pudding", "Gloop", "Jelly", "Bean", "Kiwi", "Bubbles", "Noodle", "Pickle", "Squish", "Wobble",
  "Gummy", "Marshmallow", "Dumpling", "Tofu", "Pebble", "Sprout", "Biscuit", "Nugget", "Peaches", "Waffles", "Muffin",
  "Olive", "Pistachio", "Clover", "Boba", "Taro", "Matcha", "Sunny", "Slurp", "Jellybean", "Goo Goo", "Blub",
];
const SKINS = ["lime", "lime", "lime", "bubblegum", "ocean", "grape", "lava", "gold", "ghost", "void"];

const now0 = Date.now();
const devices = [];
for (let i = 0; i < 164; i++) {
  const createdAt = now0 - Math.floor(rand() ** 1.6 * 120 * DAY) - int(0, 3600000);
  const activity = rand();
  const lastSeenAt = activity < 0.04 ? null
    : Math.min(now0 - int(30, 600) * 1000, createdAt + Math.floor((now0 - createdAt) * (activity < 0.5 ? 0.97 + rand() * 0.03 : rand())));
  const noSlime = i % 47 === 5;
  const level = noSlime ? null : Math.max(1, Math.min(42, Math.round(((now0 - createdAt) / DAY) * (0.15 + rand() * 0.4)) + int(0, 3)));
  devices.push({
    id: newId(),
    ownerName: rand() < 0.12 ? "Slime friend" : pick(OWNERS),
    slimeName: noSlime ? null : pick(SLIMES),
    skin: noSlime ? null : pick(SKINS),
    level,
    createdAt,
    lastSeenAt,
    banned: false,
    bannedAt: null,
  });
}
// A few hand-picked devices the reports below point at.
const special = {
  spammer: { ownerName: "FREE COINS DM ME", slimeName: "Coinz", skin: "gold", level: 2 },
  rude: { ownerName: "Kevin", slimeName: "Butthead", skin: "lava", level: 9 },
  nightOwl: { ownerName: "Max", slimeName: "Shadow", skin: "void", level: 17 },
  bannedOne: { ownerName: "Tobi", slimeName: "Gloop", skin: "ocean", level: 6, banned: true },
  renamed: { ownerName: "Slime friend", slimeName: "Slime", skin: "bubblegum", level: 12 },
  requester: { ownerName: "Jonas", slimeName: "Rocket", skin: "lime", level: 5 },
};
const specialIds = {};
Object.entries(special).forEach(([key, value], i) => {
  const d = devices[10 + i * 7];
  Object.assign(d, value);
  d.lastSeenAt = now0 - int(2, 400) * 60000;
  if (d.banned) d.bannedAt = now0 - 380 * 3600000 + 5 * 3600000;
  specialIds[key] = d.id;
});

// Two devices found the crate-only colour.
devices[3].skin = "axolotl";
devices[41].skin = "axolotl";

const byId = new Map(devices.map((d) => [d.id, d]));

// Friendships: pairs with love 0-100; full love can mean "in love".
const friendships = [];
const pairKey = (a, b) => (a < b ? `${a}|${b}` : `${b}|${a}`);
const seen = new Set();
for (let i = 0; i < 230; i++) {
  const a = pick(devices);
  const b = pick(devices);
  if (a === b || seen.has(pairKey(a.id, b.id))) continue;
  seen.add(pairKey(a.id, b.id));
  const love = Math.min(100, Math.floor(rand() ** 0.7 * 100));
  friendships.push({ a: a.id, b: b.id, love, inLove: love >= 92 && rand() < 0.7 });
}

// Care pairs (1.1): two devices sharing one slime. A separate random stream,
// so the data above stays the same. The first pair includes a hand-picked
// device, so its page shows a partner.
const careRand = mulberry32(11);
const carePairs = [];
{
  const taken = new Set();
  const candidates = devices.filter((d) => d.level != null && !d.banned);
  const free = (d) => d && !taken.has(d.id);
  const add = (host, joiner) => {
    if (!free(host) || !free(joiner) || host === joiner) return;
    taken.add(host.id);
    taken.add(joiner.id);
    carePairs.push({ host: host.id, joiner: joiner.id });
  };
  add(byId.get(specialIds.requester), candidates[5]);
  for (let i = 0; i < 40 && carePairs.length < 17; i++) {
    add(candidates[Math.floor(careRand() * candidates.length)], candidates[Math.floor(careRand() * candidates.length)]);
  }
}
const carePairOf = (id) => carePairs.find((p) => p.host === id || p.joiner === id) ?? null;
function carePartnerOf(id) {
  const pair = carePairOf(id);
  if (!pair) return null;
  const other = byId.get(pair.host === id ? pair.joiner : pair.host);
  return other ? { id: other.id, ownerName: other.ownerName } : null;
}
function endCare(id) {
  const pair = carePairOf(id);
  if (pair) carePairs.splice(carePairs.indexOf(pair), 1);
}

// Best scores per game, like PUT /me/records stores them. A separate random
// stream, so the devices, friendships and reports below stay the same.
const GAMES = ["snackCatcher", "slimeJump", "bubblePop", "memoryGarden", "slimeRacing", "rhythmBounce", "gardenPuzzle",
  "treasureDig", "basketball", "colorMatch", "slimeStack", "jellyDash"];
const recordRand = mulberry32(7);
const recordInt = (min, max) => min + Math.floor(recordRand() * (max - min + 1));

let nextReportId = 1;
const reports = [];
function addReport({ hoursAgo, reason, note = null, text = null, reporter, reported = null, resolution = null }) {
  const createdAt = now0 - Math.round(hoursAgo * 3600000);
  reports.push({
    id: nextReportId++,
    createdAt,
    reason,
    note,
    text,
    status: resolution ? "resolved" : "open",
    resolution,
    resolvedAt: resolution ? createdAt + Math.round(Math.min(hoursAgo, 30) * 0.4 * 3600000) : null,
    reporterId: reporter,
    reportedId: reported,
  });
}
const anyone = () => pick(devices).id;
addReport({ hoursAgo: 380, reason: "harassment", note: "Kept sending me challenges every few minutes.", reporter: anyone(), reported: specialIds.bannedOne, resolution: "ban" });
addReport({ hoursAgo: 300, reason: "other", note: "I added the wrong person by accident.", reporter: anyone(), reported: anyone(), resolution: "dismiss" });
addReport({ hoursAgo: 250, reason: "name", note: "The name was a rude word.", reporter: anyone(), reported: specialIds.renamed, resolution: "rename" });
addReport({ hoursAgo: 190, reason: "chat", note: "Not a nice thing to say to a kid.", text: "Homework is boring. Let's skip it and play all night!", reporter: anyone(), resolution: "dismiss" });
addReport({ hoursAgo: 150, reason: "spam", reporter: anyone(), reported: anyone(), resolution: "dismiss" });
addReport({ hoursAgo: 96, reason: "spam", note: "The name is an ad.", reporter: anyone(), reported: specialIds.spammer });
addReport({ hoursAgo: 71, reason: "spam", note: "Sends friend requests to everyone in my class.", reporter: anyone(), reported: specialIds.requester });
addReport({ hoursAgo: 49, reason: "name", note: "Rude slime name.", reporter: anyone(), reported: specialIds.rude });
addReport({ hoursAgo: 30, reason: "chat", text: "Sleep is for people without slimes. Stay up with me!", reporter: anyone() });
addReport({ hoursAgo: 20, reason: "harassment", note: "Sends “Miss you” at 3 am every night, even after I asked them to stop.", reporter: anyone(), reported: specialIds.nightOwl });
addReport({ hoursAgo: 9, reason: "name", reporter: anyone(), reported: specialIds.spammer });
addReport({ hoursAgo: 4.5, reason: "other", note: "I think this is my old phone that was stolen. Its slime still has my name.", reporter: anyone(), reported: anyone() });
addReport({ hoursAgo: 0.6, reason: "chat", note: "My slime said this out of nowhere.", text: "Blub! Did you know slimes can eat homework? I tried. It tasted like math.", reporter: anyone() });
// This reporter deleted their data since; the server then keeps the report without a reporter id.
reports[1].reporterId = null;

for (const d of devices) {
  d.records = {};
  if (d.level == null) continue;
  for (const game of GAMES) if (recordRand() < 0.45) d.records[game] = recordInt(3, 60) * recordInt(1, 40);
}

// Chat usage per day and provider for the last 90 days.
const PROVIDERS = [
  { name: "cloudflare", model: "@cf/meta/llama-3.3-70b-instruct-fp8-fast", configured: true },
  { name: "groq", model: "openai/gpt-oss-20b", configured: true },
  { name: "custom", model: null, configured: false },
];
const usage = new Map();
for (let back = 89; back >= 0; back--) {
  const ms = now0 - back * DAY;
  const key = dayKey(ms);
  const weekday = new Date(ms).getUTCDay();
  const weekend = weekday === 0 || weekday === 6 ? 1.35 : 1;
  const growth = 0.55 + ((89 - back) / 89) * 0.75;
  const partial = back === 0 ? Math.max(0.15, (now0 % DAY) / DAY) : 1;
  const cfReq = Math.round((70 + rand() * 40) * weekend * growth * partial);
  const outage = rand() < 0.05;
  const cfFail = outage ? Math.round(cfReq * (0.2 + rand() * 0.3)) : Math.round(rand() ** 3 * 4 * partial);
  const groqReq = cfFail + Math.round(rand() * 6 * partial);
  const groqFail = groqReq > 0 && rand() < 0.25 ? int(1, Math.max(1, Math.min(3, groqReq))) : 0;
  usage.set(`${key}|cloudflare`, { day: key, provider: "cloudflare", requests: cfReq, failures: cfFail });
  if (groqReq > 0) usage.set(`${key}|groq`, { day: key, provider: "groq", requests: groqReq, failures: groqFail });
}

const EVENTS_24H = 1284;
const PAIR_CODES_24H = 23;
// The server doesn't see single rounds, so it always sends null.
const PLAYS_7D = null;

/* ---------- Views of the data ---------- */

function friendsOf(id) {
  return friendships.filter((f) => f.a === id || f.b === id);
}

function deviceJson(d) {
  return {
    id: d.id,
    ownerName: d.ownerName,
    slimeName: d.slimeName,
    skin: d.skin,
    stage: d.level == null ? null : stageFor(d.level),
    level: d.level,
    createdAt: iso(d.createdAt),
    lastSeenAt: iso(d.lastSeenAt),
    friends: friendsOf(d.id).length,
    banned: d.banned,
    bannedAt: iso(d.bannedAt),
    reportsAgainst: reports.filter((r) => r.reportedId === d.id).length,
  };
}

function reportJson(r) {
  const reporter = r.reporterId ? byId.get(r.reporterId) : null;
  const reported = r.reportedId ? byId.get(r.reportedId) : null;
  return {
    id: r.id,
    createdAt: iso(r.createdAt),
    reason: r.reason,
    note: r.note,
    text: r.text,
    status: r.status,
    resolution: r.resolution,
    resolvedAt: iso(r.resolvedAt),
    // Like the server: a deleted reporter leaves { id: null, ownerName: null }.
    reporter: { id: reporter ? r.reporterId : null, ownerName: reporter?.ownerName ?? null },
    reported: reported ? { id: reported.id, ownerName: reported.ownerName, slimeName: reported.slimeName, banned: reported.banned } : null,
  };
}

/** Resolves open reports that match, the way the server's resolveOpen() does. */
function resolveOpen(match, resolution) {
  for (const r of reports) {
    if (r.status !== "open" || !match(r)) continue;
    r.status = "resolved";
    r.resolution = resolution;
    r.resolvedAt = Date.now();
  }
}

function stats() {
  const now = Date.now();
  const within = (ms, span) => ms != null && now - ms <= span;
  const today = dayKey(now);
  const byProvider = {};
  let reqToday = 0;
  let failToday = 0;
  for (const u of usage.values()) {
    if (u.day !== today) continue;
    byProvider[u.provider] = { requests: u.requests, failures: u.failures };
    reqToday += u.requests;
    failToday += u.failures;
  }
  const last7 = [];
  for (let back = 6; back >= 0; back--) {
    const key = dayKey(now - back * DAY);
    let requests = 0;
    let failures = 0;
    for (const p of PROVIDERS) {
      const u = usage.get(`${key}|${p.name}`);
      if (u) {
        requests += u.requests;
        failures += u.failures;
      }
    }
    last7.push({ day: key, requests, failures });
  }
  return {
    devices: {
      total: devices.length,
      active1d: devices.filter((d) => within(d.lastSeenAt, DAY)).length,
      active7d: devices.filter((d) => within(d.lastSeenAt, 7 * DAY)).length,
      active30d: devices.filter((d) => within(d.lastSeenAt, 30 * DAY)).length,
      new7d: devices.filter((d) => within(d.createdAt, 7 * DAY)).length,
      banned: devices.filter((d) => d.banned).length,
    },
    friendships: { total: friendships.length, inLove: friendships.filter((f) => f.inLove).length },
    care: { pairs: carePairs.length },
    events24h: EVENTS_24H,
    pairCodes24h: PAIR_CODES_24H,
    reports: { open: reports.filter((r) => r.status === "open").length, total: reports.length },
    chat: { today: { requests: reqToday, failures: failToday, byProvider }, last7 },
    games: { plays7d: PLAYS_7D },
    db: { bytes: 3_482_624 + devices.length * 2048 },
    server: { version: "1.1.0", uptimeSec: Math.floor((Date.now() - STARTED) / 1000) + 3 * 86400 + 4 * 3600, node: process.version },
    providers: PROVIDERS,
  };
}

/* ---------- HTTP ---------- */

const sessions = new Map();
const failedLogins = new Map();

function send(res, status, body, headers = {}) {
  const payload = body === undefined ? "" : JSON.stringify(body);
  res.writeHead(status, {
    "Cache-Control": "no-store",
    ...(body === undefined ? {} : { "Content-Type": "application/json; charset=utf-8" }),
    ...headers,
  });
  res.end(payload);
}

const fail = (res, status, error) => send(res, status, { error });

function cookies(req) {
  const out = {};
  for (const part of (req.headers.cookie ?? "").split(";")) {
    const i = part.indexOf("=");
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}

/** The session's expiry time in ms, or 0 when not signed in. */
function signedIn(req) {
  const token = cookies(req).slimy_admin;
  const expires = token && sessions.get(token);
  if (!expires) return 0;
  if (expires < Date.now()) {
    sessions.delete(token);
    return 0;
  }
  return expires;
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 32 * 1024) return { error: [413, "The request is too large."] };
    chunks.push(chunk);
  }
  if (!size) return { body: {} };
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error("not an object");
    return { body: parsed };
  } catch {
    return { error: [400, "The request isn't valid JSON."] };
  }
}

const delay = () => new Promise((resolve) => {
  setTimeout(resolve, FIXED_DELAY != null ? Number(FIXED_DELAY) || 0 : 120 + Math.random() * 240);
});

const clampLimit = (value) => Math.max(1, Math.min(100, Number.parseInt(value ?? "50", 10) || 50));

async function route(req, res, url) {
  await delay();
  const path = url.pathname.slice(PREFIX.length) || "/";
  const method = req.method ?? "GET";

  if (FORBIDDEN) return fail(res, 403, "The admin is only reachable from the home network.");
  if (NOT_SET_UP) return fail(res, 503, "Admin isn't set up. Run: node src/admin-cli.js passwd");

  if (method !== "GET" && req.headers["x-slimy-admin"] !== "1") {
    return fail(res, 403, "This request is missing the X-Slimy-Admin header.");
  }

  if (path === "/login" && method === "POST") {
    const ip = req.socket.remoteAddress ?? "local";
    const recent = (failedLogins.get(ip) ?? []).filter((t) => Date.now() - t < 15 * 60000);
    if (recent.length >= 5) return fail(res, 429, "Too many wrong passphrases. Try again in 15 minutes.");
    const { body, error } = await readJson(req);
    if (error) return fail(res, ...error);
    if (typeof body.password !== "string" || body.password !== PASSPHRASE) {
      recent.push(Date.now());
      failedLogins.set(ip, recent);
      return fail(res, 401, "That passphrase isn't right.");
    }
    failedLogins.delete(ip);
    const token = randomBytes(24).toString("base64url");
    sessions.set(token, Date.now() + 43200 * 1000);
    const secure = req.headers["x-forwarded-proto"] === "https" ? "; Secure" : "";
    return send(res, 204, undefined, { "Set-Cookie": `slimy_admin=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200${secure}` });
  }

  if (path === "/logout" && method === "POST") {
    const token = cookies(req).slimy_admin;
    if (token) sessions.delete(token);
    return send(res, 204, undefined, { "Set-Cookie": "slimy_admin=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0" });
  }

  const expires = signedIn(req);
  if (!expires) return fail(res, 401, "Please sign in.");

  if (path === "/session" && method === "GET") return send(res, 200, { ok: true, expiresAt: iso(expires) });
  if (path === "/stats" && method === "GET") return send(res, 200, stats());

  if (path === "/reports" && method === "GET") {
    const status = url.searchParams.get("status") ?? "open";
    if (!["open", "resolved", "all"].includes(status)) return fail(res, 400, "Status must be open, resolved or all.");
    const limit = clampLimit(url.searchParams.get("limit"));
    const before = Number.parseInt(url.searchParams.get("before") ?? "", 10);
    const list = reports
      .filter((r) => status === "all" || r.status === status)
      .filter((r) => !Number.isFinite(before) || r.id < before)
      .sort((a, b) => b.id - a.id);
    const page = list.slice(0, limit);
    return send(res, 200, { reports: page.map(reportJson), next: list.length > limit ? page[page.length - 1].id : null });
  }

  let m = path.match(/^\/reports\/([^/]+)\/resolve$/);
  if (m && method === "POST") {
    const report = reports.find((r) => String(r.id) === decodeURIComponent(m[1]));
    if (!report) return fail(res, 404, "That report doesn't exist.");
    const { body, error } = await readJson(req);
    if (error) return fail(res, ...error);
    const action = body.action;
    if (!["dismiss", "rename", "ban"].includes(action)) return fail(res, 400, "Action must be dismiss, rename or ban.");
    if (report.status === "resolved") return fail(res, 409, "This report is already resolved.");
    const target = report.reportedId ? byId.get(report.reportedId) : null;
    if (action !== "dismiss" && !target) return fail(res, 409, "This report isn't about a device, so there's nobody to rename or ban.");
    // Same cascade as the server: a rename also closes the device's open name
    // reports, a ban closes every open report against it.
    if (action === "rename") {
      target.ownerName = "Slime friend";
      if (target.slimeName != null) target.slimeName = "Slime";
      resolveOpen((r) => r.reportedId === target.id && r.reason === "name", "rename");
    }
    if (action === "ban") {
      if (!target.banned) target.bannedAt = Date.now();
      target.banned = true;
      endCare(target.id);
      resolveOpen((r) => r.reportedId === target.id, "ban");
    }
    resolveOpen((r) => r === report, action);
    return send(res, 200, { report: reportJson(report) });
  }

  if (path === "/devices" && method === "GET") {
    const q = (url.searchParams.get("q") ?? "").trim().toLowerCase();
    const limit = clampLimit(url.searchParams.get("limit"));
    const before = url.searchParams.get("before");
    const bannedOnly = url.searchParams.get("banned") === "1";
    let list = devices
      .filter((d) => !q || d.id.toLowerCase().startsWith(q) || d.ownerName.toLowerCase().includes(q)
        || (d.slimeName ?? "").toLowerCase().includes(q))
      .filter((d) => !bannedOnly || d.banned)
      .sort((a, b) => b.createdAt - a.createdAt || (a.id < b.id ? 1 : -1));
    // Like the server: an opaque "<createdAt ms>.<id>" cursor.
    if (before) {
      const match = /^(\d{1,15})\.([A-Za-z0-9_]{1,40})$/.exec(before);
      if (!match) return fail(res, 400, "\"before\" isn't a valid cursor.");
      const at = Number(match[1]);
      list = list.filter((d) => d.createdAt < at || (d.createdAt === at && d.id < match[2]));
    }
    const page = list.slice(0, limit);
    const last = page[page.length - 1];
    return send(res, 200, { devices: page.map(deviceJson), next: list.length > limit ? `${last.createdAt}.${last.id}` : null });
  }

  m = path.match(/^\/devices\/([^/]+)(?:\/(ban|unban|rename))?$/);
  if (m) {
    const device = byId.get(decodeURIComponent(m[1]));
    if (!device) return fail(res, 404, "That device doesn't exist.");
    const action = m[2];
    if (!action && method === "GET") {
      const friends = friendsOf(device.id).map((f) => {
        const other = byId.get(f.a === device.id ? f.b : f.a);
        return { id: other.id, ownerName: other.ownerName, slimeName: other.slimeName, love: f.love, inLove: f.inLove, banned: other.banned };
      }).sort((a, b) => Number(b.inLove) - Number(a.inLove) || b.love - a.love);
      const against = reports.filter((r) => r.reportedId === device.id).sort((a, b) => b.id - a.id).map(reportJson);
      return send(res, 200, {
        device: deviceJson(device),
        carePartner: carePartnerOf(device.id),
        friends,
        reports: against,
        reportsFiled: reports.filter((r) => r.reporterId === device.id).length,
        records: Object.entries(device.records ?? {}).sort(([a], [b]) => (a < b ? -1 : 1)).map(([game, best]) => ({ game, best })),
      });
    }
    if (!action && method === "DELETE") {
      endCare(device.id);
      devices.splice(devices.indexOf(device), 1);
      byId.delete(device.id);
      for (let i = friendships.length - 1; i >= 0; i--) {
        if (friendships[i].a === device.id || friendships[i].b === device.id) friendships.splice(i, 1);
      }
      // Reports stay and lose the device id (ON DELETE SET NULL on the server).
      for (const r of reports) {
        if (r.reportedId === device.id) r.reportedId = null;
        if (r.reporterId === device.id) r.reporterId = null;
      }
      return send(res, 204);
    }
    if (action && method === "POST") {
      if (action === "ban" && !device.banned) {
        device.banned = true;
        device.bannedAt = Date.now();
        endCare(device.id);
      }
      if (action === "unban") {
        device.banned = false;
        device.bannedAt = null;
      }
      if (action === "rename") {
        device.ownerName = "Slime friend";
        if (device.slimeName != null) device.slimeName = "Slime";
      }
      return send(res, 200, { device: deviceJson(device) });
    }
  }

  if (path === "/chat-usage" && method === "GET") {
    const days = Math.max(1, Math.min(90, Number.parseInt(url.searchParams.get("days") ?? "30", 10) || 30));
    const from = dayKey(Date.now() - (days - 1) * DAY);
    const list = [...usage.values()].filter((u) => u.day >= from).sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0));
    return send(res, 200, { days: list });
  }

  return fail(res, 404, "There's nothing here.");
}

/**
 * Handles /api/v1/admin/* and returns true; returns false for anything else
 * so the dev server can serve it. `url` may be a URL, a string or omitted.
 */
export function handle(req, res, url) {
  let parsed;
  try {
    parsed = url instanceof URL ? url : new URL(typeof url === "string" ? url : req.url ?? "/", "http://localhost");
  } catch {
    return false;
  }
  if (parsed.pathname !== PREFIX && !parsed.pathname.startsWith(`${PREFIX}/`)) return false;
  route(req, res, parsed).catch(() => {
    if (!res.headersSent) fail(res, 500, "Something went wrong on the server. Try again later.");
  });
  return true;
}

export default handle;
