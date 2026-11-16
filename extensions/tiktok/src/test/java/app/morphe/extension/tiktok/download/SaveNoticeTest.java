package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * A completed save used to be a two-second toast carrying a folder path and offering nothing.
 * With a gallery row in hand it is the six-second action banner now, whose Open hands the row
 * to a viewer; without one (below API 29) the toast stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SaveNoticeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class HostActivity extends Activity {
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // No other window: the banner falls to the activity's content root.
        SaveNotice.windowRootsForTests = java.util.List.of();
    }

    @After public void tearDown() {
        Utils.setActivity(null);
        SaveNotice.windowRootsForTests = null;
        SaveNotice.foregroundForTests = null;
        Settings.SHARE_AFTER_SAVE.save(false);
    }

    /** #423: with Share the video after a save on, a saved video opens the share sheet on its file. */
    @Test public void withShareOnASavedVideoOpensTheShareSheetOnItsFile() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            Settings.SHARE_AFTER_SAVE.save(true);
            SaveNotice.foregroundForTests = true;
            Uri row = Uri.parse("content://media/external/video/media/80");

            SaveNotice.savedVideo("Video saved to DCIM/TikTok", new MediaFileWriter.Saved("a.mp4", row));
            idlePastTheSheetSettle();

            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("no share sheet came up", started);
            assertEquals(Intent.ACTION_CHOOSER, started.getAction());
            Intent send = started.getParcelableExtra(Intent.EXTRA_INTENT);
            assertNotNull(send);
            assertEquals(Intent.ACTION_SEND, send.getAction());
            assertEquals(row, send.getParcelableExtra(Intent.EXTRA_STREAM));
            assertEquals("video/mp4", send.getType());
            assertEquals("the app picked can't read the file", Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            assertEquals(row, send.getClipData().getItemAt(0).getUri());
            assertEquals("Video saved to DCIM/TikTok", ShadowToast.getTextOfLatestToast());
            assertNull("the banner went up under the share sheet",
                    find(activity.findViewById(android.R.id.content), "Open"));
        }
    }

    @Test public void withShareOffASavedVideoKeepsTheBanner() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SaveNotice.foregroundForTests = true;

            SaveNotice.savedVideo("Video saved to DCIM/TikTok",
                    new MediaFileWriter.Saved("a.mp4", Uri.parse("content://media/external/video/media/81")));
            idlePastTheSheetSettle();

            assertNull("a share sheet came up with the switch off",
                    Shadows.shadowOf(activity).getNextStartedActivity());
            assertNotNull(find(activity.findViewById(android.R.id.content), "Open"));
        }
    }

    /** Android opens no share sheet for an app in the background, so the banner says it instead. */
    @Test public void aVideoSavedWhileTikTokIsInTheBackgroundGetsTheBanner() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            Settings.SHARE_AFTER_SAVE.save(true);
            SaveNotice.foregroundForTests = false;

            SaveNotice.savedVideo("Story saved to DCIM/TikTok",
                    new MediaFileWriter.Saved("a.mp4", Uri.parse("content://media/external/video/media/82")));
            idlePastTheSheetSettle();

            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertNotNull("the save went unsaid", find(activity.findViewById(android.R.id.content), "Open"));
        }
    }

    /** A quick save finished inside its own two-second toast, and the banner drew over it (#299). */
    @Test public void theBannerTakesDownTheSavingToastItWouldLandOn() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            Utils.showToastShort("Saving video to DCIM/TikTok");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.widget.Toast saving = ShadowToast.getLatestToast();
            assertNotNull(saving);

            SaveNotice.saved("Video saved to DCIM/TikTok", new MediaFileWriter.Saved("a.mp4",
                    Uri.parse("content://media/external/video/media/78")));
            assertTrue("the toast went before there was a banner to replace it",
                    !Shadows.shadowOf(saving).isCancelled());
            idlePastTheSheetSettle();

            assertTrue("the toast is still up under the banner", Shadows.shadowOf(saving).isCancelled());
        }
    }

    /**
     * #299 again on 0.82.0: a toast after "Saving" took the one slot the cancel kept, so the
     * banner cancelled that one and "Saving" stayed on top of it. Android shows an app's toasts
     * one at a time, so every toast still up or waiting goes.
     */
    @Test public void theBannerTakesDownSavingEvenWithAnotherToastQueuedBehindIt() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            Utils.showToastShort("Saving video to DCIM/TikTok");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.widget.Toast saving = ShadowToast.getLatestToast();
            Utils.showToastShort("Sound saved to Music/TikTok");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.widget.Toast queued = ShadowToast.getLatestToast();
            assertNotSame(saving, queued);

            SaveNotice.saved("Video saved", new MediaFileWriter.Saved("a.mp4",
                    Uri.parse("content://media/external/video/media/79")));
            idlePastTheSheetSettle();

            assertTrue("Saving is still up over the banner", Shadows.shadowOf(saving).isCancelled());
            assertTrue("the queued toast would come up over the banner next",
                    Shadows.shadowOf(queued).isCancelled());
            assertNotNull(find(owner.get().findViewById(android.R.id.content), "Video saved"));
        }
    }

    /**
     * What a video save said in toasts after its banner rides on the banner instead: a toast
     * queued up over it, and the cancel above would now take it down unread (#299).
     */
    @Test public void aVideoSavesOtherNewsGoesOnItsBannerAsLines() {
        assertEquals("Video saved", VideoDownloads.withNotes("Video saved", "Video saved", null,
                false, false, true));
        assertEquals("Video saved\nSound saved to Music/TikTok\nSaved, but a later save may make a second copy",
                VideoDownloads.withNotes("Video saved", "Video saved", "Sound saved to Music/TikTok",
                        false, false, false));
        String own = "No clean copy yet. Saved the watermarked one to DCIM/TikTok";
        assertEquals("Saved 1 of 2, the rest ran out of time\nThe sound couldn't be saved. Try again.\n" + own,
                VideoDownloads.withNotes("Saved 1 of 2, the rest ran out of time", own, null,
                        true, true, true));
        // When the watermark is the first line already, it isn't said twice.
        assertEquals(own, VideoDownloads.withNotes(own, own, null, false, true, true));

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            String said = VideoDownloads.withNotes("Video saved", "Video saved",
                    "Sound saved to Music/TikTok", false, false, true);
            SaveNotice.savedVideo(said, new MediaFileWriter.Saved("a.mp4",
                    Uri.parse("content://media/external/video/media/80")));
            idlePastTheSheetSettle();
            assertNotNull("the banner dropped the sound's line",
                    find(owner.get().findViewById(android.R.id.content), said));
        }
    }

    @Test public void aSaveWithARowShowsTheBannerAndOpenStartsAViewerOnIt() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            Uri row = Uri.parse("content://media/external/audio/media/77");

            SaveNotice.saved("Sound saved to Music/TikTok", new MediaFileWriter.Saved("a.m4a", row));
            idlePastTheSheetSettle();

            ViewGroup root = activity.findViewById(android.R.id.content);
            TextView open = find(root, "Open");
            assertNotNull("no banner with an Open action", open);
            assertNotNull("the banner lost the message", find(root, "Sound saved to Music/TikTok"));

            View clickable = open;
            while (clickable != null && !clickable.hasOnClickListeners()) {
                clickable = clickable.getParent() instanceof View ? (View) clickable.getParent() : null;
            }
            assertNotNull("nothing around Open takes the tap", clickable);
            assertTrue(clickable.performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("Open started nothing", started);
            assertEquals(Intent.ACTION_VIEW, started.getAction());
            assertEquals(row, started.getData());
            assertEquals("the viewer can't read the row",
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    started.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @Test @Config(qualifiers = "en")
    public void aVideoOnlyScreenRecordingKeepsItsVisibleNoticeAndOpenAction() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            Uri row = Uri.parse("content://media/external/video/media/78");
            MediaFileWriter.Saved saved = new MediaFileWriter.Saved("live.mp4", row);
            for (LiveRecorder.Outcome outcome : new LiveRecorder.Outcome[]{
                    LiveRecorder.Outcome.SAVED, LiveRecorder.Outcome.SAVED_PARTIAL}) {
                LiveRecorder.Result result = new LiveRecorder.Result(outcome, LiveRecorder.End.STORAGE,
                        61_000, "live.mp4", saved, null).withScreenAudio(LiveRecorder.ScreenAudio.VIDEO_ONLY);
                LiveRecordingSaver.announce(result);
                idlePastTheSheetSettle();
                String message = outcome == LiveRecorder.Outcome.SAVED
                        ? "LIVE recording saved to " + LiveRecordingSaver.folder()
                        : LiveRecordingSaver.partialText(LiveRecorder.End.STORAGE, LiveRecordingSaver.folder());
                ViewGroup root = activity.findViewById(android.R.id.content);
                assertNotNull("the visible notice lost the audio status",
                        find(root, message + "\nThis screen recording has no sound"));
                TextView open = find(root, "Open");
                assertNotNull(open);
                View clickable = open;
                while (clickable != null && !clickable.hasOnClickListeners()) {
                    clickable = clickable.getParent() instanceof View ? (View) clickable.getParent() : null;
                }
                assertNotNull(clickable);
                assertTrue(clickable.performClick());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(started);
                assertEquals(row, started.getData());
            }
        }
    }

    @Test @Config(qualifiers = "en")
    public void retainedVideoOnlyPartialSaveKeepsItsStopReasonAndBothActions() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            MediaFileWriter.Saved saved = new MediaFileWriter.Saved("live.mp4",
                    Uri.parse("content://media/external/video/media/79"));
            LiveRecorder.Result result = new LiveRecorder.Result(LiveRecorder.Outcome.SAVED_PARTIAL,
                    LiveRecorder.End.DISCONNECTED, 4_000, "live.mp4", saved, null)
                    .withScreenAudio(LiveRecorder.ScreenAudio.VIDEO_ONLY).withRetainedId("a".repeat(32));

            LiveRecordingSaver.announce(result);
            idlePastTheSheetSettle();

            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNotNull("the recovery banner lost the stop reason or audio status", find(root,
                    "LIVE ended or dropped. Recording saved to "
                            + LiveRecordingSaver.folder() + "\nThis screen recording has no sound"));
            assertNotNull("the saved recording cannot be opened", find(root, "Open"));
            assertNotNull("the retained source has no recovery action", find(root, "Retry"));
        }
    }

    @Test public void aSaveWithoutARowStaysAToast() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            SaveNotice.saved("Sound saved to Music/TikTok", new MediaFileWriter.Saved("a.m4a", null));
            idlePastTheSheetSettle();

            assertEquals("Sound saved to Music/TikTok", ShadowToast.getTextOfLatestToast());
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNull("a banner went up without a row to open", find(root, "Open"));
        }
    }

    /**
     * TikTok brings its share sheet back over the feed when a download finishes, and the sheet
     * is a window of its own: a banner on the activity stood underneath it, where the old toast
     * floated above. With another window up, the banner goes on that window.
     */
    @Test public void aSaveUnderAnotherWindowPutsTheBannerOnThatWindow() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            android.app.Dialog sheet = new android.app.Dialog(activity);
            sheet.setContentView(new android.widget.FrameLayout(activity));
            sheet.show();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View activityDecor = activity.getWindow().getDecorView();
            View sheetDecor = sheet.getWindow().getDecorView();
            SaveNotice.windowRootsForTests = java.util.List.of(activityDecor, sheetDecor);

            SaveNotice.saved("Story saved to DCIM/TikTok",
                    new MediaFileWriter.Saved("a.mp4", Uri.parse("content://media/external/video/media/9")));
            idlePastTheSheetSettle();

            assertNotNull("the banner missed the window on top", find(sheetDecor, "Open"));
            assertNull("a second banner went on the activity underneath",
                    find(activity.findViewById(android.R.id.content), "Open"));
            sheet.dismiss();
        }
    }

    /**
     * A toast or any other system-range window on top must not take the banner: it goes down
     * with that window in a couple of seconds, Open never reachable.
     */
    @Test public void aSystemWindowOnTopIsSkippedForTheBanner() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            android.app.Dialog sheet = new android.app.Dialog(activity);
            sheet.setContentView(new android.widget.FrameLayout(activity));
            sheet.show();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View sheetDecor = sheet.getWindow().getDecorView();
            android.widget.FrameLayout toastRoot = new android.widget.FrameLayout(activity);
            android.view.WindowManager.LayoutParams toastParams = new android.view.WindowManager.LayoutParams();
            toastParams.type = android.view.WindowManager.LayoutParams.TYPE_TOAST;
            toastRoot.setLayoutParams(toastParams);
            activity.getWindowManager().addView(toastRoot, toastParams);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            SaveNotice.windowRootsForTests = java.util.List.of(
                    activity.getWindow().getDecorView(), sheetDecor, toastRoot);

            SaveNotice.saved("Story saved to DCIM/TikTok",
                    new MediaFileWriter.Saved("a.mp4", Uri.parse("content://media/external/video/media/10")));
            idlePastTheSheetSettle();

            assertNull("the banner landed on a toast window", find(toastRoot, "Open"));
            assertNotNull("the banner missed the window under the toast", find(sheetDecor, "Open"));
            sheet.dismiss();
        }
    }

    /** The banner waits out TikTok's share-sheet swap before it picks a window. */
    private static void idlePastTheSheetSettle() {
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(SaveNotice.SHEET_SETTLE_MS + 100));
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = find(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
