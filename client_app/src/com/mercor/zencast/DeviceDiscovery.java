package com.mercor.zencast;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceDiscovery {
    private static final String TAG = "ZenCast_Discovery";
    public static final int BEACON_PORT = 38888;

    public interface DiscoveryCallback {
        void onSingleDeviceFound(DiscoveredDevice device);
        void onMultipleDevicesFound(List<DiscoveredDevice> devices);
        void onDeviceListUpdated(List<DiscoveredDevice> devices);
    }

    private final Context context;
    private final DiscoveryCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, DiscoveredDevice> devicesMap = new ConcurrentHashMap<>();

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
        devicesMap.clear();

        acquireMulticastLock();
        startListener();
        sendDiscoveryProbe();

        // Evaluate results after 1200ms scan window
        mainHandler.postDelayed(this::evaluateInitialScan, 1200);
    }

    public void rescan() {
        initialScanEvaluated = false;
        sendDiscoveryProbe();
        mainHandler.postDelayed(this::evaluateInitialScan, 1000);
    }

    private synchronized void evaluateInitialScan() {
        if (!running || initialScanEvaluated) return;
        initialScanEvaluated = true;

        List<DiscoveredDevice> list = getDevices();
        Log.i(TAG, "Scan window ended. Found " + list.size() + " devices.");

        if (list.size() == 1) {
            mainHandler.post(() -> callback.onSingleDeviceFound(list.get(0)));
        } else if (list.size() > 1) {
            mainHandler.post(() -> callback.onMultipleDevicesFound(list));
        } else {
            // Safe fallback to primary ZenFone IP without burning TCP session
            DiscoveredDevice fallback = new DiscoveredDevice("192.168.1.129", "ZenFone Max Pro M1", "ZenFone Max Pro M1", 27183, 27184, 27185);
            devicesMap.put(fallback.getIp(), fallback);
            mainHandler.post(() -> callback.onSingleDeviceFound(fallback));
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

    private void sendDiscoveryProbe() {
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                byte[] data = "ZENCAST_DISCOVER".getBytes(java.nio.charset.StandardCharsets.UTF_8);

                // 1. Broadcast to global 255.255.255.255
                try {
                    DatagramPacket p1 = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), BEACON_PORT);
                    socket.send(p1);
                } catch (Exception ignored) {}

                // 2. Subnet broadcast fallback
                try {
                    DatagramPacket p2 = new DatagramPacket(data, data.length, InetAddress.getByName("192.168.1.255"), BEACON_PORT);
                    socket.send(p2);
                } catch (Exception ignored) {}

                // 3. Unicast probe to primary known host (bypasses Wi-Fi broadcast isolation)
                try {
                    DatagramPacket p3 = new DatagramPacket(data, data.length, InetAddress.getByName("192.168.1.129"), BEACON_PORT);
                    socket.send(p3);
                } catch (Exception ignored) {}

                socket.close();
            } catch (Exception e) {
                Log.w(TAG, "Send discovery probe error: " + e.getMessage());
            }
        }).start();
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
                            int aPort = json.optInt("audio_port", 27185);

                            DiscoveredDevice dev = new DiscoveredDevice(ip, model, name, vPort, cPort, aPort);
                            boolean isNew = !devicesMap.containsKey(ip);
                            devicesMap.put(ip, dev);

                            if (isNew && initialScanEvaluated) {
                                mainHandler.post(() -> callback.onDeviceListUpdated(getDevices()));
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "UDP listener closed: " + e.getMessage());
            }
        }).start();
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
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
