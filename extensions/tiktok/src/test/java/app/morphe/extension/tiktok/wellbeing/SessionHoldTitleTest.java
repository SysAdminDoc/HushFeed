/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.ShimmerTitle;

import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/**
 * The hold's title is a Hushfeed title like the settings page's, the dialogs' and the App lock
 * cover's, so it wears the same moving cyan and red. The panel is the same near-black scrim in
 * either settings theme, so the title takes the colours made for a dark ground both times, and
 * each of them still reads at 7:1 where the scrim is thinnest, over a white video frame.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SessionHoldTitleTest {
    private final AtomicLong now = new AtomicLong();

    public static class HostActivity extends Activity {
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 12, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        SessionLockOverlay.sync();
    }

    @After public void tearDown() throws Exception {
        // As in SessionLockOverlayTest: the sandbox outlives this class, so the theme override,
        // the countdown and the budget's day all go back before the next class runs.
        ReflectionHelpers.setStaticField(Utils.class, "isDarkModeEnabled", null);
        Utils.setActivity(null);
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Runnable tick = ReflectionHelpers.getStaticField(SessionLockOverlay.class, "TICK");
        android.os.Handler handler = ReflectionHelpers.getStaticField(SessionLockOverlay.class, "MAIN");
        handler.removeCallbacks(tick);
        tick.run();
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
    }

    @Test public void theHoldTitleWearsTheGradientForADarkGroundInEitherTheme() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        for (boolean darkSettings : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(darkSettings);
            try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setActivity(activity);
                SessionLockOverlay.sync();
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

                TextView title = holdTitle(activity);
                assertEquals(SessionBudgetNotice.spentMessage(), title.getText().toString());
                assertTrue("the hold title is the one title without the gradient, dark settings "
                        + darkSettings, ShimmerTitle.wears(title));
                assertEquals("the gradient's base colour", Color.WHITE, title.getCurrentTextColor());

                // A whole drift round on, between sweeps: the cyan, white and red run across it.
                long round = ReflectionHelpers.<Long>getStaticField(ShimmerTitle.class, "DRIFT_MS");
                Bitmap drawn = draw(title, round);
                assertTrue("red letters", anyPixel(drawn, true));
                assertTrue("cyan letters", anyPixel(drawn, false));
                for (int x = 0; x < drawn.getWidth(); x++) {
                    for (int y = 0; y < drawn.getHeight(); y++) {
                        int pixel = drawn.getPixel(x, y);
                        if (Color.alpha(pixel) < 250) continue;
                        assertTrue("a dark letter on the black panel, dark settings " + darkSettings
                                + ": " + Integer.toHexString(pixel), Color.luminance(pixel) > 0.3f);
                    }
                }
            } finally {
                Utils.setActivity(null);
            }
        }
    }

    @Test public void theTitleKeepsTheGradientWhenTheHoldSaysSomethingElse() {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();
            TextView title = holdTitle(activity);
            assertTrue(ShimmerTitle.wears(title));

            // Each sync writes the title in place rather than building a new one, so a hold that
            // changes what it says keeps the same worn view.
            SessionLockOverlay.sync();
            assertSame("the panel built a second title", title, holdTitle(activity));
            assertTrue("the title lost the gradient on the next sync", ShimmerTitle.wears(title));
        } finally {
            Utils.setActivity(null);
        }
    }

    @Test public void everyStopReadsWhereTheScrimIsThinnest() {
        int[] stops = ReflectionHelpers.getStaticField(ShimmerTitle.class, "COLORS_ON_DARK");
        // The scrim is a little see-through, so the brightest the panel gets is the scrim laid
        // over a white frame of the video under it.
        int ground = over(SettingsUi.OVERLAY_SCRIM_SOLID, Color.WHITE);
        for (int stop : stops) {
            double ratio = contrast(stop, ground);
            assertTrue(Integer.toHexString(stop) + " reads " + ratio + ":1 on the hold panel",
                    ratio >= 7.0);
        }
    }

    private static TextView holdTitle(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
        assertNotNull(panel);
        return (TextView) panel.getChildAt(0);
    }

    private static Bitmap draw(TextView title, long sinceStart) throws Exception {
        Method frameFor = ShimmerTitle.class.getDeclaredMethod("frameFor", TextView.class, long.class);
        frameFor.setAccessible(true);
        frameFor.invoke(null, title, sinceStart);
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

    private static int over(int top, int bottom) {
        float a = Color.alpha(top) / 255f;
        return Color.rgb(
                Math.round(Color.red(top) * a + Color.red(bottom) * (1 - a)),
                Math.round(Color.green(top) * a + Color.green(bottom) * (1 - a)),
                Math.round(Color.blue(top) * a + Color.blue(bottom) * (1 - a)));
    }

    private static double contrast(int a, int b) {
        double la = Color.luminance(a);
        double lb = Color.luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month, day, hour, minute);
        return calendar.getTimeInMillis();
    }
}
