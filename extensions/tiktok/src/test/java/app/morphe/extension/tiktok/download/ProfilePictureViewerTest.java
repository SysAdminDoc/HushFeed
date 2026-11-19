package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

/**
 * #467: with Show the picture before saving on, holding a profile picture shows it full screen
 * first, and only its Save button saves it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en")
public class ProfilePictureViewerTest {
    private final CountDownLatch release = new CountDownLatch(1);
    private ActivityController<Activity> owner;
    private View avatar;
    private final AtomicReference<List<String>> asked = new AtomicReference<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_PROFILE_PICTURE.save(true);
        Settings.PROFILE_PICTURE_VIEWER.save(true);
        owner = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = owner.get();
        LinearLayout header = new LinearLayout(activity);
        TextView handle = new TextView(activity);
        handle.setText("@dancer");
        header.addView(handle);
        avatar = new View(activity);
        header.addView(avatar);
        activity.setContentView(header);
        ProfileAvatarSaver.recordProfileResponse(new ProfileAvatarPermissionTest.ProfileResponse());
        ProfileAvatarSaver.attachAvatar(avatar);

        // Hold the media workers so a save that gets asked for stays visible in the queue.
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            assertTrue(MediaJobScheduler.submit("viewer test hold", () -> {
                started.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue("the media workers never started", started.await(5, TimeUnit.SECONDS));
        ShadowDialog.reset();
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        ProfilePictureViewer.loaderForTests = null;
        release.countDown();
        for (int wait = 0; wait < 500
                && (MediaJobScheduler.runningJobs() != 0 || MediaJobScheduler.queuedJobs() != 0); wait++) {
            Thread.sleep(20);
        }
        Settings.PROFILE_PICTURE_VIEWER.resetToDefault();
        Settings.SAVE_PROFILE_PICTURE.save(false);
        SettingsStatus.advancedDownloadsEnabled = false;
        if (owner != null) owner.close();
        assertEquals("a test download was left queued", 0, MediaJobScheduler.queuedJobs());
    }

    @Test public void holdingShowsThePictureAndOnlySaveSavesIt() throws Exception {
        Bitmap picture = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888);
        CountDownLatch loaded = loadWith(urls -> picture);

        assertTrue(avatar.performLongClick());
        Dialog dialog = shownViewer(loaded);
        assertEquals(List.of("https://127.0.0.1:1/avatar.jpg"), asked.get());
        assertEquals("nothing is saved just for looking", 0, MediaJobScheduler.queuedJobs());
        assertEquals(0, ShadowToast.shownToastCount());

        ImageView image = find(dialog.getWindow().getDecorView(), ImageView.class);
        assertSame(picture, ((BitmapDrawable) image.getDrawable()).getBitmap());
        assertEquals(View.GONE, find(dialog.getWindow().getDecorView(), ProgressBar.class).getVisibility());

        Button save = find(dialog.getWindow().getDecorView(), Button.class);
        assertEquals("Save", save.getText().toString());
        assertTrue(save.performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertEquals(1, MediaJobScheduler.queuedJobs());
        assertEquals("Saving the profile picture\nStarts after one other save", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aTapOnThePictureClosesItWithoutSaving() throws Exception {
        CountDownLatch loaded = loadWith(urls -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        assertTrue(avatar.performLongClick());
        Dialog dialog = shownViewer(loaded);

        View image = find(dialog.getWindow().getDecorView(), ImageView.class);
        assertTrue(((View) image.getParent()).performClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertEquals(0, MediaJobScheduler.queuedJobs());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void aPictureThatWontLoadClosesAndSaysSo() throws Exception {
        CountDownLatch loaded = loadWith(urls -> {
            throw new IOException("no network in the test");
        });
        assertTrue(avatar.performLongClick());
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(loaded.await(5, TimeUnit.SECONDS));
        for (int wait = 0; wait < 250 && dialog.isShowing(); wait++) {
            Thread.sleep(20);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertFalse(dialog.isShowing());
        assertEquals("The profile picture couldn't be loaded. Try again.", ShadowToast.getTextOfLatestToast());
        assertEquals(0, MediaJobScheduler.queuedJobs());
    }

    @Test public void withTheViewerOffHoldingSavesAtOnce() {
        Settings.PROFILE_PICTURE_VIEWER.save(false);
        loadWith(urls -> {
            throw new AssertionError("the viewer loaded a picture while it was off");
        });
        assertTrue(avatar.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowDialog.getLatestDialog());
        assertEquals(1, MediaJobScheduler.queuedJobs());
    }

    @Test public void aHugePictureIsDecodedSmallEnoughToDraw() {
        assertEquals(1, ProfilePictureViewer.sampleSize(1080, 1080));
        assertEquals(1, ProfilePictureViewer.sampleSize(2048, 1000));
        assertEquals(2, ProfilePictureViewer.sampleSize(4000, 3000));
        assertEquals(4, ProfilePictureViewer.sampleSize(3000, 8000));
    }

    private interface Load {
        Bitmap load(List<String> urls) throws IOException;
    }

    private CountDownLatch loadWith(Load load) {
        CountDownLatch done = new CountDownLatch(1);
        ProfilePictureViewer.loaderForTests = (app, urls) -> {
            asked.set(urls);
            try {
                return load.load(urls);
            } finally {
                done.countDown();
            }
        };
        return done;
    }

    /** The viewer once its picture is on screen. */
    private static Dialog shownViewer(CountDownLatch loaded) throws Exception {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("holding the picture opened nothing", dialog);
        assertTrue(dialog.isShowing());
        assertTrue(loaded.await(5, TimeUnit.SECONDS));
        ImageView image = find(dialog.getWindow().getDecorView(), ImageView.class);
        for (int wait = 0; wait < 250 && image.getDrawable() == null; wait++) {
            Thread.sleep(20);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertNotNull("the picture never showed", image.getDrawable());
        return dialog;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = find(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
