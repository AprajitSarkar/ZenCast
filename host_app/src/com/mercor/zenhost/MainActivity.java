package com.mercor.zenhost;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView textIp;
    private TextView textPorts;
    private TextView statusTitle;
    private View statusDot;
    private Button btnRestart;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isUpdating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textIp = findViewById(R.id.text_ip);
        textPorts = findViewById(R.id.text_ports);
        statusTitle = findViewById(R.id.status_title);
        statusDot = findViewById(R.id.status_dot);
        btnRestart = findViewById(R.id.btn_restart);

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Start permanent foreground service
        startHostService();

        btnRestart.setOnClickListener(v -> {
            Toast.makeText(this, "Restarting ZenCast Host...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -9 zen_daemon; pkill -9 app_process; nohup /data/local/tmp/zen_daemon > /data/local/tmp/zen_daemon.log 2>&1 &"});
                } catch (Exception ignored) {}
            }).start();
            startHostService();
            updateUI();
        });
    }

    private void startHostService() {
        Intent serviceIntent = new Intent(this, ZenHostService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isUpdating = true;
        pollStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isUpdating = false;
    }

    private void pollStatus() {
        if (!isUpdating) return;
        updateUI();
        handler.postDelayed(this::pollStatus, 2000);
    }

    private void updateUI() {
        String ip = ZenHostService.getWifiIP();
        textIp.setText("IP: " + ip);
        textPorts.setText("Video Port: 27183 | Control Port: 27184");

        boolean online = !ip.equals("127.0.0.1");
        statusTitle.setText(online ? "  HOST RUNNING" : "  WAITING FOR WI-FI");
        statusTitle.setTextColor(online ? 0xFF00E676 : 0xFFFFD600);
        statusDot.setBackgroundColor(online ? 0xFF00E676 : 0xFFFFD600);
    }
}
