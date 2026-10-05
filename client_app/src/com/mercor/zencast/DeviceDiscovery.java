package com.mercor.zencast;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceDiscovery {
    private static final String TAG = "ZenCast_Discovery";
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
    private final ExecutorService scanPool = Executors.newFixedThreadPool(16);

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
        devicesMap.clear();

        acquireMulticastLock();
        startListener();
        performFullScan();

        // Evaluate results after 1500ms initial scan window
        mainHandler.postDelayed(this::evaluateScan, 1500);
    }

    public void rescan() {
        initialScanEvaluated = false;
        hasDispatched = false;
        devicesMap.clear();
        performFullScan();
        mainHandler.postDelayed(this::evaluateScan, 1500);
    }

    private void performFullScan() {
        scanPool.execute(() -> {
            sendBroadcastProbes();
            sendSubnetUnicastProbes();
            probeRecentKnownIPs();
        });
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

    private void probeRecentKnownIPs() {
        scanPool.execute(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                byte[] data = "ZENCAST_DISCOVER".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String[] knownIPs = {"192.168.1.129", "192.168.1.106", "192.168.1.128", "192.168.1.130"};
                for (String ip : knownIPs) {
                    try {
                        InetAddress target = InetAddress.getByName(ip);
                        socket.send(new DatagramPacket(data, data.length, target, BEACON_PORT));
                    } catch (Exception ignored) {}
                }
                socket.close();
            } catch (Exception ignored) {}
        });
    }

    private boolean hasDispatched = false;

    private synchronized void evaluateScan() {
        if (!running) return;
        initialScanEvaluated = true;

        List<DiscoveredDevice> list = getDevices();
        Log.i(TAG, "Scan window evaluated. Found " + list.size() + " devices.");

        if (list.size() == 1) {
            if (!hasDispatched) {
                hasDispatched = true;
                mainHandler.post(() -> callback.onSingleDeviceFound(list.get(0)));
            }
        } else if (list.size() > 1) {
            if (!hasDispatched) {
                hasDispatched = true;
                mainHandler.post(() -> callback.onMultipleDevicesFound(list));
            } else {
                mainHandler.post(() -> callback.onDeviceListUpdated(list));
            }
        } else {
            // NEVER inject a fake device! Report no devices found and schedule continuous background scan
            mainHandler.post(callback::onNoDevicesFound);
            mainHandler.postDelayed(() -> {
                if (running && getDevices().isEmpty()) {
                    performFullScan();
                    mainHandler.postDelayed(this::evaluateScan, 2500);
                }
            }, 2500);
        }
    }

    private synchronized void onDeviceFound(DiscoveredDevice dev) {
        if (!hasDispatched && getDevices().size() == 1) {
            hasDispatched = true;
            initialScanEvaluated = true;
            mainHandler.post(() -> callback.onSingleDeviceFound(dev));
        } else {
            mainHandler.post(() -> callback.onDeviceListUpdated(getDevices()));
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
