package io.github.hccullen.volumehud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * One foreground service that listens for the system's volume-changed broadcast and shows the pill.
 * The listener is registered at runtime, here, because Android does not deliver implicit broadcasts
 * to manifest receivers of apps targeting API 26 or later, and android.media.VOLUME_CHANGED_ACTION
 * is not on the exemption list - a manifest receiver for it never fires. Runs while the pill is
 * switched on; starts at boot, when the app is updated, and whenever its card is opened.
 */
public class PillService extends Service {
    private static final String TAG = "VolumePill";
    private static final String CHANNEL = "pill";
    /** Sent by the system on every volume change; not in the SDK's constants but sent on every Android version. */
    static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Pill.nudge(context);   // catches its own failures: a volume pill must never disturb what is playing
        }
    };

    /** Starts the service when the pill is on; silent when it is off or Android refuses the start. */
    static void start(Context context) {
        if (!Prefs.enabled(context)) return;
        try {
            context.startForegroundService(new Intent(context, PillService.class));
        } catch (RuntimeException refused) {
            // Android 12+ refuses a foreground start from the background; the next open of the app starts it.
            Log.w(TAG, "service not started: " + refused.getMessage());
        }
    }

    /** Stops the service (the card's switch turned the pill off). */
    static void stop(Context context) {
        context.stopService(new Intent(context, PillService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        registerReceiver(changes, new IntentFilter(VOLUME_CHANGED));
        Log.i(TAG, "listening for " + VOLUME_CHANGED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(changes);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
