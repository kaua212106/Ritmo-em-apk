package com.kaua.ritmo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public class ProtectionWatchdogService extends Service {
    public static final String ACTION_START = "com.kaua.ritmo.START_STRICT_WATCHDOG";
    public static final String ACTION_STOP = "com.kaua.ritmo.STOP_STRICT_WATCHDOG";

    private static final String CHANNEL_ID = "ritmo_strict_guard";
    private static final int NOTIFICATION_ID = 43;
    private static final long CHECK_INTERVAL_MS = 15000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean lastEnabled = true;

    private final Runnable checker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            boolean enabled = isStrictAccessibilityEnabled();
            updateNotification(enabled);
            lastEnabled = enabled;
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            running = false;
            handler.removeCallbacksAndMessages(null);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        boolean enabled = isStrictAccessibilityEnabled();
        lastEnabled = enabled;
        startForeground(NOTIFICATION_ID, buildNotification(enabled));
        handler.removeCallbacks(checker);
        handler.post(checker);
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Modo rígido do Ritmo",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Mantém o modo rígido monitorado mesmo com o Ritmo fechado.");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(boolean enabled) {
        Intent tapIntent;
        String title;
        String text;

        if (enabled) {
            tapIntent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            title = "Ritmo • Modo rígido ativo";
            text = "A proteção continua ativa em segundo plano.";
        } else {
            tapIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            title = "Ritmo • Modo rígido interrompido";
            text = "Toque para reativar a Acessibilidade do Ritmo.";
        }

        PendingIntent pi = PendingIntent.getActivity(
                this,
                enabled ? 4301 : 4302,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(enabled ? android.R.drawable.ic_lock_lock : android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(enabled)
                .setOnlyAlertOnce(lastEnabled == enabled)
                .setAutoCancel(!enabled)
                .build();
    }

    private void updateNotification(boolean enabled) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(enabled));
    }

    private boolean isStrictAccessibilityEnabled() {
        try {
            int accessibility = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
            );
            if (accessibility != 1) return false;

            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (enabledServices == null || enabledServices.trim().isEmpty()) return false;

            ComponentName expected = new ComponentName(this, StrictBlockAccessibilityService.class);
            for (String raw : enabledServices.split(":")) {
                ComponentName current = ComponentName.unflattenFromString(raw);
                if (current != null && expected.equals(current)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The service lives in a separate process and is sticky. Keeping this method
        // explicit prevents the recent-apps task from being treated as an intentional stop.
        if (running) {
            try {
                Intent restart = new Intent(this, ProtectionWatchdogService.class).setAction(ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restart);
                else startService(restart);
            } catch (Exception ignored) {
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
