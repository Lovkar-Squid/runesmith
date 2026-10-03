# Build the headless test harness (mod id runesmithtest) into ..\..\out\runesmithtest-<version>.jar.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-harness.ps1
#
# Compiles tools\harness\*.java against the stubs, libs\ and - when tools\build.ps1 has run - the
# Runesmith classes in build\classes. Same JDK/libs rules as tools\build.ps1.

$ErrorActionPreference = 'Stop'
$repo  = Split-Path -Parent $PSScriptRoot
$work  = (Resolve-Path (Join-Path $repo '..\..')).Path
$jdk   = if ($env:RUNESMITH_JDK)  { $env:RUNESMITH_JDK }  else { Join-Path $work 'tools\jdk21' }
$libs  = if ($env:RUNESMITH_LIBS) { $env:RUNESMITH_LIBS } else { Join-Path $work 'libs' }
$javac = Join-Path $jdk 'bin\javac.exe'
$jarx  = Join-Path $jdk 'bin\jar.exe'
$src   = Join-Path $repo 'tools\harness'

# NeoForge's patched Minecraft classes (neoforge-*-client.jar) must come before the vanilla client jar:
# libs\* lists jars in no fixed order, and the vanilla ItemStack/AnvilMenu lack NeoForge's methods.
$patched = (Get-ChildItem $libs -Filter 'neoforge-*-client.jar' | Select-Object -First 1 | ForEach-Object { $_.FullName + ';' })
$out   = Join-Path $repo 'build\harness'
$stubs = Join-Path $out 'stubs'; $classes = Join-Path $out 'classes'; $res = Join-Path $out 'res'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory $stubs, $classes, (Join-Path $res 'META-INF') | Out-Null

function Write-Args($files, $path) {
  $list = @($files | ForEach-Object { $_.FullName.Replace('\', '/') })
  if ($list.Count -eq 0) { throw "no .java files for $path" }
  $list | Set-Content -Encoding ascii $path
}

Write-Args (Get-ChildItem (Join-Path $repo 'stubsrc') -Recurse -Filter *.java) (Join-Path $out 'stubs.args')
& $javac -encoding UTF-8 --release 21 -proc:none -nowarn -cp "$libs\*" -d $stubs "@$out\stubs.args"
if ($LASTEXITCODE) { throw 'stubs failed' }

$cp = "$stubs;$patched$libs\*"
$modClasses = Join-Path $repo 'build\classes'
if (Test-Path $modClasses) { $cp = "$modClasses;$cp" }
Write-Args (Get-ChildItem $src -Filter *.java) (Join-Path $out 'src.args')
& $javac -g -encoding UTF-8 --release 21 -proc:none -Xlint:-options -cp $cp -d $classes "@$out\src.args"
if ($LASTEXITCODE) { throw 'javac failed' }

$toml = Join-Path $src 'neoforge.mods.toml'
Copy-Item $toml (Join-Path $res 'META-INF\neoforge.mods.toml')
$ver = [regex]::Match((Get-Content $toml -Raw), '(?m)^version="([^"]+)"').Groups[1].Value
New-Item -ItemType Directory -Force (Join-Path $work 'out') | Out-Null
$jar = Join-Path $work "out\runesmithtest-$ver.jar"
if (Test-Path $jar) { Remove-Item $jar -Force }
& $jarx cf $jar -C $classes . -C $res .
if ($LASTEXITCODE) { throw 'jar failed' }
"built $jar ($((Get-Item $jar).Length) bytes)"
