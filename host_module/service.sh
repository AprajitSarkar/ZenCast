#!/system/bin/sh
MODDIR="${0%/*}"

# 1. Wait until Android boot process completes
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

LOG="/data/local/tmp/zen_host_service.log"
echo "[$(date)] Android boot completed. Starting ZenCast Host..." > "$LOG"

# 2. Basic device hygiene for remote screen sharing
svc power stayon true
settings put global development_settings_enabled 1
settings put global stay_on_while_plugged_in 7
settings put system screen_off_timeout 2147483647
settings put global lockscreen.disabled 1 2>/dev/null || true
wm dismiss-keyguard 2>/dev/null || true
input keyevent 224 2>/dev/null || true
input keyevent 82 2>/dev/null || true

# 3. Clean standard Android display settings (no harmful sysfs/kernel overrides)
settings put system screen_brightness 150 2>/dev/null || true

# 4. Turn OFF Airplane Mode & Enable Wi-Fi
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

# 9. Clean, lightweight watchdog loop (Wi-Fi, ADB port, and daemon keepalive)
while true; do
    # Ensure ADB port remains 5555
    if [ "$(getprop service.adb.tcp.port)" != "5555" ]; then
        setprop service.adb.tcp.port 5555
        stop adbd 2>/dev/null || true
        sleep 1
        start adbd 2>/dev/null || true
    fi

    # Ensure Wi-Fi stays connected
    CURR_IP=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
    if [ -z "$CURR_IP" ]; then
        connect_wifi
    fi

    # Ensure native zen_daemon is running
    if [ -x "$DAEMON" ] && ! pgrep -f "zen_daemon" >/dev/null 2>&1; then
        echo "[$(date)] zen_daemon not running, restarting..." >> "$LOG"
        "$DAEMON" >> "$LOG" 2>&1 &
    fi

    sleep 60
done
