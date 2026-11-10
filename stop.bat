@echo off
setlocal
rem Stops whichever environment is running (demo or pilot) cleanly, keeping its data, and closes any R2 dashboard started by demo-r2.bat / pilot-r2.bat.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\manage-local.ps1" -Action Stop %*
set "result=%errorlevel%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\r2-explorer.ps1" -Env stop
if not "%result%"=="0" pause
exit /b %result%
