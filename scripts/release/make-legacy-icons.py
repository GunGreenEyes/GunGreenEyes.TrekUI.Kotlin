#!/usr/bin/env python3
"""Generates the PNG launcher icons Android 6.0-7.1 (API 23-25) show, from each app's adaptive-icon art.

    python scripts/release/make-legacy-icons.py

Adaptive icons (res/mipmap-anydpi-v26/ic_launcher.xml) only exist from Android 8.0 (API 26). Below that the
system loads res/mipmap-<density>/ic_launcher.png, so every app needs those PNGs (TUK-T14, minSdk 23). For
each of the four apps this reads the foreground vector the adaptive icon names and draws it on the icon
background colour, as a rounded square with the 2dp margin of the legacy 48dp icon grid. The foreground keeps
the adaptive icon's own scale: the 72dp visible part of the 108dp viewport fills the square, so the icon looks
the same on 23-25 as on 26+. Writes mipmap-mdpi .. mipmap-xxxhdpi/ic_launcher.png (48 .. 192 px).

Reuses the vector rasteriser in make-store-art.py. Needs Pillow (pip install pillow).
"""
import importlib.util
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
_spec = importlib.util.spec_from_file_location("store_art", os.path.join(HERE, "make-store-art.py"))
art = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(art)
Image, ImageDraw = art.Image, art.ImageDraw

ANDROID = art.ANDROID
MODULES = ("app", "messages", "keyboard", "lockscreen")
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ICON_DP, MARGIN_DP, CORNER_DP, VISIBLE_DP = 48, 2, 6, 72


def res_ref(value):
    """'@drawable/ic_launcher_fg' -> ('drawable', 'ic_launcher_fg')."""
    kind, name = value.lstrip("@").split("/")
    return kind, name


def color_value(res, name):
    for f in os.listdir(os.path.join(res, "values")):
        root = ET.parse(os.path.join(res, "values", f)).getroot()
        for c in root.iter("color"):
            if c.get("name") == name:
                return art.hex_rgb(c.text.strip())
    sys.exit(f"colour {name} not found under {res}")


def load_shapes(path):
    root = ET.parse(path).getroot()
    size = float(root.get(ANDROID + "viewportWidth"))
    shapes = [(art.hex_rgb(p.get(ANDROID + "fillColor")), art.path_polygons(p.get(ANDROID + "pathData")))
              for p in root.iter("path")]
    return size, shapes


def render(bg, viewport, shapes, px_per_dp, ss=4):
    size = round(ICON_DP * px_per_dp)
    big = size * ss
    unit = big / ICON_DP
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [MARGIN_DP * unit, MARGIN_DP * unit, big - MARGIN_DP * unit, big - MARGIN_DP * unit],
        radius=CORNER_DP * unit, fill=255)
    face = Image.new("RGBA", (big, big), bg + (255,))
    inner = (ICON_DP - 2 * MARGIN_DP) * unit
    scale = inner / VISIBLE_DP * (108 / viewport)
    offset = big / 2 - viewport / 2 * scale
    art.draw_vector(face, shapes, scale, offset, offset)
    img.paste(face, (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS)


def main():
    for module in MODULES:
        res = os.path.join(ROOT, module, "src", "main", "res")
        adaptive = ET.parse(os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml")).getroot()
        bg_kind, bg_name = res_ref(adaptive.find("background").get(ANDROID + "drawable"))
        fg_kind, fg_name = res_ref(adaptive.find("foreground").get(ANDROID + "drawable"))
        if bg_kind != "color" or fg_kind != "drawable":
            sys.exit(f"{module}: expected a colour background and a vector foreground")
        viewport, shapes = load_shapes(os.path.join(res, "drawable", fg_name + ".xml"))
        bg = color_value(res, bg_name)
        for density, factor in DENSITIES.items():
            out = os.path.join(res, f"mipmap-{density}")
            os.makedirs(out, exist_ok=True)
            render(bg, viewport, shapes, factor).save(os.path.join(out, "ic_launcher.png"), optimize=True)
        print(f"{module}: {', '.join(DENSITIES)} from {fg_name}")


if __name__ == "__main__":
    main()
