# One-click start of FAST Studio in one mode: demo (data\) or pilot (data-pilot\, real data). Demo and pilot share
# ports 8080 and 8787, so only one runs at a time: if the other mode is running it is stopped first (cleanly), then this one starts.
param([Parameter(Mandatory = $true)][ValidateSet('demo', 'pilot')][string]$Mode)
$ErrorActionPreference = 'Stop'
$manage = Join-Path $PSScriptRoot 'manage-local.ps1'
$state = Join-Path (Split-Path $PSScriptRoot -Parent) 'data\runtime\app.json'
if (Test-Path -LiteralPath $state) {
    $running = if ((Get-Content -LiteralPath $state -Raw | ConvertFrom-Json).pilot) { 'pilot' } else { 'demo' }
    if ($running -ne $Mode -and (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
        Write-Host "The $running environment is running; stopping it first (its data is kept)..."
        & $manage -Action Stop
    }
}
if ($Mode -eq 'pilot') { & $manage -Action Start -Pilot } else { & $manage -Action Start }
