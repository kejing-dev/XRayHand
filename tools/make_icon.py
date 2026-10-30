"""X-ray Hand icon: the PICO 3D icon layers and the 512 export, drawn from the same shapes as the adaptive icon
(`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python tools/make_icon.py [export.png]

Layer 0: the lightbox-slate tile. Layer 1: the ivory bone hand + the x-ray cyan dot at the wrist (design palette.icon).
sdf_N = the layer's alpha in white. Coordinates are the vector's 108-unit viewport, after its group transform
(translate 3, -4, scale 0.95).
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
SLATE = (0x3A, 0x43, 0x50, 255)
IVORY = (0xEA, 0xE2, 0xCF, 255)
CYAN = (0xBF, 0xEF, 0xFF, 255)

SEGS = [
    ((49, 74), (44, 58)), ((44, 56), (42, 47)), ((42, 45), (41, 39)), ((41, 37), (40.5, 33)),
    ((54, 73), (54, 57)), ((54, 55), (54, 45)), ((54, 43), (54, 37)), ((54, 35), (54, 31)),
    ((59, 74), (63, 58)), ((63.3, 56), (65, 47)), ((65.3, 45), (66.3, 39.5)), ((66.5, 37.5), (67, 34)),
    ((63, 76), (70, 63)), ((70.8, 61), (74, 54)), ((74.6, 52), (76, 48)), ((76.4, 46), (77, 43)),
    ((46, 77), (37, 68)), ((35.8, 66.5), (31, 60)), ((30, 58.5), (27.5, 55)),
]
CARPALS = [(48, 79), (53.5, 78), (59, 79), (50.5, 83), (56.5, 83)]


GAP = 1.0  # each bone stops this far short of its joint so the joints read as gaps
WIDTH = 2.8


def shrink(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = (dx * dx + dy * dy) ** 0.5
    return (a[0] + dx / n * GAP, a[1] + dy / n * GAP), (b[0] - dx / n * GAP, b[1] - dy / n * GAP)


def T(p):
    return ((p[0] * 0.95 + 3.0) * K, (p[1] * 0.95 - 4.0) * K)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=SLATE)
    return im


def front():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = WIDTH * 0.95 * K
    for a, b in SEGS:
        a, b = shrink(a, b)
        pa, pb = T(a), T(b)
        d.line((pa, pb), fill=IVORY, width=int(w))
        for p in (pa, pb):
            d.ellipse((p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2), fill=IVORY)
    r = 2.3 * 0.95 * K
    for c in CARPALS:
        p = T(c)
        d.ellipse((p[0] - r, p[1] - r, p[0] + r, p[1] + r), fill=IVORY)
    r = 2.6 * 0.95 * K
    p = T((54, 88))
    d.ellipse((p[0] - r, p[1] - r, p[0] + r, p[1] + r), fill=CYAN)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        full = Image.new("RGBA", (S, S), SLATE)
        Image.alpha_composite(full, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
