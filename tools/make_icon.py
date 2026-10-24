#!/usr/bin/env python3
"""Draws the 1024px app icon: the slime glowing on a hyprnode night."""
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from make_slime import ANIMS, W, H  # noqa: E402

SIZE = 1024
COLORS = {"b": (125, 230, 122), "s": (61, 170, 90), "k": (20, 48, 40), "w": (240, 255, 240),
          "r": (255, 110, 154), "c": (127, 224, 255)}


def main():
    img = Image.new("RGB", (SIZE, SIZE))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            # violet glow from the top, fading into the void
            d = math.hypot(x - SIZE * 0.5, y - SIZE * 0.1) / SIZE
            t = max(0.0, 1 - d * 1.25)
            px[x, y] = (int(12 + 120 * t * t), int(11 + 80 * t * t), int(20 + 200 * t * t))

    glow = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(glow).ellipse([200, 380, 824, 900], fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    img.paste(Image.new("RGB", (SIZE, SIZE), (125, 230, 122)), (0, 0), glow.point(lambda v: v // 3))

    frame = ANIMS["love"][0].rows()
    cell = 38
    ox = (SIZE - W * cell) // 2
    oy = SIZE - H * cell - 90
    draw = ImageDraw.Draw(img)
    draw.ellipse([ox + 120, oy + H * cell - 26, ox + W * cell - 120, oy + H * cell + 26], fill=(8, 7, 14))
    for y, row in enumerate(frame):
        for x, ch in enumerate(row):
            if ch in COLORS:
                draw.rectangle([ox + x * cell, oy + y * cell, ox + (x + 1) * cell - 1, oy + (y + 1) * cell - 1],
                               fill=COLORS[ch])

    out = os.path.join(ROOT, "Resources", "Assets.xcassets", "AppIcon.appiconset")
    os.makedirs(out, exist_ok=True)
    img.save(os.path.join(out, "icon.png"))
    with open(os.path.join(out, "Contents.json"), "w") as fh:
        json.dump({"images": [{"filename": "icon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
                   "info": {"author": "xcode", "version": 1}}, fh, indent=2)
    with open(os.path.join(ROOT, "Resources", "Assets.xcassets", "Contents.json"), "w") as fh:
        json.dump({"info": {"author": "xcode", "version": 1}}, fh, indent=2)
    accent = os.path.join(ROOT, "Resources", "Assets.xcassets", "AccentColor.colorset")
    os.makedirs(accent, exist_ok=True)
    with open(os.path.join(accent, "Contents.json"), "w") as fh:
        json.dump({"colors": [{"idiom": "universal", "color": {"color-space": "srgb", "components": {
            "red": "0.608", "green": "0.482", "blue": "1.000", "alpha": "1.000"}}}],
            "info": {"author": "xcode", "version": 1}}, fh, indent=2)
    print("icon written")


if __name__ == "__main__":
    main()
