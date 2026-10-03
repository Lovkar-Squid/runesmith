# Building Runesmith

Runesmith is compiled with plain `javac` and packed with `jar`. There is no Gradle and no mappings
step, because NeoForge 1.21.1 mods run on the official Mojang names.

## What you need

- **JDK 21.** By default `tools/build.ps1` uses `../../tools/jdk21`, relative to the repository.
  Set `RUNESMITH_JDK` to use another JDK.
- **A `libs/` folder.** It is not part of the repository, because the jars are not ours to
  redistribute. By default it is `../../libs`; set `RUNESMITH_LIBS` to use another folder. It
  holds:
  - Minecraft 1.21.1, both the NeoForge-patched client jar and `neoforge-21.1.x-universal.jar`;
  - MineColonies 1.1.13xx, Structurize, BlockUI and Domum Ornamentum;
  - the libraries Minecraft links against: DataFixerUpper, fastutil, Guava, Gson, SLF4J,
    commons-lang3, JOML, Brigadier, the JetBrains annotations, and so on.
- **`stubsrc/`.** It holds two tiny stand-ins for `net.neoforged.api.distmarker.Dist` and
  `OnlyIn`, which none of those jars carry. The build compiles them onto the compile classpath
  only, and they never go into the mod jar.

## Compile and package

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1
```

The script writes `out/runesmith-<version>.jar` next to `libs/`. The version lives only in
`resources/META-INF/neoforge.mods.toml`.
