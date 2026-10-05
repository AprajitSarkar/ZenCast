package com.mercor.zencast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;

public class ZenCastService extends Service {
    private static final String TAG = "ZenCast_Service";
    private static final String CHANNEL_ID = "zencast_media_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.mercor.zencast.ACTION_START";
    public static final String ACTION_STOP  = "com.mercor.zencast.ACTION_STOP";
    public static final String ACTION_MEDIA_CONTROL = "com.mercor.zencast.ACTION_MEDIA_CONTROL";
    public static final String EXTRA_HOST_NAME = "extra_host_name";
    public static final String EXTRA_KEY_CODE  = "extra_key_code";

    public interface MediaControlCallback {
        void onMediaControl(int keyCode);
    }

    private static volatile MediaControlCallback mediaCallback;

    public static void setMediaControlCallback(MediaControlCallback cb) {
        mediaCallback = cb;
    }

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSession mediaSession;
    private String currentHost = "ZenFone Max Pro M1";
    private boolean isPlaying = true;

    public static void start(Context context, String hostName) {
        try {
            Intent intent = new Intent(context, ZenCastService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_HOST_NAME, hostName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ZenCastService: " + e.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, ZenCastService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop ZenCastService: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setupMediaSession();
        acquireLocks();
    }

    private void setupMediaSession() {
        try {
            mediaSession = new MediaSession(this, "ZenCastMediaSession");
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    handleMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY);
                }

                @Override
                public void onPause() {
                    handleMediaCommand(KeyEvent.KEYCODE_MEDIA_PAUSE);
                }

                @Override
                public void onSkipToNext() {
                    handleMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT);
                }

                @Override
                public void onSkipToPrevious() {
                    handleMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                }

                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null && event.getAction() == KeyEvent.ACTION_UP) {
                        handleMediaCommand(event.getKeyCode());
                        return true;
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
            });

            updatePlaybackState(true);
            mediaSession.setActive(true);
        } catch (Exception e) {
            Log.w(TAG, "MediaSession setup error: " + e.getMessage());
        }
    }

    private void updatePlaybackState(boolean playing) {
        this.isPlaying = playing;
        if (mediaSession != null) {
            long actions = PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_STOP;

            PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
            mediaSession.setPlaybackState(stateBuilder.build());

            MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "ZenCast Remote Audio")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, currentHost)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Remote Cast");
            mediaSession.setMetadata(metaBuilder.build());
        }
    }

    private void handleMediaCommand(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            isPlaying = !isPlaying;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            isPlaying = true;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            isPlaying = false;
        }
        updatePlaybackState(isPlaying);

        // Forward to host via callback
        if (mediaCallback != null) {
            mediaCallback.onMediaControl(keyCode);
        }

        // Refresh notification to update Play/Pause icon
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(currentHost));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                releaseLocks();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_MEDIA_CONTROL.equals(action)) {
                int keyCode = intent.getIntExtra(EXTRA_KEY_CODE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                handleMediaCommand(keyCode);
                return START_STICKY;
            }
        }

        if (intent != null && intent.hasExtra(EXTRA_HOST_NAME)) {
            String name = intent.getStringExtra(EXTRA_HOST_NAME);
            if (name != null && !name.isEmpty()) {
                currentHost = name;
            }
        }

        Notification notification = buildNotification(currentHost);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            try {
                startForeground(NOTIF_ID, notification);
            } catch (Exception ignored) {}
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ZenCast Media Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps video & audio streaming alive with media player controls");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private PendingIntent createMediaActionPendingIntent(int keyCode, int requestCode) {
        Intent intent = new Intent(this, ZenCastService.class);
        intent.setAction(ACTION_MEDIA_CONTROL);
        intent.putExtra(EXTRA_KEY_CODE, keyCode);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private Notification buildNotification(String hostName) {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent prevPI  = createMediaActionPendingIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1);
        PendingIntent playPI  = createMediaActionPendingIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 2);
        PendingIntent nextPI  = createMediaActionPendingIntent(KeyEvent.KEYCODE_MEDIA_NEXT, 3);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Add 3 Media actions: Previous | Play/Pause | Next
        Notification.Action prevAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", prevPI).build();
        Notification.Action playPauseAction = new Notification.Action.Builder(
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play", playPI).build();
        Notification.Action nextAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next", nextPI).build();

        builder.addAction(prevAction)
               .addAction(playPauseAction)
               .addAction(nextAction);

        // Apply Android Notification.MediaStyle
        if (mediaSession != null) {
            Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2);
            builder.setStyle(mediaStyle);
        }

        builder.setContentTitle("ZenCast: " + hostName)
                .setContentText("Host Audio Streaming • Remote Media Controls Active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        return builder.build();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenCast:StreamCpuLock");
                wakeLock.acquire();
                Log.i(TAG, "Acquired PARTIAL_WAKE_LOCK for background streaming");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire WakeLock: " + e.getMessage());
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZenCast:StreamWifiLock");
                wifiLock.acquire();
                Log.i(TAG, "Acquired WIFI_MODE_FULL_HIGH_PERF for background streaming");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire WifiLock: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.i(TAG, "Released WakeLock");
            }
        } catch (Exception ignored) {}

        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                wifiLock = null;
                Log.i(TAG, "Released WifiLock");
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseLocks();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        Log.i(TAG, "ZenCastService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
