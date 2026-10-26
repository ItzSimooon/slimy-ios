// Pixel art in pure Node for the site build: a tiny RGBA canvas, a
// hand-written PNG encoder (zlib only), a small proportional bitmap font, and
// the slime drawn from sprites.json exactly like site/assets/slime.js does.

import { deflateSync } from "node:zlib";

// ------------------------------------------------------------------ PNG

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/// Encodes 8-bit RGBA pixels (row-major, w*h*4 bytes) as a PNG file.
export function encodePNG(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type: RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// --------------------------------------------------------------- canvas

/// "#RRGGBB" or "#RRGGBBAA" or a 0xRRGGBB number → [r, g, b, a].
export function rgba(color) {
  if (typeof color === "number") return [(color >> 16) & 255, (color >> 8) & 255, color & 255, 255];
  const h = color.replace("#", "");
  const n = parseInt(h.slice(0, 6), 16);
  const a = h.length === 8 ? parseInt(h.slice(6, 8), 16) : 255;
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, a];
}

/// A grid of RGBA pixels with flat fills only, like the site's canvases.
export class Pix {
  constructor(width, height, background = null) {
    this.width = width;
    this.height = height;
    this.data = new Uint8Array(width * height * 4);
    if (background) this.rect(0, 0, width, height, background);
  }

  set(x, y, color) {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return;
    const [r, g, b, a] = Array.isArray(color) ? color : rgba(color);
    const i = (y * this.width + x) * 4;
    if (a === 255) {
      this.data[i] = r;
      this.data[i + 1] = g;
      this.data[i + 2] = b;
      this.data[i + 3] = 255;
      return;
    }
    // Source-over blending for the few translucent touches.
    const sa = a / 255;
    const da = this.data[i + 3] / 255;
    const oa = sa + da * (1 - sa);
    if (oa === 0) return;
    this.data[i] = Math.round((r * sa + this.data[i] * da * (1 - sa)) / oa);
    this.data[i + 1] = Math.round((g * sa + this.data[i + 1] * da * (1 - sa)) / oa);
    this.data[i + 2] = Math.round((b * sa + this.data[i + 2] * da * (1 - sa)) / oa);
    this.data[i + 3] = Math.round(oa * 255);
  }

  rect(x, y, w, h, color) {
    const c = rgba(color);
    for (let yy = y; yy < y + h; yy++) for (let xx = x; xx < x + w; xx++) this.set(xx, yy, c);
  }

  disc(cx, cy, r, color) {
    for (let dy = -r; dy <= r; dy++) {
      const half = Math.round(Math.sqrt(r * r - dy * dy));
      this.rect(cx - half, cy + dy, half * 2 + 1, 1, color);
    }
  }

  /// A pill (fully rounded rectangle), drawn with whole pixels.
  pill(x, y, w, h, color) {
    const r = Math.floor(h / 2);
    for (let yy = 0; yy < h; yy++) {
      const dy = yy + 0.5 - h / 2;
      const inset = Math.max(0, Math.ceil(r - Math.sqrt(Math.max(0, r * r - dy * dy)) - 0.35));
      this.rect(x + inset, y + yy, w - inset * 2, 1, color);
    }
  }

  /// A rectangle with rounded corners of radius r (pixel-art style).
  round(x, y, w, h, r, color) {
    for (let yy = 0; yy < h; yy++) {
      let inset = 0;
      const fromTop = yy, fromBottom = h - 1 - yy;
      const d = Math.min(fromTop, fromBottom);
      if (d < r) {
        const dy = r - d - 0.5;
        inset = Math.max(0, Math.ceil(r - Math.sqrt(Math.max(0, r * r - dy * dy)) - 0.35));
      }
      this.rect(x + inset, y + yy, w - inset * 2, 1, color);
    }
  }

  /// Paints character rows with a map from character to colour.
  paint(rows, colors, ox = 0, oy = 0, scale = 1) {
    rows.forEach((row, y) => {
      for (let x = 0; x < row.length; x++) {
        const c = colors[row[x]];
        if (c) this.rect(ox + x * scale, oy + y * scale, scale, scale, c);
      }
    });
  }

  /// Copies another Pix in at (ox, oy).
  draw(src, ox, oy) {
    for (let y = 0; y < src.height; y++) {
      for (let x = 0; x < src.width; x++) {
        const i = (y * src.width + x) * 4;
        if (src.data[i + 3] === 0) continue;
        this.set(ox + x, oy + y, [src.data[i], src.data[i + 1], src.data[i + 2], src.data[i + 3]]);
      }
    }
  }

  /// A new Pix, every pixel a k×k block. Keeps pixel art crisp.
  scaled(k) {
    const out = new Pix(this.width * k, this.height * k);
    for (let y = 0; y < out.height; y++) {
      const sy = Math.floor(y / k);
      for (let x = 0; x < out.width; x++) {
        const si = (sy * this.width + Math.floor(x / k)) * 4;
        const di = (y * out.width + x) * 4;
        out.data[di] = this.data[si];
        out.data[di + 1] = this.data[si + 1];
        out.data[di + 2] = this.data[si + 2];
        out.data[di + 3] = this.data[si + 3];
      }
    }
    return out;
  }

  /// Crops to the drawn pixels plus a margin.
  trimmed(margin = 0) {
    let top = this.height, left = this.width, right = -1, bottom = -1;
    for (let y = 0; y < this.height; y++) {
      for (let x = 0; x < this.width; x++) {
        if (this.data[(y * this.width + x) * 4 + 3] === 0) continue;
        top = Math.min(top, y);
        bottom = Math.max(bottom, y);
        left = Math.min(left, x);
        right = Math.max(right, x);
      }
    }
    if (bottom < 0) return this;
    const out = new Pix(right - left + 1 + margin * 2, bottom - top + 1 + margin * 2);
    for (let y = top; y <= bottom; y++) {
      for (let x = left; x <= right; x++) {
        const si = (y * this.width + x) * 4;
        const di = ((y - top + margin) * out.width + (x - left + margin)) * 4;
        for (let k = 0; k < 4; k++) out.data[di + k] = this.data[si + k];
      }
    }
    return out;
  }

  png() {
    return encodePNG(this.width, this.height, this.data);
  }
}

// ----------------------------------------------------------------- font

// A small proportional bitmap font: cap height 7, two rows of descender.
// Rows are separated by "/", "#" is ink. Glyph width = row length.
const GLYPHS = {
  a: "...../...../.###./....#/.####/#...#/.####",
  b: "#..../#..../####./#...#/#...#/#...#/####.",
  c: "..../..../.###/#.../#.../#.../.###",
  d: "....#/....#/.####/#...#/#...#/#...#/.####",
  e: "...../...../.###./#...#/#####/#..../.###.",
  f: "..##/.#../####/.#../.#../.#../.#..",
  g: "...../...../.####/#...#/#...#/#...#/.####/....#/.###.",
  h: "#.../#.../###./#..#/#..#/#..#/#..#",
  i: "#/./#/#/#/#/#",
  j: "..#/.../..#/..#/..#/..#/..#/#.#/.#.",
  k: "#.../#.../#..#/#.#./##../#.#./#..#",
  l: "#./#./#./#./#./#./.#",
  m: "...../...../##.#./#.#.#/#.#.#/#.#.#/#.#.#",
  n: "..../..../###./#..#/#..#/#..#/#..#",
  o: "...../...../.###./#...#/#...#/#...#/.###.",
  p: "..../..../###./#..#/#..#/#..#/###./#.../#...",
  q: "..../..../.###/#..#/#..#/#..#/.###/...#/...#",
  r: "..../..../#.##/##../#.../#.../#...",
  s: "..../..../.###/#.../.##./...#/###.",
  t: ".#../.#../####/.#../.#../.#../..##",
  u: "..../..../#..#/#..#/#..#/#..#/.###",
  v: "...../...../#...#/#...#/#...#/.#.#./..#..",
  w: "...../...../#...#/#...#/#.#.#/#.#.#/.#.#.",
  x: "...../...../#...#/.#.#./..#../.#.#./#...#",
  y: "..../..../#..#/#..#/#..#/#..#/.###/...#/###.",
  z: "..../..../####/...#/..#./.#../####",
  A: ".###./#...#/#...#/#####/#...#/#...#/#...#",
  B: "####./#...#/#...#/####./#...#/#...#/####.",
  C: ".###./#...#/#..../#..../#..../#...#/.###.",
  D: "####./#...#/#...#/#...#/#...#/#...#/####.",
  E: "#####/#..../#..../####./#..../#..../#####",
  F: "#####/#..../#..../####./#..../#..../#....",
  G: ".###./#...#/#..../#.###/#...#/#...#/.####",
  H: "#...#/#...#/#...#/#####/#...#/#...#/#...#",
  I: "###/.#./.#./.#./.#./.#./###",
  J: "..###/...#./...#./...#./...#./#..#./.##..",
  K: "#...#/#..#./#.#../##.../#.#../#..#./#...#",
  L: "#.../#.../#.../#.../#.../#.../####",
  M: "#...#/##.##/#.#.#/#.#.#/#...#/#...#/#...#",
  N: "#...#/##..#/#.#.#/#..##/#...#/#...#/#...#",
  O: ".###./#...#/#...#/#...#/#...#/#...#/.###.",
  P: "####./#...#/#...#/####./#..../#..../#....",
  Q: ".###./#...#/#...#/#...#/#.#.#/#..#./.##.#",
  R: "####./#...#/#...#/####./#.#../#..#./#...#",
  S: ".####/#..../#..../.###./....#/....#/####.",
  T: "#####/..#../..#../..#../..#../..#../..#..",
  U: "#...#/#...#/#...#/#...#/#...#/#...#/.###.",
  V: "#...#/#...#/#...#/#...#/#...#/.#.#./..#..",
  W: "#...#/#...#/#...#/#.#.#/#.#.#/##.##/#...#",
  X: "#...#/#...#/.#.#./..#../.#.#./#...#/#...#",
  Y: "#...#/#...#/.#.#./..#../..#../..#../..#..",
  Z: "#####/....#/...#./..#../.#.../#..../#####",
  0: ".###./#...#/#..##/#.#.#/##..#/#...#/.###.",
  1: ".#./##./.#./.#./.#./.#./###",
  2: ".###./#...#/....#/...#./..#../.#.../#####",
  3: "####./....#/....#/.###./....#/....#/####.",
  4: "...#./..##./.#.#./#..#./#####/...#./...#.",
  5: "#####/#..../####./....#/....#/#...#/.###.",
  6: ".###./#..../#..../####./#...#/#...#/.###.",
  7: "#####/....#/...#./..#../.#.../.#.../.#...",
  8: ".###./#...#/#...#/.###./#...#/#...#/.###.",
  9: ".###./#...#/#...#/.####/....#/....#/.###.",
  ".": [".", ".", ".", ".", ".", ".", "#"].join("/"),
  ",": [".", ".", ".", ".", ".", ".", "#", "#"].join("/"),
  ":": [".", ".", "#", ".", ".", "#", "."].join("/"),
  "!": ["#", "#", "#", "#", "#", ".", "#"].join("/"),
  "'": ["#", "#", ".", ".", ".", ".", "."].join("/"),
  "·": [".", ".", ".", "#", ".", ".", "."].join("/"),
  "?": ".###./#...#/....#/...#./..#../...../..#..",
  "-": "..../..../..../####/..../..../....",
  "/": "....#/...#./...#./..#../.#.../.#.../#....",
  "&": ".##../#..#./#.#../.#.../#.#.#/#..#./.##.#",
  "+": "...../..#../..#../#####/..#../..#../.....",
  "%": "##..#/##.#./...#./..#../.#.../.#.##/#..##",
};

const SPACE = 3;

function glyph(ch) {
  const g = GLYPHS[ch] ?? GLYPHS[ch.toLowerCase()] ?? GLYPHS["?"];
  return g.split("/");
}

/// Width of a string in font pixels (before scaling).
export function textWidth(text) {
  let w = 0;
  for (const ch of text) w += (ch === " " ? SPACE : glyph(ch)[0].length) + 1;
  return Math.max(0, w - 1);
}

/// Draws text with its top-left corner at (x, y). Returns the width drawn.
export function drawText(pix, text, x, y, color, scale = 1) {
  let cx = x;
  for (const ch of text) {
    if (ch === " ") {
      cx += (SPACE + 1) * scale;
      continue;
    }
    const rows = glyph(ch);
    pix.paint(rows, { "#": color }, cx, y, scale);
    cx += (rows[0].length + 1) * scale;
  }
  return cx - x - scale;
}

// --------------------------------------------------------------- slime

// Shared/Art/Skin.swift: body, shade, ink, shine. Brand: warm, cool.
export const SKINS = {
  lime: ["#7DE67A", "#3DAA5A", "#143028", "#F0FFF0"],
  bubblegum: ["#FF9FCB", "#E0609A", "#3A1228", "#FFF0F7"],
  ocean: ["#7CC8FF", "#3A86D9", "#0F2240", "#EFF8FF"],
  grape: ["#B59BFF", "#7A5BE0", "#22164A", "#F4F0FF"],
  lava: ["#FF9A5C", "#E0532F", "#3A140A", "#FFF3E8"],
  gold: ["#FFD86B", "#E0A42F", "#3A2A08", "#FFFBEA"],
  ghost: ["#E8F0FF", "#A9B6D8", "#2A3048", "#FFFFFF"],
  void: ["#2A2540", "#1A1630", "#9B7BFF", "#D9CCFF"],
};
export const WARM = "#F07A9A";
export const COOL = "#7FD6F0";

export function skinColors(skin) {
  const [b, s, k, w] = SKINS[skin] ?? SKINS.lime;
  return { b, s, k, w, r: WARM, c: COOL };
}

export function paletteOf(sprite) {
  return Object.fromEntries(Object.entries(sprite.palette).map(([k, v]) => [k, v]));
}

/// Draws a slime frame (24 × 20) at (ox, oy).
export function drawSlime(pix, sprites, { stage = "young", anim = "idle", frame = 0, skin = "lime" } = {}, ox = 0, oy = 0, scale = 1) {
  const list = sprites.frames[`${stage}.${anim}`] ?? sprites.frames[`${stage}.idle`];
  pix.paint(list[frame % list.length], skinColors(skin), ox, oy, scale);
}

export function drawItem(pix, sprites, name, ox, oy, scale = 1) {
  const it = sprites.items[name];
  if (it) pix.paint(it.rows, paletteOf(it), ox, oy, scale);
}

export function drawIcon(pix, sprites, name, color, ox, oy, scale = 1) {
  const rows = sprites.emoteIcons[name];
  if (rows) pix.paint(rows, { "#": color }, ox, oy, scale);
}
