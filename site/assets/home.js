// The home page is a toy. One slime is shared by every part of it: the phone
// in the hero and its Dynamic Island, the island that follows you in the
// header, the feature tiles, the friends scene and the photo booth.
import {
  loadSprites, sprites, SKINS, SKIN_TITLES, SKIN_LEVELS, calm,
  drawSlime, itemCanvas, drawAllItems, SlimeActor, Reel, onTick, watch, clockT,
  savedLook, saveLook, itemSize,
} from "./slime.js";
import { drawHabitat, drawLockSky, HABITATS, ROOM_FURNITURE, season as seasonNow } from "./habitat.js";
import { isDark, onTheme } from "./site.js";

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const rand = (a) => a[Math.floor(Math.random() * a.length)];
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") node.className = v;
    else if (k === "text") node.textContent = v;
    else node.setAttribute(k, v === true ? "" : String(v));
  }
  node.append(...children);
  return node;
}

// ------------------------------------------------------------------- data

// Theme.color(for:) and Theme.icon(for:)
const NEEDS = [
  { id: "hunger", title: "Food", icon: "apple", reel: "hunger", act: "feed" },
  { id: "fun", title: "Fun", icon: "ball", reel: "fun", act: "play" },
  { id: "energy", title: "Energy", icon: "starJelly", reel: "sleepy", act: "sleep" },
  { id: "clean", title: "Clean", icon: "bubbleItem", reel: "clean", act: "bath" },
  { id: "love", title: "Love", icon: "heart", reel: "love", act: "pet" },
];
const needOf = (id) => NEEDS.find((n) => n.id === id);
// Slime.restingFloor in Shared/Model/Slime.swift.
const FLOOR = 12;
const FOODS = [
  ["apple", "Apple"], ["carrot", "Carrot"], ["berry", "Strawberry"], ["mushroom", "Mushroom"],
  ["cookie", "Cookie"], ["soup", "Soup"], ["honey", "Honey"], ["pudding", "Pudding"],
  ["cake", "Cake"], ["starJelly", "Star jelly"],
];
const foodName = (id) => (FOODS.find((f) => f[0] === id)?.[1] ?? id).toLowerCase();
const WEAR = [
  ["hat", "Hats", [["hat.party", "Party hat"], ["hat.beanie", "Beanie"], ["hat.flower", "Blossom"], ["hat.pumpkin", "Pumpkin"], ["hat.crown", "Crown"], ["hat.wizard", "Wizard hat"]]],
  ["glasses", "Glasses", [["glasses.round", "Round"], ["glasses.shades", "Shades"], ["glasses.star", "Star"]]],
  ["scarf", "Scarves", [["scarf.red", "Red"], ["scarf.striped", "Striped"]]],
];
// Shared/Model/Arcade.swift: GameID.title and icon.
const GAMES = [
  ["apple", "Snack Catcher"], ["coin", "Slime Jump"], ["bubbleItem", "Bubble Pop"], ["flower", "Memory Garden"],
  ["flag", "Slime Racing"], ["noteItem", "Rhythm Bounce"], ["seed", "Garden Puzzle"], ["chest", "Treasure Dig"],
  ["ball", "Slime Basketball"], ["gem", "Color Match"], ["cake", "Slime Stack", true], ["berry", "Jelly Dash", true],
];
// Shared/Model/PhoneEvent.swift: reaction and lines.
const EVENTS = [
  { id: "battery", label: "Battery low", reel: "battery", base: "melt", anim: "melt", lines: ["battery 20%. i'm melting", "getting all runny", "plug me in. please."] },
  { id: "zap", label: "Charging", reel: "zap", base: "zap", anim: "zap", lines: ["power. yum.", "tingly", "*slurps electricity*"] },
  { id: "wifi", label: "Wi-Fi is back", reel: "wifi", base: "idle", anim: "celebrate", lines: ["wi-fi. zoom.", "hi router", "connected"] },
  { id: "confused", label: "No internet", reel: "confused", base: "confused", anim: "confused", lines: ["no internet", "offline. so quiet.", "where did everyone go"] },
  { id: "morning", label: "Good morning", reel: "morning", base: "stretch", anim: "wave", lines: ["good morning", "rise and shine", "new day. new goo."] },
  { id: "night", label: "Good night", reel: "night", base: "sleep", anim: "sleep", lines: ["sleep well", "good night", "time for slime dreams"] },
];
// Shared/Model/Dialogue.swift
const CHATTER = [
  "just wobbling along", "i saw a fly. it was fast.", "practicing my bounce", "what are you up to?",
  "i'm here if you need me", "i studied a speck of dust. fascinating.", "i'm glad you exist", "all good here",
];
const WISH_LINES = {
  hunger: ["my tummy is making noises", "an apple would be nice right now"],
  fun: ["play? please?", "i'm bored"],
  energy: ["eyes. so. heavy.", "bed?"],
  clean: ["a bath would be nice", "i'm sticking to myself"],
  love: ["cuddle break?", "pet me a little?"],
};
const TRAITS = {
  playful: { line: "wanna play something?", anim: "play" },
  cuddly: { line: "busy day? i'll keep the wobble warm.", anim: "blush" },
  foodie: { line: "is it snack o'clock?", anim: "eat" },
  sleepy: { line: "i could sleep standing up. i don't have legs.", anim: "sleep" },
  curious: { line: "i studied a speck of dust. fascinating.", anim: "curious" },
};

const state = {
  name: "Blob",
  look: { ...savedLook(), stage: "young" },
  home: null, // null: the room by day, the garden by night
  needs: { hunger: 64, fun: 52, energy: 78, clean: 70, love: 58 },
  level: 11, xp: 0.4, coins: 120,
  asleep: false,
  fav: rand(FOODS)[0],
  favFound: false,
  pets: 0, pokes: 0,
  love: 30,
  sx: 0,
  line: "just wobbling along",
};
const homeNow = () => state.home ?? (isDark() ? "garden" : "room");

// ----------------------------------------------------------------- scenes

const worldScenes = [];
class Scene {
  constructor(canvas, opts) {
    this.canvas = canvas;
    this.opts = opts;
    watch(canvas, this);
    onTick(() => this.visible && !calm() && this.draw(), 1);
    worldScenes.push(this);
    this.draw();
  }
  draw() {
    const o = this.opts();
    drawHabitat(this.canvas, o.habitat, { ...o, t: calm() ? 0 : clockT, night: o.night ?? isDark() });
  }
}

// ---------------------------------------------------------------- islands

// Widget/IslandParts.swift, NeedActions: one button per need, in need order.
const ISLAND_ACTS = [
  ["feed", "Feed", "apple", "hunger"], ["play", "Play", "ball", "fun"], ["sleep", "Sleep", "starJelly", "energy"],
  ["bath", "Bath", "bubbleItem", "clean"], ["pet", "Pet", "heart", "love"],
];

/// A Dynamic Island: compact, a short status ("Food 94%"), a line of
/// speech, or expanded with needs and buttons, like touch and hold on iPhone.
class IslandView {
  constructor(root) {
    this.root = root;
    this.toggle = $(".island-toggle", root);
    this.toggleText = $(".sr-only", this.toggle);
    this.panel = $(".island-panel", root);
    this.slime = new SlimeActor($(".i-slime", root), { look: () => state.look, crop: 2 });
    this.emote = new Reel($(".i-emote", root));
    this.face = new SlimeActor($(".ip-slime", root), { look: () => state.look, crop: 2 });
    this.label = $(".i-label", root);
    this.meter = $(".i-meter", root);
    this.sayEl = $(".i-say", root);
    this.line = $("[data-line]", root);
    this.timer = 0;
    this.autoTimer = 0;
    this.build();
    this.toggle.addEventListener("click", () => (this.isOpen ? this.close() : this.open()));
    // The transparent rim around the pill counts as part of the island.
    root.addEventListener("click", (e) => {
      if (e.target === root && !this.isOpen) this.open();
    });
    new ResizeObserver(() => this.isOpen && this.fit()).observe(this.panel);
  }
  get isOpen() { return this.opened === true; }
  build() {
    const ul = $(".ip-needs", this.root);
    for (const n of NEEDS) ul.append(el("li", { "data-need": n.id }, el("span", { class: "sr-only", text: n.title }), el("i")));
    const box = $(".ip-actions", this.root);
    for (const [act, label, icon, need] of ISLAND_ACTS) {
      const b = el("button", { type: "button", "data-act": act, "data-need": need }, itemCanvas(icon, 2), el("span", { text: label }));
      b.addEventListener("click", () => {
        clearTimeout(this.autoTimer);
        islandAction(act, this);
      });
      box.append(b);
    }
  }
  open({ auto = 0 } = {}) {
    clearTimeout(this.timer);
    clearTimeout(this.autoTimer);
    this.opened = true;
    this.panel.hidden = false;
    this.toggle.setAttribute("aria-expanded", "true");
    this.toggleText.textContent = "Close the Dynamic Island";
    this.sync();
    // Measuring flushes styles, so the change of state below still transitions.
    this.fit();
    this.root.dataset.state = "expanded";
    if (!calm()) this.face.play("wave", 1);
    if (auto) this.autoTimer = setTimeout(() => this.close(), auto);
  }
  fit() {
    const top = $(".ip-top", this.root);
    const border = parseFloat(getComputedStyle(this.root).borderTopWidth) || 0;
    this.root.style.height = this.panel.offsetHeight + border * 2 + "px";
    this.root.style.setProperty("--top-h", top.offsetTop + top.offsetHeight + "px");
  }
  close({ focus = false } = {}) {
    clearTimeout(this.autoTimer);
    if (!this.isOpen) return;
    this.opened = false;
    this.root.dataset.state = "compact";
    this.root.style.height = "";
    this.toggle.setAttribute("aria-expanded", "false");
    this.toggleText.textContent = "Open the Dynamic Island";
    setTimeout(() => { if (!this.isOpen) this.panel.hidden = true; }, calm() ? 0 : 320);
    if (focus) this.toggle.focus();
  }
  flash(kind, ms) {
    if (this.isOpen) return;
    this.root.dataset.state = kind;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => { if (!this.isOpen) this.root.dataset.state = "compact"; }, ms);
  }
  status(needId, ms = 2600) {
    const v = Math.round(state.needs[needId]);
    this.label.textContent = `${needOf(needId).title} ${v}%`;
    this.meter.dataset.need = needId;
    $("i", this.meter).style.width = v + "%";
    this.flash("status", ms);
  }
  talk(text, ms = 3200) {
    if (!this.sayEl) return;
    this.sayEl.textContent = text;
    this.flash("talk", ms);
  }
  sync() {
    if (this.line) this.line.textContent = state.line;
    for (const n of NEEDS) {
      const bar = $(`.ip-needs [data-need="${n.id}"] i`, this.root);
      if (bar) bar.style.width = Math.round(state.needs[n.id]) + "%";
    }
  }
  mood(reel) { this.emote.set(reel); }
  play(anim, loops) { this.slime.play(anim, loops); if (this.isOpen) this.face.play(anim, loops); }
  setBase(anim) { this.slime.setBase(anim); this.face.setBase(anim); }
  draw() { this.slime.draw(); this.face.draw(); }
}

let hero;
let heroIsland;
let navIsland;
let heroVisible = true;
const screen = () => $("#screen");
const g = () => screen().clientWidth / 96;
// The big slime on the 96-wide scene grid: its top row and pixel size (home.css: .slime).
const SL = { top: 70, scale: 2.5 };
const islands = () => [heroIsland, navIsland].filter(Boolean);

function playAll(anim, loops = 2) {
  islands().forEach((i) => i.play(anim, loops));
  return hero.play(anim, loops);
}
function setBaseAll(anim) {
  hero.setBase(anim);
  islands().forEach((i) => i.setBase(anim));
}

/// Speech: a bubble over the slime, or the header island when the phone is out of view.
let bubbleTimer;
function say(text, { alert = false, ms = 3400, announce = true } = {}) {
  state.line = text;
  const b = $("#bubble");
  b.textContent = text;
  b.classList.add("is-on");
  clearTimeout(bubbleTimer);
  bubbleTimer = setTimeout(() => b.classList.remove("is-on"), ms);
  if (announce) $("#say").textContent = `${state.name} says: ${text}`;
  islands().forEach((i) => i.sync());
  if (!heroVisible) navIsland?.talk(text, ms);
  else if (alert) heroIsland.open({ auto: ms + 600 });
}

/// After caring for it, the islands show the need for a moment, like "Food 94%".
function showStatus(needId) {
  if (heroVisible) heroIsland.status(needId);
  else navIsland?.status(needId);
}

// Island emote: what it needs, unless something just happened.
let tempReel = null;
let tempTimer;
const happy = ["happyA", "happyB", "happyC"][new Date().getMinutes() % 3];
function wish() {
  let low = null;
  for (const n of NEEDS) if (state.needs[n.id] < 40 && (!low || state.needs[n.id] < state.needs[low.id])) low = n;
  return low;
}
function currentReel() {
  if (tempReel) return tempReel;
  if (state.asleep) return "sleepy";
  return wish()?.reel ?? happy;
}
function flashReel(reel, ms = 6000) {
  tempReel = reel;
  clearTimeout(tempTimer);
  tempTimer = setTimeout(() => { tempReel = null; syncReels(); }, ms);
  syncReels();
}
function syncReels() {
  const r = currentReel();
  islands().forEach((i) => i.mood(r));
}

// ------------------------------------------------------------------ needs

function setNeed(id, v) {
  state.needs[id] = clamp(v, FLOOR, 100);
  renderNeeds();
}
const bump = (id, d) => setNeed(id, state.needs[id] + d);

// A ring with a tick at the resting floor (12 of 100, 43.2° round).
function ring(n) {
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", "0 0 44 44");
  svg.setAttribute("aria-hidden", "true");
  const circle = (cls) => {
    const c = document.createElementNS(NS, "circle");
    c.setAttribute("class", cls);
    c.setAttribute("cx", "22");
    c.setAttribute("cy", "22");
    c.setAttribute("r", "19.5");
    return c;
  };
  const val = circle("val");
  val.setAttribute("pathLength", "100");
  val.setAttribute("stroke-dasharray", "100");
  val.setAttribute("stroke-dashoffset", "100");
  const tick = document.createElementNS(NS, "line");
  tick.setAttribute("class", "floor");
  tick.setAttribute("x1", "35.1");
  tick.setAttribute("y1", "34.3");
  tick.setAttribute("x2", "39.1");
  tick.setAttribute("y2", "38.1");
  svg.append(circle("track"), val, tick);
  const box = el("div", { class: "ring" }, svg, itemCanvas(n.icon, 2));
  return box;
}

function buildNeeds() {
  for (const box of [$("#needs"), $("#feature-rings")]) {
    const pct = box.id === "feature-rings";
    for (const n of NEEDS) {
      const item = el("div", { class: "need", "data-need": n.id, role: "img", "aria-label": n.title }, ring(n), el("span", { text: n.title }));
      if (pct) item.append(el("b", { class: "pct" }));
      box.append(item);
    }
  }
  const lc = $("#lc-needs");
  for (const n of NEEDS) lc.append(el("li", { "data-need": n.id }, el("i")));
}

function renderNeeds() {
  const w = wish();
  for (const n of NEEDS) {
    const v = Math.round(state.needs[n.id]);
    $$(`.need[data-need="${n.id}"]`).forEach((node) => {
      $(".val", node).setAttribute("stroke-dashoffset", String(100 - v));
      node.setAttribute("aria-label", `${n.title} ${v} percent${w === n ? ", needed right now" : ""}`);
      node.classList.toggle("is-wish", w === n);
      const pct = $(".pct", node);
      if (pct) pct.textContent = v + "%";
    });
    const key = $(`.key[data-act="${n.act}"]`);
    if (key) {
      key.setAttribute("data-need", n.id);
      key.classList.toggle("is-urgent", w === n && !(n.act === "sleep" && state.asleep));
    }
    const lc = $(`#lc-needs [data-need="${n.id}"] i`);
    if (lc) lc.style.width = v + "%";
  }
  islands().forEach((i) => i.sync());
  syncReels();
}

// ------------------------------------------------------ level and coins

function setText(sel, text) { $$(sel).forEach((n) => (n.textContent = text)); }

function gainXP(x) {
  state.xp += x;
  if (state.xp >= 1) {
    state.xp -= 1;
    state.level++;
    setText("[data-level]", String(state.level));
    if (state.level === 12) {
      state.look.stage = "grown";
      redrawSlimes();
      $$(".ip-sub").forEach((n) => (n.textContent = "Big slime"));
      setTimeout(() => {
        playAll("celebrate", 3);
        hearts(4);
        say("i grew. look at me.", { alert: true });
      }, 900);
    } else {
      setTimeout(() => say(`level ${state.level}!`, { alert: true }), 900);
    }
  }
  $("#xp").style.width = Math.round(state.xp * 100) + "%";
}

function addCoins(n, { pop = false } = {}) {
  state.coins += n;
  setText("[data-coins]", String(state.coins));
  $$("[data-coins-label]").forEach((c) => c.setAttribute("aria-label", `${state.coins} coins`));
  const c = $("#coins");
  c.classList.remove("is-bump");
  void c.offsetWidth;
  c.classList.add("is-bump");
  if (pop) {
    const p = $("#coin-pop");
    p.textContent = "+" + n;
    p.classList.remove("go");
    void p.offsetWidth;
    p.classList.add("go");
  }
}

// ------------------------------------------------------ effects in the phone

const slimeCX = () => (48 + state.sx) * g();
const mouthY = () => (SL.top + 15 * SL.scale) * g();
const feetY = () => (SL.top + 19 * SL.scale) * g();

function fxSprite(name, px, x, y, layer = $("#fx")) {
  const c = itemCanvas(name, 1);
  const [w, h] = [c.width, c.height];
  c.style.width = w * px + "px";
  c.style.height = h * px + "px";
  c.style.transform = `translate(${x - (w * px) / 2}px, ${y - (h * px) / 2}px)`;
  layer.append(c);
  return { el: c, w: w * px, h: h * px };
}
function slimeTopY() {
  const top = Number(getComputedStyle($("#stage")).getPropertyValue("--top")) || 7;
  return (SL.top + top * SL.scale) * g();
}
function hearts(n = 3) {
  if (calm()) return;
  const u = g();
  for (let i = 0; i < n; i++) {
    const x = slimeCX() + (Math.random() * 36 - 18) * u;
    const y = slimeTopY() + 6 * u;
    const { el: c, w, h } = fxSprite("heart", u * 1.5, x, y);
    const x0 = x - w / 2, y0 = y - h / 2;
    c.animate([
      { transform: `translate(${x0}px, ${y0}px) scale(.6)`, opacity: 0 },
      { transform: `translate(${x0}px, ${y0 - 14 * u}px) scale(1)`, opacity: 1, offset: 0.25 },
      { transform: `translate(${x0 + (Math.random() * 8 - 4) * u}px, ${y0 - 46 * u}px) scale(1)`, opacity: 0 },
    ], { duration: 1500, delay: i * 180, easing: "ease-out", fill: "backwards" }).finished.then(() => c.remove());
  }
}
function bubbles() {
  if (calm()) return;
  const u = g();
  for (let i = 0; i < 10; i++) {
    const s = u * (0.7 + Math.random() * 0.7);
    const x = slimeCX() + (Math.random() * 44 - 22) * u;
    const y = mouthY();
    const { el: c, w, h } = fxSprite("bubbleItem", s, x, y);
    const x0 = x - w / 2, y0 = y - h / 2;
    c.animate([
      { transform: `translate(${x0}px, ${y0}px)`, opacity: 0 },
      { transform: `translate(${x0}px, ${y0 - 8 * u}px)`, opacity: 1, offset: 0.2 },
      { transform: `translate(${x0 + (Math.random() * 10 - 5) * u}px, ${y0 - 60 * u}px)`, opacity: 0 },
    ], { duration: 1800, delay: i * 160, easing: "ease-out", fill: "backwards" }).finished.then(() => c.remove());
  }
}
function ball() {
  if (calm()) return;
  const u = g();
  const top = slimeTopY();
  const { el: c, w, h } = fxSprite("ball", u * 1.4, slimeCX(), top);
  const x = slimeCX() - w / 2, floor = top - h - 2 * u;
  const keys = [];
  for (let i = 0; i <= 3; i++) {
    const dx = (i - 1.5) * 10 * u;
    keys.push({ transform: `translate(${x + dx}px, ${floor - 34 * u}px) rotate(${i * 120}deg)`, easing: "cubic-bezier(.4,0,1,1)" });
    keys.push({ transform: `translate(${x + dx + 5 * u}px, ${floor}px) rotate(${i * 120 + 60}deg)`, easing: "cubic-bezier(0,0,.6,1)" });
  }
  keys.push({ transform: `translate(${x + 40 * u}px, ${floor - 40 * u}px) rotate(480deg)`, opacity: 0 });
  c.animate(keys, { duration: 2600 }).finished.then(() => c.remove());
}
// Squish and stretch, like LiveSlimeView.
function squish() {
  if (calm()) return;
  $("#slime-canvas").animate([
    { transform: "scale(1.14, .84)" },
    { transform: "scale(.93, 1.09)", offset: 0.35 },
    { transform: "scale(1.04, .97)", offset: 0.65 },
    { transform: "scale(1, 1)" },
  ], { duration: 560, easing: "ease-out" });
}

/// Hops sideways to `target` (grid columns from the middle).
function hopTo(target) {
  target = clamp(Math.round(target), -16, 16);
  const d = target - state.sx;
  const stage = $("#stage");
  const done = () => {
    state.sx = target;
    stage.style.setProperty("--sx", String(target));
  };
  if (!d) return Promise.resolve();
  if (calm()) {
    done();
    return Promise.resolve();
  }
  const u = g();
  const hops = Math.max(1, Math.round(Math.abs(d) / 7));
  const keys = [];
  for (let i = 0; i <= hops; i++) {
    keys.push({ transform: `translate(${(d * i / hops) * u}px, 0)`, offset: i / hops });
    if (i < hops) keys.push({ transform: `translate(${(d * (i + 0.5) / hops) * u}px, ${-5 * u}px)`, offset: (i + 0.5) / hops });
  }
  $("#bubble").classList.remove("is-on");
  const anim = $("#slime").animate(keys, { duration: hops * 300, easing: "ease-in-out", fill: "forwards" });
  return anim.finished.then(() => {
    done();
    anim.cancel();
  });
}

// ------------------------------------------------------------------ actions

function wakeUp(line = "who woke me up") {
  state.asleep = false;
  screen().classList.remove("is-asleep");
  $$('[data-act="sleep"] span').forEach((n) => (n.textContent = "Sleep"));
  $('.key[data-act="sleep"]').classList.remove("is-on");
  setBaseAll("idle");
  playAll("stretch", 1);
  say(line);
  syncStatus();
  renderNeeds();
}

function poke() {
  squish();
  state.pokes++;
  if (state.asleep) return wakeUp(rand(["who woke me up", "five more minutes …"]));
  bump("fun", 3);
  if (state.pokes % 5 === 0) return pet();
  playAll(["curious", "blush", "celebrate", "confused", "wave"][state.pokes % 5], 1);
  if (state.pokes === 1) say("hehe. tickles.");
  else if (Math.random() < 0.45) say(rand(["hehe. tickles.", "what's that?", "more of that", "practicing my bounce", "i'm here if you need me"]));
  gainXP(0.04);
}

function pet() {
  if (state.asleep) return wakeUp();
  state.pets++;
  bump("love", 14);
  gainXP(0.1);
  hearts(3);
  flashReel("love", 4000);
  showStatus("love");
  if (state.pets % 7 === 0) {
    playAll("melt", 2);
    say("i'm all flat from petting");
  } else {
    playAll(rand(["love", "blush"]), 2);
    say(rand(["hehe. tickles.", "more of that", "that's nice", "okay okay, you like me. got it."]));
  }
}

async function feed(id) {
  if (state.asleep) return wakeUp("zzz … dreaming of " + foodName(state.fav));
  if (state.needs.hunger >= 96) {
    playAll("sulk", 1);
    say(rand(["no room left. really.", "later. i'm full.", "i'm about to burst"]));
    return;
  }
  bump("hunger", 18);
  gainXP(0.14);
  flashReel("happyA", 3000);
  showStatus("hunger");
  const name = foodName(id);
  const ate = playAll("eat", 2);
  say(id === state.fav ? `nom. ${name}.` : rand([`nom. ${name}.`, "thanks. that was tasty.", `best ${name} in the world`]));
  await ate;
  if (id === state.fav) {
    playAll("love", 2);
    hearts(4);
    say("you just know what i like", { alert: !state.favFound });
    if (!state.favFound) {
      state.favFound = true;
      bump("love", 10);
      const b = $(`.food[data-food="${id}"]`);
      b?.classList.add("is-fav");
      b?.setAttribute("aria-label", `${FOODS.find((f) => f[0] === id)[1]}, its favourite`);
    }
  }
}

function play() {
  if (state.asleep) return wakeUp();
  if (state.needs.energy < 25) {
    playAll("sad", 1);
    say(rand(["too tired to play", "sleep first, then play"]));
    return;
  }
  const score = 30 + Math.floor(Math.random() * 60);
  bump("fun", 24);
  bump("energy", -8);
  gainXP(0.16);
  ball();
  flashReel("fun", 3500);
  showStatus("fun");
  playAll("play", 3).then(() => {
    addCoins(Math.max(3, Math.round(score / 10)), { pop: true });
    say(score > 80 ? `new record! ${score}!` : `wheee. ${score} points.`);
  });
}

function bath() {
  if (state.asleep) return wakeUp();
  if (state.needs.clean >= 96) {
    playAll("curious", 1);
    say(rand(["i'm already clean", "can't get any cleaner"]));
    return;
  }
  setNeed("clean", 100);
  gainXP(0.12);
  bubbles();
  flashReel("clean", 3500);
  showStatus("clean");
  playAll("bath", 3).then(() => say(rand(["squeaky clean", "blub. clean.", "i'm shiny"])));
}

function sleep() {
  if (state.asleep) return wakeUp(rand(["good morning", "well rested"]));
  state.asleep = true;
  screen().classList.add("is-asleep");
  $$('[data-act="sleep"] span').forEach((n) => (n.textContent = "Wake"));
  $('.key[data-act="sleep"]').classList.add("is-on");
  setBaseAll("sleep");
  say(rand(["good night", "sleep well. me too.", "lights out"]));
  syncStatus();
  renderNeeds();
}

const ACTIONS = { feed: () => toggleTray(), play, bath, sleep, pet };

/// The island's buttons work like the app's: Feed gives the favourite (or any) food.
function islandAction(act, island) {
  if (act === "feed") feed(state.favFound ? state.fav : rand(FOODS)[0]);
  else ACTIONS[act]();
  island.sync();
  const anim = { feed: "eat", play: "play", bath: "bath", pet: "love" }[act];
  if (anim && !calm()) island.face.play(anim, 2);
}

function toggleTray(force) {
  const dock = $("#dock");
  const open = typeof force === "boolean" ? force : !dock.classList.contains("is-feeding");
  dock.classList.toggle("is-feeding", open);
  $('.key[data-act="feed"]').setAttribute("aria-expanded", String(open));
  $$(".food").forEach((f) => (f.tabIndex = open ? 0 : -1));
  if (open && !state.asleep) say(state.needs.hunger < 40 ? "hungry. very hungry." : rand(["an apple would be nice right now", "not hungry. but " + foodName(state.fav) + " always works."]));
}

// Food tray: tap to toss, or drag onto the slime.
function buildTray() {
  const tray = $("#tray");
  for (const [id, title] of FOODS) {
    const b = el("button", { type: "button", class: "food", "data-food": id, tabindex: "-1", "aria-label": title, title });
    b.append(itemCanvas(id, 3));
    tray.append(b);
    let start = null, ghost = null, dragged = false;
    b.addEventListener("pointerdown", (e) => {
      if (e.button !== 0) return;
      start = { x: e.clientX, y: e.clientY };
      dragged = false;
    });
    b.addEventListener("pointermove", (e) => {
      if (!start) return;
      if (!dragged && Math.abs(e.clientY - start.y) > 10 && Math.abs(e.clientY - start.y) > Math.abs(e.clientX - start.x)) {
        dragged = true;
        b.setPointerCapture(e.pointerId);
        ghost = itemCanvas(id, 1);
        ghost.className = "drag-food";
        document.body.append(ghost);
        playAll("curious", 1);
      }
      if (ghost) {
        ghost.style.left = e.clientX + "px";
        ghost.style.top = e.clientY + "px";
      }
    });
    const end = (e) => {
      if (!start) return;
      start = null;
      if (!dragged) return;
      b.__dragged = true;
      const r = $("#slime").getBoundingClientRect();
      const hit = e.type === "pointerup" && e.clientX > r.left - 12 && e.clientX < r.right + 12 && e.clientY > r.top - 12 && e.clientY < r.bottom + 12;
      const gh = ghost;
      ghost = null;
      if (hit) {
        gh.animate([{ opacity: 1, transform: "translate(-50%, -60%) scale(1)" }, { opacity: 0, transform: "translate(-50%, -60%) scale(.3)" }], { duration: 220 }).finished.then(() => gh.remove());
        feed(id);
      } else {
        gh.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 200 }).finished.then(() => gh.remove());
      }
    };
    b.addEventListener("pointerup", end);
    b.addEventListener("pointercancel", end);
    b.addEventListener("click", () => {
      if (b.__dragged) { b.__dragged = false; return; }
      toss(id, b);
    });
  }
}

function toss(id, from) {
  if (calm() || state.asleep || state.needs.hunger >= 96) return feed(id);
  const sr = screen().getBoundingClientRect();
  const fr = from.getBoundingClientRect();
  const u = g();
  const x0 = fr.left + fr.width / 2 - sr.left, y0 = fr.top + fr.height / 2 - sr.top;
  const x1 = slimeCX(), y1 = mouthY();
  const { el: c, w, h } = fxSprite(id, u * 1.4, x0, y0);
  const t = (x, y, s) => `translate(${x - w / 2}px, ${y - h / 2}px) scale(${s})`;
  c.animate([
    { transform: t(x0, y0, 0.7) },
    { transform: t((x0 + x1) / 2, Math.min(y0, y1) - 34 * u, 1.05), offset: 0.5 },
    { transform: t(x1, y1, 0.8), opacity: 1, offset: 0.88 },
    { transform: t(x1, y1 + 2 * u, 0.2), opacity: 0 },
  ], { duration: 620, easing: "cubic-bezier(.25,.6,.45,1)" }).finished.then(() => c.remove());
  setTimeout(() => feed(id), 520);
}

// Tap the floor: a random snack drops, the slime hops over and eats it.
let floorQueue = Promise.resolve();
function setupFloor() {
  $("#floor-hit").addEventListener("pointerdown", (e) => {
    if (e.button) return;
    const sr = screen().getBoundingClientRect();
    const u = g();
    dropFood(rand(FOODS)[0], (e.clientX - sr.left) / u, (e.clientY - sr.top) / u);
  });
}

function dropFood(id, gx, gy) {
  const u = g();
  const px = 1.5 * u;
  const fx = clamp(gx, 10, 86);
  const [w, h] = itemSize(id).map((n) => n * px);
  const x0 = fx * u - w / 2;
  const yEnd = feetY() - h;
  const yStart = Math.min(gy * u - h / 2, yEnd - 12 * u);
  const { el: c } = fxSprite(id, px, fx * u, yStart + h / 2);
  let landed;
  if (calm()) {
    c.style.transform = `translate(${x0}px, ${yEnd}px)`;
    landed = Promise.resolve();
  } else {
    landed = c.animate([
      { transform: `translate(${x0}px, ${yStart}px)`, easing: "cubic-bezier(.5,0,1,1)" },
      { transform: `translate(${x0}px, ${yEnd}px)`, offset: 0.62, easing: "cubic-bezier(0,0,.5,1)" },
      { transform: `translate(${x0}px, ${yEnd - 5 * u}px)`, offset: 0.8, easing: "cubic-bezier(.5,0,1,1)" },
      { transform: `translate(${x0}px, ${yEnd}px)` },
    ], { duration: 640, fill: "forwards" }).finished;
  }
  floorQueue = floorQueue.then(async () => {
    await landed;
    if (state.asleep) {
      c.remove();
      return wakeUp("zzz … something smells good");
    }
    await hopTo(fx - 48);
    c.animate([{ opacity: 1 }, { opacity: 0, transform: `translate(${x0}px, ${yEnd - 2 * u}px) scale(.3)` }], { duration: calm() ? 0 : 240, fill: "forwards" }).finished.then(() => c.remove());
    await feed(id);
  }).catch(() => c.remove());
}

// The slime itself: tap to poke, rub to pet, pull to stretch.
function setupSlime() {
  const hit = $("#slime");
  const cv = $("#slime-canvas");
  let down = null, rubbed = 0, last = null, moved = false;
  hit.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    down = { x: e.clientX, y: e.clientY };
    last = { x: e.clientX, y: e.clientY };
    moved = false;
    rubbed = 0;
    hit.setPointerCapture(e.pointerId);
  });
  hit.addEventListener("pointermove", (e) => {
    if (!down) return;
    const dx = clamp(e.clientX - down.x, -120, 120);
    const dy = clamp(e.clientY - down.y, -160, 160);
    if (!moved && Math.hypot(dx, dy) > 4) moved = true;
    if (!moved) return;
    rubbed += Math.hypot(e.clientX - last.x, e.clientY - last.y);
    last = { x: e.clientX, y: e.clientY };
    if (rubbed > 420) { rubbed = 0; pet(); }
    if (calm()) return;
    const pull = clamp(-dy / 500, -0.3, 0.3);
    cv.style.transform = `translateX(${dx * 0.18}px) scale(${1 - pull * 0.45 + Math.abs(dx) / 1400}, ${1 + pull})`;
  });
  const release = () => {
    if (!down) return;
    down = null;
    if (!moved) return poke();
    const from = cv.style.transform;
    cv.style.transform = "";
    if (from && !calm()) {
      cv.animate([
        { transform: from },
        { transform: "translateX(0) scale(.95, 1.06)", offset: 0.4 },
        { transform: "translateX(0) scale(1.02, .98)", offset: 0.7 },
        { transform: "none" },
      ], { duration: 520, easing: "ease-out" });
    }
  };
  hit.addEventListener("pointerup", release);
  hit.addEventListener("pointercancel", release);
  hit.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); poke(); }
    if (e.key.toLowerCase() === "p") pet();
  });
}

function syncStatus() {
  const h = homeNow();
  const darkish = state.asleep || h === "space" || h === "crystal" || (isDark() && h !== "beach");
  screen().classList.toggle("on-dark", darkish);
}

function syncTop() {
  // Where the speech bubble sits: the top of the sprite, minus the hat.
  const look = state.look;
  const a = sprites().anchors[`${look.stage}.idle`][0][0];
  const hat = look.hat ? sprites().accessories[look.hat].rows.length : 0;
  $("#stage").style.setProperty("--top", String(Math.max(0, a - hat)));
}

const lookActors = new Set();
function redrawSlimes() {
  hero.draw();
  islands().forEach((i) => i.draw());
  lookActors.forEach((a) => a.draw());
  syncTop();
  drawBooth();
}

function lookChanged() {
  saveLook(state.look);
  window.dispatchEvent(new Event("slimy:look"));
  redrawSlimes();
}

function redrawWorld() {
  if (!sprites()) return;
  worldScenes.forEach((s) => s.draw());
  drawHomes();
  syncHomes();
  syncStatus();
  drawSkies();
  drawGrass();
  drawSeason();
  drawBooth();
}

// ------------------------------------------------------------ customising

function setupTabs() {
  const tabs = $$('.tools-head [role="tab"]');
  const select = (tab) => {
    tabs.forEach((t) => {
      const on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      $("#" + t.getAttribute("aria-controls")).hidden = !on;
    });
  };
  tabs.forEach((t, i) => {
    t.addEventListener("click", () => select(t));
    t.addEventListener("keydown", (e) => {
      const d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      if (!d) return;
      e.preventDefault();
      const next = tabs[(i + d + tabs.length) % tabs.length];
      select(next);
      next.focus();
    });
  });
}

function setSkin(id, from) {
  if (state.look.skin === id) {
    playAll("blush", 1);
    return;
  }
  state.look.skin = id;
  $$(".swatch, .skin").forEach((x) => x.setAttribute("aria-pressed", String(x.dataset.skin === id)));
  lookChanged();
  playAll("celebrate", 1);
  flashReel("happyC", 3000);
  say(rand(["suits me, right?", "i look good", `${SKIN_TITLES[id].toLowerCase()}. i like it.`]), { alert: from === "showcase" });
}

function buildSwatches() {
  const box = $("#swatches");
  for (const id of Object.keys(SKINS)) {
    const c = el("canvas", { width: 24, height: 14, "aria-hidden": "true" });
    drawSlime(c.getContext("2d"), { stage: "young", skin: id }, "idle", 0, 0, -6);
    const b = el("button", { type: "button", class: "swatch", "data-skin": id, "aria-pressed": String(id === state.look.skin) }, c, el("span", { text: SKIN_TITLES[id] }));
    b.addEventListener("click", () => setSkin(id, "swatch"));
    box.append(b);
  }
}

// OnboardingView offers these three when the egg hatches.
const HATCH_SKINS = ["lime", "bubblegum", "ocean"];
function buildSkinRow() {
  const box = $("#skin-row");
  for (const id of Object.keys(SKINS)) {
    const c = el("canvas", { width: 24, height: 20, "aria-hidden": "true" });
    const lvl = SKIN_LEVELS[id];
    const b = el("button", { type: "button", class: "skin", "data-skin": id, "aria-pressed": String(id === state.look.skin) },
      c, el("b", { text: SKIN_TITLES[id] }), el("span", { text: HATCH_SKINS.includes(id) ? "At hatch" : `Level ${lvl}` }));
    const actor = new SlimeActor(c, { look: { stage: "young", skin: id } });
    b.addEventListener("click", () => {
      actor.play("celebrate", 1);
      setSkin(id, "showcase");
    });
    box.append(b);
  }
}

function buildWear() {
  const box = $("#wear");
  for (const [slot, label, items] of WEAR) {
    const list = el("div", { class: "wear-items", role: "group", "aria-labelledby": `wear-${slot}` });
    box.append(el("div", { class: "wear-row" }, el("span", { id: `wear-${slot}`, text: label }), list));
    for (const [id, title] of [[null, "None"], ...items]) {
      const b = el("button", { type: "button", class: "wear-item", "aria-pressed": String(state.look[slot] === id) });
      if (id) {
        const [w, h] = itemSize(id);
        const scale = Math.max(1, Math.min(3, Math.floor(26 / Math.max(w, h * 1.3))));
        b.append(el("i", { class: "wi-art" }, itemCanvas(id, scale)));
      } else {
        const art = el("i", { class: "wi-art none", "aria-hidden": "true" });
        art.innerHTML = '<svg viewBox="0 0 20 20"><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M5 15 15 5" stroke="currentColor" stroke-width="1.6"/></svg>';
        b.append(art);
      }
      b.append(el("span", { text: title }));
      b.addEventListener("click", () => {
        state.look[slot] = id;
        $$(".wear-item", list).forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
        lookChanged();
        if (id) {
          playAll(rand(["blush", "celebrate"]), 1);
          say(rand([`ooh, ${title.toLowerCase()}`, "suits me, right?", "i look good"]));
        } else {
          playAll("curious", 1);
        }
      });
      list.append(b);
    }
  }
}

const homeCanvases = [];
function buildHomes() {
  const box = $("#homes");
  for (const h of HABITATS) {
    const c = el("canvas", { width: 96, height: 64, "aria-hidden": "true" });
    homeCanvases.push([c, h.id]);
    const label = el("span", { text: h.title });
    label.append(el("small", { text: h.note }));
    const b = el("button", { type: "button", class: "home", "data-home": h.id, "aria-pressed": "false" }, c, label);
    b.addEventListener("click", () => {
      if (homeNow() === h.id) return;
      state.home = h.id;
      redrawWorld();
      playAll("curious", 1);
      say(rand(["let me have a look", "what's that?", "ooh. new place."]));
    });
    box.append(b);
  }
  drawHomes();
  syncHomes();
}
function syncHomes() {
  $$(".home").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.home === homeNow())));
}
function drawHomes() {
  homeCanvases.forEach(([c, id]) => drawHabitat(c, id, { rows: 64, floor: 0.66, t: 3, night: isDark(), furniture: ROOM_FURNITURE }));
}

// ---------------------------------------------------------------- features

function setupFeatureIsland() {
  const root = $("#feature-island");
  const slime = new SlimeActor($(".i-slime", root), { look: () => state.look, crop: 2 });
  lookActors.add(slime);
  const emote = new Reel($(".i-emote", root));
  const chips = $$("#moods .chip");
  const base = { happyA: "idle", hunger: "sad", fun: "sulk", sleepy: "sleep", clean: "melt", love: "blush" };
  let auto = !calm();
  let i = 0;
  const pick = (chip) => {
    chips.forEach((c) => c.setAttribute("aria-pressed", String(c === chip)));
    emote.set(chip.dataset.reel);
    slime.setBase(base[chip.dataset.reel] ?? "idle");
  };
  chips.forEach((c) => c.addEventListener("click", () => { auto = false; pick(c); }));
  setInterval(() => {
    if (!auto || document.hidden || !slime.visible) return;
    i = (i + 1) % chips.length;
    pick(chips[i]);
  }, 4000);
}

/// Lock Screen skies behind the island tile and the "Beyond the island" tile.
const skies = [];
function setupSkies() {
  for (const host of [$("#lockphone"), $("#beyond")]) {
    const canvas = $("canvas.sky", host);
    skies.push([host, canvas]);
    new ResizeObserver(() => drawSky(host, canvas)).observe(host);
  }
  drawSkies();
}
function drawSky(host, canvas) {
  const px = innerWidth < 640 ? 3 : 4;
  const cols = Math.ceil(host.clientWidth / px) + 1;
  const rows = Math.ceil(host.clientHeight / px) + 1;
  if (!cols || !rows) return;
  drawLockSky(canvas, { cols, rows, night: isDark() });
  canvas.style.width = cols * px + "px";
  canvas.style.height = rows * px + "px";
}
function drawSkies() { skies.forEach(([h, c]) => drawSky(h, c)); }

/// Strips of lawn under small slimes.
const grassCanvases = [];
function hash(n) {
  let x = (n | 0) ^ 0x9e3779b9;
  x = Math.imul(x ^ (x >>> 16), 0x85ebca6b);
  x = Math.imul(x ^ (x >>> 13), 0xc2b2ae35);
  return (x ^ (x >>> 16)) >>> 0;
}
function drawGrassOn(canvas) {
  const w = canvas.parentElement.clientWidth;
  if (!w) return;
  const rows = Number(canvas.getAttribute("height")) || 4;
  const px = 4;
  const cols = Math.ceil(w / px);
  canvas.width = cols;
  canvas.style.width = cols * px + "px";
  canvas.style.height = rows * px + "px";
  const ctx = canvas.getContext("2d");
  const night = isDark();
  const P = night
    ? { grass: "#2C4C35", light: "#365A40", flowers: ["#A8587A", "#BFA055", "#B8BCD4"] }
    : { grass: "#79C05A", light: "#95D370", flowers: ["#F07A9A", "#FFD166", "#FFFFFF"] };
  ctx.clearRect(0, 0, cols, rows);
  for (let x = 0; x < cols; x++) {
    const h = hash(x * 17 + 5);
    ctx.fillStyle = P.grass;
    ctx.fillRect(x, 1, 1, rows - 1);
    if (h % 3 === 0) ctx.fillRect(x, 0, 1, 1);
    if (h % 4 === 0) {
      ctx.fillStyle = P.light;
      ctx.fillRect(x, 2 + ((h >>> 5) % Math.max(1, rows - 2)), 1, 1);
    }
  }
  for (let x = 3; x < cols - 3; x += 9 + (hash(x) % 13)) {
    ctx.fillStyle = P.flowers[hash(x * 3) % 3];
    ctx.fillRect(x, 1, 1, 1);
  }
}
function setupGrass() {
  for (const c of $$("canvas[data-grass]")) {
    grassCanvases.push(c);
    new ResizeObserver(() => drawGrassOn(c)).observe(c.parentElement);
  }
}
function drawGrass() { grassCanvases.forEach(drawGrassOn); }

function setupGrow() {
  $$("#grow .grow-slot").forEach((b) => {
    const stage = b.dataset.stage;
    const actor = new SlimeActor($("canvas", b), { look: () => ({ ...state.look, stage, hat: null, glasses: null, scarf: null }) });
    lookActors.add(actor);
    b.setAttribute("aria-label", `${stage === "baby" ? "Baby slime" : stage === "young" ? "Slime, from level 4" : "Big slime, from level 12"}. Press to wave.`);
    b.addEventListener("click", () => actor.play(rand(["wave", "celebrate", "love", "zap", "curious"]), 2));
  });
}

function buildGames() {
  const ul = $("#games");
  for (const [icon, title, isNew] of GAMES) {
    const li = el("li", {}, itemCanvas(icon, 2), title);
    if (isNew) li.append(el("span", { class: "tag tag-live", text: "New" }));
    ul.append(li);
  }
  ul.append(el("li", { class: "today" }, itemCanvas("starJelly", 2), "Today's game", el("em", { text: "A different one every day" })));
}

function setupReacts() {
  const box = $("#reacts");
  for (const ev of EVENTS) {
    const s = el("canvas", { class: "i-slime", width: 24, height: 18 });
    const e = el("canvas", { class: "i-emote", width: 9, height: 9 });
    const b = el("button", { type: "button", class: "react" }, el("span", { class: "mini", "aria-hidden": "true" }, s, e), el("span", { text: ev.label }));
    const actor = new SlimeActor(s, { look: () => state.look, crop: 2, base: ev.base });
    lookActors.add(actor);
    new Reel(e, ev.reel);
    b.addEventListener("click", () => {
      $$(".react", box).forEach((x) => x.classList.toggle("is-on", x === b));
      actor.play(ev.anim, 2);
      flashReel(ev.reel, 7000);
      playAll(ev.anim, 2);
      if (ev.id === "zap") bump("energy", 6);
      if (ev.id === "wifi") bump("fun", 4);
      say(rand(ev.lines), { alert: true, ms: 3600 });
      clearTimeout(b.__t);
      b.__t = setTimeout(() => b.classList.remove("is-on"), 3600);
    });
    box.append(b);
  }
}

function setupTraits() {
  const actor = new SlimeActor($("#trait-slime"), { look: () => state.look, base: "curious" });
  lookActors.add(actor);
  const line = $("#trait-say");
  const chips = $$("#traits .chip");
  chips.forEach((chip) => chip.addEventListener("click", () => {
    chips.forEach((c) => c.setAttribute("aria-pressed", String(c === chip)));
    const t = TRAITS[chip.dataset.trait];
    line.textContent = t.line;
    actor.setBase(t.anim);
  }));
}

function setupGifts() {
  const box = $("#gifts");
  const coins = [10, 15, 20, 25, 30, 40, 60];
  let today = 4;
  let streak = 11;
  let opened = false;
  const cells = coins.map((n, i) => {
    const day = i + 1;
    const big = day === 7;
    const cell = el("div", { class: "gift" + (big ? " is-big" : ""), role: "img" }, el("span", { text: `Day ${day}` }), itemCanvas(big ? "chest" : "coin", 2), el("b", { text: String(n) }));
    box.append(cell);
    return cell;
  });
  const render = () => {
    cells.forEach((c, i) => {
      const day = i + 1;
      const isOpen = day < today || (day === today && opened);
      c.classList.toggle("is-open", isOpen);
      c.classList.toggle("is-today", day === today && !opened);
      const what = day === 7 ? "a piece of furniture and 60 coins" : `${coins[i]} coins`;
      c.setAttribute("aria-label", `Day ${day}: ${what}${isOpen ? ", opened" : day === today ? ", today" : ""}`);
    });
    const s = $("#streak");
    s.firstChild.textContent = `${streak} days in a row`;
    $("small", s).textContent = opened ? `${streak} days in a row. we're a team.` : "streak buddies";
  };
  const btn = $("#open-gift");
  btn.addEventListener("click", () => {
    if (opened) return;
    opened = true;
    streak++;
    addCoins(coins[today - 1], { pop: true });
    btn.disabled = true;
    btn.textContent = "See you tomorrow";
    render();
    flashReel("happyB", 4000);
    playAll("celebrate", 2);
    say("a gift! for me? for us.", { alert: true });
    if (!calm()) cells[today - 1].animate([{ transform: "scale(1)" }, { transform: "scale(1.12)" }, { transform: "scale(1)" }], { duration: 420, easing: "ease-out" });
  });
  render();
}

let seasonCanvas, seasonPick;
function setupSeasons() {
  seasonCanvas = $("#season-scene");
  const chips = $$("#seasons .chip");
  const now = seasonNow();
  seasonPick = chips.some((c) => c.dataset.season === now) ? now : "spring";
  const owner = {};
  watch(seasonCanvas, owner);
  const labels = { halloween: "October", christmas: "December", newYear: "New Year", spring: "spring" };
  const sync = () => {
    chips.forEach((c) => c.setAttribute("aria-pressed", String(c.dataset.season === seasonPick)));
    seasonCanvas.setAttribute("aria-label", `Your slime's room decorated for ${labels[seasonPick]}`);
    drawSeason();
  };
  chips.forEach((c) => c.addEventListener("click", () => { seasonPick = c.dataset.season; sync(); }));
  onTick(() => owner.visible && !calm() && drawSeason(), 2);
  sync();
}
function drawSeason() {
  if (!seasonCanvas || !sprites()) return;
  drawHabitat(seasonCanvas, "room", { rows: 64, floor: 0.66, t: calm() ? 1 : clockT, night: isDark(), season: seasonPick, furniture: ROOM_FURNITURE });
  const ctx = seasonCanvas.getContext("2d");
  drawSlime(ctx, state.look, "idle", 0, 40, Math.floor(64 * 0.66) + 10 - 20);
}

// The photo booth: a home, a pose and a line, saved as a picture.
const BOOTH_PLACES = ["garden", "room", "beach", "space", "crystal"];
const BOOTH_LINES = {
  wave: ["hi from the garden", "hi from my room", "hi from the beach", "hi from space", "hi from the cave"],
  idle: ["just chilling", "this is my good side", "all good here"],
  love: ["love you", "you're the best"],
  celebrate: ["party time", "woo"],
  blush: ["don't look", "oh. a camera."],
  sleep: ["zzz", "*snores happily*"],
};
const booth = { place: 0, pose: "wave", frame: 0 };
function setupBooth() {
  const canvas = $("#photo-canvas");
  const owner = {};
  watch(canvas, owner);
  const chips = $$("#poses .chip");
  const setLine = () => {
    const list = BOOTH_LINES[booth.pose];
    $("#photo-say").textContent = booth.pose === "wave" ? list[booth.place] : rand(list);
    const poseTitle = chips.find((c) => c.dataset.pose === booth.pose)?.textContent.toLowerCase() ?? "posing";
    const placeTitle = HABITATS.find((h) => h.id === BOOTH_PLACES[booth.place]).title.toLowerCase();
    canvas.setAttribute("aria-label", `Photo of ${state.name} in a ${poseTitle} pose, in the ${placeTitle}`);
  };
  chips.forEach((c) => c.addEventListener("click", () => {
    booth.pose = c.dataset.pose;
    booth.frame = 0;
    chips.forEach((x) => x.setAttribute("aria-pressed", String(x === c)));
    setLine();
    drawBooth();
  }));
  $("#photo-place").addEventListener("click", () => {
    booth.place = (booth.place + 1) % BOOTH_PLACES.length;
    setLine();
    drawBooth();
  });
  $("#photo-save").addEventListener("click", saveBooth);
  onTick(() => {
    if (!owner.visible) return;
    if (!calm()) booth.frame++;
    drawBooth();
  }, 4);
  setLine();
  drawBooth();
}

/// Draws the photo: the home on a 96 × 64 grid, the slime twice as big.
function paintBooth(ctx, k) {
  const bg = document.createElement("canvas");
  drawHabitat(bg, BOOTH_PLACES[booth.place], { rows: 64, floor: 0.7, t: 2, night: isDark(), furniture: ROOM_FURNITURE });
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(bg, 0, 0, 96 * k, 64 * k);
  const s = document.createElement("canvas");
  s.width = 24;
  s.height = 20;
  drawSlime(s.getContext("2d"), state.look, booth.pose, booth.frame);
  const scale = 2 * k;
  ctx.drawImage(s, (48 - 24) * k, (60 - 20 * 2) * k, 24 * scale, 20 * scale);
}
function drawBooth() {
  const canvas = $("#photo-canvas");
  if (!canvas || !sprites()) return;
  canvas.width = 192;
  canvas.height = 128;
  paintBooth(canvas.getContext("2d"), 2);
}

async function saveBooth() {
  const k = 8;
  const W = 96 * k, H = 64 * k, band = 104, pad = 24;
  const out = document.createElement("canvas");
  out.width = W + pad * 2;
  out.height = H + pad + band;
  const ctx = out.getContext("2d");
  ctx.fillStyle = "#FFFFFF";
  ctx.fillRect(0, 0, out.width, out.height);
  const pic = document.createElement("canvas");
  pic.width = W;
  pic.height = H;
  paintBooth(pic.getContext("2d"), k);
  ctx.drawImage(pic, pad, pad);
  try { await document.fonts.load('700 34px "Pixelify Sans"'); } catch {}
  // The speech bubble.
  const text = $("#photo-say").textContent;
  ctx.font = '700 34px "Pixelify Sans", monospace';
  const tw = ctx.measureText(text).width;
  const bx = pad + W / 2 - tw / 2 - 22, by = pad + 40;
  ctx.fillStyle = "#FFFFFF";
  ctx.beginPath();
  ctx.roundRect(bx, by, tw + 44, 62, 18);
  ctx.moveTo(pad + W / 2 - 12, by + 62);
  ctx.lineTo(pad + W / 2, by + 76);
  ctx.lineTo(pad + W / 2 + 12, by + 62);
  ctx.fill();
  ctx.fillStyle = "#000000";
  ctx.textBaseline = "middle";
  ctx.fillText(text, bx + 22, by + 32);
  // The caption.
  ctx.fillStyle = "rgba(60, 60, 67, .86)";
  ctx.font = '600 30px ui-rounded, "SF Pro Rounded", -apple-system, "Segoe UI", system-ui, sans-serif';
  ctx.fillText(`${state.name} · Level ${state.level}`, pad + 6, H + pad + band / 2);
  ctx.fillStyle = "#000000";
  ctx.font = '700 40px "Pixelify Sans", monospace';
  const mark = "slimy";
  ctx.fillText(mark, pad + W - ctx.measureText(mark).width - 6, H + pad + band / 2);
  out.toBlob((blob) => {
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const a = el("a", { href: url, download: `${state.name.toLowerCase()}-slimy.png` });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
    const btn = $("#photo-save");
    btn.textContent = "Saved";
    setTimeout(() => (btn.textContent = "Save picture"), 1800);
  }, "image/png");
}

function setupGet() {
  const c = $("#get-slime");
  const actor = new SlimeActor(c, { look: () => state.look });
  lookActors.add(actor);
  let waved = false;
  new IntersectionObserver(([e]) => {
    if (e.isIntersecting && !waved) { waved = true; actor.play("wave", 3); }
    if (!e.isIntersecting) waved = false;
  }, { threshold: 0.6 }).observe(c);
}

function setupTimer() {
  // The timer trick: one frame per second, the last digit is the slime.
  const c = $(".timer-slime");
  const ctx = c.getContext("2d");
  const digits = $(".timer-digits");
  const real = $("#timer-real");
  const owner = {};
  watch(c, owner);
  let sec = 0;
  const draw = () => {
    ctx.clearRect(0, 0, 24, 20);
    drawSlime(ctx, state.look, "idle", sec);
    digits.textContent = `0:${Math.floor(sec / 10)}`;
    real.textContent = `0:${String(sec).padStart(2, "0")}`;
  };
  draw();
  setInterval(() => {
    if (document.hidden || !owner.visible || calm()) return;
    sec = (sec + 1) % 60;
    draw();
  }, 1000);
}

// ---------------------------------------------------------------- friends

function setupFriends() {
  const friendLook = { stage: "young", skin: "bubblegum", hat: "hat.flower", glasses: null, scarf: null };
  const me = new SlimeActor($("#meet-me"), { look: () => state.look });
  lookActors.add(me);
  const friend = new SlimeActor($("#meet-friend"), { look: friendLook, mirror: true });
  new Scene($("#meet-ground"), () => ({ habitat: "garden", rows: 52, floor: 0.7, season: "autumn" }));
  const bar = $("#love-bar");
  for (let i = 0; i < 10; i++) bar.append(el("i"));
  const kiss = $('[data-friend="kiss"]');
  const pct = $("#love-pct");
  const note = $("#love-note");
  const bubble = $("#meet-bubble");
  const fx = $("#meet-fx");
  let bt;
  const speak = (text) => {
    bubble.textContent = text;
    bubble.classList.add("is-on");
    clearTimeout(bt);
    bt = setTimeout(() => bubble.classList.remove("is-on"), 3000);
  };
  const render = () => {
    $$("i", bar).forEach((s, i) => s.classList.toggle("on", i < Math.round(state.love / 10)));
    pct.textContent = state.love + "%";
    bar.setAttribute("aria-valuenow", String(state.love));
    kiss.disabled = state.love < 50;
    note.textContent = state.love >= 100 ? "Blob and Mochi are in love. For good."
      : state.love >= 50 ? "Kiss unlocked. At 100% they fall in love."
      : "Kiss unlocks at 50%. At 100% they fall in love.";
  };
  const heartsAt = (n) => {
    if (calm()) return;
    const w = fx.clientWidth, h = fx.clientHeight;
    const u = w / 96;
    for (let i = 0; i < n; i++) {
      const c = itemCanvas("heart", 1);
      c.style.width = 8 * u * 1.4 + "px";
      c.style.height = 6 * u * 1.4 + "px";
      fx.append(c);
      const x = w / 2 - 4 * u * 1.4 + (Math.random() * 20 - 10) * u, y = h * 0.42;
      c.animate([
        { transform: `translate(${x}px, ${y}px) scale(.5)`, opacity: 0 },
        { transform: `translate(${x}px, ${y - 6 * u}px) scale(1)`, opacity: 1, offset: 0.2 },
        { transform: `translate(${x + (Math.random() * 10 - 5) * u}px, ${y - 30 * u}px)`, opacity: 0 },
      ], { duration: 1600, delay: i * 150, easing: "ease-out", fill: "backwards" }).finished.then(() => c.remove());
    }
  };
  const raise = (d) => {
    const before = state.love;
    state.love = clamp(state.love + d, 0, 100);
    render();
    if (state.love >= 100 && before < 100) {
      me.setBase("love");
      friend.setBase("love");
      heartsAt(8);
      speak("i think i'm in love. with mochi.");
      flashReel("love", 8000);
      if (!heroVisible) navIsland?.talk("i think i'm in love. with mochi.", 3600);
    }
  };
  let busy = false;
  $$(".meet-keys .btn").forEach((b) => b.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    const kind = b.dataset.friend;
    try {
      if (kind === "hug") {
        speak("hug with mochi. soft.");
        if (!heroVisible) navIsland?.talk("hug with mochi. soft.");
        flashReel("love", 4000);
        raise(10);
        setTimeout(() => heartsAt(3), calm() ? 0 : 1600);
        await Promise.all([me.play("meet", 1), friend.play("meet", 1)]);
      } else if (kind === "food") {
        raise(5);
        speak("a gift for mochi is on its way");
        if (!calm()) {
          const w = fx.clientWidth, h = fx.clientHeight, u = w / 96;
          const c = itemCanvas("cookie", 1);
          c.style.width = c.style.height = 10 * u * 1.3 + "px";
          fx.append(c);
          const y = h * 0.5;
          await c.animate([
            { transform: `translate(${w * 0.36}px, ${y}px)` },
            { transform: `translate(${w * 0.5}px, ${y - 18 * u}px)`, offset: 0.5 },
            { transform: `translate(${w * 0.6}px, ${y}px)`, opacity: 1, offset: 0.9 },
            { transform: `translate(${w * 0.6}px, ${y}px) scale(.3)`, opacity: 0 },
          ], { duration: 900, easing: "ease-in-out" }).finished;
          c.remove();
        }
        await friend.play("eat", 2);
        speak("mail from blob. yum.");
        friend.play("love", 1);
      } else if (kind === "kiss") {
        speak(rand(["love you", "that's nice"]));
        raise(15);
        heartsAt(5);
        await Promise.all([me.play("blush", 2), friend.play("blush", 2)]);
      }
    } finally {
      busy = false;
    }
  }));
  render();
}

// ------------------------------------------------------------ page chrome

function buildNavIsland() {
  const slot = $("#bar-slot");
  if (!slot) return null;
  const root = el("div", { class: "island nav-island", id: "nav-island", "data-state": "compact" });
  root.innerHTML = `
    <button class="island-toggle" type="button" aria-expanded="false" aria-controls="nav-island-panel" tabindex="-1"><span class="sr-only">Open the Dynamic Island</span></button>
    <span class="island-compact" aria-hidden="true">
      <canvas class="i-slime" width="24" height="18"></canvas>
      <span class="i-status"><b class="i-label"></b><span class="i-meter"><i></i></span></span>
      <span class="i-say"></span>
      <canvas class="i-emote" width="9" height="9"></canvas>
    </span>
    <div class="island-panel" id="nav-island-panel" hidden>
      <div class="ip-top">
        <canvas class="ip-slime" width="24" height="18" aria-hidden="true"></canvas>
        <div class="ip-id"><b class="ip-name">Blob</b><span class="ip-sub">Young slime</span></div>
        <div class="ip-stats">
          <span class="ip-level">Level <span data-level>${state.level}</span></span>
          <span class="ip-coins" role="img" aria-label="${state.coins} coins" data-coins-label><canvas data-item="coin" data-scale="2"></canvas><span data-coins>${state.coins}</span></span>
        </div>
      </div>
      <p class="ip-line" data-line></p>
      <ul class="ip-needs" aria-label="Needs"></ul>
      <div class="ip-actions"></div>
    </div>`;
  slot.append(root);
  drawAllItems(root);
  return new IslandView(root);
}

function setupChrome() {
  const slot = $("#bar-slot");
  const shift = () => {
    if (!navIsland || !slot) return;
    const r = slot.getBoundingClientRect();
    navIsland.root.style.setProperty("--shift", `${Math.round(innerWidth / 2 - (r.left + r.width / 2))}px`);
  };
  addEventListener("resize", shift, { passive: true });
  shift();
  const io = new IntersectionObserver(([e]) => {
    heroVisible = e.isIntersecting;
    const show = !heroVisible && e.boundingClientRect.top < 0;
    if (!navIsland) return;
    navIsland.root.classList.toggle("is-shown", show);
    navIsland.toggle.tabIndex = show ? 0 : -1;
    if (!show) {
      navIsland.close();
      navIsland.root.dataset.state = "compact";
    } else {
      heroIsland.close();
      shift();
    }
  }, { threshold: 0.1 });
  io.observe($("#island"));
}

function setupIslands() {
  heroIsland = new IslandView($("#island"));
  navIsland = buildNavIsland();
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    for (const i of islands()) {
      if (!i.isOpen) continue;
      const inside = i.root.contains(document.activeElement);
      i.close({ focus: inside });
    }
  });
  document.addEventListener("pointerdown", (e) => {
    for (const i of islands()) if (i.isOpen && !i.root.contains(e.target)) i.close();
  });

  // Show once what's inside, unless the visitor gets there first.
  if (calm()) return;
  const root = $("#island");
  let touched = false;
  root.addEventListener("pointerdown", () => { touched = true; }, { once: true });
  heroIsland.toggle.addEventListener("focus", () => { touched = true; }, { once: true });
  const io = new IntersectionObserver(([e]) => {
    if (!e.isIntersecting) return;
    io.disconnect();
    setTimeout(() => {
      if (touched || heroIsland.isOpen) return;
      heroIsland.open({ auto: 3600 });
    }, 1600);
  }, { threshold: 0.9 });
  io.observe(root);
}

// ------------------------------------------------------------------- boot

let lastTouch = Date.now();

async function boot() {
  await loadSprites();
  buildNeeds();
  buildTray();
  buildSwatches();
  buildWear();
  buildHomes();
  buildGames();
  buildSkinRow();
  drawAllItems();

  hero = new SlimeActor($("#slime-canvas"), { look: () => state.look });
  setupIslands();
  new Scene($("#scene"), () => ({ habitat: homeNow(), rows: 170, floor: 0.6, furniture: ROOM_FURNITURE }));

  $$(".key").forEach((k) => k.addEventListener("click", () => ACTIONS[k.dataset.act]()));
  setupSlime();
  setupFloor();
  setupTabs();
  setupChrome();
  setupFeatureIsland();
  setupSkies();
  setupGrass();
  setupGrow();
  setupReacts();
  setupTraits();
  setupGifts();
  setupSeasons();
  setupBooth();
  setupGet();
  setupTimer();
  setupFriends();
  onTheme(() => {
    redrawWorld();
    say(isDark() ? "it's dark outside" : "the sun is nice today", { announce: false });
  });

  syncTop();
  syncStatus();
  renderNeeds();
  $("#xp").style.width = Math.round(state.xp * 100) + "%";
  $("#bubble").classList.add("is-on");
  bubbleTimer = setTimeout(() => $("#bubble").classList.remove("is-on"), 6000);
  addEventListener("resize", () => {
    drawSkies();
  }, { passive: true });

  // Needs drift down slowly to the resting floor. Sleep restores energy.
  setInterval(() => {
    if (document.hidden) return;
    const n = state.needs;
    n.hunger = Math.max(FLOOR, n.hunger - 1);
    n.fun = Math.max(FLOOR, n.fun - 1);
    n.clean = Math.max(FLOOR, n.clean - 0.6);
    n.love = Math.max(FLOOR, n.love - 0.8);
    n.energy = state.asleep ? Math.min(100, n.energy + 6) : Math.max(FLOOR, n.energy - 0.5);
    renderNeeds();
  }, 4000);

  // Small talk and idle fidgets while you watch, like HomeView.
  ["pointerdown", "keydown"].forEach((t) => addEventListener(t, () => (lastTouch = Date.now()), { passive: true }));
  setInterval(() => {
    if (document.hidden || !heroVisible || !hero.visible || state.asleep || Date.now() - lastTouch < 9000) return;
    if (state.sx && Math.random() < 0.5) return hopTo(0);
    const w = wish();
    if (Math.random() < 0.5) playAll(rand(["curious", "blush", "stretch", "wave"]), 1);
    if (Math.random() < 0.6) say(w ? rand(WISH_LINES[w.id]) : rand(CHATTER), { announce: false });
  }, 11000);
}

boot().catch((err) => console.error(err));
