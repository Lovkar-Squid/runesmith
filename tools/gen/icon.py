"""The pack icon: an anvil with a rune glowing over it, drawn with Pillow.

An original drawing, nothing copied from any game or mod texture. It is drawn at 8x and scaled
down, so the edges are smooth at the 64 x 64 the pack shows.
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SIZE = 64
SS = 8                                   # supersampling factor


def _p(points):
    return [(x * SS, y * SS) for x, y in points]


def draw_icon(path):
    s = SIZE * SS
    # night-violet background, darker toward the bottom
    y = np.linspace(0.0, 1.0, s)[:, None, None]
    top, bottom = np.array([36.0, 26.0, 64.0]), np.array([12.0, 10.0, 26.0])
    arr = np.broadcast_to(top + (bottom - top) * y, (s, s, 3)).copy()
    # a soft purple glow behind the rune
    yy, xx = np.mgrid[0:s, 0:s]
    d = np.hypot(xx - 32 * SS, yy - 24 * SS) / (30 * SS)
    glow = np.clip(1.0 - d, 0.0, 1.0) ** 2
    arr += np.array([160.0, 78.0, 235.0]) * glow[..., None] * 0.75
    img = Image.fromarray(np.clip(arr, 0, 255).astype("uint8"), "RGB")
    dr = ImageDraw.Draw(img)

    # anvil: horn, face plate, waist, base and foot
    iron, light, shade = (86, 90, 106), (150, 156, 176), (44, 46, 58)
    dr.polygon(_p([(8, 38), (19, 33), (19, 41)]), fill=iron)                       # horn
    dr.rectangle(_p([(18, 33), (55, 41)]), fill=iron)                              # face plate
    dr.polygon(_p([(26, 41), (47, 41), (43, 49), (30, 49)]), fill=shade)           # waist
    dr.rectangle(_p([(23, 49), (50, 55)]), fill=iron)                              # base
    dr.rectangle(_p([(18, 55), (55, 59)]), fill=shade)                             # foot
    dr.line(_p([(10, 38), (19, 34), (55, 34)]), fill=light, width=int(1.4 * SS))   # lit top edge
    dr.line(_p([(19, 41), (55, 41)]), fill=shade, width=int(1.0 * SS))             # shadow under the plate
    dr.line(_p([(24, 49), (49, 49)]), fill=light, width=int(0.8 * SS))

    # the rune (Algiz: a stem with two arms), glowing over the face plate
    stem = [(32, 28), (32, 8)]
    arms = [[(32, 19), (23, 9)], [(32, 19), (41, 9)]]
    layer = Image.new("L", (s, s), 0)
    ld = ImageDraw.Draw(layer)
    for seg in [stem] + arms:
        ld.line(_p(seg), fill=255, width=int(4 * SS))
    halo = layer.filter(ImageFilter.GaussianBlur(radius=3.2 * SS))
    img = Image.composite(Image.new("RGB", (s, s), (176, 98, 255)), img, halo.point(lambda v: min(255, int(v * 1.6))))
    dr = ImageDraw.Draw(img)
    for seg in [stem] + arms:
        dr.line(_p(seg), fill=(238, 220, 255), width=int(2.4 * SS))
    for cx, cy in [(32, 8), (23, 9), (41, 9)]:                                     # rounded stroke ends
        r = 1.2 * SS
        dr.ellipse([cx * SS - r, cy * SS - r, cx * SS + r, cy * SS + r], fill=(238, 220, 255))
    # a few sparks
    for cx, cy, r in [(13, 18, 1.0), (51, 14, 1.2), (47, 27, 0.8), (17, 29, 0.7)]:
        dr.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=(222, 196, 255))

    img.resize((SIZE, SIZE), Image.LANCZOS).save(path)
    return path


if __name__ == "__main__":
    import paths
    print(draw_icon(paths.out("runesmith_icon.png")))
