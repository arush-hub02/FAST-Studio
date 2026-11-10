@echo off
rem Starts FAST Studio in PILOT mode (real data in data-pilot\). Stops the demo first if it is running.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\env-start.ps1" -Mode pilot
if errorlevel 1 pause
