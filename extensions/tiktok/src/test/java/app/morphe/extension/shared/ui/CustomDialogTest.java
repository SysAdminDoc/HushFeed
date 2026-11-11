package app.morphe.extension.shared.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Resources;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

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

    @Test @Config(qualifiers = "w360dp-h640dp-port-mdpi", fontScale = 2f)
    public void largeTextFitsInEveryButton() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            assertEquals(2f, activity.getResources().getConfiguration().fontScale, 0);
            List<Button> checked = new ArrayList<>();
            // One button alone, two in a row, and three stacked.
            for (Pair<Boolean, String> shape : List.of(new Pair<Boolean, String>(false, null),
                    new Pair<Boolean, String>(true, null), new Pair<>(true, LONG_NEUTRAL))) {
                Pair<Dialog, LinearLayout> dialog = shown(activity, shape.first, shape.second);
                try {
                    for (Button button : buttonsIn(buttonsOf(dialog.second))) {
                        int text = button.getLayout().getHeight();
                        assertTrue("the text is no taller than the old fixed height, so this proves nothing",
                                text > Dim.dp36);
                        assertTrue("\"" + button.getText() + "\" is " + button.getHeight()
                                        + "px tall for " + text + "px of text",
                                button.getHeight() >= text + button.getCompoundPaddingTop()
                                        + button.getCompoundPaddingBottom());
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
    public void screenPercentagesFollowARotation() {
        assertEquals(Resources.getSystem().getDisplayMetrics().widthPixels, Dim.pctWidth(100));
        RuntimeEnvironment.setQualifiers("+land");
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        assertTrue("the rotation never reached the screen size, so this proves nothing",
                metrics.widthPixels > metrics.heightPixels);
        assertEquals(metrics.widthPixels, Dim.pctWidth(100));
        assertEquals(metrics.heightPixels, Dim.pctHeight(100));
        assertEquals(metrics.heightPixels, Dim.pctPortraitWidth(100));
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
        Utils.setContext(activity);
        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(activity, "Reset settings?",
                "This puts every setting back the way it came.", null, null, () -> { },
                cancel ? () -> { } : null, neutral, neutral == null ? null : () -> { }, true);
        dialog.first.show();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = dialog.first.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(
                        dialog.first.getWindow().getAttributes().width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        activity.getResources().getDisplayMetrics().heightPixels, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        return dialog;
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
}
