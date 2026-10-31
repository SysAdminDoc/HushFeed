/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import static org.junit.Assert.assertEquals;

import android.graphics.Color;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The comment box inside a LIVE room (#267): a dark page makes the typed text and the hint light,
 * a light page keeps TikTok's colors.
 */
@RunWith(RobolectricTestRunner.class)
public class AmoledLiveInputTest {
    private static final int TIKTOK_TEXT = 0xFF222222;
    private static final int TIKTOK_HINT = 0xFF888888;

    private static EditText box(FrameLayout root) {
        EditText box = new EditText(root.getContext());
        box.setTextColor(TIKTOK_TEXT);
        box.setHintTextColor(TIKTOK_HINT);
        FrameLayout inner = new FrameLayout(root.getContext());
        inner.addView(box);
        root.addView(inner);
        return box;
    }

    @Test
    @Config(qualifiers = "night")
    public void aDarkPageLightensTheTypedTextAndTheHint() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        AmoledLiveInput.paint(root);
        assertEquals(Color.WHITE, box.getCurrentTextColor());
        assertEquals(AmoledLiveInput.HINT, box.getCurrentHintTextColor());
    }

    @Test
    @Config(qualifiers = "notnight")
    public void aLightPageKeepsTikToksColors() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        AmoledLiveInput.paint(root);
        assertEquals(TIKTOK_TEXT, box.getCurrentTextColor());
        assertEquals(TIKTOK_HINT, box.getCurrentHintTextColor());
    }

    @Test
    public void aMissingPanelIsLeftAlone() {
        AmoledLiveInput.onPanel(null);
        AmoledLiveInput.paint(null);
    }
}
