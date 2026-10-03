"""A quick isometric preview of a voxel Structure, for a human to glance at without Minecraft.

Flat colours by block family and three shades for the three visible faces (top, south, east): not
textures, but enough to see that the roof sits on the walls, the door has a porch in front of it
and the lanterns hang where they should. The view is from the south-east, so the door side is the
left face.

    python isorender.py            ->  tools/out/runesmith1.png  and  tools/out/runesmith1_cutaway.png

The cutaway leaves out the roof and the ceiling and cuts the two near walls (east and south) down
to the base course, so the room can be seen; it marks the hut block (orange ring) and the `work`
tag (cyan ring).
"""
from PIL import Image, ImageDraw, ImageFont

import paths
from voxel import parse_state

# first substring of the block's short name that matches wins
COLOURS = [
    ("blocksolidsubstitution", (118, 92, 66)), ("blocksubstitution", (96, 140, 78)),
    ("chiseled", (176, 178, 190)), ("polished_andesite", (150, 152, 156)), ("stone_brick", (132, 134, 140)),
    ("stripped_spruce", (142, 108, 68)), ("spruce_log", (86, 62, 38)), ("spruce_door", (78, 50, 26)),
    ("spruce_planks", (122, 92, 54)), ("spruce_stairs", (150, 112, 66)), ("glass", (168, 214, 236)),
    ("lantern", (255, 206, 92)), ("chain", (96, 100, 110)), ("anvil", (58, 60, 70)),
    ("rack", (184, 138, 88)), ("blockhut", (255, 128, 32)),
]
UNKNOWN = (200, 60, 200)        # a block the preview has no colour for: loud on purpose
BACKGROUND = (22, 24, 32)
EDGE = 5 / 16
U = 20                           # pixels per half block width
V = 22                           # pixels per block height


def colour(name):
    short = name.split(":")[1]
    for key, rgb in COLOURS:
        if key in short:
            return rgb
    return UNKNOWN


def boxes(name, props):
    """The block as a few boxes in cell coordinates (x0, y0, z0, x1, y1, z1): enough shape to read."""
    short = name.split(":")[1]
    if short.endswith("_stairs"):
        top = props.get("half") == "top"
        base = (0, 0.5, 0, 1, 1, 1) if top else (0, 0, 0, 1, 0.5, 1)
        y0, y1 = (0, 0.5) if top else (0.5, 1)
        back = {"north": (0, y0, 0, 1, y1, 0.5), "south": (0, y0, 0.5, 1, y1, 1),
                "east": (0.5, y0, 0, 1, y1, 1), "west": (0, y0, 0, 0.5, y1, 1)}[props.get("facing", "north")]
        return [base, back]
    if short.endswith("_slab"):
        t = props.get("type")
        return [(0, 0.5, 0, 1, 1, 1) if t == "top" else (0, 0, 0, 1, 1, 1) if t == "double" else (0, 0, 0, 1, 0.5, 1)]
    if short.endswith("_door"):
        side = {"east": (0, 0, 0, EDGE / 2, 1, 1), "west": (1 - EDGE / 2, 0, 0, 1, 1, 1),
                "south": (0, 0, 0, 1, 1, EDGE / 2), "north": (0, 0, 1 - EDGE / 2, 1, 1, 1)}
        return [side[props.get("facing", "north")]]       # a closed door lies on the side opposite its facing
    if short == "glass_pane":
        ew = props.get("east") == "true" or props.get("west") == "true"
        return [(0, 0, 7 / 16, 1, 1, 9 / 16)] if ew else [(7 / 16, 0, 0, 9 / 16, 1, 1)]
    if short == "lantern":
        y0 = 0.5 if props.get("hanging") == "true" else 0
        return [(5 / 16, y0, 5 / 16, 11 / 16, y0 + 7 / 16, 11 / 16)]
    if short == "chain":
        return [(7 / 16, 0, 7 / 16, 9 / 16, 1, 9 / 16)]
    if short == "anvil":
        long_x = props.get("facing") in ("east", "west")
        top = (0, 0.62, 0.2, 1, 1, 0.8) if long_x else (0.2, 0.62, 0, 0.8, 1, 1)
        return [(0.12, 0, 0.12, 0.88, 0.2, 0.88), (0.3, 0.2, 0.3, 0.7, 0.62, 0.7), top]
    if short == "blockminecoloniesrack":
        return [(0.1, 0, 0.1, 0.9, 0.95, 0.9)]
    return [(0, 0, 0, 1, 1, 1)]


def shade(rgb, f):
    return tuple(int(c * f) for c in rgb)


def render(s, path, cutaway_above=None, near_walls=None, title=None, scale=2):
    """Draw the structure to a PNG. cutaway_above=N leaves out every block at y >= N; near_walls=(x, z, y)
    also leaves out the blocks on the wall lines x and z from height y up (the walls nearest the viewer)."""
    (x0, y0, z0), (x1, y1, z1) = s.bounds()

    def proj(x, y, z):
        return (x - z) * U, ((x + z) * 0.5) * U - y * V

    corners = [proj(x, y, z) for x in (x0, x1 + 1) for y in (y0, y1 + 1) for z in (z0, z1 + 1)]
    pad = 26
    lo_u, hi_u = min(c[0] for c in corners) - pad, max(c[0] for c in corners) + pad
    lo_v, hi_v = min(c[1] for c in corners) - pad - 46, max(c[1] for c in corners) + pad
    w, h = int((hi_u - lo_u) * scale), int((hi_v - lo_v) * scale)
    img = Image.new("RGB", (w, h), BACKGROUND)
    d = ImageDraw.Draw(img)

    def pt(x, y, z):
        u, v = proj(x, y, z)
        return ((u - lo_u) * scale, (v - lo_v) * scale)

    def shown(p):
        if cutaway_above is not None and p[1] >= cutaway_above:
            return False
        return not (near_walls and (p[0] == near_walls[0] or p[2] == near_walls[1]) and p[1] >= near_walls[2])

    blocks = [(p, b) for p, b in s.blocks.items() if shown(p)]
    blocks.sort(key=lambda pb: (pb[0][0] + pb[0][1] + pb[0][2], pb[0][1], pb[0][0]))      # far to near
    for (x, y, z), b in blocks:
        name, props = parse_state(b)
        rgb = colour(name)
        for bx0, by0, bz0, bx1, by1, bz1 in boxes(name, props):
            ax0, ay0, az0, ax1, ay1, az1 = x + bx0, y + by0, z + bz0, x + bx1, y + by1, z + bz1
            faces = [
                ([pt(ax0, ay1, az0), pt(ax1, ay1, az0), pt(ax1, ay1, az1), pt(ax0, ay1, az1)], 1.0),    # top
                ([pt(ax0, ay0, az1), pt(ax1, ay0, az1), pt(ax1, ay1, az1), pt(ax0, ay1, az1)], 0.78),   # south
                ([pt(ax1, ay0, az0), pt(ax1, ay0, az1), pt(ax1, ay1, az1), pt(ax1, ay1, az0)], 0.58),   # east
            ]
            for poly, f in faces:
                d.polygon(poly, fill=shade(rgb, f), outline=shade(rgb, f * 0.62))

    if cutaway_above is not None:         # markers on the room: only meaningful once the roof is off
        for pos, names in s.tags.items():
            cx, cy = pt(pos[0] + 0.5, pos[1] + 1.25, pos[2] + 0.5)
            r = 7 * scale
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(80, 230, 255), width=2 * scale)
        if s.anchor:
            cx, cy = pt(s.anchor[0] + 0.5, s.anchor[1] + 1.25, s.anchor[2] + 0.5)
            r = 7 * scale
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(255, 140, 40), width=2 * scale)

    img = img.resize((w // scale, h // scale), Image.LANCZOS) if scale > 1 else img
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.load_default(size=13)
    except TypeError:                     # Pillow without a sized default font
        font = ImageFont.load_default()
    sx, sy, sz = s.size()
    lines = [title or s.name, f"{sx} x {sy} x {sz} blocks, seen from the south-east (door on the left face)"]
    if cutaway_above is not None:
        lines.append("roof, ceiling and near walls cut away")
        lines.append("orange ring = hut block, cyan ring = work tag")
    for i, line in enumerate(lines):
        d.text((10, 8 + i * 17), line, fill=(214, 218, 230), font=font)
    img.save(path)
    return path


if __name__ == "__main__":
    import designs
    s = designs.runesmith1()
    full = render(s, paths.out("runesmith1.png"), title="runesmith1")
    cut = render(s, paths.out("runesmith1_cutaway.png"), cutaway_above=designs.Y_BEAM,
                 near_walls=(designs.X1, designs.Z1, designs.Y_BASE + 1), title="runesmith1, cutaway")
    print(full)
    print(cut)
