package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The settings dialogs: panel, width, title, message, choices, buttons, fields and keyboard. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsDialogsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class DialogActivity extends Activity {
        @Override
        protected void onCreate(android.os.Bundle state) {
            boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
            Utils.setIsDarkModeEnabled(dark);
            super.onCreate(state);
        }
    }

    @After
    public void restoreDarkMode() {
        Utils.setIsDarkModeEnabled(true);
    }

    @Test
    public void darkPanelIsTheDialogColourInsideAHairline() {
        assertPanel();
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightPanelIsTheDialogColourInsideAHairline() {
        assertPanel();
    }

    private void assertPanel() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = confirm(activity);
            ScreenColors colors = ScreenColors.current();

            Drawable panel = dialog.getWindow().getDecorView().getBackground();
            assertTrue(panel instanceof GradientDrawable);
            GradientDrawable shape = (GradientDrawable) panel;
            assertEquals(SettingsUi.liftedSurface(), shape.getColor().getDefaultColor());
            assertEquals(SettingsUi.dp(activity, 12), shape.getCornerRadius(), 0.01f);

            Bitmap drawn = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
            Drawable copy = shape.getConstantState().newDrawable().mutate();
            copy.setBounds(0, 0, 120, 80);
            copy.draw(new Canvas(drawn));
            assertNear("the panel's edge", colors.outline, drawn.getPixel(0, 40));
            assertNear("the panel's middle", SettingsUi.liftedSurface(), drawn.getPixel(60, 40));
        }
    }

    @Test
    @Config(qualifiers = "w300dp-h640dp-night-mdpi")
    public void aNarrowScreenKeepsSixteenDpEitherSideOfThePanel() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = confirm(activity);
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            assertEquals(screen - SettingsUi.dp(activity, 32), dialog.getWindow().getAttributes().width);
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h960dp-night-mdpi")
    public void aWideScreenStopsThePanelAt560Dp() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = confirm(activity);
            assertEquals(SettingsUi.dp(activity, 560), dialog.getWindow().getAttributes().width);
        }
    }

    @Test
    public void theTitleWrapsAtTwentySpInTheGradient() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = confirm(activity);
            int id = activity.getResources().getIdentifier("alertTitle", "id", "android");
            TextView title = dialog.getWindow().getDecorView().findViewById(id);
            assertNotNull(title);
            assertEquals(20f, title.getTextSize(), 0.01f);
            assertEquals(Integer.MAX_VALUE, title.getMaxLines());
            assertNull(title.getEllipsize());
            assertTrue(ShimmerTitle.wears(title));
        }
    }

    @Test
    public void theMessageIsTheSummaryColourAtFourteenSpWithRoomBetweenLines() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = confirm(activity);
            TextView message = dialog.getWindow().getDecorView().findViewById(android.R.id.message);
            assertNotNull(message);
            assertEquals(ScreenColors.current().summary, message.getCurrentTextColor());
            assertEquals(14f, message.getTextSize(), 0.01f);
            assertEquals(SettingsUi.dp(activity, 2), message.getLineSpacingExtra(), 0.01f);
        }
    }

    @Test
    public void theKeyboardResizesTheDialogAndKeepsItsStateRequest() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Rename")
                    .setView(new EditText(activity))
                    .setPositiveButton("Save", null)
                    .create();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            idle();

            int mode = dialog.getWindow().getAttributes().softInputMode;
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
        }
    }

    @Test
    public void darkButtonsAreOutlinedTouchTargets() {
        assertButtons();
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightButtonsAreOutlinedTouchTargets() {
        assertButtons();
    }

    private void assertButtons() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = confirm(activity);
            ScreenColors colors = ScreenColors.current();
            Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);

            for (Button button : new Button[]{save, cancel}) {
                assertTrue(button.getHeight() >= SettingsUi.dp(activity, 48));
                assertTrue(button.getWidth() >= SettingsUi.dp(activity, 64));
                assertEquals(16f, button.getTextSize(), 0.01f);
                assertFalse(button.isAllCaps());
                assertEquals(Integer.MAX_VALUE, button.getMaxLines());
                assertTrue(button.getBackground() instanceof RippleDrawable);
                RippleDrawable ripple = (RippleDrawable) button.getBackground();
                GradientDrawable surface = (GradientDrawable) ripple.getDrawable(0);
                assertEquals(SettingsUi.dp(activity, 12), surface.getCornerRadius(), 0.01f);
            }

            // The shape sits 4dp in from the button's sides, so the outline starts there and is
            // 2dp wide: the accent's on the action to take and the hairline's on Cancel. A
            // window out of touch mode hands one of them focus, which outlines it in the heading.
            Bitmap saveDrawn = draw(save);
            int middle = save.getHeight() / 2;
            int saveOutline = save.isFocused() ? colors.heading : colors.accent;
            assertNear("Save's outline", saveOutline, saveDrawn.getPixel(SettingsUi.dp(activity, 4), middle));
            assertNear("Save's outline", saveOutline, saveDrawn.getPixel(SettingsUi.dp(activity, 5), middle));
            assertNear("the gap beside Save", Color.BLACK, saveDrawn.getPixel(SettingsUi.dp(activity, 2), middle));
            Bitmap cancelDrawn = draw(cancel);
            assertNear("Cancel's outline", cancel.isFocused() ? colors.heading : colors.outline,
                    cancelDrawn.getPixel(SettingsUi.dp(activity, 4), cancel.getHeight() / 2));
        }
    }

    @Test
    public void darkChoicesKeepTheirCheckAndFillThePickedOne() {
        assertChoices();
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightChoicesKeepTheirCheckAndFillThePickedOne() {
        assertChoices();
    }

    private void assertChoices() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            int[] picked = {-1};
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Video quality")
                    .setSingleChoiceItems(new String[]{"Auto", "High", "Data saver"}, 1,
                            (ignored, which) -> picked[0] = which)
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            idle();

            ListView list = dialog.getListView();
            assertTrue(list.getAdapter() instanceof DialogChoices);
            assertEquals(1, list.getCheckedItemPosition());
            CheckedTextView auto = row(list, 0);
            CheckedTextView high = row(list, 1);
            assertNotNull(auto);
            assertNotNull(high);
            assertTrue(high.isChecked());
            assertFalse(auto.isChecked());
            assertTrue(high.getHeight() >= SettingsUi.dp(activity, 48));

            ScreenColors colors = ScreenColors.current();
            int wash = (colors.accent & 0x00FFFFFF) | 0x22000000;
            int x = high.getWidth() - SettingsUi.dp(activity, 30);
            int y = SettingsUi.dp(activity, 3);
            assertNear("the picked row", over(wash, Color.BLACK), draw(high).getPixel(x, y));
            assertNear("a row not picked", Color.BLACK, draw(auto).getPixel(x, y));

            list.performItemClick(row(list, 2), 2, list.getAdapter().getItemId(2));
            idle();
            assertEquals(2, picked[0]);
            assertTrue(list.isItemChecked(2));
        }
    }

    @Test
    public void aChecklistKeepsEveryCheckThroughTheRestyle() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Hide")
                    .setMultiChoiceItems(new String[]{"Ads", "Lives", "Shop"},
                            new boolean[]{true, false, true}, (ignored, which, checked) -> {})
                    .setPositiveButton("Save", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            idle();

            ListView list = dialog.getListView();
            assertTrue(list.getAdapter() instanceof DialogChoices);
            assertTrue(list.isItemChecked(0));
            assertFalse(list.isItemChecked(1));
            assertTrue(list.isItemChecked(2));
        }
    }

    @Test
    public void aTickBelowTheFoldIsInTheListBeforeItsRowIsDrawn() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            String[] labels = new String[24];
            boolean[] ticks = new boolean[24];
            for (int i = 0; i < labels.length; i++) labels[i] = "Row " + i;
            ticks[22] = true;
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Hide")
                    .setMultiChoiceItems(labels, ticks, (ignored, which, checked) -> {})
                    .setPositiveButton("Save", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            idle();

            ListView list = dialog.getListView();
            assertTrue("row 22 is on screen, so this proves nothing", list.getLastVisiblePosition() < 22);
            assertTrue(list.isItemChecked(22));
            assertFalse(list.isItemChecked(21));
        }
    }

    @Test
    public void darkCheckboxTickIsTheAccentsOwnTextColour() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Hide")
                    .setMultiChoiceItems(new String[]{"Ads", "Lives"},
                            new boolean[]{true, false}, (ignored, which, checked) -> {})
                    .setPositiveButton("Save", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            idle();

            CheckedTextView ads = row(dialog.getListView(), 0);
            assertNotNull(ads);
            Drawable mark = ads.getCompoundDrawablesRelative()[0];
            assertNotNull(mark);
            int size = mark.getIntrinsicWidth();
            Bitmap drawn = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Drawable copy = mark.mutate();
            copy.setState(new int[]{android.R.attr.state_enabled, android.R.attr.state_checked});
            android.graphics.Rect kept = new android.graphics.Rect(copy.getBounds());
            copy.setBounds(0, 0, size, size);
            copy.draw(new Canvas(drawn));
            copy.setBounds(kept);

            // White on the dark red read 3.7:1. The tick is black now, so nothing in the box
            // should come out near white, and some of it should come out near black.
            boolean anyDark = false;
            for (int px = 0; px < size; px++) {
                for (int py = 0; py < size; py++) {
                    int pixel = drawn.getPixel(px, py);
                    if (Color.alpha(pixel) < 200) continue;
                    assertFalse("a white tick at " + px + "," + py,
                            Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200);
                    if (Color.red(pixel) < 60 && Color.green(pixel) < 60 && Color.blue(pixel) < 60) {
                        anyDark = true;
                    }
                }
            }
            assertTrue(anyDark);
        }
    }

    @Test
    public void darkFieldIsFramedAndTheOutlineFollowsFocus() {
        assertField();
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightFieldIsFramedAndTheOutlineFollowsFocus() {
        assertField();
    }

    private void assertField() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            EditText field = new EditText(activity);
            EditText other = new EditText(activity);
            android.widget.LinearLayout fields = new android.widget.LinearLayout(activity);
            fields.setOrientation(android.widget.LinearLayout.VERTICAL);
            fields.addView(other);
            fields.addView(field);
            SettingsUi.styleEditText(field);
            SettingsUi.styleEditText(other);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Rename")
                    .setView(fields)
                    .setPositiveButton("Save", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            other.requestFocus();
            idle();

            ScreenColors colors = ScreenColors.current();
            assertTrue(field.getBackground() instanceof SettingsUi.FieldFrame);
            SettingsUi.FieldFrame frame = (SettingsUi.FieldFrame) field.getBackground();
            assertEquals(colors.dialog, frame.fillColor());
            assertEquals(SettingsUi.dp(activity, 12), frame.radius(), 0.01f);
            assertEquals(SettingsUi.dp(activity, 2), frame.strokeWidth(), 0.01f);
            assertEquals(SettingsUi.border(), frame.strokeColor());
            assertEquals(SettingsUi.dp(activity, 12), field.getPaddingStart());
            assertEquals(SettingsUi.dp(activity, 10), field.getPaddingTop());
            assertEquals(16f, field.getTextSize(), 0.01f);
            assertTrue(field.getHeight() >= SettingsUi.dp(activity, 48));

            // A tinted GradientDrawable gives its fill to the tint too: the frame keeps the
            // dialog's colour inside and puts the tint on the outline alone.
            Bitmap drawn = draw(field);
            int middle = field.getHeight() / 2;
            assertNear("the field's outline", SettingsUi.border(), drawn.getPixel(0, middle));
            assertNear("the field's inside", colors.dialog,
                    drawn.getPixel(field.getWidth() - SettingsUi.dp(activity, 6), middle));

            field.requestFocus();
            idle();
            assertTrue(field.isFocused());
            assertEquals(SettingsUi.accent(), frame.strokeColor());
            assertNear("the focused outline", SettingsUi.accent(), draw(field).getPixel(0, middle));

            field.setEnabled(false);
            idle();
            assertEquals(SettingsUi.border(), frame.strokeColor());
        }
    }

    @Test
    public void aTallFieldKeepsTheHeightItAskedFor() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            EditText notes = new EditText(activity);
            notes.setMinimumHeight(SettingsUi.dp(activity, 96));
            SettingsUi.styleEditText(notes);
            assertEquals(SettingsUi.dp(activity, 96), notes.getMinimumHeight());

            EditText plain = new EditText(activity);
            plain.setMinimumHeight(0);
            SettingsUi.styleEditText(plain);
            assertEquals(SettingsUi.dp(activity, 48), plain.getMinimumHeight());
        }
    }

    @Test
    public void theSearchFieldTakesTheColoursButNotAFrame() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            EditText search = new EditText(activity);
            search.setPadding(0, 0, 0, 0);
            SettingsUi.tintField(search);
            assertFalse(search.getBackground() instanceof SettingsUi.FieldFrame);
            assertEquals(0, search.getPaddingStart());
            assertEquals(ScreenColors.current().hint, search.getCurrentHintTextColor());
            assertEquals(SettingsUi.textPrimary(), search.getCurrentTextColor());
        }
    }

    private static AlertDialog confirm(Activity activity) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Clear your local watch history and every saved position")
                .setMessage("This can't be undone.")
                .setPositiveButton("Clear", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        SettingsUi.styleStandardAlertDialog(dialog);
        idle();
        return dialog;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static CheckedTextView row(ListView list, int position) {
        return find(list.getChildAt(position - list.getFirstVisiblePosition()));
    }

    private static CheckedTextView find(View view) {
        if (view instanceof CheckedTextView) return (CheckedTextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckedTextView found = find(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** [view] drawn over black, the way the black page shows through a clear part of it. */
    private static Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        view.draw(canvas);
        return bitmap;
    }

    private static int over(int top, int bottom) {
        float alpha = Color.alpha(top) / 255f;
        return Color.rgb(
                Math.round(Color.red(top) * alpha + Color.red(bottom) * (1 - alpha)),
                Math.round(Color.green(top) * alpha + Color.green(bottom) * (1 - alpha)),
                Math.round(Color.blue(top) * alpha + Color.blue(bottom) * (1 - alpha)));
    }

    private static void assertNear(String what, int expected, int actual) {
        int distance = Math.max(Math.abs(Color.red(expected) - Color.red(actual)),
                Math.max(Math.abs(Color.green(expected) - Color.green(actual)),
                        Math.abs(Color.blue(expected) - Color.blue(actual))));
        assertTrue(what + ": expected " + Integer.toHexString(expected) + ", got "
                + Integer.toHexString(actual), distance <= 3);
    }
}
