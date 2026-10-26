// The pixel slime from the app, drawn with the site's /assets/slime.js and
// /sprites.json. Loaded lazily so the admin still works if either is missing.

import { h } from "./dom.js";

// Shared/Art/Brand.swift: warm cheeks, cool highlights (same as slime.js).
const WARM = "#F07A9A";
const COOL = "#7FD6F0";

let modulePromise;
function slimeModule() {
  modulePromise ??= import("/assets/slime.js").catch(() => null);
  return modulePromise;
}

const NOOP = { dispose() {}, play() {} };

/** An animated slime inside `host`. Call `dispose()` when it leaves the page. */
export function mountSlime(host, { skin = "lime", stage = "young", anim = "idle", maxScale = 6 } = {}) {
  const canvas = h("canvas", { class: "slime-canvas", "aria-hidden": "true" });
  host.append(canvas);
  let slime = null;
  let disposed = false;
  const queued = [];

  slimeModule().then((mod) => {
    if (!mod || disposed) {
      if (!mod) canvas.remove();
      return;
    }
    const safeSkin = Object.hasOwn(mod.SKINS, skin) ? skin : "lime";
    slime = new mod.Slime(canvas, { skin: safeSkin, stage: stage || "young", anim, maxScale });
    slime.ready.then(() => {
      if (disposed) clearInterval(slime.timer);
      else for (const [a, loops] of queued.splice(0)) slime.play(a, loops);
    }, () => canvas.remove());
  });

  return {
    canvas,
    play(a, loops = 2) {
      if (slime?.data) slime.play(a, loops);
      else queued.push([a, loops]);
    },
    dispose() {
      disposed = true;
      if (slime) slime.ready.then(() => clearInterval(slime.timer), () => {});
    },
  };
}

/** Paints one still frame of a slime, for list avatars. */
export async function paintAvatar(canvas, skin, stage) {
  const mod = await slimeModule();
  if (!mod) return false;
  let data;
  try {
    data = await mod.loadSprites();
  } catch {
    return false;
  }
  const frames = (Object.hasOwn(data.frames, `${stage}.idle`) ? data.frames[`${stage}.idle`] : null) ?? data.frames["young.idle"];
  if (!frames?.length) return false;
  const [b, sh, k, w] = Object.hasOwn(mod.SKINS, skin) ? mod.SKINS[skin] : mod.SKINS.lime;
  const colors = { b, s: sh, k, w, r: WARM, c: COOL };
  const rows = frames[0];
  // Centre the drawn pixels: small stages sit at the bottom of the frame.
  let top = rows.length;
  let bottom = -1;
  let left = data.width;
  let right = -1;
  rows.forEach((line, y) => {
    for (let x = 0; x < line.length; x++) {
      if (!colors[line[x]]) continue;
      top = Math.min(top, y);
      bottom = Math.max(bottom, y);
      left = Math.min(left, x);
      right = Math.max(right, x);
    }
  });
  if (bottom < 0) return false;
  const dx = Math.floor((data.width - (right - left + 1)) / 2) - left;
  const dy = Math.floor((data.height - (bottom - top + 1)) / 2) - top;
  canvas.width = data.width;
  canvas.height = data.height;
  const ctx = canvas.getContext("2d");
  rows.forEach((line, y) => {
    for (let x = 0; x < line.length; x++) {
      const fill = colors[line[x]];
      if (!fill) continue;
      let end = x;
      while (end + 1 < line.length && line[end + 1] === line[x]) end++;
      ctx.fillStyle = fill;
      ctx.fillRect(x + dx, y + dy, end - x + 1, 1);
      x = end;
    }
  });
  return true;
}

/** A small slime avatar in a rounded well, with the skin colour as a fallback. */
export function slimeAvatar(skin, stage, { size = "medium" } = {}) {
  const canvas = h("canvas", { class: "avatar-canvas", width: 24, height: 20 });
  const el = h("span", { class: `avatar avatar-${size}`, "aria-hidden": "true" }, canvas);
  slimeModule().then((mod) => {
    const tone = mod && (Object.hasOwn(mod.SKINS, skin) ? mod.SKINS[skin] : mod.SKINS.lime);
    if (tone) el.style.setProperty("--skin", tone[0]);
  });
  paintAvatar(canvas, skin, stage).then((ok) => {
    if (!ok) el.classList.add("avatar-fallback");
  });
  return el;
}

/** Skin colour swatch (body colour) for detail rows. */
export async function skinInfo(skin) {
  const mod = await slimeModule();
  if (!mod) return { title: skin ?? "Unknown", color: null };
  const known = Object.hasOwn(mod.SKINS, skin);
  const tone = known ? mod.SKINS[skin] : null;
  const title = known && mod.SKIN_TITLES && Object.hasOwn(mod.SKIN_TITLES, skin) ? mod.SKIN_TITLES[skin] : skin;
  return { title: title ?? "Unknown", color: tone ? tone[0] : null, shade: tone ? tone[1] : null };
}
