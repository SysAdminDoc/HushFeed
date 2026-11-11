/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Every Hushfeed title in TikTok's cyan and red: every stop reads on the page and dialog surfaces
 * of its theme, a sheen sweeps as the title opens and once each period, the colours drift without
 * a jump, a worn title is drawn in more than one colour and in different ones as time passes, the
 * page bar and dialogs wear it, and it keeps moving on screen with Android's animations off, as
 * the S22 runs.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class ShimmerTitleTest {
    @Test
    public void everyGradientStopReadsOnItsThemesSurfaces() {
        int[] dark = {SettingsUi.DARK_BACKGROUND, SettingsUi.DARK_SURFACE, SettingsUi.DARK_SURFACE_LIFTED};
        int[] light = {SettingsUi.LIGHT_BACKGROUND, SettingsUi.LIGHT_SURFACE, SettingsUi.LIGHT_SURFACE_LIFTED};
        for (int color : ShimmerTitle.COLORS_ON_DARK) {
            for (int ground : dark) {
                assertTrue(Integer.toHexString(color), contrast(color, ground) >= 7.0);
            }
        }
        for (int color : ShimmerTitle.COLORS_ON_LIGHT) {
            for (int ground : light) {
                assertTrue(Integer.toHexString(color), contrast(color, ground) >= 7.0);
            }
        }
    }

    /** The sweep paints white over the letters, so on the light theme it lightens them toward the page. */
    @Test
    public void everyLightStopStillReadsUnderTheSheenAtItsBrightest() {
        int[] light = {SettingsUi.LIGHT_BACKGROUND, SettingsUi.LIGHT_SURFACE, SettingsUi.LIGHT_SURFACE_LIFTED};
        int peak = ShimmerTitle.SHEEN_ON_LIGHT[0];
        for (int stop : ShimmerTitle.SHEEN_ON_LIGHT) {
            if (Color.alpha(stop) > Color.alpha(peak)) peak = stop;
        }
        for (int color : ShimmerTitle.COLORS_ON_LIGHT) {
            int lit = over(peak, color);
            for (int ground : light) {
                double ratio = contrast(lit, ground);
                assertTrue(Integer.toHexString(color) + " under the sheen reads " + ratio + ":1 on "
                        + Integer.toHexString(ground), ratio >= 4.5);
            }
        }
    }

    @Test
    public void aSweepRunsAsTheTitleOpensAndOnceEachPeriod() {
        assertEquals(0f, ShimmerTitle.sweep(0L), 0f);
        assertEquals(0.5f, ShimmerTitle.sweep(ShimmerTitle.SWEEP_MS / 2), 0.001f);
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.SWEEP_MS), 0f);
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.PERIOD_MS - 1), 0f);
        assertEquals(0f, ShimmerTitle.sweep(ShimmerTitle.PERIOD_MS), 0f);
        assertEquals(-1f, ShimmerTitle.sweep(-5L), 0f);
    }

    @Test
    public void theColoursDriftRoundAndStartAgainWhereTheyBegan() {
        assertEquals(0f, ShimmerTitle.drift(0L), 0f);
        assertEquals(0f, ShimmerTitle.drift(-5L), 0f);
        assertEquals(0.5f, ShimmerTitle.drift(ShimmerTitle.DRIFT_MS / 2), 0.001f);
        assertEquals(0f, ShimmerTitle.drift(ShimmerTitle.DRIFT_MS), 0f);
        float before = 0f;
        for (int step = 1; step < 20; step++) {
            float now = ShimmerTitle.drift(step * ShimmerTitle.DRIFT_MS / 20);
            assertTrue(now > before);
            before = now;
        }
    }

    @Test
    public void aWornTitleIsDrawnInTheGradientAndChangesAsItDrifts() {
        TextView title = title("Search settings", SettingsUi.DARK_TEXT_PRIMARY);
        ShimmerTitle.wear(title);
        assertTrue(ShimmerTitle.wears(title));
        assertEquals(Color.WHITE, title.getCurrentTextColor());
        // A whole drift round on, between sweeps: cyan at the start, red at the end.
        assertEquals(-1f, ShimmerTitle.sweep(ShimmerTitle.DRIFT_MS), 0f);
        Bitmap still = draw(title, ShimmerTitle.DRIFT_MS);
        assertTrue("red letters", anyPixel(still, true));
        assertTrue("cyan letters", anyPixel(still, false));
        Bitmap later = draw(title, ShimmerTitle.DRIFT_MS + ShimmerTitle.DRIFT_MS / 4);
        assertFalse("the colours moved", still.sameAs(later));
        Bitmap sweeping = draw(title, ShimmerTitle.SWEEP_MS / 2);
        assertTrue("the sheen pass still shows the gradient", anyPixel(sweeping, true));
    }

    @Test
    public void aTitleInDarkInkTakesTheLightThemesColours() {
        TextView title = title("Hushfeed", SettingsUi.LIGHT_TEXT_PRIMARY);
        ShimmerTitle.wear(title);
        Bitmap still = draw(title, ShimmerTitle.DRIFT_MS);
        assertTrue("red letters", anyPixel(still, true));
        assertTrue("cyan letters", anyPixel(still, false));
        for (int x = 0; x < still.getWidth(); x++) {
            for (int y = 0; y < still.getHeight(); y++) {
                int pixel = still.getPixel(x, y);
                if (Color.alpha(pixel) < 250) continue;
                assertTrue("a light letter on the light page", Color.luminance(pixel) < 0.2f);
            }
        }
    }

    @Test
    public void thePageBarAndDialogsWearIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout header = SettingsHeaderPreference.createHeader(activity, "Hushfeed", () -> { });
        assertTrue(ShimmerTitle.wears(header.findViewWithTag("hushfeed_page_title")));
        assertTrue(ShimmerTitle.wears(SettingsUi.dialogTitle(activity, "Download folder")));

        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Translation preview")
                .setMessage("Translating").setPositiveButton("Close", null).create();
        dialog.show();
        SettingsUi.styleStandardAlertDialog(dialog);
        int id = activity.getResources().getIdentifier("alertTitle", "id", "android");
        TextView alertTitle = dialog.getWindow().getDecorView().findViewById(id);
        assertTrue(ShimmerTitle.wears(alertTitle));
        // The dialog's text pass runs again as its list scrolls and leaves the title in the gradient.
        SettingsUi.styleStandardAlertDialog(dialog);
        assertEquals(Color.WHITE, alertTitle.getCurrentTextColor());
        dialog.dismiss();
    }

    @Test
    public void aTitleOnScreenKeepsMovingWithAnimationsOff() throws Exception {
        Method scale = ValueAnimator.class.getMethod("setDurationScale", float.class);
        scale.invoke(null, 0f);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            assertFalse(ValueAnimator.areAnimatorsEnabled());
            TextView title = title("Hushfeed", SettingsUi.DARK_TEXT_PRIMARY);
            FrameLayout root = new FrameLayout(controller.get());
            root.addView(title);
            controller.get().setContentView(root);
            ShimmerTitle.wear(title);
            assertNotNull("the gradient is on as the title opens", shift(title, 100));
            // Past the opening sweep the paint holds the drifting gradient alone.
            Float first = shift(title, ShimmerTitle.SWEEP_MS + 200);
            Float later = shift(title, 500);
            assertNotNull(first);
            assertNotNull(later);
            assertTrue("the colours drifted with animations off: " + first + " then " + later, later > first);
        } finally {
            scale.invoke(null, 1f);
        }
    }

    /** Runs the main looper [ms] on and gives how far the title's shader is moved along x, if any. */
    private static Float shift(TextView title, long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        Shader shader = title.getPaint().getShader();
        if (shader == null) return null;
        Matrix local = new Matrix();
        shader.getLocalMatrix(local);
        float[] values = new float[9];
        local.getValues(values);
        return values[Matrix.MTRANS_X];
    }

    /** A worn title doesn't keep itself, or the screen it belongs to, in memory. */
    @Test
    public void aWornTitleCanBeLetGo() {
        WeakReference<TextView> gone = wornAndDropped();
        for (int i = 0; i < 20 && gone.get() != null; i++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("the gradient kept its title alive", gone.get());
    }

    private static WeakReference<TextView> wornAndDropped() {
        TextView title = title("Hushfeed", Color.WHITE);
        ShimmerTitle.wear(title);
        assertTrue(ShimmerTitle.wears(title));
        return new WeakReference<>(title);
    }

    private static TextView title(String text, int color) {
        TextView title = new TextView(RuntimeEnvironment.getApplication());
        // As in the bar: a laid-out TextView reads its layout params when its text changes.
        title.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(color);
        title.setText(text);
        return title;
    }

    private static Bitmap draw(TextView title, long sinceStart) {
        title.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        title.layout(0, 0, title.getMeasuredWidth(), title.getMeasuredHeight());
        ShimmerTitle.frameFor(title, sinceStart);
        Bitmap bitmap = Bitmap.createBitmap(title.getWidth(), title.getHeight(), Bitmap.Config.ARGB_8888);
        title.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** Whether a solid letter pixel is clearly red (red over blue) or, for not [red], clearly cyan. */
    private static boolean anyPixel(Bitmap bitmap, boolean red) {
        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 250) continue;
                int lean = Color.red(pixel) - Color.blue(pixel);
                if (red ? lean > 60 : lean < -60) return true;
            }
        }
        return false;
    }

    /** {@code top} drawn over the opaque {@code bottom}, as SRC_OVER does. */
    private static int over(int top, int bottom) {
        float a = Color.alpha(top) / 255f;
        return Color.rgb(
                Math.round(Color.red(top) * a + Color.red(bottom) * (1 - a)),
                Math.round(Color.green(top) * a + Color.green(bottom) * (1 - a)),
                Math.round(Color.blue(top) * a + Color.blue(bottom) * (1 - a)));
    }

    private static double contrast(int one, int other) {
        double a = Color.luminance(one) + 0.05, b = Color.luminance(other) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }
}
