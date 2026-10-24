"""
The J menu rail's sprites (kit/rail_wood, kit/rail_rule) and the paper-label tabs (kit/tag*), drawn as pixel art.

Four texels to a GUI px, so no texel is larger than a screen pixel at menu scales 2 to 4. Run from the repo
root: python tools/art/rail_sprites.py
"""
import json
import random
from pathlib import Path

from PIL import Image

OUT = Path("common/src/main/resources/assets/wynnlabs/textures/gui/sprites/kit")
T = 4  # texels per GUI px: one texel is at most one screen pixel at menu scales 2 to 4


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, d):
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + (c[3],)


def meta(name, scaling):
    (OUT / (name + ".png.mcmeta")).write_text(json.dumps({"gui": {"scaling": scaling}}, indent=2) + "\n", encoding="utf-8")


# the Theme palette (ui/Theme.java): nothing here may use a colour outside it
PAPER, PAPER_DEEP, LINE, CARD = hexc("D8C595"), hexc("C4AD78"), hexc("9A855C"), hexc("EADCB4")
WOOD, WOOD_HI, WOOD_LO, WOOD_EDGE = hexc("6E5440"), hexc("85684F"), hexc("4F3A2A"), hexc("3B2A1D")
PLANK_A, PLANK_B = hexc("664C36"), hexc("5B432F")
GOLD, GOLD_EDGE, BRASS = hexc("FFE071"), hexc("8A6A1C"), hexc("C9973A")


INK, INK_2, SELECTED = hexc("2E2E2E"), hexc("5E4A38"), hexc("F6E3A8")


def mix(a, b, t):
    """Colour a blended toward b by t (0..1), opaque."""
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def paper_tag(name, face, outline, ow, brass=False, notch_right=True):
    """
    A rail tab as a paper label, 24x19 GUI px: a V notch cut into both short ends (only the left
    one with {@code notch_right} off), an outline {@code ow} texels thick following the cut, and
    with {@code brass} a 4px BRASS strip on the left end. The notch's slanted edges are smoothed:
    the texel the line crosses takes the outline at its share of coverage outside, and a blend of
    outline and face inside. Nine-sliced: the ends keep their shape, the middle stretches.
    """
    w, h = 24 * T, 19 * T
    depth = 2.0 * T                                  # the notch's depth at the middle
    mid = h / 2.0
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()

    def cut(yc):                                     # where the left edge lies on this row (texel centre yc)
        return depth * (1 - abs(yc - mid) / mid)

    for y in range(h):
        yc = y + 0.5
        lb = cut(yc)
        rb = w - (cut(yc) if notch_right else 0.0)
        for x in range(w):
            # how far this texel's centre lies inside the left and right edges, in texels
            dl = x + 0.5 - lb
            dr = rb - (x + 0.5)
            d = min(dl, dr)
            if d < -0.5:
                continue
            if d < 0.5:                              # the edge crosses this texel: outline, part covered
                cov = d + 0.5
                px[x, y] = outline[:3] + (int(round(255 * cov)),)
                continue
            dv = min(y, h - 1 - y)                   # rows from the top or bottom
            if d < ow or dv < ow:
                px[x, y] = outline
            elif d < ow + 1 and dv >= ow:            # the step between outline and face: a blend
                frac = d - ow
                inner = BRASS if (brass and dl <= dr) else face
                px[x, y] = mix(outline, inner, max(0.35, min(0.65, frac)))
            elif brass and dl < ow + 4 * T:
                px[x, y] = BRASS
            else:
                px[x, y] = face
    im.save(OUT / (name + ".png"))
    left = 3 + (4 if brass else 0)
    meta(name, {"type": "nine_slice", "width": 24, "height": 19,
                "border": {"left": left, "top": 9, "right": 3, "bottom": 9}, "stretch_inner": True})


def rail_wood():
    """
    The rail's backdrop (kit/rail_wood): PLANK_B with a tone-on-tone ship's deck in it, only
    WOOD_LO for the darks and PLANK_A for the lights. Long boards with fine grain, staggered board
    ends with two nail heads each, a few knots, and a faint embossed compass rose and rope coil.
    100x160 GUI px, so the rail shows one tile across and a motif only every 160px down.
    """
    import math
    S = T // 2                                   # the layout below is in half GUI px
    rnd = random.Random(31)
    W, H = 100 * T, 160 * T
    im = Image.new("RGBA", (W, H), PLANK_B)
    px = im.load()

    def put(x, y, c):
        px[x % W, y % H] = c

    def dot(x, y, c, n=S):                       # a square n texels wide
        for dx in range(n):
            for dy in range(n):
                put(x + dx, y + dy, c)

    # fine grain: long broken streaks one texel thin, darks more often than lights
    for y in range(H):
        for _ in range(2):
            if rnd.random() < 0.45:
                x0 = rnd.randrange(W)
                ln = rnd.randrange(30 * S, 140 * S)
                c = WOOD_LO if rnd.random() < 0.7 else PLANK_A
                for i in range(ln):
                    if rnd.random() < 0.8:
                        put(x0 + i, y, c)
    # boards 20 GUI px tall: a seam under each, and staggered ends with two nails
    bh = 20 * T
    for b in range(H // bh):
        top = b * bh
        for x in range(W):
            dot(x, top + bh - S, WOOD_LO, 1)
            for k in range(S):
                put(x, top + bh - 1 - k, WOOD_LO)
            put(x, top, PLANK_A if x % 3 else PLANK_B)
        end = (b * 67 * S + 23 * S) % W
        for y in range(top, top + bh - S):
            for k in range(S):
                put(end + k, y, WOOD_LO)
                put(end + S + k, y, PLANK_A)
        for ny in (top + 5 * S, top + bh - 7 * S):
            for nx in (end - 5 * S, end + 4 * S):
                dot(nx, ny, WOOD_LO, 2 * S)
                dot(nx, ny, PLANK_A, S)          # the head's lit corner
    # a few knots: small dark rings with a light rim
    for (kx, ky, r) in [(40, 60, 4), (150, 170, 3), (90, 285, 5)]:
        for a in range(0, 360, 4):
            t = math.radians(a)
            put(int(S * (kx + r * 1.8 * math.cos(t))), int(S * (ky + r * math.sin(t))), WOOD_LO)
            put(int(S * (kx + (r + 1) * 1.8 * math.cos(t))), int(S * (ky + (r + 1) * math.sin(t))), PLANK_A)

    def emboss(points):
        for (x, y) in points:
            dot(x + S, y + S, WOOD_LO)
        for (x, y) in points:
            dot(x, y, PLANK_A)

    # a compass rose: a ring, four long points and four short ones
    cx, cy = 120 * S, 105 * S
    pts = set()
    for a in range(0, 360, 2):
        t = math.radians(a)
        pts.add((int(round(cx + 16 * S * math.cos(t))), int(round(cy + 16 * S * math.sin(t)))))
    for k in range(8):
        t = math.radians(k * 45 - 90)
        ln = (26 if k % 2 == 0 else 13) * S
        for i in range(ln):
            pts.add((int(round(cx + i * math.cos(t))), int(round(cy + i * math.sin(t)))))
    emboss(pts)
    # a rope coil: a loose spiral
    rx, ry = 60 * S, 245 * S
    pts = set()
    for i in range(1200):
        t = i / 60.0
        r = (3 + 1.6 * t) * S
        if r > 20 * S:
            break
        pts.add((int(round(rx + r * math.cos(t))), int(round(ry + r * math.sin(t)))))
    emboss(pts)
    im.save(OUT / "rail_wood.png")
    meta("rail_wood", {"type": "tile", "width": W // T, "height": H // T})
    check_seamless(im, bh)


def check_seamless(im, period):
    """
    The tile laid 3x3: the rows and columns that meet at a seam must look like any other pair of
    neighbours inside the tile (mean brightness within one shade), or the run stops.
    """
    W, H = im.size
    big = Image.new("RGBA", (3 * W, 3 * H))
    for i in range(3):
        for j in range(3):
            big.paste(im, (i * W, j * H))
    g = big.convert("L").load()

    def row(y):
        return sum(g[x, y] for x in range(W, 2 * W)) / W

    def col(x):
        return sum(g[x, y] for y in range(H, 2 * H)) / H

    # a seam row pair against the same pair one board further in
    for (a, b) in [(H - 1, H), (2 * H - 1, 2 * H)]:
        inner_a, inner_b = a + period, b + period
        assert abs(row(a) - row(inner_a)) <= 1.0 and abs(row(b) - row(inner_b)) <= 1.0, (row(a), row(inner_a), row(b), row(inner_b))
    # columns: the two sides of the seam and the columns next to them
    for x in (W - 1, W, 2 * W - 1, 2 * W):
        assert abs(col(x) - col(x + 3)) <= 1.5 or abs(col(x) - col(x - 3)) <= 1.5, (x, col(x), col(x + 3), col(x - 3))
    print("rail_wood seamless: rows", round(row(H - 1), 2), round(row(H), 2), "columns", round(col(W - 1), 2), round(col(W), 2))


def rail_rule():
    """An engraved line: a dark groove over a light lip, stretched to its width."""
    w, h = 16 * T, 2 * T
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    for x in range(w):
        for y in range(T):
            px[x, y] = WOOD_EDGE
        for y in range(T, h):
            px[x, y] = WOOD_HI[:3] + (110,)
    im.save(OUT / "rail_rule.png")
    meta("rail_rule", {"type": "stretch"})


if __name__ == "__main__":
    rail_wood()
    rail_rule()
    paper_tag("tag", PAPER_DEEP, LINE, T // 2)
    paper_tag("tag_hover", PAPER, LINE, T // 2)
    paper_tag("tag_on", SELECTED, GOLD_EDGE, T, brass=True, notch_right=False)
