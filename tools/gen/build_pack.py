"""Generate the Structurize pack shipped inside the Runesmith jar, and check it.

    python tools/gen/build_pack.py          (from any directory)

    resources/blueprints/runesmith/runesmith/pack.json
    resources/blueprints/runesmith/runesmith/runesmith.png                      pack icon
    resources/blueprints/runesmith/runesmith/runesmith/runesmith1.blueprint     level 1 (temporary design)

and previews of the design in tools/out/. Every gate runs; the blueprint is copied into resources/
only when all of them pass, so what is committed has always been checked. Prints ALL OK and exits
0 when everything passes, otherwise lists the problems and exits 1.
"""
import json
import os
import shutil
import sys

from PIL import Image

import checks
import designs
import icon
import isorender
import paths
import voxel
from voxel import parse_state

PACK_NAME = "Runesmith"
PACK_ROOT = paths.res("blueprints", "runesmith", "runesmith")
FOLDER = "runesmith"                       # the category folder inside the pack: runesmith/runesmith1.blueprint
BUILDING_TYPE = "runesmith:runesmith"      # the building's registry name
BE_TYPE = "runesmith:colonybuilding"       # the hut block's own block entity type
REQUIRED_MODS = ("minecolonies", "runesmith")   # as Structurize writes it: every namespace but minecraft and structurize
ARCHITECTS = ("Lovkar",)
PACK_JSON = {
    "icon": "runesmith.png",
    "name": PACK_NAME,
    "authors": ["Lovkar", "Claude"],
    "desc": "Buildings for the Runesmith, who applies the Enchanter's books to the colony's gear.",
    "mods": ["structurize", "minecolonies", "runesmith"],
    "version": "1",
    "pack-format": "1",
}
ICON_SIZE = (64, 64)

# The hut block is defined by this mod, so it is in no jar yet: whitelist its id and the one property
# it has. Everything else a design uses is looked up in the jars.
MOD_BLOCKS = {designs.HUT_ID: {"facing": ("north", "east", "south", "west")}}
WORKER_LIGHT = 11                          # block light wanted wherever the worker stands
ROOM_LIGHT = 8                             # and on every floor cell of the room


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


# ---------------------------------------------------------------- gates specific to this building
def hut_problems(s):
    huts = [p for p, b in s.blocks.items() if parse_state(b)[0] == designs.HUT_ID]
    if len(huts) != 1:
        return [f"{len(huts)} hut blocks, expected exactly 1"]
    if s.anchor != huts[0]:
        return [f"the anchor {s.anchor} is not the hut block {huts[0]}"]
    return []


def work_problems(s):
    anvils = [p for p, b in s.blocks.items() if parse_state(b)[0] == designs.ANVIL_ID]
    work = [p for p, names in s.tags.items() if "work" in names]
    extra = sorted({n for names in s.tags.values() for n in names} - {"work"})
    problems = []
    if len(anvils) != 1:
        problems.append(f"{len(anvils)} anvils, expected exactly 1 work block")
    if len(work) != 1:
        problems.append(f"{len(work)} positions tagged work, expected exactly 1")
    elif work[0] not in anvils:
        problems.append(f"the work tag at {work[0]} is not on the anvil")
    if extra:
        problems.append(f"unexpected tags {extra}")
    return problems


def rack_problems(s):
    """Two racks side by side against a wall, stored as MineColonies stores a double rack: the main
    half faces its partner and shows the empty rack, the partner faces back and is not rendered."""
    racks = sorted(p for p, b in s.blocks.items() if parse_state(b)[0] == designs.RACK_ID)
    if len(racks) != 2:
        return [f"{len(racks)} racks, expected 2"]
    problems = []
    (ax, ay, az), (bx, by, bz) = racks
    if abs(ax - bx) + abs(az - bz) != 1 or ay != by:
        return [f"the racks {racks[0]} and {racks[1]} are not neighbours"]
    names = {v: k for k, v in checks.FACING.items()}
    to_partner = names[(bx - ax, bz - az)]
    main_state, partner_state = (parse_state(s.blocks[p])[1] for p in racks)
    if (main_state.get("variant"), main_state.get("facing")) != ("blockrackempty", to_partner):
        problems.append(f"the main rack {racks[0]} should be blockrackempty facing {to_partner}: {main_state}")
    back = names[(ax - bx, az - bz)]
    if (partner_state.get("variant"), partner_state.get("facing")) != ("blockrackair", back):
        problems.append(f"the partner rack {racks[1]} should be blockrackair facing {back}: {partner_state}")
    for x, y, z in racks:
        along = (bx - ax, bz - az)
        walls = [(dx, dz) for dx, dz in checks.NEIGH4 if (dx, dz) not in (along, (-along[0], -along[1]))
                 and checks.kind_at(s, x + dx, y, z + dz) == "solid"]
        if not walls:
            problems.append(f"the rack at {(x, y, z)} is not against a wall")
    return problems


def light_problems(s, enclosed, walk_info):
    """Returns (problems, text). Light is wanted where the worker stands (beside the hut block, the anvil
    and the racks), on the whole floor of the room, and a roof space must not be dark."""
    lv = checks.light_map(s)
    problems = []
    cells = sorted({c for (_, stand, _) in walk_info.values() for c in stand})
    if not cells:
        problems.append("no cell where the worker could stand was found to measure the light at")
    low = [(c, lv.get(c, 0)) for c in cells if lv.get(c, 0) < WORKER_LIGHT]
    if low:
        problems.append(f"light below {WORKER_LIGHT} where the worker stands: {low}")
    lanterns = [p for p, b in s.blocks.items() if parse_state(b)[0] == "minecraft:lantern" and p in enclosed]
    if len(lanterns) < 2:
        problems.append(f"{len(lanterns)} lanterns inside, at least 2 wanted")
    dark = sorted(c for c in enclosed if lv.get(c, 0) < 1)
    if dark:
        problems.append(f"dark cells under the roof, where mobs could spawn: {dark[:6]}")
    room = [c for c in enclosed if c[1] == designs.Y_BASE and checks.standable(s, *c)]
    worst = min((lv.get(c, 0) for c in room), default=0)
    if not room:
        problems.append("no room floor to light")
    elif worst < ROOM_LIGHT:
        problems.append(f"the room floor drops to light {worst} (wanted {ROOM_LIGHT})")
    stand = [lv.get(c, 0) for c in cells] or [0]
    return problems, (f"{len(cells)} cells beside hut, anvil and racks at {min(stand)}-{max(stand)}, room floor from {worst}, "
                      f"{len(lanterns)} lanterns inside, {len(enclosed)} enclosed cells all lit")


# ---------------------------------------------------------------- the written file
def roundtrip_problems(n, path, file_name, pack_path):
    """Read the file back with our own loader and compare it with the design block for block, plus the
    fields the game reads: schematic name, corners, pack and path, tags, block entities."""
    try:
        return _roundtrip_problems(n, path, file_name, pack_path)
    except (KeyError, IndexError, ValueError, TypeError) as e:
        return [f"the file is malformed: {type(e).__name__} {e}"]


def _roundtrip_problems(n, path, file_name, pack_path):
    back = voxel.load_blueprint(path)
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
    sx, sy, sz = n.size()
    if (int(f["size_x"]), int(f["size_y"]), int(f["size_z"])) != (sx, sy, sz):
        problems.append("size differs")
    if int(f["version"]) != 1 or int(f["mcversion"]) != voxel.DATA_VERSION:
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
    racks = {p for p, b in n.blocks.items() if parse_state(b)[0] == designs.RACK_ID}
    if set(tiles) != racks | {n.anchor}:
        problems.append(f"block entities at {sorted(tiles)}, expected the hut and the racks")
    hut = tiles.get(n.anchor)
    if hut is not None:
        prov = hut["blueprintDataProvider"]
        (x0, y0, z0), (x1, y1, z1) = (0, 0, 0), (sx - 1, sy - 1, sz - 1)
        corners = [tuple(int(prov[c][k]) for k in "xyz") for c in ("corner1", "corner2")]
        expect = [(x0 - ax, y0 - ay, z0 - az), (x1 - ax, y1 - ay, z1 - az)]
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
    desc = PACK_JSON["desc"]
    if "\n" in desc or not desc.endswith(".") or desc.count(". ") > 0:
        problems.append("desc should be one sentence")
    with Image.open(os.path.join(PACK_ROOT, PACK_JSON["icon"])) as img:
        if img.size != ICON_SIZE or img.format != "PNG":
            problems.append(f"icon is {img.format} {img.size}, expected PNG {ICON_SIZE}")
    return problems


# ---------------------------------------------------------------- one level
def preview(s):
    """The full view and the cutaway, into tools/out/ (git-ignored). Drawn even when a gate fails: that is when it helps."""
    isorender.render(s, paths.out(f"{s.name}.png"), title=s.name)
    isorender.render(s, paths.out(f"{s.name}_cutaway.png"), cutaway_above=designs.Y_BEAM,
                     near_walls=(designs.X1, designs.Z1, designs.Y_BASE + 1), title=f"{s.name}, cutaway")


def build_level(make, reg, gates):
    s = make()
    file_name = f"{s.name}.blueprint"
    pack_path = f"{FOLDER}/{file_name}"
    work = sorted(p for p, t in s.tags.items() if "work" in t)

    gates.run("ids", [f"{n} does not exist" for n in checks.unknown_ids(s, reg)],
              f"{len(s.count())} block ids exist in the jars (vanilla assets, MineColonies, Structurize) or are whitelisted: "
              + ", ".join(sorted(MOD_BLOCKS)))
    gates.run("states", checks.bad_states(s, reg),
              f"{len({voxel.canon(b) for b in s.blocks.values()})} distinct block states, every property and value valid")
    gates.run("classes", [f"{n}: no class in checks.py" for n in checks.unclassified(s)],
              "every block has a class the gates know")
    gates.run("hut", hut_problems(s), f"one {designs.HUT_ID} at the anchor {s.anchor}")
    gates.run("work", work_problems(s), f"one anvil with the work tag on it, at {work[0] if work else None}")
    gates.run("racks", rack_problems(s), "a double rack against a wall, main and partner states as MineColonies stores them")
    gates.run("floating", [f"{p} {s.blocks[p]}" for p in checks.floating(s)], f"all {len(s.blocks)} blocks connect to the ground")
    gates.run("attached", [f"{p} {st}: {why}" for p, st, why in checks.unsupported(s)],
              "doors, lanterns, chains and the anvil have what they hang from or stand on")
    enc = checks.enclosed(s)
    gates.run("door", checks.door_problems(s, enc), "floor and headroom on both sides of the door, one side opens into the room")
    walk, info = checks.walk_problems(s, enc)
    anvil_reach = [r for (k, _, r) in info.values() if k == "anvil"]
    if not walk and (not anvil_reach or designs.WORK_STAND not in anvil_reach[0]):
        walk.append(f"the intended work spot {designs.WORK_STAND} is not a reachable cell beside the anvil")
    gates.run("walk", walk, "from the door the hut block, the anvil and both racks are reachable through two-block-high cells; "
                            f"work spot {designs.WORK_STAND}")
    light, text = light_problems(s, enc, info)
    gates.run("light", light, text)
    preview(s)

    # write to a staging folder first; the pack folder only ever receives a blueprint that passed every gate
    staged = os.path.join(paths.out("stage"), file_name)
    os.makedirs(os.path.dirname(staged), exist_ok=True)
    try:
        s.to_blueprint(staged, file_name, PACK_NAME, pack_path, BUILDING_TYPE, BE_TYPE, REQUIRED_MODS, ARCHITECTS)
    except ValueError as e:
        gates.run("write", [str(e)], "")
        return False
    n = s.normalized()
    racks = sorted(p for p, b in n.blocks.items() if parse_state(b)[0] == designs.RACK_ID)
    gates.run("format", checks.format_problems(staged, n.anchor, racks),
              "top-level fields, hut block entity, blueprintDataProvider, positioned tag and rack entities match MineColonies' own blueprints")
    gates.run("roundtrip", roundtrip_problems(n, staged, file_name, pack_path),
              f"read back: {len(n.blocks)} blocks, anchor, tags, block entities, corners and names identical")
    if gates.failed:
        print(f"-- {file_name} NOT written to the pack: {gates.failed} gate(s) failed")
        return False
    final = os.path.join(PACK_ROOT, FOLDER, file_name)
    os.makedirs(os.path.dirname(final), exist_ok=True)
    shutil.copyfile(staged, final)
    summary(s, n, final)
    return True


def summary(s, n, final):
    """Where everything is, in the numbers a test harness or a later design needs: all relative to the hut block."""
    sx, sy, sz = n.size()
    rel = lambda p: tuple(p[i] - s.anchor[i] for i in range(3))
    far = tuple(d - 1 - a for d, a in zip((sx, sy, sz), n.anchor))
    racks = sorted(p for p, b in s.blocks.items() if parse_state(b)[0] == designs.RACK_ID)
    work = sorted(p for p, t in s.tags.items() if "work" in t)
    print(f"{os.path.basename(final)}: {sx} x {sy} x {sz} blocks, {len(s.blocks)} non-air, {os.path.getsize(final)} bytes, "
          f"{os.path.relpath(final, paths.ROOT).replace(os.sep, '/')}")
    print(f"  primary offset (the hut block) {n.anchor} in the blueprint; corners {tuple(-a for a in n.anchor)} .. {far} from it")
    print(f"  from the hut block: door (lower half) {rel(designs.DOOR)}, work tag on the anvil {rel(work[0])}, "
          f"work spot {rel(designs.WORK_STAND)}, racks {[rel(p) for p in racks]}")


def main():
    os.makedirs(os.path.join(PACK_ROOT, FOLDER), exist_ok=True)
    with open(os.path.join(PACK_ROOT, "pack.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(PACK_JSON, fh, indent=2)
        fh.write("\n")
    icon.draw_icon(os.path.join(PACK_ROOT, PACK_JSON["icon"]))
    gates = Gates()
    gates.run("pack", pack_problems(), f"pack.json and a {ICON_SIZE[0]} x {ICON_SIZE[1]} icon")
    reg = checks.Registry(MOD_BLOCKS)
    try:
        for _, make in sorted(designs.LEVELS.items()):
            build_level(make, reg, gates)
    except FileNotFoundError as e:             # a jar missing from libs/
        print("!! " + str(e))
        return 2
    finally:
        reg.close()
    print("ALL OK" if not gates.failed else "PROBLEMS")
    return 0 if not gates.failed else 1


if __name__ == "__main__":
    sys.exit(main())
