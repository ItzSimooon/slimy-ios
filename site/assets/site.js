// Shared by every page: appearance, header, menu, the lawn above the footer,
// declarative slimes (<canvas data-slime>), pixel items, help search and copy
// buttons. Page scripts import isDark/onTheme from here.
import {
  loadSprites, sprites, SlimeActor, drawAllItems, drawSlime, drawItemAt, itemSize, frames,
  savedLook, calm, onTick, watch, clockT,
} from "./slime.js";

const root = document.documentElement;
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

// ----------------------------------------------------------- appearance

const darkQuery = matchMedia("(prefers-color-scheme: dark)");
const themeListeners = new Set();

export function themeMode() {
  const t = root.dataset.theme;
  return t === "light" || t === "dark" ? t : "auto";
}
export function isDark() {
  const m = themeMode();
  return m === "dark" || (m === "auto" && darkQuery.matches);
}
/// Calls fn whenever the page switches between light and dark.
export function onTheme(fn) {
  themeListeners.add(fn);
  return () => themeListeners.delete(fn);
}

let lastDark = isDark();
function notifyTheme() {
  root.classList.toggle("night", isDark());
  syncThemeControls();
  const dark = isDark();
  if (dark === lastDark) return;
  lastDark = dark;
  for (const fn of themeListeners) fn(dark);
}

function setTheme(mode) {
  if (mode === "auto") delete root.dataset.theme;
  else root.dataset.theme = mode;
  try {
    if (mode === "auto") localStorage.removeItem("slimy.theme");
    else localStorage.setItem("slimy.theme", mode);
  } catch {}
  for (const meta of $$('meta[name="theme-color"]')) {
    const media = meta.getAttribute("media") || "";
    const color = mode === "dark" ? "#000000" : mode === "light" ? "#F2F2F7" : media.includes("dark") ? "#000000" : "#F2F2F7";
    meta.setAttribute("content", color);
  }
  notifyTheme();
}

const MODE_LABEL = { auto: "automatic", light: "light", dark: "dark" };
function syncThemeControls() {
  const mode = themeMode();
  const btn = $("#theme-btn");
  if (btn) {
    btn.dataset.mode = mode;
    btn.setAttribute("aria-label", `Appearance: ${MODE_LABEL[mode]}. Change appearance`);
  }
  for (const b of $$("[data-theme-set]")) {
    const on = b.dataset.themeSet === mode;
    b.setAttribute("aria-checked", String(on));
    b.tabIndex = on ? 0 : -1;
  }
}

function setupTheme() {
  $("#theme-btn")?.addEventListener("click", () => {
    setTheme({ auto: "light", light: "dark", dark: "auto" }[themeMode()]);
  });
  const radios = $$("[data-theme-set]");
  radios.forEach((b, i) => {
    b.addEventListener("click", () => setTheme(b.dataset.themeSet));
    // Arrow keys move the selection, like a native segmented control.
    b.addEventListener("keydown", (e) => {
      const d = e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : e.key === "ArrowLeft" || e.key === "ArrowUp" ? -1 : 0;
      if (!d) return;
      e.preventDefault();
      const next = radios[(i + d + radios.length) % radios.length];
      next.focus();
      setTheme(next.dataset.themeSet);
    });
  });
  darkQuery.addEventListener("change", notifyTheme);
  root.classList.toggle("night", isDark());
  syncThemeControls();
}

// ---------------------------------------------------------- header

function setupHeader() {
  const bar = $("#bar");
  if (!bar) return;
  const onScroll = () => bar.classList.toggle("is-scrolled", window.scrollY > 4);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  const btn = $("#menu-btn");
  const menu = $("#menu");
  if (!btn || !menu) return;
  const setOpen = (open, { focus = false } = {}) => {
    btn.setAttribute("aria-expanded", String(open));
    btn.setAttribute("aria-label", open ? "Close menu" : "Menu");
    menu.hidden = !open;
    bar.classList.toggle("menu-open", open);
    if (open && focus) menu.querySelector("a")?.focus();
  };
  btn.addEventListener("click", () => setOpen(btn.getAttribute("aria-expanded") !== "true"));
  menu.addEventListener("click", (e) => {
    if (e.target.closest("a")) setOpen(false);
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !menu.hidden) {
      setOpen(false);
      btn.focus();
    }
  });
  document.addEventListener("pointerdown", (e) => {
    if (!menu.hidden && !menu.contains(e.target) && !btn.contains(e.target)) setOpen(false);
  });
  matchMedia("(min-width: 961px)").addEventListener("change", (e) => e.matches && setOpen(false));
}

// ------------------------------------------------- the lawn above the footer

const LAWN_DAY = { grass: "#79C05A", light: "#95D370", dark: "#62AE4C", leaf: "#4FA34A", flowers: ["#F07A9A", "#FFD166", "#FFFFFF"] };
const LAWN_NIGHT = { grass: "#2C4C35", light: "#365A40", dark: "#26432F", leaf: "#2F5A38", flowers: ["#A8587A", "#BFA055", "#B8BCD4"] };

function hash(n) {
  let x = (n | 0) ^ 0x9e3779b9;
  x = Math.imul(x ^ (x >>> 16), 0x85ebca6b);
  x = Math.imul(x ^ (x >>> 13), 0xc2b2ae35);
  return (x ^ (x >>> 16)) >>> 0;
}

/// A strip of lawn with the visitor's slime asleep on it. Tap it and it waves.
function setupLawn() {
  const canvas = $("#lawn");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const owner = {};
  watch(canvas, owner);
  const s = { anim: "sleep", frame: 0, look: savedLook() };
  let cols = 0, rows = 26, px = 4;
  const rect = (x, y, w, h, c) => {
    ctx.fillStyle = c;
    ctx.fillRect(x, y, w, h);
  };
  const draw = () => {
    if (!sprites() || !cols) return;
    const P = isDark() ? LAWN_NIGHT : LAWN_DAY;
    const top = rows - 7;
    ctx.clearRect(0, 0, cols, rows);
    for (let x = 0; x < cols; x++) {
      const h = hash(x * 13 + 3);
      rect(x, top, 1, rows - top, P.grass);
      if (h % 4 === 0) rect(x, top - 1, 1, 1, P.grass);
      if (h % 11 === 0) rect(x, top - 2, 1, 2, P.grass);
      if (h % 3 === 0) rect(x, top + 2 + ((h >>> 5) % 4), 1, 1, P.light);
    }
    rect(0, rows - 1, cols, 1, P.dark);
    const sx = Math.round(cols * 0.72) - 12;
    let x = 4 + (hash(cols) % 12);
    while (x < cols - 12) {
      const h = hash(x * 41 + 9);
      if (x < sx - 12 || x > sx + 28) {
        const kind = h % 7;
        if (kind === 0) drawItemAt(ctx, "mushroom", x, top + 2 - itemSize("mushroom")[1]);
        else if (kind === 1) drawItemAt(ctx, "rock", x, top + 4 - itemSize("rock")[1]);
        else if (kind < 4) drawItemAt(ctx, "flower", x, top + 1 - itemSize("flower")[1]);
        else {
          const col = P.flowers[h % 3];
          rect(x + 3, top - 3, 1, 1, col);
          rect(x + 2, top - 2, 3, 1, col);
          rect(x + 3, top - 1, 1, 1, P.leaf);
        }
      }
      x += 16 + ((h >>> 8) % 34);
    }
    drawSlime(ctx, s.look, s.anim, s.frame, sx, top + 1 - 20);
    if (isDark()) {
      const t = calm() ? 0 : clockT;
      for (let i = 0; i < 7; i++) {
        if (Math.floor(t * 1.5 + i) % 3 === 0) continue;
        const fx = Math.round((cols / 7) * i + 8 + 5 * Math.sin(t * 0.5 + i));
        const fy = Math.round(top - 9 - 5 * (1 + Math.sin(t * 0.7 + i * 2)));
        rect(fx, fy, 1, 1, "#EAF77A");
      }
    }
  };
  const resize = () => {
    const w = canvas.parentElement.clientWidth;
    px = w < 640 ? 3 : 4;
    cols = Math.ceil(w / px);
    canvas.width = cols;
    canvas.height = rows;
    canvas.style.width = cols * px + "px";
    canvas.style.height = rows * px + "px";
    draw();
  };
  new ResizeObserver(resize).observe(canvas.parentElement);
  onTheme(draw);
  loadSprites().then(() => {
    resize();
    onTick(() => {
      if (!owner.visible) return;
      if (calm() && s.anim === "sleep") return draw();
      s.frame++;
      if (s.anim !== "sleep" && s.frame >= frames(s.look.stage, s.anim).length * 3) {
        s.anim = "sleep";
        s.frame = 0;
      }
      draw();
    }, 4);
  }, () => {});
  // Waking it is a small reward for whoever finds it.
  canvas.parentElement.addEventListener("pointerdown", (e) => {
    const r = canvas.getBoundingClientRect();
    const gx = (e.clientX - r.left) / px;
    const sx = Math.round(cols * 0.72) - 12;
    if (gx < sx || gx > sx + 24 || s.anim !== "sleep") return;
    s.anim = "wave";
    s.frame = 0;
    draw();
  });
  window.addEventListener("slimy:look", () => {
    s.look = savedLook();
    draw();
  });
}

// --------------------------------------------- declarative pixel art

/// <canvas data-slime data-anim="sad" data-stage="young" data-skin="mine|lime" data-crop="2">
function setupSlimes() {
  const canvases = $$("canvas[data-slime]");
  for (const c of canvases) {
    const d = c.dataset;
    const fixed = d.skin && d.skin !== "mine" ? { stage: d.stage || "young", skin: d.skin, hat: d.hat || null, glasses: null, scarf: null } : null;
    const look = () => fixed ?? { ...savedLook(), stage: d.stage || "young" };
    const actor = new SlimeActor(c, { look, base: d.anim || "idle", crop: Number(d.crop || 0), still: "still" in d });
    c.__actor = actor;
    if (d.tap) {
      const target = c.closest("button, [data-tap-target]") ?? c;
      target.addEventListener("click", () => actor.play(d.tap, 2));
    }
  }
  window.addEventListener("slimy:look", () => canvases.forEach((c) => c.__actor?.draw()));
}

// ------------------------------------------------------------ help

/// Opens a <details> when the address points at it or at something inside it.
function openFromHash() {
  const id = decodeURIComponent(location.hash.slice(1));
  if (!id) return;
  const el = document.getElementById(id);
  const details = el?.closest("details") ?? (el?.tagName === "DETAILS" ? el : null);
  if (details) {
    details.open = true;
    requestAnimationFrame(() => details.scrollIntoView({ block: "start", behavior: calm() ? "auto" : "smooth" }));
  }
}

function setupFaqSearch() {
  const input = $("[data-faq-search]");
  if (!input) return;
  const items = $$("[data-faq] details");
  const groups = $$("[data-faq-group]");
  const empty = $("[data-faq-empty]");
  const count = $("[data-faq-count]");
  const norm = (s) => s.toLowerCase().normalize("NFKD").replace(/[^\p{L}\p{N} ]/gu, " ");
  const index = items.map((d) => norm(d.textContent));
  input.addEventListener("input", () => {
    const words = norm(input.value).split(/\s+/).filter(Boolean);
    let shown = 0;
    items.forEach((d, i) => {
      const hit = words.every((w) => index[i].includes(w));
      d.hidden = !hit;
      if (hit) shown++;
    });
    groups.forEach((g) => (g.hidden = !$$("details", g).some((d) => !d.hidden)));
    if (empty) empty.hidden = shown > 0;
    if (count) count.textContent = words.length ? `${shown} ${shown === 1 ? "answer" : "answers"} found` : "";
  });
}

// ------------------------------------------------------------ copy

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // Older browsers and insecure origins: select a temporary field.
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.className = "sr-only";
    document.body.append(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand("copy");
    } catch {}
    area.remove();
    return ok;
  }
}

/// <button data-copy="text"> or <button data-copy-from="#id">; shows "Copied".
function setupCopy() {
  for (const b of $$("[data-copy], [data-copy-from]")) {
    b.addEventListener("click", async () => {
      const source = b.dataset.copyFrom ? $(b.dataset.copyFrom) : null;
      const text = (b.dataset.copy ?? (source?.innerText || source?.textContent || "")).trim();
      if (!text) return;
      const ok = await copyText(text);
      const label = b.querySelector("[data-copy-label]") ?? b;
      const before = label.dataset.label ?? label.textContent;
      label.dataset.label = before;
      label.textContent = ok ? "Copied" : "Copy failed";
      b.classList.toggle("is-done", ok);
      const live = $("#copy-status");
      if (live) live.textContent = ok ? "Copied to the clipboard." : "Couldn't copy. Select the text instead.";
      clearTimeout(b.__t);
      b.__t = setTimeout(() => {
        label.textContent = before;
        b.classList.remove("is-done");
      }, 1800);
    });
  }
}
export { copyText };

// ------------------------------------------------------------ boot

setupTheme();
setupHeader();
setupFaqSearch();
setupCopy();
openFromHash();
addEventListener("hashchange", openFromHash);
setupLawn();
loadSprites().then(() => {
  drawAllItems();
  setupSlimes();
}, () => {
  // The page reads fine without pixel art.
  root.classList.add("no-sprites");
});

