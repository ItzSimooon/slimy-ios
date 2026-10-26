import { createHash, randomBytes, randomInt } from "node:crypto";

/**
 * An error with an HTTP status and an English message the app shows as is.
 * `extra` adds fields next to `error` in the answer (e.g. a 409 with the
 * current state).
 */
export class HttpError extends Error {
  constructor(status, message, extra = null) {
    super(message);
    this.status = status;
    this.extra = extra;
  }

  /** The answer body: `{error, ...extra}`. */
  get body() {
    return { error: this.message, ...(this.extra ?? {}) };
  }
}

export const fail = (status, message, extra = null) => {
  throw new HttpError(status, message, extra);
};

/** ISO 8601 in UTC without milliseconds; Swift's .iso8601 rejects them. */
export const iso = (ms) => (ms == null ? null : new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z"));

export const sha256 = (text) => createHash("sha256").update(text).digest("hex");

export const newToken = () => randomBytes(32).toString("base64url");

const ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
export function newId(prefix, length = 12) {
  let out = "";
  for (let i = 0; i < length; i++) out += ID_ALPHABET[randomInt(ID_ALPHABET.length)];
  return `${prefix}_${out}`;
}

/** Pair codes: no 0/O/1/I/L, so they read out loud without mistakes. */
export const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export function newPairCode() {
  let out = "";
  for (let i = 0; i < 6; i++) out += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return out;
}

export const dayKey = (ms) => new Date(ms).toISOString().slice(0, 10);

/** ISO week, e.g. "2026-W43", the same rule as the app's Calendar. */
export function weekKey(ms) {
  const d = new Date(ms);
  const day = (d.getUTCDay() + 6) % 7;
  d.setUTCDate(d.getUTCDate() - day + 3);
  const firstThursday = new Date(Date.UTC(d.getUTCFullYear(), 0, 4));
  const week = 1 + Math.round(((d - firstThursday) / 86400000 - 3 + ((firstThursday.getUTCDay() + 6) % 7)) / 7);
  return `${d.getUTCFullYear()}-W${String(week).padStart(2, "0")}`;
}

export const startOfUtcDay = (ms) => {
  const d = new Date(ms);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
};

/** The friendship row key: the two ids sorted. */
export const pair = (x, y) => (x < y ? [x, y] : [y, x]);

// --- Input validation -------------------------------------------------------

export const ENUMS = {
  skin: ["lime", "bubblegum", "ocean", "grape", "lava", "gold", "ghost", "void", "axolotl"],
  mood: ["idle", "sad", "sleep"],
  stage: ["baby", "young", "grown"],
  food: ["apple", "carrot", "berry", "mushroom", "cookie", "soup", "honey", "pudding", "cake", "starJelly"],
  interaction: ["hug", "visit", "gift", "kiss"],
  game: ["snackCatcher", "slimeJump", "bubblePop", "memoryGarden", "slimeRacing", "rhythmBounce", "gardenPuzzle",
    "treasureDig", "basketball", "colorMatch", "slimeStack", "jellyDash"],
  message: ["hello", "missYou", "goodNight", "goodMorning", "letsPlay", "thanks", "congrats", "proud"],
  reaction: ["none", "eat", "play", "love", "bath", "melt", "zap", "confused", "wave", "celebrate", "stretch", "sulk",
    "hide", "curious", "blush"],
  reportReason: ["name", "harassment", "spam", "chat", "other"],
  careKind: ["feed", "play", "bath", "pet", "sleep", "wake", "gift"],
  emotion: ["joy", "sadness", "anger", "fear", "love", "curiosity", "boredom", "excitement", "sleepiness"],
};

export function text(value, field, { min = 1, max = 40, fallback } = {}) {
  if (value == null && fallback !== undefined) return fallback;
  if (typeof value !== "string") fail(400, `"${field}" is missing or not text.`);
  const trimmed = value.trim().replace(/\s+/g, " ");
  if ([...trimmed].length < min) fail(400, `"${field}" is too short.`);
  return [...trimmed].slice(0, max).join("");
}

/** Optional free text: null when missing or blank, otherwise trimmed and cut to `max`. */
export function optionalText(value, field, max) {
  if (value == null) return null;
  if (typeof value !== "string") fail(400, `"${field}" must be text.`);
  const trimmed = value.trim().replace(/\s+/g, " ");
  return trimmed ? [...trimmed].slice(0, max).join("") : null;
}

export function int(value, field, min, max) {
  if (!Number.isInteger(value) || value < min || value > max) fail(400, `"${field}" must be a number from ${min} to ${max}.`);
  return value;
}

export function oneOf(value, field, list) {
  if (!list.includes(value)) fail(400, `"${field}" has an unknown value.`);
  return value;
}

export function bool(value, field) {
  if (typeof value !== "boolean") fail(400, `"${field}" must be true or false.`);
  return value;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:?\d{2})$/;

/** An ISO 8601 date with a time zone ("2026-10-25T12:00:00Z"), as milliseconds. */
export function date(value, field) {
  const ms = typeof value === "string" && ISO_DATE.test(value) ? Date.parse(value) : Number.NaN;
  if (!Number.isFinite(ms)) fail(400, `"${field}" must be a date like 2026-10-25T12:00:00Z.`);
  return ms;
}
