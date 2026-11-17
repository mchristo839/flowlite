#!/usr/bin/env python3
"""Draws the FlowLite icon set from the parakeet artwork.

Usage: make_icons.py OUT_DIR
Needs: pip install cairosvg pillow
"""
import io
import os
import sys

import cairosvg
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
BLUE, RED, AMBER = (53, 89, 224, 255), (217, 48, 37, 255), (232, 163, 23, 255)
LIGHT, DARK = "#F5F5F5", "#222222"

# Single-colour parakeet head for tray icons: head with an eye hole, plus a hooked beak.
MARK = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><g fill="{c}">
<path fill-rule="evenodd" d="M10,50 a32,32 0 1,0 64,0 a32,32 0 1,0 -64,0 Z M58,42 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0 Z"/>
<path d="M70,50 C77,48 88,52 89,61 C90,68 86,74 81,75 C82,69 80,65 75,64 C71,63 68,58 70,50 Z"/>
</g></svg>"""


def render(svg, size):
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size))).convert("RGBA")


def tray(state, colour, disc=None):
    s = 256
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if disc:
        d.ellipse([8, 8, s - 8, s - 8], fill=disc)
        im.alpha_composite(render(MARK.format(c=LIGHT), 200), (24, 28))
    else:
        im.alpha_composite(render(MARK.format(c=colour), 240), (8, 8))
        badge = {"recording": RED, "transcribing": AMBER}.get(state)
        if badge:  # state dot in the lower right corner
            d.ellipse([s - 104, s - 104, s - 8, s - 8], fill=badge)
    return im.resize((64, 64), Image.LANCZOS)


def app_icon(size=1024):
    with open(os.path.join(HERE, "flowlite.svg")) as f:
        return render(f.read(), size)


out = sys.argv[1]
for state in ("idle", "recording", "transcribing"):
    tray(state, LIGHT).save(f"{out}/tray_{state}.png")
    tray(state, DARK).save(f"{out}/tray_{state}_dark.png")
tray("idle", LIGHT).save(f"{out}/tray_idle_warning.png")
tray("idle", DARK).save(f"{out}/tray_idle_warning_dark.png")
tray("idle", LIGHT, BLUE).save(f"{out}/handy.png")
tray("idle", LIGHT, AMBER).save(f"{out}/handy_warning.png")
tray("recording", LIGHT, RED).save(f"{out}/recording.png")
tray("transcribing", LIGHT, AMBER).save(f"{out}/transcribing.png")
app_icon().save(f"{out}/flowlite-1024.png")
for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    os.makedirs(f"{out}/android/mipmap-{name}", exist_ok=True)
    app_icon(px).save(f"{out}/android/mipmap-{name}/ic_launcher.png")
