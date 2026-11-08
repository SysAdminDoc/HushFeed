/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.os.Looper;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Check for already-saved videos covers the original sound too (#356): a sound saved before is
 * offered Open or Save again rather than fetched a second time, whichever video it is saved from,
 * since the record keeps it under the sound's own id.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class OriginalSoundAlreadySavedTest {
    private static final String SOUND_URL = "https://v16.tiktokcdn.com/sound/one";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<String> fetched = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> ownIds = new HashSet<>();
    private Context context;
    private boolean previousCheck;
    private ActivityController<SaveNoticeTest.HostActivity> owner;
    private Set<String> before;

    @SuppressWarnings("unused")
    public static final class Music {
        private final String mid;

        Music(String mid) { this.mid = mid; }

        public String getMid() { return mid; }
        public String getTitle() { return "Sound " + mid; }
        public Object getPlayUrl() { return new PlayUrl(); }
    }

    @SuppressWarnings("unused")
    public static final class PlayUrl {
        public List<String> getUrlList() { return List.of(SOUND_URL); }
    }

    @SuppressWarnings("unused")
    public static final class Post {
        private final String aid;
        private final Music music;

        Post(String aid, Music music) {
            this.aid = aid;
            this.music = music;
        }

        public String getAid() { return aid; }
        public Object getMusic() { return music; }
    }

    @Before public void setUp() throws Exception {
        drainPool();
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Utils.awaitBackgroundTasksForTests();
        SavedVideoArchiveAccess.reset(context);
        previousCheck = Settings.CHECK_SAVED_VIDEOS.savedValue();
        Settings.CHECK_SAVED_VIDEOS.save(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        byte[] m4a = new byte[32];
        System.arraycopy(new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, 0, m4a, 0, 12);
        RemoteMedia.transportForTests = MediaTransportFixtures.publicClient(url -> {
            fetched.add(url.toString());
            return MediaTransportFixtures.response(url, 200, m4a);
        });
        SaveNotice.windowRootsForTests = List.of();
        owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        Utils.setActivity(owner.get());
        before = listed(Environment.getExternalStorageDirectory());
    }

    @After public void tearDown() throws Exception {
        try {
            drainPool();
            idle();
        } finally {
            RemoteMedia.transportForTests = null;
            SaveNotice.windowRootsForTests = null;
            Utils.setActivity(null);
            owner.close();
            Set<String> added = listed(Environment.getExternalStorageDirectory());
            added.removeAll(before);
            for (String path : added) new File(path).delete();
            Set<String> active = ReflectionHelpers.getStaticField(OriginalSoundDownloads.class, "ACTIVE");
            active.removeAll(ownIds);
            Settings.CHECK_SAVED_VIDEOS.save(previousCheck);
            SavedVideoArchiveAccess.reset(context);
        }
    }

    @Test public void aSoundSavedBeforeIsOfferedEvenFromAnotherVideo() throws Exception {
        OriginalSoundDownloads.start(post("sound-video-1", "6801"), owner.get());
        finish();
        assertEquals(List.of(SOUND_URL), fetched);
        assertNotNull("the sound never reached the record", SavedVideoArchive.find(context, "sound:6801"));
        assertNull("the sound was recorded as its video", SavedVideoArchive.find(context, "sound-video-1"));

        OriginalSoundDownloads.start(post("sound-video-2", "6801"), owner.get());
        finish();
        assertEquals("the sound was fetched again with no word", 1, fetched.size());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no already-saved choice came up", dialog);
        assertTrue(dialog.isShowing());
        assertEquals("This sound is already saved", Shadows.shadowOf(dialog).getTitle().toString());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        finish();
        assertEquals("Save again saves it", 2, fetched.size());
    }

    @Test public void savingIsSaidOnlyOnceTheCheckFindsNothing() throws Exception {
        ShadowToast.reset();
        OriginalSoundDownloads.start(post("sound-video-6", "6805"), owner.get());
        finish();
        assertTrue("a new sound said nothing as it saved", ShadowToast.showedToast("Saving the sound"));

        ShadowToast.reset();
        OriginalSoundDownloads.start(post("sound-video-7", "6805"), owner.get());
        finish();
        assertNotNull("no already-saved choice came up", ShadowAlertDialog.getLatestAlertDialog());
        assertFalse("a sound that was never fetched said it was saving",
                ShadowToast.showedToast("Saving the sound"));
    }

    @Test public void aDifferentSoundFromTheSameVideoIsNotTakenForIt() throws Exception {
        OriginalSoundDownloads.start(post("sound-video-3", "6802"), owner.get());
        finish();
        OriginalSoundDownloads.start(post("sound-video-3", "6803"), owner.get());
        finish();
        assertEquals(2, fetched.size());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void withTheCheckOffTheSoundIsSavedEveryTimeAndNotRecorded() throws Exception {
        Settings.CHECK_SAVED_VIDEOS.save(false);
        OriginalSoundDownloads.start(post("sound-video-4", "6804"), owner.get());
        finish();
        OriginalSoundDownloads.start(post("sound-video-5", "6804"), owner.get());
        finish();
        assertEquals(2, fetched.size());
        assertNull(SavedVideoArchive.find(context, "sound:6804"));
    }

    private Post post(String aid, String mid) {
        ownIds.add(aid);
        return new Post(aid, new Music(mid));
    }

    private static Set<String> listed(File root) {
        Set<String> paths = new HashSet<>();
        File[] children = root == null ? null : root.listFiles();
        if (children == null) return paths;
        for (File child : children) {
            if (child.isDirectory()) paths.addAll(listed(child));
            else paths.add(child.getAbsolutePath());
        }
        return paths;
    }

    /** Until neither the media pool nor the main looper has work left. */
    private void finish() throws Exception {
        do {
            drainPool();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
            idle();
        } while (!MediaJobScheduler.idle());
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void drainPool() throws Exception {
        for (int wait = 0; wait < 500; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("The media pool never emptied");
    }
}
