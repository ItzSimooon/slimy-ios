// Builds the static website into site/dist. No dependencies; Node 20+.
//
//   node site/build.mjs
//
// - copies site/ to site/dist (without partials/, tools/, dist/, the build and
//   release scripts, contact.json and dev-mock.mjs files); site/admin/ is
//   copied byte for byte, it is a standalone app,
// - fills <!-- @head -->, <!-- @header --> and <!-- @footer --> from partials/,
//   marks the current page in the navigation, and adds Open Graph and Twitter
//   tags from each page's <title>, description and canonical link,
// - fills the owner's contact details from contact.json (see below),
// - versions /assets/*.css and *.js URLs (?v=<hash>) so a deploy never mixes
//   old and new files,
// - copies the sprites, the pixel font and the app icon from the app,
// - draws the Open Graph image and the press images (pure Node PNG encoder),
// - writes sitemap.xml and robots.txt,
// - fails when a page would break the content security policy in
//   server/DEPLOY.md (inline scripts or styles, style attributes, event
//   handler attributes, scripts or images from other origins) or when a file
//   the Caddyfile or the admin depends on is missing.
//
// Contact details. The imprint (/imprint, section 5 DDG), the controller block
// in the privacy policy and the email links on the support, terms and press
// pages need the owner's real name, postal address and email. Only the owner
// can supply them, so they come from site/contact.json (ignored by git, so the
// address stays out of the public repository; SLIMY_CONTACT_FILE=<path> reads
// another file):
//
//   { "name": "Full name", "address": ["Street 1", "12345 City", "Germany"],
//     "email": "an address you read", "phone": "optional", "vatId": "optional" }
//
// Without it the build still succeeds, prints a warning, leaves /imprint out
// and the pages point to GitHub Issues only. Pages mark the parts that depend
// on it with <!-- @if contact -->…<!-- @else -->…<!-- @end --> (also
// contact.phone and contact.vatId) and {{contact.name}}, {{contact.email}},
// {{contact.address}} (lines joined with <br>), {{contact.phone}},
// {{contact.vatId}}. A page that starts with <!-- @needs contact --> is only
// built when the details exist.

import { readFile, writeFile, mkdir, readdir, rm, copyFile, stat } from "node:fs/promises";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Pix, drawText, textWidth, drawSlime, drawItem, drawIcon, SKINS } from "./tools/pixelart.mjs";

const SITE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.dirname(SITE);
const DIST = path.join(SITE, "dist");
const ORIGIN = "https://slime.hyprnode.de";

const SKIP_TOP = new Set(["partials", "tools", "dist", "node_modules", "build.mjs", "release.mjs", "contact.json"]);
const SKIP_ANY = new Set(["dev-mock.mjs", ".DS_Store", "Thumbs.db"]);

async function walk(dir, rel = "") {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const name = entry.name;
    if (name.startsWith(".") || SKIP_ANY.has(name)) continue;
    if (!rel && SKIP_TOP.has(name)) continue;
    const relPath = rel ? `${rel}/${name}` : name;
    if (entry.isDirectory()) out.push(...(await walk(path.join(dir, name), relPath)));
    else out.push(relPath);
  }
  return out;
}

async function write(rel, data) {
  const file = path.join(DIST, rel);
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, data);
}

// ------------------------------------------------------------ pages

/// "install.html" → "/install", "index.html" → "/", "f/index.html" → "/f/".
function routeOf(rel) {
  if (rel === "index.html") return "/";
  if (rel.endsWith("/index.html")) return "/" + rel.slice(0, -"index.html".length);
  return "/" + rel.replace(/\.html$/, "");
}

const escapeAttr = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
const unescape = (s) => s.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");

/// Adds aria-current="page" to navigation links (not buttons) that point at this page.
function markCurrent(html, route) {
  return html.replace(/<a\b[^>]*>/g, (tag) => {
    const href = /\shref="([^"]*)"/.exec(tag)?.[1];
    if (href !== route || /aria-current=/.test(tag) || /class="[^"]*\bbtn\b/.test(tag)) return tag;
    return tag.replace(/<a\b/, '<a aria-current="page"');
  });
}

function socialTags(html, rel) {
  const title = /<title>([^<]+)<\/title>/.exec(html)?.[1];
  const description = /<meta name="description" content="([^"]+)">/.exec(html)?.[1];
  const canonical = /<link rel="canonical" href="([^"]+)">/.exec(html)?.[1];
  const missing = [!title && "<title>", !description && "meta description", !canonical && "canonical link"].filter(Boolean);
  if (missing.length) throw new Error(`${rel} is missing ${missing.join(", ")}`);
  if (!canonical.startsWith(ORIGIN + "/")) throw new Error(`${rel}: canonical must start with ${ORIGIN}/`);
  const t = escapeAttr(unescape(title));
  const d = escapeAttr(unescape(description));
  const img = `${ORIGIN}/assets/og.png`;
  return [
    `<meta property="og:type" content="website">`,
    `<meta property="og:title" content="${t}">`,
    `<meta property="og:description" content="${d}">`,
    `<meta property="og:url" content="${canonical}">`,
    `<meta property="og:image" content="${img}">`,
    `<meta property="og:image:width" content="1200">`,
    `<meta property="og:image:height" content="630">`,
    `<meta property="og:image:alt" content="Slimy, a pixel slime that lives in the Dynamic Island">`,
    `<meta name="twitter:card" content="summary_large_image">`,
    `<meta name="twitter:title" content="${t}">`,
    `<meta name="twitter:description" content="${d}">`,
    `<meta name="twitter:image" content="${img}">`,
  ].join("\n");
}

function versionAssets(html, version) {
  return html.replace(/((?:href|src)=")(\/assets\/[^"?#]+\.(?:css|js))(")/g, `$1$2?v=${version}$3`);
}

function versionImports(js, version) {
  return js
    .replace(/(\bfrom\s*["'])(\.\/[^"'?]+\.js|\/assets\/[^"'?]+\.js)(["'])/g, `$1$2?v=${version}$3`)
    .replace(/(\bimport\s*\(\s*["'])(\.\/[^"'?]+\.js|\/assets\/[^"'?]+\.js)(["']\s*\))/g, `$1$2?v=${version}$3`);
}

// ------------------------------------------------------------ contact

const escapeHTML = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");

/// The owner's contact details, or null when contact.json doesn't exist.
/// A file that exists but is incomplete stops the build.
async function loadContact() {
  const file = process.env.SLIMY_CONTACT_FILE ? path.resolve(process.env.SLIMY_CONTACT_FILE) : path.join(SITE, "contact.json");
  let raw;
  try {
    raw = await readFile(file, "utf8");
  } catch (err) {
    if (err.code === "ENOENT" && !process.env.SLIMY_CONTACT_FILE) return null;
    throw new Error(`Can't read the contact details in ${file}: ${err.message}`);
  }
  let data;
  try {
    data = JSON.parse(raw);
  } catch (err) {
    throw new Error(`${file} isn't valid JSON: ${err.message}`);
  }
  const text = (v) => (typeof v === "string" ? v.trim() : "");
  const contact = {
    name: text(data?.name),
    address: Array.isArray(data?.address) ? data.address.map(text).filter(Boolean) : [],
    email: text(data?.email),
    phone: text(data?.phone),
    vatId: text(data?.vatId),
  };
  const problems = [];
  if (!contact.name) problems.push('"name" (your full name)');
  if (contact.address.length < 2) problems.push('"address" (a list of at least two lines: street, postcode and city)');
  if (!/^[^\s@<>"]+@[^\s@<>"]+\.[^\s@<>"]+$/.test(contact.email)) problems.push('"email" (an address you read)');
  if (problems.length) throw new Error(`${file} needs ${problems.join(", ")}.`);
  return contact;
}

/// Resolves <!-- @if contact[.key] -->…[<!-- @else -->…]<!-- @end --> and the {{contact.*}} fields.
function applyContact(html, contact, rel) {
  const has = (key) => Boolean(contact && (!key || contact[key]));
  html = html.replace(
    /<!-- @if contact(?:\.(\w+))? -->([\s\S]*?)(?:<!-- @else -->([\s\S]*?))?<!-- @end -->/g,
    (_, key, yes, no = "") => (has(key) ? yes : no),
  );
  if (/<!-- @(if|else|end)\b/.test(html)) throw new Error(`${rel}: unmatched <!-- @if contact --> block (blocks can't be nested)`);
  return html.replace(/\{\{contact\.(\w+)\}\}/g, (_, key) => {
    if (!contact) throw new Error(`${rel}: {{contact.${key}}} outside an <!-- @if contact --> block`);
    if (key === "address") return contact.address.map(escapeHTML).join("<br>\n");
    if (!(key in contact)) throw new Error(`${rel}: unknown field {{contact.${key}}}`);
    if (!contact[key]) throw new Error(`${rel}: {{contact.${key}}} is empty; wrap it in <!-- @if contact.${key} -->`);
    return escapeHTML(contact[key]);
  });
}

// ------------------------------------------------------------ checks

/// The production CSP (server/DEPLOY.md) allows only same-origin scripts,
/// stylesheets and images (plus data: images): no inline code of any kind.
function cspProblems(html) {
  const problems = [];
  html = html.replace(/<!--[\s\S]*?-->/g, "");
  const tags = html.match(/<[a-zA-Z][^>]*>/g) ?? [];
  if (/<style[\s>]/i.test(html)) problems.push("a <style> block");
  for (const tag of tags) {
    const name = /^<([a-zA-Z0-9-]+)/.exec(tag)[1].toLowerCase();
    // Attribute names only, so text inside a value never counts.
    const bare = tag.replace(/=\s*("[^"]*"|'[^']*')/g, '=""');
    if (/\sstyle\s*=/i.test(bare)) problems.push(`a style attribute in ${tag.slice(0, 60)}`);
    const handler = /\s(on[a-z]+)\s*=/i.exec(bare);
    if (handler) problems.push(`an ${handler[1]} attribute in ${tag.slice(0, 60)}`);
    if (/\s(?:href|src|action|formaction)\s*=\s*["']?\s*javascript:/i.test(tag)) problems.push(`a javascript: URL in ${tag.slice(0, 60)}`);
    const src = /\ssrc\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1];
    if (src && /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(src) && !/^data:/i.test(src)) problems.push(`${src} loaded from another origin`);
    if (name === "link" && /\srel\s*=\s*["'][^"']*\b(?:stylesheet|preload|modulepreload|icon)\b/i.test(tag)) {
      const href = /\shref\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1] ?? "";
      if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) problems.push(`${href} loaded from another origin`);
    }
  }
  for (const m of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    const attrs = m[1];
    const dataBlock = /\stype\s*=\s*["']application\/(?:ld\+)?json["']/i.test(attrs);
    if (!/\ssrc\s*=/i.test(attrs) && !dataBlock) problems.push("an inline <script>");
    else if (m[2].trim() && !dataBlock) problems.push("a <script src> with inline code");
  }
  return problems;
}

async function exists(file) {
  try {
    return (await stat(file)).isFile();
  } catch {
    return false;
  }
}

// ------------------------------------------------------------ images

const DAY = {
  sky: "#8FD3F2", skyLow: "#C6EBF7", sun: "#FFE38A", cloud: "#FFFFFF",
  hillBack: "#8ACB6A", hillFront: "#6BB45A", grass: "#79C05A", grassLight: "#95D370", grassDark: "#62AE4C",
  flowers: ["#F07A9A", "#FFD166", "#FFFFFF"], leaf: "#4FA34A",
};

function hash(n) {
  let x = (n | 0) ^ 0x9e3779b9;
  x = Math.imul(x ^ (x >>> 16), 0x85ebca6b);
  x = Math.imul(x ^ (x >>> 13), 0xc2b2ae35);
  return (x ^ (x >>> 16)) >>> 0;
}

/// A little garden: sky, a cloud, hills and grass with flowers.
function garden(p, x0, y0, w, h, groundY) {
  p.rect(x0, y0, w, groundY - y0, DAY.sky);
  p.rect(x0, y0 + Math.floor((groundY - y0) * 0.66), w, groundY - y0 - Math.floor((groundY - y0) * 0.66), DAY.skyLow);
  p.disc(x0 + w - 16, y0 + 12, 5, DAY.sun);
  const cloud = (cx, cy) => {
    p.rect(cx, cy, 16, 3, DAY.cloud);
    p.rect(cx + 3, cy - 2, 9, 2, DAY.cloud);
    p.rect(cx + 5, cy - 3, 4, 1, DAY.cloud);
  };
  cloud(x0 + 8, y0 + 30);
  cloud(x0 + w - 40, y0 + 44);
  for (let x = x0; x < x0 + w; x++) {
    const h1 = Math.round(8 + 5 * Math.sin(x / 11));
    p.rect(x, groundY - h1 - 6, 1, h1 + 6, DAY.hillBack);
    const h2 = Math.round(5 + 3 * Math.sin(x / 7 + 2));
    p.rect(x, groundY - h2, 1, h2, DAY.hillFront);
  }
  p.rect(x0, groundY, w, y0 + h - groundY, DAY.grass);
  for (let x = x0; x < x0 + w; x++) {
    const v = hash(x * 13 + 3);
    if (v % 4 === 0) p.rect(x, groundY - 1, 1, 1, DAY.grass);
    if (v % 3 === 0) p.rect(x, groundY + 2 + ((v >>> 5) % Math.max(1, y0 + h - groundY - 3)), 1, 1, DAY.grassLight);
    if (v % 11 === 0 && x > x0 + 2 && x < x0 + w - 3) {
      const c = DAY.flowers[(v >>> 9) % 3];
      const y = groundY + 4 + ((v >>> 3) % Math.max(1, y0 + h - groundY - 7));
      p.rect(x, y, 1, 1, c);
      p.rect(x - 1, y + 1, 3, 1, c);
      p.rect(x, y + 2, 1, 1, DAY.leaf);
    }
  }
}

/// A black Dynamic Island pill with the slime on the left and an emote on the right.
function island(p, sprites, x, y, w, skin = "lime", emote = "heart") {
  const h = 16;
  p.pill(x, y, w, h, "#000000");
  // The young idle frame has six empty rows on top; show rows 6…19.
  const s = new Pix(24, 20);
  drawSlime(s, sprites, { skin, stage: "young" });
  for (let yy = 6; yy < 20; yy++) {
    for (let xx = 0; xx < 24; xx++) {
      const i = (yy * 24 + xx) * 4;
      if (s.data[i + 3]) p.set(x + 5 + xx, y + 1 + (yy - 6), [s.data[i], s.data[i + 1], s.data[i + 2], 255]);
    }
  }
  drawIcon(p, sprites, emote, "#F07A9A", x + w - 16, y + 4);
}

function ogImage(sprites) {
  // 240 × 126 grid, five device pixels each: 1200 × 630.
  const p = new Pix(240, 126, "#F2F2F7");
  const ink = "#000000";

  // Right: a card with the garden, the island and the slime.
  const cx = 128, cy = 9, cw = 102, ch = 108;
  const card = new Pix(cw, ch);
  garden(card, 0, 0, cw, ch, 80);
  island(card, sprites, Math.floor((cw - 56) / 2), 8, 56, "lime", "heart");
  drawSlime(card, sprites, { skin: "lime", stage: "young", anim: "idle" }, Math.floor((cw - 48) / 2), 88 - 40, 2);
  // A speech bubble.
  const say = "hi.";
  const bw = textWidth(say) + 8;
  const bx = Math.floor(cw / 2) + 12, by = 40;
  card.round(bx, by, bw, 13, 3, "#FFFFFF");
  card.rect(bx + 3, by + 13, 3, 1, "#FFFFFF");
  card.rect(bx + 3, by + 14, 2, 1, "#FFFFFF");
  card.rect(bx + 3, by + 15, 1, 1, "#FFFFFF");
  drawText(card, say, bx + 4, by + 3, ink);
  // Round the card's corners onto the page background.
  const mask = new Pix(cw, ch);
  mask.round(0, 0, cw, ch, 9, "#000000");
  for (let i = 0; i < cw * ch; i++) if (!mask.data[i * 4 + 3]) card.data[i * 4 + 3] = 0;
  p.draw(card, cx, cy);

  // Left: wordmark, maker, tagline, address.
  const x = 14;
  drawText(p, "slimy", x, 18, ink, 4);
  drawText(p, "by hyprnode", x + 1, 60, "#9B7BFF", 1);
  drawText(p, "A tiny slime that lives", x + 1, 79, "#3C3C43", 1);
  drawText(p, "in your Dynamic Island.", x + 1, 90, "#3C3C43", 1);
  drawText(p, "slime.hyprnode.de", x + 1, 108, "#1E7F37", 1);
  return p.scaled(5);
}

function skinImage(sprites, skin) {
  const p = new Pix(24, 20);
  drawSlime(p, sprites, { skin, stage: "young" });
  return p.trimmed(1).scaled(16);
}

function stagesImage(sprites) {
  const p = new Pix(84, 24);
  drawSlime(p, sprites, { skin: "lime", stage: "baby" }, 2, 2);
  drawSlime(p, sprites, { skin: "lime", stage: "young" }, 30, 2);
  drawSlime(p, sprites, { skin: "lime", stage: "grown" }, 58, 2);
  return p.trimmed(2).scaled(12);
}

function islandImage(sprites) {
  const p = new Pix(64, 18);
  island(p, sprites, 1, 1, 62, "lime", "heart");
  return p.trimmed(0).scaled(10);
}

function itemsImage(sprites) {
  const names = ["apple", "cookie", "cake", "starJelly", "ball", "bubbleItem", "heart", "coin"];
  const p = new Pix(names.length * 14, 14);
  names.forEach((n, i) => drawItem(p, sprites, n, i * 14 + 2, 2 + (n === "heart" ? 2 : n === "coin" ? 1 : 0)));
  return p.trimmed(1).scaled(10);
}

// ------------------------------------------------------------ build

async function build() {
  const started = Date.now();
  await rm(DIST, { recursive: true, force: true });
  await mkdir(DIST, { recursive: true });

  const files = await walk(SITE);
  const contact = await loadContact();
  const partials = {
    head: await readFile(path.join(SITE, "partials/head.html"), "utf8"),
    header: await readFile(path.join(SITE, "partials/header.html"), "utf8"),
    footer: await readFile(path.join(SITE, "partials/footer.html"), "utf8"),
  };
  const skipped = [];
  const cspErrors = [];

  // One version for all site assets: a hash of their contents.
  const hasher = createHash("sha256");
  for (const rel of files.filter((f) => f.startsWith("assets/")).sort()) {
    hasher.update(rel);
    hasher.update(await readFile(path.join(SITE, rel)));
  }
  const version = hasher.digest("hex").slice(0, 10);

  const pages = [];
  for (const rel of files) {
    const src = path.join(SITE, rel);
    if (rel.startsWith("admin/")) {
      // The admin is standalone: same layout, same names, no partials, no versions.
      await mkdir(path.dirname(path.join(DIST, rel)), { recursive: true });
      await copyFile(src, path.join(DIST, rel));
      if (rel.endsWith(".html")) {
        const problems = cspProblems(await readFile(src, "utf8"));
        if (problems.length) console.warn(`warning: ${rel} breaks the CSP: ${problems.join("; ")}`);
      }
    } else if (rel.endsWith(".html")) {
      let html = await readFile(src, "utf8");
      if (/^\s*<!-- @needs contact -->/.test(html)) {
        if (!contact) {
          skipped.push(rel);
          continue;
        }
        html = html.replace(/^\s*<!-- @needs contact -->\s*/, "");
      }
      if (html.includes("<!-- @head -->")) {
        const route = routeOf(rel);
        // Function replacements, so a "$" in a partial is never read as a pattern.
        html = html
          .replace("<!-- @head -->", () => partials.head.trim())
          .replace("<!-- @header -->", () => markCurrent(partials.header.trim(), route))
          .replace("<!-- @footer -->", () => markCurrent(partials.footer.trim(), route));
        html = applyContact(html, contact, rel);
        const social = socialTags(html, rel);
        html = html.replace("</head>", () => social + "\n</head>");
        html = versionAssets(html, version);
        const noindex = /<meta name="robots" content="[^"]*noindex/.test(html);
        if (!noindex) pages.push(route);
      } else {
        html = applyContact(html, contact, rel);
      }
      if (/<!-- @(head|header|footer|needs)\b[^>]*-->/.test(html)) throw new Error(`${rel}: unfilled partial marker`);
      for (const problem of cspProblems(html)) cspErrors.push(`${rel}: ${problem}`);
      await write(rel, html);
    } else if (rel.startsWith("assets/") && rel.endsWith(".js")) {
      await write(rel, versionImports(await readFile(src, "utf8"), version));
    } else {
      await mkdir(path.dirname(path.join(DIST, rel)), { recursive: true });
      await copyFile(src, path.join(DIST, rel));
    }
  }

  // From the app.
  const spritesJSON = await readFile(path.join(REPO, "Shared/Sprites/sprites.json"));
  await write("sprites.json", spritesJSON);
  const fontDir = path.join(REPO, "Shared/Fonts");
  for (const name of await readdir(fontDir)) {
    if (name.endsWith(".ttf") || name === "PixelifySans-OFL.txt") await write(`fonts/${name}`, await readFile(path.join(fontDir, name)));
  }
  const icon = await readFile(path.join(REPO, "Resources/Assets.xcassets/AppIcon.appiconset/icon.png"));
  await write("assets/icon.png", icon);
  await write("press/slimy-app-icon.png", icon);

  // Pixel images.
  const sprites = JSON.parse(spritesJSON);
  await write("assets/og.png", ogImage(sprites).png());
  for (const skin of Object.keys(SKINS)) await write(`press/slime-${skin}.png`, skinImage(sprites, skin).png());
  await write("press/slime-stages.png", stagesImage(sprites).png());
  await write("press/slime-island.png", islandImage(sprites).png());
  await write("press/slimy-items.png", itemsImage(sprites).png());

  // Search engines.
  const today = new Date().toISOString().slice(0, 10);
  const urls = pages
    .sort((a, b) => (a === "/" ? -1 : b === "/" ? 1 : a.localeCompare(b)))
    .map((r) => `  <url><loc>${ORIGIN}${r}</loc><lastmod>${today}</lastmod></url>`);
  await write("sitemap.xml", `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.join("\n")}\n</urlset>\n`);
  await write("robots.txt", `User-agent: *\nDisallow: /admin\nDisallow: /api/\nDisallow: /f/\n\nSitemap: ${ORIGIN}/sitemap.xml\n`);

  // What the Caddyfile (server/DEPLOY.md) and the admin rely on.
  if (cspErrors.length) throw new Error(`These pages would break the content security policy:\n  ${cspErrors.join("\n  ")}`);
  const required = [
    "index.html", "404.html", "f/index.html", "install.html", "support.html", "privacy.html", "terms.html",
    "sprites.json", "fonts/PixelifySans-Bold.ttf", "assets/slime.js", "assets/site.css",
  ];
  if (files.some((f) => f.startsWith("admin/"))) required.push("admin/index.html");
  const missing = [];
  for (const rel of required) if (!(await exists(path.join(DIST, rel)))) missing.push(rel);
  if (missing.length) throw new Error(`site/dist is missing ${missing.join(", ")}`);
  // The admin imports these from /assets/slime.js.
  const slimeJS = await readFile(path.join(DIST, "assets/slime.js"), "utf8");
  const exportsMissing = ["class Slime", "function loadSprites", "const SKINS", "const SKIN_TITLES"]
    .filter((e) => !new RegExp(`^export (?:async )?${e}\\b`, "m").test(slimeJS));
  if (exportsMissing.length) throw new Error(`assets/slime.js must export ${exportsMissing.join(", ")} (the admin uses them)`);

  const count = (await walk(DIST)).length;
  console.log(`site/dist: ${count} files, ${pages.length} pages in the sitemap, assets v=${version} (${Date.now() - started} ms)`);
  if (!contact) {
    console.warn(
      `warning: no site/contact.json, so ${skipped.join(", ") || "the imprint"} wasn't built and the privacy policy has no controller ` +
        "address. Add your name, postal address and email before going live (see the top of site/build.mjs).",
    );
  }
}

build().catch((err) => {
  console.error(err.message ?? err);
  process.exit(1);
});
