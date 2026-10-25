#!/usr/bin/env python3
"""Draws the 1024px app icon: the slime glowing on a hyprnode night."""
import json
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from make_slime import ANIMS, W, H  # noqa: E402

SIZE = 1024
COLORS = {"b": (125, 230, 122), "s": (61, 170, 90), "k": (20, 48, 40), "w": (240, 255, 240),
          "r": (255, 110, 154), "c": (127, 224, 255)}


def main():
    # Flat moss-black, the slime on a strip of ground. No gradients.
    img = Image.new("RGB", (SIZE, SIZE), (16, 20, 17))
    draw = ImageDraw.Draw(img)
    frame = ANIMS["idle"][0].rows()
    cell = 36
    ox = (SIZE - W * cell) // 2
    oy = SIZE - H * cell - 150
    draw.rectangle([0, oy + H * cell, SIZE, oy + H * cell + 24], fill=(46, 58, 50))
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
            "red": "0.490", "green": "0.902", "blue": "0.478", "alpha": "1.000"}}}],
            "info": {"author": "xcode", "version": 1}}, fh, indent=2)
    launch = os.path.join(ROOT, "Resources", "Assets.xcassets", "LaunchBackground.colorset")
    os.makedirs(launch, exist_ok=True)
    with open(os.path.join(launch, "Contents.json"), "w") as fh:
        json.dump({"colors": [{"idiom": "universal", "color": {"color-space": "srgb", "components": {
            "red": "0.063", "green": "0.078", "blue": "0.067", "alpha": "1.000"}}}],
            "info": {"author": "xcode", "version": 1}}, fh, indent=2)
    print("icon written")


if __name__ == "__main__":
    main()
