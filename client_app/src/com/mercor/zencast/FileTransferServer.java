package com.mercor.zencast;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FileTransferServer {
    private static final String TAG = "ZenCast_TransferServer";
    public static final int RECEIVER_PORT = 27187;
    private static final int MAGIC = 0x5A434654; // "ZCFT"
    private static final byte CMD_FILE_START = 0x01;
    private static final byte CMD_FILE_DATA  = 0x02;
    private static final byte CMD_FILE_END   = 0x03;
    private static final byte CMD_BATCH_DONE = 0x04;
    private static final byte CMD_ACK        = 0x05;

    private static volatile boolean running = false;
    private static ServerSocket serverSocket;
    private static Thread serverThread;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized void start(Context context) {
        if (running) return;
        running = true;
        Context appContext = context.getApplicationContext();

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress(RECEIVER_PORT));
                Log.i(TAG, "File Transfer Receiver listening on port " + RECEIVER_PORT);

                while (running) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleIncomingTransfer(appContext, client)).start();
                }
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Server error: " + e.getMessage());
                }
            } finally {
                running = false;
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (Exception ignored) {}
                    serverSocket = null;
                }
            }
        });
        serverThread.setName("ZenCast-FileReceiver");
        serverThread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }
        serverSocket = null;
        serverThread = null;
    }

    private static void handleIncomingTransfer(Context context, Socket socket) {
        try {
            socket.setSoTimeout(30000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            int magic = in.readInt();
            if (magic != MAGIC) {
                Log.w(TAG, String.format("Invalid magic: 0x%08X", magic));
                socket.close();
                return;
            }

            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File zenCastDir = new File(downloadDir, "ZenCast");
            if (!zenCastDir.exists()) {
                zenCastDir.mkdirs();
            }

            File currentFile = null;
            FileOutputStream fos = null;
            int receivedCount = 0;
            String lastFileName = "";

            byte[] buffer = new byte[64 * 1024];

            boolean done = false;
            while (!done) {
                byte cmd = in.readByte();
                switch (cmd) {
                    case CMD_FILE_START:
                        int nameLen = in.readInt();
                        byte[] nameBytes = new byte[nameLen];
                        in.readFully(nameBytes);
                        String fileName = new String(nameBytes, StandardCharsets.UTF_8);
                        long fileSize = in.readLong();

                        // Sanitize filename to avoid path traversal
                        fileName = new File(fileName).getName();
                        if (fileName.isEmpty()) fileName = "file_" + System.currentTimeMillis();

                        currentFile = new File(zenCastDir, fileName);
                        lastFileName = fileName;
                        fos = new FileOutputStream(currentFile);
                        Log.i(TAG, "Receiving file: " + fileName + " (" + fileSize + " bytes)");
                        break;

                    case CMD_FILE_DATA:
                        int chunkLen = in.readInt();
                        if (chunkLen > 0 && chunkLen <= buffer.length) {
                            in.readFully(buffer, 0, chunkLen);
                            if (fos != null) {
                                fos.write(buffer, 0, chunkLen);
                            }
                        } else if (chunkLen > buffer.length) {
                            byte[] bigBuf = new byte[chunkLen];
                            in.readFully(bigBuf);
                            if (fos != null) {
                                fos.write(bigBuf);
                            }
                        }
                        break;

                    case CMD_FILE_END:
                        if (fos != null) {
                            fos.flush();
                            fos.close();
                            fos = null;
                        }
                        receivedCount++;
                        if (currentFile != null) {
                            final String path = currentFile.getAbsolutePath();
                            MediaScannerConnection.scanFile(context, new String[]{path}, null, null);
                            Log.i(TAG, "File received and saved to: " + path);
                        }
                        break;

                    case CMD_BATCH_DONE:
                        done = true;
                        out.writeByte(CMD_ACK);
                        out.writeByte(0x00); // 0 = Success
                        out.flush();
                        break;

                    default:
                        Log.w(TAG, "Unknown transfer command: " + cmd);
                        done = true;
                        break;
                }
            }

            final int count = receivedCount;
            final String name = lastFileName;
            mainHandler.post(() -> {
                String msg = (count == 1)
                        ? "Received: " + name + " (Saved to Downloads/ZenCast)"
                        : "Received " + count + " files from ZenFone (Saved to Downloads/ZenCast)";
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            Log.e(TAG, "Error receiving file transfer: " + e.getMessage(), e);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }
}
