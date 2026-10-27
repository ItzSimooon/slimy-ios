#!/usr/bin/env python3
"""Google Play store graphics for Slimy: the 512 px icon, the 1024x500 feature
graphic and 1080x1920 phone screenshots with a caption (English and German).

Needs Pillow and the Android screenshots from
`gradle :app:recordRoborazziDebug` (android/app/build/outputs/roborazzi).
Writes play/graphics/.  Run: python tools/make_play_assets.py
"""
import json
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "play", "graphics")
SHOTS = os.path.join(ROOT, "android", "app", "build", "outputs", "roborazzi")
BOLD = os.path.join(ROOT, "Shared", "Fonts", "PixelifySans-Bold.ttf")
REGULAR = os.path.join(ROOT, "Shared", "Fonts", "PixelifySans-Regular.ttf")

# Shared/Art/Brand.swift: the island's moss black, the same as the app icon.
BG = (16, 20, 17)
LINE = (46, 58, 50)
TEXT = (233, 241, 228)
MUTED = (141, 157, 145)
SLIME = (125, 230, 122)
HYPR = (155, 123, 255)

# Shared/Art/Skin.swift, lime: body, shade, ink, shine, warm, cool.
LIME = {"b": 0x7DE67A, "s": 0x3DAA5A, "k": 0x143028, "w": 0xF0FFF0, "r": 0xF07A9A, "c": 0x7FD6F0}

# The first store screenshot shows the island instead of one app screen.
ISLAND = ("island", "It lives in an island on top of every app", "Er wohnt in einer Insel über jeder App")

# Screenshot, English caption, German caption. The order is the store order.
SCREENS = [
    ("light-home", "A tiny slime that lives on your phone", "Ein kleiner Slime, der auf deinem Handy wohnt"),
    ("light-crate-reveal", "Open crates. One hides a secret legendary.", "Öffne Kisten. Eine birgt etwas Legendäres."),
    ("light-game-snack-catcher", "12 mini games and a new one every day", "12 Minispiele und jeden Tag ein neues"),
    ("light-chat", "Talk to it. It has real feelings.", "Rede mit ihm. Er hat echte Gefühle."),
    ("light-care", "Raise one slime together", "Kümmert euch zu zweit um einen Slime"),
    ("light-friends", "Hug, visit and send gifts to friends", "Umarme, besuche und beschenke Freunde"),
    ("light-shop", "Dress it up and decorate its home", "Zieh ihn an und richte sein Zuhause ein"),
]

with open(os.path.join(ROOT, "Shared", "Sprites", "sprites.json"), encoding="utf-8") as f:
    SPRITES = json.load(f)


def rgb(value):
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def paint(draw, rows, palette, x, y, scale):
    """Draws pixel rows; characters missing from the palette are empty."""
    for row_index, row in enumerate(rows):
        for col, ch in enumerate(row):
            colour = palette.get(ch)
            if colour is None:
                continue
            left, top = x + col * scale, y + row_index * scale
            draw.rectangle([left, top, left + scale - 1, top + scale - 1], fill=rgb(colour))


def bottom_row(rows):
    """Index of the last row with a pixel, to stand art on a floor."""
    for i in range(len(rows) - 1, -1, -1):
        if rows[i].strip("."):
            return i
    return len(rows) - 1


def item(name):
    sprite = SPRITES["items"][name]
    return sprite["rows"], {k: int(v) for k, v in sprite["palette"].items()}


def wrap(text, font, width):
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if font.getlength(trial) <= width or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    lines.append(line)
    return lines


def icon():
    src = Image.open(os.path.join(ROOT, "Resources", "Assets.xcassets", "AppIcon.appiconset", "icon.png")).convert("RGB")
    # Exactly half the size: averaging 2x2 blocks keeps the pixel art crisp.
    src.resize((512, 512), Image.BOX).save(os.path.join(OUT, "icon-512.png"), optimize=True)


def feature():
    w, h = 1024, 500
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)

    floor = 420
    d.rectangle([520, floor, w, floor + 5], fill=LINE)

    scale = 13
    frame = SPRITES["frames"]["young.idle"][0]
    slime_x = 640
    slime_y = floor - (bottom_row(frame) + 1) * scale
    paint(d, frame, LIME, slime_x, slime_y, scale)

    for name, x, y, s in [("heart", 590, 150, 6), ("coin", 930, 200, 6), ("apple", 560, 330, 5)]:
        rows, pal = item(name)
        paint(d, rows, pal, x, y, s)
    rows, pal = item("crate.golden")
    paint(d, rows, pal, 900, floor - (bottom_row(rows) + 1) * 6, 6)

    title = ImageFont.truetype(BOLD, 150)
    d.text((64, 96), "slimy", font=title, fill=TEXT)
    d.text((70, 270), "by hyprnode", font=ImageFont.truetype(BOLD, 36), fill=HYPR)
    tagline = ImageFont.truetype(REGULAR, 34)
    for i, line in enumerate(["A pixel pet that lives", "on your phone"]):
        d.text((70, 336 + i * 44), line, font=tagline, fill=MUTED)
    img.save(os.path.join(OUT, "feature-1024x500.png"), optimize=True)


def caption_canvas(caption):
    """A 1080x1920 store screenshot with the caption on top."""
    w, h = 1080, 1920
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(BOLD, 74)
    lines = wrap(caption, font, 940)
    line_h = 92
    top = 210 - len(lines) * line_h // 2
    d.rectangle([w // 2 - 28, top - 44, w // 2 + 28, top - 36], fill=SLIME)
    for i, line in enumerate(lines):
        d.text((w // 2, top + i * line_h), line, font=font, fill=TEXT, anchor="mt")
    return img, d


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=radius, fill=255)
    return mask


def island_screenshot(caption, out_path):
    """The island compact and expanded, cut from the top of the screen."""
    img, d = caption_canvas(caption)
    width, gap = 960, 90
    tops = []
    for name in ("dark-island-compact", "dark-island-expanded"):
        top = Image.open(os.path.join(SHOTS, f"{name}.png")).convert("RGB")
        tops.append(top.resize((width, round(top.height * width / top.width)), Image.LANCZOS))
    # Centred in the space below the caption.
    y = 400 + (1920 - 400 - sum(t.height for t in tops) - gap) // 2
    x = (1080 - width) // 2
    for top in tops:
        d.rounded_rectangle([x - 12, y - 12, x + width + 12, y + top.height + 12], radius=52, fill=LINE)
        img.paste(top, (x, y), rounded(top, 40))
        y += top.height + gap
    img.save(out_path, optimize=True)


def screenshot(name, caption, out_path):
    w = 1080
    img, d = caption_canvas(caption)

    shot = Image.open(os.path.join(SHOTS, f"{name}.png")).convert("RGB")
    screen_h = 1440
    screen_w = round(shot.width * screen_h / shot.height)
    shot = shot.resize((screen_w, screen_h), Image.LANCZOS)
    x, y = (w - screen_w) // 2, 400
    bezel, radius = 14, 56
    d.rounded_rectangle([x - bezel, y - bezel, x + screen_w + bezel, y + screen_h + bezel], radius=radius + bezel, fill=LINE)
    mask = Image.new("L", (screen_w, screen_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, screen_w - 1, screen_h - 1], radius=radius, fill=255)
    img.paste(shot, (x, y), mask)
    img.save(out_path, optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    icon()
    feature()
    for lang, index in (("en-US", 1), ("de-DE", 2)):
        folder = os.path.join(OUT, "screenshots", lang)
        if os.path.isdir(folder):
            for old in os.listdir(folder):
                os.remove(os.path.join(folder, old))
        os.makedirs(folder, exist_ok=True)
        island_screenshot(ISLAND[index], os.path.join(folder, "01-island.png"))
        for n, screen in enumerate(SCREENS, start=2):
            screenshot(screen[0], screen[index], os.path.join(folder, f"{n:02d}-{screen[0].removeprefix('light-')}.png"))
    print("written to", OUT)


if __name__ == "__main__":
    main()
