$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$backendDir = Join-Path $projectRoot 'backend'
$jarPath = Join-Path $backendDir 'target\backend-0.1.0.jar'
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

if (Get-Command docker.exe -ErrorAction SilentlyContinue) {
    $oldPassword = $env:FASTSTUDIO_DB_PASSWORD
    try {
        $env:FASTSTUDIO_DB_PASSWORD = 'configuration-check-only'
        & docker.exe compose --file (Join-Path $projectRoot 'compose.yaml') config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
        Write-Host 'PASS: Docker Compose configuration (no Docker engine needed).'
    } finally { $env:FASTSTUDIO_DB_PASSWORD = $oldPassword }
}
