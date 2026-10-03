"""Variant A - "Forge Hall": a stone smithy with a jettied timber-and-plaster upper storey, a forge
lean-to screened with iron bars, and a massive blackstone chimney. It grows into a forge hall:

    1  the smithy: house, forge lean-to, chimney
    2  + a lean-to rack store on the west side, lamp posts along the path
    3  the store is rebuilt as the rune study (west wing, round rune window, enchanting corner)
    4  + the hall behind the house, first stage: low stone walls, plain gable roof
    5  the hall rebuilt tall: steep roof, dormers, ridge turret, rose windows; the chimney climbs
       past it; an amethyst geode breaks through the forge yard

Coordinates: x east, y up, z south; the entrance is on the south side. y=1 is the ground surface
(the grass layer), the floor is raised one block (y=2) on a cobbled-deepslate plinth, people walk at y=3.
The hut block stands at (3, 3, 7) facing east at every level.
"""
from blocks import (anvil, barrel, bars, campfire, candle, cauldron, chain, chiseled_shelf, crystal, door, furnace,
                    grindstone, hut, lantern, lectern, log, pane, rack, slab, stairs, wall)
from kit import fill_dark_pockets, fill_under_roof, gable_h, hang, roof
from voxel import Structure

G, F = 1, 2
COBBLE = "minecraft:cobbled_deepslate"
STONE = "minecraft:stone_bricks"
DBRICK = "minecraft:deepslate_bricks"
TILES = "minecraft:deepslate_tiles"
POLISHED = "minecraft:polished_deepslate"
BLACK = "minecraft:polished_blackstone_bricks"
BLACK_SMOOTH = "minecraft:polished_blackstone"
CHISEL = "minecraft:chiseled_deepslate"
CHISEL_S = "minecraft:chiseled_stone_bricks"
CHISEL_B = "minecraft:chiseled_polished_blackstone"
AMETHYST = "minecraft:amethyst_block"
CRYING = "minecraft:crying_obsidian"
PLASTER = "minecraft:calcite"
OAK = "dark_oak"
ROOF = "deepslate_tile"
ROOF_FULL = TILES
LEAN = "spruce"


def beam(axis):
    return log(OAK, axis, stripped=True)


def post():
    return log(OAK, "y")


def skirt_around(s, rects, y=F, skip=()):
    """Sloped plinth: cobbled-deepslate stairs one block outside the union of rectangles, rising to it."""
    inside = set()
    for (x0, z0, x1, z1) in rects:
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                inside.add((x, z))
    for (x, z) in list(inside):
        for f, (dx, dz) in (("south", (0, -1)), ("north", (0, 1)), ("east", (-1, 0)), ("west", (1, 0))):
            c = (x + dx, z + dz)
            if c in inside or c in skip or s.get(c[0], y, c[1]) is not None:
                continue
            s.set(c[0], G, c[1], COBBLE)
            s.set(c[0], y, c[1], stairs("cobbled_deepslate", f))
    for (x, z) in list(inside):
        for dx in (-1, 1):
            for dz in (-1, 1):
                c = (x + dx, z + dz)
                if c in inside or c in skip or s.get(c[0], y, c[1]) is not None:
                    continue
                if (x + dx, z) in inside or (x, z + dz) in inside:
                    continue
                s.set(c[0], G, c[1], COBBLE)
                s.set(c[0], y, c[1], stairs("cobbled_deepslate", "south" if dz < 0 else "north"))


def window(s, cells, sill=None, sill_dir=None, glass=None):
    """Glass panes in a wall, with an optional stone sill (an upside-down stair) under it."""
    for (x, y, z) in cells:
        s.set(x, y, z, pane(glass))
    if sill:
        s.set(*sill, stairs("stone_brick", sill_dir, "top"))


def lamp_post(s, x, z, top=4, soul=False):
    """A stone lamp post: blackstone shaft with a carved band, the lantern standing on top."""
    s.set(x, 0, z, COBBLE); s.set(x, G, z, COBBLE)
    for y in range(F, top + 1):
        s.set(x, y, z, CHISEL_B if y == top - 1 else BLACK)
    s.set(x, top + 1, z, lantern(soul=soul))


def finial(s, x, y, z):
    """An amethyst point on a carved blackstone block (a crystal needs a full block under it)."""
    s.set(x, y, z, CHISEL_B)
    s.set(x, y + 1, z, crystal("cluster", "up"))


# ---------------------------------------------------------------- level 1: the smithy
# house: walls x 1..7, z 2..8, two storeys (stone below, timber and plaster above), gable to the south
# forge: lean-to x 8..10, z 3..8 on posts, screened with iron bars; chimney x 8..10, z 1..2
HX0, HX1, HZ0, HZ1 = 1, 7, 2, 8
FLOOR2 = 6          # the timber floor line between the storeys
PLATE = 10          # top plate, the roof starts above it
DOOR_X = 4
LX1 = 10
HUT_POS = (DOOR_X - 1, 3, HZ1 - 1)


def _house(s):
    JZ = HZ1 + 1                                   # the jettied front of the upper storey
    s.box(HX0, 0, HZ0, HX1, G, HZ1, COBBLE)
    s.box(HX0 + 1, F, HZ0 + 1, HX1 - 1, F, HZ1 - 1, POLISHED)
    for z in range(HZ0 + 1, HZ1):
        s.set(DOOR_X, F, z, TILES)
    # ground storey: cobbled base course, stone bricks, deepslate quoins
    s.walls(HX0, HZ0, HX1, HZ1, F, F, COBBLE)
    s.walls(HX0, HZ0, HX1, HZ1, 3, FLOOR2 - 1, STONE)
    for x, z in ((HX0, HZ0), (HX1, HZ0), (HX0, HZ1), (HX1, HZ1)):
        s.column(x, z, F, FLOOR2 - 1, DBRICK)
    # floor line, then the timber-framed upper storey, jettied one block over the front
    for x in range(HX0, HX1 + 1):
        for z in (HZ0, JZ):
            s.set(x, FLOOR2, z, beam("x")); s.set(x, PLATE, z, beam("x"))
    for z in range(HZ0, JZ + 1):
        for x in (HX0, HX1):
            s.set(x, FLOOR2, z, beam("z")); s.set(x, PLATE, z, beam("z"))
    s.walls(HX0, HZ0, HX1, JZ, FLOOR2 + 1, PLATE - 1, PLASTER)
    for x in (HX0, 3, 5, HX1):
        for z in (HZ0, JZ):
            s.column(x, z, FLOOR2 + 1, PLATE - 1, post())
    for z in (HZ0, 5, JZ):
        for x in (HX0, HX1):
            s.column(x, z, FLOOR2 + 1, PLATE - 1, post())
    for x in range(HX0 + 1, HX1):
        s.set(x, FLOOR2, HZ1, beam("x"))
    for x in (HX0, DOOR_X, HX1):                       # corbels carry the jetty
        s.set(x, FLOOR2 - 1, JZ, stairs("dark_oak", "north", "top"))
    # roof: steep gable, eaves overhang east and west, gable flush with the jetty
    H = gable_h(HX0 - 1, HX1 + 1, HZ0, JZ, PLATE, "z")
    roof(s, H, ROOF, peak=ROOF_FULL)
    for z in (HZ0, JZ):
        for x in range(HX0, HX1 + 1):
            for y in range(PLATE + 1, H[(x, z)]):
                s.set(x, y, z, PLASTER)
    # barge: tile stairs stepping up the front gable one block proud, amethyst finial on top
    for x in range(HX0 - 1, HX1 + 2):
        h = H[(x, JZ)]
        if x == DOOR_X:
            s.set(x, h, JZ + 1, ROOF_FULL)
            finial(s, x, h + 1, JZ + 1)
        else:
            s.set(x, h, JZ + 1, stairs(ROOF, "east" if x < DOOR_X else "west"))
    # rune window in the gable: a cross of purple glass in a timber frame
    for dx, dy in ((0, 1), (-1, 1), (1, 1), (0, 0), (0, 2)):
        s.set(DOOR_X + dx, PLATE + 1 + dy, JZ, "minecraft:purple_stained_glass")
    for x in (DOOR_X - 2, DOOR_X + 2):
        s.set(x, PLATE + 1, JZ, beam("y"))
    s.set(DOOR_X - 1, PLATE + 1, JZ, beam("x")); s.set(DOOR_X + 1, PLATE + 1, JZ, beam("x"))
    # the door between carved rune jambs, lanterns hung from the jetty, a solid stone step
    s.set(DOOR_X, 3, HZ1, door(OAK, "north", "lower")); s.set(DOOR_X, 4, HZ1, door(OAK, "north", "upper"))
    s.set(DOOR_X, 5, HZ1, CHISEL_S)
    for x in (DOOR_X - 1, DOOR_X + 1):
        s.set(x, 3, HZ1, CHISEL); s.set(x, 4, HZ1, CHISEL)
        s.set(x, 5, JZ, lantern(hanging=True))
    s.set(DOOR_X, G, JZ, COBBLE); s.set(DOOR_X, F, JZ, BLACK)
    # windows
    for x in (2, 6):
        window(s, [(x, 4, HZ1)], (x, 3, JZ), "north")
        s.set(x, 5, HZ1, CHISEL_S)
        window(s, [(x, 4, HZ0)], (x, 3, HZ0 - 1), "south")
        window(s, [(x, 7, JZ), (x, 8, JZ)])
        window(s, [(x, 7, HZ0), (x, 8, HZ0)])
    window(s, [(DOOR_X, 7, JZ), (DOOR_X, 8, JZ)], glass="purple")
    for z in (3, 7):
        window(s, [(HX0, 7, z), (HX0, 8, z)])
        window(s, [(HX1, 7, z), (HX1, 8, z)])
    for z in (4, 6):
        window(s, [(HX0, 4, z)], (HX0 - 1, 3, z), "east")
    # inside: the hut block by the door, two racks, the rune lectern between chiseled shelves
    s.set_anchor(*HUT_POS, hut("east"))
    s.set(HX0 + 1, 3, 4, rack("east")); s.set(HX0 + 1, 3, 5, rack("east"))
    s.set(DOOR_X, 3, HZ0 + 1, lectern("south"))
    s.set(DOOR_X - 1, 3, HZ0 + 1, chiseled_shelf("south")); s.set(DOOR_X + 1, 3, HZ0 + 1, chiseled_shelf("south"))
    s.set(DOOR_X - 1, 4, HZ0 + 1, candle("purple", 3)); s.set(DOOR_X + 1, 4, HZ0 + 1, candle("purple", 2))
    for z in (4, 6):
        for x in range(HX0 + 1, HX1):
            s.set(x, FLOOR2, z, beam("x"))
    hang(s, DOOR_X, FLOOR2, 4, 0); hang(s, DOOR_X, FLOOR2, 6, 0)
    hang(s, 2, FLOOR2, 4, 0); hang(s, 2, FLOOR2, 6, 0)
    for x in range(HX0 + 1, HX1):
        s.set(x, PLATE, 5, beam("x"))
    hang(s, 6, PLATE, 5, 2)
    # arch into the forge
    for y in (3, 4):
        s.set(HX1, y, 5, None)
    s.set(HX1, 5, 5, CHISEL_S)


def _forge(s):
    """The forge on the east side: a spruce lean-to on dark oak posts, iron-bar screens between them,
    the chimney at its north end."""
    for x in range(HX1 + 1, LX1 + 1):
        for z in range(HZ0 + 1, HZ1 + 1):
            s.set(x, 0, z, COBBLE)
            s.set(x, G, z, COBBLE)
            s.set(x, F, z, "minecraft:polished_blackstone")
    for z in range(HZ0 + 1, HZ1 + 1):
        s.set(LX1, F, z, COBBLE)
    for x in range(HX1 + 1, LX1 + 1):
        s.set(x, F, HZ1 + 1, stairs("cobbled_deepslate", "north")); s.set(x, G, HZ1 + 1, COBBLE)
    for z in range(HZ0 + 1, HZ1 + 2):
        s.set(LX1 + 1, F, z, stairs("cobbled_deepslate", "west")); s.set(LX1 + 1, G, z, COBBLE)
    # posts, the eave beam, iron-bar screens on the open sides
    for z in (HZ0 + 1, 6, HZ1):
        s.column(LX1, z, 3, 4, post())
    for z in range(HZ0 + 1, HZ1 + 1):
        s.set(LX1, 5, z, beam("z"))
    for z in (4, 5, 7):
        s.set(LX1, 3, z, bars()); s.set(LX1, 4, z, bars())
    for y in range(3, 7):
        s.set(HX1 + 1, y, HZ1 + 1, bars())
    for y in range(3, 6):
        s.set(HX1 + 2, y, HZ1 + 1, bars())
    # lean-to roof: spruce, falling from under the house eave to the posts
    for z in range(HZ0 + 1, HZ1 + 2):
        s.set(HX1 + 1, 7, z, stairs(LEAN, "west"))
        s.set(HX1 + 2, 6, z, stairs(LEAN, "west"))
        s.set(LX1, 6, z, slab(LEAN, "bottom"))
    _chimney(s, 14)
    # the hearth, the anvil, grindstone, quench tub; lanterns on the furnace and the barrel
    CX0 = HX1 + 1
    s.set(CX0 + 1, 3, HZ0 + 1, furnace("south"))
    s.set(CX0, 3, HZ0 + 1, stairs("polished_blackstone_brick", "east"))
    s.set(CX0 + 1, 4, HZ0 + 1, lantern())
    s.set(CX0 + 1, 3, 5, anvil("north"))
    s.set(CX0 + 1, F, 5, CHISEL)
    s.tag(CX0 + 1, 3, 5, "work")
    s.set(CX0 + 1, 3, 7, grindstone("east"))
    s.set(CX0, 3, HZ1, cauldron(water=True))
    s.set(CX0 + 1, 3, HZ1, barrel())
    s.set(CX0 + 1, 4, HZ1, lantern())


def _chimney(s, top):
    """A 3 x 2 forge stack (x 8..10, z 1..2) that steps in to 2 x 2 above the lean-to, a carved band,
    campfires smoking on top; `top` is the crown's height."""
    CX0, CX1, CZ0, CZ1 = HX1 + 1, LX1, HZ0 - 1, HZ0
    for y in range(G, 8):
        for x in range(CX0, CX1 + 1):
            for z in (CZ0, CZ1):
                s.set(x, y, z, BLACK)
    for z in (CZ0, CZ1):
        s.set(CX0, 8, z, stairs("polished_blackstone_brick", "east"))
    for y in range(8, top):
        for x in (CX0 + 1, CX1):
            for z in (CZ0, CZ1):
                s.set(x, y, z, BLACK)
    band = 12 if top <= 14 else top - 3
    for x in (CX0 + 1, CX1):
        for z in (CZ0, CZ1):
            s.set(x, band, z, CHISEL_B)
    if top > 14:
        s.set(CX1, band, CZ1, CRYING)
    s.set(CX0 + 1, top, CZ0, wall("polished_blackstone_brick")); s.set(CX1, top, CZ1, wall("polished_blackstone_brick"))
    s.set(CX1, top, CZ0, campfire(facing="south")); s.set(CX0 + 1, top, CZ1, campfire(facing="south"))


def _base(s):
    _house(s)
    _forge(s)
    skirt_around(s, [(HX0, HZ0, HX1, HZ1)],
                 skip={(x, z) for x in range(HX1 + 1, LX1 + 2) for z in range(HZ0 - 2, HZ1 + 2)})
    s.path_cells = [(DOOR_X, z) for z in range(HZ1 + 2, HZ1 + 7)]


# ---------------------------------------------------------------- level 2: lean-to store, lamp posts
def _store(s):
    """A low lean-to against the house's west wall: two more racks, a lantern on a barrel."""
    X0, Z0, Z1 = -2, 3, 8
    s.box(X0, 0, Z0, 0, G, Z1, COBBLE)
    s.box(X0 + 1, F, Z0 + 1, 0, F, Z1 - 1, POLISHED)
    for z in range(Z0, Z1 + 1):
        s.set(X0, F, z, COBBLE)
        s.set(X0, 3, z, STONE); s.set(X0, 4, z, STONE)
    for x in range(X0, 1):
        for z in (Z0, Z1):
            s.set(x, F, z, COBBLE)
    for z in (Z0, Z1):                                   # end walls up under the shed roof
        s.column(X0, z, F, 4, DBRICK)
        s.column(-1, z, 3, 5, STONE)
        s.column(0, z, 3, 6, STONE)
    for z in range(Z0 - 1, Z1 + 2):
        s.set(0, 7, z, stairs(ROOF, "east")); s.set(-1, 6, z, stairs(ROOF, "east"))
        s.set(-2, 5, z, stairs(ROOF, "east")); s.set(-3, 4, z, stairs(ROOF, "east"))
    for z in (5, 6):
        s.set(X0, 4, z, pane())
    s.set(-1, 4, Z1, pane())
    s.set(-1, 3, Z0 + 1, rack("south")); s.set(0, 3, Z0 + 1, rack("south"))
    s.set(-1, 3, Z1 - 1, barrel()); s.set(-1, 4, Z1 - 1, lantern())
    for y in (3, 4):
        s.set(HX0, y, 6, None)
    s.set(HX0, F, 6, POLISHED)
    s.set(0, 3, 6, None)                                 # the house's old window sill
    skirt_around(s, [(X0, Z0, 0, Z1)])


def _lamp_posts(s):
    for x in (DOOR_X - 2, DOOR_X + 2):
        lamp_post(s, x, HZ1 + 3)


# ---------------------------------------------------------------- level 3: the rune study (west wing)
# walls x -5..0, z 3..9, one tall storey, gable to the south with a round rune window
WX0, WX1, WZ0, WZ1 = -5, 0, 3, 9
WPLATE = 7


def _rune_study(s):
    s.box(WX0, 0, WZ0, WX1, G, WZ1, COBBLE)
    s.box(WX0 + 1, F, WZ0 + 1, WX1 - 1, F, WZ1 - 1, POLISHED)
    s.walls(WX0, WZ0, WX1, WZ1, F, F, COBBLE)
    s.walls(WX0, WZ0, WX1, WZ1, 3, WPLATE - 1, STONE)
    for x, z in ((WX0, WZ0), (WX1, WZ0), (WX0, WZ1), (WX1, WZ1)):
        s.column(x, z, F, WPLATE - 1, DBRICK)
    for x in range(WX0, WX1 + 1):
        s.set(x, WPLATE, WZ0, beam("x")); s.set(x, WPLATE, WZ1, beam("x"))
    for z in range(WZ0, WZ1 + 1):
        s.set(WX0, WPLATE, z, beam("z")); s.set(WX1, WPLATE, z, beam("z"))
    for z in (5, 7):                                     # buttresses on the long west wall
        s.column(WX0 - 1, z, G, 4, DBRICK)
        s.set(WX0 - 1, 5, z, stairs("deepslate_brick", "east"))
    H = gable_h(WX0 - 1, WX1, WZ0 - 1, WZ1 + 1, WPLATE, "z")
    roof(s, H, ROOF, peak=ROOF_FULL)
    for z in (WZ0, WZ1):
        for x in range(WX0 + 1, WX1):
            for y in range(WPLATE + 1, H[(x, z)]):
                s.set(x, y, z, PLASTER)
    s.set(-3, H[(-3, WZ1 + 1)] + 1, WZ1 + 1, crystal("cluster", "up"))
    # the great rune window of the front: purple glass round a glowing heart, a carved arch over it
    cx, cy = -3, 5
    for (dx, dy) in ((0, -1), (-1, 0), (1, 0), (0, 1), (-1, -1), (1, -1), (-1, 1), (1, 1)):
        s.set(cx + dx, cy + dy, WZ1, pane("purple"))
    s.set(cx, cy, WZ1, CRYING)
    s.set(cx, cy + 2, WZ1, CHISEL_S)
    s.set(cx - 1, cy + 2, WZ1, stairs("stone_brick", "east", "top")); s.set(cx + 1, cy + 2, WZ1, stairs("stone_brick", "west", "top"))
    s.set(cx, cy - 2, WZ1 + 1, stairs("stone_brick", "north", "top"))
    for z in (4, 8):
        window(s, [(WX0, 4, z), (WX0, 5, z)], (WX0 - 1, 3, z), "east")
    window(s, [(WX0, 4, 6), (WX0, 5, 6)])
    window(s, [(-3, 4, WZ0), (-3, 5, WZ0)], (-3, 3, WZ0 - 1), "south")
    # inside: racks on the west wall, a free aisle, the enchanting table on its rune stone
    for z in (4, 5, 7, 8):
        s.set(WX0 + 1, 3, z, rack("east"))
    s.set(-2, 3, 6, "minecraft:enchanting_table")
    s.set(-2, F, 6, CHISEL)
    s.set(-2, 3, 4, "minecraft:bookshelf"); s.set(-1, 3, 4, "minecraft:bookshelf")
    s.set(-1, 4, 4, candle("purple", 4)); s.set(-2, 4, 4, candle("purple", 3))
    s.set(-1, 3, 8, lectern("north"))
    for z in (5, 7):
        for x in range(WX0 + 1, WX1):
            s.set(x, 6, z, beam("x"))
        hang(s, -3, 6, z, 0)
    # doorway from the house
    for x in (WX1, HX0):
        s.set(x, 3, 6, None); s.set(x, 4, 6, None)
        s.set(x, F, 6, POLISHED)
    s.set(WX1, 5, 6, CHISEL_S)
    for x in range(WX0 - 1, WX1 + 1):
        if s.get(x, F, WZ1 + 1) is None:
            s.set(x, G, WZ1 + 1, COBBLE); s.set(x, F, WZ1 + 1, stairs("cobbled_deepslate", "north"))
        if s.get(x, F, WZ0 - 1) is None:
            s.set(x, G, WZ0 - 1, COBBLE); s.set(x, F, WZ0 - 1, stairs("cobbled_deepslate", "south"))
    for z in range(WZ0, WZ1 + 1):
        if s.get(WX0 - 1, F, z) is None:
            s.set(WX0 - 1, G, z, COBBLE); s.set(WX0 - 1, F, z, stairs("cobbled_deepslate", "east"))


# ---------------------------------------------------------------- levels 4 and 5: the hall behind
# walls x -4..11, z -8..0. Level 4 builds it low with a plain gable roof; level 5 rebuilds it tall with a
# steep roof, dormers, a ridge turret and rose windows, and the forge chimney climbs past it.
GX0, GX1, GZ0, GZ1 = -4, 11, -8, 0


def _great_hall(s, tall):
    plate = 11 if tall else 8
    s.box(GX0, 0, GZ0, GX1, G, GZ1, COBBLE)
    s.box(GX0 + 1, F, GZ0 + 1, GX1 - 1, F, GZ1 - 1, POLISHED)
    for x in range(GX0 + 1, GX1):
        s.set(x, F, -4, TILES)
    s.walls(GX0, GZ0, GX1, GZ1, F, F, COBBLE)
    s.walls(GX0, GZ0, GX1, GZ1, 3, plate - 1, STONE)
    s.walls(GX0, GZ0, GX1, GZ1, plate, plate, DBRICK)
    for x, z in ((GX0, GZ0), (GX1, GZ0), (GX0, GZ1), (GX1, GZ1)):
        s.column(x, z, F, plate, DBRICK)

    def buttress(x, z, dx, dz, top):
        f = {(1, 0): "west", (-1, 0): "east", (0, 1): "north", (0, -1): "south"}[(dx, dz)]
        s.column(x + dx, z + dz, G, top, DBRICK)
        s.set(x + dx, top + 1, z + dz, stairs("deepslate_brick", f))
        s.set(x + dx, 5, z + dz, CHISEL)

    btop = plate - 3
    for x in (-2, 2, 6, 9):
        buttress(x, GZ0, 0, -1, btop)
    for z in (-7, -1):
        buttress(GX1, z, 1, 0, btop)
        buttress(GX0, z, -1, 0, btop)
    for x in (0, 4, 8):                                   # lancet windows on the north wall
        for y in range(4, plate - 2):
            s.set(x, y, GZ0, pane())
        s.set(x, plate - 2, GZ0, CHISEL_S)
    # roof: level 4 a plain 45-degree gable; level 5 steep (3 up for 2 across) with two dormers
    H = {}
    for x in range(GX0 - 1, GX1 + 2):
        for z in range(GZ0 - 1, GZ1 + 2):
            d = min(z - (GZ0 - 1), (GZ1 + 1) - z)
            H[(x, z)] = plate + ((d * 3) // 2 if tall else d)
    dormers = (0, 6) if tall else ()
    for dx in dormers:
        Hd = gable_h(dx - 2, dx + 2, -3, GZ1 + 1, plate + 2, "z")
        for kk, v in Hd.items():
            if kk in H:
                H[kk] = max(H[kk], v)
    roof(s, H, ROOF, peak=ROOF_FULL)
    if tall:
        fill_under_roof(s, H, ROOF_FULL)
    for dx in dormers:                                    # dormer fronts: stone, a purple window
        for x in range(dx - 1, dx + 2):
            for y in range(plate + 1, H[(x, GZ1)]):
                s.set(x, y, GZ1, STONE)
        s.set(dx, plate + 2, GZ1, pane("purple")); s.set(dx, plate + 3, GZ1, pane("purple"))
        s.set(dx, plate + 1, GZ1, CHISEL_S)
        s.set(dx, H[(dx, GZ1 + 1)] + 1, GZ1 + 1, crystal("medium", "up"))
    for x in (GX0, GX1):
        for z in range(GZ0 + 1, GZ1):
            for y in range(plate + 1, H[(x, z)]):
                if s.get(x, y, z) is None:
                    s.set(x, y, z, STONE)
    for x in (GX0 - 1, GX1 + 1):
        finial(s, x, H[(x, -4)] + 1, -4)
    # gable windows: level 4 a round window, level 5 a rose window round a glowing heart
    rz = -4
    ry = plate + (2 if not tall else 0)
    for gx in (GX1, GX0):
        if tall:
            ring = [(-1, -2), (0, -2), (1, -2), (-2, -1), (2, -1), (-2, 0), (2, 0), (-2, 1), (2, 1), (-1, 2), (0, 2), (1, 2)]
            for (dz, dy) in ring + [(-1, -1), (1, -1), (-1, 1), (1, 1)]:
                s.set(gx, ry + dy, rz + dz, pane("purple"))
            for (dz, dy) in ((0, -1), (-1, 0), (1, 0), (0, 1)):
                s.set(gx, ry + dy, rz + dz, CHISEL_S)
            s.set(gx, ry, rz, CRYING)
        else:
            for (dz, dy) in ((0, -1), (-1, 0), (1, 0), (0, 1)):
                s.set(gx, ry + dy, rz + dz, pane("purple"))
            s.set(gx, ry, rz, CRYING)
    # a barred forge arch in the east gable (one door per building: this one is a grille)
    for z in (-5, -4, -3):
        for y in (3, 4, 5):
            s.set(GX1, y, z, bars())
    s.set(GX1, 6, -5, stairs("stone_brick", "south", "top")); s.set(GX1, 6, -3, stairs("stone_brick", "north", "top"))
    s.set(GX1, 6, -4, CHISEL_S)
    # inside: a second hearth, racks along the north wall with lanterns between, lantern line
    for x in (0, 2, 4, 6):
        s.set(x, 3, GZ0 + 1, rack("south"))
    for x in (1, 3, 5, 7):
        s.set(x, 3, GZ0 + 1, lantern())
    s.set(9, 3, GZ0 + 1, furnace("south")); s.set(10, 3, GZ0 + 1, furnace("south"))
    for x in range(GX0 + 1, GX1):
        s.set(x, plate, -4, beam("x"))
    for x in (-2, 1, 4, 7, 10):
        hang(s, x, plate, -4, plate - 8)
    for z in (-7, -1):
        for x in (-2, 9):
            if s.get(x, 3, z) is None:
                s.set(x, 3, z, lantern())
    if tall:
        # the ridge turret: an open lantern stage on the ridge, a small spire with an amethyst tip
        top = H[(3, -4)]
        for (x, z) in ((2, -5), (4, -5), (2, -3), (4, -3)):
            for y in range(H[(x, z)] + 1, top + 3):
                s.set(x, y, z, wall("polished_blackstone_brick"))
        s.set(3, top + 1, -4, lantern(soul=True))
        for x in (2, 3, 4):
            for z in (-5, -4, -3):
                if (x, z) == (3, -4):
                    s.set(x, top + 3, z, ROOF_FULL)
                else:
                    f = "east" if x == 2 else "west" if x == 4 else ("south" if z == -5 else "north")
                    s.set(x, top + 3, z, stairs(ROOF, f))
        s.set(3, top + 4, -4, AMETHYST); s.set(3, top + 5, -4, crystal("cluster", "up"))
    # the forge chimney: level 4 keeps it, level 5 raises it past the hall's roof
    _chimney(s, 22 if tall else 14)
    # passage from the house's back wall into the hall
    for z in (1, 2):
        s.set(DOOR_X, 3, z, None); s.set(DOOR_X, 4, z, None)
        s.set(DOOR_X, F, z, POLISHED); s.set(DOOR_X, G, z, COBBLE); s.set(DOOR_X, 0, z, COBBLE)
        for x in (DOOR_X - 1, DOOR_X + 1):
            s.column(x, z, F, 5, STONE)
            s.set(x, G, z, COBBLE); s.set(x, 0, z, COBBLE)
        s.set(DOOR_X, 5, z, STONE)
    s.set(DOOR_X, 3, GZ1, None); s.set(DOOR_X, 4, GZ1, None)
    s.set(DOOR_X, 3, HZ0 + 1, None)                       # the lectern moves aside
    s.set(DOOR_X + 1, 3, HZ0 + 1, lectern("south"))
    s.set(DOOR_X + 1, 4, HZ0 + 1, None)
    # lamp posts by the west gable and in the alley behind the house
    for z in (-5, -3):
        lamp_post(s, GX0 - 1, z)
    s.set(0, 0, 1, COBBLE); s.set(0, G, 1, COBBLE); s.set(0, F, 1, COBBLE); s.set(0, 3, 1, lantern())
    for z in range(GZ0 - 1, GZ1 + 2):
        if s.get(GX0 - 1, F, z) is None:
            s.set(GX0 - 1, G, z, COBBLE); s.set(GX0 - 1, F, z, stairs("cobbled_deepslate", "east"))
        if s.get(GX1 + 1, F, z) is None:
            s.set(GX1 + 1, G, z, COBBLE); s.set(GX1 + 1, F, z, stairs("cobbled_deepslate", "west"))
    for x in range(GX0 - 1, GX1 + 2):
        if s.get(x, F, GZ0 - 1) is None:
            s.set(x, G, GZ0 - 1, COBBLE); s.set(x, F, GZ0 - 1, stairs("cobbled_deepslate", "south"))


def _geode(s):
    """A half-buried amethyst geode by the forge yard, cracked open towards the path: smooth basalt
    outside, calcite inside, amethyst at the heart."""
    BAS, CAL = "minecraft:smooth_basalt", "minecraft:calcite"
    gx, gz = 12, 8
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            if abs(dx) == 2 and abs(dz) == 2:
                continue
            s.set(gx + dx, 0, gz + dz, COBBLE)
            s.set(gx + dx, G, gz + dz, BAS)
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            s.set(gx + dx, G, gz + dz, CAL)
    s.set(gx, G, gz, AMETHYST)
    for (dx, dz) in ((-2, -1), (-2, 0), (-1, -2), (0, -2), (1, -2), (2, -1), (2, 0), (-2, 1), (2, 1)):
        s.set(gx + dx, F, gz + dz, BAS)
    for (dx, dz) in ((-1, -2), (0, -2), (1, -2), (-2, -1), (2, -1)):
        s.set(gx + dx, F + 1, gz + dz, BAS)
    for (dx, dz) in ((-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0)):
        s.set(gx + dx, F, gz + dz, CAL)
    s.set(gx, F + 1, gz - 1, CAL); s.set(gx - 1, F + 1, gz - 1, CAL); s.set(gx + 1, F + 1, gz - 1, CAL)
    s.set(gx, F, gz, AMETHYST); s.set(gx, F + 1, gz, AMETHYST)
    s.set(gx, F + 2, gz, crystal("cluster", "up"))
    s.set(gx, F + 2, gz - 1, crystal("large", "up"))
    s.set(gx - 1, F + 1, gz, crystal("medium", "up")); s.set(gx + 1, F + 1, gz, crystal("cluster", "up"))
    s.set(gx, F, gz + 1, crystal("large", "south"))


# ---------------------------------------------------------------- the five levels
def level1():
    s = Structure("A1")
    _base(s)
    return s.finalize()


def level2():
    s = Structure("A2")
    _base(s)
    _store(s)
    _lamp_posts(s)
    return s.finalize()


def level3():
    s = Structure("A3")
    _base(s)
    _rune_study(s)
    _lamp_posts(s)
    return s.finalize()


def level4():
    s = Structure("A4")
    _base(s)
    _rune_study(s)
    _lamp_posts(s)
    _great_hall(s, tall=False)
    return s.finalize()


def level5():
    s = Structure("A5")
    _base(s)
    _rune_study(s)
    _lamp_posts(s)
    _great_hall(s, tall=True)
    _geode(s)
    fill_dark_pockets(s, ROOF_FULL)
    return s.finalize()


LEVELS = {1: level1, 2: level2, 3: level3, 4: level4, 5: level5}
