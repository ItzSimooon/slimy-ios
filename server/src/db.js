import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";

// Each entry runs once, in order, inside a transaction. Never edit an
// applied migration; add a new one.
export const migrations = [
  `
  CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    owner_name TEXT NOT NULL,
    accept_requests INTEGER NOT NULL DEFAULT 1,
    share_mood INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
  );
  CREATE TABLE slimes (
    device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    skin TEXT NOT NULL,
    level INTEGER NOT NULL,
    mood TEXT NOT NULL,
    happiness INTEGER NOT NULL,
    equipped TEXT,
    updated_at INTEGER NOT NULL
  );
  CREATE TABLE pair_codes (
    code TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER
  );
  CREATE INDEX pair_codes_device ON pair_codes(device_id);
  CREATE TABLE friend_requests (
    id TEXT PRIMARY KEY,
    from_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    to_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    UNIQUE (from_device, to_device)
  );
  CREATE TABLE friendships (
    a TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    b TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    love INTEGER NOT NULL DEFAULT 0,
    in_love INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (a, b),
    CHECK (a < b)
  );
  CREATE INDEX friendships_b ON friendships(b);
  CREATE TABLE blocks (
    blocker TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    blocked TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (blocker, blocked)
  );
  CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    to_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    from_device TEXT,
    kind TEXT NOT NULL,
    data TEXT,
    created_at INTEGER NOT NULL
  );
  CREATE INDEX events_inbox ON events(to_device, id);
  CREATE INDEX events_caps ON events(from_device, to_device, kind, created_at);
  CREATE TABLE memories (
    a TEXT NOT NULL,
    b TEXT NOT NULL,
    kind TEXT NOT NULL,
    detail TEXT,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (a, b, kind)
  );
  CREATE TABLE challenges (
    id TEXT PRIMARY KEY,
    from_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    to_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    game TEXT NOT NULL,
    seed TEXT NOT NULL,
    from_score INTEGER NOT NULL,
    to_score INTEGER,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    answered_at INTEGER
  );
  CREATE INDEX challenges_to ON challenges(to_device, created_at);
  CREATE INDEX challenges_from ON challenges(from_device, created_at);
  CREATE TABLE coop (
    a TEXT NOT NULL,
    b TEXT NOT NULL,
    week TEXT NOT NULL,
    points_a INTEGER NOT NULL DEFAULT 0,
    points_b INTEGER NOT NULL DEFAULT 0,
    done_at INTEGER,
    PRIMARY KEY (a, b, week)
  );
  CREATE TABLE coop_daily (
    device_id TEXT NOT NULL,
    day TEXT NOT NULL,
    points INTEGER NOT NULL,
    PRIMARY KEY (device_id, day)
  );
  CREATE TABLE idempotency (
    device_id TEXT NOT NULL,
    key TEXT NOT NULL,
    method TEXT NOT NULL,
    path TEXT NOT NULL,
    status INTEGER NOT NULL,
    body TEXT,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (device_id, key)
  );
  `,
  // 2: moderation (reports, bans), friends leaderboard, chat usage, last seen.
  `
  ALTER TABLE devices RENAME COLUMN last_seen TO last_seen_at;
  ALTER TABLE devices ADD COLUMN banned_at INTEGER;
  ALTER TABLE devices ADD COLUMN blocked_names TEXT;
  CREATE INDEX devices_created ON devices(created_at, id);
  CREATE INDEX devices_last_seen ON devices(last_seen_at);
  CREATE TABLE reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    reporter TEXT REFERENCES devices(id) ON DELETE SET NULL,
    reported TEXT REFERENCES devices(id) ON DELETE SET NULL,
    reason TEXT NOT NULL,
    note TEXT,
    text TEXT,
    status TEXT NOT NULL DEFAULT 'open',
    resolution TEXT,
    created_at INTEGER NOT NULL,
    resolved_at INTEGER
  );
  CREATE INDEX reports_status ON reports(status, id);
  CREATE INDEX reports_reported ON reports(reported);
  CREATE INDEX reports_reporter ON reports(reporter, created_at);
  CREATE TABLE records (
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    game TEXT NOT NULL,
    best INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (device_id, game)
  );
  CREATE TABLE chat_usage (
    day TEXT NOT NULL,
    provider TEXT NOT NULL,
    requests INTEGER NOT NULL DEFAULT 0,
    failures INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, provider)
  );
  `,
  // 3: caring for one slime together (codes, pairs with the shared snapshot, care events).
  `
  CREATE TABLE care_codes (
    code TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER
  );
  CREATE INDEX care_codes_device ON care_codes(device_id);
  CREATE TABLE care_pairs (
    id TEXT PRIMARY KEY,
    host TEXT NOT NULL UNIQUE REFERENCES devices(id) ON DELETE CASCADE,
    joiner TEXT NOT NULL UNIQUE REFERENCES devices(id) ON DELETE CASCADE,
    snapshot TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    CHECK (host <> joiner)
  );
  CREATE TABLE care_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pair_id TEXT NOT NULL REFERENCES care_pairs(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    food TEXT,
    game TEXT,
    amount REAL,
    at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE INDEX care_events_pair ON care_events(pair_id, id);
  CREATE INDEX care_events_created ON care_events(created_at);
  `,
];

export function openDatabase(path = ":memory:") {
  if (path !== ":memory:") mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec("PRAGMA foreign_keys = ON");
  if (path !== ":memory:") db.exec("PRAGMA journal_mode = WAL");
  migrate(db);
  return db;
}

export function migrate(db) {
  db.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
  const applied = new Set(db.prepare("SELECT version FROM schema_migrations").all().map((r) => r.version));
  migrations.forEach((sql, i) => {
    const version = i + 1;
    if (applied.has(version)) return;
    transaction(db, () => {
      db.exec(sql);
      db.prepare("INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)").run(version, Date.now());
    });
  });
}

export function schemaVersion(db) {
  return db.prepare("SELECT MAX(version) AS v FROM schema_migrations").get().v ?? 0;
}

/** Runs `fn` in a transaction; rolls back if it throws. Not re-entrant. */
export function transaction(db, fn) {
  db.exec("BEGIN IMMEDIATE");
  try {
    const result = fn();
    db.exec("COMMIT");
    return result;
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}
