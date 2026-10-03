SKIPUNZIP=0

ui_print "***************************************************"
ui_print "*        ZENFONE HEADLESS REMOTE HOST v2.0        *"
ui_print "*   Hardware-Accelerated Low-Latency Host Server   *"
ui_print "*            Author: Aprajit Sarkar               *"
ui_print "***************************************************"

ui_print "- Installing native binaries..."
set_perm "$MODPATH/bin/zen_daemon" 0 0 0755
set_perm "$MODPATH/bin/scrcpy-server.jar" 0 0 0644
set_perm "$MODPATH/service.sh" 0 0 0755

# Preserve or restore wifi.conf across module installs
if [ -f "/data/adb/mercor_wifi.conf" ]; then
    cp -f "/data/adb/mercor_wifi.conf" "$MODPATH/wifi.conf" 2>/dev/null || true
elif [ -f "$MODPATH/wifi.conf" ]; then
    cp -f "$MODPATH/wifi.conf" "/data/adb/mercor_wifi.conf" 2>/dev/null || true
fi
[ -f "$MODPATH/wifi.conf" ] && set_perm "$MODPATH/wifi.conf" 0 0 0600

# Ensure /data/local/tmp has scrcpy-server.jar as well for fallback
cp "$MODPATH/bin/scrcpy-server.jar" /data/local/tmp/scrcpy-server.jar 2>/dev/null || true
chmod 644 /data/local/tmp/scrcpy-server.jar 2>/dev/null || true

ui_print "  [✓] Binaries installed to $MODPATH/bin"
ui_print "  [✓] Permanent Wireless ADB (Port 5555) configured"
ui_print "  [✓] Ultra-Low-Latency Display Mirroring ready"
ui_print "***************************************************"
