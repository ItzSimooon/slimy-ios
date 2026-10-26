// The invite page, /f/<CODE>. Share text from the app:
//   "Be my slime friend in Slimy! Code: ABC123 https://slime.hyprnode.de/f/ABC123"
// Looks the code up with GET /api/v1/invites/<CODE> (doesn't use it up) and
// shows who sent it, their slime, the code to type and the next steps.
// Everything from the server is written with textContent, never as HTML.
import { loadSprites, SKINS, SlimeActor, Reel, savedLook } from "./slime.js";
import { copyText } from "./site.js";

const $ = (s) => document.querySelector(s);
const page = $("#invite");
const STAGES = ["baby", "young", "grown"];
// Pair codes are 6 characters (the server uses ABCDEFGHJKMNPQRSTUVWXYZ23456789).
// Any 6 letters or digits are looked up; the server decides.
const CODE = /^[A-Z0-9]{6}$/;

let slimeLook = { stage: "young", skin: "lime" };
const actors = {};

function show(view, { focus = true } = {}) {
  for (const v of document.querySelectorAll("[data-view]")) v.hidden = v.dataset.view !== view;
  page.setAttribute("aria-busy", String(view === "loading"));
  if (!focus) return;
  const h = document.querySelector(`[data-view="${view}"] h1`);
  if (h) {
    h.tabIndex = -1;
    h.focus({ preventScroll: true });
  }
}

function codeFromPath() {
  let raw = "";
  try {
    raw = decodeURIComponent(location.pathname.replace(/^\/f\/?/, "").split("/")[0] || "");
  } catch {
    raw = "";
  }
  if (!raw) raw = new URLSearchParams(location.search).get("code") || "";
  return raw.toUpperCase().replace(/[\s-]/g, "");
}

function slime(canvasId, look, base = "idle") {
  const c = document.getElementById(canvasId);
  if (!c) return null;
  if (actors[canvasId]) {
    actors[canvasId].lookFn = () => look;
    actors[canvasId].setBase(base);
    return actors[canvasId];
  }
  actors[canvasId] = new SlimeActor(c, { look: () => look, base, crop: c.height === 18 ? 2 : 0 });
  return actors[canvasId];
}

// ------------------------------------------------------------ countdown

let countdown = 0;
function startCountdown(expiresAt, code) {
  clearInterval(countdown);
  const end = Date.parse(expiresAt);
  const out = $("#inv-expiry");
  if (Number.isNaN(end)) {
    out.textContent = "Codes expire 10 minutes after they're made.";
    return;
  }
  const tick = () => {
    const left = Math.round((end - Date.now()) / 1000);
    if (left <= 0) {
      clearInterval(countdown);
      problem("expired", code);
      return;
    }
    const m = Math.floor(left / 60), s = String(left % 60).padStart(2, "0");
    out.textContent = `Expires in ${m}:${s}`;
    out.classList.toggle("is-late", left < 60);
  };
  tick();
  countdown = setInterval(tick, 1000);
}

// ---------------------------------------------------------------- views

function renderInvite(code, data) {
  const owner = typeof data?.ownerName === "string" && data.ownerName.trim() ? data.ownerName.trim() : "Someone";
  const s = data?.slime ?? {};
  const name = typeof s.name === "string" && s.name.trim() ? s.name.trim() : "their slime";
  slimeLook = {
    stage: STAGES.includes(s.stage) ? s.stage : "young",
    skin: Object.hasOwn(SKINS, s.skin ?? "") ? s.skin : "lime",
    hat: null, glasses: null, scarf: null,
  };

  $("#inv-owner").textContent = owner;
  $("#inv-owner-2").textContent = owner;
  $("#inv-slime-name").textContent = name;
  $("#inv-code").textContent = code;
  $("#inv-code").setAttribute("aria-label", `Friend code ${code.split("").join(" ")}`);
  for (const el of document.querySelectorAll("[data-code]")) el.textContent = code;
  $("#inv-open").href = `slimy://f/${encodeURIComponent(code)}`;
  document.title = `${owner} invites you · Slimy`;

  loadSprites().then(() => {
    slime("inv-slime", slimeLook, "wave");
    slime("inv-island-slime", slimeLook);
    if (!actors.reel) actors.reel = new Reel($("#inv-island-emote"), "love");
    actors["inv-slime"].play("wave", 3);
  }, () => {});

  show("invite");
  startCountdown(data?.expiresAt, code);
}

const PROBLEMS = {
  invalid: {
    title: "That isn't a friend code.",
    text: "Friend codes have six letters and numbers, like ABC234. Check the link your friend sent, or type the code yourself.",
  },
  notFound: {
    title: "This code doesn't work anymore.",
    text: "Codes expire after 10 minutes and work once. Ask your friend for a new one.",
  },
  expired: {
    title: "This code just expired.",
    text: "Codes last 10 minutes. Ask your friend for a new one, then open the new link.",
  },
  busy: {
    title: "Too many lookups.",
    text: "This network looked up a lot of codes. Try again in an hour, or type the code in Slimy under Friends.",
    retry: true, keepCode: true,
  },
  offline: {
    title: "Couldn't reach the slime server.",
    text: "Check your connection and try again. You can still type the code in Slimy under Friends.",
    retry: true, keepCode: true,
  },
};

function problem(kind, code) {
  clearInterval(countdown);
  const p = PROBLEMS[kind];
  $("#err-title").textContent = p.title;
  $("#err-text").textContent = p.text;
  const box = $("#err-code-box");
  box.hidden = !(p.keepCode && code);
  $("#err-code").textContent = code || "";
  $("#err-retry").hidden = !p.retry;
  document.title = `${p.title.replace(/\.$/, "")} · Slimy`;
  loadSprites().then(() => slime("err-slime", { ...savedLook(), stage: "young" }, kind === "offline" || kind === "busy" ? "confused" : "sad"), () => {});
  show("problem");
}

function enterCode() {
  document.title = "Enter a friend code · Slimy";
  loadSprites().then(() => slime("enter-slime", { ...savedLook(), stage: "young" }, "curious"), () => {});
  show("enter", { focus: false });
}

// --------------------------------------------------------------- lookup

async function lookup(code) {
  show("loading", { focus: false });
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const res = await fetch(`/api/v1/invites/${encodeURIComponent(code)}`, {
      headers: { Accept: "application/json" },
      cache: "no-store",
      signal: controller.signal,
    });
    if (res.status === 404 || res.status === 410) return problem("notFound", code);
    if (res.status === 429) return problem("busy", code);
    if (!res.ok) return problem("offline", code);
    const data = await res.json();
    renderInvite(code, data);
  } catch {
    problem("offline", code);
  } finally {
    clearTimeout(timer);
  }
}

// ----------------------------------------------------------------- boot

const code = codeFromPath();
if (!code) enterCode();
else if (!CODE.test(code)) problem("invalid", code);
else lookup(code);

$("#err-retry").addEventListener("click", () => lookup(code));

$("#inv-copy").addEventListener("click", async () => {
  const btn = $("#inv-copy");
  const label = btn.querySelector("[data-copy-label]");
  const ok = await copyText($("#inv-code").textContent);
  label.textContent = ok ? "Copied" : "Select the code to copy it";
  btn.classList.toggle("is-done", ok);
  $("#copy-status").textContent = ok ? "Code copied." : "Couldn't copy. Select the code instead.";
  clearTimeout(btn.__t);
  btn.__t = setTimeout(() => {
    label.textContent = "Copy code";
    btn.classList.remove("is-done");
  }, 1800);
});

$("#code-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const value = $("#code-input").value.toUpperCase().replace(/[\s-]/g, "");
  const err = $("#code-error");
  if (!CODE.test(value)) {
    err.textContent = "A friend code has six letters and numbers, like ABC234.";
    $("#code-input").setAttribute("aria-invalid", "true");
    return;
  }
  err.textContent = "";
  location.assign(`/f/${value}`);
});
