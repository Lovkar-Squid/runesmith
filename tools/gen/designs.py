"""The Runesmith building as a voxel structure. Level 1 only, and temporary: a small, tidy stone and
spruce workshop that stands in until the real five-level design replaces it.

Front of the building is SOUTH (+z): the door is in the middle of the south wall. The hut block
stands just inside it on the same axis, one cell behind the entry cell, with its front toward the
door. y=0 is the foundation, y=1 the floor (ground level, where the grass was), people walk at y=2.
Walls are 9 x 9 (x 0..8, z 0..8); the roof overhangs the long sides by one block (z -1 and z 9, the
latter also shelters the porch in front of the door).

Inside: a double rack against the east wall, an anvil on a carved brick at the north end as the one
work block (tagged `work`), a bench on the west wall, and a lantern on a chain over each of hut
block, anvil and racks so the room is lit where the worker stands. Only cheap materials, because a
level-1 building has to be affordable to build in survival.
"""
from voxel import Structure

HUT_ID = "runesmith:blockhutrunesmith"
RACK_ID = "minecolonies:blockminecoloniesrack"
ANVIL_ID = "minecraft:anvil"
DOOR_ID = "minecraft:spruce_door"

# ---------------------------------------------------------------- layout (design coordinates)
X0, X1 = 0, 8                     # west and east wall lines
Z0, Z1 = 0, 8                     # north wall line, south wall line (the one with the door)
Y_FOUND, Y_FLOOR = 0, 1
Y_BASE = 2                        # first wall row, and the level people walk at
Y_BEAM = 6                        # top wall row, and the ceiling over the room
CENTER_X = 4                      # the middle of the 9-wide building: door, hut block, anvil and runner line up here
DOOR_X = CENTER_X
PORCH_X0, PORCH_X1 = CENTER_X - 1, CENTER_X + 1   # the paved strip in front of the door, one block deep (z = Z1 + 1)
RIDGE_Z, RIDGE_Y = 4, 10

DOOR = (DOOR_X, Y_BASE, Z1)       # lower half of the door
HUT_POS = (CENTER_X, Y_BASE, 6)   # just inside the entrance: the entry cell (4, 2, 7) lies between it and the door
ANVIL_POS = (CENTER_X, Y_BASE, 2) # the work block
WORK_STAND = (CENTER_X, Y_BASE, 3)  # the cell south of the anvil, where the worker stands
RACK_MAIN = (7, Y_BASE, 3)        # double rack against the east wall: this half and the one south of it
RACK_PARTNER = (7, Y_BASE, 4)
LANTERNS_INSIDE = [(p[0], p[2]) for p in (ANVIL_POS, HUT_POS, RACK_MAIN)]   # chain lanterns over the anvil, the hut block and the racks
LANTERN_Y = 4                     # the lantern itself; its chain is at LANTERN_Y + 1, the ceiling at Y_BEAM

# ---------------------------------------------------------------- materials
FLOOR = "minecraft:stone_bricks"
RUNNER = "minecraft:polished_andesite"
ACCENT = "minecraft:chiseled_stone_bricks"
BASE = "minecraft:stone_bricks"
PLANKS = "minecraft:spruce_planks"
POST = "minecraft:spruce_log"
BEAM = "minecraft:stripped_spruce_log"
ROOF_STAIRS = "minecraft:stone_brick_stairs"
RIDGE = "minecraft:stone_bricks"
BENCH = "minecraft:spruce_stairs"
SOLID_FILL = "structurize:blocksolidsubstitution"    # "a solid block of this terrain": fills holes under the floor
KEEP = "structurize:blocksubstitution"               # "leave whatever is there": no work, no materials
# blocks a glass pane connects to (full cubes), used to compute the panes' states
FULL_CUBES = {FLOOR, BASE, PLANKS, POST, BEAM, RUNNER, ACCENT, RIDGE}


# ---------------------------------------------------------------- block states
def stairs(block_id, facing, half="bottom", shape="straight"):
    return f"{block_id}[facing={facing},half={half},shape={shape},waterlogged=false]"


def door(block_id, facing, half, hinge="left"):
    return f"{block_id}[facing={facing},half={half},hinge={hinge},open=false,powered=false]"


def lantern(hanging):
    return f"minecraft:lantern[hanging={'true' if hanging else 'false'},waterlogged=false]"


def chain():
    return "minecraft:chain[axis=y,waterlogged=false]"


def log(block_id, axis):
    return f"{block_id}[axis={axis}]"


def rack(facing, variant):
    return f"{RACK_ID}[facing={facing},variant={variant}]"


def anvil(facing):
    return f"{ANVIL_ID}[facing={facing}]"


def hut(facing):
    return f"{HUT_ID}[facing={facing}]"


def pane(north=False, east=False, south=False, west=False):
    flag = lambda v: "true" if v else "false"
    return (f"minecraft:glass_pane[east={flag(east)},north={flag(north)},south={flag(south)},"
            f"waterlogged=false,west={flag(west)}]")


# ---------------------------------------------------------------- the building
def roof_y(z):
    """Height of the roof row at depth z: a 45-degree gable running east-west, ridge at RIDGE_Z."""
    return RIDGE_Y if z == RIDGE_Z else (Y_BEAM + 1 + z if z < RIDGE_Z else Y_BEAM + 1 + (Z1 - z))


def _ground(s):
    """Foundation and floor. Everything inside the walls and the porch is built; the rest of the
    blueprint box is left to the terrain (KEEP), and anything above ground there is cleared."""
    for x in range(X0, X1 + 1):
        for z in range(Z0 - 1, Z1 + 2):
            built = Z0 <= z <= Z1 or (z == Z1 + 1 and PORCH_X0 <= x <= PORCH_X1)
            s.set(x, Y_FOUND, z, SOLID_FILL if built else KEEP)
            s.set(x, Y_FLOOR, z, FLOOR if built else KEEP)
    for z in range(ANVIL_POS[2] + 1, Z1 + 2):          # a runner of polished andesite from the porch to the work spot
        s.set(DOOR_X, Y_FLOOR, z, RUNNER)
    s.set(ANVIL_POS[0], Y_FLOOR, ANVIL_POS[2], ACCENT)    # the anvil stands on a carved brick


def _walls(s):
    corners = {(X0, Z0), (X1, Z0), (X0, Z1), (X1, Z1)}
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            if x not in (X0, X1) and z not in (Z0, Z1):
                continue
            if (x, z) in corners:
                s.column(x, z, Y_BASE, Y_BEAM, log(POST, "y"))
                continue
            s.set(x, Y_BASE, z, BASE)
            for y in range(Y_BASE + 1, Y_BEAM):
                s.set(x, y, z, PLANKS)
            s.set(x, Y_BEAM, z, log(BEAM, "x" if z in (Z0, Z1) else "z"))
    s.box(X0 + 1, Y_BEAM, Z0 + 1, X1 - 1, Y_BEAM, Z1 - 1, PLANKS)     # ceiling


def _roof(s):
    for x in range(X0, X1 + 1):
        for z in range(Z0 - 1, Z1 + 2):
            if z == RIDGE_Z:
                s.set(x, RIDGE_Y, z, RIDGE)
            else:
                # the slope climbs toward the ridge: stairs face the ridge
                s.set(x, roof_y(z), z, stairs(ROOF_STAIRS, "south" if z < RIDGE_Z else "north"))
    for x in (X0, X1):                                  # gable ends: planks up to the underside of the roof
        for z in range(Z0 + 1, Z1):
            for y in range(Y_BEAM + 1, roof_y(z)):
                s.set(x, y, z, PLANKS)


def _openings(s):
    dx, dy, dz = DOOR
    s.set(dx, dy, dz, door(DOOR_ID, "north", "lower"))
    s.set(dx, dy + 1, dz, door(DOOR_ID, "north", "upper"))
    windows = [(x, Z0) for x in (2, 6)] + [(x, Z1) for x in (2, 6)] + \
              [(X0, z) for z in (2, 6)] + [(X1, z) for z in (2, 6)]
    for x, z in windows:
        for y in (Y_BASE + 1, Y_BASE + 2):
            s.set(x, y, z, pane())
    # a pane connects to full cubes and to other panes beside it; set the four flags from the neighbours
    for x, z in windows:
        for y in (Y_BASE + 1, Y_BASE + 2):
            def joins(nx, nz):
                n = s.get(nx, y, nz)
                return n is not None and (n.split("[")[0] in FULL_CUBES or n.startswith("minecraft:glass_pane"))
            s.set(x, y, z, pane(north=joins(x, z - 1), east=joins(x + 1, z), south=joins(x, z + 1), west=joins(x - 1, z)))


def _inside(s):
    # a hut block's facing is the way its placer looked, so facing north puts its front on the south side, to the door
    s.set_anchor(*HUT_POS, hut("north"))
    s.set(*ANVIL_POS, anvil("east"))
    s.tag(*ANVIL_POS, "work")
    s.set(*RACK_MAIN, rack("south", "blockrackempty"))   # a double rack: the main half faces its partner,
    s.set(*RACK_PARTNER, rack("north", "blockrackair"))  # the partner (not rendered) faces back
    for z in (3, 4, 5):                                  # a bench along the west wall, its backs to the wall
        s.set(X0 + 1, Y_BASE, z, stairs(BENCH, "west"))


def _lights(s):
    for x, z in LANTERNS_INSIDE:
        s.set(x, LANTERN_Y + 1, z, chain())
        s.set(x, LANTERN_Y, z, lantern(True))
    s.set(CENTER_X, RIDGE_Y - 1, RIDGE_Z, lantern(True))   # keeps the roof space from going dark
    for x in (PORCH_X0, PORCH_X1):                       # either side of the way to the door
        s.set(x, Y_BASE, Z1 + 1, lantern(False))


def runesmith1():
    s = Structure("runesmith1")
    _ground(s)
    _walls(s)
    _roof(s)
    _openings(s)
    _inside(s)
    _lights(s)
    return s


LEVELS = {1: runesmith1}
