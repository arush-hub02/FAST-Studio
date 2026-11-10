# Opens Cloudflare's Local Explorer on the local R2 of production (data-pilot\edge) or the demo (data\edge):
# browse folders, upload, add directories, download, delete, object details (the R2 dashboard, locally).
#   pilot-r2.bat (production) or demo-r2.bat; to close the explorers started here: powershell tools\r2-explorer.ps1 -Env stop
# If that environment's edge is running (FAST Studio started), its explorer on port 8787 is used. Otherwise a
# browse-only edge for it starts on port 8798 (production) or 8797 (demo); start.bat, demo-start.bat and pilot-start.bat close it before starting.
param([ValidateSet('prod', 'production', 'pilot', 'demo', 'stop')][string]$Env = 'prod', [switch]$Quiet)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $root 'data\runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null

function Stop-Explorers {
    foreach ($name in 'demo', 'prod') {
        $state = Join-Path $runtime "r2-explorer-$name.json"
        if (!(Test-Path -LiteralPath $state)) { continue }
        $s = Get-Content -LiteralPath $state -Raw | ConvertFrom-Json
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($s.pid)" -ErrorAction SilentlyContinue
        # only the process this script started (same id and start time), with its child processes (workerd)
        if ($p -and $p.CreationDate.ToUniversalTime().ToString('o') -eq $s.createdAt) {
            Get-CimInstance Win32_Process -Filter "ParentProcessId=$($s.pid)" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Stop-Process -Id $s.pid -Force -ErrorAction SilentlyContinue
            if (!$Quiet) { Write-Host "Closed the $name R2 explorer." }
        }
        Remove-Item -LiteralPath $state -Force
    }
}

if ($Env -eq 'stop') { Stop-Explorers; exit 0 }
$name = if ($Env -eq 'demo') { 'demo' } else { 'prod' }
$label = if ($name -eq 'demo') { 'Demo' } else { 'Production' }
$edgeData = Join-Path $root $(if ($name -eq 'demo') { 'data\edge' } else { 'data-pilot\edge' })
if (!(Test-Path -LiteralPath (Join-Path $edgeData 'v3\r2'))) { throw "$label has no local R2 yet ($edgeData). Start FAST Studio in that mode once." }

# an edge already serving this environment's R2 (FAST Studio's, or one this script started)
$port = $null
foreach ($p in Get-CimInstance Win32_Process -Filter "Name='node.exe'") {
    $cmd = [string]$p.CommandLine
    if ($cmd -match 'wrangler' -and $cmd -match ' dev ' -and $cmd.Replace('/', '\').ToLower().Contains($edgeData.ToLower())) {
        if ($cmd -match '--port\s+(\d+)') { $port = [int]$Matches[1] } else { $port = 8787 }
        break
    }
}

if (!$port) {
    $port = if ($name -eq 'demo') { 8797 } else { 8798 }
    $worker = Join-Path $root 'worker'
    $wrangler = Join-Path $worker 'node_modules\wrangler\bin\wrangler.js'
    if (!(Test-Path -LiteralPath $wrangler)) { throw "Wrangler isn't installed: run 'npm install' in $worker." }
    $env:WRANGLER_SEND_METRICS = 'false'
    $log = Join-Path $runtime "r2-explorer-$name.log"
    $proc = Start-Process -FilePath (Get-Command node.exe).Source -WorkingDirectory $worker -WindowStyle Hidden -PassThru `
        -ArgumentList @(('"' + $wrangler + '"'), 'dev', '--ip', '127.0.0.1', '--port', "$port", '--persist-to', ('"' + $edgeData + '"')) `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $created = (Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)").CreationDate.ToUniversalTime().ToString('o')
    @{ pid = $proc.Id; createdAt = $created; port = $port } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime "r2-explorer-$name.json") -Encoding UTF8
    if (!$Quiet) { Write-Host "Starting a browse-only edge for $label R2 on port $port (can take a minute)..." }
    $deadline = (Get-Date).AddSeconds(240)
    while ($true) {
        try { Invoke-WebRequest -Uri "http://127.0.0.1:$port/cdn-cgi/local/explorer" -UseBasicParsing -TimeoutSec 2 | Out-Null; break } catch {}
        $proc.Refresh()
        if ($proc.HasExited) { throw "The edge exited. See $log.err" }
        if ((Get-Date) -gt $deadline) { throw "The edge did not start. See $log" }
        Start-Sleep -Seconds 1
    }
}

$url = "http://127.0.0.1:$port/cdn-cgi/local/explorer/r2/fast-studio-media?delimiter=true"
Write-Host "$label R2: $url"
Write-Host 'Careful: deleting a folder a video, ad or slate uses breaks its playback (check with: node tools\r2-local.mjs --usage).'
if (!$Quiet) { Start-Process $url }
