@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-one-click.ps1" -OpenBrowser
if errorlevel 1 (
  echo.
  echo 项目启动失败。请查看上方错误信息，或运行 scripts\start-one-click.ps1 获取同样的日志。
  pause
  exit /b 1
)
echo.
echo 项目已启动。此窗口可以关闭，应用进程会继续运行。
pause
