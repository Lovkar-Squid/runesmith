# Build Runesmith with plain javac + jar (no Gradle, official Mojang names, no mappings step).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1
#
# Runs from any directory. JDK and libs default to ..\..\tools\jdk21 and ..\..\libs relative to the
# repository; override with RUNESMITH_JDK / RUNESMITH_LIBS. Writes ..\..\out\runesmith-<version>.jar.
# The stubs (stubsrc\) go on the compile classpath only, never into the jar.

$ErrorActionPreference = 'Stop'
$repo  = Split-Path -Parent $PSScriptRoot
$work  = (Resolve-Path (Join-Path $repo '..\..')).Path
$jdk   = if ($env:RUNESMITH_JDK)  { $env:RUNESMITH_JDK }  else { Join-Path $work 'tools\jdk21' }
$libs  = if ($env:RUNESMITH_LIBS) { $env:RUNESMITH_LIBS } else { Join-Path $work 'libs' }
$javac = Join-Path $jdk 'bin\javac.exe'
$jarx  = Join-Path $jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "no javac at $javac (set RUNESMITH_JDK)" }
if (-not (Test-Path $libs))  { throw "no libs folder at $libs (set RUNESMITH_LIBS)" }

# NeoForge's patched Minecraft classes (neoforge-*-client.jar) must come before the vanilla client jar:
# libs\* lists jars in no fixed order, and the vanilla ItemStack/AnvilMenu lack NeoForge's methods.
$patched = (Get-ChildItem $libs -Filter 'neoforge-*-client.jar' | Select-Object -First 1 | ForEach-Object { $_.FullName + ';' })

$build = Join-Path $repo 'build'; $stubs = Join-Path $build 'stubs'; $classes = Join-Path $build 'classes'
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory $stubs, $classes | Out-Null

# javac @argfiles treat backslashes as escapes: write forward slashes.
function Write-Args($files, $path) {
  $list = @($files | ForEach-Object { $_.FullName.Replace('\', '/') })
  if ($list.Count -eq 0) { throw "no .java files for $path" }
  $list | Set-Content -Encoding ascii $path
}

Write-Args (Get-ChildItem (Join-Path $repo 'stubsrc') -Recurse -Filter *.java) (Join-Path $build 'stubs.args')
& $javac -encoding UTF-8 --release 21 -proc:none -nowarn -cp "$libs\*" -d $stubs "@$build\stubs.args"
if ($LASTEXITCODE) { throw 'stubs failed' }

Write-Args (Get-ChildItem (Join-Path $repo 'src') -Recurse -Filter *.java) (Join-Path $build 'src.args')
& $javac -g -encoding UTF-8 --release 21 -proc:none -Xlint:-options -cp "$stubs;$patched$libs\*" -d $classes "@$build\src.args"
if ($LASTEXITCODE) { throw 'javac failed' }

$toml = Get-Content (Join-Path $repo 'resources\META-INF\neoforge.mods.toml') -Raw
$ver = [regex]::Match($toml, '(?m)^version="([^"]+)"').Groups[1].Value
if (-not $ver) { throw 'no version= line in neoforge.mods.toml' }

New-Item -ItemType Directory -Force (Join-Path $work 'out') | Out-Null
$jar = Join-Path $work "out\runesmith-$ver.jar"
if (Test-Path $jar) { Remove-Item $jar -Force }
& $jarx cf $jar -C $classes . -C (Join-Path $repo 'resources') .
if ($LASTEXITCODE) { throw 'jar failed' }
"built $jar ($((Get-Item $jar).Length) bytes)"
