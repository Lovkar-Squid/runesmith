"""Block-state helpers for the designs: short names in, full Minecraft state strings out.

Connection properties of walls, fences, panes and the shape of stairs are placeholders here;
Structure.finalize() computes them from the neighbours, like the game does when the block is placed.
"""


def mc(name):
    return name if ":" in name else "minecraft:" + name


def stairs(mat, facing, half="bottom"):
    return f"minecraft:{mat}_stairs[facing={facing},half={half},shape=straight,waterlogged=false]"


def slab(mat, kind="bottom"):
    return f"minecraft:{mat}_slab[type={kind},waterlogged=false]"


def wall(mat):
    return f"minecraft:{mat}_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"


def fence(mat):
    return f"minecraft:{mat}_fence[east=false,north=false,south=false,waterlogged=false,west=false]"


def gate(mat, facing, open_=False):
    return f"minecraft:{mat}_fence_gate[facing={facing},in_wall=false,open={str(open_).lower()},powered=false]"


def pane(color=None):
    n = f"{color}_stained_glass_pane" if color else "glass_pane"
    return f"minecraft:{n}[east=false,north=false,south=false,waterlogged=false,west=false]"


def bars():
    return "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]"


def log(wood, axis="y", stripped=False, bark=False):
    kind = "wood" if bark else "log"
    if wood in ("crimson", "warped"):
        kind = "hyphae" if bark else "stem"
    return f"minecraft:{'stripped_' if stripped else ''}{wood}_{kind}[axis={axis}]"


def pillar(name, axis="y"):
    """Blocks with an axis: purpur_pillar, quartz_pillar, basalt, polished_basalt, bone_block..."""
    return f"minecraft:{name}[axis={axis}]"


def door(wood, facing, half, hinge="left", open_=False):
    return f"minecraft:{wood}_door[facing={facing},half={half},hinge={hinge},open={str(open_).lower()},powered=false]"


def trapdoor(wood, facing, half="bottom", open_=False):
    return f"minecraft:{wood}_trapdoor[facing={facing},half={half},open={str(open_).lower()},powered=false,waterlogged=false]"


def lantern(hanging=False, soul=False):
    return f"minecraft:{'soul_' if soul else ''}lantern[hanging={str(hanging).lower()},waterlogged=false]"


def chain(axis="y"):
    return f"minecraft:chain[axis={axis},waterlogged=false]"


def candle(color="purple", n=1, lit=True):
    c = f"{color}_candle" if color else "candle"
    return f"minecraft:{c}[candles={n},lit={str(lit).lower()},waterlogged=false]"


def crystal(size="cluster", facing="up"):
    n = {"cluster": "amethyst_cluster", "large": "large_amethyst_bud", "medium": "medium_amethyst_bud",
         "small": "small_amethyst_bud"}[size]
    return f"minecraft:{n}[facing={facing},waterlogged=false]"


def campfire(lit=True, soul=False, facing="south"):
    """signal_fire is left out: the blueprint gate accepts only the properties the model uses
    (plus waterlogged and powered), and the game defaults it to false."""
    return f"minecraft:{'soul_' if soul else ''}campfire[facing={facing},lit={str(lit).lower()},waterlogged=false]"


def anvil(facing):
    return f"minecraft:anvil[facing={facing}]"


def rack(facing, variant="blockrackemptysingle"):
    return f"minecolonies:blockminecoloniesrack[facing={facing},variant={variant}]"


def hut(facing):
    return f"runesmith:blockhutrunesmith[facing={facing}]"


def lectern(facing):
    """Always without a book (a book needs block-entity data); has_book defaults to false."""
    return f"minecraft:lectern[facing={facing},powered=false]"


def grindstone(facing, face="floor"):
    return f"minecraft:grindstone[face={face},facing={facing}]"


def furnace(facing, lit=False, kind="blast_furnace"):
    """Unlit: a furnace without fuel switches itself off on its first tick, so a lit one in a
    blueprint would only mislead the light check."""
    return f"minecraft:{kind}[facing={facing},lit={str(lit).lower()}]"


def button(mat, facing, face="wall"):
    return f"minecraft:{mat}_button[face={face},facing={facing},powered=false]"


def barrel(facing="up", open_=False):
    return f"minecraft:barrel[facing={facing},open={str(open_).lower()}]"


def cauldron(water=False):
    return "minecraft:water_cauldron[level=3]" if water else "minecraft:cauldron"


def chiseled_shelf(facing, slots=(1, 0, 1, 1, 0, 1)):
    occ = ",".join(f"slot_{i}_occupied={'true' if v else 'false'}" for i, v in enumerate(slots))
    return f"minecraft:chiseled_bookshelf[facing={facing},{occ}]"


def end_rod(facing="up"):
    return f"minecraft:end_rod[facing={facing}]"


def lightning_rod(facing="up"):
    return f"minecraft:lightning_rod[facing={facing},powered=false,waterlogged=false]"


def copper_bulb(kind="", lit=True):
    return f"minecraft:{kind + '_' if kind else ''}copper_bulb[lit={str(lit).lower()},powered=false]"


def carpet(color):
    return f"minecraft:{color}_carpet"


def pot(plant=None):
    return f"minecraft:potted_{plant}" if plant else "minecraft:flower_pot"


def leaves(kind="dark_oak"):
    return f"minecraft:{kind}_leaves[distance=1,persistent=true,waterlogged=false]"


def bell(facing, attachment="ceiling"):
    return f"minecraft:bell[attachment={attachment},facing={facing},powered=false]"
