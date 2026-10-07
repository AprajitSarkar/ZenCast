package com.mercor.zencast;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements
        SurfaceHolder.Callback,
        DeviceDiscovery.DiscoveryCallback,
        ScrcpyStreamDecoder.StreamListener,
        ScrcpyControlClient.ClipboardListener {

    private static final String TAG = "ZenCast_Main";
    private static final String PREFS_NAME = "ZenCastPrefs";
    private static final String KEY_LAST_IP = "last_ip";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_LAST_MODEL = "last_model";
    private static final String KEY_LAST_VPORT = "last_vport";
    private static final String KEY_LAST_CPORT = "last_cport";
    private static final String KEY_LAST_APORT = "last_aport";

    private SurfaceView surfaceView;
    private FrameLayout statusOverlay;

    // Floating Controls
    private LinearLayout floatingMenuContainer;
    private ImageButton floatingMainBtn;
    private LinearLayout floatingExpandedMenu;
    private ImageButton btnDisplayPower;
    private ImageButton btnPowerMenu;
    private ImageButton btnChangeDevice;
    private ImageButton btnRotateScreen;
    private ImageButton btnClipboardSync;
    private ImageButton btnUploadFile;
    private ImageButton btnCloseMenu;

    private static final int REQUEST_CODE_PICK_FILE = 2001;

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
    private volatile boolean isConnected = false;
    private volatile boolean isConnecting = false;
    private boolean isFloatingMenuExpanded = false;
    private boolean isHostDisplayOn = true;
    private long lastBackPressTime = 0;
    private float savedExpandedY = -1f; // Y before upward expand shift

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
        ZenCastService.start(this, "ZenCast");
        FileTransferServer.start(this);

        setContentView(R.layout.activity_main);

        initViews();
        setupClipboardSync();
        setupFloatingMenu();

        // Forward media controls from Android Notification / Lockscreen to Host
        ZenCastService.setMediaControlCallback(keyCode -> {
            if (controlClient != null && isConnected) {
                controlClient.sendKey(0, keyCode);
                controlClient.sendKey(1, keyCode);
                Log.i(TAG, "Forwarded notification media key to host: " + keyCode);
            }
        });

        surfaceView.getHolder().addCallback(this);

        // Full Multi-Touch input forwarding with precise aspect-ratio scaling
        surfaceView.setOnTouchListener((v, event) -> {
            if (!isConnected && (decoder == null || !decoder.isRunning())) return false;
            if (decoder != null && decoder.isRunning()) {
                isConnected = true;
            }

            if (statusOverlay.getVisibility() != View.GONE) {
                statusOverlay.setVisibility(View.GONE);
            }

            if (controlClient == null || !controlClient.isConnected() || controlClient.isClosed()) {
                if (currentDevice != null) {
                    if (controlClient == null || controlClient.isClosed()) {
                        controlClient = new ScrcpyControlClient(currentDevice.getIp(), currentDevice.getControlPort(), this);
                    }
                    controlClient.connect();
                }
            }

            float viewW = v.getWidth();
            float viewH = v.getHeight();
            if (viewW <= 0 || viewH <= 0 || remoteWidth <= 0 || remoteHeight <= 0) return true;

            // Calculate letterbox / pillarbox offsets so touch perfectly matches the rendered video
            float scale = Math.min(viewW / (float) remoteWidth, viewH / (float) remoteHeight);
            float displayedW = remoteWidth * scale;
            float displayedH = remoteHeight * scale;
            float offsetX = (viewW - displayedW) / 2f;
            float offsetY = (viewH - displayedH) / 2f;

            int action = event.getActionMasked();
            int actionIndex = event.getActionIndex();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    long pointerId = event.getPointerId(actionIndex);
                    float px = event.getX(actionIndex);
                    float py = event.getY(actionIndex);
                    float pressure = event.getPressure(actionIndex);
                    float touchX = (px - offsetX) / displayedW * remoteWidth;
                    float touchY = (py - offsetY) / displayedH * remoteHeight;
                    int targetX = (int) Math.max(0, Math.min(remoteWidth - 1, touchX));
                    int targetY = (int) Math.max(0, Math.min(remoteHeight - 1, touchY));
                    Log.i(TAG, "Touch DOWN: (" + targetX + "," + targetY + ") ptr=" + pointerId);
                    if (controlClient != null) {
                        controlClient.sendTouch(0, pointerId, targetX, targetY, remoteWidth, remoteHeight, pressure);
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    int count = event.getPointerCount();
                    for (int i = 0; i < count; i++) {
                        long pointerId = event.getPointerId(i);
                        float px = event.getX(i);
                        float py = event.getY(i);
                        float pressure = event.getPressure(i);
                        float touchX = (px - offsetX) / displayedW * remoteWidth;
                        float touchY = (py - offsetY) / displayedH * remoteHeight;
                        int targetX = (int) Math.max(0, Math.min(remoteWidth - 1, touchX));
                        int targetY = (int) Math.max(0, Math.min(remoteHeight - 1, touchY));
                        if (controlClient != null) {
                            controlClient.sendTouch(2, pointerId, targetX, targetY, remoteWidth, remoteHeight, pressure);
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP: {
                    long pointerId = event.getPointerId(actionIndex);
                    float px = event.getX(actionIndex);
                    float py = event.getY(actionIndex);
                    float pressure = event.getPressure(actionIndex);
                    float touchX = (px - offsetX) / displayedW * remoteWidth;
                    float touchY = (py - offsetY) / displayedH * remoteHeight;
                    int targetX = (int) Math.max(0, Math.min(remoteWidth - 1, touchX));
                    int targetY = (int) Math.max(0, Math.min(remoteHeight - 1, touchY));
                    Log.i(TAG, "Touch UP: (" + targetX + "," + targetY + ") ptr=" + pointerId);
                    if (controlClient != null) {
                        controlClient.sendTouch(1, pointerId, targetX, targetY, remoteWidth, remoteHeight, pressure);
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL: {
                    int count = event.getPointerCount();
                    for (int i = 0; i < count; i++) {
                        long pointerId = event.getPointerId(i);
                        float px = event.getX(i);
                        float py = event.getY(i);
                        float touchX = (px - offsetX) / displayedW * remoteWidth;
                        float touchY = (py - offsetY) / displayedH * remoteHeight;
                        int targetX = (int) Math.max(0, Math.min(remoteWidth - 1, touchX));
                        int targetY = (int) Math.max(0, Math.min(remoteHeight - 1, touchY));
                        if (controlClient != null) {
                            controlClient.sendTouch(1, pointerId, targetX, targetY, remoteWidth, remoteHeight, 0f);
                        }
                    }
                    break;
                }
            }
            return true;
        });

        discovery = new DeviceDiscovery(this, this);
        DiscoveredDevice lastDev = getLastConnectedDevice();
        if (lastDev != null) {
            discovery.setPreferredIp(lastDev.getIp());
        }
        startAutoReconnect("onCreate");

        checkAndRequestStoragePermissions();
    }

    private void checkAndRequestStoragePermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, 1001);
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "Storage permission check error: " + ex.getMessage());
        }
    }

    private void initViews() {
        surfaceView = findViewById(R.id.surface_view);
        statusOverlay = findViewById(R.id.status_overlay);

        floatingMenuContainer = findViewById(R.id.floating_menu_container);
        floatingMainBtn = findViewById(R.id.floating_main_btn);
        floatingExpandedMenu = findViewById(R.id.floating_expanded_menu);
        btnDisplayPower = findViewById(R.id.btn_display_power);
        btnPowerMenu = findViewById(R.id.btn_power_menu);
        btnChangeDevice = findViewById(R.id.btn_change_device);
        btnRotateScreen = findViewById(R.id.btn_rotate_screen);
        btnClipboardSync = findViewById(R.id.btn_clipboard_sync);
        btnUploadFile = findViewById(R.id.btn_upload_file);
        btnCloseMenu = findViewById(R.id.btn_close_menu);

        deviceSwitcherOverlay = findViewById(R.id.device_switcher_overlay);
        deviceListContainer = findViewById(R.id.device_list_container);

        Button btnPresetRedmi = findViewById(R.id.btn_preset_redmi);
        if (btnPresetRedmi != null) {
            btnPresetRedmi.setOnClickListener(v -> {
                hideDeviceSwitcher();
                DiscoveredDevice redmi = new DiscoveredDevice("192.168.1.172", "Redmi Note 7S", "Redmi Note 7S", 27183, 27184, 27185);
                switchDevice(redmi);
            });
        }

        Button btnPresetAsus = findViewById(R.id.btn_preset_asus);
        if (btnPresetAsus != null) {
            btnPresetAsus.setOnClickListener(v -> {
                hideDeviceSwitcher();
                DiscoveredDevice asus = new DiscoveredDevice("192.168.1.129", "ZenFone Max Pro M1", "ZenFone Max Pro M1", 27183, 27184, 27185);
                switchDevice(asus);
            });
        }

        EditText editManualIp = findViewById(R.id.edit_manual_ip);
        Button btnConnectManual = findViewById(R.id.btn_connect_manual);
        if (btnConnectManual != null && editManualIp != null) {
            btnConnectManual.setOnClickListener(v -> {
                String ip = editManualIp.getText().toString().trim();
                if (ip.isEmpty()) {
                    Toast.makeText(this, "Enter host IP address", Toast.LENGTH_SHORT).show();
                    return;
                }
                hideDeviceSwitcher();
                Toast.makeText(this, "Connecting to " + ip + "...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    DiscoveredDevice dev = DeviceDiscovery.probeHostDirect(ip, 1200);
                    if (dev == null) {
                        dev = new DiscoveredDevice(ip, "ZenCast Host", "ZenCast Host", 27183, 27184, 27185);
                    }
                    final DiscoveredDevice target = dev;
                    uiHandler.post(() -> switchDevice(target));
                }).start();
            });
        }

        findViewById(R.id.btn_rescan_devices).setOnClickListener(v -> {
            Toast.makeText(this, "Scanning for ZenCast hosts...", Toast.LENGTH_SHORT).show();
            TextView statusText = findViewById(R.id.text_discovery_status);
            if (statusText != null) statusText.setText("Scanning subnet for ZenCast hosts...");
            if (discovery != null) {
                discovery.rescan();
            }
        });

        findViewById(R.id.btn_dismiss_device_switcher).setOnClickListener(v -> hideDeviceSwitcher());
    }

    private void setupClipboardSync() {
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(() -> {
                if (isSyncingClipboard || !isConnected || controlClient == null) return;
                syncClientClipboardToHost(false);
            });
        }
    }

    private void syncClientClipboardToHost(boolean notifyUser) {
        if (clipboardManager == null || controlClient == null || !isConnected) return;
        try {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null && text.length() > 0) {
                    String str = text.toString();
                    if (!str.equals(lastSyncedClipboard) || notifyUser) {
                        lastSyncedClipboard = str;
                        controlClient.sendClipboard(str, false);
                        if (notifyUser) {
                            String preview = str.length() > 25 ? str.substring(0, 25) + "..." : str;
                            Toast.makeText(this, "Copied to Host: " + preview, Toast.LENGTH_SHORT).show();
                        }
                    }
                    return;
                }
            }
            if (notifyUser) {
                controlClient.requestHostClipboard();
                Toast.makeText(this, "Syncing Host Clipboard...", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
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
                String preview = text.length() > 25 ? text.substring(0, 25) + "..." : text;
                Toast.makeText(this, "Copied from Host: " + preview, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    private void setupFloatingMenu() {
        // Set initial position via code (top-right corner) AFTER layout is measured
        // Avoids negative translationX from XML margins which causes invisible-after-drag glitch
        floatingMenuContainer.post(() -> {
            View p = (View) floatingMenuContainer.getParent();
            int screenW = p != null ? p.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int margin = (int) (16 * getResources().getDisplayMetrics().density);
            floatingMenuContainer.setX(screenW - floatingMenuContainer.getWidth() - margin);
            floatingMenuContainer.setY(margin * 2); // ~32dp from top
            floatingMenuContainer.bringToFront();
        });

        floatingMainBtn.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float downRawX, downRawY;
            private boolean isDragging = false;
            private final float touchSlop = 10f;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        dX = floatingMenuContainer.getX() - downRawX;
                        dY = floatingMenuContainer.getY() - downRawY;
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float diffX = event.getRawX() - downRawX;
                        float diffY = event.getRawY() - downRawY;
                        if (!isDragging && (Math.abs(diffX) > touchSlop || Math.abs(diffY) > touchSlop)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            View parent = (View) floatingMenuContainer.getParent();
                            int parentW = parent != null ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels;
                            int parentH = parent != null ? parent.getHeight() : getResources().getDisplayMetrics().heightPixels;

                            int viewW = floatingMenuContainer.getWidth();
                            int viewH = floatingMenuContainer.getHeight();
                            if (viewW <= 0) viewW = v.getWidth();
                            if (viewH <= 0) viewH = v.getHeight();

                            float targetX = event.getRawX() + dX;
                            float targetY = event.getRawY() + dY;

                            // Hard clamp — icon can NEVER go off screen
                            float minX = 0f;
                            float maxX = Math.max(0f, parentW - viewW);
                            float minY = 0f;
                            float maxY = Math.max(0f, parentH - viewH);

                            floatingMenuContainer.setX(Math.max(minX, Math.min(maxX, targetX)));
                            floatingMenuContainer.setY(Math.max(minY, Math.min(maxY, targetY)));
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            toggleFloatingMenu();
                        } else {
                            // CRITICAL: bringToFront + invalidate fixes invisible-after-drag GPU layer glitch
                            floatingMenuContainer.bringToFront();
                            floatingMenuContainer.invalidate();
                            ((View) floatingMenuContainer.getParent()).invalidate();
                        }
                        return true;
                }
                return false;
            }
        });

        // 1. Lock / Screen Power Toggle
        // Uses lockScreen()/wakeScreen() which send the correct scrcpy power mode bytes
        // (NOT writeBoolean which was causing ASUS to open calendar)
        btnDisplayPower.setOnClickListener(v -> {
            if (controlClient != null) {
                isHostDisplayOn = !isHostDisplayOn;
                if (isHostDisplayOn) {
                    controlClient.wakeScreen();
                    Toast.makeText(this, "Host Screen: ON", Toast.LENGTH_SHORT).show();
                } else {
                    controlClient.lockScreen();
                    Toast.makeText(this, "Host Screen: OFF (Mirror continues)", Toast.LENGTH_SHORT).show();
                }
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

        // 5. Manual Clipboard Push & Sync
        btnClipboardSync.setOnClickListener(v -> {
            if (controlClient != null && isConnected) {
                syncClientClipboardToHost(true);
                controlClient.requestHostClipboard();
            } else {
                Toast.makeText(this, "Host not connected", Toast.LENGTH_SHORT).show();
            }
        });

        // 6. Upload / Send File to Host
        if (btnUploadFile != null) {
            btnUploadFile.setOnClickListener(v -> pickAndUploadFiles());
        }

        // 7. Close Menu
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
        floatingExpandedMenu.animate().cancel();

        // ── Pre-shift the container UP before the menu is revealed ──────────
        // Using post() is too late (layout hasn't remeasured yet) → menu clips.
        // Instead, estimate menu height from fixed dp values and shift NOW.
        View parent = (View) floatingMenuContainer.getParent();
        int screenH = parent != null ? parent.getHeight() : getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        // Menu: 5×34dp buttons + 1×30dp close + 5×8dp margins = ~240dp
        int menuEstH = (int)(240 * density);
        int btnH = floatingMenuContainer.getHeight(); // height of just the main icon
        if (btnH <= 0) btnH = (int)(38 * density);

        float iconY = floatingMenuContainer.getY();
        float spaceBelow = screenH - iconY - btnH;

        if (spaceBelow < menuEstH + (int)(16 * density)) {
            // Not enough space below — shift the container UP so menu appears above the fold
            savedExpandedY = iconY;
            float targetY = Math.max(0f, iconY - (menuEstH - spaceBelow) - (int)(16 * density));
            floatingMenuContainer.setY(targetY); // instant shift BEFORE menu appears
        } else {
            savedExpandedY = -1f;
        }
        // ───────────────────────────────────────────────────────────────────

        floatingExpandedMenu.setVisibility(View.VISIBLE);
        floatingExpandedMenu.setAlpha(0f);
        floatingExpandedMenu.animate()
                .alpha(1f)
                .setDuration(180)
                .setListener(null)
                .start();
    }

    private void collapseFloatingMenu() {
        isFloatingMenuExpanded = false;
        floatingExpandedMenu.animate().cancel();
        floatingExpandedMenu.animate()
                .alpha(0f)
                .setDuration(160)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        floatingExpandedMenu.setVisibility(View.GONE);
                        // Snap back to the dragged position after menu is hidden
                        if (savedExpandedY >= 0f) {
                            floatingMenuContainer.animate()
                                    .y(savedExpandedY)
                                    .setDuration(120)
                                    .setListener(null)
                                    .start();
                            savedExpandedY = -1f;
                        }
                    }
                })
                .start();
    }

    private void pickAndUploadFiles() {
        if (!isConnected || currentDevice == null) {
            Toast.makeText(this, "Connect to ZenCast first", Toast.LENGTH_SHORT).show();
            return;
        }
        collapseFloatingMenu();
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("*/*");
                fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(fallback, "Select Files to Send"), REQUEST_CODE_PICK_FILE);
            } catch (Exception ex) {
                Toast.makeText(this, "Unable to open file picker: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }

            if (!uris.isEmpty() && currentDevice != null) {
                final String hostName = currentDevice.getDeviceName();
                Toast.makeText(this, "Uploading " + uris.size() + " file(s) to " + hostName + "...", Toast.LENGTH_SHORT).show();
                FileTransferClient.uploadFiles(this, currentDevice.getIp(), 27186, uris, new FileTransferClient.TransferCallback() {
                    @Override
                    public void onProgress(String filename, int current, int total) {
                        uiHandler.post(() -> Toast.makeText(MainActivity.this, "Uploading (" + current + "/" + total + "): " + filename, Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onSuccess(int totalFiles) {
                        uiHandler.post(() -> Toast.makeText(MainActivity.this, "Uploaded " + totalFiles + " file(s) to " + hostName + " (/sdcard/Download/ZenCast/)!", Toast.LENGTH_LONG).show());
                    }

                    @Override
                    public void onError(String error) {
                        uiHandler.post(() -> Toast.makeText(MainActivity.this, "Upload failed: " + error, Toast.LENGTH_LONG).show());
                    }
                });
            }
        }
    }

    private String lastDisplayedDevicesHash = "";

    private void showDeviceSwitcher() {
        collapseFloatingMenu();
        lastDisplayedDevicesHash = "";
        if (discovery != null) {
            discovery.clearPreferredIp();
            if (currentDevice != null) {
                discovery.addKnownDevice(currentDevice);
            }
            discovery.start();
            updateDeviceListView(discovery.getDevices());
            discovery.rescan();
        } else {
            updateDeviceListView(java.util.Collections.emptyList());
        }
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

    private boolean isDeviceSwitcherOpen() {
        return deviceSwitcherOverlay != null && deviceSwitcherOverlay.getVisibility() == View.VISIBLE;
    }

    private synchronized void switchDevice(DiscoveredDevice dev) {
        if (isFinishing() || dev == null) return;
        if (isConnected && currentDevice != null && currentDevice.getIp().equals(dev.getIp()) && decoder != null && decoder.isRunning()) {
            Toast.makeText(this, "Already connected to " + dev.getDeviceName(), Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "Switching host device to: " + dev.getDeviceName() + " (" + dev.getIp() + ")");
        Toast.makeText(this, "Connecting to " + dev.getDeviceName() + "...", Toast.LENGTH_SHORT).show();

        // 1. Reset state and cancel any discovery/reconnect tasks
        isConnected = false;
        isConnecting = true;
        uiHandler.removeCallbacks(reconnectRunnable);
        uiHandler.removeCallbacksAndMessages(null);
        if (discovery != null) {
            discovery.stop();
        }

        // 2. Stop decoder & connections cleanly
        cleanupConnections();

        // 3. Connect to selected device with a delay so hardware surface unbinds cleanly
        uiHandler.postDelayed(() -> {
            if (!isFinishing()) {
                isConnecting = false;
                connectToDevice(dev);
            }
        }, 250);
    }

    private void updateDeviceListView(List<DiscoveredDevice> devices) {
        StringBuilder sb = new StringBuilder();
        for (DiscoveredDevice d : devices) {
            sb.append(d.getIp()).append(",").append(d.getDeviceName()).append(";");
        }
        String newHash = sb.toString();
        if (newHash.equals(lastDisplayedDevicesHash) && deviceListContainer.getChildCount() > 0) {
            return;
        }
        lastDisplayedDevicesHash = newHash;

        deviceListContainer.removeAllViews();
        TextView statusText = findViewById(R.id.text_discovery_status);

        if (devices.isEmpty()) {
            if (statusText != null) statusText.setText("Scanning Wi-Fi subnet for ZenCast hosts...");
            TextView emptyView = new TextView(this);
            emptyView.setText("Scanning Wi-Fi network...\nTap a preset below or enter IP to connect.");
            emptyView.setTextColor(Color.parseColor("#94A3B8"));
            emptyView.setTextSize(13);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(16, 24, 16, 24);
            deviceListContainer.addView(emptyView);
            return;
        }

        if (statusText != null) {
            statusText.setText(devices.size() + " host" + (devices.size() > 1 ? "s" : "") + " detected online");
        }

        for (DiscoveredDevice dev : devices) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackgroundResource(R.drawable.bg_device_item);
            item.setPadding(20, 16, 20, 16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 10);
            item.setLayoutParams(lp);

            TextView nameView = new TextView(this);
            nameView.setText("🖥 " + dev.getDeviceName());
            nameView.setTextColor(Color.WHITE);
            nameView.setTextSize(15);
            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
            item.addView(nameView);

            TextView subView = new TextView(this);
            boolean isCur = isConnected && currentDevice != null && currentDevice.getIp().equals(dev.getIp());
            subView.setText(dev.getIp() + (isCur ? "  ● Connected" : "  ● Tap to Connect"));
            subView.setTextColor(isCur ? Color.parseColor("#34D399") : Color.parseColor("#38BDF8"));
            subView.setTextSize(12);
            item.addView(subView);

            item.setOnClickListener(v -> {
                hideDeviceSwitcher();
                switchDevice(dev);
            });

            deviceListContainer.addView(item);
        }
    }

    private void saveLastConnectedDevice(DiscoveredDevice dev) {
        if (dev == null || dev.getIp() == null) return;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_LAST_IP, dev.getIp())
                .putString(KEY_LAST_NAME, dev.getDeviceName())
                .putString(KEY_LAST_MODEL, dev.getModel())
                .putInt(KEY_LAST_VPORT, dev.getVideoPort())
                .putInt(KEY_LAST_CPORT, dev.getControlPort())
                .putInt(KEY_LAST_APORT, dev.getAudioPort())
                .apply();
        Log.i(TAG, "Saved last connected device: " + dev.getIp() + " (" + dev.getDeviceName() + ")");
    }

    private DiscoveredDevice getLastConnectedDevice() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!sp.contains(KEY_LAST_IP)) return null;
        String ip = sp.getString(KEY_LAST_IP, null);
        if (ip == null || ip.trim().isEmpty()) return null;
        String model = sp.getString(KEY_LAST_MODEL, "Android Device");
        String name = sp.getString(KEY_LAST_NAME, model);
        int vport = sp.getInt(KEY_LAST_VPORT, DeviceDiscovery.VIDEO_PORT);
        int cport = sp.getInt(KEY_LAST_CPORT, DeviceDiscovery.CONTROL_PORT);
        int aport = sp.getInt(KEY_LAST_APORT, DeviceDiscovery.AUDIO_PORT);
        return new DiscoveredDevice(ip, model, name, vport, cport, aport);
    }

    private int reconnectAttempts = 0;
    private volatile boolean isAutoReconnecting = false;

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected || isConnecting || isFinishing() || isDeviceSwitcherOpen() || (decoder != null && decoder.isRunning())) {
                return;
            }

            reconnectAttempts++;
            DiscoveredDevice lastDev = getLastConnectedDevice();

            // ONLY show centered progress bar when not connected and no stream running
            if (!isConnected && (decoder == null || !decoder.isRunning()) && !isDeviceSwitcherOpen()) {
                statusOverlay.setVisibility(View.VISIBLE);
            }

            new Thread(() -> {
                if (isConnected || isConnecting || isFinishing() || isDeviceSwitcherOpen() || (decoder != null && decoder.isRunning())) return;

                // 1. Direct high-speed TCP probe to last known IP
                if (lastDev != null) {
                    DiscoveredDevice direct = DeviceDiscovery.probeHostDirect(lastDev.getIp(), 1200);
                    if (direct != null) {
                        uiHandler.post(() -> {
                            if (!isConnected && !isConnecting && !isFinishing() && !isDeviceSwitcherOpen() && (decoder == null || !decoder.isRunning())) {
                                Log.i(TAG, "Direct auto-reconnect probe found last device at " + direct.getIp());
                                connectToDevice(direct);
                            }
                        });
                        return;
                    }
                }

                // 2. Subnet discovery scan to catch dynamic IP changes or newly available devices
                if (!isConnected && !isConnecting && !isFinishing() && !isDeviceSwitcherOpen() && (decoder == null || !decoder.isRunning())) {
                    if (discovery != null) {
                        discovery.rescan();
                    }
                    uiHandler.postDelayed(reconnectRunnable, 3000);
                }
            }).start();
        }
    };

    private void startAutoReconnect(String reason) {
        if (isFinishing() || isConnected || isConnecting || (decoder != null && decoder.isRunning()) || isDeviceSwitcherOpen()) {
            Log.i(TAG, "startAutoReconnect suppressed (" + reason + "): connecting, stream active or switcher open");
            return;
        }
        Log.i(TAG, "startAutoReconnect triggered: " + reason);
        isConnected = false;
        cleanupConnections();

        uiHandler.removeCallbacks(reconnectRunnable);
        if (!isAutoReconnecting) {
            reconnectAttempts = 0;
            isAutoReconnecting = true;
        }

        DiscoveredDevice lastDev = getLastConnectedDevice();
        if (lastDev != null && discovery != null) {
            discovery.setPreferredIp(lastDev.getIp());
        }

        if (!isConnected && (decoder == null || !decoder.isRunning()) && !isDeviceSwitcherOpen()) {
            statusOverlay.setVisibility(View.VISIBLE);
        }

        // Priority probe: if we know the device left last time, probe it immediately via direct TCP
        if (lastDev != null) {
            new Thread(() -> {
                Log.i(TAG, "Prioritizing last connected device directly: " + lastDev.getIp());
                DiscoveredDevice direct = DeviceDiscovery.probeHostDirect(lastDev.getIp(), 1200);
                if (direct != null) {
                    uiHandler.post(() -> {
                        if (!isConnected && !isConnecting && !isFinishing() && !isDeviceSwitcherOpen() && (decoder == null || !decoder.isRunning())) {
                            Log.i(TAG, "Direct probe connected to last used device: " + direct.getIp());
                            connectToDevice(direct);
                        }
                    });
                } else {
                    Log.i(TAG, "Last used device " + lastDev.getIp() + " unreachable directly, starting general discovery");
                    uiHandler.post(() -> {
                        if (!isConnected && !isConnecting && !isFinishing() && !isDeviceSwitcherOpen() && (decoder == null || !decoder.isRunning())) {
                            if (discovery != null) {
                                discovery.start();
                            }
                            uiHandler.postDelayed(reconnectRunnable, 2500);
                        }
                    });
                }
            }).start();
        } else {
            if (discovery != null) {
                discovery.start();
            }
            uiHandler.postDelayed(reconnectRunnable, 1500);
        }
    }

    private void stopAutoReconnect() {
        isAutoReconnecting = false;
        reconnectAttempts = 0;
        uiHandler.removeCallbacks(reconnectRunnable);
        if (discovery != null) {
            discovery.stop();
        }
    }

    // Discovery Callbacks
    @Override
    public void onSingleDeviceFound(DiscoveredDevice device) {
        Log.i(TAG, "Host found: " + device);
        if (isDeviceSwitcherOpen()) {
            return;
        }
        if (!isConnected && !isConnecting && !isFinishing() && (decoder == null || !decoder.isRunning())) {
            DiscoveredDevice lastDev = getLastConnectedDevice();
            if (lastDev != null && !device.getIp().equals(lastDev.getIp())) {
                // Another device was discovered, quick check (400ms) if lastDev is alive
                new Thread(() -> {
                    DiscoveredDevice pref = DeviceDiscovery.probeHostDirect(lastDev.getIp(), 400);
                    uiHandler.post(() -> {
                        if (!isConnected && !isConnecting && !isFinishing() && !isDeviceSwitcherOpen() && (decoder == null || !decoder.isRunning())) {
                            if (pref != null) {
                                Log.i(TAG, "Prioritizing last used device " + pref.getIp() + " over " + device.getIp());
                                connectToDevice(pref);
                            } else {
                                Log.i(TAG, "Last used device offline, auto-connecting to discovered device: " + device.getIp());
                                Toast.makeText(MainActivity.this, "Connecting to " + device.getDeviceName() + "...", Toast.LENGTH_SHORT).show();
                                connectToDevice(device);
                            }
                        }
                    });
                }).start();
                return;
            }
            connectToDevice(device);
        }
    }

    @Override
    public void onMultipleDevicesFound(List<DiscoveredDevice> devices) {
        Log.i(TAG, "Multiple hosts found: " + devices.size());
        if (isDeviceSwitcherOpen()) {
            updateDeviceListView(devices);
            return;
        }
        if (!isConnected && !isConnecting && !isFinishing() && (decoder == null || !decoder.isRunning())) {
            DiscoveredDevice lastDev = getLastConnectedDevice();
            if (lastDev != null) {
                for (DiscoveredDevice d : devices) {
                    if (d.getIp().equals(lastDev.getIp())) {
                        Log.i(TAG, "Auto-connecting to last used device from multiple: " + d.getIp());
                        connectToDevice(d);
                        return;
                    }
                }
            }
            showDeviceSwitcher();
        }
    }

    @Override
    public void onDeviceListUpdated(List<DiscoveredDevice> devices) {
        if (isDeviceSwitcherOpen()) {
            updateDeviceListView(devices);
            return;
        }
        // If not connected and not streaming, and devices are discovered:
        if (!isConnected && !isConnecting && (decoder == null || !decoder.isRunning()) && !devices.isEmpty()) {
            DiscoveredDevice lastDev = getLastConnectedDevice();
            boolean lastDevInList = false;
            if (lastDev != null) {
                for (DiscoveredDevice d : devices) {
                    if (d.getIp().equals(lastDev.getIp())) {
                        lastDevInList = true;
                        break;
                    }
                }
            }
            if (!lastDevInList && !isConnecting && !isDeviceSwitcherOpen()) {
                if (devices.size() == 1) {
                    Log.i(TAG, "Last device offline, auto-connecting to only available host: " + devices.get(0));
                    connectToDevice(devices.get(0));
                } else {
                    showDeviceSwitcher();
                }
            }
        }
    }

    @Override
    public void onNoDevicesFound() {
        if (!isConnected && !isConnecting && !isFinishing() && !isDeviceSwitcherOpen() && (decoder == null || !decoder.isRunning())) {
            runOnUiThread(() -> {
                if (!isConnected && !isConnecting && !isDeviceSwitcherOpen() && (decoder == null || !decoder.isRunning())) {
                    // Show device switcher with presets and manual IP so user isn't stuck on a blank loading screen
                    showDeviceSwitcher();
                }
            });
        }
    }

    private synchronized void connectToDevice(DiscoveredDevice dev) {
        if (isFinishing() || dev == null) return;
        if (isConnected && currentDevice != null && currentDevice.getIp().equals(dev.getIp()) && decoder != null && decoder.isRunning()) {
            Log.i(TAG, "Already connected to " + dev.getIp() + ", ignoring redundant trigger");
            return;
        }
        if (isConnecting) {
            Log.i(TAG, "Connection already in progress, ignoring trigger for " + dev.getIp());
            return;
        }
        isConnecting = true;
        uiHandler.removeCallbacks(reconnectRunnable);
        if (discovery != null) {
            discovery.stop();
        }
        this.currentDevice = dev;
        saveLastConnectedDevice(dev);
        if (discovery != null) {
            discovery.setPreferredIp(dev.getIp());
        }
        Log.i(TAG, "Initiating connection to " + dev.getDeviceName() + " (" + dev.getIp() + ")");

        // Show centered progress bar
        statusOverlay.setVisibility(View.VISIBLE);

        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
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

        // 2. Start CONTROL channel after 400ms
        uiHandler.postDelayed(() -> {
            if (decoder != null && currentDevice != null && currentDevice.getIp().equals(dev.getIp())) {
                controlClient = new ScrcpyControlClient(dev.getIp(), dev.getControlPort(), this);
                controlClient.connect();
                // Send wake up to ensure host screen is active
                uiHandler.postDelayed(() -> {
                    if (controlClient != null) {
                        controlClient.wakeScreen();
                    }
                }, 300);
            }
        }, 400);

        // 3. Start AUDIO stream after 700ms
        uiHandler.postDelayed(() -> {
            if (decoder != null && currentDevice != null && currentDevice.getIp().equals(dev.getIp())) {
                audioPlayer = new ScrcpyAudioPlayer(dev.getIp(), dev.getAudioPort());
                audioPlayer.start();
            }
        }, 700);
    }

    @Override
    public void onStreamStarted(ScrcpyStreamDecoder source, int width, int height) {
        runOnUiThread(() -> {
            if (this.decoder != source) {
                Log.w(TAG, "Ignoring onStreamStarted from obsolete decoder");
                return;
            }
            stopAutoReconnect();
            if (discovery != null) {
                discovery.stop();
            }
            remoteWidth = width;
            remoteHeight = height;
            isConnected = true;
            isConnecting = false;
            statusOverlay.setVisibility(View.GONE);
            handleAutoOrientation(width, height);
            ZenCastService.start(this, currentDevice != null ? currentDevice.getDeviceName() : "ZenFone");
            FileTransferServer.start(this);
            Log.i(TAG, "Stream active (" + width + "x" + height + ") with background service!");
        });
    }

    @Override
    public void onResolutionChanged(ScrcpyStreamDecoder source, int width, int height) {
        runOnUiThread(() -> {
            if (this.decoder != source) return;
            boolean orientationFlip = (remoteWidth > remoteHeight) != (width > height);
            boolean significantChange = Math.abs(remoteWidth - width) > 32 || Math.abs(remoteHeight - height) > 32;
            if (orientationFlip || significantChange) {
                Log.i(TAG, "Resolution changed from " + remoteWidth + "x" + remoteHeight + " to " + width + "x" + height);
                remoteWidth = width;
                remoteHeight = height;
                handleAutoOrientation(width, height);
            }
        });
    }

    // Automatic Fullscreen / Orientation adaptation
    private void handleAutoOrientation(int w, int h) {
        int currentOrientation = getResources().getConfiguration().orientation;
        if (w > h && currentOrientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else if (w <= h && currentOrientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    @Override
    public void onStreamError(ScrcpyStreamDecoder source, String message) {
        runOnUiThread(() -> {
            if (this.decoder != source) {
                Log.i(TAG, "Ignoring onStreamError from obsolete decoder: " + message);
                return;
            }
            Log.w(TAG, "onStreamError: " + message);
            if (source.isRunning()) {
                Log.i(TAG, "Decoder still running, ignoring transient error: " + message);
                return;
            }
            if (isConnected || isConnecting) {
                isConnected = false;
                isConnecting = false;
                cleanupConnections();
                uiHandler.postDelayed(() -> startAutoReconnect("Error: " + message), 1500);
            }
        });
    }

    @Override
    public void onStreamEnded(ScrcpyStreamDecoder source) {
        runOnUiThread(() -> {
            if (this.decoder != source) {
                Log.i(TAG, "Ignoring onStreamEnded from obsolete decoder");
                return;
            }
            Log.i(TAG, "onStreamEnded");
            if (isConnected || isConnecting) {
                isConnected = false;
                isConnecting = false;
                cleanupConnections();
                uiHandler.postDelayed(() -> startAutoReconnect("Stream Ended"), 1500);
            }
        });
    }

    private void cleanupConnections() {
        if (decoder != null) {
            try {
                decoder.setSurface(null);
            } catch (Exception ignored) {}
            decoder.stop();
            decoder = null;
        }
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
            if (now - lastBackPressTime > 2000) {
                lastBackPressTime = now;
                if (controlClient != null && isConnected) {
                    controlClient.sendKey(0, KeyEvent.KEYCODE_BACK);
                    controlClient.sendKey(1, KeyEvent.KEYCODE_BACK);
                }
                Toast.makeText(this, "Press BACK again to exit ZenCast", Toast.LENGTH_SHORT).show();
                return true;
            } else {
                finish();
                return true;
            }
        } else if (isConnected && controlClient != null) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
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
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (isConnected && controlClient != null) {
            syncClientClipboardToHost(false);
        }
        if (decoder != null && decoder.isRunning() && surfaceView.getHolder().getSurface() != null && surfaceView.getHolder().getSurface().isValid()) {
            decoder.setSurface(surfaceView.getHolder().getSurface());
            statusOverlay.setVisibility(View.GONE);
            isConnected = true;
        } else if (!isConnected && !isConnecting && (decoder == null || !decoder.isRunning())) {
            startAutoReconnect("onResume");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created");
        if (decoder != null && decoder.isRunning()) {
            Log.i(TAG, "Restoring decoder output to active visible surface");
            decoder.setSurface(holder.getSurface());
            statusOverlay.setVisibility(View.GONE);
            isConnected = true;
        } else if (!isConnecting && !isConnected && (decoder == null || !decoder.isRunning())) {
            startAutoReconnect("surfaceCreated");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed - backgrounding decoder (audio & connection preserved)");
        if (decoder != null) {
            decoder.setSurface(null);
        }
    }

    private void cleanupSession() {
        stopAutoReconnect();
        ZenCastService.stop(this);
        FileTransferServer.stop();
        if (discovery != null) discovery.stop();
        if (decoder != null) decoder.stop();
        cleanupConnections();
        isConnected = false;
        isConnecting = false;
    }

    @Override
    protected void onDestroy() {
        stopAutoReconnect();
        super.onDestroy();
        Log.i(TAG, "MainActivity onDestroy (isFinishing=" + isFinishing() + ")");
        if (isFinishing()) {
            cleanupSession();
        }
    }
}
