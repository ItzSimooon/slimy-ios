// Points download links at the newest beta. /release.json is written on the
// server by site/release.mjs, so visitors never talk to GitHub from here.
// Without it every link keeps its fallback: the GitHub releases page.
//
//   <a data-download>            → the release page of the newest build
//   <a data-ipa="ipa|unsigned">  → that IPA file
//   <a data-apk>                 → Slimy.apk, the Android beta
//   <x data-release-name>        → "Slimy 1.1 (build 141)"
//   <x data-release-date>        → "25 October 2026"
//   <x data-release-ready>       → shown once a release is known (starts hidden)

const GITHUB = "https://github.com/ItzSimooon/slimy-ios/";

function trusted(url) {
  return typeof url === "string" && url.startsWith(GITHUB);
}

function formatDate(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" });
}

fetch("/release.json", { cache: "no-cache" })
  .then((r) => (r.ok ? r.json() : null))
  .then((release) => {
    if (!release || !trusted(release.url)) return;
    for (const link of document.querySelectorAll("[data-download]")) link.href = release.url;
    for (const link of document.querySelectorAll("[data-ipa]")) {
      const url = release[link.dataset.ipa];
      if (trusted(url)) link.href = url;
    }
    if (trusted(release.apk)) {
      for (const link of document.querySelectorAll("[data-apk]")) link.href = release.apk;
    }
    if (typeof release.name === "string") {
      for (const el of document.querySelectorAll("[data-release-name]")) el.textContent = release.name;
    }
    if (release.published) {
      const date = formatDate(release.published);
      for (const el of document.querySelectorAll("[data-release-date]")) {
        el.textContent = date;
        if (el.tagName === "TIME") el.setAttribute("datetime", release.published);
      }
    }
    for (const el of document.querySelectorAll("[data-release-ready]")) el.hidden = false;
  })
  .catch(() => {});
