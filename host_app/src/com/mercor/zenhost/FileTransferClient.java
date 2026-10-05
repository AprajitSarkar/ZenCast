package com.mercor.zenhost;

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
    private static final String TAG = "ZenHost_TransferClient";
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

    public static void uploadFiles(Context context, String clientIp, int port, List<Uri> uris, TransferCallback callback) {
        if (uris == null || uris.isEmpty()) {
            if (callback != null) callback.onError("No files to send");
            return;
        }

        new Thread(() -> {
            Socket socket = null;
            try {
                Log.i(TAG, "Connecting to ZenCast client at " + clientIp + ":" + port);
                socket = new Socket();
                socket.setTcpNoDelay(true);

                // Strategy 1: Connect via local root daemon proxy on 127.0.0.1:27188
                // (Runs as root with SO_BINDTODEVICE on wlan0, 100% bypasses any VPN like ProtonVPN)
                boolean connected = false;
                try {
                    socket.connect(new InetSocketAddress("127.0.0.1", 27188), 1200);
                    Log.i(TAG, "Connected to ZenCast client via local root VPN-bypass proxy (127.0.0.1:27188)");
                    connected = true;
                } catch (Exception ex) {
                    Log.i(TAG, "Local proxy not reachable (" + ex.getMessage() + "), attempting direct connect...");
                    try { socket.close(); } catch (Exception ignored) {}
                    socket = new Socket();
                    socket.setTcpNoDelay(true);
                }

                // Strategy 2: Direct connection to client with Wi-Fi network binding
                if (!connected) {
                    try {
                        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                        if (cm != null) {
                            for (android.net.Network net : cm.getAllNetworks()) {
                                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                                    net.bindSocket(socket);
                                    Log.i(TAG, "Bound socket directly to Wi-Fi network interface");
                                    break;
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "Wi-Fi bindSocket warning: " + ex.getMessage());
                    }
                    socket.connect(new InetSocketAddress(clientIp, port), 6000);
                }

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

                    Log.i(TAG, "Sending shared file (" + (i + 1) + "/" + total + "): " + fileName + " (" + fileSize + " bytes)");
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
                    Log.i(TAG, "Shared files successfully transferred to client: " + total);
                    if (callback != null) callback.onSuccess(total);
                } else {
                    String err = "Client rejected transfer with status: " + ackStatus;
                    Log.e(TAG, err);
                    if (callback != null) callback.onError(err);
                }

            } catch (Exception e) {
                Log.e(TAG, "Share transfer error: " + e.getMessage(), e);
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
            name = "shared_" + System.currentTimeMillis();
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
