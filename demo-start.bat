@echo off
rem Starts FAST Studio in DEMO mode (sample data in data\). Stops the pilot first if it is running.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\env-start.ps1" -Mode demo
if errorlevel 1 pause
