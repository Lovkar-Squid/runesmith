"""Repository-relative paths shared by the generator scripts, so they run from any checkout.

Third-party jars are read from libs/, which is not part of the repository (see BUILDING.md): by
default the folder two levels above the repository root, or the folder named by the RUNESMITH_LIBS
environment variable. Previews and scratch files go to tools/out/, which is git-ignored.
"""
import glob
import os

GEN = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(GEN)
ROOT = os.path.dirname(TOOLS)
RESOURCES = os.path.join(ROOT, "resources")
LIBS = os.environ.get("RUNESMITH_LIBS") or os.path.normpath(os.path.join(ROOT, "..", "..", "libs"))
OUT = os.environ.get("RUNESMITH_OUT") or os.path.join(TOOLS, "out")


def res(*parts):
    """A path under resources/."""
    return os.path.join(RESOURCES, *parts)


def out(*parts):
    """A path under tools/out/ (the folder is created on demand)."""
    os.makedirs(OUT, exist_ok=True)
    return os.path.join(OUT, *parts)


def lib(pattern):
    """The jar in libs/ matching a glob pattern ('minecolonies-*.jar'); raises if there is none."""
    hits = sorted(glob.glob(os.path.join(LIBS, pattern)))
    if not hits:
        raise FileNotFoundError(f"no {pattern} in {LIBS} - put the jar there or set RUNESMITH_LIBS (see BUILDING.md)")
    return hits[0]
