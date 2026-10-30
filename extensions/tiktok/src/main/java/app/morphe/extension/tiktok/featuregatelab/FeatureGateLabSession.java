/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.app.Activity;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.shared.Utils;

public final class FeatureGateLabSession {
    /**
     * The Lab's restart debt lives in the settings pages' own set, as one change, so the
     * restart row on those pages counts it and the Lab's row and theirs never disagree.
     */
    static final String RESTART_KEY = "hushfeed_feature_gate_lab";
    private static boolean noticeShown;

    private FeatureGateLabSession() {}

    public static void begin() {
        noticeShown = false;
    }

    static void resetForTests() {
        AbstractPreferenceFragment.restartPending.remove(RESTART_KEY);
        noticeShown = false;
    }

    public static void markRestartNeeded() {
        AbstractPreferenceFragment.restartPending.add(RESTART_KEY);
    }

    public static boolean restartNeeded() {
        return AbstractPreferenceFragment.restartPending.contains(RESTART_KEY);
    }

    /**
     * Retained entry point for callers leaving the Lab. The Lab page shows a restart row once
     * a change owes one, and the settings pages show it after leaving.
     */
    public static void showRestartDialog(Activity activity) {
    }

    public static void showAfterHostExit(Activity host) {
        showRestartDialog(host);
    }
}
