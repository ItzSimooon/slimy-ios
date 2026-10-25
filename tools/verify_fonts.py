#!/usr/bin/env python3
"""Checks the animation fonts the way the island uses them.

The island right-aligns the timer and shows only the last glyph, so every
glyph must be exactly one frame wide, nothing may be substituted or merged
(no ligatures), and the last glyph must be the digit for the seconds.
"""
import glob
import os
import sys

import uharfbuzz as hb

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRAMES = {"Slime": 24 * 50, "Emote": 9 * 100}
DIGITS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
samples = [f"{m}:{s:02d}" for m in (0, 1, 9, 10, 59) for s in range(60)]
samples += [f"{h}:{m:02d}:{s:02d}" for h in (1, 7, 11) for m in (0, 30) for s in range(0, 60, 7)]
samples += ["0.07", "0,07", " 0:07"]

bad = 0
fonts = sorted(glob.glob(os.path.join(ROOT, "Widget", "Fonts", "*.ttf")))
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
sys.exit(1 if bad else 0)
