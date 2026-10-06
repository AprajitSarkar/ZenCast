package com.mercor.zencast;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceDiscovery {
    private static final String TAG = "ZenCast_Discovery";
    public static final int DISCOVERY_TCP_PORT = 27182;
    public static final int BEACON_PORT = 38888;
    public static final int VIDEO_PORT = 27183;
    public static final int CONTROL_PORT = 27184;
    public static final int AUDIO_PORT = 27185;

    public interface DiscoveryCallback {
        void onSingleDeviceFound(DiscoveredDevice device);
        void onMultipleDevicesFound(List<DiscoveredDevice> devices);
        void onDeviceListUpdated(List<DiscoveredDevice> devices);
        void onNoDevicesFound();
    }

    private final Context context;
    private final DiscoveryCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, DiscoveredDevice> devicesMap = new ConcurrentHashMap<>();
    private final ExecutorService scanPool = Executors.newFixedThreadPool(32);

    private volatile boolean running = false;
    private DatagramSocket udpSocket;
    private WifiManager.MulticastLock multicastLock;
    private boolean initialScanEvaluated = false;

    public DeviceDiscovery(Context context, DiscoveryCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        initialScanEvaluated = false;
        hasDispatched = false;

        acquireMulticastLock();
        startListener();
        performFullScan();

        // Evaluate results after 1500ms initial scan window
        mainHandler.postDelayed(this::evaluateScan, 1500);
    }

    public void rescan() {
        initialScanEvaluated = false;
        hasDispatched = false;
        performFullScan();
        mainHandler.postDelayed(this::evaluateScan, 1500);
    }

    public void addKnownDevice(DiscoveredDevice dev) {
        if (dev != null && dev.getIp() != null) {
            devicesMap.put(dev.getIp(), dev);
        }
    }

    public void clearPreferredIp() {
        this.preferredIp = null;
    }

    private void performFullScan() {
        scanPool.execute(this::probeRecentKnownIPs);
        scanPool.execute(this::probeSubnetTCP);
        scanPool.execute(this::sendBroadcastProbes);
        scanPool.execute(this::sendSubnetUnicastProbes);
    }

    private String getSubnetPrefix() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        int lastDot = host.lastIndexOf('.');
                        if (lastDot > 0) {
                            return host.substring(0, lastDot + 1);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.";
    }

    private void sendBroadcastProbes() {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            byte[] data = "ZENCAST_DISCOVER".getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Global broadcast
            try {
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), BEACON_PORT));
            } catch (Exception ignored) {}

            // Subnet broadcast
            String subnet = getSubnetPrefix();
            try {
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(subnet + "255"), BEACON_PORT));
            } catch (Exception ignored) {}

            socket.close();
        } catch (Exception e) {
            Log.w(TAG, "Broadcast error: " + e.getMessage());
        }
    }

    private void sendSubnetUnicastProbes() {
        String subnet = getSubnetPrefix();
        try {
            DatagramSocket socket = new DatagramSocket();
            byte[] data = "ZENCAST_DISCOVER".getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Direct unicast UDP probes across subnet (bypasses router broadcast isolation)
            for (int i = 1; i <= 254; i++) {
                if (!running) break;
                try {
                    InetAddress target = InetAddress.getByName(subnet + i);
                    socket.send(new DatagramPacket(data, data.length, target, BEACON_PORT));
                } catch (Exception ignored) {}
            }
            socket.close();
        } catch (Exception e) {
            Log.w(TAG, "Unicast subnet probe error: " + e.getMessage());
        }
    }

    private volatile String preferredIp;

    public void setPreferredIp(String ip) {
        this.preferredIp = ip;
    }

    public static DiscoveredDevice probeHostDirect(String ip, int timeoutMs) {
        if (ip == null || ip.trim().isEmpty()) return null;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, DISCOVERY_TCP_PORT), timeoutMs);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line != null && line.contains("video_port")) {
                JSONObject json = new JSONObject(line);
                String devIp = json.optString("ip", ip);
                String model = json.optString("model", "ZenFone Max Pro M1");
                String name = json.optString("device", model);
                int vPort = json.optInt("video_port", VIDEO_PORT);
                int cPort = json.optInt("control_port", CONTROL_PORT);
                int aPort = json.optInt("audio_port", AUDIO_PORT);
                return new DiscoveredDevice(devIp, model, name, vPort, cPort, aPort);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void probeRecentKnownIPs() {
        scanPool.execute(() -> {
            String subnet = getSubnetPrefix();
            java.util.LinkedHashSet<String> ipSet = new java.util.LinkedHashSet<>();
            if (preferredIp != null && !preferredIp.isEmpty()) ipSet.add(preferredIp);
            ipSet.add("192.168.1.172"); // Redmi Note 7S
            ipSet.add("192.168.1.129"); // ZenFone Max Pro M1
            ipSet.add("192.168.1.224");
            ipSet.add("192.168.1.176");
            ipSet.add("10.19.221.204");
            ipSet.add(subnet + "172");
            ipSet.add(subnet + "129");
            ipSet.add(subnet + "224");
            ipSet.add(subnet + "176");
            ipSet.add(subnet + "204");
            ipSet.add(subnet + "106");
            ipSet.add(subnet + "100");
            ipSet.add(subnet + "1");

            List<String> targetIPs = new ArrayList<>(ipSet);
            byte[] data = "ZENCAST_DISCOVER".getBytes(StandardCharsets.UTF_8);

            // 1. Direct UDP Probe
            try (DatagramSocket socket = new DatagramSocket()) {
                for (String ip : targetIPs) {
                    try {
                        InetAddress target = InetAddress.getByName(ip);
                        socket.send(new DatagramPacket(data, data.length, target, BEACON_PORT));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            // 2. High-speed TCP Discovery Check on dedicated discovery port 27182
            for (String ip : targetIPs) {
                if (!running) break;
                DiscoveredDevice dev = probeHostDirect(ip, 800);
                if (dev != null) {
                    boolean isNew = !devicesMap.containsKey(dev.getIp());
                    devicesMap.put(dev.getIp(), dev);
                    if (isNew) {
                        Log.i(TAG, "TCP discovery identified host: " + dev);
                        onDeviceFound(dev);
                    }
                }
            }
        });
    }

    private void probeSubnetTCP() {
        String subnet = getSubnetPrefix();
        for (int i = 1; i <= 254; i++) {
            if (!running) break;
            final String targetIp = subnet + i;
            scanPool.execute(() -> {
                if (!running) return;
                DiscoveredDevice dev = probeHostDirect(targetIp, 500);
                if (dev != null) {
                    boolean isNew = !devicesMap.containsKey(dev.getIp());
                    devicesMap.put(dev.getIp(), dev);
                    if (isNew) {
                        Log.i(TAG, "Subnet TCP probe identified host: " + dev);
                        onDeviceFound(dev);
                    }
                }
            });
        }
    }

    private boolean hasDispatched = false;

    private synchronized void evaluateScan() {
        if (!running || hasDispatched) return;
        initialScanEvaluated = true;

        List<DiscoveredDevice> list = getDevices();
        Log.i(TAG, "Scan window evaluated. Found " + list.size() + " devices.");

        // Priority 1: Check if preferred device is in the discovered list
        if (preferredIp != null && !preferredIp.isEmpty()) {
            for (DiscoveredDevice d : list) {
                if (d.getIp().equals(preferredIp)) {
                    hasDispatched = true;
                    mainHandler.post(() -> {
                        if (running) callback.onSingleDeviceFound(d);
                    });
                    return;
                }
            }

            // Priority 2: Probe preferredIp directly one more time before giving up
            DiscoveredDevice directPref = probeHostDirect(preferredIp, 1000);
            if (directPref != null) {
                devicesMap.put(directPref.getIp(), directPref);
                hasDispatched = true;
                mainHandler.post(() -> {
                    if (running) callback.onSingleDeviceFound(directPref);
                });
                return;
            }
            Log.i(TAG, "Preferred device " + preferredIp + " is unreachable, evaluating other devices");
        }

        if (list.size() == 1) {
            hasDispatched = true;
            mainHandler.post(() -> {
                if (running) callback.onSingleDeviceFound(list.get(0));
            });
        } else if (list.size() > 1) {
            hasDispatched = true;
            mainHandler.post(() -> {
                if (running) callback.onMultipleDevicesFound(list);
            });
        } else {
            mainHandler.post(() -> {
                if (running && !hasDispatched) callback.onNoDevicesFound();
            });
            mainHandler.postDelayed(() -> {
                if (running && getDevices().isEmpty()) {
                    performFullScan();
                    mainHandler.postDelayed(this::evaluateScan, 2500);
                }
            }, 2500);
        }
    }

    private synchronized void onDeviceFound(DiscoveredDevice dev) {
        if (!running) return;

        // Always notify listener that devices list has updated so UI can refresh instantly
        mainHandler.post(() -> {
            if (running) callback.onDeviceListUpdated(getDevices());
        });

        // If a preferred device IP is configured (for auto-reconnect)
        if (preferredIp != null && !preferredIp.isEmpty()) {
            if (dev.getIp().equals(preferredIp)) {
                // The last used device is online! Immediately dispatch it as the target.
                if (!hasDispatched) {
                    hasDispatched = true;
                    initialScanEvaluated = true;
                    mainHandler.post(() -> {
                        if (running) callback.onSingleDeviceFound(dev);
                    });
                }
            }
        }
    }

    public List<DiscoveredDevice> getDevices() {
        return new ArrayList<>(devicesMap.values());
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("ZenCastDiscovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "MulticastLock error: " + e.getMessage());
        }
    }

    private void startListener() {
        new Thread(() -> {
            try {
                udpSocket = new DatagramSocket(null);
                udpSocket.setReuseAddress(true);
                udpSocket.bind(new InetSocketAddress(BEACON_PORT));
                byte[] buf = new byte[2048];

                while (running) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);

                    try {
                        JSONObject json = new JSONObject(msg);
                        if (json.has("ip") && json.has("video_port") && json.has("control_port")) {
                            String ip = json.getString("ip");
                            String model = json.optString("model", "ZenCast Device");
                            String name = json.optString("device", model);
                            int vPort = json.getInt("video_port");
                            int cPort = json.getInt("control_port");
                            int aPort = json.optInt("audio_port", AUDIO_PORT);

                            DiscoveredDevice dev = new DiscoveredDevice(ip, model, name, vPort, cPort, aPort);
                            boolean isNew = !devicesMap.containsKey(ip);
                            devicesMap.put(ip, dev);

                            if (isNew) {
                                onDeviceFound(dev);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "UDP listener closed: " + e.getMessage());
            }
        }).start();
    }

    public boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        hasDispatched = true;
        mainHandler.removeCallbacksAndMessages(null);
        devicesMap.clear();
        if (udpSocket != null && !udpSocket.isClosed()) {
            try {
                udpSocket.close();
            } catch (Exception ignored) {}
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {}
        }
    }
}
