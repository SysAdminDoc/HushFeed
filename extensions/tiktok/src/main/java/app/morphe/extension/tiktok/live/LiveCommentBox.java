/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import app.morphe.extension.shared.Logger;

/**
 * The comment box a viewer types in inside a LIVE room, under Dark LIVE panels (#267, #323).
 *
 * <p>The panel's fill follows the room's theme, so the switch makes it dark, but the box's typed
 * text comes from a color every TikTok theme sets to the same dark ink, which no dark theme can
 * flip, and read dark on dark. The patch calls {@link #onPanel} with the panel's root view as
 * TikTok builds it. Once the view has finished its own setup, every text box in it takes a light
 * text color and a softer light hint, on the same answer that darkened the room. A room the switch
 * leaves light keeps TikTok's colors, in dark mode as well.
 */
public final class LiveCommentBox {
    static final int TEXT = Color.WHITE;
    static final int HINT = Color.argb(0x99, 0xff, 0xff, 0xff);

    private LiveCommentBox() {}

    /** Called with the comment panel's root view, first thing in its setup. */
    public static void onPanel(View root) {
        if (root == null) return;
        try {
            root.post(() -> paint(root));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not watch the LIVE comment box", failure);
        }
    }

    /** Lights the text and the hint of each text box under {@code root} while the panel is dark. */
    static void paint(View root) {
        try {
            if (root == null || !LiveControls.darkLivePanels(root.getContext())) return;
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
