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
- **Python 3** with `nbtlib` and `Pillow`, if you want to regenerate the blueprints.

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
(`resources/blueprints/runesmith/runesmith/`) and prints `ALL OK` when every gate passes:

- known block ids and states;
- no floating blocks;
- a door with floor on both sides;
- a walkable path to the anvil;
- light where the worker stands;
- a byte-exact round trip of the written blueprint.

`tools/gen/isorender.py` draws a flat isometric preview into `tools/out/`.

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
