// The home page is a toy. One slime is shared by every part of it: the phone
// in the hero and its Dynamic Island, the island that follows you in the
// header, the feelings tile, the crates, the two phones caring for it
// together, the friends scene and the photo booth.
import {
  loadSprites, sprites, SKINS, SKIN_TITLES, SKIN_LEVELS, calm,
  drawSlime, itemCanvas, drawAllItems, SlimeActor, Reel, onTick, watch, clockT,
  savedLook, saveLook, itemSize, drawItemAt, paint,
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
  { id: "battery", label: "Battery low", reel: "battery", base: "melt", anim: "melt", feel: ["sadness", 0.25], lines: ["battery 20%. i'm melting", "getting all runny", "plug me in. please."] },
  { id: "zap", label: "Charging", reel: "zap", base: "zap", anim: "zap", feel: ["excitement", 0.3], lines: ["power. yum.", "tingly", "*slurps electricity*"] },
  { id: "wifi", label: "Wi-Fi is back", reel: "wifi", base: "idle", anim: "celebrate", feel: ["joy", 0.18], lines: ["wi-fi. zoom.", "hi router", "connected"] },
  { id: "confused", label: "No internet", reel: "confused", base: "confused", anim: "confused", feel: ["curiosity", 0.2], lines: ["no internet", "offline. so quiet.", "where did everyone go"] },
  { id: "morning", label: "Good morning", reel: "morning", base: "stretch", anim: "wave", feel: ["joy", 0.2], lines: ["good morning", "rise and shine", "new day. new goo."] },
  { id: "night", label: "Good night", reel: "night", base: "sleep", anim: "sleep", feel: ["sleepiness", 0.35], lines: ["sleep well", "good night", "time for slime dreams"] },
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
// The eight colours this site shows, in the app's order. Listed by hand so
// that no other colour from the sprites ever appears in a picker here.
const PUBLIC_SKINS = ["lime", "bubblegum", "ocean", "grape", "lava", "gold", "ghost", "void"];
const publicSkins = () => PUBLIC_SKINS.filter((id) => Object.hasOwn(SKINS, id));

// Shared/Model: Emotion. Nine feelings from 0 to 1. They rise with what
// happens and settle back toward a resting mood, and the strongest one
// decides how the slime moves and what it talks about.
// `words` are the app's mood words (SlimeMoodWord in LiveSlimeView.swift):
// mild, then strong from FEEL_STRONG on; below FEEL_FAINT it's "calm".
const EMOTIONS = [
  { id: "joy", title: "Joy", words: ["content", "cheerful"], anim: "idle" },
  { id: "sadness", title: "Sadness", words: ["glum", "sad"], anim: "sad" },
  { id: "anger", title: "Anger", words: ["grumpy", "cross"], anim: "sulk" },
  { id: "fear", title: "Fear", words: ["nervous", "scared"], anim: "hide" },
  { id: "love", title: "Love", words: ["fond", "cuddly"], anim: "blush" },
  { id: "curiosity", title: "Curiosity", words: ["curious", "fascinated"], anim: "curious" },
  { id: "boredom", title: "Boredom", words: ["a bit bored", "bored"], anim: "melt" },
  { id: "excitement", title: "Excitement", words: ["eager", "excited"], anim: "play" },
  { id: "sleepiness", title: "Sleepiness", words: ["drowsy", "sleepy"], anim: "sleep" },
];
const FEEL_FAINT = 0.15;
const FEEL_STRONG = 0.55;
// Feeling one thing a lot quiets its opposites a little (Emotion.opposites in
// Shared/Model/Emotions.swift; keep the two in step).
const OPPOSITE = {
  joy: ["sadness", "anger", "boredom"], sadness: ["joy", "excitement"], anger: ["love", "joy"], fear: ["joy", "curiosity"],
  love: ["anger", "sadness", "fear"], curiosity: ["boredom"], boredom: ["excitement", "curiosity"],
  excitement: ["boredom", "sleepiness", "sadness"], sleepiness: ["excitement"],
};
// What it says on its own, by feeling. Short, lowercase, never a guilt trip.
const FEEL_LINES = {
  joy: ["good day for wobbling", "everything's nice right now", "i feel bouncy", "hehe. just happy."],
  sadness: ["a little blue. it'll pass.", "feeling soft and quiet", "a pet would be nice right now"],
  anger: ["hmph.", "grumpy. give me a minute.", "okay. that's enough boo."],
  fear: ["eek.", "was that a ghost?", "hiding. don't look."],
  love: ["you're my favourite", "i like it when you're here", "warm and wobbly"],
  curiosity: ["what's that?", "i studied a speck of dust. fascinating.", "how does the sky stay up?"],
  boredom: ["counting my wobbles. 312.", "play something?", "quiet day. i'll make up a song."],
  excitement: ["again! again!", "zoom zoom", "so much bounce right now"],
  sleepiness: ["eyes. so. heavy.", "*yawns*", "five more minutes"],
};
// What it says about something that just happened.
const EVENT_LINES = {
  fed: ["that snack was good", "still tasting it. yum.", "tummy: happy"],
  played: ["that was fun. again later?", "i'm still bouncing", "good game"],
  bath: ["i smell like bubbles", "so shiny right now"],
  crate: ["what's in the next crate?", "i like crates. they creak.", "shiny things. more please."],
  friend: ["mochi says hi", "mochi's garden smells nice", "friends are good"],
  partner: ["two people, one me. lucky.", "ana and ben take turns. i like turns.", "someone looked after me from the other phone"],
};
const PLACE_LINES = {
  room: ["my room is the best room", "the lamp is cozy"],
  garden: ["the grass tickles", "a bug waved at me"],
  beach: ["sand in my goo", "the waves sound like slurping"],
  space: ["zero gravity. zero problems.", "hi, stars"],
  crystal: ["everything sparkles in here", "echo. echo."],
};
const SEASON_LINES = {
  halloween: ["spooky season. boo.", "pumpkins everywhere. i approve."],
  christmas: ["is it snowing yet?", "the little tree is blinking"],
  newYear: ["new year. new goo.", "fireworks. so loud. so pretty."],
  spring: ["flowers are back", "spring. everything smells green."],
  summer: ["warm out. good jelly weather.", "summer wobble"],
  autumn: ["leaves everywhere. crunchy.", "cozy sweater weather. i don't have a sweater."],
  winter: ["cold out. good thing i'm jelly.", "snuggle weather"],
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
  feel: { joy: 0.32, sadness: 0, anger: 0, fear: 0, love: 0.12, curiosity: 0.42, boredom: 0, excitement: 0.08, sleepiness: 0.05 },
  event: null, // { kind, at }: the last thing that happened, for small talk
  sx: 0,
  line: "just wobbling along",
};
if (!Object.hasOwn(SKINS, state.look.skin) || !PUBLIC_SKINS.includes(state.look.skin)) state.look.skin = "lime";
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
let feelActor;
let heroVisible = true;
const screen = () => $("#screen");
const g = () => screen().clientWidth / 96;
// The big slime on the 96-wide scene grid: its top row and pixel size (home.css: .slime).
const SL = { top: 70, scale: 2.5 };
const islands = () => [heroIsland, navIsland].filter(Boolean);

function playAll(anim, loops = 2) {
  islands().forEach((i) => i.play(anim, loops));
  feelActor?.play(anim, loops);
  return hero.play(anim, loops);
}
function setBaseAll(anim) {
  hero.setBase(anim);
  islands().forEach((i) => i.setBase(anim));
  syncFeelBase();
}

// The last twelve things it said, so it never repeats itself too soon.
const recentLines = [];
function remember(text) {
  const i = recentLines.indexOf(text);
  if (i >= 0) recentLines.splice(i, 1);
  recentLines.push(text);
  if (recentLines.length > 12) recentLines.shift();
}

/// The bubble in the feelings tile shows whatever the slime said last.
let feelSayTimer;
function setFeelLine(text) {
  const b = $("#feel-say");
  if (!b || b.textContent === text) return;
  b.textContent = text;
  if (calm()) return;
  b.classList.remove("is-new");
  clearTimeout(feelSayTimer);
  void b.offsetWidth;
  b.classList.add("is-new");
  feelSayTimer = setTimeout(() => b.classList.remove("is-new"), 400);
}

/// Speech: a bubble over the slime, or the header island when the phone is out of view.
let bubbleTimer;
function say(text, { alert = false, ms = 3400, announce = true } = {}) {
  state.line = text;
  remember(text);
  setFeelLine(text);
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
    feel("excitement", 0.35);
    feel("joy", 0.15);
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
  const bed = $('[data-feel="sleep"]');
  if (bed) bed.textContent = "Bedtime";
  feel("sleepiness", -0.4);
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
  feel("curiosity", 0.08);
  feel("joy", 0.04);
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
  feel("love", 0.2);
  feel("joy", 0.1);
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
  feel("joy", id === state.fav ? 0.3 : 0.15);
  if (id === state.fav) feel("love", 0.15);
  noteEvent("fed");
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
  feel("excitement", 0.32);
  feel("joy", 0.1);
  noteEvent("played");
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
  feel("joy", 0.14);
  noteEvent("bath");
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
  const bed = $('[data-feel="sleep"]');
  if (bed) bed.textContent = "Wake up";
  feel("sleepiness", 0.45);
  setBaseAll("sleep");
  say(rand(["good night", "sleep well. me too.", "lights out"]));
  syncStatus();
  renderNeeds();
}

/// "Say boo" in the feelings tile: a little scare, and grumpy if you keep at it.
function boo() {
  if (state.asleep) return wakeUp(rand(["eek. a dream?", "who said boo"]));
  const already = state.feel.fear > 0.5;
  feel("fear", 0.45);
  if (already) feel("anger", 0.3);
  flashReel("confused", 3000);
  playAll("hide", 2).then(() => {
    if (!state.asleep && state.feel.fear > 0.4) say(rand(already ? FEEL_LINES.anger : FEEL_LINES.fear));
  });
  squish();
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
  for (const id of publicSkins()) {
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
  for (const id of publicSkins()) {
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
      feel(...ev.feel);
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

// --------------------------------------------------------------- feelings

function feel(id, amount) {
  const f = state.feel;
  if (!Object.hasOwn(f, id)) return;
  f[id] = clamp(f[id] + amount, 0, 1);
  if (amount > 0) for (const o of OPPOSITE[id]) f[o] = clamp(f[o] - amount * 0.3, 0, 1);
  renderFeel();
}

/// The strongest feeling and how strong it is (Emotions.dominant, .intensity).
function dominant() {
  let best = EMOTIONS[0];
  for (const e of EMOTIONS) if (state.feel[e.id] > state.feel[best.id]) best = e;
  return { e: best, v: state.feel[best.id] };
}

function noteEvent(kind) {
  state.event = { kind, at: Date.now() };
}

/// Where each feeling settles when nothing happens: a curious, mostly content
/// slime, sleepier late at night and in bed, a little blue while it needs
/// something, and bored after a while without you.
function restingFeel(id) {
  const h = new Date().getHours();
  const night = h >= 22 || h < 6;
  switch (id) {
    case "joy": return wish() ? 0.18 : 0.3;
    case "curiosity": return 0.24;
    case "love": return 0.1;
    case "sleepiness": return state.asleep ? 0.75 : night ? 0.3 : 0.05;
    case "sadness": return wish() ? 0.2 : 0;
    case "boredom": return Date.now() - lastTouch > 40000 ? 0.45 : 0;
    default: return 0;
  }
}

function settleFeel() {
  const f = state.feel;
  for (const e of EMOTIONS) {
    const rate = e.id === "boredom" ? 0.02 : 0.04;
    f[e.id] = clamp(f[e.id] + (restingFeel(e.id) - f[e.id]) * rate, 0, 1);
  }
  renderFeel();
}

let feelRows = null;
let feelWord = null;
let feelBase = "";

/// The feelings tile's slime moves the way it feels, like LiveSlimeView.
function syncFeelBase() {
  if (!feelActor) return;
  const d = dominant();
  const base = state.asleep ? "sleep" : d.v >= 0.3 ? d.e.anim : "idle";
  if (base === feelBase) return;
  feelBase = base;
  feelActor.setBase(base);
}

function renderFeel() {
  if (!feelRows) return;
  const d = dominant();
  for (const e of EMOTIONS) {
    const v = Math.round(state.feel[e.id] * 100);
    const row = feelRows[e.id];
    row.li.classList.toggle("is-top", e === d.e && d.v >= FEEL_FAINT);
    if (row.v === v) continue;
    row.v = v;
    row.bar.style.width = v + "%";
    row.li.setAttribute("aria-valuenow", String(v));
    row.li.setAttribute("aria-valuetext", `${v} percent`);
  }
  const word = state.asleep ? "Asleep" : d.v < FEEL_FAINT ? "calm" : d.e.words[d.v >= FEEL_STRONG ? 1 : 0];
  if (word !== feelWord) {
    feelWord = word;
    feelRows.now.replaceChildren(...(state.asleep ? [el("b", { text: word })] : ["Feeling ", el("b", { text: word })]));
  }
  syncFeelBase();
}

function setupFeelings() {
  feelActor = new SlimeActor($("#feel-slime"), { look: () => state.look, base: "curious" });
  lookActors.add(feelActor);
  const ul = $("#feel-list");
  feelRows = { now: $("#feel-now") };
  for (const e of EMOTIONS) {
    const bar = el("i");
    const li = el("li", {
      role: "meter", "aria-label": e.title, "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": "0", "data-feel-id": e.id,
    }, el("span", { class: "fl-name", text: e.title }), el("span", { class: "fl-bar", "aria-hidden": "true" }, bar));
    feelRows[e.id] = { li, bar, v: -1 };
    ul.append(li);
  }
  const acts = { pet, play, boo, sleep };
  $$("#feel-acts [data-feel]").forEach((b) => b.addEventListener("click", () => acts[b.dataset.feel]?.()));
  setInterval(() => !document.hidden && settleFeel(), 1000);
  renderFeel();
}

// ------------------------------------------------------------- small talk

/// A line from `pool` it hasn't said among its last twelve, or null.
function fresh(pool) {
  const left = (pool ?? []).filter((l) => !recentLines.includes(l));
  return left.length ? rand(left) : null;
}

function timeLines() {
  const h = new Date().getHours();
  if (h >= 5 && h < 10) return ["good morning", "morning stretch. done.", "new day. new goo."];
  if (h >= 12 && h < 17) return ["afternoon wobble", "sun's high. i'm lounging."];
  if (h >= 18 && h < 22) return ["evening. cozy.", "the light's all orange"];
  if (h >= 22 || h < 5) return ["late. cozy.", "the moon is out. hi moon."];
  return ["late morning. good time for a snack."];
}

/// What to say next, like GameStore.chatter(): what it needs, how it feels,
/// what just happened, the time of day, the season, where it lives, or just
/// something on its mind.
function chatterLine() {
  const pools = [];
  const w = wish();
  if (w) pools.push([WISH_LINES[w.id], 3]);
  const d = dominant();
  if (d.v >= 0.3) pools.push([FEEL_LINES[d.e.id], 4]);
  if (state.event && Date.now() - state.event.at < 90000) pools.push([EVENT_LINES[state.event.kind], 3]);
  pools.push([timeLines(), 1], [SEASON_LINES[seasonNow()], 1], [PLACE_LINES[homeNow()], 1], [CHATTER, 2]);
  const options = pools.map(([lines, weight]) => [fresh(lines), weight]).filter(([line]) => line);
  if (!options.length) return null;
  let r = Math.random() * options.reduce((sum, [, weight]) => sum + weight, 0);
  for (const [line, weight] of options) if ((r -= weight) < 0) return line;
  return options[0][0];
}

/// It talks on its own while you watch, like the app with "Talks on its own"
/// on: every 8 to 16 seconds, three times in four, never over you, in the
/// hero or in the feelings tile, whichever is on screen.
function startChatter() {
  const next = () => setTimeout(tick, 8000 + Math.random() * 8000);
  const tick = () => {
    next();
    if (document.hidden || state.asleep || Math.random() >= 0.75) return;
    if (Date.now() - lastTouch < 4000) return;
    const inHero = heroVisible && hero.visible;
    const inTile = Boolean(feelActor?.visible);
    if (!inHero && !inTile) return;
    const line = chatterLine();
    if (!line) return;
    if (inHero) {
      say(line, { announce: false });
      if (Math.random() < 0.4) playAll(rand(["curious", "blush", "wave", "stretch"]), 1);
    } else {
      state.line = line;
      remember(line);
      setFeelLine(line);
      islands().forEach((i) => i.sync());
      if (Math.random() < 0.4 && !calm()) feelActor.play(rand(["curious", "blush", "wave"]), 1);
    }
  };
  next();
}

// ------------------------------------------------------------------ crates

// CrateKind in Shared/Model. The app draws crate.wooden, crate.golden and
// crate.mystery from sprites.json; these pixel crates stand in until the
// sprites have them.
const CRATES = {
  wooden: {
    title: "Wooden crate", price: "80 coins",
    colors: { d: "#6B4423", l: "#C98F55", b: "#A8733F" },
    rows: [
      ".dddddddddddd.", "dlllllllllllld", "dbbbbbbbbbbbbd", "dddddddddddddd",
      "dbbdbbbbbbdbbd", "dbbbdbbbbdbbbd", "dbbbbdbbdbbbbd", "dbbbbbddbbbbbd",
      "dbbbbdbbdbbbbd", "dbbbdbbbbdbbbd", "dbbdbbbbbbdbbd", "dddddddddddddd",
    ],
  },
  golden: {
    title: "Golden crate", price: "300 coins",
    colors: { d: "#7A4A0E", y: "#FFF1B0", g: "#F2B630", k: "#5A3A10" },
    rows: [
      ".dddddddddddd.", "dyyyyyyyyyyyyd", "dggggggggggggd", "dddddddddddddd",
      "dggggdkkdggggd", "dggggkyykggggd", "dggggkyykggggd", "dggggdkkdggggd",
      "dggggggggggggd", "dyggggggggggyd", "dggggggggggggd", "dddddddddddddd",
    ],
  },
  mystery: {
    title: "Mystery crate", price: "Earned only",
    colors: { d: "#23284F", l: "#8C9BE8", b: "#5763C2", q: "#FFFFFF" },
    rows: [
      ".dddddddddddd.", "dlllllllllllld", "dbbbbbbbbbbbbd", "dddddddddddddd",
      "dbbbbqqqqbbbbd", "dbbbqbbbbqbbbd", "dbbbbbbbbqbbbd", "dbbbbbbbqbbbbd",
      "dbbbbbbqbbbbbd", "dbbbbbbbbbbbbd", "dbbbbbbqbbbbbd", "dddddddddddddd",
    ],
  },
};
const RARITY_TITLES = { common: "Common", rare: "Rare", epic: "Epic" };
// The odds of the crates on this page: the app's (Crates.dropRates) without
// the legendary, which is never in these.
const ODDS = {
  wooden: [["common", 72], ["rare", 24], ["epic", 4]],
  golden: [["common", 35], ["rare", 44.4], ["epic", 20]],
  mystery: [["common", 50], ["rare", 33], ["epic", 15]],
};
// A sample of what each rarity holds in the app (Crates.items and
// Crates.foods): common items cost 45 coins or less, rare ones more, epic
// ones are the catalog's rare pieces. Prices from Shared/Model/Catalog.swift.
const LOOT = {
  common: [{ coins: true }, { food: "cookie", n: 3 }, { food: "berry", n: 3 }, { food: "carrot", n: 3 },
    { item: "glasses.round", title: "Round glasses", price: 35 }, { item: "hat.beanie", title: "Beanie", price: 40 },
    { item: "furn.plant", title: "Potted plant", price: 25 }],
  rare: [{ coins: true }, { food: "cake", n: 2 }, { food: "pudding", n: 2 }, { item: "scarf.striped", title: "Striped scarf", price: 70 },
    { item: "glasses.shades", title: "Sunglasses", price: 60 }, { crate: "wooden" }],
  epic: [{ coins: true }, { item: "hat.crown", title: "Crown", price: 150 }, { item: "hat.wizard", title: "Wizard hat", price: 220 },
    { item: "furn.aquarium", title: "Aquarium", price: 120 }, { skin: "void" }, { skin: "ghost" }, { crate: "golden" }],
};
// Coins by crate and rarity (Crates.coinRange), lowest and highest.
const COIN_RANGES = {
  wooden: { common: [20, 40], rare: [60, 100], epic: [150, 220] },
  golden: { common: [60, 100], rare: [140, 220], epic: [320, 480] },
  mystery: { common: [40, 70], rare: [100, 160], epic: [240, 360] },
};
// A duplicate turns into coins (CrateReward.convertedCoins): half its price,
// but at least these per rarity (Crates.duplicateCoins); a colour always 150.
const DUPLICATE_COINS = { common: 15, rare: 40, epic: 100 };
const DUPLICATE_COLOUR_COINS = 150;
const BURST_COLORS = { common: ["#C7C7CC", "#FFFFFF", "#FFD166"], rare: ["#0A84FF", "#7FD6F0", "#FFFFFF"], epic: ["#BF5AF2", "#FFD166", "#FFFFFF"] };

/// The crate's pixels: the app's sprite when sprites.json has it, opened
/// (crate.golden.open) when `open` and there is such a sprite.
function crateArt(kind, open = false) {
  const name = open && sprites()?.items?.[`crate.${kind}.open`] ? `crate.${kind}.open` : `crate.${kind}`;
  if (sprites()?.items?.[name]) {
    const [w, h] = itemSize(name);
    return { w, h, draw: (ctx) => drawItemAt(ctx, name, 0, 0) };
  }
  const c = CRATES[kind];
  return { w: c.rows[0].length, h: c.rows.length, draw: (ctx) => paint(ctx, c.rows, c.colors) };
}

function crateCanvas(kind, scale) {
  const art = crateArt(kind);
  const c = el("canvas", { width: art.w, height: art.h, "aria-hidden": "true" });
  art.draw(c.getContext("2d"));
  c.style.width = art.w * scale + "px";
  c.style.height = art.h * scale + "px";
  return c;
}

function slimeIcon(skin) {
  const c = el("canvas", { width: 24, height: 20, "aria-hidden": "true", class: "cr-slime" });
  drawSlime(c.getContext("2d"), { stage: "young", skin }, "idle", 0);
  return c;
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function setupCrates() {
  const stage = $(".crate-stage");
  const btn = $("#crate");
  const canvas = $("#crate-canvas");
  const fxBox = $("#crate-fx");
  const card = $("#crate-reward");
  const hint = $("#crate-hint");
  const label = $("#crate-label");
  const price = $("#crate-price");
  const live = $("#crate-status");
  const chips = $$("#crate-kinds .chip");
  const legend = $$(".rarities li");
  // The room without the bed and the lamp, so the crate has the floor to itself.
  const furniture = { ceiling: ROOM_FURNITURE.ceiling, wallLeft: ROOM_FURNITURE.wallLeft, rug: ROOM_FURNITURE.rug };
  new Scene($("#crate-room"), () => ({ habitat: "room", rows: 56, floor: 0.7, furniture }));
  // What this slime already has, so a second one turns into coins.
  const owned = new Set([state.look.skin, state.look.hat, state.look.glasses, state.look.scarf].filter(Boolean));
  let kind = "golden";
  let taps = 0;
  let opened = false;
  let busy = false;

  const fit = () => {
    const art = crateArt(kind);
    const k = Math.max(3, Math.floor((stage.clientWidth * 0.26) / art.w));
    canvas.style.width = art.w * k + "px";
    canvas.style.height = art.h * k + "px";
  };
  const drawCrate = (open = false) => {
    const art = crateArt(kind, open);
    canvas.width = art.w;
    canvas.height = art.h;
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, art.w, art.h);
    art.draw(ctx);
    fit();
  };
  const describe = () => {
    const title = CRATES[kind].title;
    const left = ["Tap three times to open it.", "Two more taps.", "One more tap."][taps] ?? "";
    label.textContent = opened ? `${title}, opened. Press for another one.` : `${title}. ${left}`;
  };
  const reset = () => {
    taps = 0;
    opened = false;
    card.classList.remove("is-on");
    card.hidden = true;
    btn.classList.remove("is-open", "is-open-art");
    stage.classList.remove("is-open");
    drawCrate();
    legend.forEach((li) => li.classList.remove("is-hit"));
    hint.textContent = "Tap the crate three times.";
    describe();
  };
  const choose = (next) => {
    kind = next;
    chips.forEach((c) => c.setAttribute("aria-pressed", String(c.dataset.crate === kind)));
    price.textContent = CRATES[kind].price;
    reset();
  };
  const shake = (n) => {
    if (calm()) return;
    const a = 2 + n * 2;
    canvas.animate([
      { transform: "translateX(0) scale(1, 1)" },
      { transform: `translateX(${-a}px) scale(1.06, .94)`, offset: 0.2 },
      { transform: `translateX(${a}px) scale(.96, 1.05)`, offset: 0.45 },
      { transform: `translateX(${-a / 2}px) scale(1.02, .98)`, offset: 0.7 },
      { transform: "translateX(0) scale(1, 1)" },
    ], { duration: 360, easing: "ease-out" });
  };
  const burst = (rarity) => {
    if (calm()) return;
    const colors = BURST_COLORS[rarity];
    const r = canvas.getBoundingClientRect();
    const s = stage.getBoundingClientRect();
    const cx = r.left - s.left + r.width / 2;
    const cy = r.top - s.top + r.height / 2;
    const px = Math.max(4, Math.round((s.width / 96) * 1.5));
    for (let i = 0; i < 24; i++) {
      const p = el("i");
      p.style.width = p.style.height = px + "px";
      p.style.background = colors[i % colors.length];
      fxBox.append(p);
      const angle = Math.random() * Math.PI * 2;
      const dist = (0.22 + Math.random() * 0.4) * (s.width / 2);
      p.animate([
        { transform: `translate(${cx}px, ${cy}px)`, opacity: 1 },
        { transform: `translate(${cx + Math.cos(angle) * dist}px, ${cy + Math.sin(angle) * dist * 0.6}px)`, opacity: 0 },
      ], { duration: 700 + Math.random() * 400, easing: "cubic-bezier(.2, .7, .3, 1)" }).finished.then(() => p.remove());
    }
  };
  const roll = () => {
    const odds = ODDS[kind];
    let r = Math.random() * odds.reduce((sum, [, p]) => sum + p, 0);
    let rarity = odds[0][0];
    for (const [name, p] of odds) {
      if ((r -= p) < 0) {
        rarity = name;
        break;
      }
    }
    const prize = rand(LOOT[rarity]);
    if (prize.coins) {
      const [lo, hi] = COIN_RANGES[kind][rarity];
      return { rarity, coins: lo + Math.floor(Math.random() * (hi - lo + 1)) };
    }
    return { rarity, ...prize };
  };
  /// Fills the reward card; returns what to announce and what the slime says.
  const present = (reward) => {
    let icon = null;
    let title = "";
    let note = "";
    let line = "ooh.";
    let coins = 0;
    if (reward.coins) {
      icon = itemCanvas("coin", 3);
      title = `${reward.coins} coins`;
      note = "Added to your coins.";
      coins = reward.coins;
      line = rand(["coins. jingly.", "ka-ching"]);
    } else if (reward.food) {
      const food = FOODS.find((f) => f[0] === reward.food);
      icon = itemCanvas(reward.food, 3);
      title = `${food[1]} × ${reward.n}`;
      note = "Free servings in the kitchen.";
      line = `${foodName(reward.food)}. for later. maybe.`;
    } else if (reward.item) {
      const [w, h] = itemSize(reward.item);
      icon = itemCanvas(reward.item, Math.max(2, Math.min(5, Math.floor(44 / Math.max(w, h, 1)))));
      title = reward.title;
      line = reward.item.startsWith("furn.") ? "where do we put it?" : `ooh. ${reward.title.toLowerCase()}.`;
    } else if (reward.skin) {
      icon = slimeIcon(reward.skin);
      title = `${SKIN_TITLES[reward.skin]} colour`;
      line = `${SKIN_TITLES[reward.skin].toLowerCase()}. i'd look good in that.`;
    } else if (reward.crate) {
      icon = crateCanvas(reward.crate, 3);
      title = CRATES[reward.crate].title;
      note = "Another crate to open.";
      line = "a crate in a crate?";
    }
    const key = reward.item ?? reward.skin;
    if (key) {
      if (owned.has(key)) {
        coins = reward.skin ? DUPLICATE_COLOUR_COINS : Math.max(DUPLICATE_COINS[reward.rarity], Math.floor((reward.price ?? 0) / 2));
        note = `Already yours, so you get ${coins} coins instead.`;
        line = rand(["we have that already. coins it is.", "a twin! turned into coins.", "already ours. shiny coins instead."]);
      } else {
        owned.add(key);
        note = reward.skin ? "A new colour." : reward.item.startsWith("furn.") ? "New furniture for your home." : "Something new to wear.";
      }
    }
    $("#cr-rarity").textContent = RARITY_TITLES[reward.rarity];
    $("#cr-icon").replaceChildren(...(icon ? [icon] : []));
    $("#cr-title").textContent = title;
    $("#cr-note").textContent = note;
    return { title, note, line, coins };
  };

  btn.addEventListener("click", async () => {
    if (busy) return;
    if (opened) return reset();
    taps++;
    if (taps < 3) {
      shake(taps);
      hint.textContent = taps === 1 ? "Two more taps." : "One more tap.";
      describe();
      return;
    }
    busy = true;
    shake(3);
    await wait(calm() ? 0 : 300);
    const reward = roll();
    const shown = present(reward);
    // The opened crate stays under the reward when there is a sprite for it.
    const hasOpenArt = Boolean(sprites()?.items?.[`crate.${kind}.open`]);
    if (hasOpenArt) drawCrate(true);
    btn.classList.add(hasOpenArt ? "is-open-art" : "is-open");
    stage.classList.add("is-open");
    card.dataset.rarity = reward.rarity;
    card.hidden = false;
    burst(reward.rarity);
    requestAnimationFrame(() => card.classList.add("is-on"));
    legend.forEach((li) => li.classList.toggle("is-hit", li.dataset.rarity === reward.rarity));
    opened = true;
    hint.textContent = "Tap again for another one.";
    describe();
    live.textContent = `${RARITY_TITLES[reward.rarity]}: ${shown.title}. ${shown.note}`;
    if (shown.coins) addCoins(shown.coins, { pop: heroVisible });
    feel("excitement", reward.rarity === "epic" ? 0.45 : 0.28);
    feel("joy", reward.rarity === "common" ? 0.08 : 0.18);
    noteEvent("crate");
    playAll(reward.rarity === "epic" ? "celebrate" : "wave", 1);
    say(shown.line, { announce: false });
    await wait(calm() ? 0 : 450);
    busy = false;
  });
  chips.forEach((c) => c.addEventListener("click", () => {
    if (c.dataset.crate !== kind || opened || taps) choose(c.dataset.crate);
  }));
  new ResizeObserver(fit).observe(stage);
  choose(kind);
}

// ------------------------------------------------------- caring together

const CARE_VERBS = { feed: "fed", play: "played with", pet: "petted" };
const CARE_ANIMS = { feed: "eat", play: "play", pet: "love" };
const CARE_ICONS = { feed: "apple", play: "ball", pet: "heart" };
const CARE_NEED = { feed: ["hunger", 18], play: ["fun", 22], pet: ["love", 14] };

function setupCare() {
  const tile = $("#care");
  const log = $("#care-log");
  const status = $("#care-status");
  const needs = { hunger: 56, fun: 44, love: 62 };
  const phones = $$(".cphone").map((root) => {
    const slime = new SlimeActor($(".cphone-slime", root), { look: () => state.look });
    lookActors.add(slime);
    new Scene($(".cphone-scene", root), () => ({ habitat: "room", rows: 116, floor: 0.66, furniture: ROOM_FURNITURE }));
    return { root, who: root.dataset.who, slime, toast: $(".cphone-toast", root), timer: 0 };
  });
  const now = Date.now();
  const events = [
    { who: "Ben", kind: "pet", at: now - 5 * 60000 },
    { who: "Ana", kind: "feed", at: now - 47 * 60000 },
    { who: "Ben", kind: "play", at: now - 3 * 3600000 },
  ];
  const text = (e) => `${e.who} ${CARE_VERBS[e.kind]} ${state.name}`;
  const renderLog = () => {
    log.replaceChildren(...events.slice(0, 4).map((e) => el("li", {},
      itemCanvas(CARE_ICONS[e.kind], 2), el("span", { text: text(e) }), el("time", { text: ago(e.at) }))));
  };
  const paintNeeds = (phone) => {
    for (const [id, v] of Object.entries(needs)) {
      const bar = $(`.cphone-needs [data-need="${id}"] i`, phone.root);
      if (bar) bar.style.width = Math.round(v) + "%";
    }
  };
  const toast = (phone, line) => {
    phone.toast.textContent = line;
    phone.toast.classList.add("is-on");
    clearTimeout(phone.timer);
    phone.timer = setTimeout(() => phone.toast.classList.remove("is-on"), 3400);
  };
  let lastHand = 0;
  const act = (from, kind, { auto = false } = {}) => {
    const other = phones.find((p) => p !== from);
    const e = { who: from.who, kind, at: Date.now() };
    events.unshift(e);
    events.length = Math.min(events.length, 8);
    const [need, gain] = CARE_NEED[kind];
    needs[need] = clamp(needs[need] + gain, FLOOR, 100);
    from.slime.play(CARE_ANIMS[kind], 2);
    paintNeeds(from);
    // The other phone gets it a moment later, the way a care event arrives.
    setTimeout(() => {
      other.slime.play(CARE_ANIMS[kind], 2);
      toast(other, `${text(e)} · just now`);
      paintNeeds(other);
    }, calm() ? 0 : 700);
    renderLog();
    feel("love", auto ? 0.03 : 0.08);
    feel("joy", auto ? 0.03 : 0.06);
    noteEvent("partner");
    if (!auto) status.textContent = `${text(e)}. ${other.who}'s iPhone shows it too.`;
  };
  for (const p of phones) {
    $$("[data-care]", p.root).forEach((b) => b.addEventListener("click", () => {
      lastHand = Date.now();
      act(p, b.dataset.care);
    }));
  }
  // Now and then one of them looks after Blob without a click, so the sync
  // is there to see.
  const owner = {};
  watch(tile, owner);
  let turn = 1;
  setInterval(() => {
    if (document.hidden || !owner.visible || Date.now() - lastHand < 12000) return;
    act(phones[turn++ % phones.length], rand(["feed", "play", "pet"]), { auto: true });
  }, 8000);
  setInterval(() => {
    if (document.hidden) return;
    for (const k of Object.keys(needs)) needs[k] = Math.max(FLOOR, needs[k] - 1);
    phones.forEach(paintNeeds);
  }, 5000);
  setInterval(() => !document.hidden && renderLog(), 30000);
  phones.forEach(paintNeeds);
  renderLog();
}

// ---------------------------------------------------------------- friends

/// "just now", "5 min ago", "2 h ago", "yesterday", like the app's activity rows.
function ago(at) {
  const s = Math.max(0, (Date.now() - at) / 1000);
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return s < 172800 ? "yesterday" : `${Math.round(s / 86400)} days ago`;
}

function setupFriends() {
  const friendLook = { stage: "young", skin: "bubblegum", hat: "hat.flower", glasses: null, scarf: null };
  const me = new SlimeActor($("#meet-me"), { look: () => state.look });
  lookActors.add(me);
  const friend = new SlimeActor($("#meet-friend"), { look: friendLook, mirror: true });
  new Scene($("#meet-ground"), () => ({ habitat: "garden", rows: 52, floor: 0.7, season: "autumn" }));
  const bubble = $("#meet-bubble");
  const fx = $("#meet-fx");
  const list = $("#moments");
  const status = $("#moments-status");
  const now = Date.now();
  const moments = [
    { text: "Mochi visited Blob", at: now - 2 * 3600000 },
    { text: "Blob sent Mochi a cookie", at: now - 26 * 3600000 },
    { text: "Blob and Mochi became friends", at: now - 4 * 86400000 },
  ];
  const name = () => state.name;
  const render = () => {
    list.replaceChildren(...moments.slice(0, 3).map((m) => el("li", {}, el("span", { text: m.text }), el("time", { text: ago(m.at) }))));
  };
  const add = (text) => {
    moments.unshift({ text, at: Date.now() });
    moments.length = Math.min(moments.length, 6);
    render();
    status.textContent = text + ".";
  };
  setInterval(() => !document.hidden && render(), 30000);
  let bt;
  const speak = (text) => {
    bubble.textContent = text;
    bubble.classList.add("is-on");
    clearTimeout(bt);
    bt = setTimeout(() => bubble.classList.remove("is-on"), 3000);
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
  const tell = (text) => {
    if (!heroVisible) navIsland?.talk(text, 3200);
  };
  // MessagePreset in App/Online/HyprnodeAPI.swift: greetings are fixed phrases.
  const GREETINGS = ["Hi!", "Let's play!", "Thank you!", "Good night!", "Good morning!", "I'm proud of you."];
  let greeting = 0;
  let busy = false;
  $$(".meet-keys .btn").forEach((b) => b.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    const kind = b.dataset.friend;
    try {
      noteEvent("friend");
      if (kind === "hug") {
        feel("love", 0.15);
        feel("joy", 0.1);
        speak("hug with mochi. soft.");
        tell("hug with mochi. soft.");
        flashReel("love", 4000);
        add(`${name()} hugged Mochi`);
        setTimeout(() => heartsAt(3), calm() ? 0 : 1600);
        await Promise.all([me.play("meet", 1), friend.play("meet", 1)]);
      } else if (kind === "visit") {
        feel("excitement", 0.2);
        feel("curiosity", 0.1);
        speak("knock knock. it's blob.");
        tell("visiting mochi's garden");
        add(`${name()} visited Mochi`);
        await Promise.all([me.play("wave", 2), friend.play("celebrate", 2)]);
        speak("come in. mind the flowers.");
      } else if (kind === "food") {
        feel("joy", 0.1);
        const food = rand(["cookie", "apple", "berry", "pudding"]);
        speak("a gift for mochi is on its way");
        if (!calm()) {
          const w = fx.clientWidth, h = fx.clientHeight, u = w / 96;
          const c = itemCanvas(food, 1);
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
        add(`${name()} sent Mochi ${food === "apple" ? "an apple" : `a ${foodName(food)}`}`);
        await friend.play("eat", 2);
        speak(`mail from ${name().toLowerCase()}. yum.`);
        friend.play("love", 1);
      } else if (kind === "greet") {
        const text = GREETINGS[greeting++ % GREETINGS.length];
        feel("joy", 0.12);
        speak(text);
        tell(`to mochi: ${text.toLowerCase()}`);
        add(`${name()} said “${text}” to Mochi`);
        heartsAt(1);
        await Promise.all([me.play("wave", 1), friend.play("wave", 2)]);
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
  setupFeelings();
  setupCrates();
  setupCare();
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
  startChatter();
  setInterval(() => {
    if (document.hidden || !heroVisible || !hero.visible || state.asleep || Date.now() - lastTouch < 9000) return;
    if (state.sx && Math.random() < 0.5) hopTo(0);
  }, 11000);
}

boot().catch((err) => console.error(err));
