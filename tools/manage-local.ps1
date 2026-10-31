param(
    [Parameter(Mandatory = $true)][ValidateSet('Start', 'Stop')][string]$Action,
    [switch]$Docker,
    [switch]$NoBrowser,
    # Real-data pilot: no demo content, own data folder (data-pilot), stable media secret, first admin from pilot.env
    [switch]$Pilot
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path $PSScriptRoot -Parent
$backendDir = Join-Path $projectRoot 'backend'
$workerDir = Join-Path $projectRoot 'worker'
$jarPath = Join-Path $backendDir 'target\backend-0.1.0.jar'
$runtimeDir = Join-Path $projectRoot 'data\runtime'
$statePath = Join-Path $runtimeDir 'app.json'
$credentialsPath = Join-Path $runtimeDir 'docker.env'
$composePath = Join-Path $projectRoot 'compose.yaml'
$appUrl = 'http://localhost:8080'

function ConvertFrom-JavaProperty([string]$Value) {
    return [regex]::Replace($Value, '\\(u[0-9a-fA-F]{4}|.)', {
        param($match)
        $escaped = $match.Groups[1].Value
        if ($escaped -cmatch '^u[0-9a-fA-F]{4}$') { return [string][char][Convert]::ToInt32($escaped.Substring(1), 16) }
        return $escaped
    })
}

# Identify the workspace as well as the PID; never stop every Java process.
function Get-WorkspaceProcess([int]$ProcessId) {
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
    if (!$candidate -or $candidate.Name -ne 'java.exe') { return $null }
    $command = $candidate.CommandLine
    if ($command -match ('-jar\s+"?' + [regex]::Escape($jarPath) + '"?(?:\s|$)')) { return $candidate }

    # Recognize the original launcher, which used a relative jar/classpath.
    if ($command -notmatch '(?:-jar\s+target[/\\]backend-0\.1\.0\.jar|com\.faststudio\.BackendApplication)') { return $null }
    $jcmd = Join-Path (Split-Path $candidate.ExecutablePath -Parent) 'jcmd.exe'
    if (!(Test-Path -LiteralPath $jcmd)) { return $null }
    try { $properties = & $jcmd $ProcessId VM.system_properties 2>$null } catch { return $null }
    if ($LASTEXITCODE -ne 0) { return $null }
    $workingDir = ($properties | Where-Object { $_ -like 'user.dir=*' } | Select-Object -First 1) -replace '^user.dir=', ''
    $workingDir = ConvertFrom-JavaProperty $workingDir
    if ($workingDir -and [IO.Path]::GetFullPath($workingDir).TrimEnd('\') -eq $backendDir.TrimEnd('\')) { return $candidate }
    return $null
}

# The local Cloudflare Worker (wrangler dev) this launcher started: node.exe running this workspace's wrangler.
function Get-EdgeProcess([int]$ProcessId, [string]$CreatedAt) {
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
    if (!$candidate -or $candidate.Name -ne 'node.exe') { return $null }
    if ($candidate.CommandLine -notlike ('*' + (Join-Path $workerDir 'node_modules\wrangler') + '*')) { return $null }
    if ($candidate.CreationDate.ToUniversalTime().ToString('o') -ne $CreatedAt) { return $null }
    return $candidate
}

function Stop-Edge($State) {
    if (!$State -or !$State.edgeProcessId) { return }
    $edge = Get-EdgeProcess ([int]$State.edgeProcessId) $State.edgeCreatedAt
    if ($edge) {
        & taskkill.exe /PID $edge.ProcessId /T /F | Out-Null
        Write-Host 'Local Cloudflare Worker stopped.'
    }
}

function Get-ListeningProcess {
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { return Get-WorkspaceProcess ([int]$listener.OwningProcess) }
    return $null
}

# Loads NAME=value lines into this process's environment (Java inherits them). Blank lines and lines starting with
# '#' are skipped, surrounding double quotes are removed, and an empty value is ignored (it never clears a value
# set earlier, e.g. in .env).
function Import-EnvFile([string]$Path) {
    if (!(Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $text = $line.Trim()
        if (!$text -or $text.StartsWith('#') -or !$text.Contains('=')) { continue }
        $name, $value = $text -split '=', 2
        $name = $name.Trim()
        $value = $value.Trim().Trim('"')
        if ($value) { Set-Item -Path "env:$name" -Value $value }
    }
}

function Invoke-DatabaseCompose([string[]]$ComposeArguments) {
    if (!(Get-Command docker.exe -ErrorAction SilentlyContinue)) { throw 'Docker Desktop is required for -Docker mode.' }
    $ErrorActionPreference = 'Continue'
    & docker.exe info --format '{{.ServerVersion}}' 1>$null 2>$null
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Start Docker Desktop, then try again.' }
    & docker.exe compose --env-file $credentialsPath --file $composePath @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker database operation failed. See the Docker output above.' }
}

try {
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
    # Serialize double-clicks, including while the frontend/Java build is running.
    $launchLock = [IO.File]::Open((Join-Path $runtimeDir 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $state = $null
    if (Test-Path -LiteralPath $statePath) { $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
    $appProcess = $null
    if ($state) {
        $candidate = Get-WorkspaceProcess ([int]$state.processId)
        if ($candidate -and $candidate.CreationDate.ToUniversalTime().ToString('o') -eq $state.createdAt) { $appProcess = $candidate }
    }
    if (!$appProcess) { $appProcess = Get-ListeningProcess }

    if ($Action -eq 'Stop') {
        if ($appProcess) {
            # Shut this workspace's embedded DB down cleanly before terminating Java.
            $dataName = if (($state -and $state.pilot) -or $Pilot) { 'data-pilot' } else { 'data' }
            $pgData = Join-Path $projectRoot "$dataName\pg"
            $pgPidFile = Join-Path $pgData 'postmaster.pid'
            if (Test-Path -LiteralPath $pgPidFile) {
                $pgProcessId = [int](Get-Content -LiteralPath $pgPidFile -TotalCount 1)
                $pgProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $pgProcessId"
                if ($pgProcess -and $pgProcess.Name -eq 'postgres.exe') {
                    $pgArguments = [regex]::Match($pgProcess.CommandLine, '-D\s+"([^"]+)"')
                    if ($pgArguments.Success -and [IO.Path]::GetFullPath($pgArguments.Groups[1].Value) -eq $pgData) {
                        $pgCtl = Join-Path (Split-Path $pgProcess.ExecutablePath -Parent) 'pg_ctl.exe'
                        & $pgCtl -D $pgData stop -m fast -t 30
                        if ($LASTEXITCODE -ne 0) { throw 'Embedded database did not stop cleanly; Java has been left running.' }
                    }
                }
            }
            $current = Get-WorkspaceProcess ([int]$appProcess.ProcessId)
            if (!$current -or $current.CreationDate -ne $appProcess.CreationDate) { throw 'The app process changed during shutdown; no other Java process will be stopped.' }
            & taskkill.exe /PID $appProcess.ProcessId /T /F
            if ($LASTEXITCODE -ne 0) { throw 'Could not stop FAST Studio.' }
            Write-Host 'FAST Studio stopped.'
        } else { Write-Host 'FAST Studio is not running (no verified workspace process found).' }
        Stop-Edge $state
        if ($Docker -or ($state -and $state.docker)) {
            Invoke-DatabaseCompose -ComposeArguments @('stop', '--timeout', '30', 'db')
            Write-Host 'Docker PostgreSQL stopped. Its data volume is retained.'
        }
        if (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
        exit 0
    }

    if ($appProcess) {
        Write-Host "FAST Studio is already running at $appUrl (PID $($appProcess.ProcessId))."
        Write-Host 'Use stop.bat before changing database mode or rebuilding.'
        if (!$NoBrowser) { Start-Process $appUrl }
        exit 0
    }
    if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 8080 is occupied by an unrecognized process; it will not be stopped.'
    }
    foreach ($tool in @('java.exe', 'npm.cmd', 'node.exe')) {
        if (!(Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Missing $tool. Install Java 21+ and Node.js 22+ before starting." }
    }
    $nodeMajor = [int](& node.exe -p "process.versions.node.split('.')[0]")
    if ($nodeMajor -lt 22) { throw "Node.js 22 or newer is required (found $(& node.exe --version)); the local Cloudflare Worker needs it. With nvm: nvm use 24" }

    if ($Pilot -and $Docker) { throw 'Pilot mode uses its own embedded database; -Docker is not supported together with -Pilot yet.' }
    # your own settings (copy .env.example to .env); in pilot mode data-pilot\pilot.env is loaded after it and wins
    Import-EnvFile (Join-Path $projectRoot '.env')
    if ($Pilot) {
        $pilotDir = Join-Path $projectRoot 'data-pilot'
        New-Item -ItemType Directory -Force -Path $pilotDir | Out-Null
        $pilotEnv = Join-Path $pilotDir 'pilot.env'
        if (!(Test-Path -LiteralPath $pilotEnv)) {
            # First pilot start: a stable media-signing secret and a first administrator with a temporary password.
            $secret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
            $adminEmail = if ($env:FASTSTUDIO_ADMIN_EMAIL) { $env:FASTSTUDIO_ADMIN_EMAIL } else { 'admin@pilot.local' }
            $adminPassword = if ($env:FASTSTUDIO_ADMIN_PASSWORD) { $env:FASTSTUDIO_ADMIN_PASSWORD } else { 'Fs-' + [guid]::NewGuid().ToString('N').Substring(0, 16) }
            Set-Content -LiteralPath $pilotEnv -Encoding ASCII -Value @(
                "FASTSTUDIO_MEDIA_SECRET=$secret", "FASTSTUDIO_ADMIN_EMAIL=$adminEmail", "FASTSTUDIO_ADMIN_PASSWORD=$adminPassword",
                '# Generated on the first pilot start: keep this file private. Values here win over .env.',
                '# Email, your address (FASTSTUDIO_APP_URL), HTTPS and the rest: put them in .env (see .env.example).')
            Set-Content -LiteralPath (Join-Path $pilotDir 'FIRST-SIGN-IN.txt') -Encoding UTF8 -Value @(
                'FAST Studio pilot - first sign-in', '',
                "Open $appUrl and sign in as:", "  Email:    $adminEmail", "  Password: $adminPassword", '',
                'You will be asked to choose your own password straight away; after that this temporary password stops working.',
                'Then use Organizations > New organization to create your company and its owner.',
                'Keep data-pilot\pilot.env private: it holds the media-signing secret.')
            Write-Host ''
            Write-Host 'First pilot start - sign in with:' -ForegroundColor Cyan
            Write-Host "  Email:    $adminEmail"
            Write-Host "  Password: $adminPassword   (temporary; also saved in data-pilot\FIRST-SIGN-IN.txt)"
            Write-Host ''
        }
        Import-EnvFile $pilotEnv
        $env:SPRING_PROFILES_ACTIVE = 'pilot'
        $env:FASTSTUDIO_DATA_DIR = $pilotDir
    }

    if ($Docker) {
        if (!(Test-Path -LiteralPath $credentialsPath)) {
            $password = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
            Set-Content -LiteralPath $credentialsPath -Value "FASTSTUDIO_DB_PASSWORD=$password" -Encoding ASCII
        }
        $env:FASTSTUDIO_DB_PASSWORD = (Get-Content -LiteralPath $credentialsPath | Where-Object { $_ -like 'FASTSTUDIO_DB_PASSWORD=*' }) -replace '^FASTSTUDIO_DB_PASSWORD=', ''
        Invoke-DatabaseCompose -ComposeArguments @('up', '-d', '--wait', '--wait-timeout', '60', 'db')
        @{ processId = 0; createdAt = ''; docker = $true } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
        $env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/faststudio'
        $env:DB_USER = 'faststudio'
        $env:DB_PASSWORD = $env:FASTSTUDIO_DB_PASSWORD
    }

    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        if (!(Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    Push-Location (Join-Path $projectRoot 'mailer')
    try {
        if (!(Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw 'Mailer (nodemailer) installation failed.' }
        }
    } finally { Pop-Location }
    Push-Location $workerDir
    try {
        if (!(Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw 'Cloudflare Worker (wrangler) installation failed.' }
        }
    } finally { Pop-Location }
    Push-Location $backendDir
    try {
        & .\mvnw.cmd -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Java build failed.' }
    } finally { Pop-Location }

    # Viewers are served by the Cloudflare Worker (worker/), run locally by wrangler with its R2/KV state in <data>\edge.
    # It shares one secret with Java: generated once into data\runtime\edge.env unless .env sets FASTSTUDIO_EDGE_SECRET.
    if (!$env:FASTSTUDIO_EDGE_SECRET) {
        $edgeEnv = Join-Path $runtimeDir 'edge.env'
        if (!(Test-Path -LiteralPath $edgeEnv)) {
            $edgeSecret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
            Set-Content -LiteralPath $edgeEnv -Encoding ASCII -Value @(
                "FASTSTUDIO_EDGE_SECRET=$edgeSecret",
                '# Generated on the first start: shared by FAST Studio and the local Worker (worker\.dev.vars). Keep it private.')
        }
        Import-EnvFile $edgeEnv
    }
    Set-Content -LiteralPath (Join-Path $workerDir '.dev.vars') -Encoding ASCII -Value "EDGE_SECRET=$env:FASTSTUDIO_EDGE_SECRET"
    Stop-Edge $state   # a Worker left over from an interrupted start
    if (Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 8787 (the local Cloudflare Worker) is occupied by another process; it will not be stopped.'
    }
    $edgeData = Join-Path $projectRoot $(if ($Pilot) { 'data-pilot\edge' } else { 'data\edge' })
    $env:WRANGLER_SEND_METRICS = 'false'
    $wrangler = Join-Path $workerDir 'node_modules\wrangler\bin\wrangler.js'
    $edge = Start-Process -FilePath (Get-Command node.exe).Source -WorkingDirectory $workerDir -WindowStyle Hidden -PassThru `
        -ArgumentList @(('"' + $wrangler + '"'), 'dev', '--ip', '127.0.0.1', '--port', '8787', '--persist-to', ('"' + $edgeData + '"')) `
        -RedirectStandardOutput (Join-Path $runtimeDir 'edge.log') -RedirectStandardError (Join-Path $runtimeDir 'edge-error.log')
    $edgeCreatedAt = (Get-CimInstance Win32_Process -Filter "ProcessId = $($edge.Id)").CreationDate.ToUniversalTime().ToString('o')
    @{ processId = 0; createdAt = ''; docker = [bool]$Docker; pilot = [bool]$Pilot; edgeProcessId = $edge.Id; edgeCreatedAt = $edgeCreatedAt } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    $deadline = (Get-Date).AddSeconds(90)
    while (!(Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue)) {
        $edge.Refresh()
        if ($edge.HasExited) { throw "The local Cloudflare Worker exited. Check $runtimeDir\edge.log and edge-error.log." }
        if ((Get-Date) -gt $deadline) { throw "The local Cloudflare Worker did not start. Check $runtimeDir\edge.log." }
        Start-Sleep -Seconds 1
    }
    Write-Host 'Local Cloudflare Worker ready: http://127.0.0.1:8787'

    $javaPath = (Get-Command java.exe).Source
    $launched = Start-Process -FilePath $javaPath -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'app.log') -RedirectStandardError (Join-Path $runtimeDir 'app-error.log')
    $javaProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($launched.Id)"
    @{ processId = $launched.Id; createdAt = $javaProcess.CreationDate.ToUniversalTime().ToString('o'); docker = [bool]$Docker; pilot = [bool]$Pilot; edgeProcessId = $edge.Id; edgeCreatedAt = $edgeCreatedAt } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    Write-Host ("Starting FAST Studio (" + $(if ($Pilot) { 'real-data pilot, data in data-pilot' } else { 'demo' }) + "). Logs: $runtimeDir")
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        $launched.Refresh()
        if ($launched.HasExited) { throw "Java exited. Check $runtimeDir\app.log and app-error.log." }
        try {
            $response = Invoke-WebRequest 'http://127.0.0.1:8080/api/auth/demo-accounts' -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Host "Ready: $appUrl"
                if (!$NoBrowser) { Start-Process $appUrl }
                exit 0
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "Startup is still pending. Check $runtimeDir\app.log; stop.bat can stop this launch."
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    if ($launchLock) { $launchLock.Dispose() }
}
