@echo off
rem Offline build wrapper: works even when PowerShell execution policy is Restricted.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
endlocal
