"""Block models and textures read straight from the game jars (read-only), baked into faces.

A block state ("minecraft:deepslate_brick_stairs[facing=north,half=top,shape=straight]") goes through
the same steps the game takes: blockstate JSON -> variant or multipart models (with x/y rotation and
uvlock) -> model JSON with its parent chain -> elements (from/to, element rotation, per-face uv,
texture, cullface, tint) -> textures (PNG, first animation frame). The result is a list of Face
objects in block-local coordinates (0..1), ready for the isometric renderer.

Sources: libs/mc-extra.jar (vanilla), the MineColonies jar in libs/ (for its rack model), and this
repository's resources (the hut block model). Nothing is written anywhere.
"""
import glob
import io
import json
import math
import os
import re
import zipfile
from functools import lru_cache

import numpy as np
from PIL import Image

import paths

LIBS = paths.LIBS
REPO_ASSETS = paths.res("assets")

DIRS = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
        "west": (-1, 0, 0), "east": (1, 0, 0)}
_VEC2DIR = {v: k for k, v in DIRS.items()}

_STATE = re.compile(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(.*)\])?$")

# grey textures the game tints by biome; a pleasant temperate plains/forest look
GRASS_TINT = (0.53, 0.74, 0.33)
FOLIAGE_TINT = (0.44, 0.66, 0.20)
TINTS = {
    "grass_block": GRASS_TINT, "short_grass": GRASS_TINT, "tall_grass": GRASS_TINT, "fern": GRASS_TINT,
    "large_fern": GRASS_TINT, "sugar_cane": GRASS_TINT, "potted_fern": GRASS_TINT,
    "oak_leaves": FOLIAGE_TINT, "jungle_leaves": FOLIAGE_TINT, "acacia_leaves": FOLIAGE_TINT,
    "dark_oak_leaves": FOLIAGE_TINT, "mangrove_leaves": FOLIAGE_TINT, "vine": FOLIAGE_TINT,
    "spruce_leaves": (0.38, 0.60, 0.38), "birch_leaves": (0.50, 0.65, 0.33),
}

# light-emitting blocks: how strongly their bright pixels glow in the bloom pass (rough light level / 15)
EMISSIVE = {
    "lantern": 1.0, "soul_lantern": 0.9, "torch": 1.0, "wall_torch": 1.0, "soul_torch": 0.8,
    "soul_wall_torch": 0.8, "campfire": 1.0, "soul_campfire": 0.8, "glowstone": 0.9, "sea_lantern": 0.9,
    "shroomlight": 0.9, "crying_obsidian": 0.75, "magma_block": 0.5, "amethyst_cluster": 0.45,
    "large_amethyst_bud": 0.35, "medium_amethyst_bud": 0.25, "small_amethyst_bud": 0.15,
    "end_rod": 1.0, "respawn_anchor": 0.9, "ochre_froglight": 1.0, "verdant_froglight": 1.0,
    "pearlescent_froglight": 1.0, "jack_o_lantern": 1.0, "redstone_lamp": 1.0, "beacon": 1.0,
    "enchanting_table": 0.0, "blast_furnace": 0.6, "furnace": 0.6, "smoker": 0.6,
}
TRANSLUCENT_HINTS = ("stained_glass", "ice", "slime_block", "honey_block", "tinted_glass")


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


def _split_ref(ref, default_ns="minecraft"):
    if ":" in ref:
        ns, path = ref.split(":", 1)
    else:
        ns, path = default_ns, ref
    return ns, path


class Face:
    """One textured quad. corners: 4x3 block-local points in the order top-left, top-right,
    bottom-right, bottom-left of the texture; uv: 4x2 (0..16) for those corners."""
    __slots__ = ("corners", "uv", "tex", "normal", "cull", "tint", "shade", "ao")

    def __init__(self, corners, uv, tex, normal, cull, tint, shade, ao):
        self.corners, self.uv, self.tex, self.normal = corners, uv, tex, normal
        self.cull, self.tint, self.shade, self.ao = cull, tint, shade, ao


class Baked:
    """A block state ready to draw."""
    __slots__ = ("faces", "occluder", "weight", "layer", "emissive", "name")

    def __init__(self, name, faces, occluder, weight, layer, emissive):
        self.name, self.faces, self.occluder, self.weight = name, faces, occluder, weight
        self.layer, self.emissive = layer, emissive


# ------------------------------------------------------------------ geometry helpers (0..16 space)
def face_corners(d, f, t):
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
    }[d]


def default_uv(d, f, t):
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {
        "down": [x0, 16 - z1, x1, 16 - z0], "up": [x0, z0, x1, z1],
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0], "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0], "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
    }[d]


def corner_uvs(uv, rotation):
    u1, v1, u2, v2 = uv
    base = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
    k = (int(rotation) // 90) % 4
    return [base[(i - k) % 4] for i in range(4)]


def _rot_matrix(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if abs(c) < 1e-9:
        c = 0.0
    if abs(s) < 1e-9:
        s = 0.0
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], dtype=float)
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=float)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], dtype=float)


def _snap_dir(v):
    v = np.asarray(v, dtype=float)
    i = int(np.argmax(np.abs(v)))
    if abs(v[i]) < 0.99:
        return None
    t = [0, 0, 0]
    t[i] = 1 if v[i] > 0 else -1
    return _VEC2DIR[tuple(t)]


# ------------------------------------------------------------------ the asset store
class Assets:
    def __init__(self):
        self.mc = zipfile.ZipFile(os.path.join(LIBS, "mc-extra.jar"))
        hits = sorted(glob.glob(os.path.join(LIBS, "minecolonies-*.jar")))
        self.mcol = zipfile.ZipFile(hits[0]) if hits else None
        self._tex = {}
        self._json = {}
        self._baked = {}
        self.warnings = set()

    # -------------------------------------------------------------- raw files
    def _read(self, ns, rel):
        if ns == "minecraft":
            return self.mc.read(f"assets/minecraft/{rel}")
        if ns == "minecolonies" and self.mcol is not None:
            return self.mcol.read(f"assets/minecolonies/{rel}")
        p = os.path.join(REPO_ASSETS, ns, *rel.split("/"))
        if os.path.exists(p):
            with open(p, "rb") as fh:
                return fh.read()
        # a texture of another namespace that only exists in vanilla
        return self.mc.read(f"assets/minecraft/{rel}")

    def json(self, ns, rel):
        key = (ns, rel)
        if key not in self._json:
            self._json[key] = json.loads(self._read(ns, rel))
        return self._json[key]

    def texture(self, ref):
        """RGBA float32 array (h, w, 4), first animation frame."""
        if ref in self._tex:
            return self._tex[ref]
        ns, path = _split_ref(ref)
        try:
            data = self._read(ns, f"textures/{path}.png")
        except KeyError:
            self.warnings.add("missing texture " + ref)
            arr = np.zeros((16, 16, 4), np.float32)
            arr[..., 0] = 1.0; arr[..., 2] = 1.0; arr[..., 3] = 1.0
            self._tex[ref] = arr
            return arr
        im = Image.open(io.BytesIO(data)).convert("RGBA")
        w, h = im.size
        if h > w:
            im = im.crop((0, 0, w, w))
        arr = np.asarray(im, dtype=np.float32) / 255.0
        self._tex[ref] = arr
        return arr

    # -------------------------------------------------------------- models
    def model(self, ref):
        """Resolved model: (textures dict, elements list or None, ambientocclusion)."""
        ns, path = _split_ref(ref)
        if path.startswith("builtin/"):
            return {}, None, True
        try:
            data = self.json(ns, f"models/{path}.json")
        except KeyError:
            self.warnings.add("missing model " + ref)
            return {}, None, True
        if "parent" in data:
            # a resource location without a namespace always means minecraft:, whoever writes it
            ptex, pel, pao = self.model(data["parent"])
        else:
            ptex, pel, pao = {}, None, True
        tex = dict(ptex)
        tex.update(data.get("textures", {}))
        el = data.get("elements", pel)
        ao = data.get("ambientocclusion", pao)
        return tex, el, ao

    def _resolve_tex(self, textures, key, ns):
        seen = 0
        while key.startswith("#") and seen < 10:
            key = textures.get(key[1:], "")
            seen += 1
        if not key or key.startswith("#"):
            return None
        if ":" not in key:
            key = "minecraft:" + key
        return key

    # -------------------------------------------------------------- blockstates
    @staticmethod
    def _cond_ok(when, props):
        if "OR" in when:
            return any(Assets._cond_ok(w, props) for w in when["OR"])
        if "AND" in when:
            return all(Assets._cond_ok(w, props) for w in when["AND"])
        for k, v in when.items():
            if str(props.get(k, "")) not in str(v).split("|"):
                return False
        return True

    def state_models(self, name, props):
        """[(model ref, x, y, uvlock)] for a block state."""
        ns, path = _split_ref(name)
        try:
            bs = self.json(ns, f"blockstates/{path}.json")
        except KeyError:
            self.warnings.add("missing blockstate " + name)
            return []
        out = []
        if "variants" in bs:
            best, best_score = None, -1
            for key, val in bs["variants"].items():
                conds = [kv.split("=") for kv in key.split(",")] if key else []
                if all(props.get(k) == v for k, v in conds):
                    score = len(conds)
                    if score > best_score:
                        best, best_score = val, score
            if best is None:          # fall back to the variant agreeing with most properties
                scored = []
                for key, val in bs["variants"].items():
                    conds = [kv.split("=") for kv in key.split(",")] if key else []
                    scored.append((sum(props.get(k) == v for k, v in conds), val))
                scored.sort(key=lambda t: -t[0])
                best = scored[0][1]
                self.warnings.add(f"no exact variant for {name}{props}")
            vals = [best] if isinstance(best, dict) else best[:1]
            for v in vals:
                out.append((v["model"], v.get("x", 0), v.get("y", 0), v.get("uvlock", False)))
        else:
            for part in bs.get("multipart", []):
                if "when" in part and not self._cond_ok(part["when"], props):
                    continue
                ap = part["apply"]
                vals = [ap] if isinstance(ap, dict) else ap[:1]
                for v in vals:
                    out.append((v["model"], v.get("x", 0), v.get("y", 0), v.get("uvlock", False)))
        return out

    # -------------------------------------------------------------- baking
    def bake(self, state):
        if state in self._baked:
            return self._baked[state]
        name, props = parse_state(state)
        short = name.split(":")[1]
        faces = []
        full_opaque = False
        volume = 0.0
        ao_flags = []
        for mref, rx, ry, uvlock in self.state_models(name, props):
            mns, _ = _split_ref(mref)
            textures, elements, ao = self.model(mref)
            ao_flags.append(ao)
            if not elements:
                continue
            Rb = _rot_matrix("y", -ry) @ _rot_matrix("x", -rx)
            for el in elements:
                f = el["from"]; t = el["to"]
                volume += max(0, t[0] - f[0]) * max(0, t[1] - f[1]) * max(0, t[2] - f[2]) / 4096.0
                er = el.get("rotation")
                Re, origin, scale = None, None, None
                if er and er.get("angle", 0):
                    Re = _rot_matrix(er["axis"], er["angle"])
                    origin = np.array(er.get("origin", [8, 8, 8]), dtype=float)
                    if er.get("rescale"):
                        sc = 1.0 / math.cos(math.radians(abs(er["angle"])))
                        scale = np.array([1.0 if er["axis"] == "x" else sc, 1.0 if er["axis"] == "y" else sc,
                                          1.0 if er["axis"] == "z" else sc])
                is_full = f == [0, 0, 0] and t == [16, 16, 16] and Re is None
                el_opaque = True
                for d, fd in el.get("faces", {}).items():
                    texref = self._resolve_tex(textures, fd.get("texture", ""), mns)
                    if texref is None:
                        continue
                    tex = self.texture(texref)
                    if tex[..., 3].min() < 0.99:
                        el_opaque = False
                    corners = np.array(face_corners(d, f, t), dtype=float)
                    uv = fd.get("uv") or default_uv(d, f, t)
                    normal = np.array(DIRS[d], dtype=float)
                    if Re is not None:
                        corners = (corners - origin) @ Re.T
                        if scale is not None:
                            corners = corners * scale
                        corners = corners + origin
                        normal = Re @ normal
                    corners = (corners - 8.0) @ Rb.T + 8.0
                    normal = Rb @ normal
                    cull = fd.get("cullface")
                    if cull:
                        cull = _snap_dir(Rb @ np.array(DIRS[cull], dtype=float))
                    uvc = corner_uvs(uv, fd.get("rotation", 0))
                    if uvlock and (rx or ry) and Re is None:
                        nd = _snap_dir(normal)
                        if nd is not None:
                            lo = np.round(corners.min(axis=0), 4)
                            hi = np.round(corners.max(axis=0), 4)
                            corners = np.array(face_corners(nd, lo, hi), dtype=float)
                            uvc = corner_uvs(default_uv(nd, lo, hi), 0)
                    faces.append(Face(corners / 16.0, np.array(uvc, dtype=float), texref,
                                      normal / max(1e-9, np.linalg.norm(normal)), cull,
                                      fd.get("tintindex", -1), el.get("shade", True), ao))
                if is_full and el_opaque and len(el.get("faces", {})) == 6:
                    full_opaque = True
        layer = "translucent" if (any(h in short for h in TRANSLUCENT_HINTS) or short in ("glass", "glass_pane")) else "solid"
        if short.endswith("leaves"):
            full_opaque = False
        weight = 1.0 if full_opaque else (0.55 if volume >= 0.45 else 0.0)
        emissive = EMISSIVE.get(short, 0.0)
        if short.endswith("candle") or short.endswith("candles"):
            emissive = 0.9 if props.get("lit") == "true" else 0.0
        if short in ("campfire", "soul_campfire") and props.get("lit") == "false":
            emissive = 0.0
        if short in ("blast_furnace", "furnace", "smoker") and props.get("lit") != "true":
            emissive = 0.0
        if short == "redstone_lamp" and props.get("lit") != "true":
            emissive = 0.0
        if short.endswith("copper_bulb") and props.get("lit") == "true":
            emissive = 0.9
        b = Baked(name, faces, full_opaque, weight, layer, emissive)
        self._baked[state] = b
        return b

    def tint_for(self, name):
        return TINTS.get(name.split(":")[1], (1.0, 1.0, 1.0))


_ASSETS = None


def assets():
    global _ASSETS
    if _ASSETS is None:
        _ASSETS = Assets()
    return _ASSETS
