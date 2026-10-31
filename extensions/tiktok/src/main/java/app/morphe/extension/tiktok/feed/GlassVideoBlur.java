/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.download.PlayerLayers;
import app.morphe.extension.tiktok.playback.PictureInPicture;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;

/**
 * Blur the video behind the bar (#231). A view's RenderEffect can only blur what the view draws
 * itself, and the feed's video sits in a surface of its own under the window, so the glass pill
 * could only be frosted, never made to blur the picture. This reads the strip of video behind the
 * pill back from the player's surface with PixelCopy, small, and draws it inside the pill's
 * background through a RenderNode with a blur effect.
 *
 * <p>The video is found the way the frame save finds it: the player's own layer under the feed's
 * SurfaceView ({@link PlayerLayers}) when it draws into one, else the SurfaceView itself, measured
 * as drawn (TikTok scales it up a little). With no SurfaceView to read, a TextureView player (a
 * LIVE preview in For You) is read whole and small through its own copy instead, and the pill's
 * strip cut from that. A window with no video under most of the pill has nothing to read, and
 * the plain glass stays. One bitmap is kept and written in
 * place. It is sampled at an eighth of the pill's size about 20 times a second while the bar is on
 * screen and the window has focus, a few times a second once the picture has stopped changing (a
 * paused video), and not at all once the switch is off, Hushfeed is paused, the bar is hidden or
 * another background replaced the glass. Android 12 added RenderEffect, so below it the switch does nothing.
 */
final class GlassVideoBlur {
    /** The sample is this many times smaller than the pill, in each direction. */
    static final int SAMPLE_DIVISOR = 8;
    /** Time between two reads while the picture changes, about 20 a second. */
    static final long SAMPLE_MS = 50;
    /** Time between two reads once the picture has stopped changing. */
    static final long STILL_MS = 500;
    /** Reads in a row with the same picture before it counts as stopped. */
    static final int STILL_SAMPLES = 10;
    /** How long a found video is trusted before the window is searched again. */
    static final long FIND_MS = 1000;
    /** Failed reads in a row before the blur gives the pill back to the plain glass for a while. */
    static final int MAX_FAILURES = 3;
    /** How long the plain glass stays after those failures. */
    static final long RETRY_MS = 3000;
    /** The blur's size in dp, at the pill's own size, for the standard strength. */
    static final float BLUR_DP = 10f;

    /**
     * The blur's size in dp for Blur strength: half of {@link #BLUR_DP} for "light" and five
     * thirds of it for "strong", the steps of HushGram's glass tab bar.
     */
    static float blurDp(String strength) {
        if ("light".equals(strength)) return BLUR_DP / 2f;
        if ("strong".equals(strength)) return BLUR_DP * 5f / 3f;
        return BLUR_DP;
    }

    /** Tells a read finished; java.util.function needs API 24. */
    interface Done {
        void done(boolean copied);
    }

    /** The video the pill sits over: where it is in the window, how big its buffer is, and how to read it. */
    interface Source {
        /** The video's place in the window. */
        Rect bounds();

        int bufferWidth();

        int bufferHeight();

        void read(Rect from, Bitmap into, Done done);

        /**
         * Whether this is still the video on screen. A swipe hands the next video a new player
         * layer, often under the same SurfaceView, while the last one keeps its final frame, so a
         * source kept for {@link #FIND_MS} went on blurring the video before into the pill.
         */
        boolean current();
    }

    /** Finds the video behind the bar, or null; a test stands in its own, since Robolectric draws no surface. */
    interface Finder {
        Source find(View bar);
    }

    static Finder finder = GlassVideoBlur::findSource;

    private final float density;
    private final WeakReference<android.graphics.drawable.Drawable> ownerDrawable;
    private final Rect pillInWindow = new Rect();
    private final int[] spot = new int[2];
    private final Runnable tick = this::tick;
    private final ViewTreeObserver.OnWindowFocusChangeListener focus = this::focused;
    private WeakReference<View> bar = new WeakReference<>(null);
    private ViewTreeObserver watched;
    private Bitmap sample;
    private int[] pixels = new int[0];
    private int[] before = new int[0];
    private Painter painter;
    /** The strip the last draw put in the pill, null when it drew none. */
    private RenderNode drawn;
    private boolean hasFrame;
    private boolean copying;
    private boolean running;
    private int still;
    private int failures;
    private long lastRead;
    private long lastFind;
    private long retryAt;
    private Source source;
    /** The last state written to the debug log, so each change is logged once. */
    private String state;
    /** Reads asked for since this blur was made, for the tests. */
    int reads;

    /** {@code owner} is the background drawing this; the blur stops once the bar holds another. */
    GlassVideoBlur(float density, android.graphics.drawable.Drawable owner) {
        this.density = density;
        this.ownerDrawable = new WeakReference<>(owner);
    }

    /** True where this build can draw the blur: RenderEffect starts with Android 12. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** True while the switch is on, Hushfeed isn't paused and the phone can draw it. */
    static boolean wanted() {
        return supported() && Settings.GLASS_VIDEO_BLUR.get();
    }

    /**
     * Where the picture shows inside a video view at {@code video}: the whole view for a buffer at
     * least as tall for its width as the view, else the band TikTok fits a wider, landscape buffer
     * into, as wide as the view and centred top to bottom. The pill sat over the black under a
     * landscape video and still blurred the buffer's bottom rows into it.
     */
    static Rect picture(Rect video, int bufferWidth, int bufferHeight) {
        if ((long) bufferWidth * video.height() <= (long) bufferHeight * video.width()) return video;
        int height = Math.round(video.width() * (float) bufferHeight / bufferWidth);
        int top = video.centerY() - height / 2;
        return new Rect(video.left, top, video.right, top + height);
    }

    /**
     * The part of the video the pill covers, in the video buffer's pixels, or null when the pill
     * isn't over the video. A pill hanging a little past the video's edge (a full width bar a few
     * pixels lower than a scaled video) reads the part that is over it, as long as that is at least
     * three quarters of the pill. Both rects are in the window's coordinates. A buffer as tall as
     * or taller than the view fills it; a wider one, a landscape video, is shown fitted across with
     * black above and under ({@link #picture}), and a pill over that black reads nothing.
     */
    static Rect sourceRect(Rect pill, Rect video, int bufferWidth, int bufferHeight) {
        if (pill == null || video == null || pill.isEmpty() || video.isEmpty()
                || bufferWidth <= 0 || bufferHeight <= 0) {
            return null;
        }
        video = picture(video, bufferWidth, bufferHeight);
        Rect over = new Rect(pill);
        if (!over.intersect(video)) return null;
        if ((long) over.width() * over.height() * 4 < (long) pill.width() * pill.height() * 3) return null;
        float scaleX = bufferWidth / (float) video.width();
        float scaleY = bufferHeight / (float) video.height();
        Rect from = new Rect(
                Math.max(0, Math.round((over.left - video.left) * scaleX)),
                Math.max(0, Math.round((over.top - video.top) * scaleY)),
                Math.min(bufferWidth, Math.round((over.right - video.left) * scaleX)),
                Math.min(bufferHeight, Math.round((over.bottom - video.top) * scaleY)));
        return from.isEmpty() ? null : from;
    }

    /**
     * Where the view draws in its window, after its own and its parents' scale and offsets. TikTok
     * scales the feed's player view up to fill the screen (about 1.07 on the S22), so its corner plus
     * its unscaled width and height ended above the bar and every pill looked off the video.
     */
    static Rect boundsInWindow(View view) {
        RectF box = new RectF(0, 0, view.getWidth(), view.getHeight());
        View at = view;
        while (true) {
            android.graphics.Matrix matrix = at.getMatrix();
            if (!matrix.isIdentity()) matrix.mapRect(box);
            box.offset(at.getLeft(), at.getTop());
            if (!(at.getParent() instanceof View)) break;
            at = (View) at.getParent();
            box.offset(-at.getScrollX(), -at.getScrollY());
        }
        return new Rect(Math.round(box.left), Math.round(box.top), Math.round(box.right), Math.round(box.bottom));
    }

    /** The sample's width and height for a pill of this size: an eighth each way, at least a pixel. */
    static int[] sampleSize(int pillWidth, int pillHeight) {
        return new int[]{Math.max(1, pillWidth / SAMPLE_DIVISOR), Math.max(1, pillHeight / SAMPLE_DIVISOR)};
    }

    /** True when the pill's blur has a picture to draw. */
    boolean hasFrame() {
        return hasFrame;
    }

    /**
     * The blurred strip the last {@link #drawBehind} drew into the pill, pill-sized and drawn at
     * the pill's corner, or null when it drew none. The glass lens magnifies it.
     */
    RenderNode drawnBackdrop() {
        return drawn;
    }

    /** True while the sampling loop is scheduled. */
    boolean sampling() {
        return running;
    }

    /**
     * Called from the glass background's draw, with the pill's bounds in the bar. Starts the
     * sampling when it isn't running, and draws the blurred strip into the pill's shape once
     * there is a picture, clipped to the pill's {@code radius} corners. Draws nothing when the
     * blur can't run, which leaves the plain glass.
     */
    void drawBehind(Canvas canvas, View view, Rect pill, float radius) {
        drawn = null;
        if (!wanted() || pill.isEmpty()) return;
        bar = new WeakReference<>(view);
        watch(view);
        start(view);
        if (!hasFrame || sample == null || !canvas.isHardwareAccelerated()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (painter == null) painter = new Painter();
        painter.draw(canvas, sample, pill, radius, blurDp(Settings.GLASS_BLUR_STRENGTH.get()) * density);
        drawn = painter.node;
    }

    /** Starts the loop when it isn't already going. */
    void start(View view) {
        if (running || !wanted()) return;
        bar = new WeakReference<>(view);
        running = true;
        view.postOnAnimation(tick);
    }

    private void watch(View view) {
        ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer == watched || !observer.isAlive()) return;
        if (watched != null && watched.isAlive()) watched.removeOnWindowFocusChangeListener(focus);
        observer.removeOnWindowFocusChangeListener(focus);
        observer.addOnWindowFocusChangeListener(focus);
        watched = observer;
    }

    private void focused(boolean hasFocus) {
        View view = bar.get();
        if (hasFocus && view != null) start(view);
    }

    private void tick() {
        try {
            step(SystemClock.uptimeMillis());
        } catch (RuntimeException failure) {
            running = false;
            hasFrame = false;
            Logger.printException(() -> "Could not blur the video behind the bar", failure);
        }
    }

    /** One pass of the loop: stops when the bar is out of sight, else reads when it's time. */
    void step(long now) {
        View view = bar.get();
        if (!goOn(view)) {
            running = false;
            return;
        }
        long wait = still >= STILL_SAMPLES ? STILL_MS : SAMPLE_MS;
        if (!copying && now >= retryAt && now - lastRead >= wait) read(view, now);
        view.postOnAnimation(tick);
    }

    private boolean goOn(View view) {
        return view != null && wanted() && view.isShown() && view.hasWindowFocus()
                && view.getBackground() != null && view.getBackground() == ownerDrawable.get();
    }

    private void read(View view, long now) {
        lastRead = now;
        if (source != null && !source.current()) {
            // Another video now: its picture, not the last one's, or none until it's read.
            source = null;
            hasFrame = false;
            still = 0;
        }
        if (source == null || now - lastFind >= FIND_MS) {
            source = finder.find(view);
            lastFind = now;
        }
        Source video = source;
        android.graphics.drawable.Drawable background = view.getBackground();
        Rect pill = background instanceof android.graphics.drawable.InsetDrawable
                ? ((android.graphics.drawable.InsetDrawable) background).getDrawable().getBounds()
                : background.getBounds();
        if (video == null || pill.isEmpty()) {
            note("none", "no video found behind the bar");
            hasFrame = false;
            return;
        }
        view.getLocationInWindow(spot);
        pillInWindow.set(spot[0] + pill.left, spot[1] + pill.top, spot[0] + pill.right, spot[1] + pill.bottom);
        Rect from = sourceRect(pillInWindow, video.bounds(), video.bufferWidth(), video.bufferHeight());
        if (from == null) {
            note("off", "the pill " + pillInWindow + " isn't over the video " + video.bounds());
            hasFrame = false;
            return;
        }
        int[] size = sampleSize(pill.width(), pill.height());
        if (sample == null || sample.getWidth() != size[0] || sample.getHeight() != size[1]) {
            if (sample != null) sample.recycle();
            sample = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            pixels = new int[size[0] * size[1]];
            before = new int[size[0] * size[1]];
            hasFrame = false;
        }
        copying = true;
        reads++;
        try {
            video.read(from, sample, copied -> finished(view, copied));
        } catch (RuntimeException refused) {
            // PixelCopy turns down a surface that's already gone, a swipe at the same moment.
            copying = false;
            failed(now);
        }
    }

    private void finished(View view, boolean copied) {
        copying = false;
        if (!copied) {
            failed(lastRead);
            return;
        }
        failures = 0;
        note("reading", "reading the video behind the bar");
        sample.getPixels(pixels, 0, sample.getWidth(), 0, 0, sample.getWidth(), sample.getHeight());
        still = Arrays.equals(pixels, before) ? still + 1 : 0;
        System.arraycopy(pixels, 0, before, 0, pixels.length);
        hasFrame = true;
        view.invalidate();
    }

    private void failed(long now) {
        source = null;
        if (++failures >= MAX_FAILURES) {
            // Nothing readable behind the pill: the plain glass shows until the next try.
            note("failed", "the video behind the bar can't be read, plain glass for now");
            failures = 0;
            hasFrame = false;
            retryAt = now + RETRY_MS;
        }
    }

    private void note(String now, String line) {
        if (now.equals(state)) return;
        state = now;
        Logger.printDebug(() -> "Glass blur: " + line);
    }

    /**
     * The video behind the bar: the player's layer under the SurfaceView it draws into, else the
     * SurfaceView, else a TextureView with a picture in it. The SurfaceViews go first, so the
     * ordinary feed video reads the way it always has.
     */
    @android.annotation.TargetApi(26)
    static Source findSource(View bar) {
        List<View> videos = PictureInPicture.videoViews(bar.getRootView());
        for (View video : videos) {
            if (!(video instanceof SurfaceView)) continue;
            SurfaceView surface = (SurfaceView) video;
            Rect bounds = boundsInWindow(video);
            PlayerLayers.Layer layer = PlayerLayers.under(surface);
            if (layer != null) {
                return new LayerSource(surface, layer.surface, bounds, layer.width, layer.height);
            }
            Rect frame = surface.getHolder().getSurfaceFrame();
            if (frame != null && !frame.isEmpty()) {
                return new ViewSource(surface, bounds, frame.width(), frame.height());
            }
        }
        for (View video : videos) {
            if (!(video instanceof TextureView) || !((TextureView) video).isAvailable()) continue;
            Rect bounds = boundsInWindow(video);
            if (!bounds.isEmpty()) return new TextureSource((TextureView) video, bounds);
        }
        return null;
    }

    /** Whether {@code view} is shown and still draws where it did at {@code bounds}. */
    private static boolean stillAt(View view, Rect bounds) {
        return view.isShown() && boundsInWindow(view).equals(bounds);
    }

    /** The whole-frame copy a TextureView is read into: its drawn size cut by {@link #SAMPLE_DIVISOR}, at least a pixel. */
    static int[] frameSize(Rect bounds) {
        return sampleSize(bounds.width(), bounds.height());
    }

    private static final Paint STRIP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** Draws the part {@code from} of {@code frame} over all of {@code into}, smoothed. */
    static void copyStrip(Bitmap frame, Rect from, Bitmap into) {
        into.eraseColor(0);
        new Canvas(into).drawBitmap(frame, from, new Rect(0, 0, into.getWidth(), into.getHeight()), STRIP_PAINT);
    }

    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    @android.annotation.TargetApi(26)
    private static final class LayerSource implements Source {
        private final SurfaceView view;
        private final Surface surface;
        private final Rect bounds;
        private final int width;
        private final int height;

        LayerSource(SurfaceView view, Surface surface, Rect bounds, int width, int height) {
            this.view = view;
            this.surface = surface;
            this.bounds = bounds;
            this.width = width;
            this.height = height;
        }

        @Override public Rect bounds() {
            return bounds;
        }

        @Override public int bufferWidth() {
            return width;
        }

        @Override public int bufferHeight() {
            return height;
        }

        @Override public boolean current() {
            if (!surface.isValid() || !stillAt(view, bounds)) return false;
            PlayerLayers.Layer layer = PlayerLayers.under(view);
            return layer != null && layer.surface == surface;
        }

        @Override public void read(Rect from, Bitmap into, Done done) {
            PixelCopy.request(surface, from, into, result -> done.done(result == PixelCopy.SUCCESS), mainHandler());
        }
    }

    @android.annotation.TargetApi(26)
    private static final class ViewSource implements Source {
        private final SurfaceView view;
        private final Rect bounds;
        private final int width;
        private final int height;

        ViewSource(SurfaceView view, Rect bounds, int width, int height) {
            this.view = view;
            this.bounds = bounds;
            this.width = width;
            this.height = height;
        }

        @Override public Rect bounds() {
            return bounds;
        }

        @Override public int bufferWidth() {
            return width;
        }

        @Override public int bufferHeight() {
            return height;
        }

        @Override public boolean current() {
            return view.getHolder().getSurface().isValid() && stillAt(view, bounds);
        }

        @Override public void read(Rect from, Bitmap into, Done done) {
            PixelCopy.request(view, from, into, result -> done.done(result == PixelCopy.SUCCESS), mainHandler());
        }
    }

    /**
     * A TextureView player. PixelCopy takes only surfaces and windows, so its own getBitmap copies
     * the whole picture, small, into one kept bitmap, and the pill's strip is cut from that. The
     * buffer this source reports is that small copy, so the strip maps straight onto it.
     */
    private static final class TextureSource implements Source {
        private final TextureView view;
        private final Rect bounds;
        private final Bitmap frame;

        TextureSource(TextureView view, Rect bounds) {
            this.view = view;
            this.bounds = bounds;
            int[] size = frameSize(bounds);
            frame = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        }

        @Override public Rect bounds() {
            return bounds;
        }

        @Override public int bufferWidth() {
            return frame.getWidth();
        }

        @Override public int bufferHeight() {
            return frame.getHeight();
        }

        @Override public boolean current() {
            return view.isAvailable() && stillAt(view, bounds);
        }

        @Override public void read(Rect from, Bitmap into, Done done) {
            if (!view.isAvailable() || view.getBitmap(frame) == null) {
                done.done(false);
                return;
            }
            copyStrip(frame, from, into);
            done.done(true);
        }
    }

    /** The blurred strip: the sample, stretched over the pill in a render node whose blur effect does the rest. */
    @android.annotation.TargetApi(31)
    private static final class Painter {
        private final RenderNode node = new RenderNode("glass-video-blur");
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();
        private float effect = -1f;

        void draw(Canvas canvas, Bitmap sample, Rect pill, float radius, float blur) {
            int width = pill.width(), height = pill.height();
            node.setPosition(0, 0, width, height);
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
                box.set(0, 0, width, height);
                recording.drawBitmap(sample, null, box, paint);
            } finally {
                node.endRecording();
            }
            if (blur != effect) {
                node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
                effect = blur;
            }
            box.set(pill);
            clip.rewind();
            clip.addRoundRect(box, radius, radius, Path.Direction.CW);
            int saved = canvas.save();
            canvas.clipPath(clip);
            canvas.translate(pill.left, pill.top);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(saved);
        }
    }
}
