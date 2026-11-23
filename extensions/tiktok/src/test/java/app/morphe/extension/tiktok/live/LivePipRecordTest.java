package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Intent;
import android.graphics.drawable.Icon;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #413: TikTok's own picture-in-picture window for a LIVE gets a Record button next to its play
 * and pause, which follows the recording: Record, Stop, then dimmed while the recording saves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LivePipRecordTest {
    /** A LIVE activity that keeps the params it was handed, as Android's window would. */
    public static class Room extends Activity {
        PictureInPictureParams last;
        int sent;
        @Override public void setPictureInPictureParams(PictureInPictureParams params) {
            last = params;
            sent++;
        }
        @Override public int getMaxNumPictureInPictureActions() { return 3; }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.liveControlsEnabled = true;
        Settings.RECORD_LIVE.save(true);
    }

    @After public void tearDown() {
        LiveRecordChip.holdForTests(null, false);
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "host", new WeakReference<>(null));
        ReflectionHelpers.setStaticField(LivePipRecord.class, "theirs", Collections.emptyList());
        ReflectionHelpers.setStaticField(LivePipRecord.class, "theirsOn", new WeakReference<>(null));
        ReflectionHelpers.setStaticField(LivePipRecord.class, "shown", LivePipRecord.HIDDEN);
        ReflectionHelpers.setStaticField(LivePipRecord.class, "receiver", null);
        SettingsStatus.liveControlsEnabled = false;
        Settings.RECORD_LIVE.save(Settings.RECORD_LIVE.defaultValue);
    }

    @Test public void theButtonFollowsTheRecordingAndOnlyStreamRecordingsGetOne() {
        assertEquals(LivePipRecord.RECORD, LiveRecordChip.pipButtonState(true, false, false, false));
        assertEquals(LivePipRecord.STOP, LiveRecordChip.pipButtonState(true, false, false, true));
        assertEquals(LivePipRecord.SAVING, LiveRecordChip.pipButtonState(true, false, true, false));
        // A screen recording would film the home screen behind the window.
        assertEquals(LivePipRecord.HIDDEN, LiveRecordChip.pipButtonState(true, true, false, false));
        assertEquals(LivePipRecord.HIDDEN, LiveRecordChip.pipButtonState(false, false, false, true));
    }

    @Test public void recordStopAndSavingEachReadRightAndOnlySavingIsDimmed() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RemoteAction record = LivePipRecord.button(activity, LivePipRecord.RECORD);
        assertEquals("Record", record.getTitle().toString());
        assertEquals("Record this LIVE from now", record.getContentDescription().toString());
        assertTrue(record.isEnabled());
        RemoteAction stop = LivePipRecord.button(activity, LivePipRecord.STOP);
        assertEquals("Stop", stop.getTitle().toString());
        assertEquals("Stop recording this LIVE", stop.getContentDescription().toString());
        assertTrue(stop.isEnabled());
        RemoteAction saving = LivePipRecord.button(activity, LivePipRecord.SAVING);
        assertEquals("Saving the recording", saving.getTitle().toString());
        assertFalse(saving.isEnabled());
        assertEquals(Icon.TYPE_BITMAP, record.getIcon().getType());
    }

    @Test public void onlyTheButtonItselfCanStartOrStopARecording() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Intent sent = sentBy(LivePipRecord.button(activity, LivePipRecord.RECORD).getActionIntent());
        assertEquals(activity.getPackageName(), sent.getPackage());
        assertTrue(LivePipRecord.fromTheButton(sent));
        assertFalse(LivePipRecord.fromTheButton(new Intent(LivePipRecord.ACTION)));
        assertFalse(LivePipRecord.fromTheButton(new Intent(LivePipRecord.ACTION)
                .putExtra(LivePipRecord.EXTRA_TOKEN, "guessed")));
        assertFalse(LivePipRecord.fromTheButton(null));
    }

    @Test public void recordGoesAfterTikToksPlayAndPause() {
        Room room = Robolectric.buildActivity(Room.class).setup().get();
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "host", new WeakReference<>(room));
        RemoteAction theirs = LivePipRecord.button(room, LivePipRecord.RECORD);
        List<Object> actions = new ArrayList<>(Collections.singletonList(theirs));
        LivePipRecord.addTo(actions);
        assertEquals(2, actions.size());
        assertSame(theirs, actions.get(0));
        assertEquals("Record", ((RemoteAction) actions.get(1)).getTitle().toString());
    }

    @Test public void withRecordLiveOffOrNoRoomLeftTikToksButtonsStayAsTheyAre() {
        Room room = Robolectric.buildActivity(Room.class).setup().get();
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "host", new WeakReference<>(room));
        Settings.RECORD_LIVE.save(false);
        List<Object> one = new ArrayList<>(Collections.singletonList(LivePipRecord.button(room, LivePipRecord.STOP)));
        LivePipRecord.addTo(one);
        assertEquals(1, one.size());

        Settings.RECORD_LIVE.save(true);
        List<Object> full = new ArrayList<>();
        for (int i = 0; i < 3; i++) full.add(LivePipRecord.button(room, LivePipRecord.STOP));
        LivePipRecord.addTo(full);
        assertEquals(3, full.size());
        assertFalse(LivePipRecord.fits(LivePipRecord.RECORD, 3, 3));
        assertFalse(LivePipRecord.fits(LivePipRecord.HIDDEN, 0, 3));
        assertTrue(LivePipRecord.fits(LivePipRecord.STOP, 2, 3));
    }

    @Test public void startingAndSavingSwapTheButtonAndKeepTikToks() {
        Room room = Robolectric.buildActivity(Room.class).setup().get();
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "host", new WeakReference<>(room));
        RemoteAction theirs = LivePipRecord.button(room, LivePipRecord.RECORD);
        LivePipRecord.addTo(new ArrayList<>(Collections.singletonList(theirs)));

        // Nothing changed since TikTok set its buttons, so the window isn't touched.
        LivePipRecord.update(room);
        assertEquals(0, room.sent);

        LiveRecordChip.holdForTests(null, true);
        LivePipRecord.update(room);
        assertEquals(1, room.sent);
        List<RemoteAction> sent = actionsOf(room.last);
        assertEquals(2, sent.size());
        assertSame(theirs, sent.get(0));
        assertEquals("Saving the recording", sent.get(1).getTitle().toString());
        assertFalse(sent.get(1).isEnabled());

        LivePipRecord.update(room);
        assertEquals(1, room.sent);

        // Another activity's window isn't the one TikTok set these buttons on.
        Room other = Robolectric.buildActivity(Room.class).setup().get();
        LiveRecordChip.holdForTests(null, false);
        LivePipRecord.update(other);
        assertEquals(0, other.sent);

        LivePipRecord.forget(room);
        LivePipRecord.update(room);
        assertEquals(1, room.sent);
    }

    @Test public void turningRecordLiveOffTakesTheButtonAwayAgain() {
        Room room = Robolectric.buildActivity(Room.class).setup().get();
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "host", new WeakReference<>(room));
        LivePipRecord.addTo(new ArrayList<>());
        Settings.RECORD_LIVE.save(false);
        LivePipRecord.update(room);
        assertNotNull(room.last);
        assertTrue(actionsOf(room.last).isEmpty());
    }

    /** The window's Record, pressed after the room page it was for is gone, says so (#413). */
    @Test public void recordWithTheRoomPageGoneSaysToReopenTheLive() {
        ShadowToast.reset();
        LiveRecordChip.onPipButton();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("No stream found to record. Reopen the LIVE.", ShadowToast.getTextOfLatestToast());
    }

    private static Intent sentBy(PendingIntent pending) {
        return Shadows.shadowOf(pending).getSavedIntent();
    }

    private static List<RemoteAction> actionsOf(PictureInPictureParams params) {
        return ReflectionHelpers.callInstanceMethod(params, "getActions");
    }
}
