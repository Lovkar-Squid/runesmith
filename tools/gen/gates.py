"""Gates for the Runesmith buildings: every level of every look, and the rules across a look's levels.
A design that fails any of these cannot ship (build_pack.py writes nothing for it).

Semantics: a citizen stands only on a full block, walks through air and doors (not
lanterns, chains or carpets), climbs one block or drops up to three; a room is "sealed" when no air,
lantern or chain cell connects it to the outside (doors, panes, bars and every other block close it);
full blocks, stairs and slabs stop light, everything else lets it through; a hanging lantern needs a
full block or a chain above it, a standing one a full block below it.

    python gates.py            every level of every look, plus the cross-level rules
    python gates.py B 4        one level, with the details
"""
import importlib
import json
import sys
from collections import defaultdict, deque

from mcassets import assets, parse_state
from voxel import HUT, RACK

GROUND = 1                      # the grass layer; people walk at y=2 outside
NEIGH4 = [(1, 0), (-1, 0), (0, 1), (0, -1)]
NEIGH6 = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]
FACING = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
FACE6 = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
NONVISUAL = {"waterlogged": {"true", "false"}, "powered": {"true", "false"}}
MOD_BLOCKS = {HUT: {"facing": {"north", "south", "east", "west"}}}
FULL_SOLID = {"chiseled_bookshelf"}      # full blocks whose model is drawn in parts
FORBIDDEN = ("_sign", "_banner", "item_frame", "armor_stand", "skull", "_head", "chest", "shulker_box", "decorated_pot")
EMISSION = {
    "minecraft:lantern": 15, "minecraft:soul_lantern": 10, "minecraft:crying_obsidian": 10,
    "minecraft:magma_block": 3, "minecraft:amethyst_cluster": 5, "minecraft:large_amethyst_bud": 4,
    "minecraft:medium_amethyst_bud": 2, "minecraft:small_amethyst_bud": 1, "minecraft:glowstone": 15,
    "minecraft:sea_lantern": 15, "minecraft:shroomlight": 15,
}


def emission(state):
    name, props = parse_state(state)
    if name in EMISSION:
        return EMISSION[name]
    n = name.split(":")[1]
    if n in ("campfire", "soul_campfire"):
        return (15 if n == "campfire" else 10) if props.get("lit", "true") == "true" else 0
    if n.endswith("candle"):
        return 3 * int(props.get("candles", "1")) if props.get("lit") == "true" else 0
    return 0                    # furnaces do not count: without fuel they go out on their first tick


# ---------------------------------------------------------------- block classes
_KIND = {}


def kind(state):
    """hut, rack, anvil, lantern, chain, door, stairs, slab, pane, solid (a full opaque cube) or ?."""
    if state is None:
        return None
    if state in _KIND:
        return _KIND[state]
    name, props = parse_state(state)
    n = name.split(":")[1]
    if name == HUT:
        k = "hut"
    elif name == RACK:
        k = "rack"
    elif n in ("anvil", "chipped_anvil", "damaged_anvil"):
        k = "anvil"
    elif n in ("lantern", "soul_lantern"):
        k = "lantern"
    elif n == "chain":
        k = "chain"
    elif n.endswith("_door"):
        k = "door"
    elif n.endswith("_stairs"):
        k = "stairs"
    elif n.endswith("_slab"):
        k = "solid" if props.get("type") == "double" else "slab"
    elif n.endswith("glass_pane") or n == "iron_bars":
        k = "pane"
    elif n in FULL_SOLID or assets().bake(state).occluder:
        k = "solid"
    else:
        k = "?"
    _KIND[state] = k
    return k


class Gates:
    def __init__(self, s):
        self.s = s
        self.B = s.blocks
        (x0, y0, z0), (x1, y1, z1) = s.bounds()
        self.lo, self.hi = (x0 - 1, y0 - 1, z0 - 1), (x1 + 1, y1 + 1, z1 + 1)

    def k(self, x, y, z):
        return kind(self.B.get((x, y, z)))

    def passable(self, x, y, z):
        b = self.B.get((x, y, z))
        return b is None or kind(b) == "door"

    def standable(self, x, y, z):
        return self.k(x, y - 1, z) == "solid" and self.passable(x, y, z) and self.passable(x, y + 1, z)

    def inbox(self, p):
        return all(self.lo[i] <= p[i] <= self.hi[i] for i in range(3))

    def reach(self, start):
        seen = {start}
        q = deque([start])
        while q:
            x, y, z = q.popleft()
            for dx, dz in NEIGH4:
                nx, nz = x + dx, z + dz
                for ny in (y + 1, y, y - 1, y - 2, y - 3):
                    if (nx, ny, nz) in seen or not self.standable(nx, ny, nz):
                        continue
                    if ny == y + 1 and not self.passable(x, y + 2, z):
                        continue
                    if not self.inbox((nx, ny, nz)):
                        continue
                    seen.add((nx, ny, nz))
                    q.append((nx, ny, nz))
                    break
        return seen

    def stand_cells(self, pos):
        x, y, z = pos
        return [(x + dx, y, z + dz) for dx, dz in NEIGH4 if self.standable(x + dx, y, z + dz)]

    def enclosed(self):
        def is_open(p):
            return self.k(*p) in (None, "lantern", "chain")
        lo, hi = self.lo, self.hi
        seen = set()
        for x in range(lo[0], hi[0] + 1):
            for y in range(lo[1], hi[1] + 1):
                for z in range(lo[2], hi[2] + 1):
                    if x in (lo[0], hi[0]) or y in (lo[1], hi[1]) or z in (lo[2], hi[2]):
                        seen.add((x, y, z))
        q = deque(seen)
        while q:
            x, y, z = q.popleft()
            for dx, dy, dz in NEIGH6:
                n = (x + dx, y + dy, z + dz)
                if n not in seen and self.inbox(n) and is_open(n):
                    seen.add(n)
                    q.append(n)
        return {(x, y, z) for x in range(lo[0] + 1, hi[0]) for y in range(lo[1] + 1, hi[1]) for z in range(lo[2] + 1, hi[2])
                if (x, y, z) not in seen and is_open((x, y, z))}

    def light(self):
        level = {}
        buckets = [[] for _ in range(16)]
        for pos, st in self.B.items():
            e = emission(st)
            if e:
                level[pos] = e
                buckets[e].append(pos)
        for lv in range(15, 1, -1):
            for pos in buckets[lv]:
                if level.get(pos) != lv:
                    continue
                for dx, dy, dz in NEIGH6:
                    n = (pos[0] + dx, pos[1] + dy, pos[2] + dz)
                    if not self.inbox(n) or self.k(*n) in ("solid", "stairs", "slab"):
                        continue
                    if level.get(n, 0) < lv - 1:
                        level[n] = lv - 1
                        buckets[lv - 1].append(n)
        return level


# ---------------------------------------------------------------- states, as the jars define them
_SPEC = {}


def spec(name):
    if name in _SPEC:
        return _SPEC[name]
    if name in MOD_BLOCKS:
        _SPEC[name] = (MOD_BLOCKS[name], None)
        return _SPEC[name]
    ns, path = name.split(":")
    try:
        d = assets().json(ns, f"blockstates/{path}.json")
    except KeyError:
        _SPEC[name] = None
        return None
    visual, combos = defaultdict(set), None
    if "variants" in d:
        combos = []
        for key in d["variants"]:
            kv = dict(item.split("=", 1) for item in key.split(",")) if key else {}
            combos.append(kv)
            for kk, v in kv.items():
                visual[kk].add(v)
    else:
        def collect(cond):
            for sub in cond.get("OR", []) + cond.get("AND", []):
                collect(sub)
            for kk, v in cond.items():
                if kk not in ("OR", "AND"):
                    visual[kk].update(str(v).split("|"))
        for part in d.get("multipart", []):
            if "when" in part:
                collect(part["when"])
        for values in visual.values():
            values.update({"false", "none"})
    _SPEC[name] = (dict(visual), combos)
    return _SPEC[name]


def state_problems(state):
    name, props = parse_state(state)
    sp = spec(name)
    if sp is None:
        return [f"unknown block id {name}"]
    visual, combos = sp
    out = []
    for kk, v in props.items():
        if kk in visual:
            if v not in visual[kk]:
                out.append(f"{kk}={v} is not one of {sorted(visual[kk])}")
        elif kk in NONVISUAL:
            if v not in NONVISUAL[kk]:
                out.append(f"{kk}={v} is not true/false")
        else:
            out.append(f"unknown property {kk}")
    if combos is not None:
        missing = [kk for kk in visual if kk not in props]
        if missing:
            out.append("missing " + ", ".join(missing))
        elif not any(all(props[kk] == c.get(kk) for kk in visual) for c in combos):
            out.append("no such combination")
    return out


# ---------------------------------------------------------------- the gates
def run(s):
    """Returns (problems, facts)."""
    g = Gates(s)
    P = []
    facts = {}
    B = s.blocks

    # ids and states
    for st in sorted(set(B.values())):
        for e in state_problems(st):
            P.append(f"state {st}: {e}")
        name, props = parse_state(st)
        if any(f in name for f in FORBIDDEN):
            P.append(f"forbidden block {name}")
        if name == "minecraft:lectern" and props.get("has_book") == "true":
            P.append("lectern with a book")
        if name.startswith("structurize:"):
            P.append(f"structurize block {name} (allowed only as foundation)")

    # hut, anvil, tags
    huts = [p for p, b in B.items() if parse_state(b)[0] == HUT]
    if len(huts) != 1:
        P.append(f"{len(huts)} hut blocks")
    elif s.anchor != huts[0]:
        P.append("anchor is not the hut block")
    anvils = [p for p, b in B.items() if kind(b) == "anvil"]
    if len(anvils) != 1 or parse_state(B[anvils[0]])[0] != "minecraft:anvil":
        P.append(f"anvils: {[(p, parse_state(B[p])[0]) for p in anvils]} (need exactly one plain anvil)")
    tags = {p: list(n) for p, n in s.tags.items()}
    if len(anvils) == 1 and tags != {anvils[0]: ["work"]}:
        P.append(f"tags must be exactly 'work' on the anvil, are {tags}")

    # racks
    singles, doubles, rack_problems = [], [], []
    for p, b in B.items():
        name, props = parse_state(b)
        if name != RACK:
            continue
        var, f = props.get("variant"), props.get("facing")
        if var == "blockrackemptysingle":
            singles.append(p)
        elif var == "blockrackempty":
            dx, dz = FACING[f]
            q = (p[0] + dx, p[1], p[2] + dz)
            pb = B.get(q)
            if not pb or parse_state(pb)[0] != RACK or parse_state(pb)[1] != {"facing": OPP[f], "variant": "blockrackair"}:
                rack_problems.append(f"double rack {p}: no blockrackair partner facing back at {q}")
            doubles.append((p, q))
        elif var == "blockrackair":
            f = props.get("facing")
            dx, dz = FACING[f]
            m = B.get((p[0] + dx, p[1], p[2] + dz))
            if not m or parse_state(m)[1].get("variant") != "blockrackempty":
                rack_problems.append(f"rack partner {p} without its main half")
        else:
            rack_problems.append(f"rack {p}: variant {var}")
    for p in singles + [m for m, _ in doubles]:
        cells = [p] + [q for m, q in doubles if m == p]
        if not any(g.k(c[0] + dx, c[1], c[2] + dz) == "solid" for c in cells for dx, dz in NEIGH4):
            rack_problems.append(f"rack {p} does not stand against a wall")
    P += rack_problems
    facts["singles"] = sorted(singles)
    facts["doubles"] = sorted(doubles)
    facts["racks"] = len(singles) + len(doubles)

    # floating, unsupported
    ymin = min(p[1] for p in B)
    seen = {p for p in B if p[1] == ymin}
    q = deque(seen)
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in NEIGH6:
            n = (x + dx, y + dy, z + dz)
            if n in B and n not in seen:
                seen.add(n)
                q.append(n)
    fl = [p for p in B if p not in seen]
    if fl:
        P.append(f"floating blocks: {sorted(fl)[:6]}{' ...' if len(fl) > 6 else ''}")
    for (x, y, z), st in B.items():
        name, props = parse_state(st)
        kk = kind(st)
        below, above = g.k(x, y - 1, z), g.k(x, y + 1, z)
        why = None
        if kk == "door":
            if props.get("half") == "lower":
                up = B.get((x, y + 1, z))
                if below != "solid":
                    why = "no full block under the door"
                elif not up or parse_state(up)[0] != name or parse_state(up)[1].get("half") != "upper":
                    why = "no upper half"
        elif kk == "lantern":
            if props.get("hanging") == "true":
                if above not in ("solid", "chain"):
                    why = f"hangs from {above}"
            elif below != "solid":
                why = f"stands on {below}"
        elif kk == "chain":
            if above not in ("solid", "chain"):
                why = f"chain hangs from {above}"
        elif kk == "anvil" and below != "solid":
            why = "anvil would fall"
        elif "amethyst_cluster" in name or name.endswith("_amethyst_bud"):
            dx, dy, dz = FACE6[props["facing"]]
            if g.k(x - dx, y - dy, z - dz) != "solid":
                why = "crystal is not on a full block face"
        elif name.split(":")[1].endswith("candle"):
            if below != "solid":
                why = "candle not on a full block"
        if why:
            P.append(f"unsupported {(x, y, z)} {name.split(':')[1]}: {why}")

    # doors and rooms
    inside = g.enclosed()
    doors = []
    for (x, y, z), st in sorted(B.items()):
        name, props = parse_state(st)
        if kind(st) == "door" and props.get("half") == "lower":
            dx, dz = FACING[props["facing"]]
            doors.append(((x, y, z), [(x + dx, y, z + dz), (x - dx, y, z - dz)]))
    if len(doors) != 1:
        P.append(f"{len(doors)} doors (exactly one expected)")
    seen_walk = set()
    for pos, sides in doors:
        for (x, y, z) in sides:
            if g.k(x, y - 1, z) != "solid":
                P.append(f"door {pos}: no full block under {(x, y, z)}")
            if not (g.passable(x, y, z) and g.passable(x, y + 1, z)):
                P.append(f"door {pos}: {(x, y, z)} blocked at head height")
        ins = [c for c in sides if c in inside]
        if len(ins) != 1:
            P.append(f"door {pos}: {len(ins)} sides in a sealed room (need exactly one)")
        else:
            out = [c for c in sides if c not in inside][0]
            if not g.standable(*out):
                P.append(f"door {pos}: cannot stand in front of it at {out}")
            seen_walk = g.reach(out)
            if ins[0] not in seen_walk:
                P.append(f"door {pos}: the cell inside {ins[0]} cannot be reached")
    targets = [(p, kind(B[p])) for p in B if kind(B[p]) in ("hut", "anvil")]
    targets += [(p, "rack") for p in singles] + [(m, "rack") for m, _ in doubles]
    work_cells = set()
    for p, kk in targets:
        cells = g.stand_cells(p)
        if kk == "rack":
            for m, part in doubles:
                if m == p:
                    cells += g.stand_cells(part)
        ok = [c for c in cells if c in seen_walk]
        if not ok:
            P.append(f"{kk} {p}: no reachable cell to stand next to it")
        work_cells.update(ok)
    facts["work_cells"] = sorted(work_cells)

    # light
    L = g.light()
    dark_work = [(c, L.get(c, 0)) for c in sorted(work_cells) if L.get(c, 0) < 11]
    if dark_work:
        P.append(f"worker cells below light 11: {dark_work[:8]}{' ...' if len(dark_work) > 8 else ''}")
    walk_inside = [c for c in seen_walk if c in inside]
    dark_floor = sorted((c, L.get(c, 0)) for c in walk_inside if L.get(c, 0) < 8)
    if dark_floor:
        P.append(f"walkable cells inside below light 8: {dark_floor[:8]}{' ...' if len(dark_floor) > 8 else ''}")
    zero = sorted(c for c in inside if L.get(c, 0) == 0)
    if zero:
        P.append(f"sealed cells at light 0: {len(zero)} e.g. {zero[:6]}")
    facts["light_work_min"] = min((L.get(c, 0) for c in work_cells), default=None)
    facts["light_inside_min"] = min((L.get(c, 0) for c in walk_inside), default=None)
    facts["inside_cells"] = len(walk_inside)

    # size
    (x0, y0, z0), (x1, y1, z1) = s.bounds()
    facts["bounds"] = ((x0, y0, z0), (x1, y1, z1))
    facts["size"] = (x1 - x0 + 1, z1 - z0 + 1, y1 - GROUND)
    if y1 - GROUND > 24:
        P.append(f"{y1 - GROUND} blocks above the ground")
    facts["hut"] = huts[0] if huts else None
    facts["hut_state"] = B[huts[0]] if huts else None
    facts["anvil"] = anvils[0] if anvils else None
    return P, facts


def check_look(mod):
    """All levels of one look plus the cross-level rules. Returns {level: (problems, facts)}, cross problems."""
    res = {}
    for lvl in sorted(mod.LEVELS):
        res[lvl] = run(mod.LEVELS[lvl]())
    cross = []
    if sorted(res) != [1, 2, 3, 4, 5]:
        cross.append(f"levels {sorted(res)}")
    huts = {lvl: (f["hut"], f["hut_state"]) for lvl, (_, f) in res.items()}
    if len(set(huts.values())) != 1:
        cross.append(f"hut block moves or turns between levels: {huts}")
    racks = [res[l][1]["racks"] for l in sorted(res)]
    if any(b < a for a, b in zip(racks, racks[1:])):
        cross.append(f"fewer racks at a higher level: {racks}")
    if res.get(1) and res[1][1]["racks"] < 2:
        cross.append("fewer than 2 racks at level 1")
    if 5 in res:
        (bx0, _, bz0), (bx1, _, bz1) = res[5][1]["bounds"]
        for lvl, (_, f) in res.items():
            (x0, _, z0), (x1, _, z1) = f["bounds"]
            if x0 < bx0 or z0 < bz0 or x1 > bx1 or z1 > bz1:
                cross.append(f"level {lvl} reaches beyond level 5's x/z extent")
    return res, cross


def rel(p, hut):
    return (p[0] - hut[0], p[1] - hut[1], p[2] - hut[2])


if __name__ == "__main__":
    looks = [a.upper() for a in sys.argv[1:2]] or ["A", "B", "C"]
    only = int(sys.argv[2]) if len(sys.argv) > 2 else None
    bad = 0
    for v in looks:
        mod = importlib.import_module(f"designs_{v.lower()}")
        if only:
            P, f = run(mod.LEVELS[only]())
            print(f"{v}{only}: {'PASS' if not P else 'FAIL'}  size {f['size']}  racks {f['racks']}  "
                  f"light work>={f['light_work_min']} inside>={f['light_inside_min']}")
            for p in P:
                print("   ", p)
            bad += bool(P)
            continue
        res, cross = check_look(mod)
        for lvl, (P, f) in sorted(res.items()):
            print(f"{v}{lvl}: {'PASS' if not P else 'FAIL'}  size {f['size']}  racks {f['racks']} "
                  f"({len(f['singles'])} single, {len(f['doubles'])} double)  light work>={f['light_work_min']} "
                  f"inside>={f['light_inside_min']}")
            for p in P:
                print("   ", p)
            bad += bool(P)
        for c in cross:
            print(f"{v} cross-level: {c}")
        bad += len(cross)
    sys.exit(1 if bad else 0)
