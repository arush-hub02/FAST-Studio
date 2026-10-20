param(
    [ValidateSet('Backup', 'Restore')][string]$Action = 'Backup',
    # Back up / restore the real-data pilot (data-pilot) instead of the demo (data)
    [switch]$Pilot,
    # Restore: the backup folder to restore, e.g. backups\pilot-20261020-1530
    [string]$From
)

# Cold backup of the embedded database + media: stop the app, copy the folder, start it again.
# ponytail: cold copy means a short outage (~30 s); switch to pg_dump / PITR once the pilot runs on a managed PostgreSQL.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$launcher = Join-Path $PSScriptRoot 'manage-local.ps1'
$mode = if ($Pilot) { 'pilot' } else { 'demo' }
$dataDir = Join-Path $projectRoot $(if ($Pilot) { 'data-pilot' } else { 'data' })
$backupRoot = Join-Path $projectRoot 'backups'
$statePath = Join-Path $projectRoot 'data\runtime\app.json'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

$state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
if ($state -and $state.docker) { throw 'Docker mode keeps its data in a Docker volume: back it up with "docker exec faststudio-db pg_dump -U faststudio -Fc faststudio > backup.dump".' }
$wasRunning = [bool]$state -and $state.processId -gt 0 -and [bool]$state.pilot -eq [bool]$Pilot
$otherRunning = [bool]$state -and $state.processId -gt 0 -and !$wasRunning
if ($otherRunning) { throw "The $(if ($Pilot) { 'demo' } else { 'pilot' }) app is running; stop it first (stop.bat) or pass the matching -Pilot flag." }

function Copy-Folder([string]$Source, [string]$Target, [switch]$Mirror) {
    # runtime\ holds the launcher's own logs and state, never data
    $flags = @('/E', '/XD', 'runtime', '/NFL', '/NDL', '/NJH', '/NP', '/R:2', '/W:2')
    if ($Mirror) { $flags[0] = '/MIR' }
    & robocopy.exe $Source $Target @flags | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Copy from $Source to $Target failed (robocopy code $LASTEXITCODE)." }
}

function Invoke-Launcher([string]$LauncherAction) {
    $launchArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launcher, '-Action', $LauncherAction, '-NoBrowser')
    if ($Pilot) { $launchArgs += '-Pilot' }
    & powershell.exe @launchArgs
    if ($LASTEXITCODE -ne 0) { throw "Launcher $LauncherAction failed." }
}

if (!(Test-Path -LiteralPath (Join-Path $dataDir 'pg')) -and $Action -eq 'Backup') { throw "Nothing to back up: $dataDir has no database yet." }
if ($Action -eq 'Restore') {
    if (!$From) { throw 'Pass -From <backup folder>. Available: ' + ((Get-ChildItem $backupRoot -Directory -Filter "$mode-*" -ErrorAction SilentlyContinue).Name -join ', ') }
    $From = (Resolve-Path -LiteralPath $From).Path
    if (!(Test-Path -LiteralPath (Join-Path $From 'pg\PG_VERSION'))) { throw "$From does not look like a FAST Studio backup (no pg\PG_VERSION)." }
}

if ($wasRunning) { Write-Host 'Stopping FAST Studio for a consistent copy...'; Invoke-Launcher 'Stop' }
try {
    if ($Action -eq 'Backup') {
        $target = Join-Path $backupRoot "$mode-$stamp"
        Copy-Folder $dataDir $target
        Write-Host "Backup saved: $target"
    } else {
        # keep what we are about to overwrite, so a wrong restore can itself be undone
        if (Test-Path -LiteralPath (Join-Path $dataDir 'pg')) {
            $safety = Join-Path $backupRoot "$mode-before-restore-$stamp"
            Copy-Folder $dataDir $safety
            Write-Host "Current data kept in: $safety"
        }
        Copy-Folder $From $dataDir -Mirror
        Write-Host "Restored $From into $dataDir"
    }
} finally {
    if ($wasRunning) { Write-Host 'Starting FAST Studio again...'; Invoke-Launcher 'Start' }
}
