"""Generate the Structurize pack shipped inside the Runesmith jar, and check it.

    python tools/gen/build_pack.py             (from any directory)
    python tools/gen/build_pack.py --previews  also draw a front view of every level into tools/out/

    resources/blueprints/runesmith/runesmith/pack.json
    resources/blueprints/runesmith/runesmith/runesmith.png                       pack icon
    resources/blueprints/runesmith/runesmith/runesmith/<look><level>.blueprint   3 looks x 5 levels

The three looks (designs_a.py Forge Hall, designs_b.py Rune Tower, designs_c.py Crystal Heart) are
alternatives of the same building, the way Voyager ships its looks: the build tool offers each, and
every level of a look shares one footprint, so an upgrade never grows past the outline that was
placed. The footprint is the union of the look's five levels, aligned on the hut block. Inside it,
around the design, the two lowest layers above the foundation - the design's grass layer and the
layer people walk in outside - are left to the terrain (Structurize's "keep" block: no landscaping,
no materials) and everything above them is air the builder clears.

Why two layers: the designs raise the floor one block on a plinth, so the hut block stands two
above the design's grass, where MineColonies' huts usually stand one above the ground. A player who
places the hut the usual way sinks the building one block: the plinth then lies flush with the
terrain and the entrance is at ground level. One who places it a block higher sees it as drawn.
Either way nothing around the building is dug out or filled, and both ways are walkable.

Every gate runs (gates.py on each level and across the levels; the written file against
MineColonies' own blueprints; a byte-for-byte round trip). A blueprint reaches resources/ only when
all of its gates pass. Prints ALL OK and exits 0 when everything passes, otherwise lists the
problems and exits 1.
"""
import importlib
import json
import os
import shutil
import sys

from PIL import Image

import blueprint
import checks
import gates
import icon
import paths
from blueprint import Plain, parse_state

PACK_NAME = "Runesmith"
PACK_ROOT = paths.res("blueprints", "runesmith", "runesmith")
FOLDER = "runesmith"                       # the category folder inside the pack: runesmith/forgehall1.blueprint
BUILDING_TYPE = "runesmith:runesmith"      # the building's registry name
BE_TYPE = "runesmith:colonybuilding"       # the hut block's own block entity type
REQUIRED_MODS = ("minecolonies", "runesmith")   # as Structurize writes it: every namespace but minecraft and structurize
ARCHITECTS = ("Lovkar",)
KEEP = "structurize:blocksubstitution"     # "leave whatever is there": no work, no materials
SOLID_FILL = "structurize:blocksolidsubstitution"   # "a solid block of this terrain": the builder fills it from the ground
FOUNDATION = "minecraft:cobbled_deepslate" # what the designs lay under their plinths, below the grass layer
LOOKS = (                                  # design module, blueprint name, what the build tool and README call it
    ("designs_a", "forgehall", "Forge Hall"),
    ("designs_b", "runetower", "Rune Tower"),
    ("designs_c", "crystalheart", "Crystal Heart"),
)
LEVELS = (1, 2, 3, 4, 5)
PACK_JSON = {
    "icon": "runesmith.png",
    "name": PACK_NAME,
    "authors": ["Lovkar", "Claude"],
    "desc": ("Buildings for the Runesmith, who applies the Enchanter's books to the colony's gear: a Forge Hall, "
             "a Rune Tower or a Crystal Heart, each the same building in five levels - pick the look you like."),
    "mods": ["structurize", "minecolonies", "runesmith"],
    "version": "1",
    "pack-format": "1",
}
ICON_SIZE = (64, 64)


class Gates:
    def __init__(self):
        self.failed = 0

    def run(self, name, problems, ok_text):
        if problems:
            self.failed += 1
            print(f"!! {name}: {len(problems)} problem(s)")
            for p in problems[:10]:
                print("     " + str(p))
            if len(problems) > 10:
                print(f"     ... and {len(problems) - 10} more")
        else:
            print(f"ok {name:<10s} {ok_text}")


# ---------------------------------------------------------------- one look
def footprint(levels):
    """The union of the levels' bounds, all aligned on the hut block (gates.py already insists the hut
    block does not move between levels, so the alignment is normally a no-op)."""
    a5 = levels[5].anchor
    aligned = {}
    for lvl, s in levels.items():
        p = Plain.of(s)
        dx, dy, dz = (a5[i] - s.anchor[i] for i in range(3))
        aligned[lvl] = p.translated(dx, dy, dz) if (dx or dy or dz) else p
    lo = [min(s.bounds()[0][i] for s in aligned.values()) for i in range(3)]
    hi = [max(s.bounds()[1][i] for s in aligned.values()) for i in range(3)]
    return aligned, (tuple(lo), tuple(hi))


KEEP_TOP = gates.GROUND + 1               # the highest layer left to the terrain around the design (see the module doc)


def foundation(s):
    """The designs' foundation course under the plinth (below the grass layer, never seen) becomes solid
    substitution: the builder fills it with the ground that is there instead of asking for cobbled deepslate."""
    t = Plain(s.name, s.blocks, s.tags, s.anchor)
    for p, b in s.blocks.items():
        if p[1] < gates.GROUND and parse_state(b)[0] == FOUNDATION:
            t.blocks[p] = SOLID_FILL
    return t


def padded(s, box):
    """Inside the box, around the design: up to KEEP_TOP the terrain stays as it is, above it is air."""
    (x0, y0, z0), (x1, _, z1) = box
    t = Plain(s.name, s.blocks, s.tags, s.anchor)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, KEEP_TOP + 1):
                if (x, y, z) not in t.blocks:
                    t.blocks[(x, y, z)] = KEEP
    return t


def roundtrip_problems(n, path, file_name, pack_path, box_size):
    """Read the file back and compare it with the design block for block, plus the fields the game
    reads: schematic name, corners, pack and path, tags, block entities."""
    try:
        return _roundtrip_problems(n, path, file_name, pack_path, box_size)
    except (KeyError, IndexError, ValueError, TypeError) as e:
        return [f"the file is malformed: {type(e).__name__} {e}"]


def _roundtrip_problems(n, path, file_name, pack_path, box_size):
    back = blueprint.load_blueprint(path)
    problems = []
    want = {p: parse_state(b) for p, b in n.blocks.items()}
    got = {p: parse_state(b) for p, b in back.blocks.items()}
    if want != got:
        diff = sorted(set(want) ^ set(got) | {p for p in want if p in got and want[p] != got[p]})
        problems.append(f"{len(diff)} blocks differ after the round trip, first {diff[:3]}")
    if back.anchor != n.anchor:
        problems.append(f"anchor {back.anchor} after the round trip, expected {n.anchor}")
    if {p: sorted(t) for p, t in back.tags.items()} != {p: sorted(t) for p, t in n.tags.items()}:
        problems.append(f"tags {back.tags} after the round trip, expected {n.tags}")
    f = back.raw
    sx, sy, sz = box_size
    if (int(f["size_x"]), int(f["size_y"]), int(f["size_z"])) != (sx, sy, sz):
        problems.append("size differs from the look's footprint")
    if int(f["version"]) != 1 or int(f["mcversion"]) != blueprint.DATA_VERSION:
        problems.append(f"version {int(f['version'])} / mcversion {int(f['mcversion'])}")
    if str(f["name"]) != file_name.replace(".blueprint", ""):
        problems.append(f"name {f['name']}")
    if [str(a) for a in f["architects"]] != list(ARCHITECTS):
        problems.append(f"architects {list(f['architects'])}")
    if [str(m) for m in f["required_mods"]] != list(REQUIRED_MODS):
        problems.append(f"required_mods {list(f['required_mods'])}")
    if len(f["entities"]) != 0:
        problems.append("entities should be empty")
    if len(f["blocks"]) != (sx * sy * sz + 1) // 2:
        problems.append("the packed block array has the wrong length")
    ax, ay, az = n.anchor
    tiles = {(int(te["x"]), int(te["y"]), int(te["z"])): te for te in f["tile_entities"]}
    racks = {p for p, b in n.blocks.items() if parse_state(b)[0] == blueprint.RACK_BLOCK}
    if set(tiles) != racks | {n.anchor}:
        problems.append(f"block entities at {sorted(tiles)}, expected the hut and the racks")
    hut = tiles.get(n.anchor)
    if hut is not None:
        prov = hut["blueprintDataProvider"]
        corners = [tuple(int(prov[c][k]) for k in "xyz") for c in ("corner1", "corner2")]
        expect = [(-ax, -ay, -az), (sx - 1 - ax, sy - 1 - ay, sz - 1 - az)]
        checks_ = [
            (str(hut["id"]) == BE_TYPE, f"hut block entity id {hut['id']}"),
            (str(hut["type"]) == BUILDING_TYPE, f"hut type {hut['type']}"),
            (str(hut["pack"]) == PACK_NAME and str(prov["pack"]) == PACK_NAME, "hut pack name"),
            (str(hut["path"]) == pack_path and str(prov["path"]) == pack_path, "hut path"),
            (str(prov["schematicName"]) == file_name.replace(".blueprint", ""), f"schematicName {prov['schematicName']}"),
            (corners == expect, f"corners {corners}, expected {expect}"),
        ]
        problems += [m for ok, m in checks_ if not ok]
    po = f["optional_data"]["structurize"]["primary_offset"]
    if (int(po["x"]), int(po["y"]), int(po["z"])) != n.anchor:
        problems.append("primary_offset is not the hut block")
    return problems


def pack_problems():
    problems = []
    with open(os.path.join(PACK_ROOT, "pack.json"), encoding="utf-8") as fh:
        if json.load(fh) != PACK_JSON:
            problems.append("pack.json differs from what was meant to be written")
    with Image.open(os.path.join(PACK_ROOT, PACK_JSON["icon"])) as img:
        if img.size != ICON_SIZE or img.format != "PNG":
            problems.append(f"icon is {img.format} {img.size}, expected PNG {ICON_SIZE}")
    return problems


def build_look(module_name, name, title, gates_, previews):
    mod = importlib.import_module(module_name)
    before = gates_.failed
    print(f"== {title} ({module_name}.py -> {FOLDER}/{name}1-5)")
    res, cross = gates.check_look(mod)
    gates_.run(f"{name} levels", cross, "levels 1-5, the hut block fixed, racks never fewer, every level inside level 5's extent")
    levels = {}
    for lvl in LEVELS:
        problems, facts = res.get(lvl, ([f"level {lvl} missing"], {}))
        gates_.run(f"{name}{lvl}", problems,
                   f"size {facts.get('size')}, racks {facts.get('racks')}, worker light >= {facts.get('light_work_min')}, "
                   f"inside >= {facts.get('light_inside_min')}")
        levels[lvl] = mod.LEVELS[lvl]()
    if gates_.failed != before:
        return False
    aligned, box = footprint(levels)
    (x0, y0, z0), (x1, y1, z1) = box
    size = (x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1)
    print(f"   footprint {size[0]} x {size[2]}, {size[1]} high, the same for all five levels")
    for lvl in LEVELS:
        s = padded(foundation(aligned[lvl]), box)
        file_name = f"{name}{lvl}.blueprint"
        pack_path = f"{FOLDER}/{file_name}"
        staged = os.path.join(paths.out("stage"), file_name)
        os.makedirs(os.path.dirname(staged), exist_ok=True)
        try:
            blueprint.write_blueprint(s, staged, file_name, PACK_NAME, pack_path, BUILDING_TYPE, BE_TYPE, REQUIRED_MODS,
                                      ARCHITECTS, box=box)
        except ValueError as e:
            gates_.run(f"{name}{lvl} write", [str(e)], "")
            continue
        n = s.normalized(box)
        racks = sorted(p for p, b in n.blocks.items() if parse_state(b)[0] == blueprint.RACK_BLOCK)
        failed = gates_.failed
        gates_.run(f"{name}{lvl} format", checks.format_problems(staged, n.anchor, racks),
                   "top-level fields, hut block entity, positioned tags and racks as in MineColonies' own blueprints")
        gates_.run(f"{name}{lvl} trip", roundtrip_problems(n, staged, file_name, pack_path, size),
                   f"read back: {len(n.blocks)} blocks, anchor, tags, block entities, corners and names identical")
        if gates_.failed != failed:
            continue
        final = os.path.join(PACK_ROOT, FOLDER, file_name)
        shutil.copyfile(staged, final)
        rel = lambda p: tuple(p[i] - n.anchor[i] for i in range(3))
        work = [p for p, t in n.tags.items() if "work" in t]
        print(f"   {file_name}: {os.path.getsize(final)} bytes; from the hut block: anvil {rel(work[0])}, "
              f"{len(racks)} rack blocks")
        if previews:
            preview(levels[lvl], f"{name}{lvl}")
    return True


def preview(s, name):
    """A textured front view into tools/out/ (git-ignored), standing on a little island of terrain."""
    import isorender
    import voxel
    img, _ = isorender.render(voxel.with_ground(s, margin=3).blocks, view=0, width=1000)
    img.save(paths.out(f"{name}.png"))


def main():
    previews = "--previews" in sys.argv[1:]
    folder = os.path.join(PACK_ROOT, FOLDER)
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(PACK_ROOT, "pack.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(PACK_JSON, fh, indent=2)
        fh.write("\n")
    icon.draw_icon(os.path.join(PACK_ROOT, PACK_JSON["icon"]))
    g = Gates()
    g.run("pack", pack_problems(), f"pack.json and a {ICON_SIZE[0]} x {ICON_SIZE[1]} icon")
    # only what this run writes stays in the pack folder: a look or level that was dropped must not linger
    wanted = {f"{name}{lvl}.blueprint" for _, name, _ in LOOKS for lvl in LEVELS}
    for f in os.listdir(folder):
        if f.endswith(".blueprint") and f not in wanted:
            os.remove(os.path.join(folder, f))
            print(f"removed {FOLDER}/{f}, which no look produces any more")
    try:
        for module_name, name, title in LOOKS:
            build_look(module_name, name, title, g, previews)
    except FileNotFoundError as e:             # a jar missing from libs/
        print("!! " + str(e))
        return 2
    print("ALL OK" if not g.failed else "PROBLEMS")
    return 0 if not g.failed else 1


if __name__ == "__main__":
    sys.exit(main())
