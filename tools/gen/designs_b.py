"""Variant B - "Rune Tower": a tuff-stone smithy hall with a dark rune tower at its front corner.
The tower holds the entrance and the hut block; bands of carved blackstone with glowing runes ring it.

    1  the tower (crenellated band, slender spire) and the smithy hall
    2  + the forge annex on the east (barred forge front, chimney); the tower one storey taller
    3  + the rune store behind the hall (double racks); the tower gets its great shingled spire
    4  + the library wing west of the tower, a paved forecourt with rune posts
    5  the tower's top rebuilt as an open rune-lantern stage, a parapet with amethyst points and a
       needle spire

Coordinates: x east, y up, z south; the entrance is on the south side. y=1 is the ground surface,
the floor is raised one block (y=2), people walk at y=3. The hut block stands at (2, 3, 8) facing east
at every level.
"""
from blocks import (anvil, bars, barrel, campfire, candle, cauldron, chiseled_shelf, crystal, door, furnace,
                    grindstone, hut, lantern, lectern, log, pane, rack, stairs, trapdoor, wall)
from kit import gable_h, hang_under, roof, steep_fill
from voxel import Structure

G, F = 1, 2
# tower
BLACK = "minecraft:polished_blackstone_bricks"
BLACK_SMOOTH = "minecraft:polished_blackstone"
CHISEL_B = "minecraft:chiseled_polished_blackstone"
CRYING = "minecraft:crying_obsidian"
# hall
TUFF = "minecraft:tuff_bricks"
TUFF_P = "minecraft:polished_tuff"
TUFF_C = "minecraft:chiseled_tuff_bricks"
TUFF_CH = "minecraft:chiseled_tuff"
COBBLE = "minecraft:cobbled_deepslate"
TILES = "minecraft:deepslate_tiles"
POLISHED = "minecraft:polished_deepslate"
CHISEL_D = "minecraft:chiseled_deepslate"
AMETHYST = "minecraft:amethyst_block"
WOOD = "spruce"
ROOF = "deepslate_tile"
TROOF = "polished_blackstone_brick"


def beam(axis):
    return log(WOOD, axis, stripped=True)


def skirt(s, cells, mat="cobbled_deepslate"):
    """Sloped plinth stairs: {(x, z): facing towards the wall}; only where nothing stands yet."""
    for (x, z), f in cells.items():
        if s.get(x, F, z) is None:
            s.set(x, G, z, COBBLE)
            s.set(x, F, z, stairs(mat, f))


def ring_cells(x0, z0, x1, z1):
    out = {}
    for x in range(x0, x1 + 1):
        out[(x, z0 - 1)] = "south"
        out[(x, z1 + 1)] = "north"
    for z in range(z0, z1 + 1):
        out[(x0 - 1, z)] = "east"
        out[(x1 + 1, z)] = "west"
    return out


def shutters(s, x, y, z, n=2):
    """Spruce shutters either side of a one-wide window in the south wall."""
    for dx in (-1, 1):
        for k in range(n):
            s.set(x + dx, y + k, z + 1, trapdoor(WOOD, "south", "bottom", True))


def finial(s, x, y, z):
    s.set(x, y, z, CHISEL_B)
    s.set(x, y + 1, z, crystal("cluster", "up"))


# ---------------------------------------------------------------- the tower
TX0, TX1, TZ0, TZ1 = 1, 5, 5, 9
TCX, TCZ = 3, 7
DOOR = (3, 3, TZ1)
HUT_POS = (2, 3, 8)
TOWER = {(x, z) for x in range(TX0, TX1 + 1) for z in range(TZ0, TZ1 + 1)}


def _band(s, y):
    """A rune band: carved blackstone all round, a glowing rune in the middle of each face."""
    for x in range(TX0, TX1 + 1):
        for z in (TZ0, TZ1):
            s.set(x, y, z, CHISEL_B)
    for z in range(TZ0, TZ1 + 1):
        for x in (TX0, TX1):
            s.set(x, y, z, CHISEL_B)
    for (x, z) in ((TCX, TZ0), (TCX, TZ1), (TX0, TCZ), (TX1, TCZ)):
        s.set(x, y, z, CRYING)


def _tower(s, top, spire="slender", west_pilasters=True):
    """The rune tower with walls up to y=top. spire: 'slender' (crenellated band, needle spire on the
    tower body), 'great' (steep spire over the whole corbelled eave) or 'crown' (open rune-lantern
    stage in the top rows, parapet with amethyst points, needle spire)."""
    s.box(TX0, 0, TZ0, TX1, G, TZ1, COBBLE)
    s.box(TX0 + 1, F, TZ0 + 1, TX1 - 1, F, TZ1 - 1, POLISHED)
    s.set(TCX, F, TCZ, CHISEL_D)
    s.walls(TX0, TZ0, TX1, TZ1, F, top, BLACK)
    for x in (TX0, TX1):
        for z in (TZ0, TZ1):
            s.column(x, z, F, top, BLACK_SMOOTH)
    corners = [(TX1, TZ1, ((0, 1),))]
    if west_pilasters:
        corners += [(TX0, TZ1, ((-1, 0), (0, 1))), (TX0, TZ0, ((-1, 0), (0, -1)))]
    else:
        corners += [(TX0, TZ1, ((0, 1),))]
    for (x, z, sides) in corners:
        for (dx, dz) in sides:
            s.column(x + dx, z + dz, G, 5, BLACK_SMOOTH)
            face = {(-1, 0): "east", (1, 0): "west", (0, 1): "north", (0, -1): "south"}[(dx, dz)]
            s.set(x + dx, 6, z + dz, stairs(TROOF, face))
    cells = {k: v for k, v in ring_cells(TX0, TZ0, TX1, TZ1).items() if k[0] <= TX1}
    if not west_pilasters:
        cells = {k: v for k, v in cells.items() if k[0] >= TX0}
    skirt(s, cells, mat=TROOF)
    for y in range(6, top - 1, 5):
        _band(s, y)
    for y0 in range(8, top - 1, 5):                      # slit windows of purple glass between the bands
        for (x, z) in ((TCX, TZ1), (TX0, TCZ), (TCX, TZ0), (TX1, TCZ)):
            s.set(x, y0, z, pane("purple")); s.set(x, y0 + 1, z, pane("purple"))
    # the door under a pointed arch, a solid stone step, soul lanterns on carved posts either side
    x, y, z = DOOR
    s.set(x, y, z, door("dark_oak", "north", "lower")); s.set(x, y + 1, z, door("dark_oak", "north", "upper"))
    s.set(x - 1, y + 2, z, stairs(TROOF, "east", "top")); s.set(x + 1, y + 2, z, stairs(TROOF, "west", "top"))
    s.set(x, y + 2, z, CHISEL_B)
    s.set(x, G, z + 1, COBBLE); s.set(x, F, z + 1, BLACK_SMOOTH)
    for dx in (-1, 1):
        s.set(x + dx, G, z + 1, COBBLE)
        s.set(x + dx, F, z + 1, BLACK_SMOOTH)
        s.set(x + dx, 3, z + 1, CHISEL_B)
        s.set(x + dx, 4, z + 1, lantern(soul=True))
    if spire == "great":
        _corbel(s, top + 1)
        _spire(s, top + 2, TX0 - 1, TX1 + 1, TZ0 - 1, TZ1 + 1, needle=False)
    elif spire == "slender":
        _corbel(s, top + 1)
        _crenels(s, top + 2)
        _spire(s, top + 2, TX0, TX1, TZ0, TZ1, needle=True)
    else:
        # the top rows become an open lantern stage over a stone floor
        for yy in (top - 2, top - 1):
            for xx in range(TX0 + 1, TX1):
                for zz in (TZ0, TZ1):
                    s.set(xx, yy, zz, None)
            for zz in range(TZ0 + 1, TZ1):
                for xx in (TX0, TX1):
                    s.set(xx, yy, zz, None)
        for xx in range(TX0 + 1, TX1):
            for zz in (TZ0, TZ1):
                s.set(xx, top, zz, stairs(TROOF, "south" if zz == TZ0 else "north", "top"))
                s.set(xx, top - 3, zz, wall("polished_blackstone_brick"))
        for zz in range(TZ0 + 1, TZ1):
            for xx in (TX0, TX1):
                s.set(xx, top, zz, stairs(TROOF, "east" if xx == TX0 else "west", "top"))
                s.set(xx, top - 3, zz, wall("polished_blackstone_brick"))
        for (xx, zz) in ((TCX, TZ0), (TCX, TZ1), (TX0, TCZ), (TX1, TCZ)):
            s.set(xx, top, zz, CRYING)
        s.box(TX0 + 1, top - 3, TZ0 + 1, TX1 - 1, top - 3, TZ1 - 1, BLACK)
        s.set(TCX, top - 3, TCZ, CRYING)
        _corbel(s, top + 1)
        _crenels(s, top + 2)
        for (xx, zz) in ((TX0 - 1, TZ0 - 1), (TX1 + 1, TZ0 - 1), (TX0 - 1, TZ1 + 1), (TX1 + 1, TZ1 + 1)):
            s.set(xx, top + 2, zz, BLACK_SMOOTH)
            s.set(xx, top + 3, zz, crystal("cluster", "up"))
        _spire(s, top + 2, TX0, TX1, TZ0, TZ1, needle=True)
        s.set(TCX, top - 1, TCZ, lantern(soul=True)) if False else None
        hang_under(s, TCX, top - 1, TCZ, soul=True)


def _corbel(s, c):
    for x in range(TX0 - 1, TX1 + 2):
        for z in (TZ0 - 1, TZ1 + 1):
            s.set(x, c, z, stairs(TROOF, "south" if z < TZ0 else "north", "top"))
    for z in range(TZ0, TZ1 + 1):
        s.set(TX0 - 1, c, z, stairs(TROOF, "east", "top")); s.set(TX1 + 1, c, z, stairs(TROOF, "west", "top"))
    s.box(TX0, c, TZ0, TX1, c, TZ1, BLACK)


def _crenels(s, y):
    for xx in range(TX0 - 1, TX1 + 2):
        for zz in range(TZ0 - 1, TZ1 + 2):
            edge = xx in (TX0 - 1, TX1 + 1) or zz in (TZ0 - 1, TZ1 + 1)
            if edge and (xx + zz) % 2 == 0:
                s.set(xx, y, zz, wall("polished_blackstone_brick"))


def _spire(s, y0, x0, x1, z0, z1, needle):
    """A steep shingled spire over the rectangle, rising two per ring, solid inside, an amethyst tip
    (on a short needle)."""
    H = {}
    for xx in range(x0, x1 + 1):
        for zz in range(z0, z1 + 1):
            d = min(xx - x0, x1 - xx, zz - z0, z1 - zz)
            H[(xx, zz)] = y0 + 2 * d
    roof(s, H, ROOF, peak=TILES)
    steep_fill(s, H, TILES)
    for (xx, zz), h in H.items():                       # no hollow inside the spire
        for yy in range(y0, h):
            if s.get(xx, yy, zz) is None:
                s.set(xx, yy, zz, TILES)
    peak = H[(TCX, TCZ)]
    y = peak + 1
    if needle:
        s.set(TCX, y, TCZ, BLACK_SMOOTH)
        y += 1
    s.set(TCX, y, TCZ, AMETHYST)
    s.set(TCX, y + 1, TCZ, crystal("cluster", "up"))


def _tower_inside(s):
    s.set_anchor(*HUT_POS, hut("east"))
    s.set(2, 3, 6, lectern("south")); s.set(4, 3, 6, chiseled_shelf("south"))
    s.set(4, 4, 6, candle("purple", 3))
    for z in (6, 7):
        for y in (3, 4):
            s.set(TX1, y, z, None)
        s.set(TX1, F, z, POLISHED)
    hang_under(s, TCX, 5, TCZ)


# ---------------------------------------------------------------- the hall
HX0, HX1, HZ0, HZ1 = 5, 10, 2, 8
PLATE = 7


def _hall(s):
    s.box(HX0, 0, HZ0, HX1, G, HZ1, COBBLE)
    s.box(HX0 + 1, F, HZ0 + 1, HX1 - 1, F, HZ1 - 1, POLISHED)
    s.walls(HX0, HZ0, HX1, HZ1, F, F, COBBLE)
    s.walls(HX0, HZ0, HX1, HZ1, 3, PLATE - 1, TUFF)
    for x, z in ((HX1, HZ0), (HX1, HZ1), (HX0, HZ0)):
        s.column(x, z, F, PLATE - 1, TUFF_P)
        s.set(x, 6, z, TUFF_CH)
    for x in range(HX0, HX1 + 1):
        s.set(x, PLATE, HZ0, beam("x")); s.set(x, PLATE, HZ1, beam("x"))
    for z in range(HZ0, HZ1 + 1):
        s.set(HX1, PLATE, z, beam("z"))
    skirt(s, {k: v for k, v in ring_cells(HX0, HZ0, HX1, HZ1).items() if k not in TOWER and (k[0] > TX1 or k[1] < TZ0)})
    # roof: gable along x, kept off the tower; both gable ends closed
    H = gable_h(HX0 - 1, HX1, HZ0 - 1, HZ1 + 1, PLATE, "x")
    H = {k: v for k, v in H.items() if k not in TOWER and not (k[0] < HX0 and k[1] >= TZ0 - 1)}
    roof(s, H, ROOF, peak=TILES)
    s.hallH = H
    for x in (HX0, HX1):
        for z in range(HZ0 + 1, HZ1):
            if (x, z) in TOWER:
                continue
            for y in range(PLATE + 1 if x == HX1 else PLATE, H[(x, z)]):
                if s.get(x, y, z) is None:
                    s.set(x, y, z, TUFF)
    # barge along the east gable, amethyst finial, a carved rune stone in the gable
    for z in range(HZ0 - 1, HZ1 + 2):
        h = H[(HX1, z)]
        s.set(HX1 + 1, h, z, stairs(ROOF, "south" if z < 5 else "north") if z != 5 else TILES)
    finial(s, HX1 + 1, H[(HX1, 5)] + 1, 5)
    s.set(HX1, PLATE + 2, 5, TUFF_C)
    # windows with spruce shutters
    for x in (7, 9):
        s.set(x, 4, HZ1, pane()); s.set(x, 5, HZ1, pane())
        s.set(x, 3, HZ1 + 1, stairs("tuff_brick", "north", "top"))
        shutters(s, x, 4, HZ1)
    for z in (4, 6):
        s.set(HX1, 4, z, pane()); s.set(HX1, 5, z, pane())
        s.set(HX1 + 1, 3, z, stairs("tuff_brick", "west", "top"))
    s.set(7, 4, HZ0, pane()); s.set(7, 5, HZ0, pane())
    # chimney through the north slope over the forge
    ctop = H[(9, 3)] + 4
    for y in range(F, ctop):
        s.set(9, y, HZ0, BLACK)
    s.set(9, H[(9, 3)] + 1, HZ0, CHISEL_B)
    s.set(9, ctop, HZ0, campfire(facing="south"))
    s.chimney = (9, HZ0, ctop)
    # inside: the anvil on a carved stone, the furnace, two racks, grindstone and smithing table
    s.set(9, 3, 3, furnace("south")); s.set(9, 4, 3, lantern())
    s.set(8, 3, 5, anvil("north")); s.tag(8, 3, 5, "work")
    s.set(8, F, 5, CHISEL_D)
    s.set(6, 3, 3, rack("south")); s.set(7, 3, 3, rack("south"))
    s.set(9, 3, 7, grindstone("west")); s.set(8, 3, 7, "minecraft:smithing_table")
    for x in range(HX0 + 1, HX1):
        s.set(x, PLATE, 5, beam("x"))
    hang_under(s, 7, 5, 5); hang_under(s, 9, 5, 5)


# ---------------------------------------------------------------- level 2: the forge annex (east)
EX0, EX1, EZ0, EZ1 = 10, 14, 4, 8
EPLATE = 6


def _forge_annex(s):
    s.box(EX0 + 1, 0, EZ0, EX1, G, EZ1, COBBLE)
    s.box(EX0 + 1, F, EZ0 + 1, EX1 - 1, F, EZ1 - 1, "minecraft:polished_blackstone")
    for z in range(EZ0, EZ1 + 1):
        for x in range(EX0 + 1, EX1 + 1):
            if z in (EZ0, EZ1) or x == EX1:
                s.set(x, F, z, COBBLE)
                for y in range(3, EPLATE):
                    s.set(x, y, z, TUFF)
    for (x, z) in ((EX1, EZ0), (EX1, EZ1)):
        s.column(x, z, F, EPLATE - 1, TUFF_P)
    for x in range(EX0 + 1, EX1 + 1):
        s.set(x, EPLATE, EZ0, beam("x")); s.set(x, EPLATE, EZ1, beam("x"))
    for z in range(EZ0, EZ1 + 1):
        s.set(EX1, EPLATE, z, beam("z"))
    # the forge front: a barred opening under a carved lintel, so the glow shows through
    for x in (11, 12, 13):
        s.set(x, 3, EZ1, bars()); s.set(x, 4, EZ1, bars())
    s.set(12, 5, EZ1, TUFF_C)
    # roof: gable along x against the hall's gable, flush east gable
    H = gable_h(EX0 + 1, EX1, EZ0 - 1, EZ1 + 1, EPLATE, "x")
    roof(s, H, ROOF, peak=TILES)
    for z in range(EZ0 + 1, EZ1):
        for y in range(EPLATE + 1, H[(EX1, z)]):
            s.set(EX1, y, z, TUFF)
    for z in range(EZ0 - 1, EZ1 + 2):
        h = H[(EX1, z)]
        s.set(EX1 + 1, h, z, stairs(ROOF, "south" if z < 6 else "north") if z != 6 else TILES)
    # chimney stack on the east gable, stepped, with a glowing rune
    for y in range(0, 14):
        s.set(EX1 + 1, y, 6, BLACK)
    for z in (5, 7):
        for y in range(0, 6):
            s.set(EX1 + 1, y, z, BLACK)
        s.set(EX1 + 1, 6, z, stairs(TROOF, "north" if z == 5 else "south"))
    s.set(EX1 + 1, 10, 6, CHISEL_B); s.set(EX1 + 1, 11, 6, CRYING); s.set(EX1 + 1, 12, 6, CHISEL_B)
    s.set(EX1 + 1, 14, 6, campfire())
    # inside: blast furnace against the chimney between magma blocks, two racks, quench tub, a lantern
    s.set(EX1 - 1, 3, 6, furnace("west"))
    s.set(EX1 - 1, 3, 5, "minecraft:magma_block"); s.set(EX1 - 1, 3, 7, "minecraft:magma_block")
    s.set(11, 3, 5, rack("south")); s.set(12, 3, 5, rack("south"))
    s.set(12, 3, 7, cauldron(water=True)); s.set(11, 3, 7, barrel()); s.set(11, 4, 7, lantern())
    # the hall's east windows now look into the annex: the sill goes, the window is walled up
    s.set(HX1 + 1, 3, 6, None)
    for y in (4, 5):
        s.set(HX1, y, 4, TUFF)
    # the hall's east wall opens into the annex
    for z in (5, 6):
        for y in (3, 4):
            s.set(HX1, y, z, None)
        s.set(HX1, 5, z, TUFF_C)
        s.set(HX1, F, z, POLISHED)
    skirt(s, {(x, EZ1 + 1): "north" for x in range(EX0 + 1, EX1 + 1)})
    skirt(s, {(x, EZ0 - 1): "south" for x in range(EX0 + 1, EX1 + 1)})
    skirt(s, {(EX1 + 1, z): "west" for z in range(EZ0 - 1, EZ1 + 2) if z not in (5, 6, 7)})


# ---------------------------------------------------------------- level 3: the rune store (north)
NX0, NX1, NZ0, NZ1 = 5, 10, -3, 2


def _store(s):
    s.box(NX0, 0, NZ0, NX1, G, NZ1 - 1, COBBLE)
    s.box(NX0 + 1, F, NZ0 + 1, NX1 - 1, F, NZ1 - 1, POLISHED)
    for x in range(NX0, NX1 + 1):
        s.set(x, F, NZ0, COBBLE)
        for y in range(3, PLATE):
            s.set(x, y, NZ0, TUFF)
    for z in range(NZ0, NZ1):
        for x in (NX0, NX1):
            s.set(x, F, z, COBBLE)
            for y in range(3, PLATE):
                s.set(x, y, z, TUFF)
    for (x, z) in ((NX0, NZ0), (NX1, NZ0)):
        s.column(x, z, F, PLATE - 1, TUFF_P)
    for z in range(NZ0, NZ1 + 1):
        s.set(NX0, PLATE, z, beam("z")); s.set(NX1, PLATE, z, beam("z"))
    for x in range(NX0, NX1 + 1):
        s.set(x, PLATE, NZ0, beam("x"))
    H = gable_h(NX0 - 1, NX1 + 1, NZ0 - 1, NZ1, PLATE, "z")
    Hall = getattr(s, "hallH", {})
    for kk in list(H):
        if kk in Hall:
            H[kk] = max(H[kk], Hall[kk])
    chim = (s.chimney[0], s.chimney[1])
    roof(s, H, ROOF, peak=TILES, skip={chim})
    for x in range(NX0 + 1, NX1):
        for y in range(PLATE + 1, H[(x, NZ0)]):
            s.set(x, y, NZ0, TUFF)
    for x in (NX0, NX1):                                 # close the gaps under the joined roofs
        for z in range(NZ0, NZ1 + 1):
            for y in range(PLATE + 1, H[(x, z)]):
                if s.get(x, y, z) is None:
                    s.set(x, y, z, TUFF)
    s.set(7, PLATE + 2, NZ0, TUFF_C)
    s.set(7, H[(7, NZ0 - 1)] + 1, NZ0 - 1, crystal("cluster", "up"))
    # a doorway through the hall's north wall
    for y in (3, 4):
        s.set(8, y, HZ0, None)
    s.set(8, F, HZ0, POLISHED); s.set(8, 5, HZ0, TUFF_C)
    # racks: two doubles on the north wall, two singles on the west wall; shelves on the east
    for mx in (6, 8):
        s.set(mx, 3, NZ0 + 1, rack("east", "blockrackempty"))
        s.set(mx + 1, 3, NZ0 + 1, rack("west", "blockrackair"))
    for z in (0, 1):
        s.set(NX0 + 1, 3, z, rack("east"))
        s.set(NX1 - 1, 3, z, "minecraft:bookshelf"); s.set(NX1 - 1, 4, z, "minecraft:bookshelf")
    s.set(NX1 - 1, 5, 1, candle("purple", 3))
    s.set(NX1 - 1, 5, 0, lantern())
    for z in (-2, 1):
        s.set(NX0, 4, z, pane()); s.set(NX0, 5, z, pane())
    s.set(NX1, 4, -2, pane()); s.set(NX1, 5, -2, pane())
    hang_under(s, 7, 5, -1)
    skirt(s, {k: v for k, v in ring_cells(NX0, NZ0, NX1, NZ1 - 1).items() if k[1] < NZ1})


# ---------------------------------------------------------------- level 4: the library (west of the tower)
WX0, WX1, WZ0, WZ1 = -4, 0, 4, 9
WPLATE = 7


def _library(s):
    for z in range(TZ0 - 1, TZ1 + 2):                    # the tower's west pilasters give way to the wing
        for y in range(G, 7):
            s.set(TX0 - 1, y, z, None)
    s.box(WX0, 0, WZ0, WX1, G, WZ1, COBBLE)
    s.box(WX0 + 1, F, WZ0 + 1, WX1 - 1, F, WZ1 - 1, POLISHED)
    s.walls(WX0, WZ0, WX1, WZ1, F, F, COBBLE)
    s.walls(WX0, WZ0, WX1, WZ1, 3, WPLATE - 1, TUFF)
    for (x, z) in ((WX0, WZ0), (WX0, WZ1), (WX1, WZ0), (WX1, WZ1)):
        s.column(x, z, F, WPLATE - 1, TUFF_P)
    for x in range(WX0, WX1 + 1):
        s.set(x, WPLATE, WZ0, beam("x")); s.set(x, WPLATE, WZ1, beam("x"))
    for z in range(WZ0, WZ1 + 1):
        s.set(WX0, WPLATE, z, beam("z")); s.set(WX1, WPLATE, z, beam("z"))
    H = gable_h(WX0 - 1, WX1 + 1, WZ0 - 1, WZ1 + 1, WPLATE, "z")
    roof(s, H, ROOF, peak=TILES, skip={(TX0, z) for z in range(TZ0, TZ1 + 1)})
    for z in (WZ0, WZ1):
        for x in range(WX0 + 1, WX1):
            for y in range(WPLATE + 1, H[(x, z)]):
                s.set(x, y, z, TUFF)
    for z in range(WZ0, WZ1 + 1):                       # close the strip between the wing and the tower
        for y in range(WPLATE + 1, H[(WX1, z)] + 1):
            if s.get(WX1, y, z) is None:
                s.set(WX1, y, z, TUFF)
    s.set(-2, H[(-2, WZ1 + 1)] + 1, WZ1 + 1, crystal("cluster", "up"))
    # a tall rune window to the south: purple glass in carved tuff, a glowing heart
    for y in range(3, 7):
        s.set(-2, y, WZ1, pane("purple"))
    for y in (4, 5):
        s.set(-3, y, WZ1, pane("purple")); s.set(-1, y, WZ1, pane("purple"))
    s.set(-2, 8, WZ1, CRYING)
    s.set(-3, 6, WZ1, stairs("tuff_brick", "east", "top")); s.set(-1, 6, WZ1, stairs("tuff_brick", "west", "top"))
    s.set(-2, 7, WZ1, TUFF_C)
    for z in (6, 7):
        s.set(WX0, 4, z, pane("purple")); s.set(WX0, 5, z, pane("purple"))
    # inside: the enchanting table on a rune stone, bookshelves, two racks, a hanging lantern
    s.set(-2, 3, 6, "minecraft:enchanting_table")
    s.set(-2, F, 6, CHISEL_D)
    for x in (-3, -2, -1):
        s.set(x, 3, WZ0 + 1, "minecraft:bookshelf")
    s.set(-1, 4, WZ0 + 1, candle("purple", 4))
    s.set(-3, 3, 8, rack("east")); s.set(-3, 3, 7, rack("east"))
    hang_under(s, -2, 5, 7)
    # doorway through to the tower
    for x in (WX1, TX0):
        s.set(x, 3, 7, None); s.set(x, 4, 7, None)
        s.set(x, F, 7, POLISHED)
    skirt(s, {k: v for k, v in ring_cells(WX0, WZ0, WX1, WZ1).items() if k[0] < WX1 + 1})


def _forecourt(s):
    """A paved forecourt with rune posts and soul lanterns before the tower door."""
    for x in range(0, 7):
        for z in (11, 12):
            s.set(x, 0, z, COBBLE)
            s.set(x, G, z, BLACK if (x + z) % 2 else BLACK_SMOOTH)
    for x in (0, 6):
        s.set(x, F, 12, BLACK_SMOOTH)
        s.set(x, 3, 12, CHISEL_B); s.set(x, 4, 12, BLACK_SMOOTH)
        s.set(x, 5, 12, lantern(soul=True))


# ---------------------------------------------------------------- the five levels
def _build(name, top, spire, annex=False, store=False, library=False):
    s = Structure(name)
    _tower(s, top, spire, west_pilasters=not library)
    _hall(s)
    _tower_inside(s)
    if annex:
        _forge_annex(s)
    if store:
        _store(s)
    if library:
        _library(s)
        _forecourt(s)
    s.path_cells = [(DOOR[0], z) for z in range(TZ1 + (4 if library else 2), TZ1 + 7)]
    return s.finalize()


def level1():
    return _build("B1", 11, "slender")


def level2():
    return _build("B2", 12, "slender", annex=True)


def level3():
    return _build("B3", 12, "great", annex=True, store=True)


def level4():
    return _build("B4", 12, "great", annex=True, store=True, library=True)


def level5():
    return _build("B5", 14, "crown", annex=True, store=True, library=True)


LEVELS = {1: level1, 2: level2, 3: level3, 4: level4, 5: level5}
