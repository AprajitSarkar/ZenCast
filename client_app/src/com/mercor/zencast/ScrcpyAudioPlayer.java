package com.mercor.zencast;

import android.media.AudioFormat;
import android.media.AudioManager;
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
        audioThread.start();
    }

    private void runAudioLoop() {
        DataInputStream in = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 4000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8192));

            // Handshake: 1 byte dummy from scrcpy
            byte dummy = in.readByte();
            // 4 bytes audio codec
            int codecId = in.readInt();
            Log.i(TAG, String.format("Audio stream connected! Codec: 0x%08X", codecId));

            // Initialize AudioTrack for 48kHz Stereo 16-bit PCM
            int minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 4096);
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    48000,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM
            );
            audioTrack.play();

            byte[] pcmBuf = new byte[16384];

            while (running) {
                // Packet header: 8 bytes PTS, 4 bytes size
                long pts = in.readLong();
                int size = in.readInt();

                if (size <= 0 || size > pcmBuf.length) {
                    if (size > pcmBuf.length) pcmBuf = new byte[size + 4096];
                    if (size <= 0) break;
                }

                in.readFully(pcmBuf, 0, size);

                if (!muted && audioTrack != null) {
                    audioTrack.write(pcmBuf, 0, size);
                }
            }

        } catch (IOException e) {
            if (running) Log.w(TAG, "Audio player disconnected or unavailable: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

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
        if (audioThread != null) {
            audioThread.interrupt();
        }
    }
}
