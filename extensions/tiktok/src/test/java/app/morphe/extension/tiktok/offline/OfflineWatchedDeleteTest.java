package app.morphe.extension.tiktok.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Delete watched offline videos: at each start the watched ids TikTok answers go to TikTok's own
 * delete, unless an offline video was played this session, and only once per process.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OfflineWatchedDeleteTest {
    private static final String TITLE = "Delete watched offline videos";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    /** TikTok's side, as the patch wires it. */
    private static final class FakeHost implements OfflineWatchedDelete.Host {
        Object watched;
        Object unwatched;
        boolean consumed;
        final List<List<?>> deleted = new ArrayList<>();

        @Override public Object watchedAids() {
            return watched;
        }

        @Override public void deleteNow(List<?> aids) {
            deleted.add(aids);
        }

        @Override public Object unwatchedCount() {
            return unwatched;
        }

        @Override public boolean consumedThisSession() {
            return consumed;
        }
    }

    @After
    public void tearDown() {
        OfflineWatchedDelete.resetForTests();
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.DELETE_WATCHED_OFFLINE_VIDEOS.resetToDefault();
    }

    @Test
    public void theWatchedIdsGoToTikToksDelete() {
        FakeHost host = install();
        List<String> watched = Arrays.asList("7301", "7302");
        host.watched = watched;

        assertEquals(2, OfflineWatchedDelete.deleteWatched());
        assertEquals(1, host.deleted.size());
        assertTrue("the delete got another list", host.deleted.get(0) == watched);
        assertReported();
    }

    @Test
    public void nothingWatchedDeletesNothing() {
        FakeHost host = install();
        host.watched = Collections.emptyList();
        assertEquals(0, OfflineWatchedDelete.deleteWatched());
        // No account answers null on some builds, and an unexpected answer is left alone too.
        host.watched = null;
        assertEquals(0, OfflineWatchedDelete.deleteWatched());
        host.watched = "7301";
        assertEquals(0, OfflineWatchedDelete.deleteWatched());
        assertTrue("an empty or odd answer reached the delete", host.deleted.isEmpty());
        assertReported();
    }

    /** TikTok skips its own clean-up once an offline video was played, so the one on screen keeps its file. */
    @Test
    public void aVideoPlayedOfflineThisSessionKeepsEverything() {
        FakeHost host = install();
        host.watched = Arrays.asList("7301");
        host.consumed = true;
        assertEquals(0, OfflineWatchedDelete.deleteWatched());
        assertTrue(host.deleted.isEmpty());
        assertReported();
    }

    /** The step comes back through its own entry when it resumes, so the hook runs it once per process. */
    @Test
    public void theStartUpHookDeletesOncePerProcess() throws Exception {
        FakeHost host = install();
        host.watched = Arrays.asList("7301");
        Settings.DELETE_WATCHED_OFFLINE_VIDEOS.save(true);

        offMainThread(OfflineWatchedDelete::onBootCleanup);
        offMainThread(OfflineWatchedDelete::onBootCleanup);
        assertEquals(1, host.deleted.size());
    }

    @Test
    public void theSwitchStartsOffAndOffDeletesNothing() throws Exception {
        assertEquals(Boolean.FALSE, Settings.DELETE_WATCHED_OFFLINE_VIDEOS.defaultValue);
        assertTrue(Settings.DELETE_WATCHED_OFFLINE_VIDEOS.rebootApp);
        FakeHost host = install();
        host.watched = Arrays.asList("7301");

        offMainThread(OfflineWatchedDelete::onBootCleanup);
        assertTrue("deleted with the switch off", host.deleted.isEmpty());

        Settings.DELETE_WATCHED_OFFLINE_VIDEOS.save(true);
        PausedProcess.set(true);
        offMainThread(OfflineWatchedDelete::onBootCleanup);
        assertTrue("a paused Hushfeed still deleted", host.deleted.isEmpty());
    }

    @Test
    public void theCountIsTikToksBoxedAnswerOrNothing() {
        FakeHost host = install();
        host.unwatched = 12;
        assertEquals(Integer.valueOf(12), OfflineWatchedDelete.unwatchedCountOrNull());
        host.unwatched = null;
        assertNull(OfflineWatchedDelete.unwatchedCountOrNull());
        host.unwatched = 12L;
        assertNull("a non-Integer answer was read as a count", OfflineWatchedDelete.unwatchedCountOrNull());
    }

    /** The row shows only where the patch found the clean-up, beside the other offline rows. */
    @Test
    public void theRowShowsOnlyWhereTheCleanUpWasHooked() {
        boolean offline = SettingsStatus.customOfflineVideosEnabled;
        boolean watched = SettingsStatus.offlineWatchedDeleteEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.customOfflineVideosEnabled = true;

            SettingsStatus.offlineWatchedDeleteEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new DownloadsPreferenceCategory(activity, without);
            assertNotNull(find(without, "Offline videos limit"));
            assertNull("the row showed on a build where the clean-up wasn't hooked", find(without, TITLE));

            SettingsStatus.offlineWatchedDeleteEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new DownloadsPreferenceCategory(activity, with);
            Preference row = find(with, TITLE);
            assertNotNull(row);
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.startsWith("Each time TikTok starts, deletes the offline videos"));
            assertTrue("the restart note is missing: " + summary, summary.contains("Restart TikTok"));
            assertFalse("the row turned the switch on", Settings.DELETE_WATCHED_OFFLINE_VIDEOS.get());
        } finally {
            SettingsStatus.customOfflineVideosEnabled = offline;
            SettingsStatus.offlineWatchedDeleteEnabled = watched;
        }
    }

    /** The diagnostics report counts the family; the outcome's name is what hookdetails prints on a phone. */
    private static void assertReported() {
        List<String> report = HookStatus.report();
        assertTrue(String.valueOf(report), report.contains("offline watched delete: 1 found, 0 missing"));
    }

    private static FakeHost install() {
        FakeHost host = new FakeHost();
        OfflineWatchedDelete.hostForTests = host;
        return host;
    }

    /** The start-up task's thread, which is never the main one. */
    private static void offMainThread(Runnable work) throws InterruptedException {
        Thread thread = new Thread(work);
        thread.start();
        thread.join();
    }

    private static Preference find(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference.getTitle() != null && title.contentEquals(preference.getTitle())) return preference;
            if (preference instanceof PreferenceGroup) {
                Preference nested = find((PreferenceGroup) preference, title);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
