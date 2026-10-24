#!/usr/bin/env python3
"""Checks every animation font turns every timer string into exactly one frame."""
import glob
import os
import sys

import uharfbuzz as hb

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
samples = [f"{m}:{s:02d}" for m in (0, 1, 9, 10, 59) for s in range(60)]
samples += [f"{h}:{m:02d}:{s:02d}" for h in (1, 7, 11) for m in (0, 30) for s in range(0, 60, 7)]

bad = 0
fonts = sorted(glob.glob(os.path.join(ROOT, "Widget", "Fonts", "*.ttf")))
for path in fonts:
    font = hb.Font(hb.Face(hb.Blob.from_file_path(path)))
    reacts = "-Idle-" not in path and "-Sad-" not in path and "-Sleep-" not in path
    for text in samples:
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(font, buf, {})
        names = [font.glyph_to_string(g.codepoint) for g in buf.glyph_infos]
        frames = [n for n in names if n[0] in "mr" and n[1:].isdigit()]
        want = "r" if reacts and text.startswith("0:") else "m"
        if len(frames) != 1 or frames[0][0] != want:
            bad += 1
            print(f"{os.path.basename(path)} {text!r} -> {names}")
print(f"checked {len(fonts)} fonts x {len(samples)} timer strings, {bad} failures")
sys.exit(1 if bad else 0)
