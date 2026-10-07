#!/system/bin/sh
MODDIR="${0%/*}"

# 1. Wait until Android boot process completes
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

LOG="/data/local/tmp/zen_host_service.log"
echo "[$(date)] Android boot completed. Starting ZenFone Headless Host..." > "$LOG"

# 2. Prevent device from sleeping, acquire permanent kernel wake lock, disable doze & screen timeout
echo "zen_headless_wakelock" > /sys/power/wake_lock 2>/dev/null || true
echo "Y" > /sys/module/lpm_levels/parameters/sleep_disabled 2>/dev/null || true
echo "0" > /sys/kernel/power_suspend/power_suspend_mode 2>/dev/null || true
echo 1 > /sys/module/mdss_dsi/parameters/dsi_status_disable 2>/dev/null || true
dumpsys deviceidle disable 2>/dev/null || true
settings put global doze_enabled 0 2>/dev/null || true
settings put secure doze_enabled 0 2>/dev/null || true
cmd deviceidle whitelist +com.mercor.zenhost 2>/dev/null || true

svc power stayon true
settings put global development_settings_enabled 1
settings put global stay_on_while_plugged_in 7
settings put system screen_off_timeout 2147483647
device_config put attention_manager_service enable_flip_to_screen_off false 2>/dev/null || true
device_config set_sync_disabled_for_tests persistent 2>/dev/null || true
settings put secure wake_gesture_enabled 1 2>/dev/null || true
settings put system screen_brightness 150 2>/dev/null || true
echo 150 > /sys/class/leds/lcd-backlight/brightness 2>/dev/null || true
settings put global lockscreen.disabled 1 2>/dev/null || true
wm dismiss-keyguard 2>/dev/null || true
input keyevent 224 2>/dev/null || true
input keyevent 82 2>/dev/null || true

# 3. Headless Performance & SurfaceFlinger Ultra Smooth Tuning
resetprop persist.sys.sf.disable_blurs 1 2>/dev/null || true
resetprop ro.surface_flinger.supports_background_blur 0 2>/dev/null || true
resetprop ro.sf.blurs_are_expensive 1 2>/dev/null || true
setprop debug.sf.disable_client_composition_cache 0
setprop debug.sf.predict_hwc_composition_strategy 1
setprop debug.sf.latch_unsignaled 1
setprop debug.sf.enable_gl_backpressure 0

# GPU Performance Lock (Eliminate Adreno 509 160MHz underclocking)
echo 370 > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null || true
echo 370000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
echo 10000 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null || true
echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null || true
echo 1 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null || true

# CPU Schedutil Responsiveness Tuning (Little: 1.4GHz, Big: 1.4GHz minimum)
echo 1401600 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null || true
echo 1401600 > /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 2>/dev/null || true
echo 500 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us 2>/dev/null || true
echo 30000 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/down_rate_limit_us 2>/dev/null || true
echo 500 > /sys/devices/system/cpu/cpufreq/policy4/schedutil/up_rate_limit_us 2>/dev/null || true
echo 30000 > /sys/devices/system/cpu/cpufreq/policy4/schedutil/down_rate_limit_us 2>/dev/null || true

# Memory / VM Tuning for 3GB RAM devices (Eliminate kswapd0 thrashing)
echo 60 > /proc/sys/vm/swappiness 2>/dev/null || true
echo 100 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null || true
echo 20 > /proc/sys/vm/dirty_ratio 2>/dev/null || true
echo 10 > /proc/sys/vm/dirty_background_ratio 2>/dev/null || true
echo 1 > /proc/sys/vm/compact_memory 2>/dev/null || true

# Disable heavy UI animation overhead and background loops in CherishOS
settings put system network_traffic_enabled 0 2>/dev/null || true
settings put system qs_tile_animation_style 0 2>/dev/null || true
settings put system qs_panel_style 0 2>/dev/null || true
settings put system qs_battery_style 0 2>/dev/null || true

# Disable heavy unused services that hog CPU & RAM on headless phone
pm disable-user --user 0 co.aospa.sense 2>/dev/null || true
pm disable-user --user 0 com.android.DeviceAsWebcam 2>/dev/null || true
pm disable-user --user 0 com.google.android.apps.photos 2>/dev/null || true
pm disable-user --user 0 com.google.android.googlequicksearchbox 2>/dev/null || true
pm disable-user --user 0 com.google.android.as 2>/dev/null || true
pm disable-user --user 0 com.google.android.as.oss 2>/dev/null || true

# 4. Turn OFF Airplane Mode permanently & Enable Wi-Fi
settings put global airplane_mode_on 0
am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>/dev/null || true
cmd connectivity airplane-mode disable 2>/dev/null || true
cmd connectivity airplane-mode enable false 2>/dev/null || true
svc wifi enable
cmd wifi set-wifi-enabled enabled 2>/dev/null || true
iw dev wlan0 set power_save off 2>/dev/null || true

# Load Wi-Fi credentials from configuration file
CONFIG_FILE="$MODDIR/wifi.conf"
if [ ! -f "$CONFIG_FILE" ]; then
    CONFIG_FILE="/data/adb/mercor_wifi.conf"
fi

if [ -f "$CONFIG_FILE" ]; then
    # shellcheck disable=SC1090
    . "$CONFIG_FILE"
fi

connect_wifi() {
    # Check if already connected with IP
    IP=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
    if [ -n "$IP" ]; then
        iw dev wlan0 set power_save off 2>/dev/null
        return 0
    fi

    # 1. Primary Wi-Fi network
    if [ -n "$WIFI_1_SSID" ]; then
        echo "[$(date)] Connecting to primary Wi-Fi '$WIFI_1_SSID'..." >> "$LOG"
        if [ -n "$WIFI_1_PASSWORD" ]; then
            cmd wifi connect-network "$WIFI_1_SSID" "${WIFI_1_KEY_MGMT:-wpa2}" "$WIFI_1_PASSWORD" 2>/dev/null
        else
            cmd wifi connect-network "$WIFI_1_SSID" open 2>/dev/null
        fi
        sleep 3
        IP=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
        if [ -n "$IP" ]; then
            echo "[$(date)] Connected to $WIFI_1_SSID with IP: $IP" >> "$LOG"
            iw dev wlan0 set power_save off 2>/dev/null
            return 0
        fi
    fi

    # 2. Secondary Wi-Fi network / Hotspot
    if [ -n "$WIFI_2_SSID" ]; then
        echo "[$(date)] Connecting to secondary Wi-Fi '$WIFI_2_SSID'..." >> "$LOG"
        if [ -n "$WIFI_2_PASSWORD" ]; then
            cmd wifi connect-network "$WIFI_2_SSID" "${WIFI_2_KEY_MGMT:-wpa2}" "$WIFI_2_PASSWORD" 2>/dev/null
        else
            cmd wifi connect-network "$WIFI_2_SSID" open 2>/dev/null
        fi
        sleep 4
        IP=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
        if [ -n "$IP" ]; then
            echo "[$(date)] Connected to $WIFI_2_SSID with IP: $IP" >> "$LOG"
            iw dev wlan0 set power_save off 2>/dev/null
            return 0
        fi
    fi

    # 3. Tertiary fallback network
    if [ -n "$WIFI_3_SSID" ]; then
        if [ -n "$WIFI_3_PASSWORD" ]; then
            cmd wifi connect-network "$WIFI_3_SSID" "${WIFI_3_KEY_MGMT:-wpa2}" "$WIFI_3_PASSWORD" 2>/dev/null
        else
            cmd wifi connect-network "$WIFI_3_SSID" open 2>/dev/null
        fi
        sleep 4
        IP=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
        if [ -n "$IP" ]; then
            echo "[$(date)] Connected to $WIFI_3_SSID with IP: $IP" >> "$LOG"
            iw dev wlan0 set power_save off 2>/dev/null
            return 0
        fi
    fi

    return 1
}

for i in $(seq 1 10); do
    if connect_wifi; then
        break
    fi
    sleep 2
done

# 5. Enable Wireless ADB on TCP port 5555 permanently
resetprop persist.adb.tcp.port 5555 2>/dev/null || true
setprop service.adb.tcp.port 5555
settings put global adb_enabled 1 2>/dev/null || true
settings put global adb_wifi_enabled 1 2>/dev/null || true
stop adbd 2>/dev/null || true
sleep 1
start adbd 2>/dev/null || true
echo "[$(date)] Wireless ADB active on port 5555" >> "$LOG"

# 6. Copy scrcpy-server.jar to /data/local/tmp
cp "$MODDIR/bin/scrcpy-server.jar" /data/local/tmp/scrcpy-server.jar 2>/dev/null || true
chmod 644 /data/local/tmp/scrcpy-server.jar 2>/dev/null || true

# 7. Start native Zen Host Daemon (Discovery + TCP Bridge for scrcpy-server)
DAEMON="$MODDIR/bin/zen_daemon"
chmod 755 "$DAEMON" 2>/dev/null || true
if [ -x "$DAEMON" ]; then
    killall zen_daemon 2>/dev/null || true
    "$DAEMON" >> "$LOG" 2>&1 &
    echo "[$(date)] Native Zen Host Daemon started in background" >> "$LOG"
fi

# 8. Start ZenCast Host Status Notification Service
am start-foreground-service -n com.mercor.zenhost/.ZenHostService 2>/dev/null || true

# 9. Optimized watchdog loop (runs every 30s, pure native checks to prevent CPU micro-stutters)
while true; do
    # A. Prevent sleep / suspend / doze & Sleep of Death (zero-cost sysfs writes)
    echo "zen_headless_wakelock" > /sys/power/wake_lock 2>/dev/null
    echo "Y" > /sys/module/lpm_levels/parameters/sleep_disabled 2>/dev/null
    echo "0" > /sys/kernel/power_suspend/power_suspend_mode 2>/dev/null
    echo 1 > /sys/module/mdss_dsi/parameters/dsi_status_disable 2>/dev/null
    iw dev wlan0 set power_save off 2>/dev/null

    # B. Ensure GPU minimum frequency is locked (never underclocks to 160MHz)
    echo 370 > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null
    echo 370000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null

    # C. Ensure ADB port remains 5555
    if [ "$(getprop service.adb.tcp.port)" != "5555" ]; then
        setprop service.adb.tcp.port 5555
        stop adbd 2>/dev/null || true
        sleep 1
        start adbd 2>/dev/null || true
    fi

    # D. Ensure Wi-Fi stays connected
    CURR_IP=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
    if [ -z "$CURR_IP" ]; then
        connect_wifi
    fi

    # E. Ensure native zen_daemon is running
    if [ -x "$DAEMON" ] && ! pgrep -f "zen_daemon" >/dev/null 2>&1; then
        echo "[$(date)] zen_daemon not running, restarting..." >> "$LOG"
        "$DAEMON" >> "$LOG" 2>&1 &
    fi

    # F. Ensure ZenCastHost notification service is running
    if ! pgrep -f "com.mercor.zenhost" >/dev/null 2>&1; then
        am start-foreground-service -n com.mercor.zenhost/.ZenHostService 2>/dev/null || true
    fi

    sleep 30
done
