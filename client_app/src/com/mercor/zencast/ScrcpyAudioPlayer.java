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

    private synchronized void initAudioTrack() {
        if (audioTrack != null) return;
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack init error: " + e.getMessage());
        }
    }

    private void runAudioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        while (running) {
            DataInputStream in = null;
            try {
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(64 * 1024);
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 4000);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 16384));

                // ── scrcpy RAW audio handshake ──────────────────────────────────
                int codecId = in.readInt();
                Log.i(TAG, String.format("Audio stream connected! Codec: 0x%08X", codecId));

                initAudioTrack();

                byte[] pcmBuf = new byte[32768];

                while (running) {
                    long pts  = in.readLong();
                    int  size = in.readInt();

                    if (size < 0 || size > 1024 * 1024) {
                        Log.w(TAG, "Invalid audio packet size: " + size + " — reconnecting");
                        break;
                    }

                    if ((pts & FLAG_CONFIG) != 0) {
                        if (size > 0) in.skipBytes(size);
                        continue;
                    }

                    if (size == 0) continue;

                    if (size > pcmBuf.length) {
                        pcmBuf = new byte[size + 4096];
                    }

                    in.readFully(pcmBuf, 0, size);

                    if (!muted) {
                        // Apply software digital pre-amp boost (2.0x gain with soft limiter)
                        for (int i = 0; i + 1 < size; i += 2) {
                            short sample = (short) ((pcmBuf[i] & 0xFF) | (pcmBuf[i + 1] << 8));
                            int amplified = (int) (sample * 2.0f);
                            if (amplified > 32767) amplified = 32767;
                            else if (amplified < -32768) amplified = -32768;
                            pcmBuf[i] = (byte) (amplified & 0xFF);
                            pcmBuf[i + 1] = (byte) ((amplified >> 8) & 0xFF);
                        }
                        try {
                            AudioTrack track = audioTrack;
                            if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                track.write(pcmBuf, 0, size, AudioTrack.WRITE_NON_BLOCKING);
                            }
                        } catch (Exception ignored) {}
                    }
                }

            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "Audio player disconnected (" + e.getMessage() + "), auto-reconnecting...");
                }
            } finally {
                closeSocketOnly();
            }

            if (running) {
                try {
                    Thread.sleep(600); // 600ms backoff before reconnecting
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }

        cleanupAll();
    }

    public void setMuted(boolean muted) { this.muted = muted; }
    public boolean isMuted() { return muted; }

    private void closeSocketOnly() {
        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception ignored) {}
    }

    private synchronized void cleanupAll() {
        closeSocketOnly();
        try {
            if (audioTrack != null) {
                try { audioTrack.stop(); } catch (Exception ignored) {}
                try { audioTrack.release(); } catch (Exception ignored) {}
                audioTrack = null;
            }
        } catch (Exception ignored) {}
    }

    public void stop() {
        running = false;
        closeSocketOnly();
        if (audioThread != null) {
            audioThread.interrupt();
            if (Thread.currentThread() != audioThread) {
                try {
                    audioThread.join(300);
                } catch (InterruptedException ignored) {}
            }
        }
        cleanupAll();
    }
}
