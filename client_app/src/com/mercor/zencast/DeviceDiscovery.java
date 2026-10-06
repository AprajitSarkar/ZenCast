package com.mercor.zencast;

import android.content.Context;
import android.net.wifi.WifiInfo;
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

    private final ExecutorService priorityPool = Executors.newFixedThreadPool(4);
    private ExecutorService subnetPool = Executors.newFixedThreadPool(16);

    private volatile boolean running = false;
    private DatagramSocket udpSocket;
    private WifiManager.MulticastLock multicastLock;
    private boolean initialScanEvaluated = false;
    private boolean hasDispatched = false;
    private volatile String preferredIp;

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

        // Evaluate results after 1200ms initial scan window
        mainHandler.postDelayed(this::evaluateScan, 1200);
    }

    public synchronized void rescan() {
        initialScanEvaluated = false;
        hasDispatched = false;
        // Reset subnet pool to cancel any long-pending stale socket timeouts
        if (subnetPool != null && !subnetPool.isShutdown()) {
            subnetPool.shutdownNow();
        }
        subnetPool = Executors.newFixedThreadPool(16);
        performFullScan();
        mainHandler.postDelayed(this::evaluateScan, 1200);
    }

    public void addKnownDevice(DiscoveredDevice dev) {
        if (dev != null && dev.getIp() != null) {
            devicesMap.put(dev.getIp(), dev);
            mainHandler.post(() -> {
                if (running) callback.onDeviceListUpdated(getDevices());
            });
        }
    }

    public void setPreferredIp(String ip) {
        this.preferredIp = ip;
    }

    public void clearPreferredIp() {
        this.preferredIp = null;
    }

    public void clearCache() {
        devicesMap.clear();
    }

    private void performFullScan() {
        // Priority 1: Instant parallel check of known candidates (Redmi, ZenFone, last used)
        priorityPool.execute(this::probePriorityIPs);
        // Priority 2: Non-blocking UDP broadcast and subnet unicast beacon probes (~20ms)
        priorityPool.execute(this::sendBroadcastProbes);
        priorityPool.execute(this::sendSubnetUnicastProbes);
        // Priority 3: Background subnet TCP scan
        priorityPool.execute(this::probeSubnetTCP);
    }

    public String getSubnetPrefix() {
        try {
            // 1. WifiManager IP lookup
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null) {
                    int ipInt = info.getIpAddress();
                    if (ipInt != 0) {
                        return String.format(java.util.Locale.US, "%d.%d.%d.",
                                (ipInt & 0xff),
                                ((ipInt >> 8) & 0xff),
                                ((ipInt >> 16) & 0xff));
                    }
                }
            }

            // 2. Prioritize wlan / wifi interfaces
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase();
                if (name.startsWith("wlan") || name.startsWith("wifi")) {
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
            }

            // 3. Fallback to any active non-cellular IPv4 interface
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("p2p")) continue;
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

    private void probePriorityIPs() {
        String subnet = getSubnetPrefix();
        java.util.LinkedHashSet<String> ipSet = new java.util.LinkedHashSet<>();
        if (preferredIp != null && !preferredIp.isEmpty()) ipSet.add(preferredIp);
        ipSet.add("192.168.1.172"); // Redmi Note 7S
        ipSet.add("192.168.1.129"); // ZenFone Max Pro M1
        ipSet.add("192.168.1.128");
        ipSet.add("192.168.1.203");
        ipSet.add(subnet + "172");
        ipSet.add(subnet + "129");
        ipSet.add(subnet + "128");
        ipSet.add(subnet + "203");

        for (String ip : ipSet) {
            if (!running) break;
            priorityPool.execute(() -> {
                DiscoveredDevice dev = probeHostDirect(ip, 500);
                if (dev != null) {
                    boolean isNew = !devicesMap.containsKey(dev.getIp());
                    devicesMap.put(dev.getIp(), dev);
                    if (isNew) {
                        Log.i(TAG, "Priority TCP probe discovered host: " + dev);
                    }
                    onDeviceFound(dev);
                }
            });
        }
    }

    private void sendBroadcastProbes() {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            byte[] data = "ZENCAST_DISCOVER".getBytes(StandardCharsets.UTF_8);

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
            Log.w(TAG, "Broadcast probe error: " + e.getMessage());
        }
    }

    private void sendSubnetUnicastProbes() {
        String subnet = getSubnetPrefix();
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] data = "ZENCAST_DISCOVER".getBytes(StandardCharsets.UTF_8);
            for (int i = 1; i <= 254; i++) {
                if (!running) break;
                try {
                    InetAddress target = InetAddress.getByName(subnet + i);
                    socket.send(new DatagramPacket(data, data.length, target, BEACON_PORT));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Unicast subnet probe error: " + e.getMessage());
        }
    }

    private void probeSubnetTCP() {
        String subnet = getSubnetPrefix();
        for (int i = 1; i <= 254; i++) {
            if (!running) break;
            final String targetIp = subnet + i;
            // Skip IPs already discovered
            if (devicesMap.containsKey(targetIp)) continue;

            subnetPool.execute(() -> {
                if (!running) return;
                DiscoveredDevice dev = probeHostDirect(targetIp, 350);
                if (dev != null) {
                    boolean isNew = !devicesMap.containsKey(dev.getIp());
                    devicesMap.put(dev.getIp(), dev);
                    if (isNew) {
                        Log.i(TAG, "Subnet TCP probe discovered host: " + dev);
                    }
                    onDeviceFound(dev);
                }
            });
        }
    }

    public static DiscoveredDevice probeHostDirect(String ip, int timeoutMs) {
        if (ip == null || ip.trim().isEmpty()) return null;
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(Math.max(timeoutMs, 600));
            s.connect(new InetSocketAddress(ip, DISCOVERY_TCP_PORT), timeoutMs);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line != null && line.contains("video_port")) {
                JSONObject json = new JSONObject(line);
                String devIp = json.optString("ip", ip);
                String model = json.optString("model", "ZenCast Device");
                String name = json.optString("device", model);
                int vPort = json.optInt("video_port", VIDEO_PORT);
                int cPort = json.optInt("control_port", CONTROL_PORT);
                int aPort = json.optInt("audio_port", AUDIO_PORT);
                return new DiscoveredDevice(devIp, model, name, vPort, cPort, aPort);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private synchronized void evaluateScan() {
        if (!running || hasDispatched) return;
        initialScanEvaluated = true;

        List<DiscoveredDevice> list = getDevices();
        Log.i(TAG, "Scan window evaluated. Found " + list.size() + " devices.");

        // 1. If preferred device was found, connect to it
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
            Log.i(TAG, "Preferred device " + preferredIp + " not found yet. Active devices: " + list.size());
        }

        // 2. If preferred device is offline, but other devices were found
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
                    mainHandler.postDelayed(this::evaluateScan, 2000);
                }
            }, 2000);
        }
    }

    private synchronized void onDeviceFound(DiscoveredDevice dev) {
        if (!running) return;

        // Always notify listener so UI list refreshes instantly
        mainHandler.post(() -> {
            if (running) callback.onDeviceListUpdated(getDevices());
        });

        // If preferred device matches, dispatch immediately
        if (preferredIp != null && !preferredIp.isEmpty()) {
            if (dev.getIp().equals(preferredIp)) {
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
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

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
                                Log.i(TAG, "UDP beacon discovered host: " + dev);
                            }
                            onDeviceFound(dev);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "UDP listener closed: " + e.getMessage());
            }
        }).start();
    }

    public synchronized void stop() {
        running = false;
        hasDispatched = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (subnetPool != null && !subnetPool.isShutdown()) {
            subnetPool.shutdownNow();
        }
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
