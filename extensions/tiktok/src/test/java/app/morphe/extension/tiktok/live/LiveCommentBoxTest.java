/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;

import android.graphics.Color;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The comment box inside a LIVE room (#267, #323): a room Dark LIVE panels darkened makes the
 * typed text and the hint light, and a room it left light keeps TikTok's colors.
 */
@RunWith(RobolectricTestRunner.class)
public class LiveCommentBoxTest {
    private static final int TIKTOK_TEXT = 0xFF161823;
    private static final int TIKTOK_HINT = 0xFF888888;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.liveControlsEnabled = true;
        Settings.DARK_LIVE_PANELS.save(true);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.liveControlsEnabled = false;
        Settings.DARK_LIVE_PANELS.save(Settings.DARK_LIVE_PANELS.defaultValue);
    }

    private static EditText box(FrameLayout root) {
        EditText box = new EditText(root.getContext());
        box.setTextColor(TIKTOK_TEXT);
        box.setHintTextColor(TIKTOK_HINT);
        FrameLayout inner = new FrameLayout(root.getContext());
        inner.addView(box);
        root.addView(inner);
        return box;
    }

    private static void assertTikToksColors(EditText box) {
        assertEquals(TIKTOK_TEXT, box.getCurrentTextColor());
        assertEquals(TIKTOK_HINT, box.getCurrentHintTextColor());
    }

    @Test
    @Config(qualifiers = "night")
    public void aDarkRoomLightensTheTypedTextAndTheHint() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertEquals(Color.WHITE, box.getCurrentTextColor());
        assertEquals(LiveCommentBox.HINT, box.getCurrentHintTextColor());
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightModeKeepsTikToksColors() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertTikToksColors(box);
    }

    /** The room stays light with the switch off, even in dark mode, so white text would vanish. */
    @Test
    @Config(qualifiers = "night")
    public void aRoomTheSwitchLeftLightKeepsTikToksColorsInDarkMode() {
        Settings.DARK_LIVE_PANELS.save(false);
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertTikToksColors(box);
    }

    @Test
    @Config(qualifiers = "night")
    public void pauseLeavesTheBoxAlone() {
        PausedProcess.set(true);
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertTikToksColors(box);
    }

    @Test
    public void aMissingPanelIsLeftAlone() {
        LiveCommentBox.onPanel(null);
        LiveCommentBox.paint(null);
    }
}
