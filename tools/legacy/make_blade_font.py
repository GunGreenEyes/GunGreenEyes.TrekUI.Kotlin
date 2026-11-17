"""
Builds "Warrior Blade", an original display font for the launcher's Imperial interface: English
letters drawn the way Imperial script looks (blade strokes that taper to points, 45° chisel cuts,
angular bowls, diamond dots, a forward lean) while still reading as plain English.

Designed from scratch in this script; no existing font is traced or copied. Upper case only:
lower case maps to the same glyphs. Two weights: Regular and Bold.

    python3 tools/make_blade_font.py  ->  app/src/main/assets/fonts/WarriorBlade-{Regular,Bold}.ttf
"""
import math
import os
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from shapely.geometry import Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

U = 70          # font units per grid unit (cap height = 10 grid units = 700)
SLANT = 0.13    # forward lean
SIDE = 0.8      # side bearing, grid units


def unit(dx, dy):
    l = math.hypot(dx, dy) or 1
    return dx / l, dy / l


def seg(p0, p1, w0, w1, c0='cut', c1='cut'):
    """A stroke from p0 to p1, w0 wide at p0 and w1 at p1. Ends: 'cut' (45° chisel),
    'flat' (square, extended half a width so joints overlap) or 'pt' (sharp tip)."""
    tx, ty = unit(p1[0] - p0[0], p1[1] - p0[1])
    nx, ny = -ty, tx

    def end(p, w, kind, sign):
        # sign: -1 at the start, +1 at the finish (outward along the stroke)
        if kind == 'pt':
            ext = max(w, 0.6) * 0.9
            return [(p[0] + tx * ext * sign, p[1] + ty * ext * sign)]
        if kind == 'flat':
            e = w * 0.5 * sign
            return [(p[0] + nx * w / 2 + tx * e, p[1] + ny * w / 2 + ty * e),
                    (p[0] - nx * w / 2 + tx * e, p[1] - ny * w / 2 + ty * e)]
        # cut: one corner pushed out along the stroke, the other pulled in
        a = w * 0.5
        return [(p[0] + nx * w / 2 + tx * a * sign, p[1] + ny * w / 2 + ty * a * sign),
                (p[0] - nx * w / 2 - tx * a * sign * 0.2, p[1] - ny * w / 2 - ty * a * sign * 0.2)]

    s = end(p0, w0, c0, -1)
    e = end(p1, w1, c1, +1)
    if c0 == 'pt':
        left0 = right0 = s[0]
    else:
        left0, right0 = s[0], s[1]
    if c1 == 'pt':
        left1 = right1 = e[0]
    else:
        left1, right1 = e[0], e[1]
    pts = []
    pts.append(left0)
    if c1 == 'pt':
        pts += [(p1[0] + nx * w1 / 2, p1[1] + ny * w1 / 2), left1, (p1[0] - nx * w1 / 2, p1[1] - ny * w1 / 2)]
    else:
        pts += [left1, right1]
    if c0 == 'pt':
        pts += [(p0[0] - nx * w0 / 2, p0[1] - ny * w0 / 2)]
    else:
        pts += [right0]
    if c0 == 'pt':
        pts.insert(1, (p0[0] + nx * w0 / 2, p0[1] + ny * w0 / 2))
    poly = Polygon(pts)
    if not poly.is_valid:
        poly = poly.buffer(0)
    return poly


def poly(pts, w, start='cut', end='cut', closed=False):
    """A chain of strokes of one width with overlapping joints."""
    out = []
    if closed:
        pts = list(pts) + [pts[0]]
    for i in range(len(pts) - 1):
        c0 = start if (i == 0 and not closed) else 'flat'
        c1 = end if (i == len(pts) - 2 and not closed) else 'flat'
        out.append(seg(pts[i], pts[i + 1], w, w, c0, c1))
    return out


def diamond(cx, cy, s):
    return [Polygon([(cx, cy - s), (cx + s * 0.75, cy), (cx, cy + s), (cx - s * 0.75, cy)])]


def glyphs(T, M):
    """Glyph strokes on a 0..6 (wide) x 0..10 (cap) grid. Returns {char: (width, [polygons])}."""
    P = 0.3  # tip width
    blade = lambda a, b, w0=T, w1=P, c0='cut', c1='pt': [seg(a, b, w0, w1, c0, c1)]

    def spur(x, y, w=T):
        # a barb jutting up and out from the top-left of a stem
        return [Polygon([(x - w / 2, y - 1.6), (x - w / 2 - 1.15, y + 0.75), (x + w * 0.15, y)])]

    # stems: thick at the top with a barb, tapering to a dagger point below the baseline
    stem = lambda x, y0=0, y1=10: blade((x, y1), (x, y0 - 0.9), T * 1.08) + spur(x, y1)
    # stems that must meet a bottom bar keep a square foot
    stemF = lambda x: [seg((x, 10), (x, 0), T * 1.08, T * 0.8, 'cut', 'cut')] + spur(x, 10)

    def bar(x0, x1, y, w=M, c0='cut', c1='pt'):
        # crossbars are blades too: heavier at the start, tapering toward a pointed end
        w1 = w * 0.55 if c1 == 'pt' else w
        return [seg((x0, y), (x1, y), w * 1.15, w1, c0, c1)]
    oct_ = lambda l, b, r, t, k: [(l + k, t), (r - k, t), (r, t - k), (r, b + k), (r - k, b), (l + k, b), (l, b + k), (l, t - k)]
    g = {}
    g['A'] = (6, blade((3.1, 10.6), (0, -0.2)) + blade((3.1, 10.6), (6.1, -0.2)) + bar(1.2, 5.0, 3.4, c1='cut'))
    g['B'] = (6, stemF(0.7)
              + poly([(0.7, 10), (4.5, 10), (5.6, 8.7), (5.6, 6.7), (4.4, 5.3), (0.7, 5.3)], M)
              + poly([(4.4, 5.3), (6, 3.9), (6, 1.3), (4.7, 0), (0.7, 0)], M))
    g['C'] = (6, blade((0, 8.8), (0, 1.0), T, M, 'cut', 'flat')
              + poly([(6, 8.0), (4.6, 10), (1.3, 10), (0, 8.6), (0, 1.4), (1.3, 0), (4.6, 0), (6.2, 1.9)], M, 'pt', 'pt'))
    g['D'] = (6, stemF(0.7)
              + poly([(0.7, 10), (3.9, 10), (6, 7.6), (6, 2.4), (3.9, 0), (0.7, 0)], M))
    g['E'] = (6, stemF(0.7) + bar(0.7, 6.1, 10) + bar(0.7, 4.8, 5.2) + bar(0.7, 6.2, 0))
    g['F'] = (6, stem(0.7) + bar(0.7, 6.1, 10) + bar(0.7, 4.8, 5.2))
    g['G'] = (6, blade((0, 8.8), (0, 1.0), T, M, 'cut', 'flat')
              + poly([(6, 8.0), (4.6, 10), (1.3, 10), (0, 8.6), (0, 1.4), (1.3, 0), (4.6, 0), (6, 1.6), (6, 4.6), (3.2, 4.6)], M, 'pt', 'pt'))
    g['H'] = (6, stem(0.6) + stem(5.6) + bar(0.6, 5.6, 5.2, c1='cut'))
    g['I'] = (2.6, blade((1.3, 10.6), (1.3, -0.7)))
    g['J'] = (6, blade((5.4, 10.6), (5.4, 1.4), T, M, 'cut', 'flat') + poly([(5.4, 1.6), (4, 0), (1.3, 0), (0, 1.5), (0, 3.4)], M, 'flat', 'pt'))
    g['K'] = (6, stem(0.7) + blade((6.0, 10.6), (0.9, 4.6), T, M) + blade((2.4, 5.8), (6.2, -0.5), M, P))
    g['L'] = (6, stemF(0.7) + bar(0.7, 6.2, 0))
    g['M'] = (7.4, stem(0.6) + stem(6.8) + blade((0.6, 10), (3.7, 3.2), M, P) + blade((6.8, 10), (3.7, 3.2), M, P))
    g['N'] = (6, stem(0.6) + stem(5.6) + blade((0.6, 10.2), (5.6, -0.2), M, P))
    o = oct_(0, 0, 6, 10, 1.6)
    g['O'] = (6, poly(o, M, closed=True) + [seg((0, 8.6), (0, 1.4), T, T, 'cut', 'cut'), seg((6, 8.6), (6, 1.4), T, T, 'cut', 'cut')])
    g['P'] = (6, stem(0.7) + poly([(0.7, 10), (4.6, 10), (6, 8.4), (6, 6.2), (4.6, 4.6), (0.7, 4.6)], M))
    g['Q'] = (6, g['O'][1] + blade((3.4, 2.8), (6.6, -1.4), M, P))
    g['R'] = (6, stem(0.7) + poly([(0.7, 10), (4.6, 10), (6, 8.4), (6, 6.2), (4.6, 4.6), (0.7, 4.6)], M) + blade((3.0, 4.8), (6.2, -0.5), M + 0.2, P))
    g['S'] = (6, poly([(6, 8.4), (4.6, 10), (1.4, 10), (0, 8.6), (0, 6.6), (1.4, 5.2), (4.6, 5.2), (6, 3.8), (6, 1.4), (4.6, 0), (1.4, 0), (-0.1, 1.7)], M, 'pt', 'pt')
              + [seg((1.0, 6.0), (5.0, 4.4), M + 0.25, M + 0.25, 'flat', 'flat')])
    g['T'] = (6.2, bar(-0.2, 6.4, 10, M, 'pt', 'pt') + blade((3.1, 10), (3.1, -0.7)))
    g['U'] = (6, blade((0, 10.6), (0, 1.6), T, M, 'cut', 'flat') + blade((6, 10.6), (6, 1.6), T, M, 'cut', 'flat')
              + poly([(0, 1.6), (1.4, 0), (4.6, 0), (6, 1.6)], M, 'flat', 'flat'))
    g['V'] = (6, blade((0, 10.6), (3, -0.3)) + blade((6.1, 10.6), (3, -0.3)))
    g['W'] = (7.6, blade((0, 10.6), (1.9, -0.3)) + blade((3.8, 7.2), (1.9, -0.3), M, P, 'pt') + blade((3.8, 7.2), (5.7, -0.3), M, P, 'pt') + blade((7.6, 10.6), (5.7, -0.3)))
    g['X'] = (6, blade((0.1, 10.6), (6.1, -0.6)) + blade((6, 10.6), (-0.1, -0.6), M + 0.2, P))
    g['Y'] = (6, blade((0, 10.6), (3, 5), T, M, 'cut', 'flat') + blade((6.1, 10.6), (3, 5), T, M, 'cut', 'flat') + blade((3, 5.6), (3, -0.7), T, P, 'flat'))
    g['Z'] = (6, bar(-0.1, 6.0, 10, M, 'pt', 'cut') + blade((6.0, 10.2), (0, -0.2), T, M, 'cut', 'cut') + bar(0, 6.3, 0, M, 'cut', 'pt'))
    # digits
    g['0'] = (5.6, poly(oct_(0, 0, 5.6, 10, 1.5), M, closed=True) + [seg((0, 8.6), (0, 1.4), T, T, 'cut', 'cut'), seg((5.6, 8.6), (5.6, 1.4), T, T, 'cut', 'cut')]
              + blade((4.4, 8), (1.2, 2), M - 0.2, P, 'pt'))
    g['1'] = (4, blade((2.6, 10.6), (2.6, -0.7)) + blade((2.6, 10.2), (0.2, 7.6), M, P, 'flat'))
    g['2'] = (5.8, poly([(0, 8.3), (1.4, 10), (4.4, 10), (5.8, 8.4), (5.8, 6.6), (0, 0.7)], M, 'pt', 'flat') + bar(0, 6.1, 0, M + 0.2, 'cut', 'pt'))
    g['3'] = (5.8, poly([(0, 8.6), (1.4, 10), (4.4, 10), (5.8, 8.6), (5.8, 6.6), (4.4, 5.2), (1.8, 5.2)], M, 'pt', 'pt')
              + poly([(4.4, 5.2), (5.8, 3.8), (5.8, 1.4), (4.4, 0), (1.4, 0), (0, 1.4)], M, 'flat', 'pt')
              + [seg((5.8, 8.6), (5.8, 6.6), T, T, 'cut', 'cut'), seg((5.8, 3.8), (5.8, 1.4), T, T, 'cut', 'cut')])
    g['4'] = (6, blade((4.6, 10.6), (4.6, -0.7)) + poly([(4.6, 10), (0, 3)], M, 'flat', 'flat') + bar(0, 6.4, 3, M, 'cut', 'pt'))
    g['5'] = (5.8, poly([(6, 10), (0.6, 10), (0.2, 5.6), (4.4, 5.6), (5.8, 4.2), (5.8, 1.4), (4.4, 0), (1.4, 0), (-0.1, 1.6)], M, 'pt', 'pt')
              + [seg((5.8, 4.2), (5.8, 1.4), T, T, 'cut', 'cut')])
    g['6'] = (5.8, poly([(5.4, 10.2), (2.1, 10), (0, 7.4), (0, 1.4), (1.4, 0), (4.4, 0), (5.8, 1.4), (5.8, 4), (4.4, 5.4), (0, 5.4)], M, 'pt', 'flat')
              + [seg((0, 7.4), (0, 1.4), T, T, 'cut', 'cut')])
    g['7'] = (5.8, bar(-0.1, 5.9, 10, M, 'cut', 'cut') + blade((5.9, 10.2), (1.6, -0.7)))
    g['8'] = (5.8, poly(oct_(0.4, 5.3, 5.4, 10, 1.2), M, closed=True) + poly(oct_(0, 0, 5.8, 5.3, 1.4), M, closed=True)
              + [seg((0, 4.0), (0, 1.4), T, T, 'cut', 'cut'), seg((5.8, 4.0), (5.8, 1.4), T, T, 'cut', 'cut')])
    g['9'] = (5.8, poly([(5.8, 4.6), (1.4, 4.6), (0, 6.0), (0, 8.6), (1.4, 10), (4.4, 10), (5.8, 8.6), (5.8, 2.6), (3.6, 0), (0.3, -0.2)], M, 'flat', 'pt')
              + [seg((5.8, 8.6), (5.8, 2.6), T, T, 'cut', 'cut')])
    # punctuation
    g['.'] = (1.8, diamond(0.9, 0.7, 0.95))
    g[','] = (1.8, blade((1.1, 1.4), (0.1, -1.8), M + 0.2, P))
    g[':'] = (1.8, diamond(0.9, 0.7, 0.95) + diamond(0.9, 6.0, 0.95))
    g[';'] = (1.8, blade((1.1, 1.4), (0.1, -1.8), M + 0.2, P) + diamond(0.9, 6.0, 0.95))
    g['-'] = (4, bar(0, 4, 4.8, M, 'pt', 'pt'))
    g['–'] = (5, bar(0, 5, 4.8, M, 'pt', 'pt'))
    g['—'] = (7, bar(0, 7, 4.8, M, 'pt', 'pt'))
    g['_'] = (6, bar(0, 6, -1.0, M * 0.8, 'cut', 'cut'))
    g['/'] = (4.4, blade((4.4, 10.8), (0, -0.8), M, P))
    g['\\'] = (4.4, blade((0, 10.8), (4.4, -0.8), M, P))
    g['•'] = (3, diamond(1.5, 4.8, 1.5))
    g['·'] = (1.8, diamond(0.9, 4.8, 0.9))
    g['°'] = (3.4, poly(oct_(0, 6.8, 3.2, 10, 0.9), M * 0.7, closed=True))
    g['%'] = (6, diamond(1.2, 8.4, 1.3) + diamond(4.8, 1.6, 1.3) + blade((5.4, 10.6), (0.6, -0.6), M - 0.1, P))
    g['+'] = (5, bar(0, 5, 4.6, M, 'pt', 'pt') + [seg((2.5, 7.1), (2.5, 2.1), M, M, 'pt', 'pt')])
    g['='] = (5, bar(0, 5, 6.2, M * 0.85, 'pt', 'pt') + bar(0, 5, 3.2, M * 0.85, 'pt', 'pt'))
    g['×'] = (5, blade((0.4, 7.4), (4.6, 1.6), M, P) + blade((4.6, 7.4), (0.4, 1.6), M, P))
    g['*'] = (5, bar(0, 5, 7.6, M * 0.8, 'pt', 'pt') + blade((0.8, 10), (4.2, 5.2), M * 0.8, P, 'pt') + blade((4.2, 10), (0.8, 5.2), M * 0.8, P, 'pt'))
    g["'"] = (1.8, blade((0.9, 10.8), (0.9, 7.0), M + 0.1, P))
    g['"'] = (3.4, blade((0.9, 10.8), (0.9, 7.0), M + 0.1, P) + blade((2.6, 10.8), (2.6, 7.0), M + 0.1, P))
    g['('] = (2.6, poly([(2.4, 11), (0.4, 8.2), (0.4, 1.8), (2.4, -1)], M, 'pt', 'pt'))
    g[')'] = (2.6, poly([(0, 11), (2, 8.2), (2, 1.8), (0, -1)], M, 'pt', 'pt'))
    g['['] = (2.6, poly([(2.6, 10.8), (0.4, 10.8), (0.4, -0.8), (2.6, -0.8)], M, 'cut', 'cut'))
    g[']'] = (2.6, poly([(0, 10.8), (2.2, 10.8), (2.2, -0.8), (0, -0.8)], M, 'cut', 'cut'))
    g['<'] = (4.6, poly([(4.6, 8.6), (0, 4.8), (4.6, 1.0)], M, 'pt', 'pt'))
    g['>'] = (4.6, poly([(0, 8.6), (4.6, 4.8), (0, 1.0)], M, 'pt', 'pt'))
    g['?'] = (5.8, poly([(0, 8.4), (1.4, 10), (4.4, 10), (5.8, 8.4), (5.8, 6.8), (2.9, 4.4), (2.9, 2.6)], M, 'pt', 'pt') + diamond(2.9, 0.7, 0.95))
    g['!'] = (2.2, blade((1.1, 10.6), (1.1, 2.6)) + diamond(1.1, 0.7, 0.95))
    g['&'] = (6.4, poly([(6.2, 0), (1.2, 6.4), (1.2, 8.8), (2.4, 10), (3.8, 10), (4.8, 8.8), (4.8, 7.6), (0, 3.8), (0, 1.3), (1.3, 0), (3.6, 0), (6.4, 3.6)], M, 'pt', 'pt'))
    g['#'] = (6, blade((2.4, 10.4), (1.4, -0.4), M * 0.9, P) + blade((4.8, 10.4), (3.8, -0.4), M * 0.9, P)
              + bar(0, 6, 6.8, M * 0.8, 'pt', 'pt') + bar(0, 6, 3.2, M * 0.8, 'pt', 'pt'))
    g['@'] = (7.2, poly(oct_(0, 0, 7.2, 10, 2), M * 0.8, 'flat', 'pt', closed=False)[:-1]
              + poly(oct_(2.2, 2.8, 5.0, 7.2, 1), M * 0.8, closed=True) + [seg((5.0, 7.2), (5.0, 2.8), M * 0.8, M * 0.8, 'flat', 'flat')])
    return g


def build(weight, T, M, out):
    g = glyphs(T, M)
    order = ['.notdef', 'space'] + ['g%04X' % ord(ch) for ch in sorted(g)]
    cmap = {32: 'space', 0xA0: 'space'}
    for ch in g:
        cmap[ord(ch)] = 'g%04X' % ord(ch)
        if 'A' <= ch <= 'Z':
            cmap[ord(ch.lower())] = 'g%04X' % ord(ch)
    glyf, hmtx = {}, {}
    pen = TTGlyphPen(None)
    glyf['.notdef'] = pen.glyph()
    hmtx['.notdef'] = (500, 0)
    pen = TTGlyphPen(None)
    glyf['space'] = pen.glyph()
    hmtx['space'] = (int(3.4 * U), 0)
    for ch, (w, polys) in g.items():
        shape = unary_union([p if p.is_valid else p.buffer(0) for p in polys]).buffer(0)
        geoms = list(shape.geoms) if hasattr(shape, 'geoms') else [shape]
        pen = TTGlyphPen(None)
        minx = 1e9
        for geom in geoms:
            if geom.is_empty:
                continue
            geom = orient(geom.simplify(0.02), sign=-1.0)  # clockwise outer rings for TrueType
            for ring in [geom.exterior] + list(geom.interiors):
                pts = list(ring.coords)[:-1]
                tr = [(round((x + SIDE + y * SLANT) * U), round(y * U)) for x, y in pts]
                minx = min(minx, min(p[0] for p in tr))
                pen.moveTo(tr[0])
                for p in tr[1:]:
                    pen.lineTo(p)
                pen.closePath()
        name = 'g%04X' % ord(ch)
        glyf[name] = pen.glyph()
        hmtx[name] = (round((w + 2 * SIDE + 0.6) * U), max(0, int(minx)))
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap(cmap)
    fb.setupGlyf(glyf)
    fb.setupHorizontalMetrics(hmtx)
    fb.setupHorizontalHeader(ascent=920, descent=-240)
    fam = 'Warrior Blade'
    fb.setupNameTable({'familyName': fam, 'styleName': weight, 'uniqueFontIdentifier': fam + ' ' + weight,
                       'fullName': fam + ' ' + weight, 'psName': 'WarriorBlade-' + weight, 'version': 'Version 1.000',
                       'copyright': 'Original design for TrekUI Launcher',
                       'description': 'English letters in an angular blade hand'})
    if weight == 'Bold':
        fb.font['head'].macStyle = 1
    fb.setupOS2(sTypoAscender=920, sTypoDescender=-240, usWinAscent=980, usWinDescent=260, sCapHeight=700, sxHeight=700,
                usWeightClass=700 if weight == 'Bold' else 400, fsSelection=0x20 if weight == 'Bold' else 0x40)
    fb.setupPost()
    fb.save(out)
    return out


if __name__ == '__main__':
    dst = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'fonts')
    print(build('Regular', 1.5, 0.95, os.path.join(dst, 'WarriorBlade-Regular.ttf')))
    print(build('Bold', 2.05, 1.25, os.path.join(dst, 'WarriorBlade-Bold.ttf')))
