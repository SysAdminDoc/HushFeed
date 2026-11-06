/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Bend the video at the edges (#231): the glass pill's rim bending the blurred video behind it,
 * the way the edge of a real lens does, with its colors lifted a little. That is the look of the
 * liquid glass bar the issue showed (KernelSU's, drawn with Kyant0's AndroidLiquidGlass): a band
 * along the rim where the picture is pulled in from further inside, more the closer to the edge,
 * on a quarter circle, so it reads as a rounded glass edge rather than a frame.
 *
 * <p>It runs on the blurred strip {@link GlassVideoBlur} already draws into the pill, as one more
 * effect on the same render node, so nothing more is read back from the video. The pull points
 * inward, so every point it samples is inside the strip. Android 13 added the shaders this needs;
 * below it, or if the shader fails to build, the pill keeps the plain blur.
 */
final class GlassRefraction {
    /** How wide the bending band along the rim is, in dp, at most. */
    static final float BAND_DP = 16f;
    /** How far in the rim itself reaches for its picture, in dp, at most. */
    static final float REACH_DP = 20f;
    /** The color lift: the saturation the blurred video is drawn at. */
    static final float SATURATION = 1.5f;

    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float reach;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 hs = size * 0.5;\n"
            + "  float2 c = p - hs;\n"
            + "  float2 q = abs(c) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  if (-d >= band) return content.eval(p);\n"
            + "  float t = clamp(1.0 + min(d, 0.0) / band, 0.0, 1.0);\n"
            + "  float bend = 1.0 - sqrt(1.0 - t * t);\n"
            + "  float gr = min(radius * 1.5, min(hs.x, hs.y));\n"
            + "  float2 g = abs(c) - (hs - float2(gr));\n"
            + "  float2 m = max(g, float2(0.0));\n"
            + "  float2 n = float2(0.0, 1.0);\n"
            + "  if (length(m) > 0.0) {\n"
            + "    n = m / length(m);\n"
            + "  } else if (g.x > g.y) {\n"
            + "    n = float2(1.0, 0.0);\n"
            + "  }\n"
            + "  float2 s = float2(c.x < 0.0 ? -1.0 : 1.0, c.y < 0.0 ? -1.0 : 1.0);\n"
            + "  return content.eval(p - s * n * bend * reach);\n"
            + "}\n";

    private RuntimeShader shader;
    private boolean broken;

    /** Whether this Android can run the shader at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /** True while the switch is on, Hushfeed isn't paused and the phone can draw it. */
    static boolean wanted() {
        return supported() && Settings.GLASS_REFRACTION.get();
    }

    /**
     * The band and the reach, in pixels, for a pill this size: {@link #BAND_DP} and
     * {@link #REACH_DP} at most, and no more than three quarters of and all of the pill's half
     * height, so a thin pill still has a middle the rim leaves alone.
     */
    static float[] size(int width, int height, float density) {
        float half = Math.max(0f, Math.min(width, height) / 2f);
        return new float[]{
                Math.min(BAND_DP * density, half * 0.75f),
                Math.min(REACH_DP * density, half),
        };
    }

    /** The corner radius the shader is given: the pill's own, never more than half its short side. */
    static float radius(int width, int height, float radius) {
        return Math.max(0f, Math.min(radius, Math.min(width, height) / 2f));
    }

    /**
     * {@code blurred} with the color lift and the bent rim after it, for a pill {@code width} by
     * {@code height} with {@code radius} corners, or {@code blurred} itself when the shader can't
     * run here or failed once already.
     */
    RenderEffect over(RenderEffect blurred, int width, int height, float radius, float density) {
        if (broken || !supported() || width <= 0 || height <= 0) return blurred;
        try {
            return bent(blurred, width, height, radius, density);
        } catch (RuntimeException failure) {
            broken = true;
            Logger.printException(() -> "Could not bend the glass pill's edges", failure);
            return blurred;
        }
    }

    /** True once the shader failed, which leaves the plain blur for the rest of this bar. */
    boolean broken() {
        return broken;
    }

    @android.annotation.TargetApi(33)
    private RenderEffect bent(RenderEffect blurred, int width, int height, float radius, float density) {
        ColorMatrix lift = new ColorMatrix();
        lift.setSaturation(SATURATION);
        RenderEffect rich = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(lift), blurred);
        float[] reach = size(width, height, density);
        if (reach[0] < 1f || reach[1] <= 0f) return rich;
        if (shader == null) shader = new RuntimeShader(SHADER);
        shader.setFloatUniform("size", width, height);
        shader.setFloatUniform("radius", radius(width, height, radius));
        shader.setFloatUniform("band", reach[0]);
        shader.setFloatUniform("reach", reach[1]);
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), rich);
    }
}
