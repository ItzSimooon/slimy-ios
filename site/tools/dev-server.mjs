// Local preview of site/dist with the same routing as the Caddyfile in
// server/DEPLOY.md, the same security headers, and mocks for the API calls the
// public site makes. Run `node site/build.mjs` first.
//
//   node site/tools/dev-server.mjs            → http://localhost:4310
//   PORT=4400 node site/tools/dev-server.mjs
//
// Mocks: GET /api/v1/invites/ABC123 answers a sample invite, XYZ789 one whose
// slime wears the crate-only colour (to check the invite page draws it), any
// other code 404.
// GET /release.json answers a sample release.
//
// The admin: /admin, /admin/ and /admin/* come straight from site/admin/ (so
// admin changes show without a rebuild; .mjs files there are never served).
// If site/admin/dev-mock.mjs exists, its handle(req, res, url) sees every
// request first and answers /api/v1/admin/* with made-up data. Passphrase
// slime-admin-dev (or SLIMY_ADMIN_MOCK_PASSWORD); SLIMY_ADMIN_MOCK=unset
// makes every admin route answer 503; SLIMY_ADMIN_MOCK_DELAY=<ms> sets the
// latency. The admin's slimes and font come from dist, so build once.

import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SITE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DIST = path.join(SITE, "dist");
const ADMIN = path.join(SITE, "admin");
const PORT = Number(process.env.PORT) || 4310;

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".ttf": "font/ttf",
  ".txt": "text/plain; charset=utf-8",
  ".xml": "application/xml; charset=utf-8",
  ".ico": "image/x-icon",
  ".webmanifest": "application/manifest+json",
};

// Same headers as production (server/DEPLOY.md, section 6), so a page that
// breaks the content security policy breaks here too.
const SECURITY = {
  "Content-Security-Policy": "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()",
  "X-Frame-Options": "DENY",
};

let adminMock = null;
const mockFile = path.join(SITE, "admin", "dev-mock.mjs");
if (existsSync(mockFile)) {
  try {
    const mod = await import(pathToFileURL(mockFile).href);
    adminMock = mod.handle ?? mod.default ?? null;
  } catch (err) {
    console.warn("site/admin/dev-mock.mjs could not be loaded:", err.message);
  }
}

const iso = (ms) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");

function json(res, status, body) {
  res.writeHead(status, { ...SECURITY, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
}

function api(req, res, url) {
  const invite = /^\/api\/v1\/invites\/([^/]+)$/.exec(url.pathname);
  if (invite && req.method === "GET") {
    let code;
    try {
      code = decodeURIComponent(invite[1]);
    } catch {
      return json(res, 400, { error: "That address isn't valid." });
    }
    if (code.toUpperCase() === "ABC123") {
      return json(res, 200, {
        ownerName: "Mia",
        slime: { name: "Mochi", skin: "bubblegum", stage: "young" },
        expiresAt: iso(Date.now() + 9 * 60 * 1000 + 30 * 1000),
      });
    }
    if (code.toUpperCase() === "XYZ789") {
      return json(res, 200, {
        ownerName: "Ben",
        slime: { name: "Pebble", skin: "axolotl", stage: "grown" },
        expiresAt: iso(Date.now() + 6 * 60 * 1000),
      });
    }
    return json(res, 404, { error: "This code doesn't work. It may have expired or been used already." });
  }
  return json(res, 404, { error: "This address doesn't exist." });
}

function release(res) {
  json(res, 200, {
    name: "Slimy 1.1 (build 141)",
    tag: "build-141",
    url: "https://github.com/ItzSimooon/slimy-ios/releases/tag/build-141",
    ipa: "https://github.com/ItzSimooon/slimy-ios/releases/download/build-141/Slimy.ipa",
    unsigned: "https://github.com/ItzSimooon/slimy-ios/releases/download/build-141/Slimy-unsigned.ipa",
    apk: "https://github.com/ItzSimooon/slimy-ios/releases/download/build-141/Slimy.apk",
    published: "2026-10-26T17:05:00Z",
  });
}

/// Like Caddy's try_files {path} {path}.html {path}/index.html, inside `root`.
async function fileFor(pathname, root = DIST) {
  const clean = path.posix.normalize(pathname).replace(/^(\.\.(\/|$))+/, "");
  const candidates = [clean];
  if (!path.posix.extname(clean)) candidates.push(clean.replace(/\/$/, "") + ".html", clean.replace(/\/$/, "") + "/index.html");
  for (const c of candidates) {
    const file = path.join(root, c);
    if (file !== root && !file.startsWith(root + path.sep)) continue;
    try {
      if ((await stat(file)).isFile()) return file;
    } catch {}
  }
  return null;
}

/// /admin, /admin/ and /admin/* from site/admin/, never its .mjs files.
async function adminFile(pathname) {
  if (pathname === "/admin" || pathname === "/admin/") return path.join(ADMIN, "index.html");
  const file = await fileFor(pathname.slice("/admin".length) || "/", ADMIN);
  return file && path.extname(file) !== ".mjs" ? file : null;
}

async function send(res, file, status = 200) {
  const body = await readFile(file);
  res.writeHead(status, {
    ...SECURITY,
    "Content-Type": TYPES[path.extname(file)] ?? "application/octet-stream",
    "Cache-Control": "no-cache",
  });
  res.end(body);
}

const server = createServer(async (req, res) => {
  let url;
  try {
    url = new URL(req.url ?? "/", "http://localhost");
  } catch {
    res.writeHead(400).end();
    return;
  }
  try {
    if (adminMock && adminMock(req, res, url)) return;
    if (url.pathname.startsWith("/api/")) return api(req, res, url);
    if (url.pathname === "/release.json") return release(res);
    let pathname;
    try {
      pathname = decodeURIComponent(url.pathname);
    } catch {
      pathname = "/404";
    }
    if (pathname.startsWith("/f/")) pathname = "/f/index.html";
    const isAdmin = pathname === "/admin" || pathname.startsWith("/admin/");
    const file = isAdmin && existsSync(ADMIN) ? await adminFile(pathname) : await fileFor(pathname);
    if (file) return send(res, file);
    const notFound = path.join(DIST, "404.html");
    if (existsSync(notFound)) return send(res, notFound, 404);
    res.writeHead(404, { "Content-Type": "text/plain" }).end("Not found. Run node site/build.mjs first.");
  } catch (err) {
    console.error(err);
    if (!res.headersSent) res.writeHead(500, { "Content-Type": "text/plain" });
    res.end("Server error");
  }
});

server.listen(PORT, () => {
  console.log(`Slimy site on http://localhost:${PORT}${adminMock ? " (admin mock on)" : ""}`);
  if (!existsSync(DIST)) console.log("site/dist doesn't exist yet. Run: node site/build.mjs");
});
