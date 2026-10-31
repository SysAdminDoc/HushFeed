package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** A TikTok link started in another app's task moves to TikTok's own (#227). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LinkTaskGuardTest {
    private static final String LINK = "https://vm.tiktok.com/ZMabc/";

    // PausedProcess reads Settings, which can't load without a context when this class runs alone.
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PausedProcess.set(false);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        LinkReturn.resetForTests();
    }

    private static Activity activityWith(Intent intent) {
        Activity activity = Robolectric.buildActivity(Activity.class, intent).create().get();
        activity.setIntent(intent);
        return activity;
    }

    private static Intent view(String url, int flags) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(flags);
    }

    /** CLEAR_TOP and SINGLE_TOP keep each relaunch from leaving a For You feed under the link (#276). */
    @Test public void foreignTaskRelaunchesWithNewTaskAndFinishes() {
        Intent original = view(LINK, 0).putExtra("k", "v");
        Activity activity = activityWith(original);

        assertTrue(LinkTaskGuard.redirectIfForeign(activity, false, Boolean.FALSE));

        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Uri.parse(LINK), started.getData());
        assertEquals("v", started.getStringExtra("k"));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue(activity.isFinishing());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP, started.getFlags()
                & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        assertEquals(Intent.ACTION_MAIN, activity.getIntent().getAction());
        assertNull(activity.getIntent().getData());
        assertEquals(activity.getPackageName(), activity.getIntent().getPackage());
        assertTrue(LinkReturn.armed());
    }

    /** Remembers whether it was already finishing when it started the relaunch. */
    public static class RecordingActivity extends Activity {
        Boolean finishingAtStart;

        @Override public void startActivity(Intent intent) {
            finishingAtStart = isFinishing();
            super.startActivity(intent);
        }
    }

    @Test public void finishesBeforeRelaunching() {
        // Started while still the running top of the sender's task, the singleTop alias got its
        // own relaunch back as onNewIntent and opened the link there (seen on 47.4.3).
        RecordingActivity activity =
                Robolectric.buildActivity(RecordingActivity.class, view(LINK, 0)).create().get();
        activity.setIntent(view(LINK, 0));

        assertTrue(LinkTaskGuard.redirectIfForeign(activity, false, Boolean.FALSE));
        assertEquals(Boolean.TRUE, activity.finishingAtStart);
    }

    @Test public void ownTaskIsLeftAlone() {
        Activity activity = activityWith(view(LINK, 0));
        assertFalse(LinkTaskGuard.redirectIfForeign(activity, false, Boolean.TRUE));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertFalse(activity.isFinishing());
        assertFalse(LinkReturn.armed());
    }

    @Test public void taskRootIsLeftAlone() {
        Activity activity = activityWith(view(LINK, 0));
        assertFalse(LinkTaskGuard.redirectIfForeign(activity, true, Boolean.FALSE));
        assertFalse(activity.isFinishing());
    }

    @Test public void newTaskFlagIsLeftAlone() {
        Activity activity = activityWith(view(LINK, Intent.FLAG_ACTIVITY_NEW_TASK));
        assertFalse(LinkTaskGuard.redirectIfForeign(activity, false, Boolean.FALSE));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void otherHostAndOtherActionAreLeftAlone() {
        Activity other = activityWith(view("https://example.com/tiktok.com", 0));
        assertFalse(LinkTaskGuard.redirectIfForeign(other, false, Boolean.FALSE));
        Activity lookalike = activityWith(view("https://nottiktok.com/x", 0));
        assertFalse(LinkTaskGuard.redirectIfForeign(lookalike, false, Boolean.FALSE));
        Activity send = activityWith(new Intent(Intent.ACTION_SEND, Uri.parse(LINK)));
        assertFalse(LinkTaskGuard.redirectIfForeign(send, false, Boolean.FALSE));
        assertFalse(other.isFinishing() || lookalike.isFinishing() || send.isFinishing());
    }

    @Test public void pausedIsLeftAlone() {
        PausedProcess.set(true);
        Activity activity = activityWith(view(LINK, 0));
        assertFalse(LinkTaskGuard.redirectIfForeign(activity, false, Boolean.FALSE));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertFalse(activity.isFinishing());
    }

    @Test public void failureFallsThrough() {
        assertFalse(LinkTaskGuard.redirectIfForeign(null, false, Boolean.FALSE));
        LinkTaskGuard.onMainActivityCreate(null);
    }

    @Test public void unknownTaskFallsBackToTheReferrer() {
        assertTrue(LinkTaskGuard.shouldRelaunch(false, Intent.ACTION_VIEW, "https", "www.tiktok.com",
                0, false, null, "org.telegram.messenger", "com.zhiliaoapp.musically"));
        assertFalse(LinkTaskGuard.shouldRelaunch(false, Intent.ACTION_VIEW, "https", "www.tiktok.com",
                0, false, null, "com.zhiliaoapp.musically", "com.zhiliaoapp.musically"));
        assertFalse(LinkTaskGuard.shouldRelaunch(false, Intent.ACTION_VIEW, "https", "www.tiktok.com",
                0, false, null, null, "com.zhiliaoapp.musically"));
    }

    @Test public void hostMatchingCoversSubdomainsOnly() {
        assertTrue(LinkTaskGuard.isTikTokHost("tiktok.com"));
        assertTrue(LinkTaskGuard.isTikTokHost("VM.TikTok.com"));
        assertFalse(LinkTaskGuard.isTikTokHost("tiktok.com.evil.net"));
        assertFalse(LinkTaskGuard.isTikTokHost(null));
    }
}
