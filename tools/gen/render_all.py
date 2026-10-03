"""Render every level of every look and the contact sheet, into tools/out/. One process, one image at a time.

    python render_all.py                 everything: 15 fronts, 15 floor plans, 3 backs, the sheet
    python render_all.py A               one look
    python render_all.py --sheet         only the five-level sheet

Writes tools/out/renders/<V><level>_front.png (front-right, the entrance side), <V>5_back.png
(back-left), <V><level>_plan.png (cut one block above the floor, the hut block, anvil, racks and door
outlined) and tools/out/runesmith-looks.png (levels 1-5 by looks A, B, C, all at one scale).
"""
import importlib
import os
import sys
import time

from PIL import Image, ImageDraw

from gates import run as run_gates
from isorender import font, render
from mcassets import parse_state
from voxel import RACK, with_ground

import paths

OUT = paths.out("renders")
SHEET5 = paths.out("runesmith-looks.png")

VARIANTS = {
    "A": ("Forge Hall", "stone and timber smithy, forge, great hall"),
    "B": ("Rune Tower", "smithy hall under a dark tower of runes"),
    "C": ("Crystal Heart", "round workshop, copper roof, growing crystal"),
}
LEVELS = (1, 2, 3, 4, 5)
TEXT = (232, 230, 240)
DIM = (160, 164, 182)
GOLD = (255, 206, 120)
BG = (18, 21, 29)


def build(v, level):
    mod = importlib.import_module(f"designs_{v.lower()}")
    return mod.LEVELS[level]()


def scene(s, margin=3, cut=None, depth=3):
    t = s
    if cut is not None:
        t = s.copy()
        t.blocks = {p: b for p, b in s.blocks.items() if p[1] <= cut}
    return with_ground(t, margin=margin, depth=depth, path=getattr(s, "path_cells", None))


def header(img, title, lines, size=34):
    """A dark band at the top with the title and a line or two of facts."""
    w, h = img.size
    band = 22 + size + 10 + len(lines) * int(size * 0.62 + 8)
    out = Image.new("RGB", (w, h + band), (14, 16, 22))
    out.paste(img, (0, band))
    d = ImageDraw.Draw(out)
    d.text((28, 16), title, font=font("title", size), fill=TEXT)
    y = 22 + size + 6
    for ln in lines:
        d.text((30, y), ln, font=font("sans_regular", int(size * 0.55)), fill=DIM)
        y += int(size * 0.62 + 8)
    return out, band


def facts_line(f):
    x, z, h = f["size"]
    return (f"{x} x {z} blocks, {h} high above ground - {f['racks']} racks ({len(f['singles'])} single, "
            f"{len(f['doubles'])} double) - light at work spots >= {f['light_work_min']}, inside >= {f['light_inside_min']}")


def render_variant(v):
    name, blurb = VARIANTS[v]
    os.makedirs(OUT, exist_ok=True)
    for level in LEVELS:
        t0 = time.time()
        s = build(v, level)
        problems, f = run_gates(s)
        if problems:
            raise SystemExit(f"{v}{level} fails its gates:\n  " + "\n  ".join(problems))
        title = f"{v} - {name}  |  Level {level}"
        img, prj = render(scene(s, margin=2, depth=2).blocks, view=0, width=1600, ss=2)
        img, band = header(img, title, [facts_line(f), "seen from the front-right (entrance side)"])
        img.save(os.path.join(OUT, f"{v}{level}_front.png"))
        if level == 5:
            img, prj = render(scene(s, margin=2, depth=2).blocks, view=2, width=1600, ss=2)
            img, band = header(img, title, [facts_line(f), "seen from the back-left"])
            img.save(os.path.join(OUT, f"{v}{level}_back.png"))
        render_plan(v, level, s, title)
        print(f"{v}{level}: {f['size']} racks {f['racks']}  {time.time() - t0:.1f}s")


def render_plan(v, level, s, title):
    """Cut the building away one row above the floor (a floor plan seen from above) and outline the
    functional blocks: the hut block, the anvil with its work tag, the racks, the door."""
    F = 2
    img, prj = render(scene(s, margin=1, cut=F + 1, depth=1).blocks, view=0, width=1600, ss=2, pad=170)
    marks = []
    if s.anchor:
        marks.append((s.anchor, "hut block", (255, 150, 60)))
    for p, names in s.tags.items():
        if "work" in names:
            marks.append((p, "anvil (work spot)", (90, 220, 255)))
    racks = sorted(p for p, b in s.blocks.items() if parse_state(b)[0] == RACK)
    for i, p in enumerate(racks):
        marks.append((p, f"racks ({len(racks)} blocks)" if i == 0 else None, (150, 230, 120)))
    doors = sorted(p for p, b in s.blocks.items() if parse_state(b)[0].endswith("_door")
                   and parse_state(b)[1].get("half") == "lower")
    for p in doors:
        marks.append((p, "door", (235, 235, 235)))
    callouts(img, prj, marks)
    img, band = header(img, title + "  |  floor plan",
                       ["cut one block above the floor; orange = hut block, blue = anvil (the work block), "
                        "green = racks, white = door"])
    img.save(os.path.join(OUT, f"{v}{level}_plan.png"))


def callouts(img, prj, marks):
    """Outline each marked block's top face; put the text tags in the left and right margins, stacked so
    they never overlap, with a leader line to the first block of each kind."""
    d = ImageDraw.Draw(img)
    W, H = img.size
    pts = []
    for (p, text, col) in marks:
        top = p[1] + 1.0
        quad = prj.world_to_screen([(p[0], top, p[2]), (p[0] + 1, top, p[2]), (p[0] + 1, top, p[2] + 1),
                                    (p[0], top, p[2] + 1)])
        d.polygon([tuple(q) for q in quad], outline=col, width=4)
        x, y = prj.world_to_screen([(p[0] + 0.5, top, p[2] + 0.5)])[0]
        if text:
            pts.append((x, y, text, col))
    cx = W / 2
    f = font("sans", 26)
    for side in (-1, 1):
        group = sorted([t for t in pts if (t[0] < cx) == (side < 0)], key=lambda t: t[1])
        if not group:
            continue
        lo, hi = 120, H - 80
        slots = []
        for i, (_, yy, _, _) in enumerate(group):
            yy = max(lo + i * 56, min(yy - 60, hi - (len(group) - i) * 56))
            if slots and yy < slots[-1] + 56:
                yy = slots[-1] + 56
            slots.append(yy)
        for (x, y, text, col), ty in zip(group, slots):
            bb = d.textbbox((0, 0), text, font=f)
            tw, th = bb[2] - bb[0], bb[3] - bb[1]
            tx = 30 if side < 0 else W - 30 - tw - 20
            ax = tx + tw + 20 if side < 0 else tx
            d.line([(x, y), (ax, ty)], fill=col, width=2)
            d.rounded_rectangle([tx, ty - th // 2 - 9, tx + tw + 20, ty + th // 2 + 9], radius=7,
                                fill=(18, 20, 28), outline=col, width=2)
            d.text((tx + 10, ty - th // 2 - bb[1]), text, font=f, fill=col)


def contact_sheet(levels=LEVELS, path=SHEET5, scale=17.0):
    """Columns A, B, C by rows of levels, all at one scale so the growth shows."""
    cells = {}
    for v in VARIANTS:
        for level in levels:
            s = build(v, level)
            problems, f = run_gates(s)
            img, prj = render(scene(s, margin=2).blocks, view=0, scale=scale, ss=2, pad=22, background=BG)
            cells[(v, level)] = (img, f)
    col_w = max(cells[(v, l)][0].size[0] for v in VARIANTS for l in levels)
    row_h = {l: max(cells[(v, l)][0].size[1] for v in VARIANTS) for l in levels}
    left, top, cap = 170, 300, 54
    W = left + 3 * col_w + 30
    H = top + sum(row_h[l] + cap for l in levels) + 40
    sheet = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(sheet)
    d.text((40, 26), f"RUNESMITH  -  three looks, levels {' / '.join(str(l) for l in levels)}",
           font=font("title", 64), fill=TEXT)
    d.text((44, 108), "stone smithy with rune and amethyst details  -  all at the same scale",
           font=font("sans_regular", 32), fill=DIM)
    for i, v in enumerate(VARIANTS):
        name, blurb = VARIANTS[v]
        cx = left + i * col_w + col_w // 2
        fv, fn, fb = font("title", 110), font("title", 54), font("sans_regular", 24)
        wv, wn = d.textlength(v, font=fv), d.textlength(name, font=fn)
        x0 = cx - (wv + 24 + wn) / 2
        d.text((x0, 150), v, font=fv, fill=GOLD)
        d.text((x0 + wv + 24, 180), name, font=fn, fill=TEXT)
        wb = d.textlength(blurb, font=fb)
        d.text((cx - wb / 2, 252), blurb, font=fb, fill=DIM)
    y = top
    for level in levels:
        d.text((34, y + row_h[level] // 2 - 70), "Level", font=font("sans", 34), fill=DIM)
        d.text((52, y + row_h[level] // 2 - 30), str(level), font=font("title", 96), fill=GOLD)
        for i, v in enumerate(VARIANTS):
            img, f = cells[(v, level)]
            x = left + i * col_w + (col_w - img.size[0]) // 2
            sheet.paste(img, (x, y + row_h[level] - img.size[1]))
            sx, sz, sh = f["size"]
            txt = f"{v}{level}:  {sx} x {sz},  {sh} high,  {f['racks']} racks"
            tw = d.textlength(txt, font=font("sans", 28))
            d.text((left + i * col_w + (col_w - tw) / 2, y + row_h[level] + 8), txt, font=font("sans", 28), fill=DIM)
        y += row_h[level] + cap
        d.line([(left, y - 8), (W - 30, y - 8)], fill=(40, 45, 58), width=2)
    sheet.save(path)
    print(path, sheet.size)


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--sheet"]:
        contact_sheet()
    else:
        for v in (args or list(VARIANTS)):
            render_variant(v.upper())
        if not args:
            contact_sheet()
