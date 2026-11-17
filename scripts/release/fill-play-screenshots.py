#!/usr/bin/env python3
"""Fills the Play listing screenshot folders from emulator captures.

    python scripts/release/fill-play-screenshots.py --captures assets
    python scripts/release/fill-play-screenshots.py --captures _capture   # CI: capture-<device>-api<N>/ folders

Picks up to 8 screenshots per form factor from the newest API level available:
  phoneScreenshots      <- phone-api<N> (portrait)
  sevenInchScreenshots  <- foldable-api<N> (unfolded inner screen)
  tenInchScreenshots    <- tablet-api<N>
Play rejects images whose long side is more than twice the short side (tall 20:9 phones are), so those
are padded with black (the TrekUI background) to 1:1.95; anything over 3840 px is scaled down.
Existing PNG/JPEG files in the target folders are replaced. Needs Pillow.
"""
import argparse
import glob
import math
import os
import re
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required: python -m pip install pillow")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TARGETS = {
    "phoneScreenshots": ("phone", "portrait"),
    "sevenInchScreenshots": ("foldable", None),
    "tenInchScreenshots": ("tablet", None),
}
MAX_SIDE = 3840
MAX_RATIO = 1.95
PER_TYPE = 8


def family(device):
    """phone / foldable / tablet from a folder's device part (CI: phone, phone-land; local: pixel_7, pixel_fold)."""
    d = device.lower()
    if "tablet" in d:
        return "tablet"
    if "fold" in d:
        return "foldable"
    if "phone" in d or "pixel" in d:
        return "phone"
    return d


def runs(captures):
    """{family: [(api, dir)]} from <device>-api<N>[-<orientation>] or capture-<device>-api<N> folders."""
    out = {}
    for d in glob.glob(os.path.join(captures, "*")):
        if not os.path.isdir(d):
            continue
        m = re.match(r"^(?:capture-)?(.+)-api(\d+(?:\.\d+)?)(?:-(?:portrait|landscape))?$", os.path.basename(d))
        if m:
            out.setdefault(family(m.group(1)), []).append((float(m.group(2)), d))
    for v in out.values():
        v.sort(reverse=True)
    return out


def orientation(img):
    return "landscape" if img.width > img.height else "portrait"


def fit_for_play(img):
    img = img.convert("RGB")
    long_side, short_side = max(img.size), min(img.size)
    if long_side / short_side > 2.0:
        new_short = math.ceil(long_side / MAX_RATIO)
        w, h = (img.width, new_short) if img.width > img.height else (new_short, img.height)
        canvas = Image.new("RGB", (w, h), (0, 0, 0))
        canvas.paste(img, ((w - img.width) // 2, (h - img.height) // 2))
        img = canvas
    if max(img.size) > MAX_SIDE:
        scale = MAX_SIDE / max(img.size)
        img = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--captures", default=os.path.join(ROOT, "assets"))
    ap.add_argument("--metadata", default=os.path.join(ROOT, "fastlane", "metadata", "android", "en-US"))
    a = ap.parse_args()

    found = runs(a.captures)
    for folder, (device, want) in TARGETS.items():
        target = os.path.join(a.metadata, "images", folder)
        os.makedirs(target, exist_ok=True)
        picked = []
        for _api, d in found.get(device, []):
            files = glob.glob(os.path.join(d, "**", "*.png"), recursive=True) + glob.glob(os.path.join(d, "**", "*.jpg"), recursive=True)
            for f in sorted(f for f in files if os.sep + "thumbs" + os.sep not in f):
                with Image.open(f) as im:
                    if want and orientation(im) != want:
                        continue
                    picked.append((f, fit_for_play(im)))
                if len(picked) >= PER_TYPE:
                    break
            if picked:
                break  # newest API level that has shots
        if not picked:
            print(f"{folder}: no {device} captures, left unchanged")
            continue
        for old in glob.glob(os.path.join(target, "*.png")) + glob.glob(os.path.join(target, "*.jpg")):
            os.remove(old)
        for i, (src, img) in enumerate(picked, 1):
            dst = os.path.join(target, f"{i:02d}.png")
            img.save(dst, optimize=True)
            print(f"{folder}/{i:02d}.png  <- {os.path.relpath(src, a.captures)} {img.size}")
        if len(picked) < 2:
            print(f"::warning::{folder} has only {len(picked)} screenshot; Play needs at least 2")
    return 0


if __name__ == "__main__":
    sys.exit(main())
