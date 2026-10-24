#!/usr/bin/env python3
"""Draws Blob the slime and bakes it into everything the app needs.

Outputs
  Widget/Fonts/Slime-<Combo>-<Layer>.ttf   animation fonts for the Live Activity
  Widget/Info.plist                         widget Info.plist listing the fonts
  Shared/SlimeSprites.swift                 the same frames as data, for the app
  tools/out/sheet.png                       a contact sheet to eyeball the art

How the animation works
  Live Activities can't run code, but iOS keeps `Text(timerInterval:)` ticking
  every second on its own. Each font here hides every digit and colon, and uses
  ligatures to swap the trailing ":SS" for frame (SS mod frameCount). One timer
  per colour layer, all anchored to the same date, stack into a colour slime.

  A font is a (reaction, mood) combo. During the first minute after the anchor
  the timer reads "0:SS"; that window plays the reaction (eating, playing,
  love). After that it falls back to the mood loop (idle, sad, sleep).
"""
import math
import os
import plistlib
import sys

from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W, H = 24, 20          # sprite grid in pixels
GROUND = H - 1         # bottom row the slime sits on
PX = 50                # font units per pixel (H * PX = 1000 = unitsPerEm)
CX = (W - 1) / 2       # horizontal centre

# Pixel codes, in paint order (later wins). One char per pixel in SlimeSprites.
LAYERS = ["body", "shade", "ink", "shine", "warm", "cool"]
CODE = {"body": "b", "shade": "s", "ink": "k", "shine": "w", "warm": "r", "cool": "c"}

# ---------------------------------------------------------------- sprites

HEART = ["X.X", "XXX", ".X."]
BIG_HEART = [".X.X.", "XXXXX", "XXXXX", ".XXX.", "..X.."]
Z_SMALL = ["XXXX", "..X.", ".X..", "XXXX"]
Z_BIG = ["XXXXX", "...X.", "..X..", ".X...", "XXXXX"]
SPARKLE = [".X.", "XXX", ".X."]
DROP = [".X", "XX", "XX"]
APPLE = [".XX.", "XXXX", "XXXX", ".XX."]
BUBBLE = [".X.", "X.X", ".X."]
BATTERY_LOW = ["XXXXXX.", "X....XX", "X....XX", "XXXXXX."]
BATTERY_FILL_LOW = ["", ".X", ".X"]
BATTERY_FILL_FULL = ["", ".XXXX", ".XXXX"]
BOLT = ["..XX", ".XX.", "XXXX", ".XX.", "XX.."]
QUESTION = [".XX.", "X..X", "...X", "..X.", "....", "..X."]
NOTE = [".XX", ".X.", ".X.", "XX."]
ARM_UP = ["...kk", "..kbk", ".kbk.", "bbk..", "bk..."]
ARM_OUT = [".....", "..kkk", ".kbbk", "bbkk.", "bk..."]


class Frame:
    def __init__(self):
        self.px = {}  # (x, y) -> layer

    def put(self, x, y, layer):
        if 0 <= x < W and 0 <= y < H:
            self.px[(x, y)] = layer

    def stamp(self, pattern, x0, y0, layer):
        for dy, row in enumerate(pattern):
            for dx, ch in enumerate(row):
                if ch == "X":
                    self.put(x0 + dx, y0 + dy, layer)

    def rows(self):
        out = []
        for y in range(H):
            out.append("".join(CODE[self.px[(x, y)]] if (x, y) in self.px else "." for x in range(W)))
        return out


def half_width(yy, w, h, e=2.4):
    t = (yy + 0.5) / h
    if t >= 1:
        return -1
    return (w / 2) * (1 - t ** e) ** (1 / e)


def slime(w=18, h=13, lift=0, eyes="open", mouth="smile", look=0, blush=False, shadow=None):
    """Paint the body, face and shading. Returns (frame, geometry)."""
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

    top = ground - h + 1
    # shine: a soft highlight on the upper left
    sx = round(CX - w * 0.28)
    for (dx, dy) in ((0, 0), (1, 0), (0, 1)):
        if (sx + dx, top + 2 + dy) in shape:
            f.put(sx + dx, top + 2 + dy, "shine")
    if (sx, top + 4) in shape:
        f.put(sx, top + 4, "shine")

    # ground shadow while airborne
    if lift > 0:
        sw = shadow or max(6, w - 4 - lift)
        for x in range(round(CX - sw / 2 + 0.5), round(CX + sw / 2 + 0.5)):
            f.put(x, GROUND, "shade")

    ey = ground - round(h * 0.52)
    exl = round(CX - w * 0.2) - 1 + look
    exr = round(CX + w * 0.2) + look
    for ex in (exl, exr):
        draw_eye(f, ex, ey, eyes)
    if blush:
        f.put(exl - 1, ey + 2, "warm")
        f.put(exl, ey + 2, "warm")
        f.put(exr + 1, ey + 2, "warm")
        f.put(exr + 2, ey + 2, "warm")
    draw_mouth(f, round(CX) + look, ey + 3, mouth)
    return f, {"top": top, "ground": ground, "ey": ey, "exl": exl, "exr": exr}


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
    elif kind == "happy":  # ^ ^
        f.put(ex - 1, ey + 1, "ink")
        f.put(ex, ey, "ink")
        f.put(ex + 1, ey, "ink")
        f.put(ex + 2, ey + 1, "ink")
    elif kind == "closed":  # sleepy u
        f.put(ex - 1, ey, "ink")
        f.put(ex, ey + 1, "ink")
        f.put(ex + 1, ey + 1, "ink")
        f.put(ex + 2, ey, "ink")
    elif kind == "sad":  # droopy, looking down, with brow
        f.put(ex, ey, "ink")
        f.put(ex + 1, ey, "ink")
        f.put(ex, ey + 1, "ink")
        f.put(ex + 1, ey + 1, "ink")
        f.put(ex, ey, "shine")


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


# ------------------------------------------------------------ animations
# Each list holds frames; the frame count must divide 60 so loops are seamless.

def anim_idle():
    return [
        slime()[0],
        slime(w=19, h=12)[0],
        slime()[0],
        slime(w=17, h=14)[0],
        slime(eyes="blink")[0],
        slime(look=1)[0],
    ]


def anim_sad():
    frames = []
    for i, (w, h, eyes) in enumerate([(20, 11, "sad"), (20, 11, "sad"), (19, 11, "blink"), (20, 10, "sad")]):
        f, g = slime(w=w, h=h, eyes=eyes, mouth="frown")
        f.stamp(DROP, math.ceil(CX) + 7, g["top"] - 1 + i, "cool")
        if i >= 2:
            f.put(g["exl"], g["ey"] + 2 + (i - 2), "cool")
        frames.append(f)
    return frames


def anim_sleep():
    frames = []
    f, g = slime(w=19, h=11, eyes="closed", mouth="dot")
    f.stamp(Z_SMALL, 18, g["top"] - 3, "cool")
    frames.append(f)
    f, g = slime(w=19, h=12, eyes="closed", mouth="dot")
    f.stamp(Z_SMALL, 19, g["top"] - 5, "cool")
    f.put(17, g["top"] - 1, "cool")
    frames.append(f)
    f, g = slime(w=20, h=11, eyes="closed", mouth="dot")
    f.stamp(Z_BIG, 18, 0, "cool")
    frames.append(f)
    f, g = slime(w=19, h=11, eyes="closed", mouth="dot")
    f.stamp(BUBBLE, round(CX) + 1, g["ey"] + 2, "cool")
    frames.append(f)
    return frames


def anim_eat():
    frames = []
    f, g = slime(eyes="big", mouth="wide")
    f.stamp(APPLE, round(CX) - 2, g["top"] - 6, "warm")
    f.put(round(CX), g["top"] - 7, "ink")
    frames.append(f)
    f, g = slime(w=17, h=14, eyes="big", mouth="wide")
    f.stamp(APPLE, round(CX) - 2, g["top"] - 4, "warm")
    f.put(round(CX), g["top"] - 5, "ink")
    frames.append(f)
    f, g = slime(w=21, h=12, eyes="happy", mouth="chew", blush=True)
    for (x, y) in ((2, 8), (21, 7), (1, 12), (22, 11)):
        f.put(x, y, "warm")
    frames.append(f)
    f, g = slime(eyes="happy", mouth="chew", blush=True)
    f.put(3, 5, "warm")
    f.put(20, 4, "warm")
    frames.append(f)
    return frames


def anim_play():
    frames = []
    f, g = slime(w=21, h=10, eyes="happy", mouth="open")
    frames.append(f)
    f, g = slime(w=16, h=14, lift=4, eyes="big", mouth="open")
    f.stamp(SPARKLE, 0, 3, "cool")
    f.stamp(SPARKLE, 20, 6, "cool")
    frames.append(f)
    f, g = slime(w=17, h=13, lift=2, eyes="happy", mouth="grin")
    f.stamp(SPARKLE, 1, 8, "cool")
    f.stamp(SPARKLE, 20, 1, "cool")
    f.put(0, 2, "cool")
    frames.append(f)
    f, g = slime(w=22, h=10, eyes="happy", mouth="grin")
    f.put(0, 8, "cool")
    f.put(23, 9, "cool")
    frames.append(f)
    return frames


def anim_love():
    frames = []
    f, g = slime(eyes="happy", mouth="smile", blush=True)
    f.stamp(HEART, 20, g["top"] - 1, "warm")
    frames.append(f)
    f, g = slime(w=19, h=12, eyes="happy", mouth="smile", blush=True)
    f.stamp(BIG_HEART, 19, 0, "warm")
    f.stamp(HEART, 1, 4, "warm")
    frames.append(f)
    f, g = slime(eyes="happy", mouth="grin", blush=True)
    f.stamp(HEART, 0, 1, "warm")
    f.stamp(HEART, 21, 0, "warm")
    frames.append(f)
    f, g = slime(w=17, h=14, eyes="happy", mouth="smile", blush=True)
    f.stamp(BIG_HEART, 0, 0, "warm")
    frames.append(f)
    return frames


def anim_bath():
    frames = []
    spots = [
        [(1, 10), (20, 4), (5, 2)],
        [(2, 6), (19, 9), (9, 0)],
        [(0, 3), (21, 6), (15, 1)],
        [(3, 1), (20, 12), (12, 2)],
    ]
    shapes = [(18, 13, "happy", "grin"), (19, 12, "blink", "smile"), (17, 14, "happy", "open"), (20, 12, "happy", "grin")]
    for i, (w, h, eyes, mouth) in enumerate(shapes):
        f, g = slime(w=w, h=h, eyes=eyes, mouth=mouth, blush=i % 2 == 0)
        for j, (x, y) in enumerate(spots[i]):
            f.stamp(BUBBLE if j != 1 else SPARKLE, x, y, "cool")
        # foam on the head
        for x in range(round(CX) - 3, round(CX) + 3):
            f.put(x, g["top"] - 1, "shine")
        f.put(round(CX) - 1, g["top"] - 2, "shine")
        f.put(round(CX) + 1, g["top"] - 2, "shine")
        frames.append(f)
    return frames


def anim_melt():
    frames = []
    shapes = [(19, 12, "sad"), (21, 10, "sad"), (22, 8, "blink"), (23, 7, "sad")]
    for i, (w, h, eyes) in enumerate(shapes):
        f, g = slime(w=w, h=h, eyes=eyes, mouth="frown")
        # puddle spreading out on the ground
        for x in range(round(CX - w / 2) - i, round(CX + w / 2) + i + 1):
            f.put(x, GROUND, "shade")
        f.stamp(BATTERY_LOW, round(CX) - 3, max(0, g["top"] - 6), "ink")
        f.stamp(BATTERY_FILL_LOW, round(CX) - 3, max(0, g["top"] - 6), "warm")
        f.stamp(DROP, math.ceil(CX) + 6 + (i % 2), g["top"] + i % 2, "cool")
        frames.append(f)
    return frames


def anim_zap():
    frames = []
    f, g = slime(w=17, h=14, eyes="big", mouth="open")
    f.stamp(BOLT, round(CX) - 2, max(0, g["top"] - 6), "cool")
    frames.append(f)
    f, g = slime(w=16, h=14, lift=3, eyes="big", mouth="wide")
    f.stamp(SPARKLE, 1, 4, "cool")
    f.stamp(SPARKLE, 20, 3, "cool")
    f.stamp(BOLT, 19, 9, "cool")
    frames.append(f)
    f, g = slime(w=20, h=12, eyes="happy", mouth="grin", blush=True)
    f.stamp(BOLT, 1, 6, "cool")
    f.put(21, 5, "cool")
    frames.append(f)
    f, g = slime(eyes="happy", mouth="grin")
    f.stamp(BATTERY_LOW, round(CX) - 3, g["top"] - 6, "ink")
    f.stamp(BATTERY_FILL_FULL, round(CX) - 3, g["top"] - 6, "cool")
    frames.append(f)
    return frames


def anim_confused():
    frames = []
    for i, look in enumerate([-1, 1, -1, 0]):
        f, g = slime(w=18 + (i % 2), h=13 - (i % 2), eyes="open" if i != 2 else "blink", mouth="chew", look=look)
        f.stamp(QUESTION, 18 + (i % 2), max(0, g["top"] - 5 + (i % 2)), "shine")
        if i >= 2:
            f.stamp(QUESTION, 2, max(0, g["top"] - 3), "shine")
        frames.append(f)
    return frames


def anim_wave():
    frames = []
    for i in range(4):
        f, g = slime(eyes="happy", mouth="grin" if i % 2 else "smile", blush=True)
        # a little arm bubbling out of the right side, waving up and down
        row = g["ey"] + 2
        right = max(x for (x, y), layer in f.px.items() if y == row and layer != "cool")
        up = i % 2
        arm = ARM_UP if up else ARM_OUT
        for dy, line in enumerate(arm):
            for dx, ch in enumerate(line):
                if ch in "kb":
                    f.put(right - 1 + dx, row - len(arm) + 1 + dy, "ink" if ch == "k" else "body")
        f.stamp(NOTE, 1, 3 + up * 2, "shine")
        if i >= 2:
            f.put(5, 1 + up, "shine")
        frames.append(f)
    return frames


ANIMS = {
    "idle": anim_idle(),
    "sad": anim_sad(),
    "sleep": anim_sleep(),
    "eat": anim_eat(),
    "play": anim_play(),
    "love": anim_love(),
    "bath": anim_bath(),
    "melt": anim_melt(),
    "zap": anim_zap(),
    "confused": anim_confused(),
    "wave": anim_wave(),
}
for name, frames in ANIMS.items():
    assert 60 % len(frames) == 0, name

MOODS = ["idle", "sad", "sleep"]
REACTIONS = ["none", "eat", "play", "love", "bath", "melt", "zap", "confused", "wave"]


def combos():
    for r in REACTIONS:
        for m in MOODS:
            if r != "none" and m == "sleep":
                continue  # actions wake it up, so a reaction never plays over sleep
            yield r, m


def combo_name(r, m):
    return m.capitalize() if r == "none" else r.capitalize() + m.capitalize()


# ------------------------------------------------------------------ fonts

DIGITS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]


def frame_glyph(frame, layer):
    pen = TTGlyphPen(None)
    rows = frame.rows()
    code = CODE[layer]
    for y, row in enumerate(rows):
        x = 0
        while x < W:
            if row[x] == code:
                x0 = x
                while x < W and row[x] == code:
                    x += 1
                top = (H - y) * PX
                bottom = top - PX
                pen.moveTo((x0 * PX, bottom))
                pen.lineTo((x0 * PX, top))
                pen.lineTo((x * PX, top))
                pen.lineTo((x * PX, bottom))
                pen.closePath()
            else:
                x += 1
    return pen.glyph()


def empty_glyph():
    return TTGlyphPen(None).glyph()


def build_font(reaction, mood, layer, path):
    mood_frames = ANIMS[mood]
    react_frames = ANIMS[reaction] if reaction != "none" else None
    combo = combo_name(reaction, mood)
    ps_name = f"Slime-{combo}-{layer.capitalize()}"

    order = [".notdef", "space", "colon", "colon.hide", "zero.react"] + DIGITS
    order += [f"m{i}" for i in range(len(mood_frames))]
    if react_frames:
        order += [f"r{i}" for i in range(len(react_frames))]

    glyphs, metrics = {}, {}
    for g in order:
        glyphs[g] = empty_glyph()
        metrics[g] = (0, 0)
    for i, fr in enumerate(mood_frames):
        glyphs[f"m{i}"] = frame_glyph(fr, layer)
        metrics[f"m{i}"] = (W * PX, 0)
    if react_frames:
        for i, fr in enumerate(react_frames):
            glyphs[f"r{i}"] = frame_glyph(fr, layer)
            metrics[f"r{i}"] = (W * PX, 0)
    metrics[".notdef"] = (0, 0)

    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(order)
    cmap = {ord(str(d)): DIGITS[d] for d in range(10)}
    cmap[ord(":")] = "colon"
    cmap[ord(" ")] = "space"
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(glyphs)
    # glyf needs bounds before hmtx lsb is meaningful
    glyf = fb.font["glyf"]
    hmtx = {}
    for g in order:
        glyph = glyf[g]
        glyph.recalcBounds(glyf)
        lsb = getattr(glyph, "xMin", 0) if glyph.numberOfContours else 0
        hmtx[g] = (metrics[g][0], lsb)
    fb.setupHorizontalMetrics(hmtx)
    fb.setupHorizontalHeader(ascent=H * PX, descent=0)
    fb.setupNameTable({
        "familyName": f"Slime {combo} {layer.capitalize()}",
        "styleName": "Regular",
        "uniqueFontIdentifier": ps_name,
        "fullName": f"Slime {combo} {layer.capitalize()}",
        "psName": ps_name,
        "version": "Version 1.000",
    })
    fb.setupOS2(sTypoAscender=H * PX, sTypoDescender=0, sTypoLineGap=0,
                usWinAscent=H * PX, usWinDescent=0, fsType=0)
    fb.setupPost()
    fb.setupHead(unitsPerEm=1000)

    secs = [f"{s:02d}" for s in range(60)]
    lig_rules = []
    if react_frames:
        for s in secs:
            lig_rules.append(f"    sub zero.react colon {DIGITS[int(s[0])]} {DIGITS[int(s[1])]} by r{int(s) % len(react_frames)};")
    for s in secs:
        lig_rules.append(f"    sub colon {DIGITS[int(s[0])]} {DIGITS[int(s[1])]} by m{int(s) % len(mood_frames)};")

    react_lookups = ""
    react_refs = ""
    if react_frames:
        # "0:SS" with nothing before it is the first minute: mark that zero.
        react_lookups = """
lookup TOREACT { sub zero by zero.react; } TOREACT;
lookup MARKREACT {
    ignore sub @D zero' colon;
    sub zero' lookup TOREACT colon @D @D;
} MARKREACT;
"""
        react_refs = "    lookup MARKREACT;\n"

    fea = f"""
languagesystem DFLT dflt;
languagesystem latn dflt;
@D = [{' '.join(DIGITS)}];
lookup HIDE {{ sub colon by colon.hide; }} HIDE;
# H:MM:SS -> hide the first colon so only the seconds colon forms a frame
lookup HIDEHOURS {{ sub colon' lookup HIDE @D @D colon; }} HIDEHOURS;
{react_lookups}
lookup FRAMES {{
{chr(10).join(lig_rules)}
}} FRAMES;
feature liga {{
    lookup HIDEHOURS;
{react_refs}    lookup FRAMES;
}} liga;
feature calt {{
    lookup HIDEHOURS;
{react_refs}    lookup FRAMES;
}} calt;
"""
    addOpenTypeFeaturesFromString(fb.font, fea)
    fb.font.save(path)
    return ps_name


# ------------------------------------------------------------------ swift

def write_swift(path):
    lines = [
        "// Generated by tools/make_slime.py. Do not edit by hand.",
        "// One character per pixel: b body, s shade, k ink, w shine, r warm, c cool, . empty.",
        "",
        "enum SlimeSprites {",
        f"    static let width = {W}",
        f"    static let height = {H}",
        "",
    ]
    for name, frames in ANIMS.items():
        lines.append(f"    static let {name}: [[String]] = [")
        for fr in frames:
            lines.append("        [")
            for row in fr.rows():
                lines.append(f'            "{row}",')
            lines.append("        ],")
        lines.append("    ]")
        lines.append("")
    lines.append("}")
    with open(path, "w") as fh:
        fh.write("\n".join(lines) + "\n")


def write_widget_plist(path, font_files):
    plist = {
        "CFBundleDevelopmentRegion": "$(DEVELOPMENT_LANGUAGE)",
        "CFBundleDisplayName": "Slimy",
        "CFBundleExecutable": "$(EXECUTABLE_NAME)",
        "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)",
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": "$(PRODUCT_NAME)",
        "CFBundlePackageType": "$(PRODUCT_BUNDLE_PACKAGE_TYPE)",
        "CFBundleShortVersionString": "$(MARKETING_VERSION)",
        "CFBundleVersion": "$(CURRENT_PROJECT_VERSION)",
        "NSExtension": {"NSExtensionPointIdentifier": "com.apple.widgetkit-extension"},
        "UIAppFonts": font_files,
    }
    with open(path, "wb") as fh:
        plistlib.dump(plist, fh)


# ------------------------------------------------------------ preview

PREVIEW = {"body": (122, 230, 120), "shade": (60, 170, 90), "ink": (20, 48, 40),
           "shine": (240, 255, 240), "warm": (255, 110, 150), "cool": (140, 220, 255)}


def write_sheet(path, scale=8):
    from PIL import Image, ImageDraw
    pad = 6
    cols = max(len(f) for f in ANIMS.values())
    img = Image.new("RGB", (cols * (W * scale + pad) + pad + 60, len(ANIMS) * (H * scale + pad) + pad), (14, 14, 22))
    d = ImageDraw.Draw(img)
    for r, (name, frames) in enumerate(ANIMS.items()):
        oy = pad + r * (H * scale + pad)
        d.text((4, oy + 4), name, fill=(200, 200, 220))
        for c, fr in enumerate(frames):
            ox = 60 + pad + c * (W * scale + pad)
            d.rectangle([ox, oy, ox + W * scale - 1, oy + H * scale - 1], fill=(0, 0, 0))
            for (x, y), layer in fr.px.items():
                d.rectangle([ox + x * scale, oy + y * scale, ox + (x + 1) * scale - 1, oy + (y + 1) * scale - 1],
                            fill=PREVIEW[layer])
    img.save(path)


def main():
    fonts_dir = os.path.join(ROOT, "Widget", "Fonts")
    os.makedirs(fonts_dir, exist_ok=True)
    for old in os.listdir(fonts_dir):
        if old.endswith(".ttf"):
            os.remove(os.path.join(fonts_dir, old))
    files = []
    for r, m in combos():
        for layer in LAYERS:
            ps = build_font(r, m, layer, os.path.join(fonts_dir, "tmp.ttf"))
            final = os.path.join(fonts_dir, ps + ".ttf")
            os.replace(os.path.join(fonts_dir, "tmp.ttf"), final)
            files.append(ps + ".ttf")
    write_widget_plist(os.path.join(ROOT, "Widget", "Info.plist"), files)
    write_swift(os.path.join(ROOT, "Shared", "SlimeSprites.swift"))
    out = os.path.join(ROOT, "tools", "out")
    os.makedirs(out, exist_ok=True)
    write_sheet(os.path.join(out, "sheet.png"))
    print(f"{len(files)} fonts, {len(ANIMS)} animations")


if __name__ == "__main__":
    sys.exit(main())
