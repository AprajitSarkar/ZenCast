package com.mercor.zenhost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class ZenHostService extends Service {
    private static final String TAG = "ZenHostService";
    private static final String CHANNEL_ID = "zen_host_channel";
    private static final int NOTIFICATION_ID = 1001;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private String lastReportedIp = "";

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Keep CPU awake
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenHost:WakeLock");
            wakeLock.acquire();
        }

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String ip = getWifiIP();
        lastReportedIp = ip;

        Notification notification = buildNotification(ip);
        startForeground(NOTIFICATION_ID, notification);

        if (!isRunning) {
            isRunning = true;
            ensureDaemonRunning();
            startMonitoringLoop();
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ZenCast Host Active Status",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Permanent status notification showing ZenCast Host IP and Ports");
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String ip) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = "ZenCast Host Active";
        String content = "Wi-Fi: " + ip + " | Video: 27183 | Control: 27184";
        String subText = "Headless 60fps Server";

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
                .setContentText(content)
                .setSubText(subText)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setOngoing(true) // PERMANENT: Cannot be dismissed by user
                .setAutoCancel(false)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        return builder.build();
    }

    private void startMonitoringLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                String currentIp = getWifiIP();
                if (!currentIp.equals(lastReportedIp)) {
                    lastReportedIp = currentIp;
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(currentIp));
                }

                ensureDaemonRunning();
                handler.postDelayed(this, 10000);
            }
        }, 10000);
    }

    private void ensureDaemonRunning() {
        new Thread(() -> {
            // First check if daemon is alive via local TCP probe (takes <1ms, 0 root overhead)
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", 27182), 250);
                return; // Daemon is alive and responsive
            } catch (Exception ignored) {}

            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(getWifiIP(), 27182), 400);
                return; // Daemon is alive and responsive over Wi-Fi interface
            } catch (Exception ignored) {}

            // Daemon unreachable on port 27182, verify and restart via root
            try {
                Process checkP = Runtime.getRuntime().exec(new String[]{"su", "-c", "pgrep -f zen_daemon || true"});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(checkP.getInputStream()));
                String line = reader.readLine();
                checkP.waitFor();

                if (line == null || line.trim().isEmpty()) {
                    Log.i(TAG, "zen_daemon not running, starting...");
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "nohup /data/local/tmp/zen_daemon > /data/local/tmp/zen_daemon.log 2>&1 &"});
                }
            } catch (Exception ignored) {}
        }).start();
    }

    public static String getWifiIP() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().startsWith("wlan")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
