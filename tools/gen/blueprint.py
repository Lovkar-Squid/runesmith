"""The Structurize .blueprint format, written and read back.

Works on any structure with `blocks` ({(x, y, z): block state string}), `tags` ({(x, y, z): [names]})
and `anchor` (the hut block's position), such as a voxel.Structure.

The file layout follows what MineColonies' own packs contain (checked against its Enchanter hut
and against its racks, see checks.py): gzip NBT, version 1, palette plus a packed block array,
the hut block entity with a `blueprintDataProvider` compound (schematic name, corners and the
positioned tags, all relative to the hut block), and the hut position as Structurize's primary
offset.
"""
import gzip
import io
import re
from collections import Counter

import nbtlib
from nbtlib import tag as T

AIR = "minecraft:air"
DATA_VERSION = 3955          # Minecraft 1.21.1, the "mcversion" of MineColonies' own blueprints

RACK_BLOCK = "minecolonies:blockminecoloniesrack"
RACK_ENTITY = "minecolonies:rack"      # block entity id MineColonies saves a rack with
RACK_SLOTS = 27                        # a rack without research upgrades (tagSIze 0)

_STATE = re.compile(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(.*)\])?$")


def parse_state(s):
    """'ns:name[a=b,c=d]' -> ('ns:name', {'a': 'b', 'c': 'd'})"""
    m = _STATE.match(s.strip())
    if not m:
        raise ValueError("bad block state: " + s)
    name, props = m.group(1), {}
    if m.group(2):
        for kv in m.group(2).split(","):
            k, v = kv.split("=")
            props[k.strip()] = v.strip()
    return name, props


def canon(s):
    """The same state with its properties sorted, so equal states compare equal as strings."""
    name, props = parse_state(s)
    if not props:
        return name
    return name + "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"


class Plain:
    """A bare structure: what a blueprint file holds, and what the export needs to move one around."""

    def __init__(self, name, blocks=None, tags=None, anchor=None):
        self.name = name
        self.blocks = dict(blocks or {})
        self.tags = {p: list(n) for p, n in (tags or {}).items()}
        self.anchor = anchor
        self.raw = None        # the parsed NBT file, set by load_blueprint

    @classmethod
    def of(cls, s, name=None):
        return cls(name or s.name, s.blocks, s.tags, s.anchor)

    def set(self, x, y, z, block):
        if block is None or block == AIR:
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = block

    def bounds(self):
        xs = [p[0] for p in self.blocks]; ys = [p[1] for p in self.blocks]; zs = [p[2] for p in self.blocks]
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))

    def size(self):
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        return x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1

    def count(self):
        return Counter(parse_state(b)[0] for b in self.blocks.values())

    def translated(self, dx, dy, dz):
        """A copy moved by (dx, dy, dz), tags and anchor included."""
        a = self.anchor
        return Plain(self.name, {(x + dx, y + dy, z + dz): b for (x, y, z), b in self.blocks.items()},
                     {(x + dx, y + dy, z + dz): n for (x, y, z), n in self.tags.items()},
                     None if a is None else (a[0] + dx, a[1] + dy, a[2] + dz))

    def normalized(self, box=None):
        """A copy whose bounding box (or the given box) starts at (0, 0, 0), where a blueprint file starts."""
        (x0, y0, z0) = box[0] if box is not None else self.bounds()[0]
        return self.translated(-x0, -y0, -z0)


def _empty_inventory():
    """MineColonies saves every empty slot of a rack (and of a hut block, which is a rack too) as {empty: 1b}."""
    return T.List[T.Compound]([T.Compound({"empty": T.Byte(1)}) for _ in range(RACK_SLOTS)])


def _pos(x, y, z):
    return T.Compound({"x": T.Int(x), "y": T.Int(y), "z": T.Int(z)})


def rack_tile(x, y, z):
    """The block entity of one rack, in the shape MineColonies' own blueprints store it."""
    return T.Compound({
        "id": T.String(RACK_ENTITY),
        "x": T.Short(x), "y": T.Short(y), "z": T.Short(z),
        "tagSIze": T.Int(0),
        "inventory": _empty_inventory(),
        "inWarehouse": T.Byte(0),
        "pos": _pos(0, 0, 0),
        "version": T.Byte(0),
    })


def write_blueprint(s, path, file_name, pack_name, pack_path, building_type, be_type, required_mods, architects=(), box=None):
    """Write a Structurize v1 .blueprint (gzip NBT).

    file_name: 'forgehall1.blueprint'; pack_path: its path inside the pack,
    'runesmith/forgehall1.blueprint'; building_type: the building's registry name,
    'runesmith:runesmith'; be_type: the hut's block entity type, 'runesmith:colonybuilding'.
    box: optional ((x0, y0, z0), (x1, y1, z1)) forcing the blueprint size (filled with air, which the
    builder clears) so every level of a look shares one footprint.
    """
    if s.anchor is None or s.anchor not in s.blocks:
        raise ValueError("structure has no anchor (hut block)")
    (x0, y0, z0), (x1, y1, z1) = Plain.of(s).bounds()
    if box is not None:
        (bx0, by0, bz0), (bx1, by1, bz1) = box
        if bx0 > x0 or by0 > y0 or bz0 > z0 or bx1 < x1 or by1 < y1 or bz1 < z1:
            raise ValueError(f"{s.name}: blocks {((x0, y0, z0), (x1, y1, z1))} exceed the box {box}")
        (x0, y0, z0), (x1, y1, z1) = (bx0, by0, bz0), (bx1, by1, bz1)
    sx, sy, sz = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
    ax, ay, az = s.anchor

    # palette in layer order, so the file does not depend on the order the design was built in
    palette = [AIR]
    index = {AIR: 0}
    arr = [0] * (sx * sy * sz)
    for (x, y, z) in sorted(s.blocks, key=lambda p: (p[1], p[2], p[0])):
        b = canon(s.blocks[(x, y, z)])
        if b not in index:
            index[b] = len(palette)
            palette.append(b)
        arr[(y - y0) * sz * sx + (z - z0) * sx + (x - x0)] = index[b]
    # two 16-bit palette indices per int, the first one in the high half
    packed = []
    for i in range(0, len(arr), 2):
        hi = arr[i]
        lo = arr[i + 1] if i + 1 < len(arr) else 0
        v = (hi << 16) | lo
        if v >= 1 << 31:
            v -= 1 << 32
        packed.append(v)
    pal = T.List[T.Compound]()
    for b in palette:
        name, props = parse_state(b)
        c = T.Compound({"Name": T.String(name)})
        if props:
            c["Properties"] = T.Compound({k: T.String(props[k]) for k in sorted(props)})
        pal.append(c)

    # positioned tags are stored relative to the hut block
    tag_map = T.List[T.Compound]([
        T.Compound({
            "tagPos": _pos(tx - ax, ty - ay, tz - az),
            "tagNameList": T.List[T.Compound]([T.Compound({"tagName": T.String(n)}) for n in names]),
        })
        for (tx, ty, tz), names in sorted(s.tags.items())
    ]) if s.tags else T.List([])
    schematic_name = file_name.replace(".blueprint", "")
    hut = T.Compound({
        "id": T.String(be_type),
        "x": T.Short(ax - x0), "y": T.Short(ay - y0), "z": T.Short(az - z0),
        "colony": T.Int(0),
        "inWarehouse": T.Byte(0),
        "tagSIze": T.Int(0),
        "inventory": _empty_inventory(),
        "pos": _pos(0, 0, 0),
        "type": T.String(building_type),
        "version": T.Int(2),
        "pack": T.String(pack_name),
        "path": T.String(pack_path),
        "blueprintDataProvider": T.Compound({
            "corner1": _pos(x0 - ax, y0 - ay, z0 - az),
            "corner2": _pos(x1 - ax, y1 - ay, z1 - az),
            "path": T.String(pack_path),
            "schematicName": T.String(schematic_name),
            "pack": T.String(pack_name),
            "posTagMap": tag_map,
        }),
    })
    # keyed by design position (for a stable order); each entry carries blueprint-relative x, y, z
    tiles = {(ax, ay, az): hut}
    for (x, y, z), b in s.blocks.items():
        if parse_state(b)[0] == RACK_BLOCK:
            tiles[(x, y, z)] = rack_tile(x - x0, y - y0, z - z0)
    tile_list = [tiles[p] for p in sorted(tiles, key=lambda p: (p[1], p[2], p[0]))]

    root = T.Compound({
        "version": T.Byte(1),
        "mcversion": T.Int(DATA_VERSION),
        "name": T.String(schematic_name),
        "size_x": T.Short(sx), "size_y": T.Short(sy), "size_z": T.Short(sz),
        "required_mods": T.List[T.String]([T.String(m) for m in required_mods]),
        "architects": T.List[T.String]([T.String(a) for a in architects]),
        "palette": pal,
        "blocks": T.IntArray(packed),
        "tile_entities": T.List[T.Compound](tile_list),
        "entities": T.List([]),
        "optional_data": T.Compound({"structurize": T.Compound({"primary_offset": _pos(ax - x0, ay - y0, az - z0)})}),
    })
    # gzip with a zero timestamp, like Java's GZIPOutputStream: the same design always gives the same bytes
    raw = io.BytesIO()
    nbtlib.File(root, gzipped=False).write(raw)
    with open(path, "wb") as fh:
        with gzip.GzipFile(filename="", mode="wb", fileobj=fh, compresslevel=9, mtime=0) as gz:
            gz.write(raw.getvalue())
    return path


def load_blueprint(path):
    """Read a .blueprint back into a Plain structure (for verification). The parsed NBT stays in .raw."""
    f = nbtlib.load(path)
    sx, sy, sz = int(f["size_x"]), int(f["size_y"]), int(f["size_z"])
    arr = []
    for v in f["blocks"]:
        v = int(v) & 0xFFFFFFFF
        arr += [(v >> 16) & 0xFFFF, v & 0xFFFF]
    pal = []
    for p in f["palette"]:
        name = str(p["Name"])
        if "Properties" in p:
            name += "[" + ",".join(f"{k}={p['Properties'][k]}" for k in sorted(p["Properties"].keys())) + "]"
        pal.append(name)
    s = Plain(str(f["name"]))
    s.raw = f
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                b = pal[arr[y * sz * sx + z * sx + x]]
                if b != AIR:
                    s.set(x, y, z, b)
    po = f["optional_data"]["structurize"]["primary_offset"]
    s.anchor = (int(po["x"]), int(po["y"]), int(po["z"]))
    ax, ay, az = s.anchor
    for te in f["tile_entities"]:
        if (int(te["x"]), int(te["y"]), int(te["z"])) == s.anchor and "blueprintDataProvider" in te:
            for entry in te["blueprintDataProvider"]["posTagMap"]:
                p = entry["tagPos"]
                s.tags[(ax + int(p["x"]), ay + int(p["y"]), az + int(p["z"]))] = [str(n["tagName"]) for n in entry["tagNameList"]]
    return s
