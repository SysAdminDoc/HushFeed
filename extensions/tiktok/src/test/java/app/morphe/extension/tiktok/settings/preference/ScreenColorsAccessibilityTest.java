/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for hushfeed (TikTok), 2026.
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.Switch;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.Locale;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Every colour pair the settings screen paints, measured in both looks: text at 4.5:1 on the page,
 * a card and a dialog, headings at 7:1, switch tracks, outlines and icons at 3:1, and the text on
 * the accent at 4.5:1. The switch is a rounded rectangle on the radius scale, not a pill.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w360dp-h800dp-xhdpi")
public class ScreenColorsAccessibilityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restoreDarkMode() {
        Utils.setIsDarkModeEnabled(true);
    }

    @Test public void everyRoleReadsOnEverySurfaceInBothLooks() {
        readable(ScreenColors.DARK);
        readable(ScreenColors.LIGHT);
    }

    @Test public void theBlackPageStaysTheDefaultAndItsCardsAndDialogsAreBlack() {
        assertEquals(Color.BLACK, ScreenColors.DARK.background);
        assertEquals(Color.BLACK, ScreenColors.DARK.card);
        assertEquals(Color.BLACK, ScreenColors.DARK.dialog);
        assertNotEquals(Color.BLACK, ScreenColors.LIGHT.card);
        for (ScreenColors look : looks()) {
            contrast("card edge", look.outline, look.card, 1.3);
            contrast("card edge on the page", look.outline, look.background, 1.3);
        }
    }

    @Test public void headingsReachSevenToOneOnThePageACardAndADialog() {
        for (ScreenColors look : looks()) {
            for (int surface : new int[]{look.background, look.card, look.dialog}) {
                contrast((look.light ? "light" : "dark") + " heading", look.heading, surface, 7.0);
            }
        }
    }

    /** The row surfaces the pages paint today keep the same targets as the card. */
    @Test public void theRowSurfacesInUseKeepTheSameTargets() {
        for (boolean dark : new boolean[]{true, false}) {
            ScreenColors look = dark ? ScreenColors.DARK : ScreenColors.LIGHT;
            for (int surface : new int[]{SettingsUi.surfaceOn(dark), SettingsUi.liftedSurfaceOn(dark)}) {
                String where = String.format(Locale.ROOT, "%s on #%06X", dark ? "dark" : "light",
                        surface & 0xFFFFFF);
                contrast("title " + where, look.title, surface, 4.5);
                contrast("summary " + where, look.summary, surface, 4.5);
                contrast("placeholder " + where, look.hint, surface, 4.5);
                contrast("heading " + where, look.heading, surface, 4.5);
                contrast("accent text " + where, look.accent, surface, 4.5);
                contrast("error " + where, look.error, surface, 4.5);
                contrast("off track " + where, look.switchOff, surface, 3.0);
            }
        }
    }

    @Test public void anEmptyFieldsPlaceholderStaysReadableAndQuieterThanSavedText() {
        for (ScreenColors look : looks()) {
            for (int surface : new int[]{look.background, look.card, look.dialog}) {
                contrast("placeholder", look.hint, surface, 4.5);
                assertTrue("a placeholder reads as loud as a saved summary",
                        ratio(look.hint, surface) < ratio(look.summary, surface));
            }
        }
    }

    @Test public void aGreyedRowsSummaryStillReadsAndItsTitleLooksOff() {
        for (ScreenColors look : looks()) {
            int reason = disabled(ScreenColors.readableWhenDisabled(look.summary));
            assertTrue("the reason line doesn't look unavailable", Color.alpha(reason) < 0xFF);
            contrast("disabled summary on the card", over(reason, look.card), look.card, 4.5);
            contrast("disabled summary on the page", over(reason, look.background), look.background, 4.5);
            int title = disabled(ScreenColors.dimmedWhenDisabled(look.title));
            assertEquals("a disabled title sits at 38%", 0x61, Color.alpha(title));
            assertEquals(look.title, ScreenColors.dimmedWhenDisabled(look.title).getDefaultColor());
        }
    }

    @Test public void focusAndFieldOutlinesReachThreeToOne() {
        for (ScreenColors look : looks()) {
            ColorStateList outline = look.interactionOutline(look.outline);
            for (int state : new int[]{android.R.attr.state_focused, android.R.attr.state_hovered,
                    android.R.attr.state_selected, android.R.attr.state_activated}) {
                int focused = outline.getColorForState(new int[]{android.R.attr.state_enabled, state}, 0);
                assertEquals("a native list state lost its outline", look.heading, focused);
                for (int surface : new int[]{look.background, look.card, look.dialog}) {
                    contrast("focused outline", focused, surface, 3.0);
                }
            }
            contrast("a field's outline at rest", look.switchOff, look.dialog, 3.0);
            int press = ScreenColors.interactionRipple(look.heading).getDefaultColor();
            assertEquals("the press is the heading at 20%", 0x33, Color.alpha(press));
            assertEquals(look.heading & 0xFFFFFF, press & 0xFFFFFF);
            int picked = look.choiceFill(look.card)
                    .getColorForState(new int[]{android.R.attr.state_checked}, 0);
            assertEquals("a picked choice is the accent at 13%", 0x22, Color.alpha(picked));
        }
    }

    @Test public void theSwitchIsARoundedRectangleOnTheScaleInEveryState() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            for (boolean dark : new boolean[]{true, false}) {
                Utils.setIsDarkModeEnabled(dark);
                ScreenColors look = dark ? ScreenColors.DARK : ScreenColors.LIGHT;
                Switch control = new Switch(activity);
                SettingsUi.styleSwitch(control);
                assertEquals(dp(activity, 40), control.getSwitchMinWidth());
                int[][] states = {
                        {android.R.attr.state_enabled, android.R.attr.state_checked},
                        {android.R.attr.state_enabled},
                        {-android.R.attr.state_enabled, android.R.attr.state_checked},
                        {-android.R.attr.state_enabled}};
                int[] tracks = {look.accent, look.switchOff, ScreenColors.half(look.accent),
                        ScreenColors.half(look.switchOff)};
                int[] thumbs = {look.onAccent, look.offThumb(), ScreenColors.half(look.onAccent),
                        ScreenColors.half(look.offThumb())};
                for (int i = 0; i < states.length; i++) {
                    GradientDrawable track = shape(control.getTrackDrawable(), states[i]);
                    GradientDrawable thumb = shape(control.getThumbDrawable(), states[i]);
                    assertEquals("the track became a capsule", GradientDrawable.RECTANGLE, track.getShape());
                    assertEquals(GradientDrawable.RECTANGLE, thumb.getShape());
                    assertEquals(dp(activity, 6), track.getCornerRadius(), 0.01f);
                    assertEquals(dp(activity, 4), thumb.getCornerRadius(), 0.01f);
                    assertEquals(dp(activity, 40), track.getIntrinsicWidth());
                    assertEquals(dp(activity, 24), track.getIntrinsicHeight());
                    assertEquals(dp(activity, 20), thumb.getIntrinsicWidth());
                    assertEquals(dp(activity, 20), thumb.getIntrinsicHeight());
                    assertEquals("track colour in state " + i, tracks[i], track.getColor().getDefaultColor());
                    assertEquals("thumb colour in state " + i, thumbs[i], thumb.getColor().getDefaultColor());
                }
            }
        }
    }

    /**
     * The knob sits 2 dp inside the track on every side in both positions. Switch draws its thumb
     * from the track's top to its bottom, so the bare shapes sat flush with the track's end, and
     * the light look's white knob on the white card (and the dark on-knob, black on the black
     * card) read as a cut-off block.
     */
    @Test public void theKnobSitsInsideTheTrackInBothPositions() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            int gap = dp(activity, 2);
            int knob = dp(activity, 20);
            for (boolean dark : new boolean[]{true, false}) {
                Utils.setIsDarkModeEnabled(dark);
                ScreenColors look = dark ? ScreenColors.DARK : ScreenColors.LIGHT;
                for (boolean checked : new boolean[]{false, true}) {
                    Switch control = new Switch(activity);
                    SettingsUi.styleSwitch(control);
                    control.setChecked(checked);
                    control.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    control.layout(0, 0, control.getMeasuredWidth(), control.getMeasuredHeight());
                    Bitmap bitmap = Bitmap.createBitmap(control.getWidth(), control.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    control.draw(new Canvas(bitmap));
                    Rect track = control.getTrackDrawable().getBounds();
                    String where = (dark ? "dark" : "light") + (checked ? " on" : " off");
                    assertEquals(where + " track width", dp(activity, 40), track.width());
                    assertEquals(where + " track height", dp(activity, 24), track.height());
                    int left = checked ? track.right - gap - knob : track.left + gap;
                    int middleX = left + knob / 2;
                    int middleY = track.top + gap + knob / 2;
                    int trackColour = checked ? look.accent : look.switchOff;
                    int knobColour = checked ? look.onAccent : look.offThumb();
                    assertEquals(where + " knob", knobColour, bitmap.getPixel(middleX, middleY));
                    assertEquals(where + " knob's far edge", knobColour,
                            bitmap.getPixel(left + knob - 1, middleY));
                    assertEquals(where + " gap before the knob", trackColour,
                            bitmap.getPixel(left - gap / 2, middleY));
                    assertEquals(where + " gap after the knob", trackColour,
                            bitmap.getPixel(left + knob + gap / 2, middleY));
                    assertEquals(where + " gap above the knob", trackColour,
                            bitmap.getPixel(middleX, track.top + gap / 2));
                    assertEquals(where + " gap below the knob", trackColour,
                            bitmap.getPixel(middleX, track.bottom - gap / 2));
                }
            }
        }
    }

    private static ScreenColors[] looks() {
        return new ScreenColors[]{ScreenColors.DARK, ScreenColors.LIGHT};
    }

    private static void readable(ScreenColors look) {
        for (int surface : new int[]{look.background, look.card, look.dialog}) {
            contrast("title", look.title, surface, 4.5);
            contrast("summary", look.summary, surface, 4.5);
            contrast("heading and secondary action", look.heading, surface, 4.5);
            contrast("validation error", look.error, surface, 4.5);
            contrast("accent text and on track", look.accent, surface, 4.5);
            contrast("off track", look.switchOff, surface, 3.0);
        }
        contrast("icon", look.heading, look.iconSurface, 3.0);
        contrast("filled action text", look.onAccent, look.accent, 4.5);
        contrast("thumb on the off track", look.offThumb(), look.switchOff, 3.0);
    }

    private static GradientDrawable shape(Drawable drawable, int[] state) {
        drawable.setState(state);
        Drawable current = drawable.getCurrent();
        // The track and thumb each sit in one layer that places them; the shape is that layer.
        if (current instanceof LayerDrawable) current = ((LayerDrawable) current).getDrawable(0).getCurrent();
        assertTrue("a switch part isn't a shape: " + current, current instanceof GradientDrawable);
        return (GradientDrawable) current;
    }

    private static int disabled(ColorStateList colors) {
        return colors.getColorForState(new int[]{-android.R.attr.state_enabled}, 0);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** [foreground] drawn over [background], the colour the screen actually shows. */
    private static int over(int foreground, int background) {
        float a = Color.alpha(foreground) / 255f;
        return Color.rgb(Math.round(Color.red(foreground) * a + Color.red(background) * (1 - a)),
                Math.round(Color.green(foreground) * a + Color.green(background) * (1 - a)),
                Math.round(Color.blue(foreground) * a + Color.blue(background) * (1 - a)));
    }

    private static void contrast(String label, int foreground, int background, double minimum) {
        double ratio = ratio(foreground, background);
        assertTrue(String.format(Locale.ROOT, "%s contrast %.2f:1 is below %.1f:1", label, ratio, minimum),
                ratio >= minimum);
    }

    private static double ratio(int foreground, int background) {
        double a = luminance(foreground), b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color))
                + 0.0722 * linear(Color.blue(color));
    }

    private static double linear(int component) {
        double channel = component / 255.0;
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
