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

    public void connect() {
        senderPool.execute(() -> {
            try {
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
                if (connected) Log.w(TAG, "Device message reader stopped: " + e.getMessage());
            }
        }).start();
    }

    private final Object touchLock = new Object();
    private boolean hasPendingMove = false;
    private int pendingMoveX;
    private int pendingMoveY;
    private int pendingMoveW;
    private int pendingMoveH;
    private volatile boolean isMoveWorkerActive = false;

    public void sendTouch(final int action, final int x, final int y, final int screenW, final int screenH) {
        if (!connected) return;
        if (action == 2) { // ACTION_MOVE coalescing
            synchronized (touchLock) {
                pendingMoveX = x;
                pendingMoveY = y;
                pendingMoveW = screenW;
                pendingMoveH = screenH;
                hasPendingMove = true;
                if (!isMoveWorkerActive) {
                    isMoveWorkerActive = true;
                    senderPool.execute(this::drainPendingMoves);
                }
            }
            return;
        }

        // For ACTION_DOWN and ACTION_UP: send immediately and flush any pending move first
        senderPool.execute(() -> {
            synchronized (touchLock) {
                if (hasPendingMove) {
                    sendTouchPacket(2, pendingMoveX, pendingMoveY, pendingMoveW, pendingMoveH);
                    hasPendingMove = false;
                }
            }
            sendTouchPacket(action, x, y, screenW, screenH);
        });
    }

    private void drainPendingMoves() {
        while (connected) {
            int mx, my, mw, mh;
            synchronized (touchLock) {
                if (!hasPendingMove) {
                    isMoveWorkerActive = false;
                    return;
                }
                mx = pendingMoveX;
                my = pendingMoveY;
                mw = pendingMoveW;
                mh = pendingMoveH;
                hasPendingMove = false;
            }
            sendTouchPacket(2, mx, my, mw, mh);
        }
    }

    private void sendTouchPacket(int action, int x, int y, int screenW, int screenH) {
        try {
            if (out == null) return;
            out.writeByte(TYPE_INJECT_TOUCH);
            out.writeByte(action);          // 0=DOWN, 1=UP, 2=MOVE
            out.writeLong(0L);              // pointerId: 0L = touch (finger)
            out.writeInt(x);                // X
            out.writeInt(y);                // Y
            out.writeShort(screenW);        // Remote Screen Width
            out.writeShort(screenH);        // Remote Screen Height
            out.writeShort(0xFFFF);         // Pressure (1.0)
            out.writeInt(1);                // Action Button (PRIMARY)
            out.writeInt(1);                // Buttons (PRIMARY)
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "Send touch failed: " + e.getMessage());
        }
    }

    public void sendKey(final int action, final int keyCode) {
        if (!connected) return;
        senderPool.execute(() -> sendKeyInternal(action, keyCode));
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
        senderPool.execute(() -> {
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

    /**
     * Locks the host screen (turns display OFF).
     * Uses KEYCODE_SLEEP (223) which is safe on all OEMs including ASUS.
     */
    public void lockScreen() {
        if (!connected) return;
        senderPool.execute(() -> {
            try {
                // Safely lock host via KEYCODE_SLEEP (223) without calling dangerous SurfaceControl power mode
                // which crashes Qualcomm Snapdragon 636 DSI display panel controller into recovery loop.
                sendKeyInternal(0, KEYCODE_SLEEP);
                sendKeyInternal(1, KEYCODE_SLEEP);
                Log.i(TAG, "Sent KEYCODE_SLEEP to lock host screen safely");
            } catch (Exception e) {
                Log.w(TAG, "lockScreen failed: " + e.getMessage());
            }
        });
    }

    /**
     * Wakes the host screen (turns display ON).
     * Sends KEYCODE_WAKEUP (224), KEYCODE_POWER (26) and KEYCODE_MENU (82)
     * to wake Android's power manager cleanly and dismiss any keyguard.
     */
    public void wakeScreen() {
        if (!connected) return;
        senderPool.execute(() -> {
            try {
                // Wake device via standard Android PowerManager key events (safe on Snapdragon 636)
                sendKeyInternal(0, KEYCODE_WAKEUP);
                sendKeyInternal(1, KEYCODE_WAKEUP);
                Thread.sleep(60);
                sendKeyInternal(0, 26); // KEYCODE_POWER
                sendKeyInternal(1, 26);
                Thread.sleep(60);
                // Dismiss lockscreen / keyguard
                sendKeyInternal(0, 82); // KEYCODE_MENU
                sendKeyInternal(1, 82);
                Log.i(TAG, "Sent WAKEUP + POWER + MENU to wake host screen safely");
            } catch (Exception e) {
                Log.w(TAG, "wakeScreen failed: " + e.getMessage());
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
        senderPool.execute(() -> {
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
        senderPool.execute(() -> {
            try {
                if (out != null) out.close();
                if (in != null) in.close();
                if (socket != null) socket.close();
            } catch (IOException ignored) {}
        });
        senderPool.shutdown();
    }
}
