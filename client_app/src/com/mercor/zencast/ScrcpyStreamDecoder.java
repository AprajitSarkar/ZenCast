package com.mercor.zencast;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ScrcpyStreamDecoder {
    private static final String TAG = "ZenCast_Decoder";

    public interface StreamListener {
        void onStreamStarted(ScrcpyStreamDecoder source, int width, int height);
        void onResolutionChanged(ScrcpyStreamDecoder source, int width, int height);
        void onStreamError(ScrcpyStreamDecoder source, String message);
        void onStreamEnded(ScrcpyStreamDecoder source);
    }

    private final String host;
    private final int port;
    private final StreamListener listener;

    private volatile Surface currentSurface;
    private SurfaceTexture dummyTexture;
    private Surface dummySurface;

    private Socket socket;
    private DataInputStream in;
    private MediaCodec codec;
    private volatile boolean running = false;
    private volatile boolean stopped = false;
    private final Object codecLock = new Object();
    private Thread workerThread;
    private Thread renderThread;

    public ScrcpyStreamDecoder(String host, int port, Surface surface, StreamListener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
        if (surface != null && surface.isValid()) {
            this.currentSurface = surface;
        } else {
            try {
                dummyTexture = new SurfaceTexture(0);
                dummyTexture.setDefaultBufferSize(720, 1280);
                dummySurface = new Surface(dummyTexture);
                this.currentSurface = dummySurface;
            } catch (Exception e) {
                Log.w(TAG, "Dummy surface creation failed: " + e.getMessage());
            }
        }
    }

    public void setSurface(Surface newSurface) {
        if (newSurface != null && newSurface.isValid()) {
            this.currentSurface = newSurface;
            MediaCodec c = this.codec;
            if (c != null && running) {
                try {
                    c.setOutputSurface(newSurface);
                    Log.i(TAG, "Switched MediaCodec output surface to foreground window");
                } catch (Exception e) {
                    Log.w(TAG, "setOutputSurface to foreground failed: " + e.getMessage());
                }
            }
        } else {
            this.currentSurface = null;
            MediaCodec c = this.codec;
            if (c != null && running && dummySurface != null && dummySurface.isValid()) {
                try {
                    c.setOutputSurface(dummySurface);
                    Log.i(TAG, "Switched MediaCodec output surface to dummy background surface");
                } catch (Exception e) {
                    Log.w(TAG, "setOutputSurface to dummy failed: " + e.getMessage());
                }
            }
        }
    }

    public void start() {
        running = true;
        workerThread = new Thread(this::runDecodeLoop);
        workerThread.setName("ZenCast-Decode");
        workerThread.start();
    }

    private void runDecodeLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        boolean hasError = false;
        try {
            Log.i(TAG, "Connecting to video stream at " + host + ":" + port);
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(256 * 1024);
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 5000);
            in = new DataInputStream(socket.getInputStream());

            // 0. Handshake dummy byte from scrcpy video socket
            byte dummy = in.readByte();
            Log.i(TAG, "scrcpy handshake dummy byte: " + dummy);

            // 1. Read header: 64 bytes device name
            byte[] deviceNameBytes = new byte[64];
            in.readFully(deviceNameBytes);
            String deviceName = new String(deviceNameBytes, StandardCharsets.UTF_8).trim();
            Log.i(TAG, "Remote device name: " + deviceName);

            // 2. Read 4 bytes codec
            int codecId = in.readInt();
            Log.i(TAG, String.format("Codec ID: 0x%08X", codecId));

            // 3. Read resolution
            int firstMeta = in.readInt();
            int width;
            int height;
            if ((firstMeta & 0x80000000) != 0) {
                width = in.readInt();
                height = in.readInt();
            } else {
                width = firstMeta;
                height = in.readInt();
            }
            Log.i(TAG, "Initial stream resolution: " + width + "x" + height);

            if (width <= 0 || height <= 0) {
                width = 720;
                height = 1440;
            }

            final int finalW = width;
            final int finalH = height;
            if (listener != null && !stopped) {
                listener.onStreamStarted(this, finalW, finalH);
            }

            // 4. Initialize Hardware MediaCodec Decoder
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Realtime priority
            Surface initialSurface = (currentSurface != null && currentSurface.isValid()) ? currentSurface : dummySurface;
            codec = MediaCodec.createDecoderByType("video/avc");
            codec.configure(format, initialSurface, null, 0);
            codec.start();
            Log.i(TAG, "Hardware MediaCodec decoder initialized and started successfully (" + width + "x" + height + ")");

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] packetBuf = new byte[1024 * 1024];

            // Real-time zero-lag renderer thread (independent draining, zero lock contention)
            renderThread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                while (running) {
                    try {
                        MediaCodec c = codec;
                        if (c == null || !running) break;
                        int outIndex = c.dequeueOutputBuffer(info, 10000);
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            try {
                                MediaFormat newFormat = c.getOutputFormat();
                                int newW = (newFormat.containsKey("crop-right") && newFormat.containsKey("crop-left"))
                                        ? newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1
                                        : newFormat.getInteger(MediaFormat.KEY_WIDTH);
                                int newH = (newFormat.containsKey("crop-bottom") && newFormat.containsKey("crop-top"))
                                        ? newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1
                                        : newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                                Log.i(TAG, "MediaCodec output format changed: " + newW + "x" + newH);
                                if (listener != null && !stopped && newW > 0 && newH > 0) {
                                    listener.onResolutionChanged(this, newW, newH);
                                }
                            } catch (Exception ignored) {}
                        }
                        while (outIndex >= 0 && running) {
                            Surface s = currentSurface;
                            boolean canRender = (s != null && s.isValid() && s != dummySurface);
                            try {
                                c.releaseOutputBuffer(outIndex, canRender);
                            } catch (Exception ignored) {}
                            c = codec;
                            if (c == null || !running) break;
                            outIndex = c.dequeueOutputBuffer(info, 0);
                        }
                    } catch (Exception e) {
                        if (!running) break;
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException ignored) {}
                    }
                }
            });
            renderThread.setName("ZenCast-Render");
            renderThread.start();

            // 5. Main Feed Loop (strict 12-byte scrcpy packet header alignment)
            while (running) {
                long ptsHeader = in.readLong();
                int packetSize = in.readInt();
                if (packetSize <= 0 || packetSize > packetBuf.length) {
                    Log.w(TAG, "Invalid packet size: " + packetSize);
                    break;
                }

                in.readFully(packetBuf, 0, packetSize);

                boolean isConfig = (ptsHeader & (1L << 62)) != 0;
                long cleanPts = ptsHeader & 0x1FFFFFFFFFFFFFFFL;

                MediaCodec c = codec;
                if (c == null || !running) break;

                int inputIndex = -1;
                while (running) {
                    c = codec;
                    if (c == null || !running) break;
                    inputIndex = c.dequeueInputBuffer(10000);
                    if (inputIndex >= 0 || !running) break;
                    Thread.yield();
                }

                if (inputIndex >= 0 && running) {
                    c = codec;
                    if (c != null && running) {
                        ByteBuffer inputBuffer = c.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(packetBuf, 0, packetSize);
                            int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                            c.queueInputBuffer(inputIndex, 0, packetSize, cleanPts, flags);
                        }
                    }
                }
            }

        } catch (Exception e) {
            hasError = true;
            Log.e(TAG, "Stream decoding error: " + e, e);
            if (listener != null && running && !stopped) {
                listener.onStreamError(this, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            boolean wasRunning = running;
            running = false;
            if (renderThread != null) {
                try {
                    renderThread.interrupt();
                    renderThread.join(250);
                } catch (InterruptedException ignored) {}
            }
            cleanup();
            if (listener != null && !hasError && wasRunning && !stopped) {
                listener.onStreamEnded(this);
            }
        }
    }

    private void cleanup() {
        running = false;
        synchronized (codecLock) {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
                codec = null;
            }
        }
        try {
            if (in != null) in.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        try {
            if (dummySurface != null) {
                dummySurface.release();
                dummySurface = null;
            }
            if (dummyTexture != null) {
                dummyTexture.release();
                dummyTexture = null;
            }
        } catch (Exception ignored) {}
    }

    public boolean isRunning() {
        return running && !stopped;
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        running = false;
        try {
            if (in != null) in.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (renderThread != null) {
            renderThread.interrupt();
        }
        if (workerThread != null && Thread.currentThread() != workerThread) {
            try {
                workerThread.join(350);
            } catch (InterruptedException ignored) {}
        }
        if (renderThread != null && Thread.currentThread() != renderThread) {
            try {
                renderThread.join(250);
            } catch (InterruptedException ignored) {}
        }
        cleanup();
    }
}
