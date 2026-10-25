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
