@echo off
rem Compatibility entry point; startup and shutdown are documented in README.md.
call "%~dp0start.bat" %*
exit /b %errorlevel%
