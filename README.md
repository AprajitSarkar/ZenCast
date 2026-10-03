# ZenCast: Zero-PC Headless Android Motherboard & Broken-Screen Remote Display

[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)](https://developer.android.com)
[![Magisk](https://img.shields.io/badge/Magisk-Root%20Module-orange.svg)](https://github.com/topjohnwu/Magisk)
[![Latency](https://img.shields.io/badge/Latency-%3C15ms-blue.svg)](#performance)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> **Turn any Android phone with a broken, dead, or completely missing display into a powerful headless motherboard server — and mirror/control it directly from another Android phone without needing any PC!**

---

## 🎯 Target Use Case: Headless Motherboard & Dead Screen Recovery

Have a smartphone with a shattered, flickering, or completely detached screen? Don't throw the motherboard away! 

Modern smartphone motherboards (like Snapdragon 636/660/7+ Gen 2/8 Gen 2) are full-fledged ARM computers with powerful CPUs, GPUs, hardware H.264/H.265 video encoders, Wi-Fi, Bluetooth, and battery management.

**ZenCast** bridges the gap:
- **No PC Required**: Control your headless phone motherboard entirely from another Android phone or tablet.
- **Hardware-Accelerated Zero Latency**: Streams video directly from the GPU/HWC framebuffers via Qualcomm/MediaTek hardware encoders (`OMX.qcom.video.encoder.avc`) into a native Android `MediaCodec` SurfaceView with **< 15ms latency**.
- **Interactive Multi-Touch & Hardware Keys**: 1:1 touch mapping with high-frequency move coalescing, physical Back, Volume Up/Down, and Power Menu controls.
- **Draggable Floating Control Widget**: Free drag-and-drop floating icon with quick screen toggles, orientation switcher, clipboard sync, and multi-device switcher.
- **Motherboard Display Timeout Bypass**: Patched SurfaceFlinger configuration (`debug.sf.latch_unsignaled=1`, `debug.sf.disable_backpressure=1`) completely eliminates display-less VSYNC stutter and missed frame lags on headless boards.

---

## 🚀 Key Features

| Feature | Description |
| :--- | :--- |
| **Zero-PC Autonomous Boot** | Headless phone boots up, auto-connects to your Wi-Fi or mobile hotspot, enables permanent Wireless ADB (port 5555), and launches the native streaming daemon in the background. |
| **Draggable Floating Controls** | Draggable floating HUD allows you to reposition the control button anywhere on your screen. Tap to expand into a vertical menu for screen power, power menu, orientation, and clipboard push. |
| **Bidirectional Clipboard Sync** | Automatically syncs clipboard text between your client phone and the headless host in real time. |
| **Instant Multi-Device Switcher** | Discovers multiple headless phones on your local network (e.g. ZenFone Max Pro M1, Redmi Note 7S) and lets you switch between them with a single tap. |
| **Hardware Key Interception** | Physical Volume Up/Down and Back keys on the client phone control the remote host device. |
| **Deep Headless OS Optimization** | Automatically strips background bloat, disables unused camera/webcam daemons, sets UI animation scales to 0, and avoids kernel swap thrashing on 3GB/4GB RAM boards. |

---

## 📦 Releases & Downloads

Pre-built binaries are available in the **Releases** section:

1. **Host Magisk Module (`Headless_Remote_Host.zip` / `zenfone_remote_host.zip`)**:
   - Flash in Magisk on your host phone (the device with the broken screen/motherboard). Located in `Magisk_Module/`.
2. **Client Android App (`ZenCast.apk`)**:
   - Install on your everyday Android phone (the client viewer, e.g. Poco F5, Samsung Galaxy, Pixel).
3. **Optional PC Viewer (`START_SCREEN_MIRROR.bat`)**:
   - Double-click from Windows to mirror the headless phone onto your PC screen over USB or Wi-Fi.

---

## 🛠️ Installation & Setup Guide

### Step 1: Install the Magisk Module on the Host Phone

1. Download **`Headless_Remote_Host.zip`** from `Magisk_Module/`.
2. Configure your Wi-Fi credentials:
   - Copy `host_module/wifi.conf.example` to `wifi.conf` on your device or in `/data/adb/mercor_wifi.conf`:
     ```sh
     WIFI_1_SSID="MyHomeWiFi"
     WIFI_1_KEY_MGMT="wpa2"
     WIFI_1_PASSWORD="MySecurePassword"

     WIFI_2_SSID="MyPhoneHotspot"
     WIFI_2_KEY_MGMT="wpa2"
     WIFI_2_PASSWORD="HotspotPassword"
     ```
3. Flash the module zip in Magisk Manager (or via ADB: `adb push Magisk_Module/zenfone_remote_host.zip /data/local/tmp/ && adb shell su -c "magisk --install-module /data/local/tmp/zenfone_remote_host.zip"`).
4. Reboot the host phone. It will automatically connect to your Wi-Fi/Hotspot and start the native daemon on boot!

### Step 2: Install the Client App on Your Viewer Phone

1. Download and install **`ZenCast.apk`** on your viewer phone (e.g. Poco F5).
2. Connect your viewer phone to the same Wi-Fi network (or turn on your Hotspot).
3. Open **ZenCast**:
   - The app will automatically discover the headless motherboard via UDP beacons.
   - The remote screen appears instantly in full-screen, ultra-low-latency 60 FPS!

---

## 🎮 In-Stream Controls & Draggable HUD

- **Draggable Icon**: Touch and drag the floating icon anywhere on your screen to keep your view unobstructed.
- **Tap Icon**: Expands the quick-action menu:
  - 🔒 **Display Power Toggle**: Shuts off the physical display power on the host (saves battery/heat) while keeping remote streaming active.
  - ⚡ **Power Menu**: Triggers the host's native Reboot / Shutdown modal.
  - 🔄 **Rotate Screen**: Toggles portrait / landscape orientation.
  - 📋 **Push Clipboard**: Sends current client clipboard to host.
  - 🔁 **Device Switcher**: Lists all detected headless host motherboards on your Wi-Fi network.
- **Physical Keys**: Pressing hardware Volume or Back keys sends the events to the host. Double-tap Back quickly to exit the client app.

---

## 🔍 SEO & Discovery Keywords

`headless android` · `broken screen recovery` · `broken phone screen mirror` · `motherboard phone server` · `android kvm without pc` · `phone to phone screen share` · `scrcpy android client` · `use phone motherboard as computer` · `otg dead display screen viewer` · `magisk scrcpy host server` · `zero pc android remote control` · `snapdragon headless host`

---

## 📄 License

This project is licensed under the Apache 2.0 License.
Scrcpy server binaries are property of Genymobile and licensed under Apache 2.0.
