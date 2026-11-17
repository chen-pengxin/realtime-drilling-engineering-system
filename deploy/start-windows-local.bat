@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-windows-local.ps1" %*
set "exitCode=%ERRORLEVEL%"
endlocal & exit /b %exitCode%
