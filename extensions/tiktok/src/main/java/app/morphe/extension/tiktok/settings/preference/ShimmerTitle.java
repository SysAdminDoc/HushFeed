/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.text.Layout;
import android.view.Choreographer;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Every Hushfeed title in TikTok's cyan and red: the settings bar, its pages and dialogs, and the
 * screens that stand in for them. The colours drift along the words all the time the title is on
 * screen, and as it opens, and every {@link #PERIOD_MS} after, a white sheen sweeps over them. It
 * moves whatever Android's animation scale says, since the S22 runs with animations off and a
 * ValueAnimator would sit still there.
 */
public final class ShimmerTitle implements Choreographer.FrameCallback, Runnable, View.OnAttachStateChangeListener {
    /** One sheen sweep and the wait after it. */
    static final long PERIOD_MS = 6000;
    /** How long a sheen sweep runs. */
    static final long SWEEP_MS = 1400;
    /** How long the colours take to drift one whole mirrored round. */
    static final long DRIFT_MS = 8000;
    /**
     * The least time between frames: the next frame after it, so about 60 a second on a 60 or
     * 120 Hz screen. A frame posted with no wait runs at once wherever time stands still, as in a
     * test's paused looper, which then never goes idle.
     */
    private static final long FRAME_MS = 10;
    /** How often a title that isn't showing checks whether it is again. */
    private static final long HIDDEN_CHECK_MS = 500;
    /**
     * TikTok's cyan, white and red for a title on the dark theme or the lock cover. The red is
     * lightened from #FE2C55, keeping its hue, so every stop reads at 7:1 or better on black and
     * on the dark dialog surface.
     */
    static final int[] COLORS_ON_DARK = {0xFF25F4EE, 0xFFFFFFFF, 0xFFFE8098};
    /**
     * The same cyan and red darkened for the light theme, with the page's ink between them, each
     * 7:1 or better on the light page and the white dialog surface.
     */
    static final int[] COLORS_ON_LIGHT = {0xFF055D5A, 0xFF16161C, 0xFFAA0122};
    private static final int[] SHEEN_ON_DARK = {0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF};
    /**
     * Softer on the light theme, where white over the dark letters lightens them toward the page:
     * at the sheen's peak every light stop still reads at 4.5:1 or better on the light surfaces.
     */
    static final int[] SHEEN_ON_LIGHT = {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF};
    /**
     * The titles wearing the gradient, so painting one twice doesn't run two loops. The loop is held
     * weakly too: it holds its title, and a value that holds its key keeps the entry, and with it
     * the title's dialog and screen, for good. The title's own listener list keeps the loop alive.
     */
    private static final Map<TextView, WeakReference<ShimmerTitle>> WORN = new WeakHashMap<>();

    private final TextView title;
    private final int[] stops;
    private final int[] sheenStops;
    private final Matrix move = new Matrix();
    private final Matrix across = new Matrix();
    private LinearGradient colors;
    private LinearGradient sheen;
    private float shadedWidth = -1f;
    private float shadedHeight = -1f;
    /** When the first sweep started, so it plays as the title opens. */
    private long startedAt = -1L;
    private boolean running;

    private ShimmerTitle(TextView title, boolean onDark) {
        this.title = title;
        this.stops = onDark ? COLORS_ON_DARK : COLORS_ON_LIGHT;
        this.sheenStops = onDark ? SHEEN_ON_DARK : SHEEN_ON_LIGHT;
    }

    /**
     * Puts [title] in the gradient, moving for as long as it's on screen. Again changes nothing.
     * The colour the title already has says which ground it sits on: light letters are on a dark
     * page or the lock cover, dark ones on the light theme.
     */
    public static void wear(TextView title) {
        if (wears(title)) return;
        // Color.luminance(int) needs API 24, and the ink colours sit far either side of the middle.
        int ink = title.getCurrentTextColor();
        boolean onDark = Color.red(ink) * 299 + Color.green(ink) * 587 + Color.blue(ink) * 114 > 128_000;
        ShimmerTitle shimmer = new ShimmerTitle(title, onDark);
        WORN.put(title, new WeakReference<>(shimmer));
        // The gradient replaces the colour, and a full-strength colour keeps it full strength.
        title.setTextColor(Color.WHITE);
        title.addOnAttachStateChangeListener(shimmer);
        if (title.isAttachedToWindow()) shimmer.onViewAttachedToWindow(title);
    }

    /** Whether [title] wears the gradient. */
    public static boolean wears(TextView title) {
        return wornBy(title) != null;
    }

    @Nullable
    private static ShimmerTitle wornBy(TextView title) {
        WeakReference<ShimmerTitle> worn = WORN.get(title);
        return worn == null ? null : worn.get();
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        startedAt = -1L;
        if (!running) {
            running = true;
            Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_MS);
        }
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        title.removeCallbacks(this);
    }

    /** The look again at a title that wasn't showing: back to drawing every frame once it is. */
    @Override
    public void run() {
        if (!running || !title.isAttachedToWindow()) {
            running = false;
            return;
        }
        if (title.isShown()) Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_MS);
        else title.postDelayed(this, HIDDEN_CHECK_MS);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running || !title.isAttachedToWindow()) {
            running = false;
            return;
        }
        if (!title.isShown()) {
            // Nothing to draw behind a closed page: look again in a while instead of every frame.
            startedAt = -1L;
            title.postDelayed(this, HIDDEN_CHECK_MS);
            return;
        }
        // The frame's own time. AnimationUtils' clock never goes back past the latest time it has
        // handed out on this thread, so after the clock it reads starts again it stands still.
        long now = frameTimeNanos / 1_000_000L;
        if (startedAt < 0L) startedAt = now;
        frame(now - startedAt);
        Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_MS);
    }

    /** Shades [title], which wears the gradient, as it stands [sinceStart] ms after it opened. */
    static void frameFor(TextView title, long sinceStart) {
        ShimmerTitle shimmer = wornBy(title);
        if (shimmer != null) shimmer.frame(sinceStart);
    }

    /** Shades the title as it stands [sinceStart] ms after it opened, and has it redrawn. */
    void frame(long sinceStart) {
        title.getPaint().setShader(shade(sinceStart));
        title.invalidate();
    }

    @Nullable
    private Shader shade(long sinceStart) {
        Layout layout = title.getLayout();
        if (layout == null || layout.getLineCount() == 0) return null;
        // TextView draws its layout from the padding's corner, so these are the layout's own
        // coordinates. A wrapped title takes its colours across its widest line.
        float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        for (int line = 0; line < layout.getLineCount(); line++) {
            left = Math.min(left, layout.getLineLeft(line));
            right = Math.max(right, layout.getLineRight(line));
        }
        float width = Math.max(1f, right - left);
        float height = Math.max(1f, layout.getHeight());
        float band = height * 1.6f;
        if (colors == null || width != shadedWidth || height != shadedHeight) {
            colors = new LinearGradient(0f, 0f, width, 0f, stops, null, Shader.TileMode.MIRROR);
            sheen = new LinearGradient(0f, 0f, band, band * 0.35f, sheenStops, null, Shader.TileMode.CLAMP);
            shadedWidth = width;
            shadedHeight = height;
        }
        move.setTranslate(left + drift(sinceStart) * 2f * width, 0f);
        colors.setLocalMatrix(move);
        float sweep = sweep(sinceStart);
        if (sweep < 0f) return colors;
        across.setTranslate(left - band + sweep * (width + band), 0f);
        sheen.setLocalMatrix(across);
        // Built afresh each frame: a ComposeShader keeps the matrices its parts had when it was made.
        return new ComposeShader(colors, sheen, PorterDuff.Mode.SRC_OVER);
    }

    /**
     * How far into its sweep the title is [sinceStart] ms after it opened, 0 to 1, or -1 while it
     * waits for the next.
     */
    static float sweep(long sinceStart) {
        if (sinceStart < 0L) return -1f;
        long into = sinceStart % PERIOD_MS;
        return into < SWEEP_MS ? into / (float) SWEEP_MS : -1f;
    }

    /**
     * How far round its mirrored cycle the gradient has drifted [sinceStart] ms after the title
     * opened, 0 to 1. A whole round ends on the colours it began with, so the drift never jumps.
     */
    static float drift(long sinceStart) {
        if (sinceStart <= 0L) return 0f;
        return (sinceStart % DRIFT_MS) / (float) DRIFT_MS;
    }
}
