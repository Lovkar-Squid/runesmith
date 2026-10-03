# Build both jars and run every scenario defined so far, in order, on the headless rig.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-all.ps1 [-Only a,b] [-Words "rate=60"]
#
# Prints one PASS/FAIL line per scenario and exits 0 only if all of them passed. The research
# scenario "drain" is deliberately not in the list (see the decision log).

param(
  [string[]]$Only,
  [string]$Words = 'rate=60',
  [int]$TimeoutSec = 900
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
# powershell -File passes "-Only a,b" as one string: split it here
$scenarios = if ($Only) { @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) } else { @('boot', 'rules', 'anvil', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'n') }

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here 'build.ps1')
if ($LASTEXITCODE) { throw 'build.ps1 failed' }
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here 'build-harness.ps1')
if ($LASTEXITCODE) { throw 'build-harness.ps1 failed' }

$failed = @()
foreach ($s in $scenarios) {
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here 'run-scenario.ps1') -Name $s -Words $Words -TimeoutSec $TimeoutSec
  $code = $LASTEXITCODE
  $result = ($out | Where-Object { $_ -match 'RESULT ' } | Select-Object -Last 1)
  if ($code -eq 0) { "{0,-6} PASS  {1}" -f $s, $result } else { $failed += $s; "{0,-6} FAIL  {1}" -f $s, $result; $out | Where-Object { $_ -match 'FAIL|ERROR|Exception' } | Select-Object -First 12 }
}
if ($failed.Count) { "FAILED: $($failed -join ', ')"; exit 1 }
'ALL SCENARIOS PASS'
exit 0
