# Building Runesmith

Runesmith is compiled with plain `javac` and packed with `jar`. There is no Gradle and no mappings
step, because NeoForge 1.21.1 mods run on the official Mojang names.

## What you need

- **JDK 21.** By default the scripts use `../../tools/jdk21`, relative to the repository. Set
  `RUNESMITH_JDK` to use another JDK.
- **A `libs/` folder.** It is not part of the repository, because the jars are not ours to
  redistribute. By default it is `../../libs`; set `RUNESMITH_LIBS` to use another folder. It
  holds:
  - Minecraft 1.21.1 as NeoForge installs it: `neoforge-21.1.x-client.jar` (the classes NeoForge
    patches), the vanilla `client-1.21.1-...-srg.jar`, and `neoforge-21.1.x-universal.jar`;
  - MineColonies 1.1.13xx, Structurize, BlockUI and Domum Ornamentum;
  - the libraries Minecraft links against: DataFixerUpper, fastutil, Guava, Gson, SLF4J,
    commons-lang3, JOML, Brigadier, the JetBrains annotations, and so on;
  - `mc-extra.jar`, the vanilla assets/data jar, which the blueprint generator checks block ids
    against.
- **`stubsrc/`.** It holds two tiny stand-ins for `net.neoforged.api.distmarker.Dist` and
  `OnlyIn`, which none of those jars carry. They are compiled onto the compile classpath only and
  never go into the mod jar.
- **Python 3** with `nbtlib`, `Pillow` and `numpy`, if you want to regenerate the blueprints or the previews.

The NeoForge-patched client jar must come before the vanilla client jar on the classpath. The
scripts do this; without it, javac sees vanilla classes that lack NeoForge's methods, such as
`ItemStack.supportsEnchantment`.

## Compile and package

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1           # out/runesmith-<version>.jar
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-harness.ps1   # out/runesmithtest-<version>.jar
```

The version lives only in `resources/META-INF/neoforge.mods.toml`.

## Generated resources

`python tools/gen/build_pack.py` writes the *Runesmith* structure pack
(`resources/blueprints/runesmith/runesmith/`): three looks of the one building, five levels each.

- `designs_a.py`: Forge Hall (`runesmith/forgehall1-5`);
- `designs_b.py`: Rune Tower (`runesmith/runetower1-5`);
- `designs_c.py`: Crystal Heart (`runesmith/crystalheart1-5`).

Every level of a look shares one footprint, aligned on the hut block, so an upgrade never grows
past the outline that was placed. Around the design, the two lowest layers above the foundation are
left to the terrain (Structurize's *keep* block), everything above is cleared. The foundation course
is *solid substitution*. The script prints `ALL OK` when every gate passes, and a blueprint reaches
`resources/` only then. `gates.py` checks:

- block ids and states against the jars, and no blocks that need block-entity data;
- one hut block as the anchor and one anvil with the `work` tag;
- racks as MineColonies stores them, against a wall;
- the hut block, the anvil and every rack reachable from outside through a door;
- doors with floor on both sides;
- light where the worker stands and inside;
- no floating blocks;
- across a look's levels: the hut block fixed, racks never fewer, every level inside level 5.

The written file is then compared with MineColonies' own blueprints (`checks.py`) and read back
byte for byte (`blueprint.py`).

`python tools/gen/render_all.py` draws textured previews of every level, and a sheet of all three
looks, into `tools/out/`. It reads the game's own block models and textures from `libs/`
(`mcassets.py`, `isorender.py`); nothing is copied into the repository.

`python tools/gen/gen_hut_model.py` writes the hut block's model
(`resources/assets/runesmith/models/block/blockhutrunesmith.json`) from vanilla textures only.

The scripts need Python 3 with `nbtlib`, `Pillow` and `numpy`.

## Headless tests

The test harness (`tools/harness/`, mod id `runesmithtest`) runs one scenario on a dedicated
NeoForge server with no player. It builds a colony, pastes MineColonies' own buildings and ours,
hires the workers, stocks the racks, checks the outcome and stops the server.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-scenario.ps1 -Name a -Words "rate=60"
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-all.ps1        # every scenario, in order
```

The server lives in `../../rig` (a NeoForge 21.1.249 server install). Its `server.properties`
needs two things:

- `spawn-npcs=true`, because MineColonies citizens are NPCs and vanilla discards NPCs when they
  are off;
- a deep flat world (`generator-settings`), because hut blueprints reach below their hut block.

`run-scenario.ps1` exits 0 only on `RESULT ... PASS` with no ERROR/Exception line that mentions
runesmith or minecolonies.
