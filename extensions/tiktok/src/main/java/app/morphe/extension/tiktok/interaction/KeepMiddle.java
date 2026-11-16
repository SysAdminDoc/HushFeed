/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.SurfaceView;
import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Keep the middle unstretched (#421): the video covers the window with its middle at its own
 * shape. It is scaled up until half the gap under it is gone, which takes a little off each side,
 * and the other half is made up in a band along the top and one along the bottom. Each band is
 * stretched only a little where it meets the middle and most at the screen's edge.
 *
 * <p>The video view is laid out at the window's size, which stretches TikTok's picture evenly to
 * it, and a shader over the view draws each row from where it belongs. A shader only reaches what
 * the window draws: TikTok puts its video on a SurfaceView, a layer of its own the window never
 * draws, unless its App AB key {@link #SURFACE_VIEW_GATE} comes back 0, when it uses a TextureView
 * instead. {@link VideoFit#videoSurfaceGate} answers that key while the switch is on. TikTok
 * reads it as the player starts, so the switch asks for a restart, and until then the video is
 * cropped and stretched evenly instead, like Crop and stretch.
 */
final class KeepMiddle {
    private KeepMiddle() {}

    /** TikTok's App AB key for the player's view: 1 a SurfaceView, 0 a TextureView. */
    static final String SURFACE_VIEW_GATE = "player_enable_surface_surfaceview";

    /**
     * How the picture is drawn at the screen's edge, as a share of its own scale: three quarters
     * is a third taller, the most Stretch takes before it leaves a video alone.
     */
    static final double EDGE_SCALE = 0.75;
    /** The flattest the edge may get, twice as tall, once the bands have taken all the room they can. */
    static final double MIN_EDGE_SCALE = 0.5;
    /** The share of the height each band may take, so at least a third of the video stays as it is. */
    static final double MAX_BAND = 1.0 / 3.0;

    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float crop;\n"
            + "uniform float natural;\n"
            + "uniform float band;\n"
            + "uniform float rim;\n"
            + "float ramp(float d) {\n"
            + "  return rim * d + (1.0 / natural - rim) * d * d / (2.0 * band);\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  float v;\n"
            + "  if (p.y <= band) {\n"
            + "    v = ramp(p.y);\n"
            + "  } else if (p.y >= size.y - band) {\n"
            + "    v = 1.0 - ramp(size.y - p.y);\n"
            + "  } else {\n"
            + "    v = ramp(band) + (p.y - band) / natural;\n"
            + "  }\n"
            + "  float u = 0.5 + (p.x / size.x - 0.5) * crop;\n"
            + "  float2 at = clamp(float2(u, v) * size, float2(0.5), size - float2(0.5));\n"
            + "  return content.eval(at);\n"
            + "}\n";

    /**
     * Whether TikTok's player read {@link #SURFACE_VIEW_GATE} as 0 in this process, from the
     * server or from the switch: its videos are drawn in the window, where the shader reaches.
     */
    static volatile boolean videoInWindow;

    /** The views drawn with the shader. Weak, because a view that is gone has nothing to undo. */
    private static final Map<View, Boolean> WARPED = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Each view's own shader, built once: TikTok reuses a handful of views, and a shader's
     * values belong to the one view they were set for. Held as Object so nothing below
     * Android 13 has to know the type.
     */
    private static final Map<View, Object> SHADERS = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean broken;

    /** Whether this Android can run the shader at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /** The switch, on a phone that can draw it. */
    static boolean wanted() {
        return supported() && Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.get();
    }

    /**
     * Whether the shader can reach the picture of {@code view}. A SurfaceView handed over says
     * TikTok is playing on its own layer whatever the key came back as, and the video and its
     * cover both go by that from then on.
     */
    static boolean canWarp(View view) {
        if (view instanceof SurfaceView) {
            videoInWindow = false;
            return false;
        }
        return supported() && videoInWindow && !broken;
    }

    /** What the shader is given for one video in one space. */
    static final class Plan {
        /** The share of the video's width that shows. */
        final double crop;
        /** How many pixels the whole video's height takes at its own shape. */
        final double natural;
        /** How tall each band is, in pixels. */
        final double band;
        /** How much of the video one pixel draws at the screen's edge. */
        final double rim;

        Plan(double crop, double natural, double band, double rim) {
            this.crop = crop;
            this.natural = natural;
            this.band = band;
            this.rim = rim;
        }
    }

    /**
     * The plan for a video TikTok sized {@code videoWidth} by {@code videoHeight}, laid out over
     * a {@code width} by {@code height} window. Null when there is nothing to make up in height,
     * the video as tall as the window or taller, or when making it up would stretch the edge more
     * than twice: a video that far from the window's shape keeps TikTok's size.
     */
    static Plan plan(int videoWidth, int videoHeight, int width, int height) {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return null;
        // How tall the video is across the window's width, and how far short of the window that is.
        double across = width * (double) videoHeight / videoWidth;
        double short_ = height / across;
        if (short_ <= 1.001) return null;
        // Half the gap, as a ratio, comes from scaling the whole video up.
        double scale = Math.sqrt(short_);
        double natural = height / scale;
        double extra = height - natural;
        double band = Math.min(extra / (1 - EDGE_SCALE), height * MAX_BAND);
        double edge = 1 - extra / band;
        if (edge < MIN_EDGE_SCALE - 1e-9) return null;
        return new Plan(1 / scale, natural, band, edge / natural);
    }

    /** Where in the video, from 0 at its top to 1 at its bottom, the shader draws row {@code y} from. */
    static double sourceY(Plan plan, double y, double height) {
        if (y <= plan.band) return ramp(plan, y);
        if (y >= height - plan.band) return 1 - ramp(plan, height - y);
        return ramp(plan, plan.band) + (y - plan.band) / plan.natural;
    }

    /** Where across the video, from 0 to 1, the shader draws column {@code x} from. */
    static double sourceX(Plan plan, double x, double width) {
        return 0.5 + (x / width - 0.5) * plan.crop;
    }

    private static double ramp(Plan plan, double d) {
        return plan.rim * d + (1 / plan.natural - plan.rim) * d * d / (2 * plan.band);
    }

    /**
     * Draws {@code view}, laid out at {@code width} by {@code height}, with the plan for that
     * video, or puts it back the way TikTok draws it when there is none.
     */
    static void warp(View view, int videoWidth, int videoHeight, int width, int height) {
        Plan plan = plan(videoWidth, videoHeight, width, height);
        if (plan == null || !canWarp(view)) {
            clear(view);
            return;
        }
        try {
            draw(view, plan, width, height);
            WARPED.put(view, Boolean.TRUE);
        } catch (RuntimeException failure) {
            broken = true;
            Logger.printException(() -> "Could not keep the middle of the video unstretched", failure);
            clear(view);
        }
    }

    @android.annotation.TargetApi(33)
    private static void draw(View view, Plan plan, int width, int height) {
        RuntimeShader shader = (RuntimeShader) SHADERS.get(view);
        if (shader == null) {
            shader = new RuntimeShader(SHADER);
            SHADERS.put(view, shader);
        }
        shader.setFloatUniform("size", width, height);
        shader.setFloatUniform("crop", (float) plan.crop);
        shader.setFloatUniform("natural", (float) plan.natural);
        shader.setFloatUniform("band", (float) plan.band);
        shader.setFloatUniform("rim", (float) plan.rim);
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
    }

    /** Gives a view drawn with the shader TikTok's own drawing back. */
    static void clear(View view) {
        if (WARPED.isEmpty() || WARPED.remove(view) == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null);
    }

    /** Whether {@code view} is drawn with the shader now. */
    static boolean warped(View view) {
        return WARPED.containsKey(view);
    }

    static void resetForTests() {
        WARPED.clear();
        SHADERS.clear();
        videoInWindow = false;
        broken = false;
    }
}
