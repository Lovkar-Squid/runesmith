"""The Runesmith hut block model: a small anvil with an enchanted book on a blackstone plinth carved
with glowing runes, an amethyst cluster growing at its back corner and a heap of lapis at its foot.
Built from vanilla textures only (Blockbench-style JSON), never from MineColonies' own textures.

    python tools/gen/gen_hut_model.py

Writes resources/assets/runesmith/models/block/blockhutrunesmith.json (the blockstate turns it with
the hut's facing; the item model inherits it) and a copy in tools/out/ for previews.
"""
import json
import os

import paths

TEXTURES = {
    "rim": "minecraft:block/polished_blackstone",
    "plinth_side": "minecraft:block/chiseled_polished_blackstone",
    "plinth_top": "minecraft:block/polished_blackstone_bricks",
    "rune": "minecraft:block/amethyst_block",
    "anvil": "minecraft:block/anvil",
    "anvil_top": "minecraft:block/anvil_top",
    "book": "minecraft:item/enchanted_book",
    "crystal": "minecraft:block/amethyst_cluster",
    "lapis": "minecraft:block/lapis_block",
    "particle": "minecraft:block/polished_blackstone_bricks",
}
ALL = ("north", "south", "east", "west", "up", "down")


def auto_uv(face, a, b):
    (x1, y1, z1), (x2, y2, z2) = a, b
    if face in ("north", "south"):
        return [x1, 16 - y2, x2, 16 - y1]
    if face in ("east", "west"):
        return [z1, 16 - y2, z2, 16 - y1]
    return [x1, z1, x2, z2]


def element(a, b, faces, rotation=None, name=None, glow=False, uv=None, shade=True):
    """faces: one texture key for every face, or a dict face -> key (faces left out are not drawn).
    uv: optional dict face -> explicit uv, for a texture that should be shown whole."""
    if isinstance(faces, str):
        faces = {f: faces for f in ALL}
    el = {"from": list(a), "to": list(b),
          "faces": {f: {"uv": (uv or {}).get(f, auto_uv(f, a, b)), "texture": "#" + key} for f, key in faces.items()}}
    if name:
        el["name"] = name
    if rotation:
        axis, angle, origin = rotation
        el["rotation"] = {"angle": angle, "axis": axis, "origin": list(origin)}
    if glow:
        el["neoforge_data"] = {"block_light": 15, "sky_light": 15}
    if not shade:
        el["shade"] = False
    return el


def boxed(a, b, side, top, name):
    """A box with one texture on its sides and another on top and bottom."""
    return element(a, b, {"north": side, "south": side, "east": side, "west": side, "up": top, "down": top}, name=name)


def cross(cx, cz, y0, y1, half, key, name):
    """Two crossed planes, the way the game draws a cluster or a plant, standing at (cx, cz)."""
    whole = {f: [0, 0, 16, 16] for f in ALL}
    a = element((cx - half, y0, cz), (cx + half, y1, cz), {"north": key, "south": key},
                ("y", 45, (cx, y0, cz)), name=name + " a", glow=True, uv=whole, shade=False)
    b = element((cx, y0, cz - half), (cx, y1, cz + half), {"east": key, "west": key},
                ("y", 45, (cx, y0, cz)), name=name + " b", glow=True, uv=whole, shade=False)
    return [a, b]


def rune(face, u0, v0, u1, v1, name):
    """A glowing rune inset 0.1 into one side of the plinth (1..15 x 1.5..4 y), between u0..u1 and v0..v1."""
    if face == "north":
        a, b = (u0, v0, 0.9), (u1, v1, 1.0)
    elif face == "south":
        a, b = (u0, v0, 15.0), (u1, v1, 15.1)
    elif face == "west":
        a, b = (0.9, v0, u0), (1.0, v1, u1)
    else:
        a, b = (15.0, v0, u0), (15.1, v1, u1)
    return element(a, b, {face: "rune"}, name=name, glow=True)


elements = [
    # blackstone rim, then the carved plinth
    element((0.5, 0, 0.5), (15.5, 1.5, 15.5), "rim", name="rim"),
    boxed((1, 1.5, 1), (15, 4, 15), "plinth_side", "plinth_top", "plinth"),
]
# runes on all four sides: a long stroke and a short one, glowing amethyst
for face in ("north", "south", "east", "west"):
    elements.append(rune(face, 4, 2.25, 9, 3.25, f"rune {face} long"))
    elements.append(rune(face, 10.5, 2, 12, 3.5, f"rune {face} short"))
elements += [
    # the anvil, the vanilla one scaled down, its face running north-south
    element((4.5, 4, 4.5), (11.5, 5.5, 11.5), "anvil", name="anvil foot"),
    element((6, 5.5, 5.5), (10, 7, 10.5), "anvil", name="anvil waist"),
    element((4, 7, 2), (12, 10, 14), {"north": "anvil", "south": "anvil", "east": "anvil", "west": "anvil",
                                      "up": "anvil_top", "down": "anvil"}, name="anvil face"),
    # an enchanted book lying open on the anvil face
    element((5.5, 10, 5.5), (10.5, 10.5, 10.5), {"up": "book", "north": "rim", "south": "rim", "east": "rim", "west": "rim"},
            name="book", uv={"up": [0, 0, 16, 16]}),
    # a heap of lapis at the anvil's foot
    element((11.5, 4, 11.5), (14.5, 5.5, 14.5), "lapis", name="lapis heap"),
    element((12.25, 5.5, 12.25), (13.75, 6.5, 13.75), "lapis", name="lapis top"),
]
# amethyst growing out of the back left corner (the model's south side faces whoever placed the hut),
# tall enough to rise above the anvil from every side, with a small cluster beside it
elements += cross(3, 3, 4, 13.5, 3, "crystal", "crystal")
elements += cross(13, 2.5, 4, 8, 2, "crystal", "small crystal")

model = {
    "credit": "Runesmith - Lovkar & Claude",
    "parent": "block/block",
    "ambientocclusion": False,
    "textures": TEXTURES,
    "elements": elements,
}


def main():
    out = paths.res("assets", "runesmith", "models", "block", "blockhutrunesmith.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(model, fh, indent=2)
        fh.write("\n")
    with open(paths.out("hutmodel.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(model, fh)
    print(os.path.relpath(out, paths.ROOT).replace(os.sep, "/"), len(elements), "elements")


if __name__ == "__main__":
    main()
