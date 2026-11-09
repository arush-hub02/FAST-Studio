@echo off
rem Local R2 dashboard (Cloudflare Local Explorer): r2.bat = production, r2.bat demo, r2.bat stop
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\r2-explorer.ps1" %*
if errorlevel 1 pause
