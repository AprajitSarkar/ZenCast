@echo off
title ASUS ZenFone Max Pro M1 - Low Latency Hardware Mirror (scrcpy)
echo ========================================================
echo   ASUS ZenFone Max Pro M1 - Ultra-Low-Latency Mirror
echo ========================================================
echo.

set SCRCPY="C:\Users\Aprajit\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1\scrcpy.exe"
if not exist %SCRCPY% (
    set SCRCPY="C:\Users\Aprajit\androidtool\Adb&Fastboot\codexadb\ASUS_X00TD\scrcpy_app\scrcpy.exe"
)

echo Checking for connected ZenFone...
adb devices
echo.

:: Detect ZenFone Wi-Fi IP if connected via USB
for /f "tokens=2 delims= " %%I in ('adb -s J9AAGF022285X7B shell "ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2" 2^>nul') do set ZEN_IP=%%I
if "%ZEN_IP%"=="" set ZEN_IP=192.168.1.129

:: Low-latency tuning flags for Snapdragon 636/660:
:: --no-audio: prevents opus audio encoding CPU lag and audio sync buffering
:: --video-buffer=0: zero delay, real-time display
:: --max-size=1440: downsamples 2160p to 1440p to prevent hardware encoder saturation
:: -b 8M: optimal 8Mbps bitrate for crisp FHD without packet queuing
:: --video-codec-options=i-frame-interval=1: 1s GOP eliminates frame delay
set OPTS=--stay-awake --no-audio --video-codec=h264 --video-encoder=OMX.qcom.video.encoder.avc --max-fps=60 -b 8M --max-size=1440 --video-buffer=0 --video-codec-options=i-frame-interval=1

adb -s J9AAGF022285X7B get-state >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Connected via USB (ASUS ZenFone Max Pro M1).
    echo [OK] Starting ultra-low-latency 60fps mirror (0-buffer, HW accelerated)...
    %SCRCPY% -s J9AAGF022285X7B %OPTS%
    goto END
)

adb -s bf2bc431 get-state >nul 2>&1
if %errorlevel% equ 0 (
    echo [OK] Connected via USB (Redmi Note 7S).
    echo [OK] Starting ultra-low-latency 60fps mirror (0-buffer, HW accelerated)...
    %SCRCPY% -s bf2bc431 %OPTS%
    goto END
)

echo [*] USB not detected. Connecting wirelessly to %ZEN_IP%:5555...
adb connect %ZEN_IP%:5555
echo [OK] Starting wireless ultra-low-latency 60fps mirror on %ZEN_IP%:5555...
%SCRCPY% -s %ZEN_IP%:5555 %OPTS%

:END

pause
