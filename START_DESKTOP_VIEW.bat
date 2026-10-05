@echo off
title ZenCast - Scrcpy Desktop View Launcher
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_desktop_view.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Scrcpy finished or exited with code %ERRORLEVEL%.
    pause
)
