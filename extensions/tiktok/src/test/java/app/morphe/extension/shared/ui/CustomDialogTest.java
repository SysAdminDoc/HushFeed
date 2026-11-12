package app.morphe.extension.shared.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.morphe.extension.shared.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CustomDialogTest {
    private static final String LONG_NEUTRAL = "Restore every default setting now";

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h640dp-port-mdpi")
    public void rightToLeftKeepsTheGapBetweenTheButtons() {
        assertTheButtonsKeepTheirGap(View.LAYOUT_DIRECTION_RTL);
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    public void leftToRightKeepsTheGapBetweenTheButtons() {
        assertTheButtonsKeepTheirGap(View.LAYOUT_DIRECTION_LTR);
    }

    private static void assertTheButtonsKeepTheirGap(int direction) {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Pair<Dialog, LinearLayout> dialog = shown(owner.get(), true, null);
            try {
                ViewGroup row = (ViewGroup) buttonsOf(dialog.second).getChildAt(0);
                assertEquals("the dialog is not laid out the way under test, so this proves nothing",
                        direction, row.getLayoutDirection());
                assertEquals("the buttons stacked, so this proves nothing", 2, row.getChildCount());
                View cancel = row.getChildAt(0);
                View ok = row.getChildAt(1);
                assertTrue("the buttons were never laid out", cancel.getWidth() > 0 && ok.getWidth() > 0);
                if (direction == View.LAYOUT_DIRECTION_RTL) {
                    assertEquals("Cancel does not reach the right edge", row.getWidth(), cancel.getRight());
                    assertEquals("the gap between the buttons", Dim.dp8, cancel.getLeft() - ok.getRight());
                    assertEquals("OK does not reach the left edge", 0, ok.getLeft());
                } else {
                    assertEquals("Cancel does not reach the left edge", 0, cancel.getLeft());
                    assertEquals("the gap between the buttons", Dim.dp8, ok.getLeft() - cancel.getRight());
                    assertEquals("OK does not reach the right edge", row.getWidth(), ok.getRight());
                }
            } finally {
                dialog.first.dismiss();
            }
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-notnight-mdpi", fontScale = 2f)
    public void largeTextFitsInEveryButton() {
        assertLargeTextFitsInEveryButton(false);
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-night-mdpi", fontScale = 2f)
    public void darkLargeTextFitsInEveryButton() {
        assertLargeTextFitsInEveryButton(true);
    }

    @After
    public void restoreDarkMode() {
        Utils.setIsDarkModeEnabled(true);
    }

    private static void assertLargeTextFitsInEveryButton(boolean dark) {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            assertEquals(2f, activity.getResources().getConfiguration().fontScale, 0);
            int night = activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            assertEquals("the theme under test never reached the dialog, so this proves nothing",
                    dark, night == Configuration.UI_MODE_NIGHT_YES);
            Utils.setIsDarkModeEnabled(dark);
            List<Button> checked = new ArrayList<>();
            // One button alone, two in a row, and three stacked.
            List<Pair<Boolean, String>> shapes = List.of(new Pair<Boolean, String>(false, null),
                    new Pair<Boolean, String>(true, null), new Pair<>(true, LONG_NEUTRAL));
            List<String> arrangements = List.of("alone", "row", "stacked");
            for (int at = 0; at < shapes.size(); at++) {
                Pair<Boolean, String> shape = shapes.get(at);
                Pair<Dialog, LinearLayout> dialog = shown(activity, shape.first, shape.second);
                try {
                    assertEquals("the buttons at twice the text size", arrangements.get(at),
                            arrangement(buttonsOf(dialog.second)));
                    for (Button button : buttonsIn(buttonsOf(dialog.second))) {
                        int text = button.getLayout().getHeight();
                        assertTrue("the text is no taller than the old fixed height, so this proves nothing",
                                text > Dim.dp36);
                        assertTrue("\"" + button.getText() + "\" is " + button.getHeight()
                                        + "px tall for " + text + "px of text",
                                button.getHeight() >= text + button.getCompoundPaddingTop()
                                        + button.getCompoundPaddingBottom());
                        assertTrue("\"" + button.getText() + "\" has no fill of its own",
                                button.getBackground() instanceof ShapeDrawable);
                        int fill = ((ShapeDrawable) button.getBackground()).getPaint().getColor();
                        double ratio = contrast(button.getCurrentTextColor(), fill);
                        assertTrue("\"" + button.getText() + "\" is " + ratio + ":1 on its fill",
                                ratio >= 4.5);
                        checked.add(button);
                    }
                } finally {
                    dialog.first.dismiss();
                }
            }
            assertEquals(1 + 2 + 3, checked.size());
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    public void thePortraitWidthFollowsTheScreenAsItIsNow() {
        assertEquals(Resources.getSystem().getDisplayMetrics().widthPixels, Dim.pctPortraitWidth(100));
        // An unfold or a new display size: a dialog opened afterwards sizes from the new screen.
        RuntimeEnvironment.setQualifiers("w600dp-h900dp-port-mdpi");
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        assertEquals("the new size never reached the screen, so this proves nothing",
                Dim.dp(600), metrics.widthPixels);
        assertEquals(metrics.widthPixels, Dim.pctPortraitWidth(100));
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    public void buttonsStackWhenTheAppWindowIsNarrowerThanTheScreen() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            // Split screen: the display stays 360dp wide while the app gets a 200dp window.
            Configuration narrow = new Configuration(activity.getResources().getConfiguration());
            narrow.screenWidthDp = 200;
            ContextThemeWrapper inSplitScreen =
                    new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault);
            inSplitScreen.applyOverrideConfiguration(narrow);
            Pair<Dialog, LinearLayout> dialog = shown(activity, inSplitScreen, true, "Reset");
            try {
                LinearLayout layout = dialog.second;
                int padding = layout.getPaddingLeft() + layout.getPaddingRight();
                int width = Dim.dp(200) * 90 / 100;
                ViewGroup buttons = buttonsOf(layout);
                int needed = Dim.dp8 * 2;
                for (Button button : buttonsIn(buttons)) {
                    button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    needed += button.getMeasuredWidth();
                }
                assertTrue("the buttons fit the app's window side by side, so this proves nothing",
                        needed > width - padding);
                assertTrue("the buttons don't fit the screen's width either, so this proves nothing",
                        needed <= Dim.pctPortraitWidth(90) - padding);
                assertEquals("the dialog is wider than the app's window",
                        width, dialog.first.getWindow().getAttributes().width);
                assertEquals("the buttons were squeezed into one row " + needed + "px wide in "
                        + (width - padding) + "px", "stacked", arrangement(buttons));
            } finally {
                dialog.first.dismiss();
            }
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    public void buttonsInARowShareOneHeight() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Pair<Dialog, LinearLayout> dialog = shown(owner.get(), true, null);
            try {
                ViewGroup row = (ViewGroup) buttonsOf(dialog.second).getChildAt(0);
                assertEquals("the buttons stacked, so this proves nothing", 2, row.getChildCount());
                Button cancel = (Button) row.getChildAt(0);
                Button ok = (Button) row.getChildAt(1);
                assertTrue("the buttons were never laid out", cancel.getHeight() > 0);
                // A label in a taller script measures taller than the one beside it.
                int tall = cancel.getHeight() * 2;
                ok.setMinHeight(tall);
                layOut(dialog.first, owner.get());
                assertTrue("OK never grew, so this proves nothing", ok.getHeight() >= tall);
                assertEquals("Cancel is shorter than OK beside it", ok.getHeight(), cancel.getHeight());
            } finally {
                dialog.first.dismiss();
            }
        }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    public void buttonsWiderThanTheDialogStackOnAWideScreen() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Pair<Dialog, LinearLayout> dialog = shown(owner.get(), true, LONG_NEUTRAL);
            try {
                LinearLayout layout = dialog.second;
                ViewGroup buttons = buttonsOf(layout);
                int needed = Dim.dp8 * 2;
                for (Button button : buttonsIn(buttons)) {
                    button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    needed += button.getMeasuredWidth();
                }
                int room = layout.getWidth() - layout.getPaddingLeft() - layout.getPaddingRight();
                assertTrue("the buttons fit the dialog side by side, so this proves nothing",
                        needed > room);
                assertTrue("the buttons don't fit 80% of the screen either, so this proves nothing",
                        needed <= Resources.getSystem().getDisplayMetrics().widthPixels * 80 / 100);
                assertEquals("the buttons were squeezed into one row " + needed + "px wide in "
                        + room + "px", 3 + 2, buttons.getChildCount());
            } finally {
                dialog.first.dismiss();
            }
        }
    }

    /** A dialog with OK, Cancel when {@code cancel}, and a neutral button when {@code neutral} isn't null. */
    private static Pair<Dialog, LinearLayout> shown(Activity activity, boolean cancel, String neutral) {
        return shown(activity, activity, cancel, neutral);
    }

    /** The same, built from {@code context}, which may see a narrower window than the activity's screen. */
    private static Pair<Dialog, LinearLayout> shown(Activity activity, Context context, boolean cancel,
                                                    String neutral) {
        Utils.setContext(activity);
        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(context, "Reset settings?",
                "This puts every setting back the way it came.", null, null, () -> { },
                cancel ? () -> { } : null, neutral, neutral == null ? null : () -> { }, true);
        dialog.first.show();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        layOut(dialog.first, activity);
        return dialog;
    }

    /** Measures and lays out the dialog at the width its window asks for. */
    private static void layOut(Dialog dialog, Activity activity) {
        View decor = dialog.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(
                        dialog.getWindow().getAttributes().width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        activity.getResources().getDisplayMetrics().heightPixels, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    }

    /** How the buttons were laid out: one "alone", side by side in a "row", or "stacked". */
    private static String arrangement(ViewGroup buttons) {
        if (buttons.getChildCount() > 1) return "stacked";
        return ((ViewGroup) buttons.getChildAt(0)).getChildCount() > 1 ? "row" : "alone";
    }

    /** The dialog's last child holds its buttons. */
    private static ViewGroup buttonsOf(LinearLayout layout) {
        return (ViewGroup) layout.getChildAt(layout.getChildCount() - 1);
    }

    private static List<Button> buttonsIn(ViewGroup group) {
        List<Button> found = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button) found.add((Button) child);
            else if (child instanceof ViewGroup) found.addAll(buttonsIn((ViewGroup) child));
        }
        return found;
    }

    /** WCAG contrast between two opaque colours. */
    private static double contrast(int first, int second) {
        double one = luminance(first), two = luminance(second);
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
}
