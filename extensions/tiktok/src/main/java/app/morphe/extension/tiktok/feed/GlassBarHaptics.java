/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Forked from: https://github.com/SysAdminDoc/HushGram/blob/main/extensions/instagram/src/main/java/app/hushgram/extension/instagram/misc/GlassTabBar.java
 */
package app.morphe.extension.tiktok.feed;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

import app.morphe.extension.shared.Logger;

/**
 * Glass haptics: the tick the glass lens plays as it passes a tab, in the style picked, as on
 * HushGram's glass tab bar. "short" is a single 4 ms pulse at 170 of 255, kept short rather than
 * weak because a motor rings on after a gentler signal; a motor that can't be driven at a strength
 * plays the light tick instead. "system" is the phone's own click effect, which a maker tunes to
 * the motor. "soft" is the tick primitive at {@link #SOFT_SCALE} of its strength and "full" at all
 * of it, or the built-in tick on a phone that can't play primitives.
 *
 * <p>They play on a thread of their own, with the touch usage on Android 13 and newer, so they
 * follow the phone's touch feedback setting and never hold up a frame. Older Android uses the
 * view's own haptics for the system, soft and full ticks. Ticks closer together than
 * {@link #MIN_TICK_GAP_MS} are dropped.
 */
final class GlassBarHaptics {
    static final String SHORT = "short", SYSTEM = "system", SOFT = "soft", FULL = "full";
    /** How much of the phone's tick primitive the soft tick plays. */
    static final float SOFT_SCALE = 0.45f;
    /** The short pulse: how long, and how hard of 255. */
    static final int SHORT_MS = 4;
    static final int SHORT_AMPLITUDE = 170;
    static final long MIN_TICK_GAP_MS = 35;

    private static Handler handler;
    private static long lastTick;
    private static int primitives = -1;

    private GlassBarHaptics() {
    }

    /** Whether a tick at {@code now} is far enough after the one at {@code last} to play. */
    static boolean farEnough(long now, long last) {
        return now - last >= MIN_TICK_GAP_MS;
    }

    /** Plays one tick in {@code style} for {@code view}, unless one played moments ago. */
    static void tick(View view, String style) {
        try {
            long now = SystemClock.uptimeMillis();
            if (!farEnough(now, lastTick)) return;
            lastTick = now;
            // Android 7 has no VibrationEffect, so every style is the view's tick there.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || (SYSTEM.equals(style) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return;
            }
            Context context = view.getContext().getApplicationContext();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !SHORT.equals(style)
                    && !SYSTEM.equals(style)) {
                view.performHapticFeedback(FULL.equals(style) ? HapticFeedbackConstants.CLOCK_TICK
                        : HapticFeedbackConstants.TEXT_HANDLE_MOVE);
                return;
            }
            handler().post(() -> play(context, style));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not play the glass bar's tick", failure);
        }
    }

    @android.annotation.TargetApi(26)
    private static void play(Context context, String style) {
        try {
            Vibrator vibrator = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? context.getSystemService(VibratorManager.class).getDefaultVibrator()
                    : (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            VibrationEffect effect = null;
            // Before Android 10 the system tick never gets here: tick() plays it through the view.
            if (SYSTEM.equals(style) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            } else if (SHORT.equals(style) && vibrator.hasAmplitudeControl()) {
                effect = VibrationEffect.createOneShot(SHORT_MS, SHORT_AMPLITUDE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (primitives < 0) {
                    primitives = vibrator.areAllPrimitivesSupported(
                            VibrationEffect.Composition.PRIMITIVE_TICK) ? 1 : 0;
                }
                if (primitives == 1) {
                    effect = VibrationEffect.startComposition().addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_TICK,
                            FULL.equals(style) ? 1f : SOFT_SCALE).compose();
                }
            }
            // Android 9 has no predefined effects, so a motor without amplitude control gets the short pulse.
            if (effect == null) {
                effect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        : VibrationEffect.createOneShot(SHORT_MS, VibrationEffect.DEFAULT_AMPLITUDE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                vibrator.vibrate(effect);
            }
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not play the glass bar's tick", failure);
        }
    }

    private static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread("hushfeed-glass-haptics");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }
}
