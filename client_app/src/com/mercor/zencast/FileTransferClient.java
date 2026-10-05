package com.mercor.zencast;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class FileTransferClient {
    private static final String TAG = "ZenCast_TransferClient";
    private static final int MAGIC = 0x5A434654; // "ZCFT"
    private static final byte CMD_FILE_START = 0x01;
    private static final byte CMD_FILE_DATA  = 0x02;
    private static final byte CMD_FILE_END   = 0x03;
    private static final byte CMD_BATCH_DONE = 0x04;
    private static final byte CMD_ACK        = 0x05;

    public interface TransferCallback {
        void onProgress(String filename, int current, int total);
        void onSuccess(int totalFiles);
        void onError(String error);
    }

    public static void uploadFiles(Context context, String hostIp, int port, List<Uri> uris, TransferCallback callback) {
        if (uris == null || uris.isEmpty()) {
            if (callback != null) callback.onError("No files selected");
            return;
        }

        new Thread(() -> {
            Socket socket = null;
            try {
                Log.i(TAG, "Connecting to file transfer server at " + hostIp + ":" + port);
                socket = new Socket();
                socket.setTcpNoDelay(true);

                // Bind socket directly to Wi-Fi to bypass VPN routing if active
                try {
                    android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        for (android.net.Network net : cm.getAllNetworks()) {
                            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                                net.bindSocket(socket);
                                Log.i(TAG, "Bound upload socket directly to Wi-Fi network interface");
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                socket.connect(new InetSocketAddress(hostIp, port), 5000);

                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                // 1. Send Magic Header
                out.writeInt(MAGIC);
                out.flush();

                byte[] buffer = new byte[64 * 1024];
                int total = uris.size();

                for (int i = 0; i < total; i++) {
                    Uri uri = uris.get(i);
                    String fileName = getFileName(context, uri);
                    long fileSize = getFileSize(context, uri);

                    Log.i(TAG, "Uploading file (" + (i + 1) + "/" + total + "): " + fileName + " (" + fileSize + " bytes)");
                    if (callback != null) {
                        callback.onProgress(fileName, i + 1, total);
                    }

                    // Send CMD_FILE_START
                    out.writeByte(CMD_FILE_START);
                    byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(nameBytes.length);
                    out.write(nameBytes);
                    out.writeLong(fileSize);
                    out.flush();

                    // Send CMD_FILE_DATA chunks
                    try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                        if (is != null) {
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                out.writeByte(CMD_FILE_DATA);
                                out.writeInt(bytesRead);
                                out.write(buffer, 0, bytesRead);
                            }
                        }
                    }

                    // Send CMD_FILE_END
                    out.writeByte(CMD_FILE_END);
                    out.flush();
                }

                // Send CMD_BATCH_DONE
                out.writeByte(CMD_BATCH_DONE);
                out.flush();

                // Read ACK
                byte ackMagic = in.readByte();
                byte ackStatus = in.readByte();
                if (ackMagic == CMD_ACK && ackStatus == 0x00) {
                    Log.i(TAG, "Batch upload successful! Total files: " + total);
                    if (callback != null) callback.onSuccess(total);
                } else {
                    String err = "Remote server returned failure code: " + ackStatus;
                    Log.e(TAG, err);
                    if (callback != null) callback.onError(err);
                }

            } catch (Exception e) {
                Log.e(TAG, "Upload failed: " + e.getMessage(), e);
                if (callback != null) callback.onError(e.getMessage());
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private static String getFileName(Context context, Uri uri) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {}

        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.trim().isEmpty()) {
            name = "file_" + System.currentTimeMillis();
        }
        return name;
    }

    private static long getFileSize(Context context, Uri uri) {
        long size = -1;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {}
        return size;
    }
}
