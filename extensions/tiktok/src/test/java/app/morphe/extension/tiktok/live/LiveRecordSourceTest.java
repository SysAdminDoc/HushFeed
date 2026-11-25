package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.LiveRecorder;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

/** #37: the LIVE recording source choice, and what a screen recording targets. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class LiveRecordSourceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        Settings.LIVE_RECORD_SOURCE.save("stream");
    }

    public static final class Owner {
        public final String username = "someone";
    }

    public static final class Room {
        public final Owner owner = new Owner();
        public final String idStr = "777";
    }

    @Test public void theStreamIsTheDefaultAndTheScreenIsOptIn() {
        assertEquals("stream", Settings.LIVE_RECORD_SOURCE.defaultValue);
        assertFalse(LiveRecordSource.screen());
        Settings.LIVE_RECORD_SOURCE.save("screen");
        assertTrue(LiveRecordSource.screen());
    }

    @Test public void anythingUnknownRecordsTheStreamAsBefore() {
        assertFalse(LiveRecordSource.screen(null));
        assertFalse(LiveRecordSource.screen(""));
        assertFalse(LiveRecordSource.screen("Screen "));
        assertFalse(LiveRecordSource.screen("stream"));
        assertTrue(LiveRecordSource.screen("screen"));
    }

    @Test public void theListOffersBothChoicesWithTheStreamFirst() {
        assertArrayEquals(new String[]{"Stream only", "Screen"}, LiveRecordSource.LABELS);
        assertArrayEquals(new String[]{"stream", "screen"}, LiveRecordSource.VALUES);
        assertEquals(Settings.LIVE_RECORD_SOURCE.defaultValue, LiveRecordSource.VALUES[0]);
    }

    @Test public void holdingTheRecordButtonPicksWhatTheNextRecordingIsMadeFrom() {
        // #529: the chip's long press lists both sources with the one in effect marked, and a
        // pick is saved and named in a toast.
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(android.R.style.Theme_Material_Light);
            Utils.setContext(activity);
            TextView chip = LiveRecordChip.buildChip(activity);

            assertTrue(chip.performLongClick());
            AlertDialog picker = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(picker);
            assertTrue(picker.isShowing());
            ListView choices = picker.getListView();
            assertEquals(2, choices.getAdapter().getCount());
            assertEquals("Stream only", choices.getAdapter().getItem(0).toString());
            assertEquals("Screen", choices.getAdapter().getItem(1).toString());
            assertEquals("the stream is in effect", 0, choices.getCheckedItemPosition());

            choices.performItemClick(choices.getAdapter().getView(1, null, choices), 1, 1);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("screen", Settings.LIVE_RECORD_SOURCE.get());
            assertTrue(LiveRecordSource.screen());
            assertEquals("Screen", ShadowToast.getTextOfLatestToast());
            assertFalse(picker.isShowing());

            // Holding it again shows the screen marked, and the stream can be picked back.
            assertTrue(chip.performLongClick());
            AlertDialog again = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            ListView second = again.getListView();
            assertEquals(1, second.getCheckedItemPosition());
            second.performItemClick(second.getAdapter().getView(0, null, second), 0, 0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("stream", Settings.LIVE_RECORD_SOURCE.get());
            assertEquals("Stream only", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void soundNeedsAndroid10AndTheMicrophonePermission() {
        assertFalse(LiveRecordSource.audioAllowed(28, true));
        assertFalse(LiveRecordSource.audioAllowed(29, false));
        assertFalse(LiveRecordSource.audioAllowed(34, false));
        assertTrue(LiveRecordSource.audioAllowed(29, true));
        assertTrue(LiveRecordSource.audioAllowed(36, true));
    }

    @Test public void aScreenTargetNamesTheCreatorAndRoomAndHasNoStreamAddress() {
        LiveRoomStream.Pick pick = LiveRoomStream.screenTarget(new Room());
        assertNull(pick.problem);
        assertNotNull(pick.target);
        assertEquals(LiveRecorder.Transport.SCREEN, pick.target.transport);
        assertNull(pick.target.url);
        assertEquals("someone", pick.target.creator);
        assertEquals("777", pick.target.room);
    }

    @Test public void noRoomIsNoTarget() {
        LiveRoomStream.Pick pick = LiveRoomStream.screenTarget(null);
        assertNull(pick.target);
        assertNotNull(pick.problem);
    }
}
