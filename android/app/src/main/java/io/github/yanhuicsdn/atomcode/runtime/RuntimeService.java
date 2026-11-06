package io.github.yanhuicsdn.atomcode.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import io.github.yanhuicsdn.atomcode.MainActivity;
import io.github.yanhuicsdn.atomcode.R;

public final class RuntimeService extends Service implements RuntimeController.Listener {
    public static final String STOP = "io.github.yanhuicsdn.atomcode.STOP";
    private RuntimeController controller;
    private PowerManager.WakeLock wake;
    private android.net.ConnectivityManager connectivity;
    private android.net.ConnectivityManager.NetworkCallback network;
    private static final String CHANNEL = "local-runtime";

    @Override public void onCreate() {
        super.onCreate();
        controller = RuntimeController.get(this);
        controller.addListener(this);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "本机运行环境", NotificationManager.IMPORTANCE_LOW));
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtomCode:runtime");
        wake.setReferenceCounted(false);
        connectivity = getSystemService(android.net.ConnectivityManager.class);
        network = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(android.net.Network value, android.net.LinkProperties properties) { controller.networkChanged(); }
            @Override public void onLost(android.net.Network value) { controller.networkChanged(); }
        };
        connectivity.registerDefaultNetworkCallback(network);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        controller.attachService(this);
        startForeground(1, notification());
        if (intent != null && STOP.equals(intent.getAction())) controller.stop();
        else {
            wake.acquire(30 * 60 * 1000L);
            controller.start();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RuntimeService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.app_name) + " · 手机本机运行")
                .setContentText(controller.message)
                .setSmallIcon(R.drawable.ic_atomcode)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
    }

    @Override public void changed() {
        if (controller.phase == RuntimeController.Phase.STOPPED || controller.phase == RuntimeController.Phase.ERROR) {
            if (wake.isHeld()) wake.release();
            stopForeground(true);
            stopSelf();
        } else getSystemService(NotificationManager.class).notify(1, notification());
    }

    @Override public void onDestroy() {
        controller.removeListener(this);
        connectivity.unregisterNetworkCallback(network);
        if (wake.isHeld()) wake.release();
        controller.detachService(this);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
