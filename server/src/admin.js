import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { BlockList, isIP } from "node:net";
import { transaction } from "./db.js";
import { DEFAULT_HASH_FILE, MAX_LENGTH, parseHash, verifyPassphrase } from "./passphrase.js";
import { DAY, HOUR } from "./ratelimit.js";
import { dayKey, fail, iso, oneOf, sha256 } from "./util.js";

// The admin API behind the website's /admin page: sign in with the
// passphrase whose scrypt hash the owner wrote with `node src/admin-cli.js
// passwd`, then look at numbers, handle reports and ban, rename or delete
// devices. The reverse proxy only lets the home network and Tailscale reach
// these routes; ADMIN_NETWORKS can enforce the same here as a second lock.

export const ADMIN_COOKIE = "slimy_admin";
export const SESSION_SECONDS = 12 * 3600;
export { DEFAULT_HASH_FILE };
export const RENAMED = { ownerName: "Slime friend", slimeName: "Slime" };

const NOT_SET_UP = "Admin isn't set up. Run: node src/admin-cli.js passwd";
const LOGIN_WINDOW = 15 * 60_000;
const MAX_SESSIONS = 50;
const MAX_VERIFYING = 4;

/** Parses "192.168.1.0/24, 100.64.0.0/10, ::1" into a BlockList; null when empty. */
export function parseNetworks(spec) {
  const entries = String(spec ?? "").split(",").map((part) => part.trim()).filter(Boolean);
  if (!entries.length) return null;
  const list = new BlockList();
  for (const entry of entries) {
    const [address, prefixText] = entry.split("/");
    const version = isIP(address);
    if (!version) throw new Error(`ADMIN_NETWORKS: "${entry}" isn't an IP address or network.`);
    const max = version === 6 ? 128 : 32;
    const prefix = prefixText === undefined ? max : Number(prefixText);
    if (!Number.isInteger(prefix) || prefix < 0 || prefix > max) throw new Error(`ADMIN_NETWORKS: "${entry}" has an invalid prefix.`);
    list.addSubnet(address, prefix, version === 6 ? "ipv6" : "ipv4");
  }
  return list;
}

/** True when `ip` is inside `list`. IPv4-mapped IPv6 addresses count as IPv4. */
export function inNetworks(list, ip) {
  let address = String(ip ?? "").replace(/%.*$/, "");
  if (/^::ffff:\d+\.\d+\.\d+\.\d+$/i.test(address)) address = address.slice(7);
  const version = isIP(address);
  return Boolean(version) && list.check(address, version === 6 ? "ipv6" : "ipv4");
}

function cookies(header) {
  const out = {};
  for (const part of String(header ?? "").split(";")) {
    const at = part.indexOf("=");
    if (at > 0) out[part.slice(0, at).trim()] = part.slice(at + 1).trim();
  }
  return out;
}

const clampInt = (value, min, max, fallback) => {
  const n = Number.parseInt(value ?? "", 10);
  return Number.isInteger(n) ? Math.max(min, Math.min(max, n)) : fallback;
};

export function createAdmin({
  db, clock, limiter, route, deleteDevice, endCare = () => false, stageFor, chat,
  hashFile = DEFAULT_HASH_FILE, networks = null, trustProxy = false, serverVersion = "1.0.0", startedAt = clock(),
  log = (line) => console.error(line),
}) {
  const q = (sql) => db.prepare(sql);
  const sessions = new Map();
  let warnedBadHash = false;
  let verifying = 0;

  // --- Guards ------------------------------------------------------------------

  /** The current hash line, or null when there is none (or it's unreadable). */
  function hashLine() {
    let line;
    try {
      line = readFileSync(hashFile, "utf8").trim();
    } catch (error) {
      if (error.code !== "ENOENT") log(`admin: can't read ${hashFile} (${error.code ?? error.message})`);
      return null;
    }
    if (!parseHash(line)) {
      if (!warnedBadHash) log(`admin: ${hashFile} isn't a passphrase hash; run node src/admin-cli.js passwd again`);
      warnedBadHash = true;
      return null;
    }
    warnedBadHash = false;
    return line;
  }

  function currentSession(ctx, line) {
    const token = cookies(ctx.headers.cookie)[ADMIN_COOKIE];
    if (!token) return null;
    const id = sha256(token);
    const session = sessions.get(id);
    if (!session) return null;
    // A new passphrase ends every session made with the old one.
    if (session.expires <= clock() || session.hash !== sha256(line)) {
      sessions.delete(id);
      return null;
    }
    return { id, ...session };
  }

  function cookie(ctx, token, maxAge) {
    const proto = String(ctx.headers["x-forwarded-proto"] ?? "").split(",")[0].trim().toLowerCase();
    const secure = trustProxy && proto === "https" ? "; Secure" : "";
    ctx.responseHeaders["Set-Cookie"] = `${ADMIN_COOKIE}=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}${secure}`;
  }

  /** Every admin route: network, set up, CSRF header for writes, then the session. */
  const adminRoute = (method, pattern, handler, { session = true } = {}) => route(method, `/admin${pattern}`, async (ctx) => {
    ctx.responseHeaders["X-Robots-Tag"] = "noindex";
    if (networks && !inNetworks(networks, ctx.ip)) fail(403, "The admin is only reachable from the home network.");
    if (!limiter.hit("admin", ctx.ip, 1200, HOUR)) fail(429, "Too many requests. Wait a moment and try again.");
    const line = hashLine();
    if (!line) fail(503, NOT_SET_UP);
    if (method !== "GET" && ctx.headers["x-slimy-admin"] !== "1") fail(403, "This request is missing the X-Slimy-Admin header.");
    ctx.hashLine = line;
    if (session) {
      ctx.session = currentSession(ctx, line);
      if (!ctx.session) fail(401, "Please sign in.");
    }
    return handler(ctx);
  }, { auth: false });

  // --- Views -------------------------------------------------------------------

  const REPORT_SQL = `
    SELECT r.*, rd.owner_name AS reporter_name, td.owner_name AS reported_owner, ts.name AS reported_slime,
      td.banned_at AS reported_banned
    FROM reports r
    LEFT JOIN devices rd ON rd.id = r.reporter
    LEFT JOIN devices td ON td.id = r.reported
    LEFT JOIN slimes ts ON ts.device_id = r.reported`;

  function reportView(r) {
    return {
      id: r.id,
      createdAt: iso(r.created_at),
      reason: r.reason,
      note: r.note,
      text: r.text,
      status: r.status,
      resolution: r.resolution,
      resolvedAt: iso(r.resolved_at),
      reporter: { id: r.reporter, ownerName: r.reporter_name ?? null },
      reported: r.reported
        ? { id: r.reported, ownerName: r.reported_owner, slimeName: r.reported_slime ?? RENAMED.slimeName, banned: r.reported_banned != null }
        : null,
    };
  }

  const reportById = (id) => q(`${REPORT_SQL} WHERE r.id = ?`).get(id);

  const DEVICE_SQL = `
    SELECT d.id, d.owner_name, d.created_at, d.last_seen_at, d.banned_at, s.name AS slime_name, s.skin, s.level,
      (SELECT COUNT(*) FROM friendships f WHERE f.a = d.id OR f.b = d.id) AS friends,
      (SELECT COUNT(*) FROM reports r WHERE r.reported = d.id) AS reports_against
    FROM devices d LEFT JOIN slimes s ON s.device_id = d.id`;

  function deviceView(d) {
    const level = d.level ?? 1;
    return {
      id: d.id,
      ownerName: d.owner_name,
      slimeName: d.slime_name ?? RENAMED.slimeName,
      skin: d.skin ?? "lime",
      stage: stageFor(level),
      level,
      createdAt: iso(d.created_at),
      lastSeenAt: iso(d.last_seen_at),
      friends: d.friends,
      banned: d.banned_at != null,
      bannedAt: iso(d.banned_at),
      reportsAgainst: d.reports_against,
    };
  }

  function requireDevice(id) {
    const row = q(`${DEVICE_SQL} WHERE d.id = ?`).get(id);
    if (!row) fail(404, "This device doesn't exist.");
    return row;
  }

  // --- Moderation actions (callers wrap them in a transaction) -----------------

  function ban(id) {
    q("UPDATE devices SET banned_at = COALESCE(banned_at, ?) WHERE id = ?").run(clock(), id);
    q("DELETE FROM pair_codes WHERE device_id = ?").run(id);
    q("DELETE FROM care_codes WHERE device_id = ?").run(id);
    q("DELETE FROM friend_requests WHERE from_device = ? OR to_device = ?").run(id, id);
    // Whoever cared for a slime with it just becomes unpaired; an unban doesn't bring the pair back.
    endCare(id);
  }

  function unban(id) {
    q("UPDATE devices SET banned_at = NULL WHERE id = ?").run(id);
  }

  /** Resets both names and remembers the old ones so the app can't simply upload them again. */
  function rename(id) {
    const d = q("SELECT owner_name, blocked_names FROM devices WHERE id = ?").get(id);
    const s = q("SELECT name FROM slimes WHERE device_id = ?").get(id);
    const blocked = new Set(d.blocked_names ? JSON.parse(d.blocked_names) : []);
    for (const name of [d.owner_name, s?.name]) {
      const key = nameKey(name);
      if (key && name !== RENAMED.ownerName && name !== RENAMED.slimeName && name !== "Someone") blocked.add(key);
    }
    q("UPDATE devices SET owner_name = ?, blocked_names = ? WHERE id = ?")
      .run(RENAMED.ownerName, JSON.stringify([...blocked].slice(-20)), id);
    q("UPDATE slimes SET name = ?, updated_at = ? WHERE device_id = ?").run(RENAMED.slimeName, clock(), id);
  }

  function resolveOpen(where, params, resolution) {
    q(`UPDATE reports SET status = 'resolved', resolution = ?, resolved_at = ? WHERE status = 'open' AND ${where}`)
      .run(resolution, clock(), ...params);
  }

  // --- Session -------------------------------------------------------------------

  adminRoute("POST", "/login", async (ctx) => {
    if (limiter.exhausted("admin-login-fail", ctx.ip, 5)) fail(429, "Too many wrong passphrases. Try again in 15 minutes.");
    if (!limiter.hit("admin-login", ctx.ip, 30, LOGIN_WINDOW)) fail(429, "Too many sign-in attempts. Try again in 15 minutes.");
    const password = ctx.body.password;
    if (typeof password !== "string" || !password || password.length > MAX_LENGTH) fail(400, "Enter the admin passphrase.");
    // Each check takes 32 MB of memory for a moment; a few at a time is plenty.
    if (verifying >= MAX_VERIFYING) fail(429, "Too many sign-in attempts. Try again in a moment.");
    // Counted as wrong before checking, so parallel guesses can't slip past the lockout.
    if (!limiter.hit("admin-login-fail", ctx.ip, 5, LOGIN_WINDOW)) fail(429, "Too many wrong passphrases. Try again in 15 minutes.");
    verifying += 1;
    let ok;
    try {
      ok = await verifyPassphrase(password, ctx.hashLine);
    } finally {
      verifying -= 1;
    }
    if (!ok) fail(401, "That passphrase isn't right.");
    limiter.undo("admin-login-fail", ctx.ip);
    const token = randomBytes(32).toString("base64url");
    sessions.set(sha256(token), { expires: clock() + SESSION_SECONDS * 1000, hash: sha256(ctx.hashLine), createdAt: clock() });
    while (sessions.size > MAX_SESSIONS) sessions.delete(sessions.keys().next().value);
    cookie(ctx, token, SESSION_SECONDS);
    return null;
  }, { session: false });

  adminRoute("POST", "/logout", (ctx) => {
    const token = cookies(ctx.headers.cookie)[ADMIN_COOKIE];
    if (token) sessions.delete(sha256(token));
    cookie(ctx, "", 0);
    return null;
  }, { session: false });

  adminRoute("GET", "/session", (ctx) => ({ ok: true, expiresAt: iso(ctx.session.expires) }));

  // --- Numbers -------------------------------------------------------------------

  adminRoute("GET", "/stats", () => {
    const now = clock();
    const count = (sql, ...params) => q(sql).get(...params).n;
    const today = dayKey(now);
    const byProvider = {};
    let requests = 0;
    let failures = 0;
    for (const row of q("SELECT provider, requests, failures FROM chat_usage WHERE day = ? ORDER BY provider").all(today)) {
      byProvider[row.provider] = { requests: row.requests, failures: row.failures };
      requests += row.requests;
      failures += row.failures;
    }
    const sums = new Map(q("SELECT day, SUM(requests) AS requests, SUM(failures) AS failures FROM chat_usage WHERE day >= ? GROUP BY day")
      .all(dayKey(now - 6 * DAY)).map((row) => [row.day, row]));
    const last7 = [];
    for (let i = 6; i >= 0; i--) {
      const day = dayKey(now - i * DAY);
      last7.push({ day, requests: sums.get(day)?.requests ?? 0, failures: sums.get(day)?.failures ?? 0 });
    }
    const pages = q("PRAGMA page_count").get().page_count;
    const pageSize = q("PRAGMA page_size").get().page_size;
    return {
      devices: {
        total: count("SELECT COUNT(*) AS n FROM devices"),
        active1d: count("SELECT COUNT(*) AS n FROM devices WHERE last_seen_at >= ?", now - DAY),
        active7d: count("SELECT COUNT(*) AS n FROM devices WHERE last_seen_at >= ?", now - 7 * DAY),
        active30d: count("SELECT COUNT(*) AS n FROM devices WHERE last_seen_at >= ?", now - 30 * DAY),
        new7d: count("SELECT COUNT(*) AS n FROM devices WHERE created_at >= ?", now - 7 * DAY),
        banned: count("SELECT COUNT(*) AS n FROM devices WHERE banned_at IS NOT NULL"),
      },
      friendships: {
        total: count("SELECT COUNT(*) AS n FROM friendships"),
        inLove: count("SELECT COUNT(*) AS n FROM friendships WHERE in_love = 1"),
      },
      care: { pairs: count("SELECT COUNT(*) AS n FROM care_pairs") },
      events24h: count("SELECT COUNT(*) AS n FROM events WHERE created_at >= ?", now - DAY),
      pairCodes24h: count("SELECT COUNT(*) AS n FROM pair_codes WHERE created_at >= ?", now - DAY),
      reports: {
        open: count("SELECT COUNT(*) AS n FROM reports WHERE status = 'open'"),
        total: count("SELECT COUNT(*) AS n FROM reports"),
      },
      chat: { today: { requests, failures, byProvider }, last7 },
      // The server doesn't see single rounds, only records and challenges.
      games: { plays7d: null },
      db: { bytes: pages * pageSize },
      server: { version: serverVersion, uptimeSec: Math.max(0, Math.floor((clock() - startedAt) / 1000)), node: process.version },
      providers: chat?.describe?.() ?? [],
    };
  });

  adminRoute("GET", "/chat-usage", ({ query }) => {
    const days = clampInt(query.get("days"), 1, 400, 30);
    const rows = q("SELECT day, provider, requests, failures FROM chat_usage WHERE day >= ? ORDER BY day, provider")
      .all(dayKey(clock() - (days - 1) * DAY));
    return { days: rows.map((r) => ({ day: r.day, provider: r.provider, requests: r.requests, failures: r.failures })) };
  });

  // --- Reports -------------------------------------------------------------------

  adminRoute("GET", "/reports", ({ query }) => {
    const status = query.get("status") ?? "open";
    if (!["open", "resolved", "all"].includes(status)) fail(400, '"status" must be open, resolved or all.');
    const limit = clampInt(query.get("limit"), 1, 100, 50);
    const beforeText = query.get("before");
    const before = beforeText == null || beforeText === "" ? null : Number.parseInt(beforeText, 10);
    if (before !== null && !Number.isSafeInteger(before)) fail(400, '"before" must be a report id.');
    const rows = q(`${REPORT_SQL} WHERE (? = 'all' OR r.status = ?) AND (? IS NULL OR r.id < ?) ORDER BY r.id DESC LIMIT ?`)
      .all(status, status, before, before, limit + 1);
    const page = rows.slice(0, limit);
    return { reports: page.map(reportView), next: rows.length > limit ? page.at(-1).id : null };
  });

  adminRoute("POST", "/reports/:id/resolve", ({ params, body }) => {
    const id = /^\d{1,15}$/.test(params.id) ? Number(params.id) : null;
    const action = oneOf(body.action, "action", ["dismiss", "rename", "ban"]);
    return transaction(db, () => {
      const report = Number.isSafeInteger(id) ? q("SELECT * FROM reports WHERE id = ?").get(id) : null;
      if (!report) fail(404, "This report doesn't exist.");
      if (report.status !== "open") fail(409, "This report is already resolved.");
      if (action !== "dismiss" && !report.reported) fail(409, "This report isn't about a device, so there's nobody to rename or ban.");
      if (action === "rename") {
        rename(report.reported);
        resolveOpen("reported = ? AND reason = 'name'", [report.reported], "rename");
      }
      if (action === "ban") {
        ban(report.reported);
        resolveOpen("reported = ?", [report.reported], "ban");
      }
      resolveOpen("id = ?", [id], action);
      return { report: reportView(reportById(id)) };
    });
  });

  // --- Devices -------------------------------------------------------------------

  adminRoute("GET", "/devices", ({ query }) => {
    const limit = clampInt(query.get("limit"), 1, 100, 50);
    const search = String(query.get("q") ?? "").trim().slice(0, 40);
    const like = search ? search.replace(/[\\%_]/g, (c) => `\\${c}`) : null;
    const cursor = query.get("before");
    let beforeAt = null;
    let beforeId = null;
    if (cursor) {
      const match = /^(\d{1,15})\.([A-Za-z0-9_]{1,40})$/.exec(cursor);
      if (!match) fail(400, '"before" isn\'t a valid cursor.');
      beforeAt = Number(match[1]);
      beforeId = match[2];
    }
    const bannedOnly = query.get("banned") === "1" ? 1 : 0;
    const rows = q(`${DEVICE_SQL}
      WHERE (? IS NULL OR d.id LIKE ? ESCAPE '\\' OR d.owner_name LIKE ? ESCAPE '\\' OR s.name LIKE ? ESCAPE '\\')
        AND (? IS NULL OR d.created_at < ? OR (d.created_at = ? AND d.id < ?))
        AND (? = 0 OR d.banned_at IS NOT NULL)
      ORDER BY d.created_at DESC, d.id DESC LIMIT ?`)
      .all(like, like && `${like}%`, like && `%${like}%`, like && `%${like}%`,
        beforeAt, beforeAt, beforeAt, beforeId, bannedOnly, limit + 1);
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return { devices: page.map(deviceView), next: rows.length > limit ? `${last.created_at}.${last.id}` : null };
  });

  adminRoute("GET", "/devices/:id", ({ params }) => {
    const device = requireDevice(params.id);
    const friends = q(`
      SELECT f.love, f.in_love, d.id, d.owner_name, d.banned_at, s.name AS slime_name
      FROM friendships f
      JOIN devices d ON d.id = CASE WHEN f.a = ? THEN f.b ELSE f.a END
      LEFT JOIN slimes s ON s.device_id = d.id
      WHERE f.a = ? OR f.b = ?
      ORDER BY f.in_love DESC, f.love DESC`).all(device.id, device.id, device.id);
    const care = q(`
      SELECT d.id, d.owner_name FROM care_pairs p
      JOIN devices d ON d.id = CASE WHEN p.host = ? THEN p.joiner ELSE p.host END
      WHERE p.host = ? OR p.joiner = ?`).get(device.id, device.id, device.id);
    return {
      device: deviceView(device),
      carePartner: care ? { id: care.id, ownerName: care.owner_name } : null,
      friends: friends.map((f) => ({
        id: f.id, ownerName: f.owner_name, slimeName: f.slime_name ?? RENAMED.slimeName,
        love: f.love, inLove: f.in_love === 1, banned: f.banned_at != null,
      })),
      reports: q(`${REPORT_SQL} WHERE r.reported = ? ORDER BY r.id DESC LIMIT 50`).all(device.id).map(reportView),
      reportsFiled: q("SELECT COUNT(*) AS n FROM reports WHERE reporter = ?").get(device.id).n,
      records: q("SELECT game, best FROM records WHERE device_id = ? ORDER BY game").all(device.id)
        .map((r) => ({ game: r.game, best: r.best })),
    };
  });

  const deviceAction = (name, action) => adminRoute("POST", `/devices/:id/${name}`, ({ params }) => {
    transaction(db, () => {
      requireDevice(params.id);
      action(params.id);
    });
    return { device: deviceView(requireDevice(params.id)) };
  });
  deviceAction("ban", ban);
  deviceAction("unban", unban);
  deviceAction("rename", rename);

  adminRoute("DELETE", "/devices/:id", ({ params }) => {
    requireDevice(params.id);
    deleteDevice(params.id);
    return null;
  });

  function sweep() {
    const now = clock();
    for (const [id, session] of sessions) if (session.expires <= now) sessions.delete(id);
  }

  return { sweep, sessions };
}

/** How names are compared when an admin has reset them: folded, letters and digits only. */
export function nameKey(name) {
  return String(name ?? "").normalize("NFKD").replace(/\p{M}+/gu, "").toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");
}
