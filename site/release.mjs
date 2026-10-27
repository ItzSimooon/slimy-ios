// Writes the newest GitHub release to <site>/release.json for the Download
// buttons. Runs on the server from a timer; releases are prereleases, so
// GitHub's /releases/latest does not work for them.
//   node release.mjs /srv/slimy-site/release.json
//
// "Newest" means the highest build number (tags are build-<run number>), not
// GitHub's list order: builds on main run in parallel, so an older build can
// finish, and be listed, after a newer one.
//
// The Android APK (Slimy.apk) comes from the same release. Should that one
// lack it, the newest release that has one is used; with none, "apk" is null
// and the Android links keep their fallback, the GitHub releases page.
import { writeFile, rename } from "node:fs/promises";

const out = process.argv[2] ?? "release.json";
const res = await fetch("https://api.github.com/repos/ItzSimooon/slimy-ios/releases?per_page=30", {
  headers: { accept: "application/vnd.github+json", "user-agent": "slime.hyprnode.de" },
  signal: AbortSignal.timeout(20_000),
});
if (!res.ok) throw new Error(`GitHub answered ${res.status}`);

const buildNo = (r) => Number(/^build-(\d+)$/.exec(r.tag_name ?? "")?.[1] ?? -1);
const publishedAt = (r) => Date.parse(r.published_at ?? r.created_at ?? "") || 0;
const has = (name) => (r) => !r.draft && r.assets?.some((a) => a.name === name);
const releases = (await res.json()).sort((a, b) => buildNo(b) - buildNo(a) || publishedAt(b) - publishedAt(a));
const release = releases.find(has("Slimy.ipa"));
if (!release) throw new Error("no release with Slimy.ipa");
const assetOf = (r, name) => r?.assets.find((a) => a.name === name)?.browser_download_url ?? null;
const asset = (name) => assetOf(release, name);
const apk = asset("Slimy.apk") ?? assetOf(releases.find(has("Slimy.apk")), "Slimy.apk");

const body = JSON.stringify({
  name: release.name || release.tag_name,
  tag: release.tag_name,
  url: release.html_url,
  ipa: asset("Slimy.ipa"),
  unsigned: asset("Slimy-unsigned.ipa"),
  apk,
  published: release.published_at?.replace(/\.\d{3}Z$/, "Z") ?? null,
});
await writeFile(out + ".tmp", body);
await rename(out + ".tmp", out);
