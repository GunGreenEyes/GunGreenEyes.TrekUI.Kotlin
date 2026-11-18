"""Renders the extended pictogram set (glyphs_extra.E, LCA-T22) as the bold "trekui" stroke style.

Usage:
    python glyph_render.py <outroot>            # <outroot>/trekui/<name>.png
    python glyph_render.py --assets <assetsdir> # straight into the assets layout: <assetsdir>/glyphs/

The plain TrekUI set for the core 28 comes from tools/legacy/make_glyphs.py, so those names are
never rendered here (it would overwrite the hand-tuned solid silhouettes).

Needs node with @resvg/resvg-js (npm install --prefix tools/art; the setup scripts do this) and Pillow.
"""
import json, re, subprocess, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from glyphs_extra import E
from PIL import Image

STYLES = {"trekui": 15}


def names_for(style):
    """Glyph names rendered in a style: only the extended set."""
    return list(E)


def svg_for(name, w):
    body = E[name]
    body = re.sub(r'style="stroke-width:calc\(var\(--w\)\*([0-9.]+)\)"', lambda m: f'stroke-width="{w*float(m.group(1)):.1f}"', body)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192" width="192" height="192">
<style>* {{ fill:none; stroke:#fff; stroke-width:{w}; stroke-linecap:round; stroke-linejoin:round }} .f {{ fill:#fff }}</style>
<g>{body}</g></svg>'''


def out_dir(outroot, style, assets):
    return os.path.join(outroot, "glyphs") if assets else os.path.join(outroot, style)


def main(outroot, assets=False):
    jobs = []
    tmp = os.path.join(outroot if not assets else os.environ.get("TMP", outroot), "_glyph_tmp"); os.makedirs(tmp, exist_ok=True)
    for st, w in STYLES.items():
        for n in names_for(st):
            jobs.append({"svg": svg_for(n, w), "out": f"{tmp}/{st}_{n}.png", "width": 192})
    jf = os.path.join(tmp, "jobs.json"); json.dump(jobs, open(jf, "w"))
    subprocess.run(["node", os.path.join(os.path.dirname(__file__), "render.js"), jf], check=True)
    for st in STYLES:
        od = out_dir(outroot, st, assets); os.makedirs(od, exist_ok=True)
        for n in names_for(st):
            a = Image.open(f"{tmp}/{st}_{n}.png").convert("RGBA").getchannel("A")
            out = Image.new("RGBA", a.size, (255, 255, 255, 0)); out.putalpha(a)
            out.save(os.path.join(od, n + ".png"), optimize=True)
    print("done")


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--assets":
        main(sys.argv[2], assets=True)
    else:
        main(sys.argv[1])
