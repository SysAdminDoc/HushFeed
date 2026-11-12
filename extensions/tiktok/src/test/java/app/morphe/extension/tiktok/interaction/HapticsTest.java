/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.time.Duration;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The stand-ins Turn off haptics puts in place of TikTok's own haptic and vibrator calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HapticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int NO_FLAGS = -1;

    /** A view that records the haptics it was asked for instead of playing them. */
    private static final class CountingView extends View {
        int haptics;
        int lastConstant = -1;
        int lastFlags = NO_FLAGS;

        CountingView(Context context) {
            super(context);
        }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            haptics++;
            lastConstant = feedbackConstant;
            lastFlags = NO_FLAGS;
            return true;
        }

        @Override public boolean performHapticFeedback(int feedbackConstant, int flags) {
            haptics++;
            lastConstant = feedbackConstant;
            lastFlags = flags;
            return true;
        }
    }

    private CountingView view;
    private Vibrator vibrator;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        view = new CountingView(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        // The switch starts off; these tests are about what it does once the reader turns it on.
        Settings.TURN_OFF_HAPTICS.save(true);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.TURN_OFF_HAPTICS.resetToDefault();
        Haptics.resetForTests();
    }

    private static VibrationEffect shortEffect(long millis) {
        return VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    private static AudioAttributes touchAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build();
    }

    @Test public void theSwitchStartsOffAndOnTikToksHapticsDontPlay() {
        assertEquals("the patch is in the default selection, so its switch starts off",
                Boolean.FALSE, Settings.TURN_OFF_HAPTICS.defaultValue);
        assertTrue(Settings.TURN_OFF_HAPTICS.get());

        assertFalse("the view call answers as a view with haptics off does",
                Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertFalse(Haptics.performHapticFeedback(view, HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
        assertEquals("the view was never asked", 0, view.haptics);

        Haptics.vibrate(vibrator, 30L);
        assertFalse("a timed vibration played", shadowOf(vibrator).isVibrating());
        Haptics.vibrate(vibrator, shortEffect(30L));
        assertFalse("an effect played", shadowOf(vibrator).isVibrating());
        Haptics.vibrate(vibrator, shortEffect(30L), touchAttributes());
        assertFalse("an effect with attributes played", shadowOf(vibrator).isVibrating());
    }

    @Test public void switchedOffTheyPlayAsTikTokAsked() {
        Settings.TURN_OFF_HAPTICS.save(false);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertEquals(1, view.haptics);
        assertEquals(HapticFeedbackConstants.LONG_PRESS, view.lastConstant);
        assertEquals("the one-argument call stays one", NO_FLAGS, view.lastFlags);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
        assertEquals(2, view.haptics);
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, view.lastConstant);
        assertEquals(HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING, view.lastFlags);

        Haptics.vibrate(vibrator, 30L);
        assertTrue(shadowOf(vibrator).isVibrating());
        assertEquals(30L, shadowOf(vibrator).getMilliseconds());
    }

    @Test public void switchedOffAnEffectPlays() {
        Settings.TURN_OFF_HAPTICS.save(false);

        Haptics.vibrate(vibrator, shortEffect(40L));
        assertTrue(shadowOf(vibrator).isVibrating());
    }

    @Test public void switchedOffAnEffectWithAttributesPlays() {
        Settings.TURN_OFF_HAPTICS.save(false);

        Haptics.vibrate(vibrator, shortEffect(40L), touchAttributes());
        assertTrue(shadowOf(vibrator).isVibrating());
    }

    @Test public void pausedTheyPlayWithTheSwitchOn() {
        PausedProcess.set(true);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertEquals(1, view.haptics);
        Haptics.vibrate(vibrator, 25L);
        assertTrue(shadowOf(vibrator).isVibrating());
        assertEquals(25L, shadowOf(vibrator).getMilliseconds());
    }

    /**
     * A view that notes whether its haptics were on when Android asked it for the long press buzz.
     * Robolectric's ShadowView answers performHapticFeedback(int) itself, records the constant and
     * ignores the view's setting, so what it records can't show a buzz was skipped. What this
     * shows is the setting Android's own check reads at that moment, on the call API 28's
     * performLongClickInternal makes right after the listener answers.
     */
    private static final class WitnessView extends View {
        int longPressAsks;
        boolean enabledWhenAsked;

        WitnessView(Context context) {
            super(context);
            setOnLongClickListener(v -> true);
        }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            if (feedbackConstant == HapticFeedbackConstants.LONG_PRESS) {
                longPressAsks++;
                enabledWhenAsked = isHapticFeedbackEnabled();
            }
            return super.performHapticFeedback(feedbackConstant);
        }
    }

    /** A TikTok screen, opened after the patch's install, with a column to put views in. */
    private interface Screen {
        void run(Activity activity, LinearLayout column);
    }

    private static void onScreen(Screen body) {
        Haptics.install(RuntimeEnvironment.getApplication());
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            LinearLayout column = new LinearLayout(controller.get());
            column.setOrientation(LinearLayout.VERTICAL);
            controller.get().setContentView(column);
            body.run(controller.get(), column);
        }
    }

    /** {@code view} as a row 100 pixels tall, laid out. */
    private static <T extends View> T row(ViewGroup column, T view) {
        column.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
        settle();
        return view;
    }

    /** A finger at the middle of {@code view}, handed to its window the way Android hands it on. */
    private static void touch(View view, int action) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action,
                at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f, 0);
        view.getRootView().dispatchTouchEvent(event);
        event.recycle();
    }

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    private static void hold() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    @Test public void aLongPressIsQuietAndTheViewsHapticsComeBackAtTheLift() {
        onScreen((activity, column) -> {
            assertNotSame("the screen's window is followed", activity, activity.getWindow().getCallback());
            WitnessView target = row(column, new WitnessView(activity));

            touch(target, MotionEvent.ACTION_DOWN);
            assertFalse("off from the moment the finger lands", target.isHapticFeedbackEnabled());
            hold();
            assertEquals("Android asked for its long press buzz", 1, target.longPressAsks);
            assertFalse("the view's haptics were on when Android asked", target.enabledWhenAsked);

            touch(target, MotionEvent.ACTION_UP);
            assertTrue("back on at the lift", target.isHapticFeedbackEnabled());
        });
    }

    @Test public void aCancelGivesTheHapticsBackToo() {
        onScreen((activity, column) -> {
            WitnessView target = row(column, new WitnessView(activity));
            touch(target, MotionEvent.ACTION_DOWN);
            assertFalse(target.isHapticFeedbackEnabled());
            touch(target, MotionEvent.ACTION_CANCEL);
            assertTrue(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void onlyTheLongPressViewsUnderTheFingerGoQuiet() {
        onScreen((activity, column) -> {
            WitnessView elsewhere = row(column, new WitnessView(activity));
            View plain = row(column, new View(activity));
            EditText field = row(column, new EditText(activity));
            assertTrue("a text field takes long presses", field.isLongClickable());

            touch(plain, MotionEvent.ACTION_DOWN);
            assertTrue("a long press view the finger isn't on", elsewhere.isHapticFeedbackEnabled());
            touch(plain, MotionEvent.ACTION_UP);

            touch(field, MotionEvent.ACTION_DOWN);
            assertTrue("a text field keeps its selection buzz", field.isHapticFeedbackEnabled());
            touch(field, MotionEvent.ACTION_UP);
        });
    }

    @Test public void aLiftThatNeverCameIsMadeUpAtTheNextPress() {
        onScreen((activity, column) -> {
            WitnessView first = row(column, new WitnessView(activity));
            View plain = row(column, new View(activity));
            touch(first, MotionEvent.ACTION_DOWN);
            assertFalse(first.isHapticFeedbackEnabled());
            // A panel took the press over, and the lift went to its window.
            touch(plain, MotionEvent.ACTION_DOWN);
            assertTrue(first.isHapticFeedbackEnabled());
            touch(plain, MotionEvent.ACTION_UP);
        });
    }

    @Test public void aViewWithHapticsOffStaysOff() {
        onScreen((activity, column) -> {
            WitnessView target = row(column, new WitnessView(activity));
            target.setHapticFeedbackEnabled(false);
            touch(target, MotionEvent.ACTION_DOWN);
            touch(target, MotionEvent.ACTION_UP);
            assertFalse(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void switchedOffOrPausedAPressKeepsItsBuzz() {
        onScreen((activity, column) -> {
            WitnessView target = row(column, new WitnessView(activity));
            Settings.TURN_OFF_HAPTICS.save(false);
            touch(target, MotionEvent.ACTION_DOWN);
            hold();
            assertEquals(1, target.longPressAsks);
            assertTrue("the buzz was asked for with haptics on", target.enabledWhenAsked);
            touch(target, MotionEvent.ACTION_UP);

            Settings.TURN_OFF_HAPTICS.save(true);
            PausedProcess.set(true);
            touch(target, MotionEvent.ACTION_DOWN);
            assertTrue(target.isHapticFeedbackEnabled());
            touch(target, MotionEvent.ACTION_UP);
        });
    }

    @Test public void switchedOffTheScreensWindowIsLeftAsItIs() {
        Settings.TURN_OFF_HAPTICS.save(false);
        onScreen((activity, column) ->
                assertSame(activity, activity.getWindow().getCallback()));
    }

    @Test public void aDialogsWindowIsFollowedToo() {
        onScreen((activity, column) -> {
            Dialog dialog = new Dialog(activity);
            WitnessView target = new WitnessView(activity);
            dialog.setContentView(target, new ViewGroup.LayoutParams(300, 300));
            dialog.show();
            settle();
            Haptics.watchOtherWindows();
            assertNotSame("the dialog's window is followed", dialog, dialog.getWindow().getCallback());

            touch(target, MotionEvent.ACTION_DOWN);
            assertFalse(target.isHapticFeedbackEnabled());
            touch(target, MotionEvent.ACTION_UP);
            assertTrue(target.isHapticFeedbackEnabled());
            dialog.dismiss();
        });
    }
}
