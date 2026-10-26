/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.os.Build;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The hand-over between Android's screen capture consent, the foreground service that has to be
 * running before the projection can be used, and the recorder that uses it (#37). One recording at
 * a time: {@link #request} asks, the consent screen answers through {@link #consented}, the service
 * turns the answer into a projection and reports it through {@link #projectionReady}, and the
 * recorder takes it with {@link #take}.
 */
public final class ScreenCapture {
    private ScreenCapture() {
    }

    /** Told once, on the main thread, whether a projection is ready to take. */
    public interface Ready {
        void onResult(boolean granted);
    }

    private static final Object LOCK = new Object();
    private static Ready waiting;
    private static MediaProjection projection;
    private static Runnable onEnded;
    private static Context app;

    /** Asks Android for permission to capture the screen. Each recording asks again. */
    public static void request(Activity from, Ready ready) {
        synchronized (LOCK) {
            waiting = ready;
            app = from.getApplicationContext() == null ? from : from.getApplicationContext();
        }
        from.startActivity(new Intent(from, ScreenRecordConsentActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** The consent screen's answer; starts the service that holds the projection. */
    static void consented(Context context, int resultCode, Intent data) {
        try {
            Intent start = new Intent(context, ScreenRecordService.class)
                    .putExtra(ScreenRecordService.EXTRA_CODE, resultCode)
                    .putExtra(ScreenRecordService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start);
            else context.startService(start);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not start the screen recording service", failure);
            denied();
        }
    }

    /** The viewer said no, or the service could not start. */
    static void denied() {
        deliver(false);
    }

    static void projectionReady(MediaProjection ready) {
        synchronized (LOCK) {
            projection = ready;
        }
        deliver(true);
    }

    private static void deliver(boolean granted) {
        Ready ready;
        synchronized (LOCK) {
            ready = waiting;
            waiting = null;
        }
        if (ready != null) Utils.runOnMainThread(() -> ready.onResult(granted));
    }

    /** The projection the service holds, handed over once; null when there isn't one. */
    public static MediaProjection take() {
        synchronized (LOCK) {
            MediaProjection taken = projection;
            projection = null;
            return taken;
        }
    }

    /** Snapshot the pending owner without consuming Android's one-shot projection. */
    public static MediaProjection pending() { synchronized(LOCK){return projection;} }

    /** Transfer only the projection seen by this recorder's construction. */
    public static MediaProjection take(MediaProjection expected) {
        synchronized(LOCK){if(expected==null || projection!=expected)return null;projection=null;return expected;}
    }

    /** A setup failure must not discard a newer recorder's pending consent. */
    public static void discardUnused(MediaProjection expected) {MediaProjection owned=take(expected);if(owned!=null)release(owned);}

    /** Something to run when Android ends the projection by itself, such as its status bar chip. */
    public static void whenEnded(Runnable ended) {
        synchronized (LOCK) {
            onEnded = ended;
        }
    }

    static void projectionEnded() {
        Runnable ended;
        synchronized (LOCK) {
            ended = onEnded;
            onEnded = null;
        }
        if (ended != null) ended.run();
    }

    /** Stops the projection and the service. Safe to call more than once. */
    public static void release(MediaProjection used) {
        try {
            if (used != null) used.stop();
        } catch (RuntimeException ignored) {
            // Already stopped.
        }
        Context context;
        synchronized (LOCK) {
            onEnded = null;
            context = app;
        }
        if (context != null) {
            try {
                context.stopService(new Intent(context, ScreenRecordService.class));
            } catch (RuntimeException ignored) {
                // The service is gone already.
            }
        }
    }

    /** Lets go of a projection that was allowed but never used, such as when the room was left. */
    public static void discardUnused() {
        release(take());
    }
}
