"""The written blueprint file against MineColonies' own blueprints.

Structurize reads a blueprint leniently: a field of the wrong type or a block entity in the wrong
shape is not an error when the file is written, only a hut that misbehaves in the game. So the file
is compared, key for key and tag type for tag type, with MineColonies' own blueprints from the jar
in libs/. The building's own rules (blocks, reachability, light, levels) are in gates.py.
"""
import gzip
import io
import zipfile
from functools import lru_cache

import nbtlib
from nbtlib import tag as T

import paths


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
