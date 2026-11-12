/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.preference.PreferenceActivity;
import android.text.InputType;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.DownloadDestination;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Accessibility contract for the three hand-built settings editor dialogs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en-w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EditorAccessibilityTest {
    public static final class TestActivity extends PreferenceActivity {}

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void seedValues() {
        Settings.BLOCKED_CAPTION_WORDS.save("spoiler");
        Settings.MAX_VIDEO_SECONDS.save(42);
        Settings.DOWNLOAD_VIDEO_PATH.save("Movies/Hushfeed");
    }

    @After public void resetValues() {
        Settings.BLOCKED_CAPTION_WORDS.resetToDefault();
        Settings.MAX_VIDEO_SECONDS.resetToDefault();
        Settings.DOWNLOAD_VIDEO_PATH.resetToDefault();
        Utils.setIsDarkModeEnabled(true);
    }

    @Test @Config(fontScale = 1f)
    public void preferenceEditorsHaveOneNamedInputAtNormalTextSize() {
        assertPreferenceEditors(1f, true);
    }

    @Test @Config(fontScale = 2f)
    public void preferenceEditorsHaveOneNamedInputAtDoubleTextSize() {
        assertPreferenceEditors(2f, true);
    }

    @Test @Config(qualifiers = "en-w480dp-h960dp-notnight-mdpi", fontScale = 2f)
    public void lightPreferenceEditorsHaveOneNamedInputAtDoubleTextSize() {
        assertPreferenceEditors(2f, false);
    }

    private static void assertPreferenceEditors(float expectedScale, boolean dark) {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            assertEquals(expectedScale,
                    activity.getResources().getConfiguration().fontScale, 0.01f);
            SettingsUi.syncDarkMode(activity);
            assertEquals("the theme under test never reached the editors, so this proves nothing",
                    dark, ScreenColors.current() == ScreenColors.DARK);

            InputTextPreference text = new InputTextPreference(
                    activity,
                    "Blocked caption words",
                    "Comma separated words or phrases.",
                    Settings.BLOCKED_CAPTION_WORDS
            );
            assertEditor(text.onCreateDialogView(), "Blocked caption words", "spoiler",
                    InputType.TYPE_CLASS_TEXT);

            NumberInputPreference number = new NumberInputPreference(
                    activity,
                    "Maximum video length",
                    "Seconds. Zero keeps every length.",
                    Settings.MAX_VIDEO_SECONDS,
                    "%1$s second",
                    "%1$s seconds"
            );
            assertEditor(number.onCreateDialogView(), "Maximum video length", "42",
                    InputType.TYPE_CLASS_NUMBER);

            DownloadPathPreference destination = new DownloadPathPreference(
                    activity,
                    "Video destination",
                    Settings.DOWNLOAD_VIDEO_PATH,
                    DownloadDestination.Kind.VIDEO
            );
            assertEditor(destination.onCreateDialogView(), "Video destination",
                    "Movies/Hushfeed", InputType.TYPE_CLASS_TEXT);
        }
    }

    private static void assertEditor(View dialog, String labelText, String currentValue,
                                     int expectedInputClass) {
        TextView label = findText(dialog, labelText);
        EditText input = find(dialog, EditText.class);
        assertTrue("the visible label is missing", label != null);
        assertTrue("the dialog input is missing", input != null);
        assertNotEquals("the input has no address for its label", View.NO_ID, input.getId());
        assertEquals("the visible label is not connected to its input",
                input.getId(), label.getLabelFor());
        assertEquals("the visible label is a second accessibility stop",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, label.getImportantForAccessibility());
        assertFalse("the visible label takes focus before the input", label.isFocusable());
        assertTrue("the input cannot take focus", input.isFocusable());
        assertNull("the accessible name replaced the value with a content description",
                input.getContentDescription());

        // EditTextPreference copies its stored value during the platform bind that follows this
        // custom-view creation. Type it here so this node-level test exercises the same final
        // state without depending on AlertDialog's unrelated binding internals.
        input.setText(currentValue);
        assertReadable(dialog);
        AccessibilityNodeInfo node = input.createAccessibilityNodeInfo();
        assertEquals(currentValue, String.valueOf(node.getText()));
        assertTrue("the node does not name the setting it edits",
                String.valueOf(node.getHintText()).contains(labelText));
        assertTrue("the node does not expose its required input class",
                (node.getInputType() & InputType.TYPE_MASK_CLASS) == expectedInputClass);
        assertTrue("the node does not expose its editable state", node.isEditable());
        assertTrue("an enabled input is announced as disabled", node.isEnabled());
        assertNull("the node's name replaced its current value",
                node.getContentDescription());

        input.setEnabled(false);
        AccessibilityNodeInfo disabled = input.createAccessibilityNodeInfo();
        assertFalse("a disabled input is announced as enabled", disabled.isEnabled());
        assertEquals(currentValue, String.valueOf(disabled.getText()));
        assertEquals(node.getInputType(), disabled.getInputType());
        assertTrue(String.valueOf(disabled.getHintText()).contains(labelText));
    }

    /**
     * Laid out as narrow as a phone's dialog gets, every piece of text in the editor is drawn
     * in full and reads at 4.5:1 on the dialog's surface in the theme under test.
     */
    private static void assertReadable(View dialog) {
        int width = SettingsUi.dp(dialog.getContext(), 280);
        dialog.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        dialog.layout(0, 0, dialog.getMeasuredWidth(), dialog.getMeasuredHeight());

        int surface = ScreenColors.current().dialog;
        List<TextView> texts = new ArrayList<>();
        collectTexts(dialog, texts);
        int checked = 0;
        for (TextView view : texts) {
            if (view.getVisibility() != View.VISIBLE || view.length() == 0) continue;
            String name = "\"" + view.getText() + "\"";
            Layout layout = view.getLayout();
            assertNotNull(name + " was never laid out", layout);
            int needed = layout.getHeight() + view.getCompoundPaddingTop()
                    + view.getCompoundPaddingBottom();
            assertTrue(name + " is " + view.getHeight() + "px tall for " + needed + "px of text",
                    view.getHeight() >= needed);
            if (!(view instanceof EditText)) {
                assertEquals(name + " is cut short with an ellipsis",
                        0, layout.getEllipsisCount(layout.getLineCount() - 1));
            }
            // A heading's gradient replaces its text colour once it's attached, and
            // ShimmerTitleTest holds every stop of it to 4.5:1 in both themes.
            if (ShimmerTitle.wears(view)) continue;
            double ratio = contrast(view.getCurrentTextColor(), surface);
            assertTrue(name + " is " + ratio + ":1 on the dialog", ratio >= 4.5);
            checked++;
        }
        assertTrue("fewer than a description and a value were checked, so this proves nothing",
                checked >= 2);
    }

    private static void collectTexts(View view, List<TextView> found) {
        if (view instanceof TextView) found.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectTexts(group.getChildAt(index), found);
            }
        }
    }

    /** WCAG contrast of text, blended onto the surface first when it is translucent. */
    private static double contrast(int text, int surface) {
        int alpha = Color.alpha(text);
        int shown = Color.rgb(
                (Color.red(text) * alpha + Color.red(surface) * (255 - alpha)) / 255,
                (Color.green(text) * alpha + Color.green(surface) * (255 - alpha)) / 255,
                (Color.blue(text) * alpha + Color.blue(surface) * (255 - alpha)) / 255);
        double one = luminance(shown), two = luminance(surface);
        return (Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05);
    }

    private static double luminance(int colour) {
        double[] parts = new double[3];
        int[] raw = {Color.red(colour), Color.green(colour), Color.blue(colour)};
        for (int at = 0; at < 3; at++) {
            double channel = raw[at] / 255.0;
            parts[at] = channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2];
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = find(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
