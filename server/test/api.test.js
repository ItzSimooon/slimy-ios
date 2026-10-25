import { after, before, beforeEach, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createApp, LOVE } from "../src/app.js";
import { createChat } from "../src/chat.js";
import { openDatabase, schemaVersion, migrations, migrate } from "../src/db.js";
import { weekKey } from "../src/util.js";

// Every test talks to the real HTTP listener on a random port, with an
// in-memory database and a clock the test controls.

let server, base, db, app, now;
let fakeReply = { stop_reason: "end_turn", content: [{ type: "text", text: '{"reply":"blub, hallo!","reaction":"wave"}' }] };
let lastChatRequest = null;
const fakeClient = { beta: { messages: { create: async (req) => { lastChatRequest = req; return fakeReply; } } } };

function start() {
  now = Date.UTC(2026, 9, 25, 12, 0, 0);
  db = openDatabase(":memory:");
  app = createApp({ db, clock: () => now, chat: createChat({ client: fakeClient, model: "claude-opus-5-5" }) });
  server = createServer(app.listener);
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => {
    base = `http://127.0.0.1:${server.address().port}/api/v1`;
    resolve();
  }));
}

async function call(method, path, { token, body, key } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers["Idempotency-Key"] = key;
  const res = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function device(name = "Simon") {
  const r = await call("POST", "/devices", { body: { ownerName: name } });
  assert.equal(r.status, 200);
  await call("PUT", "/me/slime", { token: r.body.token, body: { name: `${name}s Blob`, skin: "lime", level: 5, mood: "idle", happiness: 80 } });
  return r.body;
}

async function friends(x, y) {
  const code = (await call("POST", "/pair-codes", { token: x.token, body: {} })).body.code;
  const join = await call("POST", "/friend-requests", { token: y.token, body: { code } });
  assert.equal(join.body.status, "pending");
  const accept = await call("POST", `/friend-requests/${join.body.request.id}/accept`, { token: x.token, body: {} });
  assert.equal(accept.status, 200);
  return accept.body.friend;
}

beforeEach(async () => {
  if (server) await new Promise((r) => server.close(r));
  await start();
});
after(() => server?.close());

describe("basics", () => {
  test("health reports the schema and needs no auth", async () => {
    const r = await call("GET", "/health");
    assert.equal(r.status, 200);
    assert.equal(r.body.ok, true);
    assert.equal(r.body.schema, migrations.length);
  });

  test("unknown paths answer with an error body", async () => {
    const r = await call("GET", "/nope");
    assert.equal(r.status, 404);
    assert.match(r.body.error, /doesn't exist/);
  });

  test("requests without a valid token are rejected", async () => {
    assert.equal((await call("GET", "/friends")).status, 401);
    assert.equal((await call("GET", "/friends", { token: "wrong" })).status, 401);
  });

  test("invalid JSON and invalid fields are 400", async () => {
    const d = await device();
    const res = await fetch(base + "/me/slime", { method: "PUT", headers: { Authorization: `Bearer ${d.token}` }, body: "{nope" });
    assert.equal(res.status, 400);
    const bad = await call("PUT", "/me/slime", { token: d.token, body: { name: "x", skin: "plaid", level: 1, mood: "idle", happiness: 1 } });
    assert.equal(bad.status, 400);
  });

  test("dates have no milliseconds", async () => {
    const d = await device();
    const r = await call("POST", "/pair-codes", { token: d.token, body: {} });
    assert.match(r.body.expiresAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  });

  test("tokens are stored only as hashes", async () => {
    const d = await device();
    const row = db.prepare("SELECT token_hash FROM devices WHERE id = ?").get(d.deviceId);
    assert.notEqual(row.token_hash, d.token);
    assert.equal(row.token_hash.length, 64);
  });

  test("registration is rate limited per address", async () => {
    for (let i = 0; i < 10; i++) assert.equal((await call("POST", "/devices", { body: {} })).status, 200);
    assert.equal((await call("POST", "/devices", { body: {} })).status, 429);
  });
});

describe("migrations", () => {
  test("running them twice changes nothing", () => {
    const v = schemaVersion(db);
    migrate(db);
    assert.equal(schemaVersion(db), v);
  });
});

describe("friend codes and requests", () => {
  test("join creates a request the owner accepts", async () => {
    const a = await device("Anna"), b = await device("Ben");
    const f = await friends(a, b);
    assert.equal(f.id, b.deviceId);
    assert.equal(f.love, 0);
    const list = await call("GET", "/friends", { token: b.token });
    assert.equal(list.body.length, 1);
    assert.equal(list.body[0].slime.name, "Annas Blob");
    assert.equal(list.body[0].slime.stage, "young");
  });

  test("codes are single use and expire", async () => {
    const a = await device(), b = await device(), c = await device();
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    assert.equal((await call("POST", "/friend-requests", { token: b.token, body: { code } })).status, 200);
    assert.equal((await call("POST", "/friend-requests", { token: c.token, body: { code } })).status, 404);
    const code2 = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    now += 11 * 60_000;
    assert.equal((await call("POST", "/friend-requests", { token: c.token, body: { code: code2 } })).status, 410);
  });

  test("the website can show an invite without using the code", async () => {
    const a = await device("Anna"), b = await device();
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    const invite = await call("GET", `/invites/${code.toLowerCase()}`);
    assert.equal(invite.status, 200);
    assert.equal(invite.body.ownerName, "Anna");
    assert.equal(invite.body.slime.name, "Annas Blob");
    assert.equal(invite.body.token, undefined);
    assert.equal((await call("POST", "/friend-requests", { token: b.token, body: { code } })).status, 200);
    assert.equal((await call("GET", `/invites/${code}`)).status, 404);
  });

  test("your own code is refused", async () => {
    const a = await device();
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    const r = await call("POST", "/friend-requests", { token: a.token, body: { code } });
    assert.equal(r.status, 400);
  });

  test("guessing codes gets locked out", async () => {
    const a = await device();
    let last;
    for (let i = 0; i < 10; i++) last = await call("POST", "/friend-requests", { token: a.token, body: { code: "ZZZZZ" + i } });
    assert.equal(last.status, 429);
  });

  test("declining removes the request", async () => {
    const a = await device(), b = await device();
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    const join = await call("POST", "/friend-requests", { token: b.token, body: { code } });
    assert.equal((await call("POST", `/friend-requests/${join.body.request.id}/decline`, { token: a.token, body: {} })).status, 204);
    assert.equal((await call("GET", "/friend-requests", { token: a.token })).body.incoming.length, 0);
    assert.equal((await call("GET", "/friends", { token: a.token })).body.length, 0);
  });

  test("privacy: no codes while requests are off", async () => {
    const a = await device();
    await call("PUT", "/me/privacy", { token: a.token, body: { acceptRequests: false } });
    assert.equal((await call("POST", "/pair-codes", { token: a.token, body: {} })).status, 409);
  });

  test("privacy: hidden mood", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    await call("PUT", "/me/slime", { token: a.token, body: { name: "Blob", skin: "lime", level: 2, mood: "sad", happiness: 10 } });
    await call("PUT", "/me/privacy", { token: a.token, body: { shareMood: false } });
    const seen = (await call("GET", "/friends", { token: b.token })).body[0].slime;
    assert.equal(seen.mood, "idle");
    assert.equal(seen.happiness, null);
  });
});

describe("love", () => {
  test("hugs raise love up to the daily cap", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    let r;
    for (let i = 0; i < LOVE.hug.perDay; i++) r = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" } });
    assert.equal(r.body.love, LOVE.hug.gain * LOVE.hug.perDay);
    const capped = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" } });
    assert.equal(capped.status, 200);
    assert.equal(capped.body.gained, 0);
    assert.equal(capped.body.capped, true);
    now += 24 * 3600_000;
    const tomorrow = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" } });
    assert.equal(tomorrow.body.gained, LOVE.hug.gain);
  });

  test("kisses need 50 % love", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    const r = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "kiss" } });
    assert.equal(r.status, 409);
  });

  test("falling in love at 100 tells both and is remembered", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    db.prepare("UPDATE friendships SET love = 98").run();
    const r = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "visit" } });
    assert.equal(r.body.love, 100);
    assert.equal(r.body.fellInLove, true);
    const inbox = await call("GET", "/inbox", { token: b.token });
    assert.ok(inbox.body.events.some((e) => e.kind === "inLove"));
    const detail = await call("GET", `/friends/${a.deviceId}`, { token: b.token });
    assert.ok(detail.body.memories.some((m) => m.kind === "inLove"));
  });

  test("gifts carry a valid food and arrive in the inbox", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    assert.equal((await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "gift", food: "rocks" } })).status, 400);
    await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "gift", food: "cake" } });
    const inbox = await call("GET", "/inbox", { token: b.token });
    const gift = inbox.body.events.find((e) => e.kind === "gift");
    assert.equal(gift.food, "cake");
    assert.equal(gift.friendId, a.deviceId);
  });

  test("strangers cannot interact", async () => {
    const a = await device(), b = await device();
    const r = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" } });
    assert.equal(r.status, 404);
  });
});

describe("idempotency", () => {
  test("a retried write returns the first answer and counts once", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    const first = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" }, key: "k1" });
    const again = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" }, key: "k1" });
    assert.deepEqual(again.body, first.body);
    const inbox = await call("GET", "/inbox", { token: b.token });
    assert.equal(inbox.body.events.filter((e) => e.kind === "hug").length, 1);
  });

  test("a key reused for another path is refused", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" }, key: "same" });
    const r = await call("POST", `/friends/${b.deviceId}/messages`, { token: a.token, body: { preset: "hello" }, key: "same" });
    assert.equal(r.status, 422);
  });
});

describe("inbox", () => {
  test("cursor returns only newer events", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    await call("POST", `/friends/${b.deviceId}/messages`, { token: a.token, body: { preset: "hello" } });
    const first = await call("GET", "/inbox", { token: b.token });
    assert.equal(first.body.events.at(-1).preset, "hello");
    const next = await call("GET", `/inbox?after=${first.body.cursor}`, { token: b.token });
    assert.equal(next.body.events.length, 0);
    assert.equal(next.body.cursor, first.body.cursor);
  });

  test("messages are presets only", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    const r = await call("POST", `/friends/${b.deviceId}/messages`, { token: a.token, body: { preset: "buy crypto" } });
    assert.equal(r.status, 400);
  });

  test("old events are cleaned up", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    await call("POST", `/friends/${b.deviceId}/messages`, { token: a.token, body: { preset: "hello" } });
    now += 31 * 24 * 3600_000;
    app.cleanup();
    assert.equal((await call("GET", "/inbox", { token: b.token })).body.events.length, 0);
  });
});

describe("blocking", () => {
  test("ends the friendship, hides events and makes the code look unknown", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    await call("POST", `/friends/${a.deviceId}/messages`, { token: b.token, body: { preset: "hello" } });
    assert.equal((await call("POST", "/blocks", { token: a.token, body: { deviceId: b.deviceId } })).status, 204);
    assert.equal((await call("GET", "/friends", { token: a.token })).body.length, 0);
    assert.equal((await call("GET", "/inbox", { token: a.token })).body.events.filter((e) => e.friendId === b.deviceId).length, 0);
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    const r = await call("POST", "/friend-requests", { token: b.token, body: { code } });
    assert.equal(r.status, 404);
    assert.equal((await call("GET", "/blocks", { token: a.token })).body.length, 1);
    assert.equal((await call("DELETE", `/blocks/${b.deviceId}`, { token: a.token })).status, 204);
  });
});

describe("challenges", () => {
  test("friend plays the same seed and both see the result", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    const c = await call("POST", "/challenges", { token: a.token, body: { friendId: b.deviceId, game: "bubblePop", seed: "12345", score: 300 } });
    assert.equal(c.status, 200);
    const list = await call("GET", "/challenges", { token: b.token });
    assert.equal(list.body[0].seed, "12345");
    assert.equal(list.body[0].theirScore, 300);
    const answered = await call("POST", `/challenges/${c.body.id}/answer`, { token: b.token, body: { score: 410 } });
    assert.equal(answered.body.myScore, 410);
    // Answering twice keeps the first score.
    const again = await call("POST", `/challenges/${c.body.id}/answer`, { token: b.token, body: { score: 9999 } });
    assert.equal(again.body.myScore, 410);
    const mine = await call("GET", "/challenges", { token: a.token });
    assert.equal(mine.body[0].theirScore, 410);
    const inbox = await call("GET", "/inbox", { token: a.token });
    assert.ok(inbox.body.events.some((e) => e.kind === "challengeResult"));
  });

  test("the sender cannot answer their own challenge, and expired ones are closed", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    const c = await call("POST", "/challenges", { token: a.token, body: { friendId: b.deviceId, game: "colorMatch", seed: "7", score: 10 } });
    assert.equal((await call("POST", `/challenges/${c.body.id}/answer`, { token: a.token, body: { score: 99 } })).status, 404);
    now += 4 * 24 * 3600_000;
    assert.equal((await call("POST", `/challenges/${c.body.id}/answer`, { token: b.token, body: { score: 1 } })).status, 410);
  });
});

describe("co-op garden", () => {
  test("points add up, are capped per day and finish the weekly goal once", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    const one = await call("POST", `/friends/${b.deviceId}/coop`, { token: a.token, body: { points: 5000 } });
    assert.equal(one.body.added, 300);
    for (let i = 0; i < 3; i++) await call("POST", `/friends/${b.deviceId}/coop`, { token: a.token, body: { points: 300 } });
    const capped = await call("POST", `/friends/${b.deviceId}/coop`, { token: a.token, body: { points: 300 } });
    assert.equal(capped.body.mine, 900);
    assert.equal(capped.body.added, 0);
    const done = await call("POST", `/friends/${a.deviceId}/coop`, { token: b.token, body: { points: 200 } });
    assert.equal(done.body.total, 1100);
    assert.equal(done.body.done, true);
    assert.equal(done.body.week, weekKey(now));
    const inbox = await call("GET", "/inbox", { token: a.token });
    assert.equal(inbox.body.events.filter((e) => e.kind === "coopDone").length, 1);
  });
});

describe("chat", () => {
  test("answers in character with the key only on the server", async () => {
    const a = await device();
    const r = await call("POST", "/chat", {
      token: a.token,
      body: { message: "hallo", history: [{ role: "slime", text: "blub" }, { role: "user", text: "na?" }],
        slime: { name: "Blob", ageDays: 3, level: 2, mood: "idle", needs: { hunger: 40 }, favorite: "cake", localTime: "12:00" } },
    });
    assert.equal(r.status, 200);
    assert.equal(r.body.reply, "blub, hallo!");
    assert.equal(r.body.reaction, "wave");
    assert.equal(lastChatRequest.model, "claude-opus-5-5");
    assert.equal(lastChatRequest.messages[0].role, "user");
    assert.match(lastChatRequest.system, /Blob/);
  });

  test("a refusal becomes a gentle line, not an error", async () => {
    const a = await device();
    fakeReply = { stop_reason: "refusal", content: [] };
    const r = await call("POST", "/chat", { token: a.token, body: { message: "hi" } });
    assert.equal(r.status, 200);
    assert.equal(r.body.reaction, "confused");
    fakeReply = { stop_reason: "end_turn", content: [{ type: "text", text: '{"reply":"blub, hallo!","reaction":"wave"}' }] };
  });

  test("without a key the chat says it is not set up", async () => {
    const off = createChat({ apiKey: "" });
    assert.equal(off.enabled, false);
  });
});

describe("account deletion", () => {
  test("removes the device and its friendships", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    assert.equal((await call("DELETE", "/me", { token: a.token })).status, 204);
    assert.equal((await call("GET", "/friends", { token: a.token })).status, 401);
    assert.equal((await call("GET", "/friends", { token: b.token })).body.length, 0);
  });
});
