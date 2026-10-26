import { randomBytes, scrypt, timingSafeEqual } from "node:crypto";
import { chmodSync, chownSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

// The admin passphrase, stored only as an scrypt hash in one line:
//   scrypt$32768$8$1$<salt, base64>$<key, base64>

export const DEFAULT_HASH_FILE = "/etc/slimy/admin.hash";
export const SCRYPT = { N: 2 ** 15, r: 8, p: 1, keyLength: 64, saltLength: 16 };
export const MIN_LENGTH = 12;
export const MAX_LENGTH = 1024;

function derive(passphrase, salt, { N, r, p, keyLength }) {
  return new Promise((resolve, reject) => {
    // 128 * N * r bytes is exactly Node's default limit for these settings, so allow a bit more.
    scrypt(passphrase.normalize("NFC"), salt, keyLength, { N, r, p, maxmem: 256 * N * r }, (error, key) =>
      (error ? reject(error) : resolve(key)));
  });
}

/** A new hash line for `passphrase`. */
export async function hashPassphrase(passphrase) {
  if (typeof passphrase !== "string") throw new TypeError("The passphrase must be text.");
  const salt = randomBytes(SCRYPT.saltLength);
  const key = await derive(passphrase, salt, SCRYPT);
  return ["scrypt", SCRYPT.N, SCRYPT.r, SCRYPT.p, salt.toString("base64"), key.toString("base64")].join("$");
}

/** The parts of a hash line, or null when it isn't one. */
export function parseHash(line) {
  const parts = String(line ?? "").trim().split("$");
  if (parts.length !== 6 || parts[0] !== "scrypt") return null;
  const [N, r, p] = parts.slice(1, 4).map(Number);
  const salt = Buffer.from(parts[4], "base64");
  const key = Buffer.from(parts[5], "base64");
  const powerOfTwo = Number.isInteger(N) && N > 1 && (N & (N - 1)) === 0;
  if (!powerOfTwo || N > 2 ** 20 || !Number.isInteger(r) || r < 1 || r > 32 || !Number.isInteger(p) || p < 1 || p > 16) return null;
  if (salt.length < 16 || key.length < 32) return null;
  return { N, r, p, salt, key };
}

/** True when `passphrase` matches the hash line. Compares in constant time. */
export async function verifyPassphrase(passphrase, line) {
  const parsed = parseHash(line);
  if (!parsed || typeof passphrase !== "string") return false;
  const key = await derive(passphrase, parsed.salt, { ...parsed, keyLength: parsed.key.length });
  return key.length === parsed.key.length && timingSafeEqual(key, parsed.key);
}

/** Why a new passphrase can't be used, or null. */
export function passphraseProblem(passphrase) {
  const length = [...String(passphrase ?? "")].length;
  if (length < MIN_LENGTH) return `Use at least ${MIN_LENGTH} characters. A few random words work well.`;
  if (length > MAX_LENGTH) return `Use at most ${MAX_LENGTH} characters.`;
  if (passphrase.trim() !== passphrase) return "The passphrase can't start or end with a space.";
  return null;
}

/** The numeric id of a group from /etc/group, or null. */
export function groupId(name, groupFile = "/etc/group") {
  try {
    for (const line of readFileSync(groupFile, "utf8").split("\n")) {
      const [group, , gid] = line.split(":");
      if (group === name && /^\d+$/.test(gid ?? "")) return Number(gid);
    }
  } catch {
    // No group file (Windows, minimal containers): ownership stays as it is.
  }
  return null;
}

/**
 * Replaces `file` with the hash line, atomically, mode 0640. An existing
 * file keeps its owner; a new one written by root goes to root:`group`, so
 * the service user can read it.
 */
export function writeHashFile(file, line, group = "slimy") {
  mkdirSync(dirname(file), { recursive: true });
  const temp = `${file}.${process.pid}.tmp`;
  writeFileSync(temp, `${line}\n`, { mode: 0o640 });
  try {
    chmodSync(temp, 0o640);
    const root = process.getuid?.() === 0;
    let owner = null;
    if (existsSync(file)) {
      const old = statSync(file);
      owner = { uid: old.uid, gid: old.gid };
    } else if (root) {
      const gid = groupId(group);
      if (gid !== null) owner = { uid: 0, gid };
    }
    if (owner && root) chownSync(temp, owner.uid, owner.gid);
    renameSync(temp, file);
  } catch (error) {
    rmSync(temp, { force: true });
    throw error;
  }
  const info = statSync(file);
  return { mode: (info.mode & 0o777).toString(8).padStart(4, "0"), gid: info.gid };
}
