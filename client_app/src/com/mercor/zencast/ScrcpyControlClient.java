package com.mercor.zencast;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScrcpyControlClient {
    private static final String TAG = "ZenCast_Control";

    private static final byte TYPE_INJECT_KEYCODE    = 0;
    private static final byte TYPE_INJECT_TOUCH       = 2;
    private static final byte TYPE_GET_CLIPBOARD      = 8;
    private static final byte TYPE_SET_CLIPBOARD      = 9;
    private static final byte TYPE_SET_SCREEN_POWER   = 10; // scrcpy v2 SET_SCREEN_POWER_MODE

    // scrcpy power modes (ControlMsg::ScreenPowerMode)
    private static final byte POWER_MODE_OFF     = 0;
    private static final byte POWER_MODE_NORMAL  = 2;

    // Android KeyCodes
    private static final int KEYCODE_POWER = 26;
    private static final int KEYCODE_SLEEP = 223;  // locks screen (no ASUS remap issues)
    private static final int KEYCODE_WAKEUP = 224; // wakes screen

    public interface ClipboardListener {
        void onHostClipboardReceived(String text);
    }

    private final String host;
    private final int port;
    private final ClipboardListener clipboardListener;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private final ExecutorService senderPool = Executors.newSingleThreadExecutor();
    private volatile boolean connected = false;

    public ScrcpyControlClient(String host, int port, ClipboardListener clipboardListener) {
        this.host = host;
        this.port = port;
        this.clipboardListener = clipboardListener;
    }

    private void postCommand(Runnable r) {
        if (senderPool.isShutdown()) return;
        try {
            senderPool.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    public void connect() {
        if (connected && socket != null && !socket.isClosed()) return;
        postCommand(() -> {
            if (connected && socket != null && !socket.isClosed()) return;
            try {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 5000);
                out = new DataOutputStream(socket.getOutputStream());
                in = new DataInputStream(socket.getInputStream());
                connected = true;
                Log.i(TAG, "Connected to ZenFone control port " + port);

                // Start reader thread for device messages (clipboard sync from host)
                startDeviceMessageReader();
            } catch (IOException e) {
                Log.e(TAG, "Control connection failed: " + e.getMessage());
                connected = false;
            }
        });
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public boolean isClosed() {
        return senderPool.isShutdown();
    }

    private void startDeviceMessageReader() {
        new Thread(() -> {
            try {
                while (connected && in != null) {
                    int type = in.readUnsignedByte();
                    if (type == 0) { // TYPE_CLIPBOARD
                        int length = in.readInt();
                        if (length > 0 && length < 262144) {
                            byte[] buf = new byte[length];
                            in.readFully(buf);
                            String text = new String(buf, StandardCharsets.UTF_8);
                            Log.i(TAG, "Received clipboard from host: " + (text.length() > 20 ? text.substring(0, 20) + "..." : text));
                            if (clipboardListener != null) {
                                clipboardListener.onHostClipboardReceived(text);
                            }
                        }
                    } else if (type == 1) { // TYPE_ACK_CLIPBOARD
                        in.readLong(); // sequence
                    } else if (type == 2) { // TYPE_UHID_OUTPUT
                        in.readShort(); // id
                        int size = in.readUnsignedShort();
                        if (size > 0) in.skipBytes(size);
                    }
                }
            } catch (IOException e) {
                if (connected) {
                    Log.w(TAG, "Device message reader stopped: " + e.getMessage());
                    connected = false;
                }
            }
        }).start();
    }

    private static class TouchPoint {
        final long pointerId;
        final int x;
        final int y;
        final int screenW;
        final int screenH;
        final float pressure;

        TouchPoint(long pointerId, int x, int y, int screenW, int screenH, float pressure) {
            this.pointerId = pointerId;
            this.x = x;
            this.y = y;
            this.screenW = screenW;
            this.screenH = screenH;
            this.pressure = pressure;
        }
    }

    private final Object touchLock = new Object();
    private final java.util.Map<Long, TouchPoint> pendingMoves = new java.util.HashMap<>();
    private volatile boolean isMoveWorkerActive = false;

    public void sendTouch(final int action, final long pointerId, final int x, final int y, final int screenW, final int screenH, final float pressure) {
        if (!connected || socket == null || socket.isClosed()) {
            connect();
        }
        if (action == 2) { // ACTION_MOVE coalescing per pointerId
            synchronized (touchLock) {
                pendingMoves.put(pointerId, new TouchPoint(pointerId, x, y, screenW, screenH, pressure));
                if (!isMoveWorkerActive) {
                    isMoveWorkerActive = true;
                    postCommand(this::drainPendingMoves);
                }
            }
            return;
        }

        // For ACTION_DOWN and ACTION_UP: send immediately and flush any pending move for this pointer first
        postCommand(() -> {
            TouchPoint pending = null;
            synchronized (touchLock) {
                pending = pendingMoves.remove(pointerId);
            }
            if (pending != null) {
                sendTouchPacket(2, pending.pointerId, pending.x, pending.y, pending.screenW, pending.screenH, pending.pressure);
            }
            sendTouchPacket(action, pointerId, x, y, screenW, screenH, pressure);
        });
    }

    public void sendTouch(final int action, final int x, final int y, final int screenW, final int screenH) {
        sendTouch(action, 0L, x, y, screenW, screenH, 1.0f);
    }

    private void drainPendingMoves() {
        java.util.List<TouchPoint> batch;
        synchronized (touchLock) {
            if (pendingMoves.isEmpty()) {
                isMoveWorkerActive = false;
                return;
            }
            batch = new java.util.ArrayList<>(pendingMoves.values());
            pendingMoves.clear();
            isMoveWorkerActive = false;
        }
        for (TouchPoint p : batch) {
            sendTouchPacket(2, p.pointerId, p.x, p.y, p.screenW, p.screenH, p.pressure);
        }
    }

    private void sendTouchPacket(int action, long pointerId, int x, int y, int screenW, int screenH, float pressure) {
        try {
            if (out == null) return;
            out.writeByte(TYPE_INJECT_TOUCH);
            out.writeByte(action);          // 0=DOWN, 1=UP, 2=MOVE
            out.writeLong(pointerId);       // pointerId (finger 0, 1, 2...)
            out.writeInt(x);                // X
            out.writeInt(y);                // Y
            out.writeShort(screenW);        // Remote Screen Width
            out.writeShort(screenH);        // Remote Screen Height
            int pInt = action == 1 ? 0 : (int) (Math.max(0.1f, Math.min(1f, pressure)) * 65535f);
            if (pInt == 0 && action != 1) pInt = 0xFFFF;
            out.writeShort((short) pInt);    // Pressure (u16)
            out.writeInt(0);                 // Action Button = 0 (MUST BE 0 FOR TOUCHSCREEN)
            out.writeInt(0);                 // Buttons = 0 (MUST BE 0 FOR TOUCHSCREEN)
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "Send touch failed: " + e.getMessage());
            connected = false;
        }
    }

    public void sendKey(final int action, final int keyCode) {
        if (!connected) return;
        postCommand(() -> sendKeyInternal(action, keyCode));
    }

    private void sendKeyInternal(int action, int keyCode) {
        try {
            if (out == null) return;
            out.writeByte(TYPE_INJECT_KEYCODE);
            out.writeByte(action);          // 0=DOWN, 1=UP
            out.writeInt(keyCode);          // e.g. 4=BACK, 26=POWER, 208=POWER_MENU
            out.writeInt(0);                // repeat
            out.writeInt(0);                // metaState
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "Send key failed: " + e.getMessage());
        }
    }

    public void sendClipboard(final String text, final boolean paste) {
        if (!connected || text == null) return;
        postCommand(() -> {
            try {
                if (out == null) return;
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                out.writeByte(TYPE_SET_CLIPBOARD); // 9
                out.writeLong(0L);                 // sequence
                out.writeByte(paste ? 1 : 0);      // paste flag
                out.writeInt(textBytes.length);    // length
                out.write(textBytes);              // UTF-8 payload
                out.flush();
                Log.i(TAG, "Sent clipboard to host (" + textBytes.length + " bytes)");
            } catch (Exception e) {
                Log.w(TAG, "Send clipboard failed: " + e.getMessage());
            }
        });
    }

    public void requestHostClipboard() {
        if (!connected) return;
        postCommand(() -> {
            try {
                if (out == null) return;
                out.writeByte(TYPE_GET_CLIPBOARD); // 8
                out.writeByte(1);                  // copyKey: COPY_KEY_COPY (1)
                out.flush();
                Log.i(TAG, "Requested clipboard from host");
            } catch (Exception e) {
                Log.w(TAG, "Request clipboard failed: " + e.getMessage());
            }
        });
    }

    /**
     * Safely turns OFF the host screen backlight (brightness 0).
     * This keeps the Qualcomm DSI video pipeline active (mirroring continues seamlessly)
     * while completely powering down the physical LCD LEDs (0 light, near-zero power, NO sleep of death).
     */
    public void lockScreen() {
        setHostBacklight(0);
    }

    /**
     * Restores host screen backlight to normal (brightness 150) and dismisses any keyguard.
     */
    public void wakeScreen() {
        setHostBacklight(150);
        if (connected) {
            postCommand(() -> {
                try {
                    sendKeyInternal(0, KEYCODE_WAKEUP);
                    sendKeyInternal(1, KEYCODE_WAKEUP);
                    Thread.sleep(50);
                    sendKeyInternal(0, 82); // KEYCODE_MENU (dismiss keyguard)
                    sendKeyInternal(1, 82);
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * Sends backlight brightness command (0-255) to ZenHost TCP Discovery & Control port (27182).
     */
    public void setHostBacklight(final int brightness) {
        postCommand(() -> {
            Socket s = null;
            try {
                s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(InetAddress.getByName(host), 27182), 2000);
                java.io.OutputStream out = s.getOutputStream();
                String cmd = "SET_BACKLIGHT:" + brightness + "\n";
                out.write(cmd.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Log.i(TAG, "Sent host backlight command: " + cmd.trim());
            } catch (Exception e) {
                Log.w(TAG, "setHostBacklight failed: " + e.getMessage());
            } finally {
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /** @deprecated use lockScreen() / wakeScreen() */
    public void setDisplayPower(final boolean on) {
        if (on) wakeScreen(); else lockScreen();
    }

    /**
     * Triggers the host power menu via a 650ms long-press of KEYCODE_POWER (26).
     * This is the correct approach — KEYCODE_POWER_MENU (208) doesn't exist on Android.
     */
    public void triggerPowerMenu() {
        if (!connected) return;
        postCommand(() -> {
            try {
                sendKeyInternal(0, KEYCODE_POWER);  // DOWN
                Thread.sleep(650);                  // hold 650ms = long press threshold
                sendKeyInternal(1, KEYCODE_POWER);  // UP
                Log.i(TAG, "Triggered power menu via long-press POWER");
            } catch (Exception e) {
                Log.w(TAG, "Trigger power menu failed: " + e.getMessage());
            }
        });
    }

    public void close() {
        connected = false;
        try {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
                out = null;
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
                in = null;
            }
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
                socket = null;
            }
        } catch (Exception ignored) {}
        if (!senderPool.isShutdown()) {
            senderPool.shutdownNow();
        }
    }
}
