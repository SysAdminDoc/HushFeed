package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * App lock through TikTok's screens as Android starts and stops them. The system prompt is
 * stood in for by a recorder from Android 9 on, since Robolectric can't show it; below that the
 * real confirm-credential request goes out and its answer comes back through the fragment.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AppLockTest {
    /** A screen of TikTok's with content, so it has a decor before its first frame. */
    public static class Screen extends Activity {
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            setContentView(new FrameLayout(this));
        }
    }

    private Application application;
    private final List<Activity> asked = new ArrayList<>();

    @Before public void setUp() {
        application = RuntimeEnvironment.getApplication();
        Utils.setContext(application);
        AppLock.resetForTests(application);
        Settings.APP_LOCK.save(true);
        Settings.APP_LOCK_TIMEOUT.save("5");
        secure(true);
        AppLock.install(application);
        AppLock.askerForTests = asked::add;
    }

    @After public void tearDown() {
        AppLock.resetForTests(application);
        PausedProcess.set(false);
        Settings.APP_LOCK.save(Settings.APP_LOCK.defaultValue);
        Settings.APP_LOCK_TIMEOUT.save(Settings.APP_LOCK_TIMEOUT.defaultValue);
    }

    @Test public void aColdStartIsCoveredBeforeItsFirstFrameAndAsksUntilUnlocked() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).create().start()) {
            Activity activity = screen.get();
            assertNotNull("the screen was not covered by the time it started", coverOf(activity));
            assertTrue(AppLock.isLocked());

            screen.resume().visible();
            assertTrue("it asked before the screen settled", asked.isEmpty());
            idleFor(AppLock.ASK_DELAY_MS + 50);
            assertEquals("the screen in front was not asked", List.of(activity), asked);

            AppLock.succeeded();
            assertNull("unlocking left the cover up", coverOf(activity));
            assertFalse(AppLock.isLocked());
        }
    }

    /** A mouse wheel over the cover leaves the screen under it where it is until the unlock. */
    @Test public void aWheelOverTheCoverNeverReachesTheScreenUnderIt() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            Activity activity = screen.get();
            idleFor(AppLock.ASK_DELAY_MS + 50);
            View decor = activity.getWindow().getDecorView();
            ViewGroup content = activity.findViewById(android.R.id.content);
            int[] turns = app.morphe.extension.tiktok.Wheel.count(content.getChildAt(0));
            assertNotNull(coverOf(activity));
            assertTrue("nothing took the wheel", app.morphe.extension.tiktok.Wheel.turn(decor));
            assertEquals("a wheel over the cover scrolled the screen under it", 0, turns[0]);

            AppLock.succeeded();
            assertNull(coverOf(activity));
            assertTrue(app.morphe.extension.tiktok.Wheel.turn(decor));
            assertEquals("the fixture's wheel never reaches the screen", 1, turns[0]);
        }
    }

    @Test public void aReturnInsideTheDelayStaysOpenAndOnePastItLocksAgain() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            Activity activity = screen.get();
            idleFor(AppLock.ASK_DELAY_MS + 50);
            AppLock.succeeded();
            asked.clear();

            screen.pause().stop();
            idleFor(Duration.ofMinutes(4).toMillis());
            screen.restart().resume();
            idleFor(AppLock.ASK_DELAY_MS + 50);
            assertNull("a return inside five minutes was covered", coverOf(activity));
            assertTrue("a return inside five minutes was asked", asked.isEmpty());

            screen.pause().stop();
            idleFor(Duration.ofMinutes(5).toMillis() + 1_000);
            screen.restart();
            assertNotNull("a return past five minutes was not covered", coverOf(activity));
            screen.resume();
            idleFor(AppLock.ASK_DELAY_MS + 50);
            assertEquals(List.of(activity), asked);
        }
    }

    @Test public void rightAwayLocksOnEveryReturnButNotOnARotation() {
        Settings.APP_LOCK_TIMEOUT.save("0");
        ActivityController<Screen> first = Robolectric.buildActivity(Screen.class).setup();
        idleFor(AppLock.ASK_DELAY_MS + 50);
        AppLock.succeeded();

        // A rotation stops the screen and starts its replacement; the app never left.
        ReflectionHelpers.setField(Activity.class, first.get(), "mChangingConfigurations", true);
        first.pause().stop();
        ActivityController<Screen> rotated = Robolectric.buildActivity(Screen.class).setup();
        first.destroy();
        assertNull("a rotation locked TikTok", coverOf(rotated.get()));

        rotated.pause().stop();
        idleFor(1_000);
        rotated.restart();
        assertNotNull("a return was not locked with the delay at right away", coverOf(rotated.get()));
        rotated.resume().pause().stop().destroy();
    }

    @Test public void aLinkOpenedFromOutsideLandsOnItsScreenAfterTheUnlock() {
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@someone/video/7"));
        // The link's trampoline hands over and finishes before anything is asked.
        ActivityController<Screen> trampoline = Robolectric.buildActivity(Screen.class, link).setup();
        assertNotNull(coverOf(trampoline.get()));
        trampoline.pause();
        ActivityController<Screen> video = Robolectric.buildActivity(Screen.class, link).setup();
        trampoline.get().finish();
        trampoline.stop().destroy();
        assertNotNull("the video's screen was not covered", coverOf(video.get()));

        idleFor(AppLock.ASK_DELAY_MS + 50);
        assertEquals("the ask was not made on the screen the link landed on", List.of(video.get()), asked);
        AppLock.succeeded();
        assertNull(coverOf(video.get()));
        assertEquals("the link's screen lost its intent", link.getData(), video.get().getIntent().getData());
        video.pause().stop().destroy();
    }

    @Test public void closingThePromptKeepsTheCoverAndItsButtonAsksAgain() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            Activity activity = screen.get();
            idleFor(AppLock.ASK_DELAY_MS + 50);
            AppLock.onPromptError(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, activity);
            idleFor(5_000);
            assertEquals("a closed prompt came back by itself", 1, asked.size());
            View cover = coverOf(activity);
            assertNotNull("closing the prompt took the cover down", cover);

            View unlock = ((ViewGroup) ((ViewGroup) cover).getChildAt(0)).getChildAt(1);
            assertTrue(unlock.performClick());
            assertEquals(2, asked.size());
        }
    }

    @Test public void tabAndAReaderGoToUnlockAndNothingUnderTheCover() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).create()) {
            Activity activity = screen.get();
            ViewGroup content = activity.findViewById(android.R.id.content);
            android.widget.Button like = new android.widget.Button(activity);
            like.setFocusableInTouchMode(true);
            ((ViewGroup) content.getChildAt(0)).addView(like);
            screen.start().resume().visible();
            View cover = coverOf(activity);
            assertNotNull(cover);
            View unlock = ((ViewGroup) ((ViewGroup) cover).getChildAt(0)).getChildAt(1);

            ArrayList<View> stops = new ArrayList<>();
            activity.getWindow().getDecorView().addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertEquals("Tab goes somewhere other than Unlock under the lock", List.of(unlock), stops);
            assertFalse("the screen under the lock can still take focus", like.requestFocus());

            android.view.accessibility.AccessibilityNodeInfo info = cover.createAccessibilityNodeInfo();
            assertFalse("a reader is told the cover itself can be pressed", info.isClickable());
            assertFalse("a reader is offered a click on the cover itself", info.getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            assertTrue("the cover stopped swallowing touches", cover.isClickable());

            AppLock.succeeded();
            assertTrue("the screen can't take focus after the unlock", like.requestFocus());
        }
    }

    @Test public void aViewTikTokPutsOnTheScreenWhileLockedStaysUnderTheCover() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            Activity activity = screen.get();
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View cover = coverOf(activity);
            assertNotNull(cover);
            View unlock = ((ViewGroup) ((ViewGroup) cover).getChildAt(0)).getChildAt(1);

            // Straight onto the decor, after the cover went up, as a floating view would be.
            android.widget.Button late = new android.widget.Button(activity);
            late.setFocusableInTouchMode(true);
            decor.addView(late);
            decor.getViewTreeObserver().dispatchOnGlobalLayout();

            assertEquals("the cover is no longer over everything on the screen",
                    decor.getChildCount() - 1, decor.indexOfChild(cover));
            ArrayList<View> stops = new ArrayList<>();
            decor.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertEquals("Tab reaches what TikTok added under the lock", List.of(unlock), stops);
            assertFalse("a reader reaches what TikTok added under the lock", late.isImportantForAccessibility());

            AppLock.succeeded();
            assertTrue("what TikTok added can't take focus after the unlock", late.requestFocus());
            assertTrue("a reader can't reach what TikTok added after the unlock", late.isImportantForAccessibility());
        }
    }

    @Test public void theSystemTakingThePromptDownAsksAgainButNotForever() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            Activity activity = screen.get();
            idleFor(AppLock.ASK_DELAY_MS + 50);
            for (int cancel = 0; cancel < 5; cancel++) {
                AppLock.onPromptError(BiometricPrompt.BIOMETRIC_ERROR_CANCELED, activity);
                idleFor(AppLock.ASK_DELAY_MS + 50);
            }
            assertEquals("cancels were not retried, or were retried without end", 3, asked.size());
            assertNotNull(coverOf(activity));
        }
    }

    @Test public void offNothingChanges() {
        Settings.APP_LOCK.save(false);
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            idleFor(AppLock.ASK_DELAY_MS + 50);
            assertNull(coverOf(screen.get()));
            assertTrue(asked.isEmpty());
            screen.pause();
            assertEquals("the window was made secure with the switch off", 0,
                    screen.get().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** Pause is a file any file manager can make, so it can't be the way past the lock. */
    @Test public void pausedHushfeedStillLocks() {
        PausedProcess.set(true);
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            idleFor(AppLock.ASK_DELAY_MS + 50);
            assertNotNull(coverOf(screen.get()));
            assertFalse(asked.isEmpty());
            assertTrue(AppLock.isLocked());
        }
    }

    @Test public void aPhoneWithNoScreenLockOpensAndSaysWhy() {
        secure(false);
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            idleFor(AppLock.ASK_DELAY_MS + 50);
            assertNull(coverOf(screen.get()));
            assertTrue(asked.isEmpty());
            assertEquals(L10n.t("TikTok can't lock without a phone screen lock"),
                    ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aScreenLockRemovedBeforeTheAskLetsTheReaderIn() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            Activity activity = screen.get();
            assertNotNull(coverOf(activity));
            secure(false);
            idleFor(AppLock.ASK_DELAY_MS + 50);
            assertTrue(asked.isEmpty());
            assertNull("the reader was kept out with no screen lock to ask for", coverOf(activity));
            assertFalse(AppLock.isLocked());
        }
    }

    @Test public void theRecentsScreenshotIsBlankedOnTheWayOutAndScreenshotsWorkInside() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            idleFor(AppLock.ASK_DELAY_MS + 50);
            AppLock.succeeded();
            int secure = WindowManager.LayoutParams.FLAG_SECURE;
            assertEquals("screenshots inside TikTok were blocked", 0,
                    screen.get().getWindow().getAttributes().flags & secure);
            screen.pause();
            assertEquals("the screen left without FLAG_SECURE", secure,
                    screen.get().getWindow().getAttributes().flags & secure);
            screen.resume();
            assertEquals("FLAG_SECURE stayed after the return", 0,
                    screen.get().getWindow().getAttributes().flags & secure);

            // A window TikTok made secure itself stays that way.
            screen.get().getWindow().addFlags(secure);
            screen.pause().resume();
            assertEquals(secure, screen.get().getWindow().getAttributes().flags & secure);
        }
    }

    @Config(sdk = 33)
    @Test public void androidThirteenStillCoversWithTheRecentsSwitch() {
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            assertNotNull(coverOf(screen.get()));
            screen.pause();
            assertEquals("Android 13 doesn't need FLAG_SECURE for recents", 0,
                    screen.get().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** Below Android 9 the system's confirm screen asks, and its answer reaches the lock. */
    @Config(sdk = 27)
    @Test public void belowAndroidNineTheConfirmScreenAsksAndItsAnswerUnlocks() {
        AppLock.askerForTests = null;
        try (ActivityController<Screen> screen = Robolectric.buildActivity(Screen.class).setup()) {
            Activity activity = screen.get();
            idleFor(AppLock.ASK_DELAY_MS + 50);
            Intent request = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", request.getAction());
            Fragment asking = activity.getFragmentManager().findFragmentByTag(AppLock.CREDENTIAL_TAG);
            assertNotNull(asking);

            asking.onActivityResult(AppLock.CredentialRequest.REQUEST, Activity.RESULT_CANCELED, null);
            idle();
            assertNotNull("backing out of the confirm screen let the reader in", coverOf(activity));

            View unlock = ((ViewGroup) ((ViewGroup) coverOf(activity)).getChildAt(0)).getChildAt(1);
            assertTrue(unlock.performClick());
            idle();
            Shadows.shadowOf(activity).getNextStartedActivityForResult();
            Fragment again = activity.getFragmentManager().findFragmentByTag(AppLock.CREDENTIAL_TAG);
            assertNotNull("the button did not ask again", again);
            again.onActivityResult(AppLock.CredentialRequest.REQUEST, Activity.RESULT_OK, null);
            idle();
            assertNull("confirming the screen lock left the cover up", coverOf(activity));
            assertNull("the request stayed behind after its answer",
                    activity.getFragmentManager().findFragmentByTag(AppLock.CREDENTIAL_TAG));
        }
    }

    private void secure(boolean secure) {
        KeyguardManager keyguard = (KeyguardManager) application.getSystemService(Context.KEYGUARD_SERVICE);
        Shadows.shadowOf(keyguard).setIsDeviceSecure(secure);
    }

    private static View coverOf(Activity activity) {
        View decor = activity.getWindow().peekDecorView();
        return decor instanceof ViewGroup ? AppLock.coverIn((ViewGroup) decor) : null;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void idleFor(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
