/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import app.morphe.extension.shared.Logger;

/**
 * A surface nobody sees, for the profile queue's videos off the screen (#292). The player draws
 * each frame into it and the frame is let go at once, so a video's sound keeps going with no page
 * to show it. One small reader serves every video, made the first time one asks.
 */
final class QueueSurface {
    private static ImageReader frames;

    private QueueSurface() { }

    static synchronized Surface get() {
        if (frames == null) {
            HandlerThread thread = new HandlerThread("hushfeed-queue-surface");
            thread.start();
            ImageReader reader = ImageReader.newInstance(64, 64, ImageFormat.PRIVATE, 2);
            reader.setOnImageAvailableListener(QueueSurface::letGo, new Handler(thread.getLooper()));
            frames = reader;
        }
        return frames.getSurface();
    }

    private static void letGo(ImageReader reader) {
        try {
            Image frame = reader.acquireLatestImage();
            if (frame != null) frame.close();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not let a background queue frame go", error);
        }
    }
}
