package com.mercor.zencast;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements
        SurfaceHolder.Callback,
        DeviceDiscovery.DiscoveryCallback,
        ScrcpyStreamDecoder.StreamListener,
        ScrcpyControlClient.ClipboardListener {

    private static final String TAG = "ZenCast_Main";

    private SurfaceView surfaceView;
    private LinearLayout statusOverlay;
    private TextView statusText;

    // Floating Controls
    private LinearLayout floatingMenuContainer;
    private ImageButton floatingMainBtn;
    private LinearLayout floatingExpandedMenu;
    private ImageButton btnDisplayPower;
    private ImageButton btnPowerMenu;
    private ImageButton btnChangeDevice;
    private ImageButton btnRotateScreen;
    private ImageButton btnClipboardSync;
    private ImageButton btnCloseMenu;

    // Device Switcher Overlay
    private FrameLayout deviceSwitcherOverlay;
    private LinearLayout deviceListContainer;

    private DeviceDiscovery discovery;
    private ScrcpyStreamDecoder decoder;
    private ScrcpyControlClient controlClient;
    private ScrcpyAudioPlayer audioPlayer;

    private DiscoveredDevice currentDevice;
    private int remoteWidth = 720;
    private int remoteHeight = 1440;
    private boolean isConnected = false;
    private boolean isFloatingMenuExpanded = false;
    private boolean isHostDisplayOn = true;
    private long lastBackPressTime = 0;

    // Clipboard Sync
    private ClipboardManager clipboardManager;
    private String lastSyncedClipboard = "";
    private boolean isSyncingClipboard = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen awake and full screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        setContentView(R.layout.activity_main);

        initViews();
        setupClipboardSync();
        setupFloatingMenu();

        surfaceView.getHolder().addCallback(this);

        // Touch input forwarding
        surfaceView.setOnTouchListener((v, event) -> {
            if (!isConnected || controlClient == null) return false;

            int action = event.getActionMasked();
            int scrcpyAction = -1;
            if (action == MotionEvent.ACTION_DOWN) {
                scrcpyAction = 0;
            } else if (action == MotionEvent.ACTION_UP) {
                scrcpyAction = 1;
            } else if (action == MotionEvent.ACTION_MOVE) {
                scrcpyAction = 2;
            }

            if (scrcpyAction != -1) {
                float viewW = v.getWidth();
                float viewH = v.getHeight();
                int targetX = (int) Math.max(0, Math.min(remoteWidth, (event.getX() / viewW) * remoteWidth));
                int targetY = (int) Math.max(0, Math.min(remoteHeight, (event.getY() / viewH) * remoteHeight));

                controlClient.sendTouch(scrcpyAction, targetX, targetY, remoteWidth, remoteHeight);
            }
            return true;
        });

        discovery = new DeviceDiscovery(this, this);
    }

    private void initViews() {
        surfaceView = findViewById(R.id.surface_view);
        statusOverlay = findViewById(R.id.status_overlay);
        statusText = findViewById(R.id.status_text);

        floatingMenuContainer = findViewById(R.id.floating_menu_container);
        floatingMainBtn = findViewById(R.id.floating_main_btn);
        floatingExpandedMenu = findViewById(R.id.floating_expanded_menu);
        btnDisplayPower = findViewById(R.id.btn_display_power);
        btnPowerMenu = findViewById(R.id.btn_power_menu);
        btnChangeDevice = findViewById(R.id.btn_change_device);
        btnRotateScreen = findViewById(R.id.btn_rotate_screen);
        btnClipboardSync = findViewById(R.id.btn_clipboard_sync);
        btnCloseMenu = findViewById(R.id.btn_close_menu);

        deviceSwitcherOverlay = findViewById(R.id.device_switcher_overlay);
        deviceListContainer = findViewById(R.id.device_list_container);

        findViewById(R.id.btn_rescan_devices).setOnClickListener(v -> {
            Toast.makeText(this, "Scanning for ZenCast hosts...", Toast.LENGTH_SHORT).show();
            discovery.rescan();
        });

        findViewById(R.id.btn_dismiss_device_switcher).setOnClickListener(v -> hideDeviceSwitcher());
    }

    private void setupClipboardSync() {
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(() -> {
                if (isSyncingClipboard || !isConnected || controlClient == null) return;
                try {
                    ClipData clip = clipboardManager.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).getText();
                        if (text != null && !text.toString().equals(lastSyncedClipboard)) {
                            lastSyncedClipboard = text.toString();
                            controlClient.sendClipboard(lastSyncedClipboard, false);
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    public void onHostClipboardReceived(String text) {
        if (text == null || text.equals(lastSyncedClipboard)) return;
        uiHandler.post(() -> {
            try {
                lastSyncedClipboard = text;
                isSyncingClipboard = true;
                if (clipboardManager != null) {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("ZenCast", text));
                }
                isSyncingClipboard = false;
                Toast.makeText(this, "Copied from Host!", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    private void setupFloatingMenu() {
        floatingMainBtn.setOnTouchListener(new View.OnTouchListener() {
            private float initialTouchX, initialTouchY;
            private float initialTransX, initialTransY;
            private boolean isDragging = false;
            private final float touchSlop = 12f;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        initialTransX = floatingMenuContainer.getTranslationX();
                        initialTransY = floatingMenuContainer.getTranslationY();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            floatingMenuContainer.setTranslationX(initialTransX + dx);
                            floatingMenuContainer.setTranslationY(initialTransY + dy);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            toggleFloatingMenu();
                        }
                        return true;
                }
                return false;
            }
        });

        // 1. Lock / Screen Power Toggle (turns physical host screen off while mirror continues)
        btnDisplayPower.setOnClickListener(v -> {
            if (controlClient != null) {
                isHostDisplayOn = !isHostDisplayOn;
                controlClient.setDisplayPower(isHostDisplayOn);
                Toast.makeText(this, isHostDisplayOn ? "Host Display: ON" : "Host Display: OFF (Mirroring active)", Toast.LENGTH_SHORT).show();
            }
        });

        // 2. Power Menu
        btnPowerMenu.setOnClickListener(v -> {
            if (controlClient != null) {
                controlClient.triggerPowerMenu();
                Toast.makeText(this, "Opening Host Power Menu...", Toast.LENGTH_SHORT).show();
            }
        });

        // 3. Change Device
        btnChangeDevice.setOnClickListener(v -> {
            collapseFloatingMenu();
            showDeviceSwitcher();
        });

        // 4. Orientation / Rotate
        btnRotateScreen.setOnClickListener(v -> {
            int currentOrientation = getResources().getConfiguration().orientation;
            if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        });

        // 5. Manual Clipboard Push
        btnClipboardSync.setOnClickListener(v -> {
            if (clipboardManager != null && controlClient != null) {
                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        controlClient.sendClipboard(text.toString(), false);
                        Toast.makeText(this, "Pushed clipboard to host", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // 6. Close Menu
        btnCloseMenu.setOnClickListener(v -> collapseFloatingMenu());
    }

    private void toggleFloatingMenu() {
        if (isFloatingMenuExpanded) {
            collapseFloatingMenu();
        } else {
            expandFloatingMenu();
        }
    }

    private void expandFloatingMenu() {
        isFloatingMenuExpanded = true;
        floatingExpandedMenu.animate().setListener(null);
        floatingExpandedMenu.setVisibility(View.VISIBLE);
        floatingExpandedMenu.setAlpha(0f);
        floatingExpandedMenu.setTranslationY(-20f);
        floatingExpandedMenu.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setListener(null)
                .start();
    }

    private void collapseFloatingMenu() {
        isFloatingMenuExpanded = false;
        floatingExpandedMenu.animate().setListener(null);
        floatingExpandedMenu.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(180)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        floatingExpandedMenu.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void showDeviceSwitcher() {
        updateDeviceListView(discovery.getDevices());
        deviceSwitcherOverlay.animate().setListener(null);
        deviceSwitcherOverlay.setVisibility(View.VISIBLE);
        deviceSwitcherOverlay.setAlpha(0f);
        deviceSwitcherOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .setListener(null)
                .start();
    }

    private void hideDeviceSwitcher() {
        deviceSwitcherOverlay.animate().setListener(null);
        deviceSwitcherOverlay.animate()
                .alpha(0f)
                .setDuration(180)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        deviceSwitcherOverlay.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void updateDeviceListView(List<DiscoveredDevice> devices) {
        deviceListContainer.removeAllViews();

        if (devices.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("Scanning for devices on Wi-Fi...\nEnsure ZenCast Host is running.");
            emptyView.setTextColor(Color.parseColor("#94A3B8"));
            emptyView.setTextSize(14);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(16, 32, 16, 32);
            deviceListContainer.addView(emptyView);
            return;
        }

        for (DiscoveredDevice dev : devices) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackgroundResource(R.drawable.bg_device_item);
            item.setPadding(20, 16, 20, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 12);
            item.setLayoutParams(lp);

            TextView nameView = new TextView(this);
            nameView.setText(dev.getDeviceName());
            nameView.setTextColor(Color.WHITE);
            nameView.setTextSize(16);
            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
            item.addView(nameView);

            TextView subView = new TextView(this);
            boolean isCur = currentDevice != null && currentDevice.getIp().equals(dev.getIp());
            subView.setText(dev.getIp() + (isCur ? " (Currently Connected)" : " - Tap to Connect"));
            subView.setTextColor(isCur ? Color.parseColor("#34D399") : Color.parseColor("#38BDF8"));
            subView.setTextSize(13);
            item.addView(subView);

            item.setOnClickListener(v -> {
                hideDeviceSwitcher();
                connectToDevice(dev);
            });

            deviceListContainer.addView(item);
        }
    }

    // Discovery Callbacks
    @Override
    public void onSingleDeviceFound(DiscoveredDevice device) {
        Log.i(TAG, "Single host found: " + device);
        if (!isConnected || currentDevice == null) {
            connectToDevice(device);
        }
    }

    @Override
    public void onMultipleDevicesFound(List<DiscoveredDevice> devices) {
        Log.i(TAG, "Multiple hosts found: " + devices.size());
        if (!isConnected) {
            showDeviceSwitcher();
        }
    }

    @Override
    public void onDeviceListUpdated(List<DiscoveredDevice> devices) {
        if (deviceSwitcherOverlay.getVisibility() == View.VISIBLE) {
            updateDeviceListView(devices);
        }
    }

    private synchronized void connectToDevice(DiscoveredDevice dev) {
        this.currentDevice = dev;
        Log.i(TAG, "Initiating connection to " + dev.getDeviceName() + " (" + dev.getIp() + ")");

        statusOverlay.setVisibility(View.VISIBLE);
        statusText.setText("Connecting to " + dev.getDeviceName() + "...");

        if (decoder != null) decoder.stop();
        if (controlClient != null) {
            controlClient.close();
            controlClient = null;
        }
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }

        // 1. Start VIDEO stream
        decoder = new ScrcpyStreamDecoder(dev.getIp(), dev.getVideoPort(), surfaceView.getHolder().getSurface(), this);
        decoder.start();

        // 2. Start CONTROL channel after 200ms
        uiHandler.postDelayed(() -> {
            if (decoder != null && currentDevice != null && currentDevice.getIp().equals(dev.getIp())) {
                controlClient = new ScrcpyControlClient(dev.getIp(), dev.getControlPort(), this);
                controlClient.connect();
            }
        }, 200);

        // 3. Start AUDIO stream after 350ms
        uiHandler.postDelayed(() -> {
            if (decoder != null && currentDevice != null && currentDevice.getIp().equals(dev.getIp())) {
                audioPlayer = new ScrcpyAudioPlayer(dev.getIp(), dev.getAudioPort());
                audioPlayer.start();
            }
        }, 350);
    }

    @Override
    public void onStreamStarted(int width, int height) {
        runOnUiThread(() -> {
            remoteWidth = width;
            remoteHeight = height;
            isConnected = true;
            statusOverlay.setVisibility(View.GONE);
            handleAutoOrientation(width, height);
            Log.i(TAG, "Stream active (" + width + "x" + height + ")!");
        });
    }

    @Override
    public void onResolutionChanged(int width, int height) {
        runOnUiThread(() -> {
            remoteWidth = width;
            remoteHeight = height;
            handleAutoOrientation(width, height);
        });
    }

    // Automatic Fullscreen / Orientation adaptation
    private void handleAutoOrientation(int w, int h) {
        if (w > h) {
            // Host is Landscape (e.g. YouTube fullscreen video) -> switch client to Landscape!
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            // Host is Portrait -> switch client to Portrait
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    @Override
    public void onStreamError(String message) {
        runOnUiThread(() -> {
            isConnected = false;
            cleanupConnections();
            statusOverlay.setVisibility(View.VISIBLE);
            statusText.setText("Reconnecting...");
        });
    }

    @Override
    public void onStreamEnded() {
        runOnUiThread(() -> {
            isConnected = false;
            cleanupConnections();
            statusOverlay.setVisibility(View.VISIBLE);
            statusText.setText("Stream ended. Reconnecting...");
            uiHandler.postDelayed(() -> {
                if (!isConnected) {
                    discovery.rescan();
                }
            }, 2000);
        });
    }

    private void cleanupConnections() {
        if (controlClient != null) {
            controlClient.close();
            controlClient = null;
        }
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
    }

    // Physical Keys Interception
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isConnected && controlClient != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (deviceSwitcherOverlay.getVisibility() == View.VISIBLE) {
                    hideDeviceSwitcher();
                    return true;
                }
                if (isFloatingMenuExpanded) {
                    collapseFloatingMenu();
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < 1500) {
                    finish();
                    return true;
                }
                lastBackPressTime = now;
                controlClient.sendKey(0, KeyEvent.KEYCODE_BACK);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                controlClient.sendKey(0, KeyEvent.KEYCODE_VOLUME_UP);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                controlClient.sendKey(0, KeyEvent.KEYCODE_VOLUME_DOWN);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isConnected && controlClient != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                controlClient.sendKey(1, KeyEvent.KEYCODE_BACK);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                controlClient.sendKey(1, KeyEvent.KEYCODE_VOLUME_UP);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                controlClient.sendKey(1, KeyEvent.KEYCODE_VOLUME_DOWN);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created. Starting discovery...");
        statusText.setText("Scanning for ZenCast Host...");
        discovery.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed");
        cleanupSession();
    }

    private void cleanupSession() {
        if (discovery != null) discovery.stop();
        if (decoder != null) decoder.stop();
        cleanupConnections();
        isConnected = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupSession();
    }
}
