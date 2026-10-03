@echo off
title Install ZenFone Remote Host (Magisk Module)
echo ========================================================
echo   ZenFone Headless Remote Host - Magisk Installer
echo ========================================================
echo.

echo Checking for connected ZenFone...
adb devices
echo.

echo Pushing zenfone_remote_host.zip to ZenFone...
adb push "%~dp0zenfone_remote_host.zip" /data/local/tmp/zenfone_remote_host.zip
if %errorlevel% neq 0 (
    echo [ERROR] Failed to push module. Make sure ZenFone is connected.
    pause
    exit /b 1
)

echo Installing Magisk module...
adb shell "su -c 'magisk --install-module /data/local/tmp/zenfone_remote_host.zip'"
if %errorlevel% neq 0 (
    echo [ERROR] Module installation failed.
    pause
    exit /b 1
)

echo.
echo [✓] Module installed successfully!
echo Rebooting ZenFone now to activate auto-boot service...
adb reboot
echo.
echo ZenFone is rebooting. It will auto-connect to Wi-Fi and start the host service.
pause
