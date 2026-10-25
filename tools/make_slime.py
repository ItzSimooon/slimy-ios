#!/usr/bin/env python3
"""Draws the slime and bakes it into everything the app needs.

Outputs
  Widget/Fonts/Slime-<Stage>-<Anim>-<Layer>.ttf   island animation fonts
  Widget/Fonts/Acc-<item>-<Stage>-<Anim>.ttf       accessories that move with the slime
  Widget/Fonts/Emote-<reel>.ttf                    emote reels (see emotes.py)
  Widget/Info.plist                                widget Info.plist listing the fonts
  Shared/Sprites/sprites.json                      every sprite as data, for the app
  tools/out/*.png                                  contact sheets to check the art

How the island animation works
  Live Activities can't run code, but iOS keeps `Text(timerInterval:)` ticking
  every second on its own. In these fonts every digit is a whole animation
  frame and every glyph is exactly one frame wide. The island draws the timer
  right-aligned in a wide box and clips it to one glyph, so only the seconds
  digit shows and the frame changes once a second in a ten-step loop. One
  timer per colour layer, anchored to the same date, stacks into a colour
  slime. (iOS doesn't apply ligatures to timer text, so none are used.)
"""
import os
import plistlib
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from art_items import ACCESSORIES, FURNITURE, ITEMS  # noqa: E402
from emotes import ICONS, REELS  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W, H = 24, 20          # sprite grid in pixels
GROUND = H - 1         # bottom row the slime sits on
PX = 50                # font units per pixel (H * PX = 1000 = unitsPerEm)
CX = (W - 1) / 2       # horizontal centre

# Paint layers, in order (later wins). One char per pixel in SlimeSprites.
LAYERS = ["body", "shade", "ink", "shine", "warm", "cool"]
CODE = {"body": "b", "shade": "s", "ink": "k", "shine": "w", "warm": "r", "cool": "c"}

STAGES = {
    # name: (width, height, default eyes, crest)
    "baby": (14, 10, "big", False),
    "young": (18, 13, "open", False),
    "grown": (20, 15, "open", True),
}

# --------------------------------------------------------------- patterns

HEART = ["X.X", "XXX", ".X."]
BIG_HEART = [".X.X.", "XXXXX", "XXXXX", ".XXX.", "..X.."]
Z_SMALL = ["XXXX", "..X.", ".X..", "XXXX"]
Z_BIG = ["XXXXX", "...X.", "..X..", ".X...", "XXXXX"]
SPARKLE = [".X.", "XXX", ".X."]
DROP = [".X", "XX", "XX"]
APPLE = [".XX.", "XXXX", "XXXX", ".XX."]
BUBBLE = [".X.", "X.X", ".X."]
BATTERY = ["XXXXXX.", "X....XX", "X....XX", "XXXXXX."]
BATTERY_LOW = ["", ".X", ".X"]
BATTERY_FULL = ["", ".XXXX", ".XXXX"]
BOLT = ["..XX", ".XX.", "XXXX", ".XX.", "XX.."]
QUESTION = [".XX.", "X..X", "...X", "..X.", "....", "..X."]
EXCLAIM = ["X", "X", "X", ".", "X"]
NOTE = [".XX", ".X.", ".X.", "XX."]
PUFF = [".XX.", "XXXX", ".XX."]
ARM_UP = ["...kk", "..kbk", ".kbk.", "bbk..", "bk..."]
ARM_OUT = [".....", "..kkk", ".kbbk", "bbkk.", "bk..."]
CREST = ["..k..", ".kbk.", ".kbk.", "kbbbk"]


class Frame:
    def __init__(self):
        self.px = {}       # (x, y) -> layer
        self.shape = set()
        self.anchor = {}   # where accessories go

    def put(self, x, y, layer):
        if 0 <= x < W and 0 <= y < H:
            self.px[(x, y)] = layer

    def stamp(self, pattern, x0, y0, layer):
        for dy, row in enumerate(pattern):
            for dx, ch in enumerate(row):
                if ch == "X":
                    self.put(x0 + dx, y0 + dy, layer)

    def stamp_layers(self, pattern, x0, y0):
        """Pattern with k (ink) and b (body) characters."""
        for dy, row in enumerate(pattern):
            for dx, ch in enumerate(row):
                if ch in "kb":
                    self.put(x0 + dx, y0 + dy, "ink" if ch == "k" else "body")

    def rows(self):
        return ["".join(CODE[self.px[(x, y)]] if (x, y) in self.px else "." for x in range(W)) for y in range(H)]


def half_width(yy, w, h, e=2.4):
    t = (yy + 0.5) / h
    if t >= 1:
        return -1
    return (w / 2) * (1 - t ** e) ** (1 / e)


def slime(stage, dw=0, dh=0, lift=0, eyes=None, mouth="smile", look=0, blush=False, blush_wide=False,
          shadow=None, eye_dy=0):
    """Paints body, face and shading for a stage. Returns (frame, geometry)."""
    bw, bh, default_eyes, crest = STAGES[stage]
    w, h = max(8, bw + dw), max(5, bh + dh)
    eyes = eyes or default_eyes
    if eyes == "open" and stage == "baby":
        eyes = "big"
    f = Frame()
    ground = GROUND - lift
    shape = set()
    for y in range(H):
        yy = ground - y
        if yy < 0:
            continue
        hw = half_width(yy, w, h)
        for x in range(W):
            if abs(x + 0.5 - (CX + 0.5)) <= hw:
                shape.add((x, y))
    f.shape = shape
    for (x, y) in shape:
        edge = any((x + dx, y + dy) not in shape for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        yy = ground - y
        hw = half_width(yy, w, h)
        if edge:
            f.put(x, y, "ink")
        elif yy <= 1 or (x + 0.5 - (CX + 0.5) > hw - 2.6 and yy < h * 0.75):
            f.put(x, y, "shade")
        else:
            f.put(x, y, "body")

    top = min(y for (x, y) in shape) if shape else ground
    if crest:
        f.stamp_layers(CREST, round(CX) - 2, top - len(CREST) + 1)
        f.put(round(CX), top, "body")

    sx = round(CX - w * 0.28)
    for (dx, dy) in ((0, 0), (1, 0), (0, 1)):
        if (sx + dx, top + 2 + dy) in shape:
            f.put(sx + dx, top + 2 + dy, "shine")
    if (sx, top + 4) in shape:
        f.put(sx, top + 4, "shine")

    if lift > 0:
        sw = shadow or max(6, w - 4 - lift)
        for x in range(round(CX - sw / 2 + 0.5), round(CX + sw / 2 + 0.5)):
            f.put(x, GROUND, "shade")

    ey = ground - round(h * 0.52) + eye_dy
    exl = round(CX - w * 0.2) - 1 + look
    exr = round(CX + w * 0.2) + look
    for ex in (exl, exr):
        draw_eye(f, ex, ey, eyes)
    if blush or blush_wide:
        extra = 1 if blush_wide else 0
        for x in range(exl - 1 - extra, exl + 1):
            f.put(x, ey + 2, "warm")
        for x in range(exr + 1, exr + 3 + extra):
            f.put(x, ey + 2, "warm")
    my = ey + 3
    draw_mouth(f, round(CX) + look, my, mouth)

    right = max(x for (x, y) in shape) if shape else round(CX)
    left = min(x for (x, y) in shape) if shape else round(CX)
    neck = min(ground - 1, my + 2)
    f.anchor = {"top": top - (len(CREST) - 1 if crest else 0), "headTop": top, "ey": ey, "neck": neck,
                "look": look, "lift": lift}
    return f, {"top": top, "ground": ground, "ey": ey, "exl": exl, "exr": exr, "left": left, "right": right,
               "w": w, "h": h}


def draw_eye(f, ex, ey, kind):
    if kind == "open":
        for y in (ey - 1, ey, ey + 1):
            f.put(ex, y, "ink")
            f.put(ex + 1, y, "ink")
        f.put(ex, ey - 1, "shine")
    elif kind == "big":
        for y in (ey - 2, ey - 1, ey, ey + 1):
            f.put(ex, y, "ink")
            f.put(ex + 1, y, "ink")
        f.put(ex, ey - 2, "shine")
        f.put(ex + 1, ey + 1, "shine")
    elif kind == "blink":
        f.put(ex, ey + 1, "ink")
        f.put(ex + 1, ey + 1, "ink")
    elif kind == "happy":
        f.put(ex - 1, ey + 1, "ink")
        f.put(ex, ey, "ink")
        f.put(ex + 1, ey, "ink")
        f.put(ex + 2, ey + 1, "ink")
    elif kind == "closed":
        f.put(ex - 1, ey, "ink")
        f.put(ex, ey + 1, "ink")
        f.put(ex + 1, ey + 1, "ink")
        f.put(ex + 2, ey, "ink")
    elif kind == "sad":
        for (x, y) in ((ex, ey), (ex + 1, ey), (ex, ey + 1), (ex + 1, ey + 1)):
            f.put(x, y, "ink")
        f.put(ex, ey, "shine")
    elif kind == "flat":
        f.put(ex - 1, ey, "ink")
        f.put(ex, ey, "ink")
        f.put(ex + 1, ey, "ink")
        f.put(ex + 1, ey + 1, "ink")
    elif kind == "up":
        for y in (ey - 2, ey - 1):
            f.put(ex, y, "ink")
            f.put(ex + 1, y, "ink")
        f.put(ex + 1, ey - 2, "shine")


def draw_mouth(f, mx, my, kind):
    if kind == "smile":
        f.put(mx - 2, my, "ink")
        f.put(mx - 1, my + 1, "ink")
        f.put(mx, my + 1, "ink")
        f.put(mx + 1, my, "ink")
    elif kind == "grin":
        for x in range(mx - 2, mx + 2):
            f.put(x, my, "ink")
        f.put(mx - 1, my + 1, "ink")
        f.put(mx, my + 1, "ink")
        f.put(mx - 1, my + 1, "warm")
    elif kind == "open":
        for x in (mx - 1, mx):
            f.put(x, my, "ink")
            f.put(x, my + 1, "ink")
        f.put(mx - 1, my + 1, "warm")
    elif kind == "wide":
        for x in range(mx - 2, mx + 2):
            for y in (my - 1, my, my + 1):
                f.put(x, y, "ink")
        f.put(mx - 1, my + 1, "warm")
        f.put(mx, my + 1, "warm")
    elif kind == "chew":
        for x in range(mx - 2, mx + 2):
            f.put(x, my, "ink")
    elif kind == "frown":
        f.put(mx - 2, my + 1, "ink")
        f.put(mx - 1, my, "ink")
        f.put(mx, my, "ink")
        f.put(mx + 1, my + 1, "ink")
    elif kind == "dot":
        f.put(mx - 1, my, "ink")
    elif kind == "pout":
        f.put(mx - 1, my, "ink")
        f.put(mx, my, "ink")
        f.put(mx - 1, my + 1, "warm")


# ------------------------------------------------------------ animations
# Every animation takes the stage. Frame counts must divide 60.

def anim_idle(st):
    return [
        slime(st)[0],
        slime(st, 1, -1)[0],
        slime(st)[0],
        slime(st, -1, 1)[0],
        slime(st, eyes="blink")[0],
        slime(st, look=1)[0],
    ]


def anim_sad(st):
    frames = []
    for i, (dw, dh, eyes) in enumerate([(2, -2, "sad"), (2, -2, "sad"), (1, -2, "blink"), (2, -3, "sad")]):
        f, g = slime(st, dw, dh, eyes=eyes, mouth="frown")
        f.stamp(DROP, g["right"] - 2, g["top"] - 1 + i, "cool")
        if i >= 2:
            f.put(g["exl"], g["ey"] + 2 + (i - 2), "cool")
        frames.append(f)
    return frames


def anim_sleep(st):
    frames = []
    f, g = slime(st, 1, -2, eyes="closed", mouth="dot")
    f.stamp(Z_SMALL, g["right"] - 1, g["top"] - 3, "cool")
    frames.append(f)
    f, g = slime(st, 1, -1, eyes="closed", mouth="dot")
    f.stamp(Z_SMALL, g["right"], g["top"] - 5, "cool")
    f.put(g["right"] - 2, g["top"] - 1, "cool")
    frames.append(f)
    f, g = slime(st, 2, -2, eyes="closed", mouth="dot")
    f.stamp(Z_BIG, g["right"] - 1, max(0, g["top"] - 7), "cool")
    frames.append(f)
    f, g = slime(st, 1, -2, eyes="closed", mouth="dot")
    f.stamp(BUBBLE, round(CX) + 1, g["ey"] + 2, "cool")
    frames.append(f)
    return frames


def anim_eat(st):
    frames = []
    f, g = slime(st, eyes="big", mouth="wide")
    f.stamp(APPLE, round(CX) - 2, g["top"] - 6, "warm")
    f.put(round(CX), g["top"] - 7, "ink")
    frames.append(f)
    f, g = slime(st, -1, 1, eyes="big", mouth="wide")
    f.stamp(APPLE, round(CX) - 2, g["top"] - 4, "warm")
    f.put(round(CX), g["top"] - 5, "ink")
    frames.append(f)
    f, g = slime(st, 3, -1, eyes="happy", mouth="chew", blush=True)
    for (dx, dy) in ((-2, 2), (2, 1), (-3, 5), (3, 4)):
        f.put((g["left"] if dx < 0 else g["right"]) + dx, g["top"] + dy, "warm")
    frames.append(f)
    f, g = slime(st, eyes="happy", mouth="chew", blush=True)
    f.put(g["left"] - 1, g["top"] - 1, "warm")
    f.put(g["right"] + 1, g["top"] - 2, "warm")
    frames.append(f)
    return frames


def anim_play(st):
    frames = []
    f, g = slime(st, 3, -3, eyes="happy", mouth="open")
    frames.append(f)
    f, g = slime(st, -2, 1, lift=4, eyes="big", mouth="open")
    f.stamp(SPARKLE, g["left"] - 4, g["top"] + 1, "cool")
    f.stamp(SPARKLE, g["right"] + 1, g["top"] + 3, "cool")
    frames.append(f)
    f, g = slime(st, -1, 0, lift=2, eyes="happy", mouth="grin")
    f.stamp(SPARKLE, g["left"] - 3, g["top"] + 4, "cool")
    f.stamp(SPARKLE, g["right"], max(0, g["top"] - 3), "cool")
    frames.append(f)
    f, g = slime(st, 4, -3, eyes="happy", mouth="grin")
    f.put(g["left"] - 1, g["top"] + 1, "cool")
    f.put(g["right"] + 1, g["top"] + 2, "cool")
    frames.append(f)
    return frames


def anim_love(st):
    frames = []
    f, g = slime(st, eyes="happy", mouth="smile", blush=True)
    f.stamp(HEART, g["right"] - 1, g["top"] - 2, "warm")
    frames.append(f)
    f, g = slime(st, 1, -1, eyes="happy", mouth="smile", blush=True)
    f.stamp(BIG_HEART, g["right"] - 2, max(0, g["top"] - 6), "warm")
    f.stamp(HEART, g["left"] - 2, g["top"] + 1, "warm")
    frames.append(f)
    f, g = slime(st, eyes="happy", mouth="grin", blush=True)
    f.stamp(HEART, g["left"] - 3, max(0, g["top"] - 3), "warm")
    f.stamp(HEART, g["right"], max(0, g["top"] - 5), "warm")
    frames.append(f)
    f, g = slime(st, -1, 1, eyes="happy", mouth="smile", blush=True)
    f.stamp(BIG_HEART, g["left"] - 4, max(0, g["top"] - 5), "warm")
    frames.append(f)
    return frames


def anim_bath(st):
    frames = []
    shapes = [(0, 0, "happy", "grin"), (1, -1, "blink", "smile"), (-1, 1, "happy", "open"), (2, -1, "happy", "grin")]
    for i, (dw, dh, eyes, mouth) in enumerate(shapes):
        f, g = slime(st, dw, dh, eyes=eyes, mouth=mouth, blush=i % 2 == 0)
        spots = [(g["left"] - 3, g["top"] + 3 + i % 2), (g["right"] + 1 - i % 2, g["top"] - 2 + i),
                 (round(CX) - 5 + 3 * i, max(0, g["top"] - 6))]
        for j, (x, y) in enumerate(spots):
            f.stamp(BUBBLE if j != 1 else SPARKLE, x, y, "cool")
        for x in range(round(CX) - 3, round(CX) + 3):
            f.put(x, g["top"] - 1, "shine")
        f.put(round(CX) - 1, g["top"] - 2, "shine")
        f.put(round(CX) + 1, g["top"] - 2, "shine")
        frames.append(f)
    return frames


def anim_melt(st):
    frames = []
    for i, (dw, dh, eyes) in enumerate([(1, -1, "sad"), (3, -3, "sad"), (4, -5, "blink"), (5, -6, "sad")]):
        f, g = slime(st, dw, dh, eyes=eyes, mouth="frown")
        for x in range(g["left"] - i, g["right"] + i + 1):
            f.put(x, GROUND, "shade")
        f.stamp(BATTERY, round(CX) - 3, max(0, g["top"] - 6), "ink")
        f.stamp(BATTERY_LOW, round(CX) - 3, max(0, g["top"] - 6), "warm")
        f.stamp(DROP, g["right"] - 1 + i % 2, g["top"] + i % 2, "cool")
        frames.append(f)
    return frames


def anim_zap(st):
    frames = []
    f, g = slime(st, -1, 1, eyes="big", mouth="open")
    f.stamp(BOLT, round(CX) - 2, max(0, g["top"] - 6), "cool")
    frames.append(f)
    f, g = slime(st, -2, 1, lift=3, eyes="big", mouth="wide")
    f.stamp(SPARKLE, g["left"] - 3, g["top"] + 1, "cool")
    f.stamp(SPARKLE, g["right"] + 1, g["top"], "cool")
    f.stamp(BOLT, g["right"] + 1, g["top"] + 5, "cool")
    frames.append(f)
    f, g = slime(st, 2, -1, eyes="happy", mouth="grin", blush=True)
    f.stamp(BOLT, g["left"] - 4, g["top"] + 2, "cool")
    f.put(g["right"] + 1, g["top"], "cool")
    frames.append(f)
    f, g = slime(st, eyes="happy", mouth="grin")
    f.stamp(BATTERY, round(CX) - 3, max(0, g["top"] - 6), "ink")
    f.stamp(BATTERY_FULL, round(CX) - 3, max(0, g["top"] - 6), "cool")
    frames.append(f)
    return frames


def anim_confused(st):
    frames = []
    for i, look in enumerate([-1, 1, -1, 0]):
        f, g = slime(st, i % 2, -(i % 2), eyes="open" if i != 2 else "blink", mouth="chew", look=look)
        f.stamp(QUESTION, g["right"] - 2 + i % 2, max(0, g["top"] - 6 + i % 2), "shine")
        if i >= 2:
            f.stamp(QUESTION, g["left"] - 3, max(0, g["top"] - 4), "shine")
        frames.append(f)
    return frames


def anim_wave(st):
    frames = []
    for i in range(4):
        f, g = slime(st, eyes="happy", mouth="grin" if i % 2 else "smile", blush=True)
        row = g["ey"] + 2
        xs = [x for (x, y) in f.shape if y == row]
        right = max(xs) if xs else g["right"]
        arm = ARM_UP if i % 2 else ARM_OUT
        f.stamp_layers(arm, right - 1, row - len(arm) + 1)
        f.stamp(NOTE, max(0, g["left"] - 5), max(0, g["top"] - 2 + (i % 2) * 2), "shine")
        frames.append(f)
    return frames


def anim_celebrate(st):
    frames = []
    f, g = slime(st, 3, -3, eyes="happy", mouth="grin")
    frames.append(f)
    f, g = slime(st, -2, 2, lift=5, eyes="big", mouth="wide")
    for j, (x, y) in enumerate(((2, 2), (5, 0), (19, 1), (21, 4), (3, 6), (20, 8))):
        f.put(x, y, "warm" if j % 2 else "cool")
    frames.append(f)
    f, g = slime(st, -1, 1, lift=3, eyes="happy", mouth="grin", blush=True)
    for j, (x, y) in enumerate(((1, 4), (6, 2), (18, 3), (22, 6), (4, 9), (19, 10))):
        f.put(x, y, "cool" if j % 2 else "warm")
    frames.append(f)
    f, g = slime(st, 4, -3, eyes="happy", mouth="grin")
    f.stamp(SPARKLE, g["left"] - 3, g["top"] - 1, "cool")
    f.stamp(SPARKLE, g["right"] + 1, g["top"], "warm")
    frames.append(f)
    return frames


def anim_stretch(st):
    return [
        slime(st, -3, 3, eyes="closed", mouth="open")[0],
        slime(st, -2, 2, eyes="closed", mouth="open")[0],
        slime(st, 1, -1, eyes="blink", mouth="smile")[0],
        slime(st)[0],
    ]


def anim_sulk(st):
    frames = []
    for i in range(4):
        f, g = slime(st, 1, -1, eyes="flat" if i != 2 else "blink", mouth="pout", look=-1)
        if i == 1:
            f.stamp(PUFF, g["left"] - 4, g["ey"] + 1, "cool")
        if i == 3:
            f.put(g["left"] - 2, g["ey"] + 2, "cool")
        frames.append(f)
    return frames


def anim_hide(st):
    return [
        slime(st, 1, -2, eyes="open", mouth="dot")[0],
        slime(st, 3, -5, eyes="open", mouth="dot")[0],
        slime(st, 4, -7, eyes="flat", mouth="dot")[0],
        slime(st, 4, -7, eyes="flat", mouth="dot", look=1)[0],
    ]


def anim_curious(st):
    frames = []
    for i, look in enumerate([0, -1, 1, 0]):
        f, g = slime(st, -1 if i == 3 else 0, 1 if i == 3 else 0, eyes="big", mouth="dot", look=look)
        if i == 3:
            f.stamp(EXCLAIM, g["right"], max(0, g["top"] - 5), "shine")
        frames.append(f)
    return frames


def anim_blush(st):
    frames = []
    for i, dw in enumerate([1, 0, -1, 0]):
        f, g = slime(st, dw, -dw, eyes="happy", mouth="smile", blush_wide=True)
        if i == 2:
            f.stamp(HEART, g["right"], max(0, g["top"] - 3), "warm")
        frames.append(f)
    return frames


ANIM_FUNCS = {
    "idle": anim_idle, "sad": anim_sad, "sleep": anim_sleep, "eat": anim_eat, "play": anim_play,
    "love": anim_love, "bath": anim_bath, "melt": anim_melt, "zap": anim_zap, "confused": anim_confused,
    "wave": anim_wave, "celebrate": anim_celebrate, "stretch": anim_stretch, "sulk": anim_sulk,
    "hide": anim_hide, "curious": anim_curious, "blush": anim_blush,
}

ANIMS = {stage: {name: fn(stage) for name, fn in ANIM_FUNCS.items()} for stage in STAGES}
for _stage, _anims in ANIMS.items():
    for _name, _frames in _anims.items():
        assert 60 % len(_frames) == 0, (_stage, _name)


def ten_step(n):
    """Which frame each final seconds digit 0-9 shows (a ten-second loop)."""
    if n == 6:
        return [0, 1, 2, 3, 0, 1, 2, 3, 4, 5]
    if n == 4:
        return [0, 1, 2, 3, 0, 1, 2, 3, 0, 0]
    return [i % n for i in range(10)]


# ------------------------------------------------------------- accessories

def place_accessory(item, frame):
    """Pixels {(x, y): palette key} of an accessory on a frame."""
    kind, palette, rows = ACCESSORIES[item]
    a = frame.anchor
    out = {}
    if kind == "hat":
        x0 = round(CX) - len(rows[0]) // 2
        y0 = a["top"] - len(rows) + 1
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch != ".":
                    out[(x0 + dx, y0 + dy)] = ch
    elif kind == "glasses":
        x0 = round(CX) - len(rows[0]) // 2 + a["look"]
        y0 = a["ey"] - 1
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch != ".":
                    out[(x0 + dx, y0 + dy)] = ch
    elif kind == "scarf":
        for i, y in enumerate((a["neck"], a["neck"] + 1)):
            xs = [x for (x, yy) in frame.shape if yy == y]
            if not xs:
                continue
            for x in range(min(xs), max(xs) + 1):
                out[(x, y)] = "a" if (x + i) % 3 else "b"
        xs = [x for (x, yy) in frame.shape if yy == a["neck"]]
        if xs:
            tail = min(xs) + 3
            out[(tail, a["neck"] + 2)] = "a"
            out[(tail, a["neck"] + 3)] = "b"
    return {(x, y): c for (x, y), c in out.items() if 0 <= x < W and 0 <= y < H}


def accessory_primary(item):
    """Colour used for the one-colour island version."""
    kind, palette, rows = ACCESSORIES[item]
    counts = {}
    for row in rows:
        for ch in row:
            if ch not in ".kw":
                counts[ch] = counts.get(ch, 0) + 1
    if not counts:
        return palette.get("a", palette.get("k"))
    return palette[max(counts, key=counts.get)]


# ------------------------------------------------------------------ fonts

DIGITS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
BLANKS = {
    "space": [" ", " ", " ", " "],
    "colon": [":", "∶"],
    "period": ["."],
    "comma": [","],
}


def pixels_glyph(pixels, px, height):
    """Glyph from a set of (x, y) pixels, merging horizontal runs."""
    pen = TTGlyphPen(None)
    rows = {}
    for (x, y) in pixels:
        rows.setdefault(y, []).append(x)
    for y, xs in rows.items():
        xs.sort()
        start = prev = xs[0]
        for x in xs[1:] + [None]:
            if x is not None and x == prev + 1:
                prev = x
                continue
            top = (height - y) * px
            pen.moveTo((start * px, top - px))
            pen.lineTo((start * px, top))
            pen.lineTo(((prev + 1) * px, top))
            pen.lineTo(((prev + 1) * px, top - px))
            pen.closePath()
            if x is not None:
                start = prev = x
    return pen.glyph()


def build_digit_font(ps_name, family, glyph_pixels, px, cols, rows, path):
    """A font whose digits 0-9 are the given pixel sets; every glyph one frame wide."""
    order = [".notdef"] + list(BLANKS) + DIGITS
    glyphs = {g: TTGlyphPen(None).glyph() for g in order}
    for d, name in enumerate(DIGITS):
        glyphs[name] = pixels_glyph(glyph_pixels[d], px, rows)
    fb = FontBuilder(rows * px, isTTF=True)
    fb.setupGlyphOrder(order)
    cmap = {ord(str(d)): DIGITS[d] for d in range(10)}
    for glyph, chars in BLANKS.items():
        for ch in chars:
            cmap[ord(ch)] = glyph
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(glyphs)
    glyf = fb.font["glyf"]
    hmtx = {}
    for g in order:
        glyph = glyf[g]
        glyph.recalcBounds(glyf)
        lsb = getattr(glyph, "xMin", 0) if glyph.numberOfContours else 0
        hmtx[g] = (cols * px, lsb)
    fb.setupHorizontalMetrics(hmtx)
    fb.setupHorizontalHeader(ascent=rows * px, descent=0)
    fb.setupNameTable({
        "familyName": family, "styleName": "Regular", "uniqueFontIdentifier": ps_name,
        "fullName": family, "psName": ps_name, "version": "Version 3.000",
    })
    fb.setupOS2(sTypoAscender=rows * px, sTypoDescender=0, sTypoLineGap=0,
                usWinAscent=rows * px, usWinDescent=0, fsType=0)
    fb.setupPost()
    fb.setupHead(unitsPerEm=rows * px)
    fb.font.save(path)


def cap(s):
    return s[0].upper() + s[1:]


def acc_key(item):
    return item.replace(".", "")


# ------------------------------------------------------------------ swift

def write_sprites_json(path):
    """All sprite data for the app and widget. Loaded once at runtime; a huge
    Swift literal would make the compiler crawl."""
    import json
    data = {
        "width": W,
        "height": H,
        "frames": {f"{stage}.{name}": [fr.rows() for fr in frames]
                   for stage, anims in ANIMS.items() for name, frames in anims.items()},
        "anchors": {f"{stage}.{name}": [[fr.anchor["top"], fr.anchor["ey"], fr.anchor["neck"], fr.anchor["look"]]
                                        for fr in frames]
                    for stage, anims in ANIMS.items() for name, frames in anims.items()},
        "accessories": {item: {"kind": kind, "palette": {k: v for k, v in palette.items()}, "rows": rows,
                               "island": accessory_primary(item)}
                        for item, (kind, palette, rows) in ACCESSORIES.items()},
        "items": {name: {"palette": {k: v for k, v in palette.items()}, "rows": rows}
                  for name, (palette, rows) in list(ITEMS.items()) + list(FURNITURE.items())},
        "emoteIcons": ICONS,
        "emoteReels": REELS,
    }
    for name, (kind, palette, rows) in ACCESSORIES.items():
        shop_rows = rows if rows else ["aabaabaabaab", "baabaabaabaa", ".a..........", ".b.........."]
        data["items"][name] = {"palette": dict(palette), "rows": shop_rows}
    with open(path, "w") as fh:
        json.dump(data, fh, separators=(",", ":"), sort_keys=True)


def write_widget_plist(path, font_files):
    plist = {
        "CFBundleDevelopmentRegion": "de",
        "CFBundleDisplayName": "Slimy",
        "CFBundleExecutable": "$(EXECUTABLE_NAME)",
        "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)",
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": "$(PRODUCT_NAME)",
        "CFBundlePackageType": "$(PRODUCT_BUNDLE_PACKAGE_TYPE)",
        "CFBundleShortVersionString": "$(MARKETING_VERSION)",
        "CFBundleVersion": "$(CURRENT_PROJECT_VERSION)",
        "NSExtension": {"NSExtensionPointIdentifier": "com.apple.widgetkit-extension"},
        "UIAppFonts": font_files + ["PixelifySans-Regular.ttf", "PixelifySans-Bold.ttf"],
    }
    with open(path, "wb") as fh:
        plistlib.dump(plist, fh)


# ---------------------------------------------------------------- previews

PREVIEW = {"b": (125, 230, 122), "s": (61, 170, 90), "k": (20, 48, 40),
           "w": (240, 255, 240), "r": (240, 122, 154), "c": (127, 214, 240)}


def rgb(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


def write_sheet(path, stage, scale=6, accessories=()):
    from PIL import Image, ImageDraw
    pad = 4
    anims = ANIMS[stage]
    cols = max(len(f) for f in anims.values())
    img = Image.new("RGB", (80 + cols * (W * scale + pad), len(anims) * (H * scale + pad) + pad), (16, 20, 17))
    d = ImageDraw.Draw(img)
    for r, (name, frames) in enumerate(anims.items()):
        oy = pad + r * (H * scale + pad)
        d.text((4, oy + 4), name, fill=(200, 210, 200))
        for c, fr in enumerate(frames):
            ox = 80 + c * (W * scale + pad)
            d.rectangle([ox, oy, ox + W * scale - 1, oy + H * scale - 1], fill=(0, 0, 0))
            for (x, y), layer in fr.px.items():
                d.rectangle([ox + x * scale, oy + y * scale, ox + (x + 1) * scale - 1, oy + (y + 1) * scale - 1],
                            fill=PREVIEW[CODE[layer]])
            for item in accessories:
                pal = ACCESSORIES[item][1]
                for (x, y), ch in place_accessory(item, fr).items():
                    d.rectangle([ox + x * scale, oy + y * scale, ox + (x + 1) * scale - 1, oy + (y + 1) * scale - 1],
                                fill=rgb(pal[ch]))
    img.save(path)


def write_items_sheet(path):
    from PIL import Image, ImageDraw
    allitems = list(ITEMS.items()) + list(FURNITURE.items()) + [(k, (v[1], v[2])) for k, v in ACCESSORIES.items() if v[2]]
    cell = 110
    per_row = 8
    rows_n = (len(allitems) + per_row - 1) // per_row
    img = Image.new("RGB", (per_row * cell + 10, rows_n * cell + 10), (16, 20, 17))
    d = ImageDraw.Draw(img)
    for i, (name, (pal, rows)) in enumerate(allitems):
        ox, oy = 5 + (i % per_row) * cell, 5 + (i // per_row) * cell
        s = max(2, min(8, (cell - 24) // max(len(rows[0]), len(rows))))
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in pal:
                    d.rectangle([ox + x * s, oy + y * s, ox + (x + 1) * s - 1, oy + (y + 1) * s - 1], fill=rgb(pal[ch]))
        d.text((ox, oy + cell - 16), name[:16], fill=(180, 190, 180))
    img.save(path)


def write_emote_sheet(path, scale=5):
    from PIL import Image, ImageDraw
    cell = 9 * scale + 6
    img = Image.new("RGB", (90 + 10 * cell, len(REELS) * cell + 6), (16, 20, 17))
    d = ImageDraw.Draw(img)
    for r, (name, reel) in enumerate(REELS.items()):
        d.text((4, 6 + r * cell + 14), name, fill=(200, 210, 200))
        for c, icon in enumerate(reel):
            ox, oy = 90 + c * cell, 6 + r * cell
            for y, row in enumerate(ICONS[icon]):
                for x, ch in enumerate(row):
                    if ch == "#":
                        d.rectangle([ox + x * scale, oy + y * scale, ox + (x + 1) * scale - 1, oy + (y + 1) * scale - 1],
                                    fill=(240, 122, 154))
    img.save(path)


# --------------------------------------------------------------------- main

def main():
    fonts_dir = os.path.join(ROOT, "Widget", "Fonts")
    os.makedirs(fonts_dir, exist_ok=True)
    for old in os.listdir(fonts_dir):
        if old.endswith(".ttf"):
            os.remove(os.path.join(fonts_dir, old))
    files = []

    def save(ps_name, family, digit_pixels, px, cols, rows):
        build_digit_font(ps_name, family, digit_pixels, px, cols, rows, os.path.join(fonts_dir, ps_name + ".ttf"))
        files.append(ps_name + ".ttf")

    for stage, anims in ANIMS.items():
        for name, frames in anims.items():
            seq = ten_step(len(frames))
            for layer in LAYERS:
                digit_pixels = [{p for p, l in frames[seq[d]].px.items() if l == layer} for d in range(10)]
                save(f"Slime-{cap(stage)}-{cap(name)}-{cap(layer)}", f"Slime {stage} {name} {layer}",
                     digit_pixels, PX, W, H)
            for item, (kind, _, _) in ACCESSORIES.items():
                placed = [place_accessory(item, fr) for fr in frames]
                # One colour in the island; highlights are left out so shapes still read.
                digit_pixels = [{p for p, c in placed[seq[d]].items() if c != "w"} for d in range(10)]
                save(f"Acc-{acc_key(item)}-{cap(stage)}-{cap(name)}", f"Acc {item} {stage} {name}",
                     digit_pixels, PX, W, H)

    for reel, icons in REELS.items():
        digit_pixels = []
        for d in range(10):
            rows = ICONS[icons[d]]
            digit_pixels.append({(x, y) for y, row in enumerate(rows) for x, ch in enumerate(row) if ch == "#"})
        save(f"Emote-{reel}", f"Emote {reel}", digit_pixels, 100, 9, 9)

    write_widget_plist(os.path.join(ROOT, "Widget", "Info.plist"), files)
    os.makedirs(os.path.join(ROOT, "Shared", "Sprites"), exist_ok=True)
    write_sprites_json(os.path.join(ROOT, "Shared", "Sprites", "sprites.json"))
    out = os.path.join(ROOT, "tools", "out")
    os.makedirs(out, exist_ok=True)
    for stage in STAGES:
        write_sheet(os.path.join(out, f"sheet-{stage}.png"), stage)
    write_sheet(os.path.join(out, "sheet-accessories.png"), "young",
                accessories=("hat.party", "glasses.round", "scarf.red"))
    write_items_sheet(os.path.join(out, "items.png"))
    write_emote_sheet(os.path.join(out, "emotes.png"))
    print(f"{len(files)} fonts, {sum(len(a) for a in ANIMS.values())} animations over {len(STAGES)} stages")


if __name__ == "__main__":
    sys.exit(main())
