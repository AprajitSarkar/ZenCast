package com.mercor.zencast;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class ScrcpyAudioPlayer {
    private static final String TAG = "ZenCast_Audio";

    // scrcpy PTS flag: bit 62 set = config/header packet, NOT audio PCM data
    private static final long FLAG_CONFIG = (1L << 62);

    private final String host;
    private final int port;
    private volatile boolean running = false;
    private volatile boolean muted = false;
    private Thread audioThread;
    private Socket socket;
    private AudioTrack audioTrack;

    public ScrcpyAudioPlayer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        running = true;
        audioThread = new Thread(this::runAudioLoop);
        audioThread.setName("ZenCast-Audio");
        audioThread.start();
    }

    private void runAudioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        DataInputStream in = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(64 * 1024);
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 4000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 16384));

            // ── scrcpy RAW audio handshake ──────────────────────────────────
            // Exactly 4-byte codec ID (e.g. 0x72617700 = "raw\0", 0x61616320 = "aac ", etc.)
            // No dummy connection byte on audio socket!
            int codecId = in.readInt();
            Log.i(TAG, String.format("Audio stream connected! Codec: 0x%08X", codecId));
            // ────────────────────────────────────────────────────────────────

            // Initialize AudioTrack — 48kHz Stereo 16-bit PCM (scrcpy RAW default)
            int sampleRate  = 48000;
            int channelCfg  = AudioFormat.CHANNEL_OUT_STEREO;
            int encoding    = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf      = AudioTrack.getMinBufferSize(sampleRate, channelCfg, encoding);
            int bufSize     = Math.max(minBuf * 4, 32768);

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelCfg)
                    .setEncoding(encoding)
                    .build();

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            audioTrack.play();
            Log.i(TAG, "AudioTrack playing: 48kHz stereo PCM16, buf=" + bufSize);

            byte[] pcmBuf = new byte[32768];

            while (running) {
                // Each scrcpy audio frame: 8-byte PTS | 4-byte size | [size] bytes payload
                long pts  = in.readLong();
                int  size = in.readInt();

                if (size < 0 || size > 1024 * 1024) {
                    Log.w(TAG, "Invalid audio packet size: " + size + " — disconnecting");
                    break;
                }

                // ── CRITICAL FIX ────────────────────────────────────────────
                // scrcpy sends config/header packets BEFORE the first PCM frame.
                // These have bit 62 of PTS set and contain codec metadata, NOT audio.
                // Writing config bytes to AudioTrack causes noise → silence → desync.
                if ((pts & FLAG_CONFIG) != 0) {
                    if (size > 0) in.skipBytes(size);
                    Log.d(TAG, "Skipped audio config packet (" + size + " bytes)");
                    continue;
                }
                // ────────────────────────────────────────────────────────────

                if (size == 0) continue;

                // Grow buffer if payload is unexpectedly large
                if (size > pcmBuf.length) {
                    pcmBuf = new byte[size + 4096];
                }

                in.readFully(pcmBuf, 0, size);

                if (!muted && audioTrack != null) {
                    audioTrack.write(pcmBuf, 0, size);
                }
            }

        } catch (IOException e) {
            if (running) Log.w(TAG, "Audio player disconnected: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    public void setMuted(boolean muted) { this.muted = muted; }
    public boolean isMuted() { return muted; }

    private void cleanup() {
        running = false;
        try {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }
        } catch (Exception ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }

    public void stop() {
        running = false;
        cleanup();
        if (audioThread != null) audioThread.interrupt();
    }
}
