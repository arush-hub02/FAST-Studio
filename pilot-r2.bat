@echo off
rem Opens the R2 dashboard (Cloudflare Local Explorer) of the PILOT (production) environment.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\r2-explorer.ps1" -Env prod
if errorlevel 1 pause
