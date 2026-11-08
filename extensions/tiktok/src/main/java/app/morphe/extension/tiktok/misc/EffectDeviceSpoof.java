/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The phone model TikTok's effect platform sends (issue #176).
 *
 * <p>Before an effect downloads, the effect platform asks TikTok's server whether the phone can
 * run it, and sends the phone's {@code Build.MODEL} as its {@code device_type}. A refusal comes
 * back as API error 2003 and shows "This effect doesn't work on this device". The patch routes
 * the model strings that feed the effect platform's two config builders through here. While the
 * switch is on they carry a Galaxy S24 Ultra, or the model the reader typed under the switch,
 * since one effect may open for one phone and not another. Sign-in, analytics and every other
 * model read keep the real phone.
 *
 * <p>Off, paused, or a failure in here, and the real model goes out as TikTok has it.
 */
public final class EffectDeviceSpoof {
    /** The model reported while the switch is on and no other is typed. */
    static final String NEWER_MODEL = "SM-S928B";
    /** Longer than any model name a phone reports. */
    static final int LONGEST_MODEL = 40;
    /** What the default reads as in the settings list. */
    static final String NEWER_PHONE = "Galaxy S24 Ultra";

    /**
     * The phones the settings row lists, newest first: the name shown, then the model sent, which
     * is what each one reports as {@code Build.MODEL} (#176). Pixels report their own name.
     */
    static final String[][] LISTED_PHONES = {
            {"Galaxy S25 Ultra", "SM-S938B"},
            {"Galaxy S25 Edge", "SM-S937B"},
            {"Galaxy S25+", "SM-S936B"},
            {"Galaxy S25", "SM-S931B"},
            {"Galaxy Z Fold7", "SM-F966B"},
            {"Galaxy Z Flip7", "SM-F766B"},
            {"Galaxy S24", "SM-S921B"},
            {"Galaxy Z Fold6", "SM-F956B"},
            {"Galaxy Z Flip6", "SM-F741B"},
            {"Galaxy S23 Ultra", "SM-S918B"},
            {"Galaxy A56", "SM-A566B"},
            {"Pixel 10 Pro XL", "Pixel 10 Pro XL"},
            {"Pixel 10 Pro", "Pixel 10 Pro"},
            {"Pixel 10", "Pixel 10"},
            {"Pixel 9 Pro XL", "Pixel 9 Pro XL"},
            {"Pixel 9 Pro", "Pixel 9 Pro"},
            {"Pixel 9", "Pixel 9"},
            {"Pixel 8 Pro", "Pixel 8 Pro"},
    };

    private EffectDeviceSpoof() {
    }

    /** Injection point right after each {@code Build.MODEL} read that feeds the effect platform. Never throws. */
    public static String deviceType(String real) {
        String model;
        try {
            if (!Settings.REPORT_NEWER_PHONE_TO_EFFECTS.get()) return real;
            model = chosenModel(Settings.EFFECT_PHONE_MODEL.get());
        } catch (Throwable failure) {
            return real;
        }
        Logger.printDebug(() -> "Effect device type: reporting " + model + " instead of " + real);
        return model;
    }

    /** The typed model, trimmed, or the Galaxy S24 Ultra when nothing usable is typed. */
    static String chosenModel(@Nullable String typed) {
        String model = typed == null ? "" : typed.trim();
        return isModelName(model) ? model : NEWER_MODEL;
    }

    /**
     * The models the settings row lists, in the order of {@link #listedLabels()}: empty first,
     * which is the Galaxy S24 Ultra, then every listed phone.
     */
    public static String[] listedModels() {
        String[] models = new String[LISTED_PHONES.length + 1];
        models[0] = "";
        for (int i = 0; i < LISTED_PHONES.length; i++) models[i + 1] = LISTED_PHONES[i][1];
        return models;
    }

    /** What the list shows for each of {@link #listedModels()}: a phone's name, with its model when that differs. */
    public static String[] listedLabels() {
        String[] labels = new String[LISTED_PHONES.length + 1];
        labels[0] = L10n.f("%1$s, the default", label(NEWER_PHONE, NEWER_MODEL));
        for (int i = 0; i < LISTED_PHONES.length; i++) labels[i + 1] = label(LISTED_PHONES[i][0], LISTED_PHONES[i][1]);
        return labels;
    }

    private static String label(String name, String model) {
        return name.equals(model) ? name : name + " (" + model + ")";
    }

    /** The settings row's check: empty is fine (the Galaxy S24 Ultra), anything else has to read as a model. */
    @Nullable
    public static String modelProblem(@Nullable String typed) {
        String model = typed == null ? "" : typed.trim();
        if (model.isEmpty() || isModelName(model)) return null;
        return L10n.t("Use up to 40 letters and numbers. Spaces, dots, dashes and brackets are fine too.");
    }

    /**
     * Whether {@code text} reads as a phone model: up to {@link #LONGEST_MODEL} letters, digits,
     * spaces and the marks model names use, starting with a letter or digit. It goes out in a
     * request, so nothing else gets through.
     */
    public static boolean isModelName(@Nullable String text) {
        if (text == null || text.isEmpty() || text.length() > LONGEST_MODEL) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean plain = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (i == 0 ? !plain : !plain && " ._+-()".indexOf(c) < 0) return false;
        }
        return true;
    }
}
