"""Gates for a generated building. Each gate returns a list of problems (empty = pass).

The point of every gate is a failure that does not show up when the blueprint is written but only
in the game: Structurize silently turns an unknown block id into air and ignores a property it
does not know, a lantern with nothing to hang from pops off, a door without floor on both sides
traps the citizens, and a worker in a dark room is a spawn point.

Block ids and block-state properties are read from the blockstate files of the jars in libs/
(vanilla assets, MineColonies, Structurize). Blocks this mod defines are not in any jar yet and are
passed in explicitly.
"""
import gzip
import io
import json
import re
import zipfile
from collections import defaultdict, deque
from functools import lru_cache

import nbtlib
from nbtlib import tag as T

import paths
from designs import HUT_ID
from voxel import canon, parse_state

NEIGH4 = [(1, 0), (-1, 0), (0, 1), (0, -1)]
NEIGH6 = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]
FACING = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


# ---------------------------------------------------------------- block classes
# Every block id a design uses must have a class, so no gate can silently skip a new block: an
# unclassified id fails the "classes" gate until it is added here, together with its rules below.
SOLID = {   # full cubes: they carry weight, stop movement and light, and a lantern can hang from them
    "minecraft:stone_bricks", "minecraft:chiseled_stone_bricks", "minecraft:polished_andesite",
    "structurize:blocksolidsubstitution", "structurize:blocksubstitution",   # the terrain these stand for
}
_EXACT = {
    "minecraft:lantern": "lantern", "minecraft:chain": "chain", "minecraft:anvil": "anvil",
    "minecraft:glass_pane": "pane", "minecolonies:blockminecoloniesrack": "rack", HUT_ID: "hut",
}
_WOOD = re.compile(r"^minecraft:(stripped_)?[a-z_]+_(planks|log|wood)$")
EMISSION = {"minecraft:lantern": 15}     # block light given off, by block id


def kind(name):
    if name in _EXACT:
        return _EXACT[name]
    if name in SOLID or _WOOD.match(name):
        return "solid"
    for suffix, k in (("_stairs", "stairs"), ("_slab", "slab"), ("_door", "door")):
        if name.endswith(suffix):
            return k
    return None


def kind_at(s, x, y, z):
    """The class of the block at a cell: None for air, '?' for a block nobody has classified (treated as closed)."""
    b = s.get(x, y, z)
    return None if b is None else (kind(parse_state(b)[0]) or "?")


def unclassified(s):
    return sorted(n for n in s.count() if kind(n) is None)


# ---------------------------------------------------------------- block registry
class Registry:
    """Block ids and state properties as the game defines them, read from the blockstate files in the jars."""
    JARS = {"minecraft": "mc-extra.jar", "minecolonies": "minecolonies-*.jar", "structurize": "structurize-*.jar"}
    NONVISUAL = {"waterlogged": {"true", "false"}, "powered": {"true", "false"}}   # valid, but no model depends on them

    def __init__(self, mod_blocks=None):
        # block id -> {property: allowed values}, for blocks this mod defines (not in any jar)
        self.mod_blocks = {b: {p: set(v) for p, v in props.items()} for b, props in (mod_blocks or {}).items()}
        self._zip, self._names, self._spec = {}, {}, {}

    def _open(self, ns):
        if ns not in self._zip:
            if ns not in self.JARS:
                return None
            z = zipfile.ZipFile(paths.lib(self.JARS[ns]))
            self._zip[ns], self._names[ns] = z, set(z.namelist())
        return self._zip[ns]

    def close(self):
        for z in self._zip.values():
            z.close()
        self._zip.clear()

    def exists(self, block_id):
        if block_id in self.mod_blocks:
            return True
        ns, _, name = block_id.partition(":")
        return self._open(ns) is not None and f"assets/{ns}/blockstates/{name}.json" in self._names[ns]

    def spec(self, block_id):
        """(visual properties -> values, list of the combinations the model lists or None for multipart)."""
        if block_id in self._spec:
            return self._spec[block_id]
        visual, combos = defaultdict(set), None
        if block_id in self.mod_blocks:
            visual = defaultdict(set, {p: set(v) for p, v in self.mod_blocks[block_id].items()})
            combos = [{}]
            for p, values in visual.items():
                combos = [dict(c, **{p: v}) for c in combos for v in values]
        else:
            ns, _, name = block_id.partition(":")
            d = json.loads(self._open(ns).read(f"assets/{ns}/blockstates/{name}.json"))
            if "variants" in d:
                combos = []
                for key in d["variants"]:
                    kv = dict(item.split("=", 1) for item in key.split(",")) if key else {}
                    combos.append(kv)
                    for k, v in kv.items():
                        visual[k].add(v)
            else:
                def collect(cond):
                    for sub in cond.get("OR", []) + cond.get("AND", []):
                        collect(sub)
                    for k, v in cond.items():
                        if k not in ("OR", "AND"):
                            visual[k].update(str(v).split("|"))
                for part in d.get("multipart", []):
                    if "when" in part:
                        collect(part["when"])
                for values in visual.values():      # a multipart model names only the non-default value
                    values.update({"false", "none"})
        self._spec[block_id] = (dict(visual), combos)
        return self._spec[block_id]

    def check_state(self, block_id, props):
        """Problems with one block state: unknown property, impossible value, or a model property left out."""
        visual, combos = self.spec(block_id)
        problems = []
        for k, v in props.items():
            if k in visual:
                if v not in visual[k]:
                    problems.append(f"{k}={v} is not one of {sorted(visual[k])}")
            elif k in self.NONVISUAL:
                if v not in self.NONVISUAL[k]:
                    problems.append(f"{k}={v} is not true/false")
            else:
                problems.append(f"unknown property {k}")
        if combos is not None:         # variants cover every combination of the model's properties
            missing = [k for k in visual if k not in props]
            if missing:
                problems.append("missing " + ", ".join(missing))
            elif not any(all(props[k] == c[k] for k in visual) for c in combos):
                problems.append("no such combination of " + ", ".join(sorted(visual)))
        return problems


def unknown_ids(s, reg):
    return sorted(n for n in s.count() if not reg.exists(n))


def bad_states(s, reg):
    problems, seen = [], set()
    for b in s.blocks.values():
        c = canon(b)
        if c in seen:
            continue
        seen.add(c)
        name, props = parse_state(c)
        if reg.exists(name):
            problems += [f"{c}: {e}" for e in reg.check_state(name, props)]
    return problems


# ---------------------------------------------------------------- structure
def floating(s):
    """Blocks whose six-neighbourhood never reaches the lowest layer (a roof or mast that hangs in the air)."""
    ymin = min(y for (_, y, _) in s.blocks)
    seen = set(p for p in s.blocks if p[1] == ymin)
    q = deque(seen)
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in NEIGH6:
            n = (x + dx, y + dy, z + dz)
            if n in s.blocks and n not in seen:
                seen.add(n)
                q.append(n)
    return sorted(p for p in s.blocks if p not in seen)


def unsupported(s):
    """Blocks that would pop off or fall in game: doors, lanterns and chains without what they hang
    from or stand on, an anvil over nothing (it falls)."""
    bad = []
    for (x, y, z), st in s.blocks.items():
        name, props = parse_state(st)
        k = kind(name)
        below, above = kind_at(s, x, y - 1, z), kind_at(s, x, y + 1, z)
        why = None
        if k == "door":
            if props.get("half") == "lower":
                up = s.get(x, y + 1, z)
                if below != "solid":
                    why = "no floor under the door"
                elif up is None or parse_state(up)[0] != name or parse_state(up)[1].get("half") != "upper":
                    why = "no upper half"
            else:
                low = s.get(x, y - 1, z)
                if low is None or parse_state(low)[0] != name or parse_state(low)[1].get("half") != "lower":
                    why = "no lower half"
        elif k == "lantern":
            if props.get("hanging") == "true":
                if above not in ("solid", "chain"):
                    why = "hangs from nothing"
            elif below != "solid":
                why = "stands on nothing"
        elif k == "chain":
            if above not in ("solid", "chain"):
                why = "hangs from nothing"
        elif k == "anvil" and below != "solid":
            why = "would fall"
        if why:
            bad.append(((x, y, z), st, why))
    return bad


# ---------------------------------------------------------------- walking
def passable(s, x, y, z):
    b = s.get(x, y, z)
    return b is None or kind(parse_state(b)[0]) == "door"      # citizens open doors


def standable(s, x, y, z):
    return kind_at(s, x, y - 1, z) == "solid" and passable(s, x, y, z) and passable(s, x, y + 1, z)


def reach(s, start):
    """Every cell a two-tall citizen can walk to from `start`: one block up or up to three down per step."""
    seen = {start}
    q = deque([start])
    while q:
        x, y, z = q.popleft()
        for dx, dz in NEIGH4:
            nx, nz = x + dx, z + dz
            for ny in (y + 1, y, y - 1, y - 2, y - 3):
                if (nx, ny, nz) in seen or not standable(s, nx, ny, nz):
                    continue
                if ny == y + 1 and not passable(s, x, y + 2, z):      # stepping up needs headroom here too
                    continue
                seen.add((nx, ny, nz))
                q.append((nx, ny, nz))
                break
    return seen


def stand_cells(s, pos):
    """Cells next to a block where a citizen can stand to use it."""
    x, y, z = pos
    return [(x + dx, y, z + dz) for dx, dz in NEIGH4 if standable(s, x + dx, y, z + dz)]


# ---------------------------------------------------------------- rooms and light
def enclosed(s):
    """Open cells (air, lanterns, chains) the outside cannot reach: the room and the roof space. Doors,
    panes and every other block count as closed."""
    (x0, y0, z0), (x1, y1, z1) = s.bounds()
    lo, hi = (x0 - 1, y0 - 1, z0 - 1), (x1 + 1, y1 + 1, z1 + 1)

    def is_open(p):
        return kind_at(s, *p) in (None, "lantern", "chain")

    def inside(p):
        return all(lo[i] <= p[i] <= hi[i] for i in range(3))

    seen = {(x, y, z) for x in range(lo[0], hi[0] + 1) for y in range(lo[1], hi[1] + 1) for z in range(lo[2], hi[2] + 1)
            if x in (lo[0], hi[0]) or y in (lo[1], hi[1]) or z in (lo[2], hi[2])}
    q = deque(seen)
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in NEIGH6:
            n = (x + dx, y + dy, z + dz)
            if n not in seen and inside(n) and is_open(n):
                seen.add(n)
                q.append(n)
    return {(x, y, z) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) for z in range(z0, z1 + 1)
            if (x, y, z) not in seen and is_open((x, y, z))}


def light_map(s):
    """Block light (0-15) in every cell around the structure. Full cubes, stairs and slabs stop light (a
    stair roof is light-tight from underneath, so the roof space needs a light of its own); doors, panes,
    racks and the rest let it through."""
    (x0, y0, z0), (x1, y1, z1) = s.bounds()
    lo, hi = (x0 - 1, y0 - 1, z0 - 1), (x1 + 1, y1 + 1, z1 + 1)
    level = {}
    buckets = [[] for _ in range(16)]
    for pos, st in s.blocks.items():
        e = EMISSION.get(parse_state(st)[0])
        if e:
            level[pos] = e
            buckets[e].append(pos)
    for lv in range(15, 1, -1):
        for pos in buckets[lv]:
            if level.get(pos) != lv:
                continue
            for dx, dy, dz in NEIGH6:
                n = (pos[0] + dx, pos[1] + dy, pos[2] + dz)
                if not all(lo[i] <= n[i] <= hi[i] for i in range(3)) or kind_at(s, *n) in ("solid", "stairs", "slab"):
                    continue
                if level.get(n, 0) < lv - 1:
                    level[n] = lv - 1
                    buckets[lv - 1].append(n)
    return level


def doors(s):
    """Every door: (lower position, facing, the two cells beside it along its axis)."""
    out = []
    for (x, y, z), st in sorted(s.blocks.items()):
        name, props = parse_state(st)
        if kind(name) == "door" and props.get("half") == "lower":
            dx, dz = FACING[props["facing"]]
            out.append(((x, y, z), props["facing"], [(x + dx, y, z + dz), (x - dx, y, z - dz)]))
    return out


def door_problems(s, inside_cells):
    """Floor and headroom on both sides of each door, and one side must lead into a room."""
    problems = []
    found = doors(s)
    if not found:
        problems.append("no door")
    for pos, facing, sides in found:
        for (x, y, z) in sides:
            if kind_at(s, x, y - 1, z) != "solid":
                problems.append(f"door {pos}: no floor at {(x, y - 1, z)}")
            if not (passable(s, x, y, z) and passable(s, x, y + 1, z)):
                problems.append(f"door {pos}: {(x, y, z)} is not clear at head height")
        inside = sum(1 for c in sides if c in inside_cells)
        if inside == 0:
            problems.append(f"door {pos}: neither side is in a sealed room (a hole in the walls or roof?)")
        elif inside == 2:
            problems.append(f"door {pos}: both sides are inside, the door leads nowhere")
    return problems


def walk_problems(s, inside_cells):
    """The door leads to the hut block, the work block and every rack through cells two blocks tall.
    Returns (problems, info)."""
    problems, info = [], {}
    found = doors(s)
    if len(found) != 1:
        return ["expected exactly one door to start from"], info
    pos, facing, sides = found[0]
    ins = [c for c in sides if c in inside_cells]
    outs = [c for c in sides if c not in inside_cells]
    if len(ins) != 1 or len(outs) != 1:
        return [f"door {pos} does not lead from outside into a sealed room"], info
    outside, entry = outs[0], ins[0]
    if not standable(s, *outside):
        return [f"cannot stand in front of the door at {outside}"], info
    seen = reach(s, outside)
    if entry not in seen:
        problems.append(f"the cell inside the door {entry} is not reachable")
    targets = {}
    for p, st in s.blocks.items():
        k = kind(parse_state(st)[0])
        if k in ("hut", "anvil", "rack"):
            targets[p] = k
    for p, k in sorted(targets.items()):
        cells = stand_cells(s, p)
        reachable = [c for c in cells if c in seen]
        info[p] = (k, cells, reachable)
        if not cells:
            problems.append(f"{k} {p}: no free cell to stand next to it")
        elif not reachable:
            problems.append(f"{k} {p}: no cell next to it can be reached from the door")
    return problems, info


# ---------------------------------------------------------------- the blueprint file
# What a MineColonies blueprint looks like, read from MineColonies' own pack: the Enchanter's hut
# (top-level fields and the hut block entity), an archery hut (the blueprintDataProvider with a
# positioned tag) and a netherworker hut (a rack's block entity).
REF_HUT = "blueprints/minecolonies/medievaloak/mystic/enchanter1.blueprint"
REF_TAGS = "blueprints/minecolonies/caledonia/military/archery1.blueprint"
REF_RACK = "blueprints/minecolonies/medievaloak/mystic/netherworker2.blueprint"
# legacy fields of the hut block entity that no loader reads any more, which we leave out
HUT_LEFT_OUT = {"mirror", "main", "Item", "style"}
# modern fields the Enchanter's older blueprint predates; the archery blueprint and most others carry them
HUT_ADDED = {"pack", "path"}


@lru_cache(maxsize=None)
def _ref(name):
    with zipfile.ZipFile(paths.lib("minecolonies-*.jar")) as z:
        return nbtlib.File.parse(io.BytesIO(gzip.decompress(z.read(name))))


def _tag_type(t):
    if isinstance(t, T.List):
        return "List"
    return type(t).__name__


def _compare(mine, ref, where, left_out=(), added=()):
    """Same keys, same tag types, recursing into compounds."""
    problems = []
    for k in ref:
        if k not in mine and k not in left_out:
            problems.append(f"{where}: missing {k}")
    for k in mine:
        if k not in ref and k not in added:
            problems.append(f"{where}: {k} is not in MineColonies' blueprint")
        elif k in ref:
            a, b = _tag_type(mine[k]), _tag_type(ref[k])
            if a != b:
                problems.append(f"{where}.{k}: {a}, MineColonies stores {b}")
            elif a == "List" and len(mine[k]) and len(ref[k]) and _tag_type(mine[k][0]) != _tag_type(ref[k][0]):
                problems.append(f"{where}.{k}: list of {_tag_type(mine[k][0])}, MineColonies stores {_tag_type(ref[k][0])}")
            elif a == "Compound":
                problems += _compare(mine[k], ref[k], f"{where}.{k}")
    return problems


def _tile(f, pos):
    return next((te for te in f["tile_entities"] if (int(te["x"]), int(te["y"]), int(te["z"])) == pos), None)


def format_problems(path, anchor, racks):
    """The written file against MineColonies' own: top-level fields, hut block entity, the
    blueprintDataProvider with its positioned tags, rack block entities, palette entries."""
    try:
        return _format_problems(path, anchor, racks)
    except (KeyError, IndexError, ValueError, TypeError, StopIteration) as e:
        return [f"the file is malformed: {type(e).__name__} {e}"]


def _format_problems(path, anchor, racks):
    f = nbtlib.load(path)
    problems = _compare(f, _ref(REF_HUT), "blueprint", added={"architects"})
    for key in ("palette", "tile_entities", "entities", "required_mods", "architects"):
        if key in f and _tag_type(f[key]) != "List":
            problems.append(f"blueprint.{key} is not a list")
    for p in f["palette"]:
        if set(p) - {"Name", "Properties"} or any(_tag_type(v) != "String" for v in p.get("Properties", {}).values()):
            problems.append(f"palette entry {p['Name']}: not a Name and string Properties")
    hut = _tile(f, anchor)
    if hut is None:
        return problems + [f"no block entity at the anchor {anchor}"]
    ref_hut = next(te for te in _ref(REF_HUT)["tile_entities"] if "blueprintDataProvider" in te)
    problems += _compare({k: v for k, v in hut.items() if k != "blueprintDataProvider"},
                         {k: v for k, v in ref_hut.items() if k != "blueprintDataProvider"},
                         "hut block entity", left_out=HUT_LEFT_OUT, added=HUT_ADDED)
    ref_tags = next(te for te in _ref(REF_TAGS)["tile_entities"] if "blueprintDataProvider" in te)
    problems += _compare(hut["blueprintDataProvider"], ref_tags["blueprintDataProvider"], "blueprintDataProvider")
    pos_map, ref_map = hut["blueprintDataProvider"]["posTagMap"], ref_tags["blueprintDataProvider"]["posTagMap"]
    for entry in pos_map:
        problems += _compare(entry, ref_map[0], "posTagMap entry")
    rack_ref = next((te for te in _ref(REF_RACK)["tile_entities"] if str(te["id"]) == "minecolonies:rack"), None)
    for p in racks:
        te = _tile(f, p)
        if te is None:
            problems.append(f"no block entity for the rack at {p}")
        else:
            problems += _compare(te, rack_ref, f"rack block entity {p}")
    return problems
