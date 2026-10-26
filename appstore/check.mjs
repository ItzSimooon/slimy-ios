// Checks the App Store metadata in this folder against App Store Connect's
// limits and our own rules before anything is pasted or uploaded.
//   node appstore/check.mjs
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const locales = readdirSync(root, { withFileTypes: true })
  .filter((d) => d.isDirectory() && /^[a-z]{2}-[A-Z]{2}$/.test(d.name))
  .map((d) => d.name);

// Characters as App Store Connect counts them (code points).
const limits = {
  "name.txt": 30,
  "subtitle.txt": 30,
  "promotional_text.txt": 170,
  "description.txt": 4000,
  "keywords.txt": 100,
  "release_notes.txt": 4000,
};

// Other apps' and companies' trademarks never go into our metadata
// (App Review Guideline 2.3.7), and the copy avoids stock marketing phrases.
const banned = [
  "tamagotchi", "gotchi", "pixel pals", "pou", "neopets", "finch", "nintendo", "pokemon", "pokémon",
  "bandai", "apple watch", "iphone 16", "iphone 17", "free!", "#1", "best app",
  "unleash", "elevate", "seamless", "revolutionize", "revolutionise", "game-changer", "lorem",
];
const emoji = /\p{Extended_Pictographic}/u;

let errors = 0;
const fail = (where, message) => {
  errors += 1;
  console.error(`x ${where}: ${message}`);
};

if (locales.length === 0) fail("appstore", "no locale folders found");

for (const locale of locales) {
  const read = (file) => {
    const path = join(root, locale, file);
    return existsSync(path) ? readFileSync(path, "utf8").replace(/\s+$/, "") : null;
  };

  for (const [file, max] of Object.entries(limits)) {
    const text = read(file);
    const where = `${locale}/${file}`;
    if (text === null) { fail(where, "missing"); continue; }
    if (text.length === 0) { fail(where, "empty"); continue; }
    const length = [...text].length;
    if (length > max) fail(where, `${length} characters, the limit is ${max}`);
    if (emoji.test(text)) fail(where, "contains an emoji");
    const lower = text.toLowerCase();
    for (const word of banned) {
      if (new RegExp(`(^|[^a-z])${word.replace(/[.*+?^${}()|[\]\\#!]/g, "\\$&")}($|[^a-z])`).test(lower)) {
        fail(where, `contains "${word}"`);
      }
    }
    console.log(`  ${where.padEnd(32)} ${String(length).padStart(4)} / ${max}`);
  }

  const keywords = read("keywords.txt");
  if (keywords) {
    const where = `${locale}/keywords.txt`;
    if (Buffer.byteLength(keywords) > 100) fail(where, `${Buffer.byteLength(keywords)} bytes; keep it at 100 bytes or fewer`);
    if (/,\s|\s,/.test(keywords)) fail(where, "spaces around commas waste characters");
    const list = keywords.split(",");
    if (list.some((k) => k.trim() === "")) fail(where, "empty keyword");
    const seen = new Set();
    for (const k of list) {
      if (seen.has(k)) fail(where, `"${k}" appears twice`);
      seen.add(k);
    }
    // Words already in the name or subtitle are indexed anyway.
    const indexed = `${read("name.txt") ?? ""} ${read("subtitle.txt") ?? ""}`.toLowerCase().split(/[^\p{L}\p{N}]+/u);
    for (const k of list) {
      if (indexed.includes(k.toLowerCase())) fail(where, `"${k}" is already in the name or subtitle`);
    }
  }

  const name = read("name.txt");
  if (name && /dynamic island/i.test(name)) fail(`${locale}/name.txt`, "keep \"Dynamic Island\" out of the app name");

  for (const file of ["support_url.txt", "marketing_url.txt", "privacy_url.txt"]) {
    const url = read(file);
    if (url === null) fail(`${locale}/${file}`, "missing");
    else if (!/^https:\/\/\S+$/.test(url)) fail(`${locale}/${file}`, "must be a single https URL");
  }
}

// The App Review notes: the text block in review-notes.md, at most 4,000
// characters. Line breaks count twice in case they are sent as CR LF.
{
  const where = "review-notes.md";
  const path = join(root, where);
  const block = existsSync(path) ? /```text\n([\s\S]*?)\n```/.exec(readFileSync(path, "utf8").replace(/\r\n/g, "\n")) : null;
  if (!block) {
    fail(where, "no ```text block with the notes");
  } else {
    const notes = block[1];
    const length = [...notes].length + (notes.match(/\n/g) ?? []).length;
    if (length > 4000) fail(where, `${length} characters, the limit is 4000`);
    if (emoji.test(notes)) fail(where, "contains an emoji");
    console.log(`  ${(where + " (notes)").padEnd(32)} ${String(length).padStart(4)} / 4000`);
  }
}

if (errors) {
  console.error(`\n${errors} problem(s) found.`);
  process.exit(1);
}
console.log(`\nAll metadata is within the limits (${locales.join(", ")}).`);
