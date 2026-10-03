@echo off
title Install ZenCast App to Poco F5
echo ========================================================
echo   ZenCast Remote Viewer - Installer for Poco F5
echo ========================================================
echo.

set POCO_DEV=adb-4c1b12c9-xrE3lM._adb-tls-connect._tcp
if not exist "%~dp0ZenCast.apk" (
    echo [ERROR] ZenCast.apk not found in %~dp0
    echo Please run build_apk.bat first.
    pause
    exit /b 1
)

echo Attempting to install ZenCast.apk to Poco F5...
adb -s %POCO_DEV% install -r "%~dp0ZenCast.apk"
if %errorlevel% neq 0 (
    echo [*] Specific device ID failed. Searching for any connected wireless/USB device...
    adb install -r "%~dp0ZenCast.apk"
)

echo.
echo [✓] Done! Open "ZenCast" on your Poco F5 to start viewing.
pause
