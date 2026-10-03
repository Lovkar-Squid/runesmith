"""Variant C - "Crystal Heart": a round deepslate workshop under a verdigris-copper roof, out of whose
crown grows a great amethyst crystal from a ring of glowing runes. The crystal grows at every level:

    1  the rotunda, crystal stage 1
    2  + a short forge annex on the east with its chimney, crystal stage 2
    3  the forge annex lengthened (two racks, quench tub), crystal stage 3
    4  + the library annex on the west (enchanting table, two racks), crystal stage 4
    5  + a ring of rune stones in a dark stone circle round the whole workshop, crystal stage 5

Coordinates: x east, y up, z south; the entrance is on the south side. y=1 is the ground surface,
the floor is raised one block (y=2), people walk at y=3. The hut block stands at (4, 3, 8) facing east
at every level.
"""
import math

from blocks import (anvil, barrel, campfire, candle, cauldron, chiseled_shelf, crystal, door, furnace,
                    grindstone, hut, lantern, lectern, pane, rack, slab, stairs, wall)
from kit import disc, fill_dark_pockets, gable_h, hang_under, outline, ring_roof, roof
from voxel import Structure

G, F = 1, 2
DBRICK = "minecraft:deepslate_bricks"
DTILE = "minecraft:deepslate_tiles"
POLISHED = "minecraft:polished_deepslate"
COBBLE = "minecraft:cobbled_deepslate"
CHISEL = "minecraft:chiseled_deepslate"
CRYING = "minecraft:crying_obsidian"
AMETHYST = "minecraft:amethyst_block"
COPPER = "oxidized_cut_copper"
COPPER_FULL = "minecraft:oxidized_cut_copper"
CX, CZ = 5, 5
R_WALL = 4.5           # walls on the outline of this disc: x/z 1..9
TOP = 8                # last wall row, under the copper eave
CROWN = TOP + 5        # the roof's crown: the rune ring the crystal grows from
HUT_POS = (CX - 1, 3, CZ + 3)


def _face(x, z):
    """The stair facing that points from (x, z) back towards the centre."""
    dx, dz = CX - x, CZ - z
    return ("east" if dx > 0 else "west") if abs(dx) >= abs(dz) else ("south" if dz > 0 else "north")


def lamp_post(s, x, z, top=4):
    s.set(x, 0, z, COBBLE); s.set(x, G, z, COBBLE)
    for y in range(F, top + 1):
        s.set(x, y, z, CHISEL if y == top - 1 else POLISHED)
    s.set(x, top + 1, z, lantern())


def _rotunda(s):
    floor = disc(CX, CZ, R_WALL)
    ring = outline(floor)
    for (x, z) in floor:
        s.set(x, 0, z, COBBLE); s.set(x, G, z, COBBLE)
        s.set(x, F, z, POLISHED)
    for (x, z) in outline(disc(CX, CZ, 2.2)):        # rune circle in the floor round the anvil
        s.set(x, F, z, DTILE)
    s.set(CX, F, CZ, CHISEL)
    for (x, z) in ring:
        s.set(x, F, z, COBBLE)
        for y in range(3, TOP + 1):
            s.set(x, y, z, DBRICK)
        s.set(x, TOP - 1, z, CHISEL)
    # pilasters: polished deepslate, one block proud, each with a glowing rune under the eave
    pil = [(CX, CZ - 5), (CX - 5, CZ), (CX + 5, CZ), (CX - 4, CZ - 3), (CX + 4, CZ - 3), (CX - 4, CZ + 3), (CX + 4, CZ + 3)]
    for (x, z) in pil:
        s.set(x, 0, z, COBBLE); s.set(x, G, z, COBBLE)
        for y in range(F, TOP + 1):
            s.set(x, y, z, POLISHED)
        s.set(x, TOP - 1, z, CRYING)
    outer = outline(disc(CX, CZ, R_WALL + 1)) - floor
    for (x, z) in outer:
        if s.get(x, F, z) is None:
            s.set(x, G, z, COBBLE); s.set(x, F, z, stairs("cobbled_deepslate", _face(x, z)))
    # tall windows of purple glass
    for (x, z) in ((CX - 2, CZ + 4), (CX + 2, CZ + 4), (CX + 4, CZ + 1), (CX + 4, CZ - 1), (CX - 4, CZ + 1),
                   (CX - 4, CZ - 1), (CX + 2, CZ - 4), (CX - 2, CZ - 4)):
        for y in (4, 5, 6):
            s.set(x, y, z, pane("purple"))
    # cornice, then the copper roof in rings up to the crown
    for (x, z) in outer:
        s.set(x, TOP, z, stairs("deepslate_brick", _face(x, z), "top"))
    RH = ring_roof(s, disc(CX, CZ, R_WALL + 1), [TOP + 1 + h for h in (0, 1, 2, 3, 4)], COPPER, COPPER_FULL,
                   CX, CZ, cap=CHISEL)
    # the crown: carved stone ring, glowing runes at the four points, the crystal's root in the middle
    for (x, z), h in RH.items():
        if h == CROWN:
            s.set(x, CROWN, z, CHISEL)
    for (x, z) in ((CX, CZ - 1), (CX, CZ + 1), (CX - 1, CZ), (CX + 1, CZ)):
        s.set(x, CROWN, z, CRYING)
    s.set(CX, CROWN, CZ, AMETHYST)
    # entrance: a portal of two columns and a carved lintel, a copper hood, a solid step, lamp posts
    DZ = CZ + 4
    s.set(CX, 3, DZ, door("dark_oak", "north", "lower")); s.set(CX, 4, DZ, door("dark_oak", "north", "upper"))
    s.set(CX, 5, DZ, CHISEL)
    for x in (CX - 1, CX + 1):
        s.set(x, 0, DZ + 1, COBBLE); s.set(x, G, DZ + 1, COBBLE)
        s.column(x, DZ + 1, F, 4, POLISHED)
        s.set(x, 5, DZ + 1, stairs("polished_deepslate", "west" if x < CX else "east", "top"))
    s.set(CX, 5, DZ + 1, slab("polished_deepslate", "top"))
    s.set(CX, 6, DZ + 1, CRYING)
    s.set(CX - 1, 6, DZ + 1, stairs(COPPER, "east")); s.set(CX + 1, 6, DZ + 1, stairs(COPPER, "west"))
    s.set(CX, 7, DZ + 1, slab(COPPER, "bottom"))
    s.set(CX, 0, DZ + 1, COBBLE); s.set(CX, G, DZ + 1, COBBLE); s.set(CX, F, DZ + 1, POLISHED)
    s.set(CX, G, DZ + 2, COBBLE); s.set(CX, F, DZ + 2, stairs("polished_deepslate", "north"))
    for x in (CX - 2, CX + 2):
        lamp_post(s, x, DZ + 2)
    # inside: the anvil on the rune circle under the crown, the hut block by the door
    s.set_anchor(*HUT_POS, hut("east"))
    s.set(CX, 3, CZ, anvil("east")); s.tag(CX, 3, CZ, "work")
    s.set(CX - 3, 3, CZ - 2, rack("east")); s.set(CX - 3, 3, CZ - 1, rack("east"))
    s.set(CX, 3, CZ - 3, furnace("south"))
    s.set(CX + 3, 3, CZ - 1, grindstone("west")); s.set(CX + 3, 3, CZ + 1, "minecraft:smithing_table")
    s.set(CX - 3, 3, CZ + 2, lectern("east")); s.set(CX - 3, 3, CZ + 1, chiseled_shelf("east"))
    s.set(CX - 3, 4, CZ + 1, candle("purple", 3))
    for (x, z) in ((CX, CZ), (CX - 2, CZ + 2), (CX + 1, CZ + 2), (CX - 2, CZ - 1), (CX + 2, CZ - 1)):
        hang_under(s, x, 5, z)


# the heart crystal, layer by layer: dy -> cells (dx, dz) relative to the crown centre, starting one
# block above the crown. A straight central prism, and shards that lean outwards by stepping one block
# per layer while keeping one block of overlap, so every block touches another face to face.
SQ3 = [(dx, dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)]
PLUS = [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)]
SQ2 = [(0, 0), (1, 0), (0, -1), (1, -1)]
CRYSTAL = {
    1: {0: SQ2, 1: SQ2, 2: SQ2, 3: [(0, 0), (1, -1)], 4: [(0, 0)]},
    2: {0: SQ2 + [(-1, 0), (2, -1)], 1: SQ2 + [(-1, 0), (2, -1)], 2: SQ2, 3: SQ2,
        4: [(0, 0), (1, -1)], 5: [(0, 0)]},
    3: {0: SQ2 + [(2, 0), (-1, 0), (-1, 1), (0, 1), (0, -2)],
        1: SQ2 + [(2, 0), (3, 0), (-1, 1), (-2, 1), (0, -2), (-1, -2)],
        2: SQ2 + [(3, 0), (-2, 1), (-1, -2)],
        3: SQ2 + [(-1, -2)],
        4: [(0, 0), (1, -1)], 5: [(0, 0)], 6: [(0, 0)]},
    4: {0: SQ3 + [(2, 0), (2, -1), (-2, 0), (-2, 1), (0, 2), (1, 2), (0, -2), (-1, -2)],
        1: SQ3 + [(2, -1), (3, -1), (-2, 1), (-3, 1), (1, 2), (1, 3), (-1, -2), (-1, -3)],
        2: PLUS + [(3, -1), (-3, 1), (1, 3), (-1, -3)],
        3: SQ2, 4: SQ2, 5: [(0, 0)], 6: [(0, 0)]},
    5: {0: SQ3 + [(2, 0), (2, -1), (-2, 0), (-2, 1), (0, 2), (1, 2), (0, -2), (-1, -2)],
        1: SQ3 + [(2, -1), (3, -1), (-2, 1), (-3, 1), (1, 2), (1, 3), (-1, -2), (-1, -3)],
        2: PLUS + [(3, -1), (3, -2), (-3, 1), (-3, 2), (1, 3), (2, 3), (-1, -3), (-2, -3)],
        3: PLUS + [(3, -2), (-3, 2), (-2, -3)],
        4: SQ2 + [(-2, -3)],
        5: SQ2, 6: [(0, 0)], 7: [(0, 0)]},
}
# (dx, dy, dz, size, facing): the tips and the small crystals round the shards, each on a full face
CRYSTAL_TIPS = {
    1: [(0, 5, 0, "cluster", "up"), (1, 4, -1, "large", "up"), (-1, 0, 0, "cluster", "up"),
        (0, 0, 1, "large", "up"), (2, 1, 0, "medium", "east")],
    2: [(0, 6, 0, "cluster", "up"), (1, 5, -1, "large", "up"), (-1, 2, 0, "cluster", "up"),
        (2, 2, -1, "large", "up"), (0, 0, 1, "medium", "up"), (2, 0, 0, "medium", "east")],
    3: [(0, 7, 0, "cluster", "up"), (1, 5, -1, "large", "up"), (3, 3, 0, "cluster", "up"),
        (-2, 3, 1, "cluster", "up"), (-1, 4, -2, "large", "up"), (1, 4, 0, "medium", "up"),
        (1, 0, 1, "medium", "up")],
    4: [(0, 7, 0, "cluster", "up"), (3, 3, -1, "cluster", "up"), (-3, 3, 1, "cluster", "up"),
        (1, 3, 3, "cluster", "up"), (-1, 3, -3, "large", "up"), (1, 5, -1, "large", "up"),
        (1, 3, 0, "medium", "up")],
    5: [(0, 8, 0, "cluster", "up"), (3, 4, -2, "cluster", "up"), (-3, 4, 2, "cluster", "up"),
        (2, 3, 3, "cluster", "up"), (-2, 5, -3, "cluster", "up"), (1, 5, 0, "large", "up"),
        (0, 5, -1, "medium", "up"), (-1, 4, 0, "large", "up")],
}


def _crystal(s, stage):
    y0 = CROWN + 1
    for dy, cells in CRYSTAL[stage].items():
        for (dx, dz) in cells:
            s.set(CX + dx, y0 + dy, CZ + dz, AMETHYST)
    for (dx, dy, dz, size, facing) in CRYSTAL_TIPS[stage]:
        s.set(CX + dx, y0 + dy, CZ + dz, crystal(size, facing))


# ---------------------------------------------------------------- annexes
def _annex(s, side, length):
    """A copper-roofed annex on the rotunda's east (side=+1, the forge) or west (side=-1, the library);
    `length` blocks long from the rotunda wall."""
    x_in, x_out = CX + 5 * side, CX + (4 + length) * side
    xs = range(min(x_in, x_out), max(x_in, x_out) + 1)
    Z0, Z1 = CZ - 2, CZ + 2
    for x in xs:
        for z in range(Z0, Z1 + 1):
            s.set(x, 0, z, COBBLE); s.set(x, G, z, COBBLE)
            s.set(x, F, z, POLISHED)
            for y in range(3, TOP):
                s.set(x, y, z, None)
            if z in (Z0, Z1) or x == x_out:
                s.set(x, F, z, COBBLE)
                for y in range(3, 7):
                    s.set(x, y, z, DBRICK)
                s.set(x, 7, z, POLISHED)
    for z in (Z0, Z1):
        s.column(x_out, z, F, 7, POLISHED)
    H = gable_h(min(x_in, x_out) - (1 if side > 0 else 0), max(x_in, x_out) + (0 if side > 0 else 1),
                Z0 - 1, Z1 + 1, 7, "x")
    roof(s, H, COPPER, peak=COPPER_FULL)
    for z in range(Z0 + 1, Z1):
        for y in range(8, H[(x_out, z)]):
            s.set(x_out, y, z, DBRICK)
    for z in range(Z0 - 1, Z1 + 2):
        h = H[(x_out, z)]
        s.set(x_out + side, h, z, stairs("deepslate_brick", "north" if z > CZ else "south") if z != CZ else POLISHED)
    s.set(x_out + side, H[(x_out, CZ)] + 1, CZ, CHISEL)
    s.set(x_out + side, H[(x_out, CZ)] + 2, CZ, crystal("cluster", "up"))
    for x in (x_in + side, x_in + 3 * side):              # purple windows on the long sides
        if min(x_in, x_out) < x < max(x_in, x_out):
            for z in (Z0, Z1):
                s.set(x, 4, z, pane("purple")); s.set(x, 5, z, pane("purple"))
    wall_x = CX + 4 * side                                  # a doorway from the rotunda
    for y in (3, 4):
        s.set(wall_x, y, CZ, None)
    s.set(wall_x, F, CZ, POLISHED)
    s.set(wall_x, 5, CZ, CHISEL)
    for x in xs:
        for z, f in ((Z0 - 1, "south"), (Z1 + 1, "north")):
            if s.get(x, F, z) is None or "stairs" in (s.get(x, F, z) or ""):
                s.set(x, G, z, COBBLE); s.set(x, F, z, stairs("cobbled_deepslate", f))
    return x_out


def _forge_annex(s, length):
    x_out = _annex(s, +1, length)
    # chimney on the gable: a broad base that steps into a stack, a glowing rune band
    cx = x_out + 1
    for y in range(0, 6):
        for z in (CZ - 1, CZ, CZ + 1):
            s.set(cx, y, z, DBRICK)
    s.set(cx, 6, CZ - 1, stairs("deepslate_brick", "south")); s.set(cx, 6, CZ + 1, stairs("deepslate_brick", "north"))
    top = 12 + length // 2
    for y in range(6, top):
        s.set(cx, y, CZ, DBRICK)
    s.set(cx, top - 4, CZ, CHISEL); s.set(cx, top - 3, CZ, CRYING); s.set(cx, top - 2, CZ, CHISEL)
    s.set(cx, top, CZ, campfire())
    # the forge: blast furnace against the chimney between magma blocks, a lantern over the work floor
    s.set(x_out - 1, 3, CZ, furnace("west"))
    if length >= 5:
        hang_under(s, x_out - 2, 5, CZ)
    s.set(x_out - 1, 3, CZ - 1, "minecraft:magma_block"); s.set(x_out - 1, 3, CZ + 1, "minecraft:magma_block")
    if length >= 5:
        # two racks against the north wall, a quench tub, a lantern on the barrel
        s.set(CX + 5, 3, CZ - 1, rack("south")); s.set(CX + 6, 3, CZ - 1, rack("south"))
        s.set(CX + 6, 3, CZ + 1, cauldron(water=True))
        s.set(CX + 5, 3, CZ + 1, barrel()); s.set(CX + 5, 4, CZ + 1, lantern())
    else:
        s.set(CX + 5, 3, CZ + 1, barrel()); s.set(CX + 5, 4, CZ + 1, lantern())


def _library_annex(s):
    x_out = _annex(s, -1, 5)
    for (dz, y) in ((0, 8), (-1, 9), (1, 9), (0, 10)):   # a round rune window in the west gable
        s.set(x_out, y, CZ + dz, pane("purple"))
    s.set(x_out, 9, CZ, CRYING)
    # inside: the enchanting table on a rune stone at the west end, shelves, two racks on the south wall
    s.set(x_out + 1, 3, CZ, "minecraft:enchanting_table"); s.set(x_out + 1, F, CZ, CHISEL)
    s.set(x_out + 1, 3, CZ - 1, "minecraft:bookshelf"); s.set(x_out + 2, 3, CZ - 1, "minecraft:bookshelf")
    s.set(x_out + 1, 3, CZ + 1, "minecraft:bookshelf")
    s.set(x_out + 2, 4, CZ - 1, candle("purple", 4))
    s.set(x_out + 2, 3, CZ + 1, rack("north")); s.set(x_out + 3, 3, CZ + 1, rack("north"))
    hang_under(s, x_out + 3, 5, CZ)


# ---------------------------------------------------------------- level 5: the ring of rune stones
def _rune_circle(s):
    R0, R1 = 9.0, 10.05
    cells = [(x, z) for x in range(CX - 11, CX + 12) for z in range(CZ - 11, CZ + 12)
             if R0 < math.hypot(x - CX, z - CZ) <= R1]
    for (x, z) in cells:
        if s.get(x, G, z) is None:
            s.set(x, 0, z, COBBLE)
            s.set(x, G, z, POLISHED)
    stones = [(CX - 10, CZ), (CX + 10, CZ), (CX, CZ - 10), (CX - 7, CZ - 7), (CX + 7, CZ - 7), (CX - 7, CZ + 7),
              (CX + 7, CZ + 7), (CX - 2, CZ + 10), (CX + 2, CZ + 10)]
    for (x, z) in stones:
        s.set(x, 0, z, COBBLE); s.set(x, G, z, POLISHED)
        s.set(x, F, z, POLISHED); s.set(x, 3, z, CRYING); s.set(x, 4, z, POLISHED); s.set(x, 5, z, CHISEL)
        s.set(x, 6, z, crystal("large", "up"))
    for ang in range(0, 360, 45):                         # glowing runes set in the ground between them
        a = math.radians(ang + 22.5)
        x, z = int(round(CX + 9.6 * math.cos(a))), int(round(CZ + 9.6 * math.sin(a)))
        if (x, z) in cells:
            s.set(x, 0, z, COBBLE); s.set(x, G, z, CRYING)
    for z in range(CZ + 7, CZ + 11):                       # the path from the circle to the door
        s.set(CX, 0, z, COBBLE); s.set(CX, G, z, DTILE)


# ---------------------------------------------------------------- the five levels
def _build(name, stage, forge=0, library=False, circle=False):
    s = Structure(name)
    _rotunda(s)
    if forge:
        _forge_annex(s, forge)
    if library:
        _library_annex(s)
    if circle:
        _rune_circle(s)
    _crystal(s, stage)
    fill_dark_pockets(s, COPPER_FULL)
    s.path_cells = [(CX, z) for z in range(CZ + (11 if circle else 7), CZ + (14 if circle else 10))]
    return s.finalize()


def level1():
    return _build("C1", 1)


def level2():
    return _build("C2", 2, forge=3)


def level3():
    return _build("C3", 3, forge=5)


def level4():
    return _build("C4", 4, forge=5, library=True)


def level5():
    return _build("C5", 5, forge=5, library=True, circle=True)


LEVELS = {1: level1, 2: level2, 3: level3, 4: level4, 5: level5}
