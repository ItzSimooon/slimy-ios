import { schemaVersion, transaction } from "./db.js";
import { DAY, HOUR, RateLimiter } from "./ratelimit.js";
import {
  ENUMS, HttpError, bool, dayKey, fail, int, iso, newId, newPairCode, newToken, oneOf, pair, sha256,
  startOfUtcDay, text, weekKey,
} from "./util.js";

// The Slimy API. Everything about the slime itself (needs, coins, levels)
// lives on the phone; the server is the authority for who is friends with
// whom, how much the slimes love each other, and what friends send.
//
// No event a friend sends can do more than show something on the other
// phone: a toast, a moment in the island, a gift of food, a challenge.

export const LIMITS = {
  maxFriends: 20,
  codeMinutes: 10,
  eventDays: 30,
  challengeDays: 3,
  coopGoal: 1000,
  coopPerCall: 300,
  coopPerDay: 900,
};

/** Love per interaction, and how often each counts per friend and day. */
export const LOVE = {
  hug: { gain: 3, perDay: 3 },
  visit: { gain: 5, perDay: 1 },
  gift: { gain: 6, perDay: 3 },
  kiss: { gain: 8, perDay: 2, minLove: 50 },
};

const CAPPED = {
  hug: "That's enough hugs for today. More tomorrow.",
  visit: "You already hung out today. More tomorrow.",
  gift: "That's enough gifts for today. More tomorrow.",
  kiss: "That's enough smooches for today.",
};

export function createApp({ db, clock = () => Date.now(), chat, trustProxy = false, version = "1" }) {
  const limiter = new RateLimiter(clock);
  const q = (sql) => db.prepare(sql);

  // --- Helpers ---------------------------------------------------------------

  function limit(bucket, key, max, windowMs, message = "Too many requests. Wait a moment and try again.") {
    if (!limiter.hit(bucket, key, max, windowMs)) fail(429, message);
  }

  function slimeOf(deviceId, viewer) {
    const s = q("SELECT * FROM slimes WHERE device_id = ?").get(deviceId);
    const d = q("SELECT owner_name, share_mood FROM devices WHERE id = ?").get(deviceId);
    if (!d) return null;
    const share = d.share_mood === 1 || viewer === deviceId;
    return {
      ownerName: d.owner_name,
      name: s?.name ?? "Slime",
      skin: s?.skin ?? "lime",
      level: s?.level ?? 1,
      stage: s ? stageFor(s.level) : "baby",
      mood: share ? s?.mood ?? "idle" : "idle",
      happiness: share ? s?.happiness ?? null : null,
      equipped: s?.equipped ? JSON.parse(s.equipped) : null,
      updatedAt: iso(s?.updated_at ?? clock()),
    };
  }

  function friendship(me, other) {
    const [a, b] = pair(me, other);
    return q("SELECT * FROM friendships WHERE a = ? AND b = ?").get(a, b);
  }

  function requireFriend(me, other) {
    const f = friendship(me, other);
    if (!f) fail(404, "You're not friends (anymore).");
    return f;
  }

  function friendView(me, row) {
    const other = row.a === me ? row.b : row.a;
    const slime = slimeOf(other, me);
    return {
      id: other,
      ownerName: slime.ownerName,
      slime,
      love: row.love,
      inLove: row.in_love === 1,
      since: iso(row.created_at),
    };
  }

  function isBlocked(x, y) {
    return Boolean(q("SELECT 1 FROM blocks WHERE (blocker = ? AND blocked = ?) OR (blocker = ? AND blocked = ?)").get(x, y, y, x));
  }

  function emit(to, from, kind, data = null) {
    q("INSERT INTO events (to_device, from_device, kind, data, created_at) VALUES (?, ?, ?, ?, ?)")
      .run(to, from, kind, data == null ? null : JSON.stringify(data), clock());
  }

  function remember(x, y, kind, detail = null) {
    const [a, b] = pair(x, y);
    q("INSERT OR IGNORE INTO memories (a, b, kind, detail, created_at) VALUES (?, ?, ?, ?, ?)").run(a, b, kind, detail, clock());
  }

  function friendCount(id) {
    return q("SELECT COUNT(*) AS n FROM friendships WHERE a = ? OR b = ?").get(id, id).n;
  }

  function befriend(x, y) {
    const [a, b] = pair(x, y);
    q("INSERT OR IGNORE INTO friendships (a, b, created_at) VALUES (?, ?, ?)").run(a, b, clock());
    q("DELETE FROM friend_requests WHERE (from_device = ? AND to_device = ?) OR (from_device = ? AND to_device = ?)").run(x, y, y, x);
    remember(x, y, "met");
  }

  function requestView(row, me) {
    const other = row.from_device === me ? row.to_device : row.from_device;
    const slime = slimeOf(other, me);
    return { id: row.id, deviceId: other, ownerName: slime?.ownerName ?? "Jemand", slime, createdAt: iso(row.created_at) };
  }

  function challengeView(row, me) {
    const mine = row.from_device === me;
    const other = mine ? row.to_device : row.from_device;
    return {
      id: row.id,
      game: row.game,
      seed: row.seed,
      friendId: other,
      friendName: slimeOf(other, me)?.name ?? "Slime",
      sentByMe: mine,
      myScore: mine ? row.from_score : row.to_score,
      theirScore: mine ? row.to_score : row.from_score,
      createdAt: iso(row.created_at),
      expiresAt: iso(row.expires_at),
      answeredAt: iso(row.answered_at),
    };
  }

  function coopView(me, other, week = weekKey(clock())) {
    const [a, b] = pair(me, other);
    const row = q("SELECT * FROM coop WHERE a = ? AND b = ? AND week = ?").get(a, b, week);
    const mine = row ? (me === a ? row.points_a : row.points_b) : 0;
    const theirs = row ? (me === a ? row.points_b : row.points_a) : 0;
    return { week, goal: LIMITS.coopGoal, mine, theirs, total: mine + theirs, done: Boolean(row?.done_at) };
  }

  function cleanup() {
    const now = clock();
    q("DELETE FROM events WHERE created_at < ?").run(now - LIMITS.eventDays * DAY);
    q("DELETE FROM pair_codes WHERE expires_at < ? OR used_at IS NOT NULL").run(now - DAY);
    q("DELETE FROM idempotency WHERE created_at < ?").run(now - 2 * DAY);
    q("DELETE FROM challenges WHERE expires_at < ?").run(now - 14 * DAY);
    q("DELETE FROM coop_daily WHERE day < ?").run(dayKey(now - 3 * DAY));
    limiter.sweep();
  }

  // --- Routes ----------------------------------------------------------------

  const routes = [];
  const route = (method, pattern, handler, { auth = true } = {}) => {
    const keys = [];
    const regex = new RegExp("^" + pattern.replace(/:(\w+)/g, (_, k) => { keys.push(k); return "([^/]+)"; }) + "$");
    routes.push({ method, regex, keys, handler, auth });
  };

  route("GET", "/health", () => {
    const ok = q("SELECT 1 AS ok").get().ok === 1;
    return { ok, version, schema: schemaVersion(db), chat: chat?.enabled ?? false };
  }, { auth: false });

  // Devices ------------------------------------------------------------------

  route("POST", "/devices", ({ body, ip }) => {
    limit("register", ip, 10, HOUR, "Too many new devices from here. Try again in an hour.");
    const ownerName = text(body.ownerName, "ownerName", { max: 24, fallback: "Someone" }) || "Someone";
    const id = newId("dev");
    const token = newToken();
    const now = clock();
    q("INSERT INTO devices (id, token_hash, owner_name, created_at, last_seen) VALUES (?, ?, ?, ?, ?)")
      .run(id, sha256(token), ownerName, now, now);
    return { deviceId: id, token };
  }, { auth: false });

  route("GET", "/me", ({ me }) => {
    const d = q("SELECT * FROM devices WHERE id = ?").get(me);
    return {
      deviceId: me,
      ownerName: d.owner_name,
      privacy: { acceptRequests: d.accept_requests === 1, shareMood: d.share_mood === 1 },
      friends: friendCount(me),
    };
  });

  route("DELETE", "/me", ({ me }) => {
    transaction(db, () => {
      q("DELETE FROM memories WHERE a = ? OR b = ?").run(me, me);
      q("DELETE FROM coop WHERE a = ? OR b = ?").run(me, me);
      q("DELETE FROM coop_daily WHERE device_id = ?").run(me);
      q("DELETE FROM idempotency WHERE device_id = ?").run(me);
      q("DELETE FROM events WHERE from_device = ?").run(me);
      q("DELETE FROM blocks WHERE blocked = ?").run(me);
      q("DELETE FROM devices WHERE id = ?").run(me);
    });
    return null;
  });

  route("PUT", "/me/slime", ({ me, body }) => {
    limit("profile", me, 120, HOUR);
    const name = text(body.name, "name", { max: 16 });
    const skin = oneOf(body.skin, "skin", ENUMS.skin);
    const level = int(body.level, "level", 1, 999);
    const mood = oneOf(body.mood, "mood", ENUMS.mood);
    const happiness = int(body.happiness, "happiness", 0, 100);
    let equipped = null;
    if (body.equipped != null) {
      const e = body.equipped;
      const clean = {};
      for (const slot of ["hat", "glasses", "scarf", "effect"]) {
        if (typeof e[slot] === "string" && /^[a-zA-Z.]{1,32}$/.test(e[slot])) clean[slot] = e[slot];
      }
      equipped = JSON.stringify(clean);
    }
    const now = clock();
    q(`INSERT INTO slimes (device_id, name, skin, level, mood, happiness, equipped, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET name = excluded.name, skin = excluded.skin, level = excluded.level,
         mood = excluded.mood, happiness = excluded.happiness, equipped = excluded.equipped, updated_at = excluded.updated_at`)
      .run(me, name, skin, level, mood, happiness, equipped, now);
    if (body.ownerName != null) {
      q("UPDATE devices SET owner_name = ? WHERE id = ?").run(text(body.ownerName, "ownerName", { max: 24 }), me);
    }
    return null;
  });

  route("PUT", "/me/privacy", ({ me, body }) => {
    const d = q("SELECT * FROM devices WHERE id = ?").get(me);
    const acceptRequests = body.acceptRequests == null ? d.accept_requests === 1 : bool(body.acceptRequests, "acceptRequests");
    const shareMood = body.shareMood == null ? d.share_mood === 1 : bool(body.shareMood, "shareMood");
    q("UPDATE devices SET accept_requests = ?, share_mood = ? WHERE id = ?").run(acceptRequests ? 1 : 0, shareMood ? 1 : 0, me);
    return { acceptRequests, shareMood };
  });

  // Pairing ------------------------------------------------------------------

  route("POST", "/pair-codes", ({ me }) => {
    limit("code", me, 20, HOUR, "You've fetched a lot of codes. Wait a little.");
    const d = q("SELECT accept_requests FROM devices WHERE id = ?").get(me);
    if (d.accept_requests !== 1) fail(409, "You're not accepting friend requests right now. Turn it on in the settings.");
    const now = clock();
    return transaction(db, () => {
      q("DELETE FROM pair_codes WHERE device_id = ? AND used_at IS NULL").run(me);
      let code;
      do { code = newPairCode(); } while (q("SELECT 1 FROM pair_codes WHERE code = ?").get(code));
      const expires = now + LIMITS.codeMinutes * 60_000;
      q("INSERT INTO pair_codes (code, device_id, created_at, expires_at) VALUES (?, ?, ?, ?)").run(code, me, now, expires);
      return { code, expiresAt: iso(expires) };
    });
  });

  // For the website's invite page (/f/CODE): shows whose code it is
  // without using it up. Limited per address like wrong codes.
  route("GET", "/invites/:code", ({ params, ip }) => {
    if (limiter.exhausted("badcode-ip", ip, 30)) fail(429, "Too many tries. Try again in an hour.");
    limit("invite", ip, 120, HOUR);
    const row = q("SELECT * FROM pair_codes WHERE code = ?").get(String(params.code).toUpperCase());
    if (!row || row.used_at || row.expires_at < clock()) {
      limiter.hit("badcode-ip", ip, 30, HOUR);
      fail(404, "This code doesn't exist anymore. Ask for a new one.");
    }
    const slime = slimeOf(row.device_id, null);
    return { ownerName: slime.ownerName, slime: { name: slime.name, skin: slime.skin, stage: slime.stage }, expiresAt: iso(row.expires_at) };
  }, { auth: false });

  route("POST", "/friend-requests", ({ me, body, ip }) => {
    // Brute-force protection: wrong codes count per device and per address.
    if (limiter.exhausted("badcode", me, 8) || limiter.exhausted("badcode-ip", ip, 30)) {
      fail(429, "Too many wrong codes. Try again in an hour.");
    }
    limit("join", me, 30, HOUR);
    const code = text(body.code, "code", { min: 6, max: 6 }).toUpperCase();
    const row = q("SELECT * FROM pair_codes WHERE code = ?").get(code);
    const wrong = (status, message) => {
      limiter.hit("badcode", me, 8, HOUR);
      limiter.hit("badcode-ip", ip, 30, HOUR);
      fail(status, message);
    };
    if (!row || row.used_at) wrong(404, "That code doesn't exist. Check the characters again.");
    if (row.device_id === me) fail(400, "That's your own code. Send it to a friend.");
    if (row.expires_at < clock()) wrong(410, "The code has expired. Ask for a new one.");
    const owner = row.device_id;
    // A block looks exactly like an unknown code, so nobody learns they were blocked.
    if (isBlocked(me, owner)) wrong(404, "That code doesn't exist. Check the characters again.");

    const existing = friendship(me, owner);
    if (existing) return { status: "friends", friend: friendView(me, existing) };
    if (friendCount(me) >= LIMITS.maxFriends) fail(409, `You already have ${LIMITS.maxFriends} slime friends.`);

    return transaction(db, () => {
      q("UPDATE pair_codes SET used_at = ? WHERE code = ?").run(clock(), code);
      // They asked us too: that's a yes from both sides.
      const theirs = q("SELECT * FROM friend_requests WHERE from_device = ? AND to_device = ?").get(owner, me);
      if (theirs) {
        befriend(me, owner);
        emit(owner, me, "accepted");
        return { status: "friends", friend: friendView(me, friendship(me, owner)) };
      }
      let request = q("SELECT * FROM friend_requests WHERE from_device = ? AND to_device = ?").get(me, owner);
      if (!request) {
        const id = newId("req");
        q("INSERT INTO friend_requests (id, from_device, to_device, created_at) VALUES (?, ?, ?, ?)").run(id, me, owner, clock());
        request = q("SELECT * FROM friend_requests WHERE id = ?").get(id);
        emit(owner, me, "request", { requestId: id });
      }
      return { status: "pending", request: requestView(request, me) };
    });
  });

  route("GET", "/friend-requests", ({ me }) => ({
    incoming: q("SELECT * FROM friend_requests WHERE to_device = ? ORDER BY created_at").all(me).map((r) => requestView(r, me)),
    outgoing: q("SELECT * FROM friend_requests WHERE from_device = ? ORDER BY created_at").all(me).map((r) => requestView(r, me)),
  }));

  route("POST", "/friend-requests/:id/accept", ({ me, params }) => {
    const r = q("SELECT * FROM friend_requests WHERE id = ? AND to_device = ?").get(params.id, me);
    if (!r) fail(404, "This request doesn't exist anymore.");
    if (friendCount(me) >= LIMITS.maxFriends) fail(409, `You already have ${LIMITS.maxFriends} slime friends.`);
    if (friendCount(r.from_device) >= LIMITS.maxFriends) fail(409, "Your friend already has the most slime friends allowed.");
    return transaction(db, () => {
      befriend(me, r.from_device);
      emit(r.from_device, me, "accepted");
      return { friend: friendView(me, friendship(me, r.from_device)) };
    });
  });

  route("POST", "/friend-requests/:id/decline", ({ me, params }) => {
    q("DELETE FROM friend_requests WHERE id = ? AND (to_device = ? OR from_device = ?)").run(params.id, me, me);
    return null;
  });

  // Friends ------------------------------------------------------------------

  route("GET", "/friends", ({ me }) =>
    q("SELECT * FROM friendships WHERE a = ? OR b = ? ORDER BY in_love DESC, love DESC").all(me, me).map((r) => friendView(me, r)));

  route("GET", "/friends/:id", ({ me, params }) => {
    const f = requireFriend(me, params.id);
    const [a, b] = pair(me, params.id);
    return {
      friend: friendView(me, f),
      memories: q("SELECT kind, detail, created_at FROM memories WHERE a = ? AND b = ? ORDER BY created_at").all(a, b)
        .map((m) => ({ kind: m.kind, detail: m.detail, at: iso(m.created_at) })),
      coop: coopView(me, params.id),
      today: Object.fromEntries(Object.keys(LOVE).map((kind) => [kind, LOVE[kind].perDay - usedToday(me, params.id, kind)])),
    };
  });

  route("DELETE", "/friends/:id", ({ me, params }) => {
    const [a, b] = pair(me, params.id);
    q("DELETE FROM friendships WHERE a = ? AND b = ?").run(a, b);
    return null;
  });

  function usedToday(me, other, kind) {
    return q("SELECT COUNT(*) AS n FROM events WHERE from_device = ? AND to_device = ? AND kind = ? AND created_at >= ?")
      .get(me, other, kind, startOfUtcDay(clock())).n;
  }

  route("POST", "/friends/:id/interactions", ({ me, params, body }) => {
    limit("interact", me, 120, HOUR);
    const kind = oneOf(body.kind, "kind", ENUMS.interaction);
    const food = kind === "gift" ? oneOf(body.food, "food", ENUMS.food) : null;
    const rule = LOVE[kind];
    return transaction(db, () => {
      const f = requireFriend(me, params.id);
      if (rule.minLove && f.love < rule.minLove) fail(409, `Too early for that. It unlocks at ${rule.minLove}% love.`);
      const capped = usedToday(me, params.id, kind) >= rule.perDay;
      if (capped && kind === "gift") fail(429, CAPPED.gift);
      const gained = capped ? 0 : Math.min(rule.gain, 100 - f.love);
      const love = f.love + gained;
      const fellInLove = f.in_love === 0 && love >= 100;
      q("UPDATE friendships SET love = ?, in_love = ? WHERE a = ? AND b = ?").run(love, f.in_love || fellInLove ? 1 : 0, f.a, f.b);
      if (!capped) emit(params.id, me, kind, food ? { food } : null);
      if (fellInLove) {
        emit(params.id, me, "inLove");
        remember(me, params.id, "inLove");
      }
      remember(me, params.id, `first.${kind}`, food);
      for (const mark of [25, 50, 75]) if (f.love < mark && love >= mark) remember(me, params.id, `love.${mark}`);
      return { love, inLove: f.in_love === 1 || fellInLove, gained, fellInLove, capped, message: capped ? CAPPED[kind] : null };
    });
  });

  route("POST", "/friends/:id/messages", ({ me, params, body }) => {
    limit("message", me, 30, DAY, "You've sent a lot of greetings today.");
    const preset = oneOf(body.preset, "preset", ENUMS.message);
    requireFriend(me, params.id);
    if (usedToday(me, params.id, "message") >= 6) fail(429, "Enough greetings to this friend for today.");
    emit(params.id, me, "message", { preset });
    return { sent: true };
  });

  // Blocking ------------------------------------------------------------------

  route("GET", "/blocks", ({ me }) =>
    q("SELECT blocked, created_at FROM blocks WHERE blocker = ? ORDER BY created_at").all(me)
      .map((r) => ({ deviceId: r.blocked, ownerName: slimeOf(r.blocked, me)?.ownerName ?? "Deleted", since: iso(r.created_at) })));

  route("POST", "/blocks", ({ me, body }) => {
    const other = text(body.deviceId, "deviceId", { max: 40 });
    if (other === me) fail(400, "You can't block yourself.");
    transaction(db, () => {
      q("INSERT OR IGNORE INTO blocks (blocker, blocked, created_at) VALUES (?, ?, ?)").run(me, other, clock());
      const [a, b] = pair(me, other);
      q("DELETE FROM friendships WHERE a = ? AND b = ?").run(a, b);
      q("DELETE FROM friend_requests WHERE (from_device = ? AND to_device = ?) OR (from_device = ? AND to_device = ?)").run(me, other, other, me);
      q("DELETE FROM events WHERE to_device = ? AND from_device = ?").run(me, other);
      q("UPDATE challenges SET expires_at = ? WHERE (from_device = ? AND to_device = ?) OR (from_device = ? AND to_device = ?)")
        .run(clock(), me, other, other, me);
    });
    return null;
  });

  route("DELETE", "/blocks/:id", ({ me, params }) => {
    q("DELETE FROM blocks WHERE blocker = ? AND blocked = ?").run(me, params.id);
    return null;
  });

  // Inbox ---------------------------------------------------------------------

  route("GET", "/inbox", ({ me, query }) => {
    const after = Number.parseInt(query.get("after") ?? "0", 10) || 0;
    const rows = q("SELECT * FROM events WHERE to_device = ? AND id > ? ORDER BY id LIMIT 50").all(me, after);
    const events = rows
      .filter((r) => !r.from_device || !isBlocked(me, r.from_device))
      .map((r) => {
        const data = r.data ? JSON.parse(r.data) : {};
        return {
          id: String(r.id),
          friendId: r.from_device,
          friendName: r.from_device ? slimeOf(r.from_device, me)?.name ?? "Slime" : null,
          kind: r.kind,
          food: data.food ?? null,
          preset: data.preset ?? null,
          challengeId: data.challengeId ?? null,
          requestId: data.requestId ?? null,
          createdAt: iso(r.created_at),
        };
      });
    return { events, cursor: rows.length ? String(rows[rows.length - 1].id) : (after ? String(after) : null) };
  });

  // Async challenges ------------------------------------------------------------

  route("POST", "/challenges", ({ me, body }) => {
    limit("challenge", me, 20, DAY, "Enough challenges for today.");
    const friendId = text(body.friendId, "friendId", { max: 40 });
    const game = oneOf(body.game, "game", ENUMS.game);
    const seed = text(body.seed, "seed", { max: 24 });
    if (!/^\d{1,20}$/.test(seed)) fail(400, "\"seed\" must be a number.");
    const score = int(body.score, "score", 0, 1_000_000);
    requireFriend(me, friendId);
    const open = q("SELECT COUNT(*) AS n FROM challenges WHERE from_device = ? AND to_device = ? AND answered_at IS NULL AND expires_at > ?")
      .get(me, friendId, clock()).n;
    if (open >= 3) fail(409, "Your friend already has three challenges waiting.");
    const id = newId("ch");
    const now = clock();
    q(`INSERT INTO challenges (id, from_device, to_device, game, seed, from_score, created_at, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`).run(id, me, friendId, game, seed, score, now, now + LIMITS.challengeDays * DAY);
    emit(friendId, me, "challenge", { challengeId: id });
    return challengeView(q("SELECT * FROM challenges WHERE id = ?").get(id), me);
  });

  route("GET", "/challenges", ({ me }) =>
    q(`SELECT * FROM challenges WHERE (from_device = ? OR to_device = ?) AND created_at > ? ORDER BY created_at DESC LIMIT 30`)
      .all(me, me, clock() - 14 * DAY).map((r) => challengeView(r, me)));

  route("POST", "/challenges/:id/answer", ({ me, params, body }) => {
    const score = int(body.score, "score", 0, 1_000_000);
    return transaction(db, () => {
      const c = q("SELECT * FROM challenges WHERE id = ? AND to_device = ?").get(params.id, me);
      if (!c) fail(404, "This challenge doesn't exist.");
      if (c.answered_at) return challengeView(c, me);
      if (c.expires_at < clock()) fail(410, "The challenge has expired.");
      q("UPDATE challenges SET to_score = ?, answered_at = ? WHERE id = ?").run(score, clock(), c.id);
      emit(c.from_device, me, "challengeResult", { challengeId: c.id });
      if (score > c.from_score) remember(me, c.from_device, "firstChallengeWin", c.game);
      return challengeView(q("SELECT * FROM challenges WHERE id = ?").get(c.id), me);
    });
  });

  // Co-op garden: friends fill a weekly goal together with arcade points ------

  route("POST", "/friends/:id/coop", ({ me, params, body }) => {
    const points = int(body.points, "points", 1, 1_000_000);
    return transaction(db, () => {
      requireFriend(me, params.id);
      const day = dayKey(clock());
      const used = q("SELECT points FROM coop_daily WHERE device_id = ? AND day = ?").get(me, day)?.points ?? 0;
      const add = Math.max(0, Math.min(points, LIMITS.coopPerCall, LIMITS.coopPerDay - used));
      const week = weekKey(clock());
      const [a, b] = pair(me, params.id);
      if (add > 0) {
        q("INSERT INTO coop_daily (device_id, day, points) VALUES (?, ?, ?) ON CONFLICT(device_id, day) DO UPDATE SET points = points + ?")
          .run(me, day, add, add);
        q("INSERT OR IGNORE INTO coop (a, b, week) VALUES (?, ?, ?)").run(a, b, week);
        q(`UPDATE coop SET ${me === a ? "points_a = points_a" : "points_b = points_b"} + ? WHERE a = ? AND b = ? AND week = ?`)
          .run(add, a, b, week);
      }
      const row = q("SELECT * FROM coop WHERE a = ? AND b = ? AND week = ?").get(a, b, week);
      if (row && !row.done_at && row.points_a + row.points_b >= LIMITS.coopGoal) {
        q("UPDATE coop SET done_at = ? WHERE a = ? AND b = ? AND week = ?").run(clock(), a, b, week);
        emit(params.id, me, "coopDone", null);
        emit(me, params.id, "coopDone", null);
        remember(me, params.id, "firstCoop", week);
      }
      return { ...coopView(me, params.id, week), added: add };
    });
  });

  // Chat ------------------------------------------------------------------------

  route("POST", "/chat", async ({ me, body }) => {
    if (!chat?.enabled) fail(503, "Talking isn't set up on the server right now.");
    limit("chat-min", me, 8, 60_000, "Not so fast, the slime is still thinking.");
    limit("chat-day", me, 60, DAY, "Your slime is tired of talking for today. More tomorrow.");
    const message = text(body.message, "message", { max: 500 });
    const history = Array.isArray(body.history) ? body.history.slice(-12) : [];
    const cleanHistory = history
      .filter((t) => t && (t.role === "user" || t.role === "slime") && typeof t.text === "string" && t.text.trim())
      .map((t) => ({ role: t.role, text: t.text.trim().slice(0, 500) }));
    const s = body.slime ?? {};
    const slime = {
      name: text(s.name, "slime.name", { max: 16, fallback: "Slime" }),
      ageDays: Number.isInteger(s.ageDays) ? Math.max(0, Math.min(s.ageDays, 100000)) : 0,
      level: Number.isInteger(s.level) ? Math.max(1, Math.min(s.level, 999)) : 1,
      mood: ENUMS.mood.includes(s.mood) ? s.mood : "idle",
      needs: typeof s.needs === "object" && s.needs ? s.needs : {},
      favorite: ENUMS.food.includes(s.favorite) ? s.favorite : "apple",
      partnerName: typeof s.partnerName === "string" ? s.partnerName.slice(0, 16) : null,
      localTime: typeof s.localTime === "string" ? s.localTime.slice(0, 16) : "",
    };
    return chat.reply({ message, history: cleanHistory, slime });
  });

  // --- Dispatch ----------------------------------------------------------------

  async function handle(req) {
    const url = new URL(req.url, "http://local");
    let path = url.pathname;
    if (!path.startsWith("/api/v1/")) fail(404, "This address doesn't exist.");
    path = path.slice("/api/v1".length);
    const found = routes.find((r) => r.method === req.method && r.regex.test(path));
    if (!found) {
      if (routes.some((r) => r.regex.test(path))) fail(405, "This method isn't allowed here.");
      fail(404, "This address doesn't exist.");
    }
    const params = {};
    const match = path.match(found.regex);
    found.keys.forEach((k, i) => { params[k] = decodeURIComponent(match[i + 1]); });

    let me = null;
    if (found.auth) {
      const header = req.headers.authorization ?? "";
      const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
      const device = token ? q("SELECT id FROM devices WHERE token_hash = ?").get(sha256(token)) : null;
      if (!device) fail(401, "This device isn't registered. Open Slimy once with internet.");
      me = device.id;
      q("UPDATE devices SET last_seen = ? WHERE id = ?").run(clock(), me);
      limit("all", me, 1200, HOUR);
    }

    const ctx = { me, params, body: req.body ?? {}, query: url.searchParams, ip: req.ip };

    // Retries of the same write (offline queue, flaky network) get the first answer again.
    const key = req.headers["idempotency-key"];
    const idempotent = me && key && req.method !== "GET";
    if (idempotent) {
      if (typeof key !== "string" || key.length > 64) fail(400, "Idempotency-Key is too long.");
      const saved = q("SELECT * FROM idempotency WHERE device_id = ? AND key = ?").get(me, key);
      if (saved) {
        if (saved.method !== req.method || saved.path !== path) fail(422, "This Idempotency-Key was already used for something else.");
        return { status: saved.status, body: saved.body == null ? null : JSON.parse(saved.body), replayed: true };
      }
    }

    let status = 200;
    let result;
    try {
      result = await found.handler(ctx);
      if (result == null) status = 204;
    } catch (error) {
      if (!(error instanceof HttpError) || !idempotent || error.status >= 500 || error.status === 429) throw error;
      status = error.status;
      result = { error: error.message };
    }
    if (idempotent) {
      q("INSERT OR IGNORE INTO idempotency (device_id, key, method, path, status, body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .run(me, key, req.method, path, status, result == null ? null : JSON.stringify(result), clock());
    }
    return { status, body: status === 204 ? null : result };
  }

  /** Node http handler. */
  async function listener(req, res) {
    const send = (status, body) => {
      const payload = body == null ? "" : JSON.stringify(body);
      res.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
        "Content-Length": Buffer.byteLength(payload),
      });
      res.end(payload);
    };
    try {
      req.ip = (trustProxy && String(req.headers["x-forwarded-for"] ?? "").split(",")[0].trim()) || req.socket.remoteAddress || "?";
      req.body = await readBody(req);
      const out = await handle(req);
      send(out.status, out.body);
    } catch (error) {
      if (error instanceof HttpError) return send(error.status, { error: error.message });
      console.error(error);
      send(500, { error: "Something went wrong on the server. Try again later." });
    }
  }

  return { listener, handle, cleanup, limiter };
}

function stageFor(level) {
  return level < 4 ? "baby" : level < 12 ? "young" : "grown";
}

async function readBody(req) {
  if (req.method === "GET" || req.method === "HEAD") return {};
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 32 * 1024) throw new HttpError(413, "The request is too large.");
    chunks.push(chunk);
  }
  if (!size) return {};
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error("not an object");
    return parsed;
  } catch {
    throw new HttpError(400, "The request isn't valid JSON.");
  }
}
