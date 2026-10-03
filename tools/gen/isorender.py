"""Textured isometric renderer for voxel structures.

Every block is drawn from the game's own block model and textures (see mcassets.py): stairs, walls,
fences, panes, chains, lanterns, candles, amethyst clusters, anvils, racks and so on keep their real
shapes. A z-buffer sorts the faces; glass and other translucent blocks are blended in a second pass,
back to front. Shading is the game's directional face shading plus smooth-lighting style ambient
occlusion at the face corners; light sources (lanterns, candles, crying obsidian, magma, clusters)
get a soft bloom so the runes and lamps read as glowing.

The camera always sits to the south-east, 30 degrees above the horizon (2:1 dimetric, the classic
pixel-art isometric). Other views rotate the world first: view=0 shows the south and east faces
(front-right when the entrance is on the south side), view=2 shows the north and west faces
(back-left).
"""
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from mcassets import DIRS, assets

ELEV = math.radians(30.0)
C45 = math.sqrt(0.5)
TO_VIEWER = np.array([C45 * math.cos(ELEV), math.sin(ELEV), C45 * math.cos(ELEV)])
AO_STEP = 0.15              # darkening per occluding neighbour at a face corner (the game uses 0.2)


def view_matrix(view):
    """Rotation about the vertical axis by view * 90 degrees (view 0..3)."""
    v = view % 4
    c, s = [(1, 0), (0, 1), (-1, 0), (0, -1)][v]
    # (x, z) -> (c*x - s*z, s*x + c*z)
    return np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]], dtype=float)


def face_shade(n):
    """The game's diffuse face light (x: 0.6, z: 0.8, up 1.0, down 0.5), lifted a little on the sides."""
    x, y, z = n
    return min(1.0, x * x * 0.70 + y * y * ((3.0 + y) / 4.0) + z * z * 0.88)


class Projector:
    def __init__(self, scale, view):
        self.s = scale
        self.R = view_matrix(view)
        self.ox = 0.0
        self.oy = 0.0

    def rot(self, p):
        return np.asarray(p, dtype=float) @ self.R.T

    def xy(self, q):
        """screen x, y of view-space points (n, 3)"""
        q = np.atleast_2d(q)
        sx = (q[:, 0] - q[:, 2]) * C45 * self.s + self.ox
        sy = (q[:, 0] + q[:, 2]) * C45 * math.sin(ELEV) * self.s - q[:, 1] * math.cos(ELEV) * self.s + self.oy
        return np.stack([sx, sy], axis=1)

    def world_to_screen(self, p):
        return self.xy(self.rot(np.atleast_2d(p)))


def _corner_ao(world, normal, weight):
    """Per-corner brightness (4,) from the occluders around each corner, like smooth lighting."""
    a = int(np.argmax(np.abs(normal)))
    if abs(normal[a]) < 0.99:
        return None
    sg = 1 if normal[a] > 0 else -1
    plane = world[0, a]
    o = math.floor(plane + sg * 1e-3)
    b, c = [i for i in range(3) if i != a]
    center = world.mean(axis=0)
    out = np.ones(4)
    for k in range(4):
        lb = int(round(world[k, b]))
        lc = int(round(world[k, c]))
        fb = lb - 1 if center[b] < lb else lb
        fc = lc - 1 if center[c] < lc else lc
        ob = lb if fb == lb - 1 else lb - 1
        oc = lc if fc == lc - 1 else lc - 1

        def w(ib, ic):
            idx = [0, 0, 0]
            idx[a] = o; idx[b] = ib; idx[c] = ic
            return weight.get((idx[0], idx[1], idx[2]), 0.0)

        s1, s2, cr = w(ob, fc), w(fb, oc), w(ob, oc)
        if s1 > 0.99 and s2 > 0.99:
            cr = 1.0
        out[k] = 1.0 - AO_STEP * (s1 + s2 + cr)
    return out


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.color = np.zeros((h, w, 3), np.float32)
        self.depth = np.full((h, w), -1e9, np.float32)
        self.emis = np.zeros((h, w, 3), np.float32)
        self.cover = np.zeros((h, w), np.float32)

    def raster(self, P, D, UV, tex, mul, ao, emissive, translucent):
        """P: (4, 2) screen corners (c0, c1, c2, c3); D: (4,) nearness; UV: (4, 2)."""
        xs, ys = P[:, 0], P[:, 1]
        x0 = max(0, int(math.floor(xs.min())) - 1)
        x1 = min(self.w, int(math.ceil(xs.max())) + 1)
        y0 = max(0, int(math.floor(ys.min())) - 1)
        y1 = min(self.h, int(math.ceil(ys.max())) + 1)
        if x0 >= x1 or y0 >= y1:
            return
        A = P[0]
        ex = P[1] - A
        ey = P[3] - A
        det = ex[0] * ey[1] - ex[1] * ey[0]
        if abs(det) < 1e-3:
            return
        gx = np.arange(x0, x1, dtype=np.float32) + 0.5 - A[0]
        gy = np.arange(y0, y1, dtype=np.float32) + 0.5 - A[1]
        qx = gx[None, :]
        qy = gy[:, None]
        s = (qx * ey[1] - qy * ey[0]) / det
        t = (ex[0] * qy - ex[1] * qx) / det
        es = 0.6 / max(1e-6, math.hypot(*ex))
        et = 0.6 / max(1e-6, math.hypot(*ey))
        inside = (s >= -es) & (s <= 1 + es) & (t >= -et) & (t <= 1 + et)
        if not inside.any():
            return
        s = np.clip(s, 0.0, 1.0)
        t = np.clip(t, 0.0, 1.0)
        dep = D[0] + s * (D[1] - D[0]) + t * (D[3] - D[0])
        reg = self.depth[y0:y1, x0:x1]
        test = inside & (dep >= reg - 1e-4)
        if not test.any():
            return
        u = UV[0, 0] + s * (UV[1, 0] - UV[0, 0]) + t * (UV[3, 0] - UV[0, 0])
        v = UV[0, 1] + s * (UV[1, 1] - UV[0, 1]) + t * (UV[3, 1] - UV[0, 1])
        th, tw = tex.shape[:2]
        tj = np.clip((u * (tw / 16.0)).astype(np.int32), 0, tw - 1)
        ti = np.clip((v * (th / 16.0)).astype(np.int32), 0, th - 1)
        texel = tex[ti, tj]
        alpha = texel[..., 3]
        if translucent:
            test &= alpha > 0.02
        else:
            test &= alpha > 0.5
        if not test.any():
            return
        if ao is not None:
            shade = ((1 - s) * (1 - t) * ao[0] + s * (1 - t) * ao[1] + s * t * ao[2] + (1 - s) * t * ao[3])
            rgb = texel[..., :3] * mul * shade[..., None]
        else:
            rgb = texel[..., :3] * mul
        col = self.color[y0:y1, x0:x1]
        if translucent:
            a = alpha[..., None]
            col[test] = (col * (1 - a) + rgb * a)[test]
            cov = self.cover[y0:y1, x0:x1]
            cov[test] = np.maximum(cov[test], alpha[test])
            return
        col[test] = rgb[test]
        reg[test] = dep[test]
        self.cover[y0:y1, x0:x1][test] = 1.0
        em = self.emis[y0:y1, x0:x1]
        if emissive > 0:
            raw = texel[..., :3]
            lum = raw[..., 0] * 0.3 + raw[..., 1] * 0.55 + raw[..., 2] * 0.15
            k = np.clip((lum - 0.32) / 0.45, 0.0, 1.0) * emissive
            em[test] = (raw * k[..., None])[test]
        else:
            em[test] = 0.0


def _gradient(w, h, top, bottom):
    t = np.linspace(0.0, 1.0, h, dtype=np.float32)[:, None, None]
    top = np.array(top, np.float32) / 255.0
    bottom = np.array(bottom, np.float32) / 255.0
    return np.broadcast_to(top * (1 - t) + bottom * t, (h, w, 3)).copy()


def _blur(arr, radius):
    """Gaussian blur of a float (h, w, 3) array via PIL in 16-bit-ish precision (two 8-bit halves)."""
    scale = max(1e-6, float(arr.max()))
    im = Image.fromarray(np.clip(arr / scale * 255.0, 0, 255).astype(np.uint8))
    im = im.filter(ImageFilter.GaussianBlur(radius))
    return np.asarray(im, np.float32) / 255.0 * scale


def render(blocks, view=0, scale=32.0, ss=2, bloom=1.0, background=((44, 52, 66), (16, 19, 26)),
           pad=60, extra_top=0, ground_shadow=None, width=None, tone=(0.85, 1.06)):
    """Render {(x, y, z): state} to a PIL image.

    scale: pixels per block edge in the output (or pass width= to fit the image to that width).
    ss: supersampling factor. background: (top, bottom) colours of a vertical gradient, or one colour.
    tone: (gamma, exposure) applied to the blocks, lifting the dark deepslate and blackstone a little
    so their texture still reads at small sizes. Returns (image, projector) - the projector maps world
    points to output pixels for labels.
    """
    A = assets()
    baked = {p: A.bake(st) for p, st in blocks.items()}
    weight = {p: b.weight for p, b in baked.items() if b.weight > 0}
    occ = {p for p, b in baked.items() if b.occluder}
    prj = Projector(1.0, view)

    # ---- collect visible faces in view space
    items = []
    for p, b in baked.items():
        pv = np.array(p, dtype=float)
        tint = A.tint_for(b.name)
        for f in b.faces:
            if f.cull:
                d = DIRS[f.cull]
                nb = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
                if nb in occ:
                    continue
                if b.layer == "translucent" and nb in baked and baked[nb].name == b.name:
                    continue
            nview = prj.R @ f.normal
            facing = float(nview @ TO_VIEWER)
            if facing <= 1e-4:
                continue
            world = pv + f.corners
            ao = _corner_ao(world, f.normal, weight) if f.ao else None
            q = prj.rot(world)
            near = q @ TO_VIEWER
            sh = face_shade(nview) if f.shade else 1.0
            if b.emissive > 0:
                sh = max(sh, 0.92)
                ao = None if ao is None else np.maximum(ao, 0.9)
            mul = np.array(tint if f.tint >= 0 else (1.0, 1.0, 1.0), np.float32) * sh
            items.append((q, near, f.uv, f.tex, mul, ao, b.emissive, b.layer == "translucent"))
    if not items:
        raise ValueError("nothing to draw")

    allq = np.concatenate([it[0] for it in items])
    xy1 = prj.xy(allq)
    lo = xy1.min(axis=0)
    hi = xy1.max(axis=0)
    if width is not None:
        scale = (width - 2 * pad) / max(1e-6, hi[0] - lo[0])
    S = scale * ss
    prj.s = S
    xyS = prj.xy(allq)
    lo = xyS.min(axis=0)
    hi = xyS.max(axis=0)
    P = int(pad * ss)
    prj.ox = -lo[0] + P
    prj.oy = -lo[1] + P + extra_top * ss
    W = int(math.ceil(hi[0] - lo[0])) + 2 * P
    H = int(math.ceil(hi[1] - lo[1])) + 2 * P + int(extra_top * ss)
    W += (-W) % ss
    H += (-H) % ss
    cv = Canvas(W, H)

    solid = [it for it in items if not it[7]]
    trans = [it for it in items if it[7]]
    for q, near, uv, tex, mul, ao, em, tr in solid:
        cv.raster(prj.xy(q), near, uv, A.texture(tex), mul, ao, em, False)
    trans.sort(key=lambda it: float(it[1].mean()))
    for q, near, uv, tex, mul, ao, em, tr in trans:
        cv.raster(prj.xy(q), near, uv, A.texture(tex), mul, ao, em, True)

    # ---- compose: background, ground shadow, scene, bloom
    if isinstance(background[0], (int, float)):
        background = (background, background)
    bg = _gradient(W, H, *background)
    if ground_shadow is not None:
        mask = Image.new("L", (W, H), 0)
        pts = [tuple(xy) for xy in prj.world_to_screen(np.array(ground_shadow, dtype=float))]
        ImageDraw.Draw(mask).polygon(pts, fill=150)
        mask = mask.filter(ImageFilter.GaussianBlur(S * 0.9))
        m = np.asarray(mask, np.float32)[..., None] / 255.0
        bg = bg * (1 - 0.75 * m)
    cover = cv.cover[..., None]
    col = cv.color
    if tone:
        col = np.clip(np.power(np.clip(col, 0.0, 1.0), tone[0]) * tone[1], 0.0, 1.0)
    img = col * cover + bg * (1 - cover)
    if bloom > 0 and cv.emis.max() > 0:
        e = cv.emis
        img = img + bloom * (0.55 * _blur(e, S * 0.18) + 0.75 * _blur(e, S * 0.65) + 0.45 * _blur(e, S * 1.8))
    img = np.clip(img, 0.0, 1.0)
    out = Image.fromarray((img * 255.0 + 0.5).astype(np.uint8))
    if ss > 1:
        out = out.resize((W // ss, H // ss), Image.LANCZOS)
        prj.s /= ss
        prj.ox /= ss
        prj.oy /= ss
    return out, prj


# ------------------------------------------------------------------ text helpers
_FONT_CACHE = {}
FONT_FILES = {
    "title": ["GeorgiaPro-Bold.ttf", "georgiab.ttf", "arialbd.ttf", "arial.ttf"],
    "serif": ["GeorgiaPro-SemiBold.ttf", "georgia.ttf", "arial.ttf"],
    "sans": ["VerdanaPro-SemiBold.ttf", "verdanab.ttf", "arial.ttf"],
    "sans_regular": ["VerdanaPro-Regular.ttf", "verdana.ttf", "arial.ttf"],
}


def font(kind, size):
    key = (kind, size)
    if key in _FONT_CACHE:
        return _FONT_CACHE[key]
    import os
    f = None
    for name in FONT_FILES.get(kind, []):
        for base in (r"C:\Windows\Fonts", "/usr/share/fonts/truetype/dejavu"):
            p = os.path.join(base, name)
            if os.path.exists(p):
                f = ImageFont.truetype(p, size)
                break
        if f:
            break
    if f is None:
        try:
            f = ImageFont.load_default(size=size)
        except TypeError:
            f = ImageFont.load_default()
    _FONT_CACHE[key] = f
    return f


def label(img, prj, world_pt, text, color=(255, 214, 120), dx=40, dy=-60, size=20):
    """A leader line from a world point to a small text tag."""
    d = ImageDraw.Draw(img)
    x, y = prj.world_to_screen(np.array([world_pt], dtype=float))[0]
    tx, ty = x + dx, y + dy
    d.line([(x, y), (tx, ty)], fill=color, width=2)
    r = 5
    d.ellipse([x - r, y - r, x + r, y + r], outline=color, width=2)
    f = font("sans", size)
    bb = d.textbbox((0, 0), text, font=f)
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    bx = tx if dx >= 0 else tx - w - 12
    d.rounded_rectangle([bx, ty - h // 2 - 7, bx + w + 12, ty + h // 2 + 7], radius=6, fill=(20, 22, 30), outline=color, width=2)
    d.text((bx + 6, ty - h // 2 - bb[1]), text, font=f, fill=color)
    return img
