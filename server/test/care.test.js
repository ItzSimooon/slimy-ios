import { after, beforeEach, describe, test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { ALREADY_CARING, LIMITS, NOT_CARING, SNAPSHOT_CONFLICT, createApp } from "../src/app.js";
import { createChat } from "../src/chat.js";
import { openDatabase } from "../src/db.js";
import { CODE_ALPHABET } from "../src/util.js";

// Caring for one slime together, over real HTTP with an in-memory database
// and a clock the test controls.

let server, base, db, app, now;
const DAY = 24 * 3600_000;

async function start() {
  if (server) await new Promise((r) => server.close(r));
  now = Date.UTC(2026, 9, 25, 12, 0, 0);
  db = openDatabase(":memory:");
  const chat = createChat({ env: {}, log: () => {} });
  app = createApp({ db, clock: () => now, chat });
  server = createServer(app.listener);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}/api/v1`;
}

async function call(method, path, { token, body, key, raw } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers["Idempotency-Key"] = key;
  const payload = raw ?? (body === undefined ? undefined : JSON.stringify(body));
  const res = await fetch(base + path, { method, headers, body: payload });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function device(name = "Simon") {
  const r = await call("POST", "/devices", { body: { ownerName: name } });
  assert.equal(r.status, 200);
  await call("PUT", "/me/slime", { token: r.body.token, body: { name: `${name}s Blob`, skin: "lime", level: 5, mood: "idle", happiness: 80 } });
  return r.body;
}

const newCode = async (d) => {
  const r = await call("POST", "/care/codes", { token: d.token, body: {} });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.body.code;
};

/** `host` makes a code, `joiner` types it. */
async function share(host, joiner) {
  const code = await newCode(host);
  const r = await call("POST", "/care/join", { token: joiner.token, body: { code } });
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.body;
}

const care = (d, after) => call("GET", after == null ? "/care" : `/care?after=${after}`, { token: d.token });
const send = (d, body, key) => call("POST", "/care/events", { token: d.token, body: { at: new Date(now).toISOString(), ...body }, key });
const put = (d, snapshot, version, key) => call("PUT", "/care/snapshot", { token: d.token, body: { snapshot, version }, key });

const SNAPSHOT = { name: "Blob", skin: "axolotl", level: 7, needs: { hunger: 60, fun: 50 }, updatedAt: "2026-10-25T12:00:00Z" };

beforeEach(start);
after(() => server?.close());

describe("care codes", () => {
  test("are 6 characters from the pair code alphabet, valid for 10 minutes", async () => {
    const a = await device();
    const r = await call("POST", "/care/codes", { token: a.token, body: {} });
    assert.equal(r.status, 200);
    assert.match(r.body.code, new RegExp(`^[${CODE_ALPHABET}]{6}$`));
    assert.equal(r.body.expiresAt, new Date(now + 10 * 60_000).toISOString().replace(".000", ""));
    assert.deepEqual(Object.keys(r.body).sort(), ["code", "expiresAt"]);
  });

  test("a new code replaces the unused one", async () => {
    const a = await device(), b = await device();
    const first = await newCode(a);
    const second = await newCode(a);
    assert.notEqual(first, second);
    assert.equal((await call("POST", "/care/join", { token: b.token, body: { code: first } })).status, 404);
    assert.equal((await call("POST", "/care/join", { token: b.token, body: { code: second } })).status, 200);
  });

  test("need auth and are refused while caring together", async () => {
    const a = await device(), b = await device();
    assert.equal((await call("POST", "/care/codes", { body: {} })).status, 401);
    await share(a, b);
    for (const d of [a, b]) {
      const r = await call("POST", "/care/codes", { token: d.token, body: {} });
      assert.equal(r.status, 409);
      assert.equal(r.body.error, ALREADY_CARING);
      assert.equal(r.body.error, "You're already caring for a slime together.");
    }
  });

  test("friend codes and care codes don't mix", async () => {
    const a = await device(), b = await device();
    const careCode = await newCode(a);
    const friendCode = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    assert.equal((await call("POST", "/friend-requests", { token: b.token, body: { code: careCode } })).status, 404);
    assert.equal((await call("POST", "/care/join", { token: b.token, body: { code: friendCode } })).status, 404);
    assert.equal((await call("GET", `/invites/${careCode}`)).status, 404);
  });

  test("are rate limited", async () => {
    const a = await device();
    for (let i = 0; i < 20; i++) assert.equal((await call("POST", "/care/codes", { token: a.token, body: {} })).status, 200);
    const r = await call("POST", "/care/codes", { token: a.token, body: {} });
    assert.equal(r.status, 429);
    assert.match(r.body.error, /a lot of codes/);
  });
});

describe("joining", () => {
  test("makes the code's owner the host and tells both sides", async () => {
    const anna = await device("Anna"), ben = await device("Ben");
    const joined = await share(anna, ben);
    assert.deepEqual(joined, { partner: { deviceId: anna.deviceId, ownerName: "Anna" }, snapshot: null, version: 0 });
    const host = await care(anna);
    assert.deepEqual(host.body.partner, { deviceId: ben.deviceId, ownerName: "Ben", role: "joiner" });
    const joiner = await care(ben);
    assert.deepEqual(joiner.body.partner, { deviceId: anna.deviceId, ownerName: "Anna", role: "host" });
    assert.equal(joiner.body.snapshot, null);
    assert.equal(joiner.body.version, 0);
    assert.deepEqual(joiner.body.events, []);
  });

  test("codes are single use, expire, and your own is refused", async () => {
    const a = await device(), b = await device(), c = await device();
    const code = await newCode(a);
    assert.equal((await call("POST", "/care/join", { token: a.token, body: { code } })).status, 400);
    await call("POST", "/care/join", { token: b.token, body: { code } });
    const used = await call("POST", "/care/join", { token: c.token, body: { code } });
    assert.equal(used.status, 404);
    assert.match(used.body.error, /doesn't exist/);
    const d = await device(), e = await device();
    const old = await newCode(d);
    now += 11 * 60_000;
    const expired = await call("POST", "/care/join", { token: e.token, body: { code: old.toLowerCase() } });
    assert.equal(expired.status, 410);
    assert.match(expired.body.error, /expired/);
    assert.equal((await call("POST", "/care/join", { token: e.token, body: { code: "AB" } })).status, 400);
  });

  test("409 when either side already cares for a slime", async () => {
    const a = await device(), b = await device(), c = await device(), d = await device();
    await share(a, b);
    const free = await newCode(c);
    // b is a joiner already.
    const busy = await call("POST", "/care/join", { token: b.token, body: { code: free } });
    assert.equal(busy.status, 409);
    assert.equal(busy.body.error, ALREADY_CARING);
    // The host side is taken: c's code is still unused, but c joins d first.
    const dCode = await newCode(d);
    const cCode = await newCode(c);
    await call("POST", "/care/join", { token: c.token, body: { code: dCode } });
    // c's open code went away when c joined.
    assert.equal((await call("POST", "/care/join", { token: a.token, body: { code: cCode } })).status, 404);
  });

  test("a code whose owner got paired in between is refused with 409", async () => {
    const a = await device(), b = await device(), c = await device();
    const code = await newCode(a);
    // Written directly: a pair that came about without using up a's code.
    db.prepare("INSERT INTO care_pairs (id, host, joiner, created_at, updated_at) VALUES ('care_x', ?, ?, ?, ?)").run(a.deviceId, c.deviceId, now, now);
    const r = await call("POST", "/care/join", { token: b.token, body: { code } });
    assert.equal(r.status, 409);
    assert.equal(r.body.error, "They're already caring for a slime with someone else.");
  });

  test("the same join again answers like the first time", async () => {
    const a = await device(), b = await device();
    const code = await newCode(a);
    const first = await call("POST", "/care/join", { token: b.token, body: { code } });
    const again = await call("POST", "/care/join", { token: b.token, body: { code } });
    assert.equal(again.status, 200);
    assert.deepEqual(again.body, first.body);
    // Only for the joiner: the host typing its own used code learns nothing new.
    assert.equal((await call("POST", "/care/join", { token: a.token, body: { code } })).status, 404);
  });

  test("a saved join answer goes when the pair ends", async () => {
    const a = await device(), b = await device();
    const code = await newCode(a);
    assert.equal((await call("POST", "/care/join", { token: b.token, body: { code }, key: "join-1" })).status, 200);
    await put(a, SNAPSHOT, 1);
    // A second join with a new key answers with the snapshot, and that answer is saved too.
    assert.deepEqual((await call("POST", "/care/join", { token: b.token, body: { code }, key: "join-2" })).body.snapshot, SNAPSHOT);
    const receipts = () => db.prepare("SELECT COUNT(*) AS n FROM idempotency WHERE path = '/care/join'").get().n;
    assert.equal(receipts(), 2);
    await call("DELETE", "/care", { token: a.token });
    assert.equal(receipts(), 0);
  });

  test("blocked people and banned owners look like unknown codes", async () => {
    const a = await device(), b = await device(), c = await device();
    await call("POST", "/blocks", { token: a.token, body: { deviceId: b.deviceId } });
    const code = await newCode(a);
    assert.equal((await call("POST", "/care/join", { token: b.token, body: { code } })).status, 404);
    db.prepare("UPDATE devices SET banned_at = ? WHERE id = ?").run(now, a.deviceId);
    assert.equal((await call("POST", "/care/join", { token: c.token, body: { code } })).status, 404);
  });

  test("guessing codes gets locked out, together with friend codes", async () => {
    const a = await device();
    for (let i = 0; i < 4; i++) await call("POST", "/friend-requests", { token: a.token, body: { code: `ZZZZZ${i}` } });
    let last;
    for (let i = 0; i < 6; i++) last = await call("POST", "/care/join", { token: a.token, body: { code: `YYYYY${i}` } });
    assert.equal(last.status, 429);
    assert.match(last.body.error, /Too many wrong codes/);
  });
});

describe("care events", () => {
  test("reach the partner, not yourself, with names, cursor and wire shape", async () => {
    const anna = await device("Anna"), ben = await device("Ben");
    await share(anna, ben);
    const fed = await send(anna, { kind: "feed", food: "cake", amount: 25 });
    assert.equal(fed.status, 200);
    const e = fed.body.event;
    assert.deepEqual(Object.keys(e).sort(), ["amount", "at", "createdAt", "deviceId", "food", "game", "id", "kind", "ownerName"]);
    assert.deepEqual({ ...e, id: undefined }, {
      id: undefined, deviceId: anna.deviceId, ownerName: "Anna", kind: "feed", food: "cake", game: null, amount: 25,
      at: "2026-10-25T12:00:00Z", createdAt: "2026-10-25T12:00:00Z",
    });
    assert.match(e.id, /^\d+$/);
    await send(ben, { kind: "pet" });
    await send(anna, { kind: "play", game: "bubblePop", amount: 12.5 });

    const forBen = await care(ben);
    assert.deepEqual(forBen.body.events.map((x) => [x.kind, x.ownerName]), [["feed", "Anna"], ["play", "Anna"]]);
    assert.equal(forBen.body.events[1].game, "bubblePop");
    assert.equal(forBen.body.events[1].amount, 12.5);
    assert.equal(typeof forBen.body.cursor, "string");
    const forAnna = await care(anna);
    assert.deepEqual(forAnna.body.events.map((x) => [x.kind, x.ownerName]), [["pet", "Ben"]]);

    const next = await care(ben, forBen.body.cursor);
    assert.deepEqual(next.body.events, []);
    assert.equal(next.body.cursor, forBen.body.cursor);
    await send(anna, { kind: "bath" });
    assert.deepEqual((await care(ben, forBen.body.cursor)).body.events.map((x) => x.kind), ["bath"]);
  });

  test("come 100 at a time", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    for (let i = 0; i < 130; i++) await send(a, { kind: "pet" });
    const first = await care(b);
    assert.equal(first.body.events.length, LIMITS.carePage);
    const rest = await care(b, first.body.cursor);
    assert.equal(rest.body.events.length, 30);
  });

  test("every kind is accepted; foods, games and amounts are checked", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    for (const kind of ["feed", "play", "bath", "pet", "sleep", "wake", "gift"]) assert.equal((await send(a, { kind })).status, 200, kind);
    // The app's daily game counts too.
    assert.equal((await send(a, { kind: "play", game: "daily" })).status, 200);
    assert.equal((await send(a, { kind: "gift", food: "starJelly", amount: 0 })).status, 200);
    assert.equal((await send(a, { kind: "play", game: "slimeStack", amount: 200 })).status, 200);
    for (const bad of [
      { kind: "kiss" }, { kind: "feed", food: "rocks" }, { kind: "play", game: "chess" }, { kind: "feed", amount: 201 },
      { kind: "feed", amount: -1 }, { kind: "feed", amount: "5" }, { kind: "feed", at: "yesterday" }, { kind: "feed", at: 5 },
    ]) {
      const r = await send(a, bad);
      assert.equal(r.status, 400, JSON.stringify(bad));
      assert.equal(typeof r.body.error, "string");
    }
    assert.equal((await call("POST", "/care/events", { token: a.token, body: { kind: "feed" } })).status, 400);
  });

  test("times in the future become now; late ones keep their time", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    const future = await send(a, { kind: "feed", at: new Date(now + 3 * 3600_000).toISOString() });
    assert.equal(future.body.event.at, "2026-10-25T12:00:00Z");
    const late = await send(a, { kind: "feed", at: "2026-10-25T09:30:00+02:00" });
    assert.equal(late.body.event.at, "2026-10-25T07:30:00Z");
    const ancient = await send(a, { kind: "feed", at: "2020-01-01T00:00:00Z" });
    assert.equal(ancient.body.event.at, new Date(now - 30 * DAY).toISOString().replace(".000", ""));
  });

  test("409 when not caring together", async () => {
    const a = await device();
    const r = await send(a, { kind: "feed" });
    assert.equal(r.status, 409);
    assert.equal(r.body.error, NOT_CARING);
  });

  test("an Idempotency-Key sends an event once", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    const first = await send(a, { kind: "feed", food: "apple" }, "evt-1");
    const again = await send(a, { kind: "feed", food: "apple" }, "evt-1");
    assert.deepEqual(again.body, first.body);
    assert.equal((await care(b)).body.events.length, 1);
  });

  test("600 a day per device", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    for (let i = 0; i < LIMITS.careEventsPerDay; i++) {
      // Stays under the general limit of 1200 an hour.
      if (i % 500 === 499) now += 3600_000;
      assert.equal((await send(a, { kind: "pet" })).status, 200);
    }
    const r = await send(a, { kind: "pet" });
    assert.equal(r.status, 429);
    assert.match(r.body.error, /a lot of care/);
    assert.equal((await send(b, { kind: "pet" })).status, 200);
  });

  test("are kept 30 days", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    await send(a, { kind: "feed" });
    now += 29 * DAY;
    app.cleanup();
    assert.equal((await care(b)).body.events.length, 1);
    now += 2 * DAY;
    app.cleanup();
    assert.equal((await care(b)).body.events.length, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM care_events").get().n, 0);
  });
});

describe("snapshot", () => {
  test("versions go up by one; GET returns the newest", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    const r = await put(a, SNAPSHOT, 1);
    assert.equal(r.status, 200);
    assert.deepEqual(r.body, { version: 1 });
    const seen = await care(b);
    assert.deepEqual(seen.body.snapshot, SNAPSHOT);
    assert.equal(seen.body.version, 1);
    assert.deepEqual((await put(b, { ...SNAPSHOT, level: 8 }, 2)).body, { version: 2 });
    assert.equal((await care(a)).body.snapshot.level, 8);
  });

  test("a stale version gets 409 with the current state", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    await put(a, SNAPSHOT, 1);
    for (const version of [1, 3]) {
      const r = await put(b, { ...SNAPSHOT, level: 99 }, version);
      assert.equal(r.status, 409);
      assert.deepEqual(r.body, { error: SNAPSHOT_CONFLICT, version: 1, snapshot: SNAPSHOT });
    }
    const empty = await device(), other = await device();
    await share(empty, other);
    const first = await put(empty, SNAPSHOT, 2);
    assert.deepEqual(first.body, { error: SNAPSHOT_CONFLICT, version: 0, snapshot: null });
  });

  test("a conflict is never replayed for an Idempotency-Key; a success is", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    await put(a, SNAPSHOT, 1);
    const conflict = await put(b, SNAPSHOT, 1, "snap-1");
    assert.equal(conflict.status, 409);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM idempotency WHERE key = 'snap-1'").get().n, 0);
    const ok = await put(b, SNAPSHOT, 2, "snap-1");
    assert.deepEqual(ok.body, { version: 2 });
    assert.deepEqual((await put(b, SNAPSHOT, 2, "snap-1")).body, { version: 2 });
    assert.equal((await care(a)).body.version, 2);
  });

  test("the slime's name passes the name filter, other fields are opaque", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    for (const name of ["fuck", "b1tch face", "www.spam.com", "Dick"]) {
      const r = await put(a, { ...SNAPSHOT, name }, 1);
      assert.equal(r.status, 400, name);
      assert.equal(r.body.error, "Please choose a different name.");
    }
    assert.equal((await put(a, { ...SNAPSHOT, name: "  " }, 1)).status, 200);
    assert.equal((await put(a, { ...SNAPSHOT, name: 5, notes: "fuck" }, 2)).status, 200);
    assert.equal((await put(a, { ...SNAPSHOT, name: "Cassandra" }, 3)).status, 200);
  });

  test("a very long name is refused at once instead of running the filter over it", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    const started = performance.now();
    const r = await put(a, { ...SNAPSHOT, name: "a-".repeat(30_000) }, 1);
    assert.equal(r.status, 400);
    assert.equal(r.body.error, "Please choose a different name.");
    assert.ok(performance.now() - started < 2000, "the server stays responsive");
    assert.equal((await put(a, { ...SNAPSHOT, name: "a".repeat(LIMITS.snapshotNameChars + 1) }, 1)).status, 400);
    // Long emoji names from the app still fit.
    assert.equal((await put(a, { ...SNAPSHOT, name: "\u{1F468}‍\u{1F469}‍\u{1F467}".repeat(16) }, 1)).status, 200);
  });

  test("a name an admin reset can't come back through the snapshot", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    db.prepare("UPDATE devices SET blocked_names = ? WHERE id = ?").run(JSON.stringify(["rudename"]), a.deviceId);
    assert.equal((await put(b, { ...SNAPSHOT, name: "Rude Name" }, 1)).status, 400);
  });

  test("must be an object of at most 64 KB with a whole version", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    for (const body of [
      { snapshot: [1, 2], version: 1 }, { snapshot: "x", version: 1 }, { snapshot: null, version: 1 }, { version: 1 },
      { snapshot: SNAPSHOT }, { snapshot: SNAPSHOT, version: 0 }, { snapshot: SNAPSHOT, version: 1.5 }, { snapshot: SNAPSHOT, version: "1" },
    ]) {
      assert.equal((await call("PUT", "/care/snapshot", { token: a.token, body })).status, 400, JSON.stringify(body));
    }
    const fits = { ...SNAPSHOT, blob: "x".repeat(LIMITS.snapshotBytes - JSON.stringify({ ...SNAPSHOT, blob: "" }).length) };
    assert.equal(Buffer.byteLength(JSON.stringify(fits)), LIMITS.snapshotBytes);
    assert.equal((await put(a, fits, 1)).status, 200);
    assert.deepEqual((await care(b)).body.snapshot, fits);
    const tooBig = await put(a, { ...fits, blob: `${fits.blob}x` }, 2);
    assert.equal(tooBig.status, 413);
    assert.match(tooBig.body.error, /too large/);
    // Other routes keep the 32 KB limit.
    const big = await call("POST", "/care/events", { token: a.token, raw: JSON.stringify({ kind: "pet", pad: "x".repeat(40 * 1024) }) });
    assert.equal(big.status, 413);
  });

  test("409 when not caring together", async () => {
    const a = await device();
    const r = await put(a, SNAPSHOT, 1);
    assert.equal(r.status, 409);
    assert.deepEqual(r.body, { error: NOT_CARING });
  });

  test("are rate limited", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    for (let v = 1; v <= LIMITS.snapshotsPerHour; v++) assert.equal((await put(a, SNAPSHOT, v)).status, 200);
    assert.equal((await put(a, SNAPSHOT, LIMITS.snapshotsPerHour + 1)).status, 429);
  });
});

describe("stopping", () => {
  test("either side ends it for both; snapshot and events are deleted", async () => {
    for (const leaver of ["host", "joiner"]) {
      const host = await device("Anna"), joiner = await device("Ben");
      await share(host, joiner);
      await put(host, SNAPSHOT, 1);
      await send(host, { kind: "feed" });
      await send(joiner, { kind: "pet" });
      const r = await call("DELETE", "/care", { token: (leaver === "host" ? host : joiner).token });
      assert.equal(r.status, 204);
      for (const d of [host, joiner]) {
        assert.deepEqual((await care(d)).body, { partner: null, snapshot: null, version: 0, events: [], cursor: "0" });
        assert.equal((await send(d, { kind: "pet" })).status, 409);
      }
      assert.equal(db.prepare("SELECT COUNT(*) AS n FROM care_pairs").get().n, 0);
      assert.equal(db.prepare("SELECT COUNT(*) AS n FROM care_events").get().n, 0);
      // Both can start again, with anyone.
      await share(joiner, host);
      await call("DELETE", "/care", { token: host.token });
    }
  });

  test("works when not caring and drops your open code", async () => {
    const a = await device(), b = await device();
    const code = await newCode(a);
    assert.equal((await call("DELETE", "/care", { token: a.token })).status, 204);
    assert.equal((await call("POST", "/care/join", { token: b.token, body: { code } })).status, 404);
  });

  test("unpaired, GET /care keeps the cursor it was given", async () => {
    const a = await device();
    assert.deepEqual((await care(a, "42")).body, { partner: null, snapshot: null, version: 0, events: [], cursor: "42" });
    assert.equal((await care(a, "nonsense")).body.cursor, "0");
  });

  test("deleting your data unpairs the partner", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    await put(a, SNAPSHOT, 1);
    await send(a, { kind: "feed" });
    assert.equal((await call("DELETE", "/me", { token: a.token })).status, 204);
    const seen = await care(b);
    assert.equal(seen.body.partner, null);
    assert.equal(seen.body.snapshot, null);
    for (const table of ["care_pairs", "care_events", "care_codes"]) {
      assert.equal(db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n, 0, table);
    }
  });

  test("a ban unpairs the partner and the banned device can't take part", async () => {
    const a = await device(), b = await device(), c = await device();
    await share(a, b);
    await put(a, SNAPSHOT, 1);
    db.prepare("UPDATE devices SET banned_at = ? WHERE id = ?").run(now, a.deviceId);
    for (const [method, path] of [["GET", "/care"], ["POST", "/care/codes"], ["DELETE", "/care"]]) {
      assert.equal((await call(method, path, { token: a.token, body: method === "GET" ? undefined : {} })).status, 403, path);
    }
    assert.equal((await care(b)).body.partner, null);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM care_pairs").get().n, 0);
    // The partner is free again.
    await share(b, c);
    // Unbanned, nothing comes back.
    db.prepare("UPDATE devices SET banned_at = NULL WHERE id = ?").run(a.deviceId);
    assert.equal((await care(a)).body.partner, null);
  });

  test("the cleanup ends pairs of devices banned straight in the database", async () => {
    const a = await device(), b = await device();
    await share(a, b);
    db.prepare("UPDATE devices SET banned_at = ? WHERE id = ?").run(now, b.deviceId);
    app.cleanup();
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM care_pairs").get().n, 0);
  });

  test("blocking the partner ends caring together", async () => {
    const a = await device(), b = await device(), c = await device();
    await share(a, b);
    await call("POST", "/blocks", { token: c.token, body: { deviceId: a.deviceId } });
    assert.notEqual((await care(a)).body.partner, null, "blocking someone else changes nothing");
    await call("POST", "/blocks", { token: b.token, body: { deviceId: a.deviceId } });
    assert.equal((await care(a)).body.partner, null);
    assert.equal((await care(b)).body.partner, null);
  });

  test("used and expired codes are cleaned up after a day", async () => {
    const a = await device(), b = await device(), c = await device();
    await share(a, b);
    await newCode(c);
    const count = () => db.prepare("SELECT COUNT(*) AS n FROM care_codes").get().n;
    app.cleanup();
    assert.equal(count(), 2);
    now += DAY + 11 * 60_000;
    app.cleanup();
    assert.equal(count(), 0);
  });
});

describe("love stays for older apps", () => {
  test("kisses and falling in love still work next to caring together", async () => {
    const a = await device(), b = await device();
    const code = (await call("POST", "/pair-codes", { token: a.token, body: {} })).body.code;
    const join = await call("POST", "/friend-requests", { token: b.token, body: { code } });
    await call("POST", `/friend-requests/${join.body.request.id}/accept`, { token: a.token, body: {} });
    await share(a, b);
    db.prepare("UPDATE friendships SET love = 96").run();
    const kiss = await call("POST", `/friends/${b.deviceId}/interactions`, { token: a.token, body: { kind: "kiss" } });
    assert.equal(kiss.status, 200);
    assert.equal(kiss.body.love, 100);
    assert.equal(kiss.body.fellInLove, true);
    const list = await call("GET", "/friends", { token: b.token });
    assert.equal(list.body[0].inLove, true);
  });
});
