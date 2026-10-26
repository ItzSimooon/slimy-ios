// Formatting for numbers, dates and the server's enums. English UI, dates in
// the day-month-year order the owner reads naturally.

const intFmt = new Intl.NumberFormat("en-US");
const compactFmt = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 });
const pctFmt = new Intl.NumberFormat("en-US", { style: "percent", maximumFractionDigits: 1 });
const dateFmt = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });
const dateTimeFmt = new Intl.DateTimeFormat("en-GB", {
  day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
});
const timeFmt = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });
const dayShortFmt = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });
const weekdayFmt = new Intl.DateTimeFormat("en-GB", { weekday: "short", timeZone: "UTC" });
const dayLongFmt = new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
const relFmt = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

/** A map lookup that ignores inherited keys like "constructor". */
const own = (map, key) => (Object.hasOwn(map, key) ? map[key] : undefined);

const num = (n) => (typeof n === "number" && Number.isFinite(n) ? n : 0);

export const fmtInt = (n) => intFmt.format(num(n));
export const fmtCompact = (n) => (Math.abs(num(n)) < 1000 ? intFmt.format(num(n)) : compactFmt.format(num(n)));
export const fmtPercent = (part, whole) => (whole > 0 ? pctFmt.format(part / whole) : "0%");
export const plural = (n, one, many = `${one}s`) => `${fmtInt(n)} ${n === 1 ? one : many}`;

export function fmtBytes(bytes) {
  const b = num(bytes);
  if (b < 1024) return `${fmtInt(b)} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = b / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[i]}`;
}

export function fmtDuration(seconds) {
  const sec = Math.max(0, Math.floor(num(seconds)));
  const d = Math.floor(sec / 86400);
  const hr = Math.floor((sec % 86400) / 3600);
  const min = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d} d ${hr} h`;
  if (hr > 0) return `${hr} h ${min} min`;
  if (min > 0) return `${min} min`;
  return "Less than a minute";
}

function toDate(value) {
  if (value == null || value === "") return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export const fmtDate = (value) => {
  const d = toDate(value);
  return d ? dateFmt.format(d) : "Unknown";
};

export const fmtDateTime = (value) => {
  const d = toDate(value);
  return d ? dateTimeFmt.format(d) : "Unknown";
};

export const fmtTime = (value) => {
  const d = toDate(value);
  return d ? timeFmt.format(d) : "";
};

/** "just now", "5 minutes ago", "yesterday", then the date after a month. */
export function fmtRelative(value, now = Date.now()) {
  const d = toDate(value);
  if (!d) return "Never";
  const diff = (d.getTime() - now) / 1000;
  const abs = Math.abs(diff);
  const sign = diff < 0 ? -1 : 1;
  // Whole units, rounded down, so lists and details agree ("1 h" = "1 hour ago").
  if (abs < 60) return "Just now";
  if (abs < 3600) return relFmt.format(sign * Math.floor(abs / 60), "minute");
  if (abs < 86400) return relFmt.format(sign * Math.floor(abs / 3600), "hour");
  if (abs < 86400 * 30) return relFmt.format(sign * Math.floor(abs / 86400), "day");
  return dateFmt.format(d);
}

/** Short form for list rows: "now", "5 min", "3 h", "2 d", then "12 Sep". */
export function fmtAgoShort(value, now = Date.now()) {
  const d = toDate(value);
  if (!d) return "Never";
  const sec = Math.max(0, (now - d.getTime()) / 1000);
  if (sec < 60) return "Now";
  if (sec < 3600) return `${Math.floor(sec / 60)} min`;
  if (sec < 86400) return `${Math.floor(sec / 3600)} h`;
  if (sec < 86400 * 30) return `${Math.floor(sec / 86400)} d`;
  return dayShortFmt.format(d);
}

/** Capitalizes the first letter, for relative times used at the start of a line. */
export const capitalize = (text) => (text ? text[0].toUpperCase() + text.slice(1) : text);

/** "YYYY-MM-DD" in UTC, the server's day key. */
export const dayKey = (ms) => new Date(ms).toISOString().slice(0, 10);

/** The last `count` UTC day keys, oldest first, ending today. */
export function lastDays(count, now = Date.now()) {
  const out = [];
  for (let i = count - 1; i >= 0; i--) out.push(dayKey(now - i * 86400000));
  return out;
}

const dayDate = (key) => new Date(`${key}T00:00:00Z`);
export const fmtDayShort = (key) => dayShortFmt.format(dayDate(key));
export const fmtWeekday = (key) => weekdayFmt.format(dayDate(key));
export const fmtDayLong = (key) => dayLongFmt.format(dayDate(key));

const PROVIDERS = {
  cloudflare: "Cloudflare Workers AI",
  "cloudflare-workers-ai": "Cloudflare Workers AI",
  workersai: "Cloudflare Workers AI",
  groq: "Groq",
  generic: "Custom endpoint",
  custom: "Custom endpoint",
  ollama: "Ollama",
};
export const providerLabel = (name) => own(PROVIDERS, String(name).toLowerCase()) ?? capitalize(String(name ?? "Unknown"));

/** Short provider names for table headers. */
export const providerShort = (name) => {
  const label = providerLabel(name);
  return label === "Cloudflare Workers AI" ? "Cloudflare" : label === "Custom endpoint" ? "Custom" : label;
};

const REASONS = { name: "Name", harassment: "Harassment", spam: "Spam", chat: "Chat reply", other: "Other" };
export const reasonLabel = (reason) => own(REASONS, reason) ?? capitalize(String(reason ?? "Other"));

const REASON_ICONS = { name: "person", harassment: "warning", spam: "doc", chat: "bubble", other: "flag" };
export const reasonIcon = (reason) => own(REASON_ICONS, reason) ?? "flag";

const RESOLUTIONS = {
  dismiss: "Dismissed", dismissed: "Dismissed",
  rename: "Names reset", renamed: "Names reset",
  ban: "Device banned", banned: "Device banned",
};
export const resolutionLabel = (resolution) => own(RESOLUTIONS, resolution) ?? (resolution ? capitalize(String(resolution)) : "Resolved");

// Game ids from Shared/Model/Arcade.swift (GameID) with their titles.
const GAMES = {
  snackCatcher: "Snack Catcher", slimeJump: "Slime Jump", bubblePop: "Bubble Pop", memoryGarden: "Memory Garden",
  slimeRacing: "Slime Racing", rhythmBounce: "Rhythm Bounce", gardenPuzzle: "Garden Puzzle", treasureDig: "Treasure Dig",
  basketball: "Slime Basketball", colorMatch: "Color Match", slimeStack: "Slime Stack", jellyDash: "Jelly Dash",
  daily: "Today's game",
};
export const gameLabel = (game) => own(GAMES, game)
  ?? capitalize(String(game ?? "Game").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase());

const STAGES = { baby: "Baby", young: "Young", grown: "Grown" };
export const stageLabel = (stage) => own(STAGES, stage) ?? (stage ? capitalize(String(stage)) : "Unknown");

/** A display name that never comes out empty. */
export const nameOr = (value, fallback) => {
  const text = typeof value === "string" ? value.trim() : "";
  return text || fallback;
};
