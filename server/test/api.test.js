import { after, before, beforeEach, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { DatabaseSync } from "node:sqlite";
import { createApp, LOVE, UNKNOWN_DEVICE } from "../src/app.js";
import { createChat } from "../src/chat.js";
import { openDatabase, schemaVersion, migrations, migrate } from "../src/db.js";
import { weekKey } from "../src/util.js";

// Every test talks to the real HTTP listener on a random port, with an
// in-memory database and a clock the test controls.

let server, base, db, app, now;
const HELLO = { choices: [{ message: { role: "assistant", content: '{"reply":"blub, hello!","reaction":"wave"}' } }] };
let fakeReply = HELLO;
let lastChatRequest = null;
let chatCalls = 0;
// Stands in for Groq's OpenAI-compatible endpoint.
const fakeFetch = async (url, init) => {
  chatCalls += 1;
  lastChatRequest = { url, headers: init.headers, ...JSON.parse(init.body) };
  return new Response(JSON.stringify(fakeReply), { status: 200, headers: { "Content-Type": "application/json" } });
};

function start() {
  now = Date.UTC(2026, 9, 25, 12, 0, 0);
  db = openDatabase(":memory:");
  const chat = createChat({ env: { GROQ_API_KEY: "test-key" }, fetch: fakeFetch, log: () => {} });
  app = createApp({ db, clock: () => now, chat });
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

  test("a broken escape in the path is 400, not a crash", async () => {
    const d = await device();
    const r = await call("GET", "/friends/%E0%A4%A", { token: d.token });
    assert.equal(r.status, 400);
    assert.match(r.body.error, /isn't valid/);
  });

  test("requests without a valid token are rejected", async () => {
    assert.equal((await call("GET", "/friends")).status, 401);
    const r = await call("GET", "/friends", { token: "wrong" });
    assert.equal(r.status, 401);
    // The app shows this as is and registers again; it must not ask for a relaunch.
    assert.equal(r.body.error, UNKNOWN_DEVICE);
    assert.match(r.body.error, /Go online again/);
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

  test("used codes are kept for a day, then cleaned up", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    const count = () => db.prepare("SELECT COUNT(*) AS n FROM pair_codes").get().n;
    app.cleanup();
    assert.equal(count(), 1);
    now += 24 * 3600_000 + 1;
    app.cleanup();
    assert.equal(count(), 0);
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
      body: { message: "hello", history: [{ role: "slime", text: "blub" }, { role: "user", text: "how are you?" }],
        slime: { name: "Blob", ageDays: 3, level: 2, mood: "idle", needs: { hunger: 40 }, favorite: "cake", localTime: "12:00" } },
    });
    assert.equal(r.status, 200);
    assert.equal(r.body.reply, "blub, hello!");
    assert.equal(r.body.reaction, "wave");
    assert.equal(lastChatRequest.model, "openai/gpt-oss-20b");
    assert.equal(lastChatRequest.headers.Authorization, "Bearer test-key");
    assert.equal(lastChatRequest.messages[0].role, "system");
    assert.match(lastChatRequest.messages[0].content, /You are Blob/);
    assert.match(lastChatRequest.messages[0].content, /local time: 12:00/);
    // The conversation starts with the user; the slime's opening line is dropped.
    assert.deepEqual(lastChatRequest.messages.slice(1).map((m) => m.role), ["user", "user"]);
  });

  test("a refusal becomes a gentle line, not an error", async () => {
    const a = await device();
    fakeReply = { choices: [{ message: { role: "assistant", content: null, refusal: "I can't help with that." } }] };
    const r = await call("POST", "/chat", { token: a.token, body: { message: "hi" } });
    fakeReply = HELLO;
    assert.equal(r.status, 200);
    assert.equal(r.body.reaction, "confused");
  });

  test("a rude message gets a gentle answer and never reaches the AI", async () => {
    const a = await device();
    const before = chatCalls;
    const r = await call("POST", "/chat", { token: a.token, body: { message: "you are a b1tch" } });
    assert.equal(r.status, 200);
    assert.equal(r.body.reaction, "confused");
    assert.ok(r.body.reply.length > 0);
    assert.equal(chatCalls, before);
  });

  test("names in the prompt pass the name filter", async () => {
    const a = await device();
    await call("POST", "/chat", { token: a.token, body: { message: "hi", slime: { name: "fuck", partnerName: "www.spam.com" } } });
    assert.match(lastChatRequest.messages[0].content, /You are Slime,/);
    assert.match(lastChatRequest.messages[0].content, /don't have any slime friends/);
  });

  test("requests are counted per provider without content", async () => {
    const a = await device();
    await call("POST", "/chat", { token: a.token, body: { message: "hi" } });
    const rows = db.prepare("SELECT * FROM chat_usage").all();
    assert.equal(rows.length, 1);
    assert.equal(rows[0].provider, "groq");
    assert.equal(rows[0].requests, 1);
    assert.equal(rows[0].failures, 0);
    assert.deepEqual(Object.keys(rows[0]).sort(), ["day", "failures", "provider", "requests"]);
  });

  test("without a provider the chat says it is not set up", async () => {
    const off = createChat({ env: {} });
    assert.equal(off.enabled, false);
    const offApp = createApp({ db, clock: () => now, chat: off });
    const a = await device();
    const r = await offApp.handle({
      method: "POST", url: "/api/v1/chat", headers: { authorization: `Bearer ${a.token}` }, body: { message: "hi" }, ip: "127.0.0.1",
    }).catch((error) => error);
    assert.equal(r.status, 503);
    assert.match(r.message, /isn't set up/);
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

describe("names", () => {
  test("registration and profile names pass the filter", async () => {
    for (const bad of ["fuck", "Sh1t Head", "a$$", "Hurensohn", "simon@gmail.com", "www.slimy.com", "0171 2345678", "@simon"]) {
      const r = await call("POST", "/devices", { body: { ownerName: bad } });
      assert.equal(r.status, 400, bad);
      assert.equal(r.body.error, "Please choose a different name.");
      now += 3600_000;
    }
    const d = await device("Cassandra");
    const profile = { name: "Blob", skin: "lime", level: 1, mood: "idle", happiness: 50 };
    assert.equal((await call("PUT", "/me/slime", { token: d.token, body: { ...profile, name: "b i t c h" } })).status, 400);
    assert.equal((await call("PUT", "/me/slime", { token: d.token, body: { ...profile, ownerName: "f.u.c.k" } })).status, 400);
    assert.equal((await call("PUT", "/me/slime", { token: d.token, body: { ...profile, ownerName: "Titan" } })).status, 204);
    assert.equal((await call("GET", "/me", { token: d.token })).body.ownerName, "Titan");
    // A real first name is fine for a person, but not for the slime.
    assert.equal((await call("PUT", "/me/slime", { token: d.token, body: { ...profile, ownerName: "Dick" } })).status, 204);
    assert.equal((await call("PUT", "/me/slime", { token: d.token, body: { ...profile, name: "Dick" } })).status, 400);
  });

  test("names are trimmed, cleaned of control characters and cut to 24", async () => {
    const r = await call("POST", "/devices", { body: { ownerName: "  Anna\u202e\u200b  Maria Josephine Alexandra  " } });
    assert.equal(r.status, 200);
    const me = await call("GET", "/me", { token: r.body.token });
    assert.equal(me.body.ownerName, "Anna Maria Josephine Ale");
  });

  test("a request from a device without a name says Someone", async () => {
    const a = await device();
    const r = await call("POST", "/devices", { body: {} });
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    await call("POST", "/friend-requests", { token: r.body.token, body: { code } });
    const incoming = (await call("GET", "/friend-requests", { token: a.token })).body.incoming;
    assert.equal(incoming[0].ownerName, "Someone");
  });
});

describe("last seen", () => {
  test("is written at most every ten minutes", async () => {
    const a = await device();
    const seen = () => db.prepare("SELECT last_seen_at FROM devices WHERE id = ?").get(a.deviceId).last_seen_at;
    const first = seen();
    now += 5 * 60_000;
    await call("GET", "/me", { token: a.token });
    assert.equal(seen(), first);
    now += 6 * 60_000;
    await call("GET", "/me", { token: a.token });
    assert.equal(seen(), now);
  });
});

describe("reports", () => {
  test("a report about a device is stored and answers 204", async () => {
    const a = await device("Anna"), b = await device("Ben");
    const r = await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "name", note: "  rude   name " } });
    assert.equal(r.status, 204);
    const row = db.prepare("SELECT * FROM reports").get();
    assert.equal(row.reporter, a.deviceId);
    assert.equal(row.reported, b.deviceId);
    assert.equal(row.reason, "name");
    assert.equal(row.note, "rude name");
    assert.equal(row.status, "open");
    // Reporting doesn't block: they can still become friends.
    await friends(a, b);
  });

  test("a chat report carries the reply and needs no device", async () => {
    const a = await device();
    assert.equal((await call("POST", "/reports", { token: a.token, body: { reason: "chat" } })).status, 400);
    const r = await call("POST", "/reports", { token: a.token, body: { reason: "chat", text: "x".repeat(600) } });
    assert.equal(r.status, 204);
    const row = db.prepare("SELECT * FROM reports").get();
    assert.equal(row.reported, null);
    assert.equal(row.text.length, 500);
  });

  test("validation: reason, device, yourself", async () => {
    const a = await device(), b = await device();
    assert.equal((await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "boring" } })).status, 400);
    assert.equal((await call("POST", "/reports", { token: a.token, body: { reason: "spam" } })).status, 400);
    assert.equal((await call("POST", "/reports", { token: a.token, body: { deviceId: a.deviceId, reason: "spam" } })).status, 400);
    assert.equal((await call("POST", "/reports", { token: a.token, body: { deviceId: "dev_nobody", reason: "spam" } })).status, 404);
    assert.equal((await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "spam", note: 5 } })).status, 400);
    assert.equal((await call("POST", "/reports", { body: { deviceId: b.deviceId, reason: "spam" } })).status, 401);
  });

  test("the same open report twice is stored once", async () => {
    const a = await device(), b = await device();
    await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "spam" } });
    assert.equal((await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "spam" } })).status, 204);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM reports").get().n, 1);
  });

  test("ten a day per device", async () => {
    const a = await device();
    for (let i = 0; i < 10; i++) {
      assert.equal((await call("POST", "/reports", { token: a.token, body: { reason: "chat", text: `reply ${i}` } })).status, 204);
    }
    const r = await call("POST", "/reports", { token: a.token, body: { reason: "chat", text: "one more" } });
    assert.equal(r.status, 429);
    assert.equal(r.body.error, "You've sent a lot of reports today. We'll look at them soon.");
    now += 24 * 3600_000 + 1;
    assert.equal((await call("POST", "/reports", { token: a.token, body: { reason: "chat", text: "tomorrow" } })).status, 204);
  });

  test("resolved reports go after 90 days, open ones stay", async () => {
    const a = await device(), b = await device();
    await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "spam" } });
    await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "name" } });
    db.prepare("UPDATE reports SET status = 'resolved', resolution = 'dismiss', resolved_at = ? WHERE reason = 'spam'").run(now);
    now += 89 * 24 * 3600_000;
    app.cleanup();
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM reports").get().n, 2);
    now += 2 * 24 * 3600_000;
    app.cleanup();
    assert.deepEqual(db.prepare("SELECT reason FROM reports").all().map((r) => r.reason), ["name"]);
  });

  test("deleting the reporter keeps the report without the device id", async () => {
    const a = await device(), b = await device();
    await call("POST", "/reports", { token: a.token, body: { deviceId: b.deviceId, reason: "harassment" } });
    await call("DELETE", "/me", { token: a.token });
    const row = db.prepare("SELECT * FROM reports").get();
    assert.equal(row.reporter, null);
    assert.equal(row.reported, b.deviceId);
  });
});

describe("bans", () => {
  const ban = (id) => db.prepare("UPDATE devices SET banned_at = ? WHERE id = ?").run(now, id);

  test("a banned device gets 403 everywhere but can still delete its data", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    ban(a.deviceId);
    for (const [method, path, body] of [
      ["GET", "/me"], ["GET", "/friends"], ["POST", "/pair-codes", {}], ["PUT", "/me/records", { records: { slimeJump: 5 } }],
      ["POST", "/chat", { message: "hi" }], ["POST", "/reports", { reason: "chat", text: "x" }], ["GET", "/inbox"],
      ["POST", `/friends/${b.deviceId}/interactions`, { kind: "hug" }], ["GET", "/friends/leaderboard"],
    ]) {
      const r = await call(method, path, { token: a.token, body });
      assert.equal(r.status, 403, `${method} ${path}`);
      assert.equal(r.body.error, "This device can't use online features anymore.");
    }
    assert.equal((await call("DELETE", "/me", { token: a.token })).status, 204);
    assert.equal((await call("GET", "/me", { token: a.token })).status, 401);
  });

  test("a banned device disappears from friends, requests, inbox, challenges and the leaderboard", async () => {
    const a = await device("Anna"), b = await device("Ben"), c = await device("Cleo");
    await friends(a, b);
    await call("POST", `/friends/${a.deviceId}/messages`, { token: b.token, body: { preset: "hello" } });
    await call("POST", "/challenges", { token: b.token, body: { friendId: a.deviceId, game: "bubblePop", seed: "1", score: 5 } });
    await call("PUT", "/me/records", { token: b.token, body: { records: { bubblePop: 50 } } });
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    await call("POST", "/friend-requests", { token: c.token, body: { code } });
    ban(b.deviceId);
    ban(c.deviceId);
    assert.equal((await call("GET", "/friends", { token: a.token })).body.length, 0);
    assert.equal((await call("GET", `/friends/${b.deviceId}`, { token: a.token })).status, 404);
    assert.equal((await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "hug" } })).status, 404);
    assert.equal((await call("GET", "/me", { token: a.token })).body.friends, 0);
    assert.equal((await call("GET", "/friend-requests", { token: a.token })).body.incoming.length, 0);
    const inbox = (await call("GET", "/inbox", { token: a.token })).body.events;
    assert.equal(inbox.filter((e) => e.friendId === b.deviceId).length, 0);
    assert.equal((await call("GET", "/challenges", { token: a.token })).body.length, 0);
    assert.deepEqual((await call("GET", "/friends/leaderboard", { token: a.token })).body.games, []);
    // Unbanned, the friendship is back.
    db.prepare("UPDATE devices SET banned_at = NULL WHERE id = ?").run(b.deviceId);
    assert.equal((await call("GET", "/friends", { token: a.token })).body.length, 1);
  });

  test("the codes of a banned device look unknown", async () => {
    const a = await device(), b = await device();
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    ban(a.deviceId);
    assert.equal((await call("GET", `/invites/${code}`)).status, 404);
    const join = await call("POST", "/friend-requests", { token: b.token, body: { code } });
    assert.equal(join.status, 404);
    assert.match(join.body.error, /doesn't exist/);
  });
});

describe("records and leaderboard", () => {
  test("records only go up, are capped and ignore unknown games", async () => {
    const a = await device();
    const put = (records) => call("PUT", "/me/records", { token: a.token, body: { records } });
    assert.equal((await put({ slimeJump: 120, bubblePop: 0, daily: 99, futureGame: 5 })).status, 204);
    assert.equal((await put({ slimeJump: 80 })).status, 204);
    assert.equal((await put({ basketball: 5_000_000 })).status, 204);
    const rows = db.prepare("SELECT game, best FROM records WHERE device_id = ? ORDER BY game").all(a.deviceId);
    assert.deepEqual(rows.map((r) => [r.game, r.best]), [["basketball", 1_000_000], ["slimeJump", 120]]);
    assert.equal((await put({ slimeJump: -1 })).status, 400);
    assert.equal((await put({ slimeJump: 1.5 })).status, 400);
    assert.equal((await put({ slimeJump: "9" })).status, 400);
    assert.equal((await call("PUT", "/me/records", { token: a.token, body: { records: [1, 2] } })).status, 400);
    assert.equal((await call("PUT", "/me/records", { token: a.token, body: {} })).status, 400);
  });

  test("the two new games are known everywhere", async () => {
    const a = await device(), b = await device();
    await friends(a, b);
    for (const game of ["slimeStack", "jellyDash"]) {
      const c = await call("POST", "/challenges", { token: a.token, body: { friendId: b.deviceId, game, seed: "42", score: 10 } });
      assert.equal(c.status, 200, game);
      assert.equal(c.body.game, game);
    }
    await call("PUT", "/me/records", { token: a.token, body: { records: { slimeStack: 7, jellyDash: 9 } } });
    const board = (await call("GET", "/friends/leaderboard", { token: a.token })).body.games.map((g) => g.game);
    assert.deepEqual(board, ["slimeStack", "jellyDash"]);
  });

  test("the leaderboard has me and my friends, best first, only games with entries", async () => {
    const a = await device("Anna"), b = await device("Ben"), c = await device("Cleo"), stranger = await device("Dora");
    await friends(a, b);
    await friends(a, c);
    await call("PUT", "/me/records", { token: a.token, body: { records: { bubblePop: 300, colorMatch: 12 } } });
    now += 1000;
    await call("PUT", "/me/records", { token: b.token, body: { records: { bubblePop: 500 } } });
    now += 1000;
    await call("PUT", "/me/records", { token: c.token, body: { records: { bubblePop: 300 } } });
    await call("PUT", "/me/records", { token: stranger.token, body: { records: { bubblePop: 9999 } } });
    const r = await call("GET", "/friends/leaderboard", { token: a.token });
    assert.equal(r.status, 200);
    assert.deepEqual(r.body.games.map((g) => g.game), ["bubblePop", "colorMatch"]);
    const pop = r.body.games[0].entries;
    // Ties go to whoever got there first.
    assert.deepEqual(pop.map((e) => [e.ownerName, e.best, e.me]), [["Ben", 500, false], ["Anna", 300, true], ["Cleo", 300, false]]);
    assert.deepEqual(Object.keys(pop[0]).sort(), ["best", "deviceId", "me", "ownerName", "skin", "slimeName", "stage"]);
    assert.equal(pop[0].slimeName, "Bens Blob");
    assert.equal(pop[0].stage, "young");
    // Ben sees Anna, but not Cleo (not his friend).
    const ben = (await call("GET", "/friends/leaderboard", { token: b.token })).body.games[0].entries;
    assert.deepEqual(ben.map((e) => e.ownerName), ["Ben", "Anna"]);
  });

  test("records go with the device", async () => {
    const a = await device();
    await call("PUT", "/me/records", { token: a.token, body: { records: { treasureDig: 3 } } });
    await call("DELETE", "/me", { token: a.token });
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM records").get().n, 0);
  });
});

describe("chat usage retention", () => {
  test("counters are kept 400 days", () => {
    db.prepare("INSERT INTO chat_usage (day, provider, requests, failures) VALUES (?, 'groq', 3, 1)").run("2025-09-01");
    db.prepare("INSERT INTO chat_usage (day, provider, requests, failures) VALUES (?, 'groq', 3, 1)").run("2026-10-01");
    app.cleanup();
    assert.deepEqual(db.prepare("SELECT day FROM chat_usage").all().map((r) => r.day), ["2026-10-01"]);
  });
});

describe("migration 2", () => {
  test("upgrades a version 1 database and keeps its devices", () => {
    const old = new DatabaseSync(":memory:");
    old.exec("PRAGMA foreign_keys = ON");
    old.exec("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
    old.exec(migrations[0]);
    old.prepare("INSERT INTO schema_migrations (version, applied_at) VALUES (1, 0)").run();
    old.prepare("INSERT INTO devices (id, token_hash, owner_name, created_at, last_seen) VALUES ('dev_a', 'h', 'Anna', 1, 2)").run();
    migrate(old);
    assert.equal(schemaVersion(old), migrations.length);
    const row = old.prepare("SELECT * FROM devices").get();
    assert.equal(row.last_seen_at, 2);
    assert.equal(row.banned_at, null);
  });
});
