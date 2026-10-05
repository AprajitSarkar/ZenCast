package com.mercor.zenhost;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ShareActivity extends Activity {
    private static final String TAG = "ZenHost_Share";
    private static final int CLIENT_RECEIVER_PORT = 27187;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;

        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            finish();
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                uris.add(uri);
            } else {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (text != null && text.length() > 0) {
                    Uri textUri = createTempTextFile(text.toString());
                    if (textUri != null) uris.add(textUri);
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> extraUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (extraUris != null) {
                uris.addAll(extraUris);
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No content found to share", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("ZenCast File Share");
        progressDialog.setMessage("Finding connected ZenCast client...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            String clientIp = resolveClientIP();
            if (clientIp == null || clientIp.trim().isEmpty() || clientIp.equals("127.0.0.1")) {
                handler.post(() -> {
                    dismissDialog();
                    Toast.makeText(this, "No ZenCast client currently connected", Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }

            handler.post(() -> {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.setMessage("Sending " + uris.size() + " item(s) to " + clientIp + "...");
                }
            });

            FileTransferClient.uploadFiles(this, clientIp, CLIENT_RECEIVER_PORT, uris, new FileTransferClient.TransferCallback() {
                @Override
                public void onProgress(String filename, int current, int total) {
                    handler.post(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.setMessage("Sending (" + current + "/" + total + "): " + filename);
                        }
                    });
                }

                @Override
                public void onSuccess(int totalFiles) {
                    handler.post(() -> {
                        dismissDialog();
                        Toast.makeText(ShareActivity.this, "Successfully sent " + totalFiles + " item(s) to ZenCast Client (" + clientIp + ")!", Toast.LENGTH_LONG).show();
                        finish();
                    });
                }

                @Override
                public void onError(String error) {
                    handler.post(() -> {
                        dismissDialog();
                        Toast.makeText(ShareActivity.this, "Share failed: " + error, Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            });

        }).start();
    }

    private void dismissDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {}
        }
    }

    private String resolveClientIP() {
        // 1. Check /data/local/tmp/last_client_ip.txt
        File ipFile = new File("/data/local/tmp/last_client_ip.txt");
        if (ipFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(ipFile))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    String candidate = line.trim();
                    if (isClientReceiverActive(candidate, 800)) {
                        Log.i(TAG, "Resolved active client IP from last_client_ip.txt: " + candidate);
                        return candidate;
                    }
                    Log.i(TAG, "Candidate from file (" + candidate + ") not reachable, trying other methods...");
                }
            } catch (Exception ignored) {}
        }

        // 2. Query local zen_daemon on TCP 27182
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 27182), 600);
            s.getOutputStream().write("GET_CLIENT_IP\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            // First line may be beacon JSON
            String line1 = r.readLine();
            String candidate = null;
            if (line1 != null && !line1.startsWith("{") && !line1.trim().isEmpty() && !line1.equals("127.0.0.1")) {
                candidate = line1.trim();
            } else {
                String line2 = r.readLine();
                if (line2 != null && !line2.trim().isEmpty() && !line2.equals("127.0.0.1")) {
                    candidate = line2.trim();
                }
            }
            if (candidate != null && isClientReceiverActive(candidate, 800)) {
                Log.i(TAG, "Resolved active client IP from daemon: " + candidate);
                return candidate;
            }
        } catch (Exception ignored) {}

        // 3. Scan neighbors from /proc/net/arp
        List<String> neighbors = getArpNeighbors();
        for (String ip : neighbors) {
            if (isClientReceiverActive(ip, 400)) {
                Log.i(TAG, "Resolved active client IP from ARP neighbor: " + ip);
                return ip;
            }
        }

        // 4. Subnet probe
        String wifiIp = ZenHostService.getWifiIP();
        if (wifiIp != null && wifiIp.contains(".")) {
            String subnet = wifiIp.substring(0, wifiIp.lastIndexOf('.') + 1);
            String[] commonOctets = {"176", "246", "100", "108", "126", "145", "174", "203", "129"};
            for (String octet : commonOctets) {
                String target = subnet + octet;
                if (!target.equals(wifiIp) && isClientReceiverActive(target, 350)) {
                    Log.i(TAG, "Resolved active client IP from subnet probe: " + target);
                    return target;
                }
            }
        }

        // 5. Final fallback: If last_client_ip.txt had an IP, return it anyway even if receiver probe timed out
        if (ipFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(ipFile))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty() && !line.equals("127.0.0.1")) {
                    Log.w(TAG, "Using unverified client IP fallback: " + line.trim());
                    return line.trim();
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private boolean isClientReceiverActive(String ip, int timeoutMs) {
        if (ip == null || ip.trim().isEmpty() || ip.equals("127.0.0.1") || ip.startsWith("10.2.")) return false;
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (android.net.Network net : cm.getAllNetworks()) {
                        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                            net.bindSocket(s);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            s.connect(new InetSocketAddress(ip, CLIENT_RECEIVER_PORT), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> getArpNeighbors() {
        List<String> list = new ArrayList<>();
        File arp = new File("/proc/net/arp");
        if (arp.exists() && arp.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(arp))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] tokens = line.trim().split("\\s+");
                    if (tokens.length >= 4 && tokens[0].contains(".")) {
                        String ip = tokens[0];
                        String flags = tokens[2];
                        if (!ip.equals("IP") && !flags.equals("0x0") && !ip.endsWith(".1")) {
                            list.add(ip);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    private Uri createTempTextFile(String text) {
        try {
            File cacheDir = getCacheDir();
            File textFile = new File(cacheDir, "shared_text.txt");
            try (FileOutputStream fos = new FileOutputStream(textFile)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return Uri.fromFile(textFile);
        } catch (Exception e) {
            Log.e(TAG, "Error saving shared text: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        super.onDestroy();
    }
}
