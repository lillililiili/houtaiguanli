@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-one-click.ps1"
if errorlevel 1 (
  echo.
  echo Stop failed. Please review the error above.
  pause
  exit /b 1
)
echo.
echo Services stopped. Database files and simulation history are preserved.
pause
