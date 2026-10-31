/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * The foreground service Android requires while the screen is captured (#37). It starts as soon as
 * the viewer allows recording, takes the projection, and hands it to {@link ScreenCapture}. The
 * service type is declared in the manifest as mediaProjection.
 */
public final class ScreenRecordService extends Service {
    static final String EXTRA_CODE = "hushfeed_code";
    static final String EXTRA_DATA = "hushfeed_data";
    /** The notification's Stop (#265); the Record chip is hidden while the screen is filmed. */
    static final String ACTION_STOP = "app.morphe.extension.tiktok.live.STOP_SCREEN_RECORDING";
    private static final String CHANNEL = "hushfeed_screen_record";
    private static final int NOTIFICATION_ID = 3701;
    /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, API 29. */
    private static final int TYPE_MEDIA_PROJECTION = 32;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Already in the foreground; this is no consent answer, so the projection stays.
            LiveRecordChip.stopFromNotification();
            return START_NOT_STICKY;
        }
        try {
            startAsForeground();
            if (intent == null) {
                ScreenCapture.denied();
                stopSelf();
                return START_NOT_STICKY;
            }
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjection projection = manager.getMediaProjection(
                    intent.getIntExtra(EXTRA_CODE, 0), data);
            if (projection == null) throw new IllegalStateException("No projection");
            // Android 14 refuses to capture from a projection nobody is watching for the end of.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    ScreenCapture.projectionEnded();
                    stopSelf();
                }
            }, null);
            ScreenCapture.projectionReady(projection);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not start screen recording", failure);
            ScreenCapture.denied();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                        L10n.t(this, "Recording this LIVE's screen"), NotificationManager.IMPORTANCE_LOW));
            }
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, ScreenRecordService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = builder
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(L10n.t(this, "Recording this LIVE's screen"))
                .setContentText(L10n.t(this, "Tap Stop or leave the room to save it."))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, getApplicationInfo().icon), L10n.t(this, "Stop"), stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
