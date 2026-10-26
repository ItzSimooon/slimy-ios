// The slime's homes, after App/UI/World/HabitatScene.swift, plus the garden
// world with its night version and the Lock Screen sky. Drawn on a grid 96
// pixels wide; the canvas is that grid and CSS scales it up.
import { sprites, itemSize, drawItemAt, paint } from "./slime.js";

export const HABITATS = [
  { id: "room", title: "Room", note: "From the start" },
  { id: "garden", title: "Garden", note: "Level 5" },
  { id: "beach", title: "Beach", note: "Level 9" },
  { id: "space", title: "Space", note: "Level 14" },
  { id: "crystal", title: "Crystal cave", note: "Achievement" },
];

export const ROOM_FURNITURE = {
  ceiling: "furn.lights", wallLeft: "furn.poster", wallRight: "furn.shelf",
  rug: "furn.rug", floorLeft: "furn.bed", floorRight: "furn.lamp",
};

const W = 96;

function rgba(hexStr, a = 1) {
  if (a >= 1) return hexStr;
  const n = parseInt(hexStr.slice(1), 16);
  return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`;
}

export function rng(seed) {
  let s = seed >>> 0 || 1;
  const next = () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return { next, int: (a, b) => a + Math.floor(next() * (b - a + 1)) };
}

/// Season.at(_:) in Shared/Model/Seasons.swift: all of October and December,
/// New Year from 6 pm on New Year's Eve through New Year's Day.
export function season(date = new Date()) {
  const m = date.getMonth() + 1, d = date.getDate();
  if ((m === 12 && d === 31 && date.getHours() >= 18) || (m === 1 && d === 1)) return "newYear";
  if (m === 10) return "halloween";
  if (m === 12) return "christmas";
  if (m >= 3 && m <= 5) return "spring";
  if (m >= 6 && m <= 8) return "summer";
  if (m >= 9 && m <= 11) return "autumn";
  return "winter";
}

function light(night) {
  return night
    ? { isNight: true, level: 0.25, sky: "#1C2240", skyLow: "#2B3260" }
    : { isNight: false, level: 1, sky: "#8FD3F2", skyLow: "#C6EBF7" };
}

// The garden by day and by night (night colours are hand-picked, not dimmed).
const DAY = {
  sky: "#8FD3F2", skyLow: "#C6EBF7", sun: "#FFE38A", cloud: "#FFFFFF",
  hillBack: "#8ACB6A", hillFront: "#6BB45A",
  grass: "#79C05A", grassLight: "#95D370", grassDark: "#62AE4C",
  trunk: "#7A4E2A", leaf: "#4FA34A", leafLight: "#66B95A",
  fence: "#E8D9C0", fenceRail: "#D9C6A6",
  wall: "#F0D2A8", wallStripe: "#E8C595", wood: "#8A5A36", woodDark: "#6B4423",
  roof: "#D0644F", roofDark: "#A84B3C", roofLight: "#E68A70",
  brick: "#B5624A", brickDark: "#8E4A38", door: "#8A5A36",
  soil: "#9A6A3A", soilDark: "#7A5230", sprout: "#4FA34A",
  pole: "#9AA0A8", board: "#F4F1EA", boardLine: "#E8524A",
  window: "#8FD3F2", windowLow: "#C6EBF7",
  flowers: ["#F07A9A", "#FFD166", "#FFFFFF"],
};
const NIGHT = {
  sky: "#1C2240", skyLow: "#2B3260", moon: "#F4EBD9", star: "#FFFFFF",
  hillBack: "#25403A", hillFront: "#203830",
  grass: "#2C4C35", grassLight: "#365A40", grassDark: "#26432F",
  trunk: "#3E2B20", leaf: "#24452E", leafLight: "#2C5236",
  fence: "#5E5C70", fenceRail: "#4E4C60",
  wall: "#C9A27A", wallStripe: "#BC9670", wood: "#6A4428", woodDark: "#4E321E",
  roof: "#7A3A38", roofDark: "#5C2C2C", roofLight: "#8E4840",
  brick: "#6A3A32", brickDark: "#522C26", door: "#5A3A22",
  soil: "#4E3624", soilDark: "#3E2A1C", sprout: "#2F5A38",
  pole: "#5A5E68", board: "#9A98A8", boardLine: "#8A3A38",
  window: "#FFE7A0", windowLow: "#FFD27A",
  flowers: ["#A8587A", "#BFA055", "#B8BCD4"],
};
const FAIRY = ["#F07A9A", "#FFD166", "#7FD6F0", "#7DE67A"];

class Painter {
  constructor(ctx) { this.ctx = ctx; }
  rect(x, y, w, h, c) {
    if (w <= 0 || h <= 0) return;
    this.ctx.fillStyle = c;
    this.ctx.fillRect(x, y, w, h);
  }
  frame(x, y, w, h, c, t = 1) {
    this.rect(x, y, w, t, c); this.rect(x, y + h - t, w, t, c);
    this.rect(x, y, t, h, c); this.rect(x + w - t, y, t, h, c);
  }
  disc(cx, cy, r, c) {
    for (let dy = -r; dy <= r; dy++) {
      const half = Math.round(Math.sqrt(r * r - dy * dy));
      this.rect(cx - half, cy + dy, half * 2 + 1, 1, c);
    }
  }
  glow(x, y, c) { this.rect(x - 1, y - 1, 3, 3, rgba(c, 0.25)); this.rect(x, y, 1, 1, c); }
  glowCone(x, y, c) {
    for (let i = 0; i < 14; i++) this.rect(x - Math.floor(i / 2) - 1, y + i, i + 2, 1, rgba(c, 0.18 - i * 0.01));
  }
  sprite(name, x, y) { drawItemAt(this.ctx, name, x, y); }
  spriteBottom(name, x, bottom) { this.sprite(name, x, bottom - itemSize(name)[1]); }
}

/// Draws a habitat. opts: {rows, t, night, floor (0…1), furniture, season}
export function drawHabitat(canvas, habitat, opts) {
  if (!sprites()) return;
  const rows = opts.rows;
  if (canvas.width !== W) canvas.width = W;
  if (canvas.height !== rows) canvas.height = rows;
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, W, rows);
  const p = new Painter(ctx);
  const w = {
    rows, t: opts.t ?? 0, light: light(opts.night), season: opts.season ?? season(),
    floorY: Math.floor(rows * (opts.floor ?? 0.66)), furniture: opts.furniture ?? {},
  };
  ({ room, garden, beach, space, crystal })[habitat]?.(p, w);
  if (w.light.level < 1 && habitat !== "space" && habitat !== "garden") p.rect(0, 0, W, rows, rgba("#14183A", (1 - w.light.level) * 0.55));
  drawGlow(p, w, habitat);
}

// ------------------------------------------------------------------ room

function room(p, w) {
  const f = w.floorY;
  p.rect(0, 0, W, f, "#F0D2A8");
  for (let x = 3; x < W; x += 8) p.rect(x, 0, 3, f, "#E8C595");
  const wx = 54, wy = Math.max(8, Math.floor(f / 6)), ww = 32, wh = 26;
  sky(p, w, wx + 2, wy + 2, ww - 4, wh - 4);
  windowSeason(p, w, wx + 2, wy + 2, ww - 4, wh - 4);
  p.frame(wx, wy, ww, wh, "#8A5A36", 2);
  p.rect(wx + ww / 2 - 1, wy, 2, wh, "#8A5A36");
  p.rect(wx, wy + wh / 2 - 1, ww, 2, "#8A5A36");
  p.rect(wx - 2, wy + wh, ww + 4, 2, "#6B4423");
  p.rect(0, f, W, w.rows - f, "#C48A58");
  let row = 0;
  for (let y = f + 3; y < w.rows; y += 5) {
    p.rect(0, y, W, 1, "#A8733F");
    for (let x = row % 2 === 0 ? 0 : 12; x < W; x += 24) p.rect(x, y - 4, 1, 4, "#A8733F");
    row++;
  }
  p.rect(0, f - 2, W, 3, "#8A5A36");
  const fu = w.furniture;
  if (fu.ceiling) { p.sprite(fu.ceiling, 0, 1); p.sprite(fu.ceiling, 32, 1); p.sprite(fu.ceiling, 64, 1); }
  if (fu.wallLeft) p.sprite(fu.wallLeft, 12, Math.max(10, Math.floor(f / 3)));
  if (fu.wallRight) p.sprite(fu.wallRight, 60, wy + wh + 6);
  if (fu.rug) p.sprite(fu.rug, 36, f + 14);
  if (fu.floorLeft) p.spriteBottom(fu.floorLeft, 3, f + 10);
  if (fu.floorRight) p.spriteBottom(fu.floorRight, 82, f + 10);
  // The year shows in the room, like in the app.
  if (w.season === "halloween") {
    p.spriteBottom("hat.pumpkin", fu.floorLeft ? 24 : 8, f + 12);
    p.spriteBottom("hat.pumpkin", 70, f + 13);
  }
  if (w.season === "christmas") xmasTree(p, w, fu.floorLeft ? 24 : 8, f + 11);
  if (w.season === "spring") {
    p.spriteBottom("flower", 26, f + 9);
    p.spriteBottom("flower", 68, f + 10);
  }
  if (w.season === "newYear") {
    for (let i = 0; i < 5; i++) p.rect(28 + i * 9, f - 6 + (i % 2) * 2, 2, 2, FAIRY[i % 4]);
  }
}

/// A small tree with lights that blink, for December.
function xmasTree(p, w, x, bottom) {
  p.rect(x + 4, bottom - 3, 2, 3, "#7A4E2A");
  for (let i = 0; i < 4; i++) {
    const width = 2 + i * 2;
    p.rect(x + 5 - Math.floor(width / 2), bottom - 13 + i * 2.5 | 0, width, 3, i % 2 ? "#3F8F45" : "#4FA34A");
  }
  p.rect(x + 4, bottom - 15, 2, 2, "#FFD166");
  const on = Math.trunc(w.t * 2) % 2;
  [[2, -6], [7, -8], [4, -10], [8, -5], [1, -4]].forEach(([dx, dy], i) => {
    if ((i + on) % 2 === 0) p.rect(x + dx, bottom + dy, 1, 1, FAIRY[i % 4]);
  });
}

/// What the window shows for the season: bats, snow or fireworks.
function windowSeason(p, w, x, y, width, height) {
  const t = w.t;
  if (w.season === "halloween") {
    for (const [bx, by, ph] of [[8, 5, 0], [19, 9, 1.7]]) {
      const flap = Math.trunc(t * 4 + ph) % 2;
      const yy = y + by + Math.round(Math.sin(t * 0.9 + ph));
      p.rect(x + bx, yy, 2, 1, "#2A1E3A");
      p.rect(x + bx - 2, yy - flap, 2, 1, "#2A1E3A");
      p.rect(x + bx + 2, yy - flap, 2, 1, "#2A1E3A");
    }
  } else if (w.season === "christmas" || w.season === "winter") {
    const r = rng(17);
    for (let i = 0; i < 14; i++) {
      const sx = r.int(0, width - 1);
      const sy = Math.floor(r.int(0, height) + t * (2 + (i % 3))) % height;
      p.rect(x + sx, y + sy, 1, 1, "#FFFFFF");
    }
    p.rect(x, y + height - 2, width, 2, "#FFFFFF");
  } else if (w.season === "newYear") {
    const k = (t * 1.2) % 3;
    for (const [cx, cy, c, d] of [[8, 6, "#FFD166", 0], [19, 10, "#F07A9A", 1.4]]) {
      const r = Math.floor(((k + d) % 3) * 2.2);
      if (r < 1 || r > 5) continue;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]]) {
        p.rect(x + cx + dx * r, y + cy + dy * r, 1, 1, c);
      }
    }
  }
}

// ---------------------------------------------------------------- garden

function garden(p, w) {
  const f = w.floorY, rows = w.rows, t = w.t;
  const night = w.light.isNight;
  const P = night ? NIGHT : DAY;
  const autumn = w.season === "autumn" || w.season === "halloween";

  // Sky.
  const low = Math.floor(f * 0.7);
  p.rect(0, 0, W, f, P.sky);
  p.rect(0, low, W, f - low, P.skyLow);
  if (night) {
    const r = rng(11);
    for (let i = 0; i < 46; i++) {
      const x = r.int(0, 95), y = r.int(0, low - 2);
      const dim = Math.trunc(t * 1.6 + i) % 7 === 0;
      p.rect(x, y, 1, 1, dim ? "rgba(255,255,255,.35)" : "rgba(255,255,255,.9)");
    }
    const mx = 72, my = Math.max(8, Math.floor(f * 0.36));
    p.disc(mx, my, 5, P.moon);
    p.disc(mx + 2, my - 2, 5, P.sky);
  } else {
    p.disc(14, Math.max(7, Math.floor(f * 0.42)), 4, P.sun);
    for (let i = 0; i < 3; i++) {
      const x = Math.trunc((t * 1.2 + i * 47) % 140) - 22;
      const y = Math.max(4, Math.floor(f * (0.18 + i * 0.16)));
      p.rect(x, y, 13, 3, P.cloud);
      p.rect(x + 2, y - 2, 8, 2, P.cloud);
      p.rect(x + 4, y - 3, 4, 1, P.cloud);
    }
  }

  // Hills.
  for (let x = 0; x < W; x++) {
    const h1 = Math.round(8 + 5 * Math.sin(x / 11));
    p.rect(x, f - h1 - 6, 1, h1 + 6, P.hillBack);
    const h2 = Math.round(5 + 3 * Math.sin(x / 7 + 2));
    p.rect(x, f - h2, 1, h2, P.hillFront);
  }

  // A tree on the left.
  const tx = 12;
  p.rect(tx - 2, f - 22, 4, 22, P.trunk);
  const leaf = autumn && !night ? "#E0893A" : P.leaf;
  const leafLight = autumn && !night ? "#F2A65A" : P.leafLight;
  for (const [dx, dy, r] of [[-6, -30, 7], [2, -34, 8], [8, -28, 6]]) p.disc(tx + dx, f + dy, r, leaf);
  for (const [dx, dy, r] of [[-8, -32, 3], [0, -37, 3], [6, -31, 2]]) p.disc(tx + dx, f + dy, r, leafLight);

  // The fence behind the lawn.
  for (let x = 22; x < 56; x += 6) p.rect(x, f - 9, 2, 9, P.fence);
  p.rect(22, f - 7, 34, 1, P.fenceRail);
  p.rect(22, f - 4, 34, 1, P.fenceRail);

  // The house, cut away at the right edge.
  const x0 = 56, x1 = 96;
  const wallTop = f - 24;
  const roofTop = wallTop - 10;
  p.rect(84, roofTop - 3, 5, 8, P.brick);
  p.rect(83, roofTop - 4, 7, 2, P.brickDark);
  for (let i = 0; i < 10; i++) {
    const y = roofTop + i;
    const left = 74 - Math.round(i * 2.1);
    p.rect(left, y, W - left, 1, i % 3 === 2 ? P.roofDark : P.roof);
    p.rect(left, y, 2, 1, P.roofDark);
  }
  p.rect(70, roofTop + 1, 14, 1, P.roofLight);
  p.rect(x0, wallTop, x1 - x0, f - wallTop, P.wall);
  for (let x = x0 + 4; x < x1; x += 7) p.rect(x, wallTop, 2, f - wallTop, P.wallStripe);
  p.rect(x0 - 2, wallTop, x1 - x0 + 2, 2, P.woodDark);
  p.rect(x0, wallTop, 2, f - wallTop, P.wood);
  // Door.
  p.rect(x0 + 6, f - 14, 8, 14, P.door);
  p.frame(x0 + 5, f - 15, 10, 15, P.woodDark);
  p.rect(x0 + 12, f - 7, 1, 1, night ? "#FFD166" : "#E8D9C0");
  // Window: today's sky by day, warm light by night.
  const wx = 81, wy = wallTop + 5, ww = 13, wh = 11;
  p.rect(wx, wy, ww, wh, P.window);
  p.rect(wx, wy + Math.floor(wh * 0.62), ww, wh - Math.floor(wh * 0.62), P.windowLow);
  if (!night) {
    p.rect(wx + 2, wy + 2, 4, 1, "#FFFFFF");
    p.rect(wx + 2, wy + 3, 2, 1, "#FFFFFF");
  }
  p.frame(wx - 1, wy - 1, ww + 2, wh + 2, P.wood);
  p.rect(wx + Math.floor(ww / 2), wy, 1, wh, P.wood);
  p.rect(wx - 2, wy + wh + 1, ww + 4, 1, P.woodDark);
  // Fairy lights along the eaves.
  for (let i = 0, x = x0; x < x1; x += 3, i++) {
    const on = !night || (i + Math.trunc(t * 1.5)) % 2 === 0;
    const c = FAIRY[i % 4];
    p.rect(x, wallTop + 2, 1, 1, night ? c : rgba(c, 0.55));
    if (night && on) p.rect(x - 1, wallTop + 1, 3, 3, rgba(c, 0.22));
  }
  // Snow on the roof in winter.
  if (w.season === "christmas" || w.season === "winter") p.rect(54, roofTop, W - 54, 1, "#FFFFFF");

  // The hoop.
  const hx = 34;
  p.rect(hx, f - 30, 2, 30, P.pole);
  p.rect(hx - 5, f - 38, 12, 9, P.board);
  p.rect(hx - 2, f - 35, 6, 4, P.boardLine);
  p.rect(hx - 1, f - 34, 4, 2, P.board);
  p.sprite("hoop", hx - 4, f - 30);

  // The lawn.
  p.rect(0, f, W, rows - f, P.grass);
  const r = rng(42);
  for (let i = 0; i < 120; i++) p.rect(r.int(0, 95), r.int(f + 1, rows - 1), 1, 1, P.grassLight);
  for (let x = 0; x < W; x += 1) if (r.int(0, 4) === 0) p.rect(x, f, 1, 1, P.grassDark);
  for (let i = 0; i < 10; i++) {
    const x = r.int(2, 93), y = r.int(f + 4, Math.max(f + 4, rows - 4));
    if (x > 70 && y < f + 12) continue;
    const c = P.flowers[i % 3];
    p.rect(x, y, 1, 1, c); p.rect(x - 1, y + 1, 3, 1, c); p.rect(x, y + 2, 1, 1, P.leaf);
  }
  if (night) {
    // Lamplight from the window falls onto the grass.
    for (let i = 0; i < 6; i++) p.rect(wx - i, f + 1 + i, ww + i * 2, 1, rgba("#FFE7A0", 0.12 - i * 0.015));
  }

  // The vegetable bed.
  const bx = 74, by = f + 5;
  p.rect(bx, by, 21, 6, P.soil);
  for (let y = by + 1; y < by + 6; y += 2) p.rect(bx + 1, y, 19, 1, P.soilDark);
  for (let x = bx + 2; x < bx + 20; x += 4) {
    p.rect(x, by - 1, 1, 2, P.sprout);
    p.rect(x - 1, by - 2, 1, 1, P.sprout);
    p.rect(x + 1, by - 2, 1, 1, P.sprout);
  }
  if (w.season === "halloween") p.spriteBottom("hat.pumpkin", 60, f + 3);

  if (night) {
    for (let i = 0; i < 6; i++) {
      const x = 6 + Math.trunc(42 * (1 + Math.sin(t * 0.3 + i * 1.7)));
      const y = f - 4 - Math.trunc(9 * (1 + Math.sin(t * 0.5 + i)));
      if (Math.trunc(t * 2.5 + i) % 3 !== 0) p.glow(x, y, "#EAF77A");
    }
  } else {
    const x = Math.trunc(48 + 36 * Math.sin(t * 0.13)), y = Math.trunc(f - 16 + 5 * Math.sin(t * 0.7));
    const flap = Math.trunc(t * 6) % 2 === 0 ? 1 : 0;
    p.rect(x, y, 1, 2, "#3A2B2E");
    p.rect(x - 2, y - flap, 2, 2, "#F07A9A");
    p.rect(x + 1, y - flap, 2, 2, "#F07A9A");
  }
}

// ----------------------------------------------------------------- beach

function beach(p, w) {
  const f = w.floorY;
  sky(p, w, 0, 0, W, f - 10);
  p.rect(0, f - 12, W, 12, "#3F8FCB");
  for (let x = 0; x < W; x++) {
    const wave = Math.trunc(1.5 * Math.sin(x / 5 + w.t * 1.4));
    p.rect(x, f - 12 + wave, 1, 1, "#7FC6EE");
    if ((x + Math.trunc(w.t * 4)) % 13 === 0) p.rect(x, f - 2 + wave, 2, 1, "#FFFFFF");
  }
  p.rect(0, f, W, w.rows - f, "#F2D49B");
  const r = rng(7);
  for (let i = 0; i < 60; i++) p.rect(r.int(0, 95), r.int(f, w.rows - 1), 1, 1, "#E0BD7E");
  for (let i = 0; i < 20; i++) p.rect(84 - Math.floor(i / 4), f - i, 3, 1, "#9A6A3A");
  for (const [dx, dy, len] of [[-10, -1, 10], [0, -2, 10], [-6, 2, 8], [2, 2, 7]]) {
    for (let i = 0; i < len; i++) p.rect(79 + dx + i, f - 21 + dy + Math.floor(Math.abs(i - Math.floor(len / 2)) / 2), 1, 2, "#4FA34A");
  }
  p.sprite("bubbleItem", 14, f + 10);
  const cx = Math.trunc((w.t * 4) % 160) - 20;
  if (cx > -4 && cx < 100) {
    p.rect(cx, f + 18, 5, 2, "#E8524A");
    p.rect(cx - 1, f + 17, 1, 1, "#E8524A");
    p.rect(cx + 5, f + 17, 1, 1, "#E8524A");
  }
}

// ----------------------------------------------------------------- space

function space(p, w) {
  const f = w.floorY;
  p.rect(0, 0, W, w.rows, "#0B0E24");
  const r = rng(99);
  for (let i = 0; i < 70; i++) {
    const x = r.int(0, 95), y = r.int(0, f);
    const twinkle = Math.trunc(w.t * 2 + i) % 5 === 0;
    p.rect(x, y, 1, 1, `rgba(255,255,255,${twinkle ? 0.3 : 0.85})`);
  }
  p.disc(72, Math.max(12, Math.floor(f * 0.3)), 9, "#B59BFF");
  p.disc(70, Math.max(10, Math.floor(f * 0.3)) - 2, 3, "#D4C6FF");
  p.rect(58, Math.max(12, Math.floor(f * 0.3)), 28, 2, "#F2D49B");
  const cycle = w.t % 12;
  if (cycle < 1.2) {
    const k = Math.trunc(cycle * 40);
    for (let i = 0; i < 6; i++) p.rect(10 + k - i, 6 + Math.floor(k / 2) - Math.floor(i / 2), 1, 1, `rgba(255,255,255,${1 - i / 6})`);
  }
  p.rect(0, f, W, w.rows - f, "#9AA0A8");
  for (const [x, y, rad] of [[14, f + 8, 4], [52, f + 16, 3], [80, f + 6, 2], [30, f + 24, 3]]) p.disc(x, y, rad, "#7C828A");
}

// --------------------------------------------------------------- crystal

function crystal(p, w) {
  const f = w.floorY;
  p.rect(0, 0, W, w.rows, "#2A1E3A");
  const r = rng(5);
  for (let i = 0; i < 120; i++) p.rect(r.int(0, 95), r.int(0, w.rows - 1), 2, 1, "#352848");
  p.rect(0, f, W, w.rows - f, "#3A2E4A");
  const pulse = 0.6 + 0.4 * Math.sin(w.t * 1.5);
  [[8, 14], [20, 9], [70, 16], [86, 10], [44, 7]].forEach(([x, h], i) => {
    const c = i % 2 === 0 ? "#B59BFF" : "#7FD6F0";
    for (let j = 0; j < h; j++) {
      const width = Math.max(1, 4 - Math.floor(j / 4));
      p.rect(x - Math.floor(width / 2), f - j, width, 1, rgba(c, 0.7 + 0.3 * pulse));
    }
  });
  for (const x of [30, 60]) {
    p.rect(x, f - 3, 1, 3, "#E9F1E4");
    p.rect(x - 2, f - 5, 5, 2, rgba("#7FD6F0", pulse));
  }
}

function sky(p, w, x, y, width, height) {
  const l = w.light;
  p.rect(x, y, width, height, l.sky);
  const low = Math.floor((height * 2) / 3);
  p.rect(x, y + low, width, height - low, l.skyLow);
  if (l.isNight) {
    const r = rng(3);
    for (let i = 0; i < Math.max(4, Math.floor((width * height) / 60)); i++) {
      p.rect(x + r.int(0, width - 1), y + r.int(0, low), 1, 1, "rgba(255,255,255,.8)");
    }
    p.disc(x + width - 8, y + 6, 3, "#F4EBD9");
    p.disc(x + width - 7, y + 5, 3, l.sky);
  } else {
    p.disc(x + 7, y + 7, 3, "#FFE38A");
  }
}

function drawGlow(p, w, habitat) {
  if (habitat !== "room" || !w.light.isNight) return;
  if (w.furniture.floorRight === "furn.lamp") p.glowCone(85, w.floorY - 12, "#FFE7A0");
  if (w.furniture.ceiling === "furn.lights") {
    for (let x = 2; x < W; x += 4) {
      if ((Math.floor(x / 4) + Math.trunc(w.t)) % 2 === 0) p.glow(x, 3, ["#F07A9A", "#FFD166", "#7FD6F0"][x % 3]);
    }
  }
}

// ------------------------------------------------------ lock screen sky

const CLOUD_A = [
  "............wwwwww................",
  ".........wwwwwwwwwww...wwww.......",
  ".......wwwwwwwwwwwwwwwwwwwwwww....",
  "....wwwwwwwwwwwwwwwwwwwwwwwwwwww..",
  "..wwwwwwwwwwwwwwwwwwwwwwwwwwwwwww.",
  ".wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww",
  "wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww",
  ".ssssssssssssssssssssssssssssssss.",
];
const CLOUD_B = [
  "......wwwww...........",
  "...wwwwwwwwwww.www....",
  ".wwwwwwwwwwwwwwwwwww..",
  "wwwwwwwwwwwwwwwwwwwwww",
  ".ssssssssssssssssssss.",
];

/// A Lock Screen wallpaper: pixel clouds by day, stars by night.
/// The canvas is sized to the grid (cols × rows); CSS scales it.
export function drawLockSky(canvas, { cols, rows, night }) {
  if (canvas.width !== cols) canvas.width = cols;
  if (canvas.height !== rows) canvas.height = rows;
  const ctx = canvas.getContext("2d");
  const fill = (x, y, w, h, c) => { ctx.fillStyle = c; ctx.fillRect(x, y, w, h); };
  fill(0, 0, cols, rows, night ? "#1C2240" : "#8FD3F2");
  const clouds = night ? { w: "#2B3260", s: "#232A52" } : { w: "#FFFFFF", s: "#C6EBF7" };
  paint(ctx, CLOUD_A, clouds, Math.round(cols * 0.04) - 6, Math.round(rows * 0.62));
  paint(ctx, CLOUD_B, clouds, Math.round(cols * 0.74), Math.round(rows * 0.44));
  if (night) {
    ctx.fillStyle = "rgba(233, 241, 228, .75)";
    for (const [fx, fy] of [[.08, .3], [.19, .48], [.31, .86], [.74, .34], [.9, .62], [.95, .24], [.58, .66], [.44, .95], [.66, .12], [.27, .18]]) {
      ctx.fillRect(Math.round(cols * fx), Math.round(rows * fy), 1, 1);
    }
    for (const [fx, fy] of [[.86, .82], [.13, .74]]) {
      const x = Math.round(cols * fx), y = Math.round(rows * fy);
      ctx.fillRect(x - 1, y, 3, 1);
      ctx.fillRect(x, y - 1, 1, 3);
    }
  }
}
