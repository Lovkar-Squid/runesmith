# Run one headless scenario on the test server in ..\..\rig and report.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-scenario.ps1 -Name boot [-Words "rate=100"] [-TimeoutSec 420] [-KeepWorld] [-Rig rig]
#
# Copies the current Runesmith and harness jars from ..\..\out into rig\mods, wipes rig\world unless
# -KeepWorld, writes rig\runesmith-test.txt, starts the server and waits for the harness's RESULT line
# (the harness stops the server itself; on timeout only this server process tree is killed).
# Prints every [runesmithtest] line plus every ERROR/Exception line that mentions runesmith or
# minecolonies. Exit 0 only for RESULT PASS with no such error line.

param(
  [Parameter(Mandatory = $true)][string]$Name,
  [string]$Words = '',
  [int]$TimeoutSec = 420,
  [switch]$KeepWorld,
  # another server folder next to rig\ (the final compatibility run uses a copy with a modpack's mods)
  [string]$Rig = 'rig'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$work = (Resolve-Path (Join-Path $repo '..\..')).Path
$rigName = $Rig                    # PowerShell names are case-insensitive: $rig below replaces $Rig
$rig  = Join-Path $work $rigName
$jdk  = if ($env:RUNESMITH_JDK) { $env:RUNESMITH_JDK } else { Join-Path $work 'tools\jdk21' }
$java = Join-Path $jdk 'bin\java.exe'
$mods = Join-Path $rig 'mods'
if (-not (Test-Path (Join-Path $rig 'libraries'))) { throw "no server installed in $rig" }

# our jars only: the newest of each kind from out\
Get-ChildItem $mods -Filter 'runesmith*.jar' | Remove-Item -Force
foreach ($pattern in 'runesmith-*.jar', 'runesmithtest-*.jar') {
  $jar = Get-ChildItem (Join-Path $work 'out') -Filter $pattern -ErrorAction SilentlyContinue |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($jar) { Copy-Item $jar.FullName $mods; "mod: $($jar.Name)" }
}

if (-not $KeepWorld) {
  $world = Join-Path $rig 'world'
  if (Test-Path $world) {
    $full = (Resolve-Path $world).Path
    if (-not $full.EndsWith("\$rigName\world")) { throw "refusing to delete $full" }
    Remove-Item $full -Recurse -Force
  }
}

Set-Content -Encoding ascii (Join-Path $rig 'runesmith-test.txt') ("scenario=$Name $Words").Trim()
$log = Join-Path $rig 'logs\latest.log'
if (Test-Path $log) { Remove-Item $log -Force }

$argsLine = '@user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.249/win_args.txt nogui'
$proc = Start-Process -FilePath $java -ArgumentList $argsLine -WorkingDirectory $rig -PassThru -WindowStyle Hidden `
          -RedirectStandardOutput (Join-Path $rig 'stdout.log') -RedirectStandardError (Join-Path $rig 'stderr.log')
"server pid $($proc.Id), scenario '$Name' $Words, timeout ${TimeoutSec}s"

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$result = $null
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 2
  if (Test-Path $log) {
    $hit = Select-String -Path $log -Pattern '\[runesmithtest\] RESULT' -SimpleMatch:$false | Select-Object -Last 1
    if ($hit) { $result = $hit.Line; break }
  }
  if ($proc.HasExited) { break }
}
if (-not $result -and -not $proc.HasExited) {
  "TIMEOUT after ${TimeoutSec}s - killing server pid $($proc.Id)"
  & taskkill /PID $proc.Id /T /F | Out-Null
}
# give the server a moment to stop by itself and flush the log
$stopBy = (Get-Date).AddSeconds(60)
while (-not $proc.HasExited -and (Get-Date) -lt $stopBy) { Start-Sleep -Seconds 1 }
if (-not $proc.HasExited) { & taskkill /PID $proc.Id /T /F | Out-Null }

$lines = @()
if (Test-Path $log) {
  $lines = Get-Content $log
  # keep each scenario's log: the next run starts a fresh latest.log
  Copy-Item $log (Join-Path $rig ("logs\scenario-{0}{1}.log" -f $Name, $(if ($KeepWorld) { '-keep' } else { '' }))) -Force
}
$ours = $lines | Where-Object { $_ -match '\[runesmithtest\]' }
$errors = $lines | Where-Object { ($_ -match 'ERROR|Exception') -and ($_ -match '(?i)runesmith|minecolonies') }
'---- harness'
$ours | ForEach-Object { ($_ -replace '^.*?\[runesmithtest\] ', '') }
'---- errors mentioning runesmith/minecolonies: ' + @($errors).Count
$errors | Select-Object -First 40
if ($result -and $result -match 'RESULT \S+: PASS' -and @($errors).Count -eq 0) { 'SCENARIO PASS'; exit 0 }
'SCENARIO FAIL'
exit 1
