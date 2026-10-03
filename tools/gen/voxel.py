"""Voxel structures for the Runesmith buildings.

A Structure is a sparse dict of positions -> block state strings, plus the positioned tags and the
hut block as anchor (x east, y up, z south). On top of that: shape helpers (boxes, lines, rings,
discs), block-state finishing as the game does on placement (stair corners, wall, fence and pane
connections, wall posts), terrain for previews, and quick functional checks (one hut block, racks, a
reachable work spot, doors with floor on both sides, light, no floating blocks). The gates a
blueprint must pass before it ships are in gates.py; the file format is in blueprint.py.
"""
import math
import re
from collections import Counter, deque

from mcassets import assets, parse_state

AIR = "minecraft:air"
HUT = "runesmith:blockhutrunesmith"
RACK = "minecolonies:blockminecoloniesrack"

_STATE = re.compile(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(.*)\])?$")
DIR = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0),
       "up": (0, 1, 0), "down": (0, -1, 0)}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "down", "down": "up"}
CCW = {"north": "west", "west": "south", "south": "east", "east": "north"}
CW = {v: k for k, v in CCW.items()}
HORIZ = ("north", "east", "south", "west")


def short(state):
    return parse_state(state)[0].split(":")[1] if state else ""


def with_props(state, **kw):
    name, props = parse_state(state)
    props.update({k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in kw.items()})
    return name + "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]" if props else name


def is_stairs(st):
    return st is not None and short(st).endswith("_stairs")


def is_wall(st):
    if st is None:
        return False
    n = short(st)
    return n.endswith("_wall") and not any(n.endswith(x) for x in ("_wall_torch", "_wall_sign", "_wall_banner",
                                                                    "_wall_head", "_wall_skull", "_wall_fan"))


def is_fence(st):
    return st is not None and short(st).endswith("_fence")


def is_gate(st):
    return st is not None and short(st).endswith("_fence_gate")


def is_pane(st):
    return st is not None and (short(st).endswith("glass_pane") or short(st) == "iron_bars")


def solid_cube(st):
    return st is not None and assets().bake(st).occluder


class Structure:
    def __init__(self, name):
        self.name = name
        self.blocks = {}
        self.tags = {}
        self.anchor = None
        self.notes = {}          # free-form facts for the README (level, footprint...)

    # ---------------------------------------------------------------- editing
    def set(self, x, y, z, block):
        if block is None or block == AIR:
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = block

    def get(self, x, y, z):
        return self.blocks.get((x, y, z))

    def clear(self, x0, y0, z0, x1, y1, z1):
        self.box(x0, y0, z0, x1, y1, z1, None)

    def box(self, x0, y0, z0, x1, y1, z1, block, hollow=False, only_empty=False):
        xa, xb = sorted((x0, x1)); ya, yb = sorted((y0, y1)); za, zb = sorted((z0, z1))
        for x in range(xa, xb + 1):
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    if hollow and xa < x < xb and ya < y < yb and za < z < zb:
                        continue
                    if only_empty and (x, y, z) in self.blocks:
                        continue
                    self.set(x, y, z, block)

    def walls(self, x0, z0, x1, z1, y0, y1, block):
        """The four walls of a rectangle (no floor or ceiling)."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, z0, block); self.set(x, y, z1, block)
            for z in range(z0, z1 + 1):
                self.set(x0, y, z, block); self.set(x1, y, z, block)

    def column(self, x, z, y0, y1, block):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            self.set(x, y, z, block)

    def tag(self, x, y, z, *names):
        self.tags.setdefault((x, y, z), []).extend(names)

    def set_anchor(self, x, y, z, block):
        self.anchor = (x, y, z)
        self.set(x, y, z, block)

    def copy(self, name=None):
        t = Structure(name or self.name)
        t.blocks = dict(self.blocks)
        t.tags = {p: list(n) for p, n in self.tags.items()}
        t.anchor = self.anchor
        t.notes = dict(self.notes)
        return t

    # ---------------------------------------------------------------- queries
    def bounds(self):
        xs = [p[0] for p in self.blocks]; ys = [p[1] for p in self.blocks]; zs = [p[2] for p in self.blocks]
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))

    def size(self):
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        return x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1

    def count(self):
        return Counter(parse_state(b)[0] for b in self.blocks.values())

    def find(self, name):
        return [p for p, b in self.blocks.items() if parse_state(b)[0] == name]

    # ---------------------------------------------------------------- finishing
    def finalize(self):
        """Set connection and shape properties the way the game would after placing every block."""
        b = self.blocks
        # stairs: corner shapes
        for p, st in list(b.items()):
            if is_stairs(st):
                b[p] = with_props(st, shape=self._stair_shape(p, st))
        # fences, panes, bars
        for p, st in list(b.items()):
            if is_fence(st) or is_pane(st):
                props = {}
                for d in HORIZ:
                    dx, dy, dz = DIR[d]
                    n = b.get((p[0] + dx, p[1] + dy, p[2] + dz))
                    if is_fence(st):
                        ok = (is_fence(n) and (short(n) == "nether_brick_fence") == (short(st) == "nether_brick_fence")) \
                            or is_gate(n) or solid_cube(n)
                    else:
                        ok = is_pane(n) or is_wall(n) or solid_cube(n)
                    props[d] = "true" if ok else "false"
                b[p] = with_props(st, **props)
        # walls: top down, so a wall knows whether the one above has a post
        walls = sorted((p for p, st in b.items() if is_wall(st)), key=lambda p: -p[1])
        for p in walls:
            st = b[p]
            above = b.get((p[0], p[1] + 1, p[2]))
            above_full = solid_cube(above)
            sides = {}
            for d in HORIZ:
                dx, dy, dz = DIR[d]
                n = b.get((p[0] + dx, p[1], p[2] + dz))
                con = is_wall(n) or is_pane(n) or is_gate(n) or solid_cube(n)
                if not con:
                    sides[d] = "none"
                    continue
                tall = above_full or (is_wall(above) and parse_state(above)[1].get(d, "none") != "none")
                sides[d] = "tall" if tall else "low"
            none = {d: sides[d] == "none" for d in HORIZ}
            up_above = is_wall(above) and parse_state(above)[1].get("up") == "true"
            isolated = all(none.values())
            uneven = none["north"] != none["south"] or none["east"] != none["west"]
            if up_above or isolated or uneven:
                up = True
            elif (sides["north"] == "tall" and sides["south"] == "tall") or (sides["east"] == "tall" and sides["west"] == "tall"):
                up = False
            else:
                an = short(above) if above else ""
                up = any(k in an for k in ("torch", "lantern", "candle", "banner", "sign", "chain", "end_rod",
                                           "amethyst_cluster", "_bud", "fence")) or above_full
            sides["up"] = "true" if up else "false"
            b[p] = with_props(st, **sides)
        return self

    def _stair_shape(self, p, st):
        _, pr = parse_state(st)
        f, half = pr.get("facing", "north"), pr.get("half", "bottom")

        def stair_at(d):
            dx, dy, dz = DIR[d]
            n = self.blocks.get((p[0] + dx, p[1] + dy, p[2] + dz))
            if is_stairs(n):
                return parse_state(n)[1]
            return None

        def can_take(d):
            o = stair_at(d)
            return o is None or o.get("facing") != f or o.get("half") != half

        front = stair_at(f)
        if front and front.get("half") == half:
            ff = front.get("facing")
            if ff in HORIZ and (ff in ("east", "west")) != (f in ("east", "west")) and can_take(OPP[ff]):
                return "outer_left" if ff == CCW[f] else "outer_right"
        back = stair_at(OPP[f])
        if back and back.get("half") == half:
            bf = back.get("facing")
            if bf in HORIZ and (bf in ("east", "west")) != (f in ("east", "west")) and can_take(bf):
                return "inner_left" if bf == CCW[f] else "inner_right"
        return "straight"


# ---------------------------------------------------------------------- terrain for previews and checks
def with_ground(s, margin=3, ground_y=1, top="minecraft:grass_block[snowy=false]", depth=3, path=None,
                keep_out=None):
    """A copy of the structure standing on a rectangular island of terrain (preview dressing, not part
    of the building): grass at ground_y wherever the building has nothing, dirt below.
    path: optional list of (x, z) cells paved with a dirt path instead of grass."""
    (x0, y0, z0), (x1, y1, z1) = s.bounds()
    t = s.copy()
    paths = set(path or [])
    for x in range(x0 - margin, x1 + margin + 1):
        for z in range(z0 - margin, z1 + margin + 1):
            for y in range(ground_y - depth, ground_y + 1):
                if (x, y, z) in s.blocks:
                    continue
                if y == ground_y:
                    blk = "minecraft:dirt_path" if (x, z) in paths else top
                    # nothing grows under the building's own blocks
                    if (x, y + 1, z) in s.blocks and blk == top and solid_cube(s.blocks[(x, y + 1, z)]):
                        blk = "minecraft:dirt"
                else:
                    blk = "minecraft:dirt" if y > ground_y - depth else "minecraft:coarse_dirt"
                t.blocks[(x, y, z)] = blk
    t.notes["island"] = (x0 - margin, z0 - margin, x1 + margin, z1 + margin, ground_y - depth)
    return t


# ---------------------------------------------------------------------- functional checks
LIGHT = {
    "lantern": 15, "soul_lantern": 10, "torch": 14, "wall_torch": 14, "soul_torch": 10, "soul_wall_torch": 10,
    "campfire": 15, "soul_campfire": 10, "glowstone": 15, "sea_lantern": 15, "shroomlight": 15,
    "crying_obsidian": 10, "magma_block": 3, "amethyst_cluster": 5, "large_amethyst_bud": 4,
    "medium_amethyst_bud": 2, "small_amethyst_bud": 1, "end_rod": 14, "jack_o_lantern": 15,
    "blast_furnace": 13, "furnace": 13, "smoker": 13, "enchanting_table": 0, "respawn_anchor": 15,
    "ochre_froglight": 15, "verdant_froglight": 15, "pearlescent_froglight": 15, "redstone_lamp": 15,
}


def light_level_of(st):
    name, props = parse_state(st)
    n = name.split(":")[1]
    if n.endswith("candle"):
        return 3 * int(props.get("candles", "1")) if props.get("lit") == "true" else 0
    if n in ("campfire", "soul_campfire") and props.get("lit") == "false":
        return 0
    if n in ("blast_furnace", "furnace", "smoker"):
        return 0                # without fuel a furnace goes out on its first tick
    if n == "redstone_lamp" and props.get("lit") != "true":
        return 0
    if n.endswith("copper_bulb"):
        if props.get("lit") != "true":
            return 0
        return {"copper_bulb": 15, "exposed_copper_bulb": 12, "weathered_copper_bulb": 8,
                "oxidized_copper_bulb": 4}.get(n.replace("waxed_", ""), 15)
    return LIGHT.get(n, 0)


def passable(st):
    """Can a colonist walk through this cell?"""
    if st is None:
        return True
    name, props = parse_state(st)
    n = name.split(":")[1]
    if n in ("air", "cave_air"):
        return True
    if n.endswith("trapdoor"):
        return props.get("open") == "true"
    if n.endswith("_door"):
        return True
    if n in ("lantern", "soul_lantern"):
        return props.get("hanging") == "true"
    if n == "chain":
        return True
    if n.endswith("carpet") or n.endswith("pressure_plate") or n.endswith("button") or n.endswith("torch"):
        return True
    if n.endswith("_sign") or n.endswith("_banner") or n in ("lever", "tripwire", "vine", "short_grass", "fern",
                                                                "tall_grass", "large_fern", "snow"):
        return True
    return False


def supports(st):
    """Can a colonist stand on top of this block?"""
    if st is None or passable(st):
        return False
    n = short(st)
    if is_fence(st) or is_wall(st) or is_gate(st):
        return False          # too high to step onto
    return True


def check(s, ground_y=1, floor_y=None, verbose=False):
    """Functional checks; returns (ok, report lines, facts)."""
    lines = []
    ok = True
    facts = {}
    huts = s.find(HUT)
    if len(huts) != 1:
        ok = False
        lines.append(f"FAIL hut blocks: {len(huts)} (need exactly 1)")
    else:
        lines.append(f"ok   hut block at {huts[0]}")
    racks = [p for p in s.find(RACK) if parse_state(s.blocks[p])[1].get("variant") != "blockrackair"]
    doubles = [p for p in racks if parse_state(s.blocks[p])[1].get("variant") in ("blockrackempty", "blockrackfull")]
    facts["racks"] = len(racks)
    facts["rack_slots"] = 27 * len(racks) + 27 * len(doubles)
    if len(racks) < 2:
        ok = False
        lines.append(f"FAIL racks: {len(racks)}")
    else:
        lines.append(f"ok   racks: {len(racks)} ({len(doubles)} double)")
    anvils = [p for p in s.blocks if short(s.blocks[p]) in ("anvil", "chipped_anvil", "damaged_anvil")]
    work = [p for p, names in s.tags.items() if "work" in names]
    if len(work) != 1:
        ok = False
        lines.append(f"FAIL work tags: {len(work)}")
    # terrain so the outside counts as walkable ground
    t = with_ground(s, margin=4, ground_y=ground_y)
    B = t.blocks

    def standable(x, y, z):
        return supports(B.get((x, y - 1, z))) and passable(B.get((x, y, z))) and passable(B.get((x, y + 1, z)))

    (x0, y0, z0), (x1, y1, z1) = t.bounds()
    start = [(x0 + 1, ground_y + 1, z0 + 1)]
    seen = set(start)
    dq = deque(start)
    while dq:
        x, y, z = dq.popleft()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for dy in (0, 1, -1):
                nx, ny, nz = x + dx, y + dy, z + dz
                if not (x0 <= nx <= x1 and z0 <= nz <= z1 and y0 <= ny <= y1 + 2):
                    continue
                if (nx, ny, nz) in seen or not standable(nx, ny, nz):
                    continue
                if dy == 1 and not passable(B.get((x, y + 2, z))):
                    continue
                if dy == -1 and not passable(B.get((nx, ny + 2, nz))):
                    continue
                # a door can only be passed along its facing axis
                d_here = short(B.get((nx, ny, nz)) or "")
                if d_here.endswith("_door"):
                    f = parse_state(B[(nx, ny, nz)])[1].get("facing")
                    if (f in ("north", "south")) != (dz != 0):
                        continue
                seen.add((nx, ny, nz))
                dq.append((nx, ny, nz))
                break
    facts["reachable"] = len(seen)
    for a in anvils:
        spots = [(a[0] + dx, a[1], a[2] + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        good = [p for p in spots if p in seen]
        if not good:
            ok = False
            lines.append(f"FAIL anvil {a}: no reachable standing spot next to it")
        else:
            lines.append(f"ok   anvil {a}: reachable standing spot(s) {good}")
    if not anvils:
        ok = False
        lines.append("FAIL no anvil")
    if huts:
        h = huts[0]
        near = [(h[0] + dx, h[1], h[2] + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if not any(p in seen for p in near):
            ok = False
            lines.append("FAIL hut block not reachable")
    # doors: floor on both sides
    for p, st in s.blocks.items():
        n, pr = parse_state(st)
        if n.endswith("_door") and pr.get("half") == "lower":
            f = pr.get("facing")
            dx, _, dz = DIR[f]
            sides = [(p[0] + dx, p[1], p[2] + dz), (p[0] - dx, p[1], p[2] - dz)]
            bad = [q for q in sides if not standable(*q)]
            if bad or not supports(B.get((p[0], p[1] - 1, p[2]))):
                ok = False
                lines.append(f"FAIL door {p}: no floor/clearance at {bad}")
    # light: flood fill through everything that is not an opaque cube
    lvl = {}
    dq = deque()
    for p, st in B.items():
        L = light_level_of(st)
        if L > 0:
            lvl[p] = L
            dq.append(p)
    while dq:
        p = dq.popleft()
        L = lvl[p]
        if L <= 1:
            continue
        for d in DIR.values():
            q = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
            if not (x0 - 1 <= q[0] <= x1 + 1 and y0 - 1 <= q[1] <= y1 + 3 and z0 - 1 <= q[2] <= z1 + 1):
                continue
            if solid_cube(B.get(q)):
                continue
            if lvl.get(q, 0) < L - 1:
                lvl[q] = L - 1
                dq.append(q)
    def enclosed(p):
        # covered from above, and a wall within reach in all four directions: inside, not under an eave
        if not any(solid_cube(s.blocks.get((p[0], y, p[2]))) for y in range(p[1] + 2, p[1] + 14)):
            return False
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if not any(s.blocks.get((p[0] + dx * k, p[1] + dy, p[2] + dz * k)) is not None
                       and not passable(s.blocks.get((p[0] + dx * k, p[1] + dy, p[2] + dz * k)))
                       for k in range(1, 13) for dy in (0, 1)):
                return False
        return True

    inside = [p for p in seen if enclosed(p)]
    if inside:
        vals = [lvl.get(p, 0) for p in inside]
        facts["light_min"] = min(vals)
        facts["light_mean"] = sum(vals) / len(vals)
        tag = "ok  " if min(vals) >= 8 else "WARN"
        lines.append(f"{tag} interior light: min {min(vals)}, mean {sum(vals) / len(vals):.1f} over {len(vals)} cells")
    # floating blocks: everything must connect to the ground through faces
    grounded = set()
    dq = deque(p for p in s.blocks if p[1] <= ground_y)
    grounded.update(dq)
    while dq:
        p = dq.popleft()
        for d in DIR.values():
            q = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
            if q in s.blocks and q not in grounded:
                grounded.add(q)
                dq.append(q)
    floating = [p for p in s.blocks if p not in grounded]
    if floating:
        ok = False
        lines.append(f"FAIL floating blocks: {len(floating)} e.g. {floating[:5]}")
    else:
        lines.append("ok   no floating blocks")
    (bx0, by0, bz0), (bx1, by1, bz1) = s.bounds()
    facts["footprint"] = (bx1 - bx0 + 1, bz1 - bz0 + 1)
    facts["height"] = by1 - ground_y      # blocks above the ground surface
    facts["total_height"] = by1 - by0 + 1
    facts["blocks"] = len(s.blocks)
    lines.append(f"     footprint {facts['footprint'][0]} x {facts['footprint'][1]}, height above ground {facts['height']}"
                 f" (blueprint {facts['total_height']} incl. foundation), {len(s.blocks)} blocks")
    return ok, lines, facts
