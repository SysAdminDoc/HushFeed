/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Forked from: https://github.com/SysAdminDoc/HushGram/blob/main/extensions/instagram/src/main/java/app/hushgram/extension/instagram/misc/GlassLens.java
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;

import app.morphe.extension.shared.Logger;

/**
 * The magnifier in the glass lens's bubble, on Android 13 and newer: the blurred video under the
 * lens drawn a little larger, bending more toward the bubble's rim, as glass would. It is the
 * blurred strip GlassVideoBlur already draws into the pill, run through a small shader into a
 * render node of its own, so nothing more is read back from the video. Before Android 13 there is
 * no shader to run and the bubble has its rim and sheen alone. A failure turns the magnifier off
 * for this lens and the bubble carries on.
 */
final class GlassLens {
    /** How much the centre of the bubble magnifies, and how much more its rim bends the video. */
    static final float CENTRE_MAGNIFY = 0.10f;
    static final float RIM_BEND = 0.14f;

    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float centre;\n"
            + "uniform float rim;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 q = abs(p - mid) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float depth = clamp(-d / max(1.0, min(hs.x, hs.y)), 0.0, 1.0);\n"
            + "  float bend = (1.0 - depth) * (1.0 - depth);\n"
            + "  float2 source = mid + (p - mid) * (1.0 - centre - rim * bend);\n"
            + "  return content.eval(source) * inside;\n"
            + "}\n";

    /** Whether this Android can run the magnifier at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private RenderNode node;
    private RuntimeShader shader;
    private boolean broken;

    /**
     * Draws the magnified video over {@code box} ({@code radius} corners), in the bar's
     * coordinates, into {@code canvas}. {@code backdrop} is the blurred strip, which the pill
     * draws at {@code left}, {@code top}. {@code width} and {@code height} are the bar's. Says
     * whether it drew.
     */
    boolean paint(Canvas canvas, RenderNode backdrop, float left, float top, int width, int height,
            RectF box, float radius) {
        if (broken || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backdrop == null
                || width <= 0 || height <= 0 || !canvas.isHardwareAccelerated()) {
            return false;
        }
        try {
            if (shader == null) shader = new RuntimeShader(SHADER);
            if (node == null) node = new RenderNode("hushfeed-glass-lens");
            shader.setFloatUniform("box", box.left, box.top, box.right, box.bottom);
            shader.setFloatUniform("radius", Math.max(0f, radius));
            shader.setFloatUniform("centre", CENTRE_MAGNIFY);
            shader.setFloatUniform("rim", RIM_BEND);
            node.setPosition(0, 0, width, height);
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            Canvas recording = node.beginRecording(width, height);
            try {
                recording.translate(left, top);
                recording.drawRenderNode(backdrop);
            } finally {
                node.endRecording();
            }
            canvas.drawRenderNode(node);
            return true;
        } catch (RuntimeException failure) {
            broken = true;
            Logger.printException(() -> "Could not draw the glass lens's magnifier", failure);
            return false;
        }
    }
}
