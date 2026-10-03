"""Building helpers shared by the three variants: roofs from height fields, windows, lights."""
import math

from blocks import chain, lantern, stairs, slab, trapdoor, pane
from voxel import HORIZ, DIR

NEG = -10 ** 6


# ------------------------------------------------------------------ roofs
def gable_h(x0, x1, z0, z1, base, ridge_axis="z"):
    """Heights of a 45-degree gable roof over the rectangle (overhangs included).
    ridge_axis 'z': the ridge runs north-south and the slopes face east and west."""
    H = {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            d = min(x - x0, x1 - x) if ridge_axis == "z" else min(z - z0, z1 - z)
            H[(x, z)] = base + d
    return H


def hip_h(x0, x1, z0, z1, base, cap=None):
    """Hipped roof (pyramid when square). cap limits the height (a flat top / ridge)."""
    H = {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            d = min(x - x0, x1 - x, z - z0, z1 - z)
            H[(x, z)] = base + (d if cap is None else min(d, cap))
    return H


def cone_h(cx, cz, radius, base, slope=1.0, top=None):
    """Round roof: height grows by `slope` per block towards the centre (cx, cz may be .5)."""
    H = {}
    r = int(math.ceil(radius)) + 1
    for x in range(int(math.floor(cx - r)), int(math.ceil(cx + r)) + 1):
        for z in range(int(math.floor(cz - r)), int(math.ceil(cz + r)) + 1):
            d = math.hypot(x - cx, z - cz)
            if d <= radius + 0.01:
                h = base + int(round((radius - d) * slope))
                if top is not None:
                    h = min(h, top)
                H[(x, z)] = h
    return H


def merge_h(*fields):
    out = {}
    for f in fields:
        for k, v in f.items():
            out[k] = max(out.get(k, NEG), v)
    return out


def roof(s, H, mat, peak=None, skip=None, fill_below=0, fill=None, prefer=("north", "south", "east", "west")):
    """Lay stairs of `mat` over a height field: each cell gets a stair facing its higher neighbour
    (outer and inner corners come from Structure.finalize); cells with no higher neighbour get `peak`
    (default: a full block of fill or the stair itself facing nowhere -> a bottom slab).
    fill_below: blocks of `fill` under every cell (roof thickness)."""
    skip = skip or set()
    for (x, z), h in H.items():
        if (x, z) in skip:
            continue
        up = [d for d in prefer if H.get((x + DIR[d][0], z + DIR[d][2]), NEG) > h]
        if not up:
            diag = [(dx, dz) for dx in (-1, 1) for dz in (-1, 1) if H.get((x + dx, z + dz), NEG) > h]
            if diag:
                dx, dz = diag[0]
                up = ["south" if dz > 0 else "north"]
        if up:
            # several uphill directions (a valley or a ridge end): prefer the steepest
            up.sort(key=lambda d: -H.get((x + DIR[d][0], z + DIR[d][2]), NEG))
            s.set(x, h, z, stairs(mat, up[0]))
        else:
            s.set(x, h, z, peak or slab(mat, "bottom"))
        for k in range(1, fill_below + 1):
            if fill:
                s.set(x, h - k, z, fill)


# ------------------------------------------------------------------ openings and details
def hang(s, x, y_top, z, length, soul=False):
    """A lantern hanging under the block at y_top on `length` chain links."""
    for k in range(1, length + 1):
        s.set(x, y_top - k, z, chain())
    s.set(x, y_top - length - 1, z, lantern(hanging=True, soul=soul))


def shutters(s, x, y, z, facing, wood, height=2, width=1):
    """Open trapdoors flat against the wall either side of a window opening (x, y, z is the lowest
    window cell at the left end; the wall's outside is `facing`; the window is `width` wide).
    An open trapdoor's panel lies on the side opposite its facing, so facing outwards puts it on the wall."""
    dx, _, dz = DIR[facing]
    ax = (1, 0) if facing in ("north", "south") else (0, 1)     # along the wall
    for k in range(height):
        for off in (-1, width):
            s.set(x + dx + ax[0] * off, y + k, z + dz + ax[1] * off, trapdoor(wood, facing, "bottom", True))


# ------------------------------------------------------------------ round plans
def disc(cx, cz, r):
    """Cells of a filled circle; cx, cz may be .5 for an even diameter."""
    out = set()
    R = int(math.ceil(r)) + 1
    for x in range(int(math.floor(cx - R)), int(math.ceil(cx + R)) + 1):
        for z in range(int(math.floor(cz - R)), int(math.ceil(cz + R)) + 1):
            if (x - cx) ** 2 + (z - cz) ** 2 <= r * r + 0.01:
                out.add((x, z))
    return out


def outline(cells):
    """The cells of a set that touch the outside (4-neighbourhood)."""
    return {(x, z) for (x, z) in cells
            if any((x + dx, z + dz) not in cells for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))}


def outward(cell, cells):
    """Directions (names) from a boundary cell to the outside."""
    x, z = cell
    out = []
    for name, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("east", (1, 0)), ("west", (-1, 0))):
        if (x + dx, z + dz) not in cells:
            out.append(name)
    return out


def profile_h(cells, cx, cz, radius, base, height, power=1.0):
    """Round roof over `cells`: height falls from base+height at the centre to base at `radius`,
    following (1 - d/radius) ** power (power > 1 gives a concave 'witch hat', < 1 a dome)."""
    H = {}
    for (x, z) in cells:
        d = math.hypot(x - cx, z - cz)
        t = max(0.0, 1.0 - d / radius)
        H[(x, z)] = base + int(round(height * (t ** power)))
    return H


def fill_under_roof(s, H, block, depth=None):
    """Close the gaps under a steep roof: wherever a cell's neighbour is more than one block lower,
    fill under the stair down to the neighbour's height (or `depth` blocks), so every ring of the
    roof touches the one below it face to face."""
    for (x, z), h in H.items():
        low = min(H.get((x + dx, z + dz), h - 1) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        n = h - low if h - low > 1 else 0
        if depth is not None:
            n = min(n, depth)
        for k in range(1, n + 1):
            s.set(x, h - k, z, block)


def dome_shell(cx, cz, y0, r, vscale=1.0, thickness=1.0):
    """Cells (x, y, z) of a dome shell centred on (cx, y0, cz): the outer `thickness` of a half-ellipsoid
    with horizontal radius r and vertical radius r * vscale, above y0."""
    out = set()
    R = int(math.ceil(r)) + 1
    top = int(math.ceil(r * vscale)) + 1
    for x in range(int(math.floor(cx - R)), int(math.ceil(cx + R)) + 1):
        for z in range(int(math.floor(cz - R)), int(math.ceil(cz + R)) + 1):
            for y in range(y0, y0 + top + 1):
                d = math.sqrt((x - cx) ** 2 + (z - cz) ** 2 + ((y - y0) / vscale) ** 2)
                if r - thickness < d <= r + 0.01:
                    out.add((x, y, z))
    return out


def peel(cells):
    """Split a plan into concentric one-block rings, outermost first."""
    rings = []
    left = set(cells)
    while left:
        r = outline(left)
        rings.append(r)
        left -= r
    return rings


def ring_roof(s, cells, heights, mat, full, cx, cz, fill=True, cap=None):
    """A round roof from concentric rings: ring k (outermost = 0) gets stairs at heights[k] facing the
    centre (cx, cz); with fill, full blocks close the roof down to the next ring out. Rings beyond the
    list of heights get `cap` (default: a full block at the last height)."""
    rings = peel(cells)
    H = {}
    for k, ring in enumerate(rings):
        h = heights[min(k, len(heights) - 1)]
        for (x, z) in ring:
            H[(x, z)] = h
    for k, ring in enumerate(rings):
        h = H[next(iter(ring))]
        inner = k + 1 < len(rings)
        for (x, z) in ring:
            if not inner or k >= len(heights) - 1:
                s.set(x, h, z, cap or full)
                if fill and k > 0:
                    prev = heights[min(k - 1, len(heights) - 1)]
                    for y in range(prev, h):
                        s.set(x, y, z, full)
                continue
            dx, dz = cx - x, cz - z
            f = ("east" if dx > 0 else "west") if abs(dx) > abs(dz) else ("south" if dz > 0 else "north")
            if abs(dx) == abs(dz):
                # a diagonal cell: face the neighbour that is higher, if any
                for cand in (("east" if dx > 0 else "west"), ("south" if dz > 0 else "north")):
                    ddx, ddz = DIR[cand][0], DIR[cand][2]
                    if H.get((x + ddx, z + ddz), -999) > h:
                        f = cand
                        break
            s.set(x, h, z, stairs(mat, f))
            if fill and k > 0:
                prev = heights[min(k - 1, len(heights) - 1)]
                for y in range(prev, h):
                    s.set(x, y, z, full)
    return H


# ------------------------------------------------------------------ amethyst crystals
AMETHYST = "minecraft:amethyst_block"
BUDDING = "minecraft:budding_amethyst"


def _layer_cells(w):
    if w >= 3:
        return [(dx, dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1) if not (w == 3 and abs(dx) + abs(dz) == 2 and False)]
    if w == 2:
        return [(0, 0), (1, 0), (0, 1), (1, 1)]
    return [(0, 0)]


def shard(s, x, y, z, widths, lean=(0, 0), every=2, tip="cluster", buds=()):
    """One crystal shard: square layers of amethyst (widths from the bottom, 3/2/1), shifted by `lean`
    every `every` layers so the shard tilts; a cluster on the tip; `buds` = [(layer, dx, dz, size)]
    adds small crystals growing out of its sides. Returns the tip position."""
    ox = oz = 0
    cells_top = [(0, 0)]
    for i, w in enumerate(widths):
        if i and i % every == 0:
            ox += lean[0]
            oz += lean[1]
        cells_top = _layer_cells(w)
        for (dx, dz) in cells_top:
            s.set(x + ox + dx, y + i, z + oz + dz, AMETHYST)
    ty = y + len(widths)
    tx, tz = x + ox, z + oz
    if tip:
        from blocks import crystal as _crystal
        s.set(tx, ty, tz, _crystal(tip if tip in ("cluster", "large", "medium", "small") else "cluster", "up"))
    from blocks import crystal as _crystal
    for (layer, dx, dz, size) in buds:
        lx = ox if layer >= every else 0
        f = {(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}[(int(math.copysign(1, dx)) if dx else 0, int(math.copysign(1, dz)) if dz else 0)]
        s.set(x + dx, y + layer, z + dz, _crystal(size, f))
    return (tx, ty, tz)


def steep_fill(s, H, full):
    """Close a steep (2-per-step) roof by stacking a copy of each stair under itself, so the slope reads
    as overlapping shingles rather than terraces; peaks and the bottom of each step get full blocks."""
    for (x, z), h in H.items():
        low = min(H.get((x + dx, z + dz), h - 1) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if h - low <= 1:
            continue
        st = s.get(x, h, z)
        for k in range(1, h - low):
            s.set(x, h - k, z, st if (st and "stairs" in st) else full)
        s.set(x, low, z, full)


def hang_under(s, x, y, z, soul=False, limit=12):
    """A hanging lantern at (x, y, z) on a chain reaching up to the first block above it. Nothing is
    placed (returns False) unless that block is a full cube: a chain cannot hang from a stair or slab."""
    from voxel import solid_cube
    top = None
    for yy in range(y + 1, y + limit + 1):
        if s.get(x, yy, z) is not None:
            top = yy
            break
    if top is None or not solid_cube(s.get(x, top, z)):
        return False
    for yy in range(y + 1, top):
        s.set(x, yy, z, chain())
    s.set(x, y, z, lantern(hanging=True, soul=soul))
    return True


def fill_dark_pockets(s, block, max_size=6):
    """Fill the small sealed voids that roofs leave behind (enclosed air with no light at all), so no
    dark pocket remains under a roof. Larger dark spaces are left alone: those need a lantern."""
    from gates import Gates
    g = Gates(s)
    L = g.light()
    dark = {c for c in g.enclosed() if L.get(c, 0) == 0 and s.get(*c) is None}
    seen = set()
    for c in sorted(dark):
        if c in seen:
            continue
        group, todo = [], [c]
        seen.add(c)
        while todo:
            p = todo.pop()
            group.append(p)
            for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                q = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
                if q in dark and q not in seen:
                    seen.add(q)
                    todo.append(q)
        if len(group) <= max_size:
            for p in group:
                s.set(*p, block)
    return s
