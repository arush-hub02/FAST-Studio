$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$backendDir = Join-Path $projectRoot 'backend'
$jarPath = Join-Path $backendDir 'target\backend-0.1.0.jar'
$runtimeDir = Join-Path $projectRoot 'data\runtime'
$releaseDir = Join-Path $runtimeDir 'releases'
$appUrl = 'http://localhost:8080'
$tokens = $null
$parseErrors = $null
$syntax = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'manage-local.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }

# Load only the ownership guard, without executing start/stop actions.
$guard = $syntax.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-WorkspaceProcess' }, $true)
Invoke-Expression $guard.Extent.Text
$decoder = $syntax.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'ConvertFrom-JavaProperty' }, $true)
Invoke-Expression $decoder.Extent.Text
if ((ConvertFrom-JavaProperty 'D\:\\Fast Product\\backend') -ne 'D:\Fast Product\backend') { throw 'Java property path escaping was not decoded.' }
if ((ConvertFrom-JavaProperty '\u0044\:\\backend') -ne 'D:\backend') { throw 'Java Unicode property escaping was not decoded.' }
function Get-CimInstance { param($ClassName, $Filter) return $script:fixture }
try {
    $script:fixture = [pscustomobject]@{ Name = 'java.exe'; CommandLine = 'java -jar "' + $jarPath + '"'; ExecutablePath = 'C:\java\bin\java.exe' }
    if (!(Get-WorkspaceProcess 123)) { throw 'Did not recognize a quoted workspace jar path.' }
    $script:fixture.CommandLine = 'java -jar "' + (Join-Path $releaseDir (('a' * 64) + '.jar')) + '"'
    if (!(Get-WorkspaceProcess 123)) { throw 'Did not recognize this workspace runtime release.' }
    $script:fixture.CommandLine = 'java -jar "C:\another-project\releases\' + ('a' * 64) + '.jar"'
    if (Get-WorkspaceProcess 123) { throw 'Accepted another workspace runtime release.' }
    $script:fixture.CommandLine = 'java -jar "C:\another-project\backend-0.1.0.jar"'
    if (Get-WorkspaceProcess 123) { throw 'Accepted a different workspace.' }
    $script:fixture.CommandLine = 'java -jar "' + $jarPath + '.backup"'
    if (Get-WorkspaceProcess 123) { throw 'Accepted a partial jar path.' }
    $script:fixture.Name = 'not-java.exe'
    $script:fixture.CommandLine = 'java -jar "' + $jarPath + '"'
    if (Get-WorkspaceProcess 123) { throw 'Accepted a non-Java executable.' }
    $script:fixture = $null
    if (Get-WorkspaceProcess 123) { throw 'Accepted a missing process.' }
} finally { Remove-Item Function:\Get-CimInstance }
Write-Host 'PASS: PowerShell syntax and workspace process ownership checks.'

foreach ($name in @('New-RuntimeJar', 'Test-ApplicationReady', 'Find-CompatibleNode')) {
    $definition = $syntax.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    if (!$definition) { throw "Missing launcher function $name" }
    Invoke-Expression $definition.Extent.Text
}

$fixtureDir = Join-Path $runtimeDir ('launcher-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $fixtureDir | Out-Null
$originalJarPath = $jarPath
$originalReleaseDir = $releaseDir
try {
    $jarPath = Join-Path $fixtureDir 'build.jar'
    $releaseDir = Join-Path $fixtureDir 'releases'
    [IO.File]::WriteAllText($jarPath, 'first build')
    $first = New-RuntimeJar
    [IO.File]::WriteAllText($jarPath, 'second build')
    $second = New-RuntimeJar
    if ($first -eq $second -or [IO.File]::ReadAllText($first) -ne 'first build') { throw 'Rebuilding changed the running release.' }
    if ([IO.File]::ReadAllText($second) -ne 'second build') { throw 'New build was not copied to a new release.' }
    if ((New-RuntimeJar) -ne $second) { throw 'Identical builds should reuse an existing verified release.' }
} finally { $jarPath = $originalJarPath; $releaseDir = $originalReleaseDir }
Write-Host 'PASS: runtime releases stay unchanged when the build JAR is replaced.'

function Invoke-WebRequest {
    param($Uri, [switch]$UseBasicParsing, $TimeoutSec)
    if ($Uri -eq "$appUrl/") { return $script:uiFixture }
    return $script:apiFixture
}
try {
    $script:apiFixture = [pscustomobject]@{ StatusCode = 200 }
    $script:uiFixture = [pscustomobject]@{ StatusCode = 500; Headers = @{'Content-Type'='application/json'}; Content = '{}' }
    if (Test-ApplicationReady) { throw 'An API-only healthy process with a broken UI was accepted.' }
    $script:uiFixture = [pscustomobject]@{ StatusCode = 200; Headers = @{'Content-Type'='text/html'}; Content = '<div id="root"></div>' }
    if (!(Test-ApplicationReady)) { throw 'Healthy UI and API were rejected.' }
    $script:uiFixture = [pscustomobject]@{ StatusCode = 200; Headers = @{'Content-Type'='application/json'}; Content = '{}' }
    if (Test-ApplicationReady) { throw 'A JSON response was accepted as the UI.' }
    $script:uiFixture = [pscustomobject]@{ StatusCode = 200; Headers = @{'Content-Type'='text/html'}; Content = '<div id="root"></div>' }
    $script:apiFixture = [pscustomobject]@{ StatusCode = 503 }
    if (Test-ApplicationReady) { throw 'An unhealthy API was accepted.' }
} finally { Remove-Item Function:\Invoke-WebRequest }
Write-Host 'PASS: readiness requires both the HTML application and API.'

$nodeRoot = Join-Path $fixtureDir 'nvm'
foreach ($version in @('16.20.2', '22.11.0', '24.22.1')) {
    $versionDir = Join-Path $nodeRoot ('v' + $version)
    New-Item -ItemType Directory -Force -Path $versionDir | Out-Null
    [IO.File]::WriteAllText((Join-Path $versionDir 'node.exe'), '')
}
if ((Find-CompatibleNode @($nodeRoot)) -ne (Join-Path $nodeRoot 'v24.22.1\node.exe')) { throw 'Did not select the installed compatible Node version.' }
if (Find-CompatibleNode @((Join-Path $fixtureDir 'missing'))) { throw 'Found a Node runtime in a missing directory.' }
Write-Host 'PASS: installed Node selection excludes incompatible versions.'

if (Get-Command docker.exe -ErrorAction SilentlyContinue) {
    $oldPassword = $env:FASTSTUDIO_DB_PASSWORD
    try {
        $env:FASTSTUDIO_DB_PASSWORD = 'configuration-check-only'
        & docker.exe compose --file (Join-Path $projectRoot 'compose.yaml') config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
        Write-Host 'PASS: Docker Compose configuration (no Docker engine needed).'
    } finally { $env:FASTSTUDIO_DB_PASSWORD = $oldPassword }
}
