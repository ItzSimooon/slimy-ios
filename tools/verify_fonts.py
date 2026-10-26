#!/usr/bin/env python3
"""Checks the animation fonts the way the island uses them.

The island right-aligns the timer and shows only the last glyph, so every
glyph must be exactly one frame wide, nothing may be substituted or merged
(no ligatures), and the last glyph must be the digit for the seconds.

It also checks that the generated files belong together: every animation has
a font for every colour layer (the axolotl's gills included), the skin
overlays in sprites.json match the frames one for one, and the widget's
Info.plist lists exactly the fonts that ship.
"""
import glob
import json
import os
import plistlib
import sys

import uharfbuzz as hb

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "Widget", "Fonts")
FRAMES = {"Slime": 24 * 50, "Acc": 24 * 50, "Emote": 9 * 100}
LAYERS = ["Body", "Shade", "Ink", "Shine", "Warm", "Cool", "Gills"]
DIGITS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
samples = [f"{m}:{s:02d}" for m in (0, 1, 9, 10, 59) for s in range(60)]
samples += [f"{h}:{m:02d}:{s:02d}" for h in (1, 7, 11) for m in (0, 30) for s in range(0, 60, 7)]
samples += ["0.07", "0,07", " 0:07"]


def cap(s):
    return s[0].upper() + s[1:]


bad = 0
fonts = sorted(glob.glob(os.path.join(FONTS, "*.ttf")))
for path in fonts:
    font = hb.Font(hb.Face(hb.Blob.from_file_path(path)))
    frame = FRAMES[os.path.basename(path).split("-")[0]]
    for text in samples:
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(font, buf, {})
        names = [font.glyph_to_string(g.codepoint) for g in buf.glyph_infos]
        advances = {p.x_advance for p in buf.glyph_positions}
        ok = (len(names) == len(text) and advances == {frame}
              and names[-1] == DIGITS[int(text[-1])])
        if not ok:
            bad += 1
            print(f"{os.path.basename(path)} {text!r} -> {names} {advances}")
print(f"checked {len(fonts)} fonts x {len(samples)} timer strings, {bad} failures")

problems = []
files = {os.path.basename(p) for p in fonts}
with open(os.path.join(ROOT, "Shared", "Sprites", "sprites.json")) as fh:
    sprites = json.load(fh)
width, height = sprites["width"], sprites["height"]
for key, frames in sprites["frames"].items():
    stage, anim = key.split(".")
    for layer in LAYERS:
        name = f"Slime-{cap(stage)}-{cap(anim)}-{layer}.ttf"
        if name not in files:
            problems.append(f"missing {name}")
for skin, overlay in sprites.get("skinOverlays", {}).items():
    if overlay.get("layer") not in LAYERS:
        problems.append(f"{skin}: overlay layer {overlay.get('layer')!r} has no fonts")
    codes = set(overlay.get("palette", {}))
    if set(sprites["frames"]) != set(overlay["frames"]):
        problems.append(f"{skin}: overlay animations differ from the frames")
    for key, grids in overlay["frames"].items():
        frames = sprites["frames"].get(key, [])
        if len(grids) != len(frames):
            problems.append(f"{skin} {key}: {len(grids)} overlays for {len(frames)} frames")
        for grid in grids:
            if len(grid) != height or any(len(row) != width for row in grid):
                problems.append(f"{skin} {key}: overlay is not {width} x {height}")
            if {ch for row in grid for ch in row} - codes - {"."}:
                problems.append(f"{skin} {key}: overlay uses colours outside its palette")
with open(os.path.join(ROOT, "Widget", "Info.plist"), "rb") as fh:
    listed = {f for f in plistlib.load(fh)["UIAppFonts"] if not f.startswith("PixelifySans")}
if listed != files:
    problems.append(f"Widget/Info.plist lists {len(listed)} fonts, Widget/Fonts has {len(files)}")
for p in problems:
    print(p)
print(f"checked {len(sprites['frames'])} animations, {len(problems)} problems with the generated files")
sys.exit(1 if bad or problems else 0)
