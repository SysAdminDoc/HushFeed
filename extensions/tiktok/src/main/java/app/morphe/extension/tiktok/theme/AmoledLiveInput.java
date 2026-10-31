/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * The comment box a viewer types in inside a LIVE room, for AMOLED dark theme (#267).
 *
 * <p>The panel's fill follows the dark palette, so AMOLED dark theme makes it black, but the box's
 * typed text and hint keep a dark color of their own and read dark on black. The patch calls
 * {@link #onPanel} with the panel's root view as TikTok builds it. Once the view has finished its
 * own setup, every text box in it takes a light text color and a softer light hint, whenever the
 * page is dark. Light mode keeps TikTok's colors.
 */
public final class AmoledLiveInput {
    static final int TEXT = Color.WHITE;
    static final int HINT = Color.argb(0x99, 0xff, 0xff, 0xff);

    private AmoledLiveInput() {}

    /** Called with the comment panel's root view, first thing in its setup. */
    public static void onPanel(View root) {
        if (root == null) return;
        try {
            root.post(() -> paint(root));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not watch the LIVE comment box", failure);
        }
    }

    /** Lights the text and the hint of each text box under {@code root} while the page is dark. */
    static void paint(View root) {
        try {
            if (!SettingsUi.isDarkContext(root.getContext())) return;
            light(root);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not color the LIVE comment box", failure);
        }
    }

    private static void light(View view) {
        if (view instanceof EditText) {
            EditText box = (EditText) view;
            box.setTextColor(TEXT);
            box.setHintTextColor(HINT);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) light(group.getChildAt(index));
    }
}
