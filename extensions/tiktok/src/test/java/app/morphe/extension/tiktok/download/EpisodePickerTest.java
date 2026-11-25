/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.ListView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Save episodes (#444): the drama's episodes as a checklist with the one on screen checked, the
 * picked ones saved as one run, and a run never more than a grid pick holds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en")
public class EpisodePickerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** An episode's post as the share sheet hands it over: its id and the drama's paid struct. */
    public static final class Post {
        public final DramaEpisodesTest.Paid mPaidContentInfo;
        private final String aid;

        Post(String aid, long collection) {
            this.aid = aid;
            mPaidContentInfo = new DramaEpisodesTest.Paid(collection, collection == 0 ? "" : "Night Shift");
        }
        public String getAid() { return aid; }
        @Override public String toString() { return "post " + aid; }
    }

    private final List<List<Object>> runs = new ArrayList<>();
    private final List<Long> loaded = new ArrayList<>();
    private List<DramaEpisodes.Episode> drama;
    private Exception loadFailure;
    private boolean started = true;
    private Activity activity;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        Utils.setContext(activity.getApplicationContext());
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_EPISODES_BUTTON.save(true);
        EpisodePicker.loader = collection -> {
            loaded.add(collection);
            if (loadFailure != null) throw loadFailure;
            return drama;
        };
        EpisodePicker.starter = (context, posts) -> {
            runs.add(new ArrayList<>(posts));
            return started;
        };
        EpisodePicker.worker = task -> {
            task.run();
            return true;
        };
        ShadowToast.reset();
    }

    @After public void tearDown() {
        AlertDialog open = ShadowAlertDialog.getLatestAlertDialog();
        if (open != null) open.dismiss();
        EpisodePicker.resetForTests();
        Settings.SAVE_EPISODES_BUTTON.resetToDefault();
        SettingsStatus.advancedDownloadsEnabled = false;
        PausedProcess.set(false);
        activity.finish();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static DramaEpisodes.Episode episode(String id, int number, boolean preview) {
        return episode(id, number, preview, false);
    }

    private static DramaEpisodes.Episode episode(String id, int number, boolean preview, boolean locked) {
        return new DramaEpisodes.Episode(new Post(id, 7431100L), id, number, preview, locked);
    }

    private static List<DramaEpisodes.Episode> episodes(int count) {
        List<DramaEpisodes.Episode> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(episode("e" + i, i, false));
        return list;
    }

    /** Opens the checklist from an episode's Save episodes and hands it back, its rows laid out. */
    private AlertDialog open(String onScreen) {
        EpisodePicker.open(activity, new Post(onScreen, 7431100L));
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no checklist", dialog);
        assertTrue(dialog.isShowing());
        ListView list = dialog.getListView();
        for (int i = 0; i < list.getCount(); i++) list.getAdapter().getView(i, null, list);
        return dialog;
    }

    private static void tapRow(AlertDialog dialog, int row) {
        ListView list = dialog.getListView();
        assertTrue(list.performItemClick(list.getAdapter().getView(row, null, list), row, row));
        idle();
    }

    private static void tap(AlertDialog dialog, int which) {
        assertTrue(dialog.getButton(which).performClick());
        idle();
    }

    @Test public void eachRowSaysItsNumberAndWhetherItsAPreviewOrOnScreen() {
        List<DramaEpisodes.Episode> list = Arrays.asList(episode("a", 1, false), episode("b", 2, true),
                episode("c", 3, false), episode("d", 4, false, true), episode("e", 5, true, true));
        assertArrayEquals(new String[]{"Episode 1", "Episode 2 · Preview only", "Episode 3 · On screen",
                        "Episode 4 · Locked", "Episode 5 · Locked"},
                EpisodePicker.labels(activity, list, "c"));
        assertEquals("Pick episodes to save\nNight Shift", EpisodePicker.title(activity, "Night Shift"));
        assertEquals("Pick episodes to save", EpisodePicker.title(activity, null));
    }

    @Test public void onlyAnEpisodeOfADramaCounts() {
        assertTrue(EpisodePicker.isEpisode(new Post("a", 7431100L)));
        assertFalse("an ordinary video's empty struct", EpisodePicker.isEpisode(new Post("a", 0L)));
        assertFalse(EpisodePicker.isEpisode(null));
        assertTrue(EpisodePicker.enabled());
        PausedProcess.set(true);
        assertFalse(EpisodePicker.enabled());
        PausedProcess.set(false);
        Settings.SAVE_EPISODES_BUTTON.save(false);
        assertFalse("the switch is off", EpisodePicker.enabled());
        Settings.SAVE_EPISODES_BUTTON.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        assertFalse(EpisodePicker.enabled());
    }

    @Test public void theEpisodeOnScreenStartsCheckedAndSaveStartsOneRunOfThePicks() {
        drama = episodes(4);
        AlertDialog dialog = open("e2");
        assertEquals(List.of(7431100L), loaded);
        assertTrue("no Loading episodes toast", ShadowToast.showedToast("Loading episodes"));
        ListView list = dialog.getListView();
        assertEquals(4, list.getCount());
        assertTrue(list.isItemChecked(1));
        assertFalse(list.isItemChecked(0));

        tapRow(dialog, 3);
        tap(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals(1, runs.size());
        assertEquals(Arrays.asList(drama.get(1).post, drama.get(3).post), runs.get(0));
        assertFalse(dialog.isShowing());
    }

    @Test public void pickAllChecksEveryEpisodeAndNothingPickedSavesNothing() {
        drama = episodes(3);
        AlertDialog dialog = open("e1");
        tapRow(dialog, 0);
        tap(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(runs.isEmpty());
        assertTrue("Save with nothing picked closed the list", dialog.isShowing());
        assertEquals("Pick at least one episode", ShadowToast.getTextOfLatestToast());

        tap(dialog, DialogInterface.BUTTON_NEUTRAL);
        assertTrue(dialog.isShowing());
        tap(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals(1, runs.size());
        assertEquals(3, runs.get(0).size());
    }

    /** #463: an episode TikTok sent no video for is never checked, by a tap, Pick all or being on screen. */
    @Test public void aLockedEpisodeCantBeCheckedAndPickAllLeavesItOut() {
        drama = Arrays.asList(episode("e1", 1, false), episode("e2", 2, false, true), episode("e3", 3, true, true));
        AlertDialog dialog = open("e2");
        ListView list = dialog.getListView();
        assertFalse("the locked episode on screen started checked", list.isItemChecked(1));

        tapRow(dialog, 2);
        assertFalse("a tap checked a locked episode", list.isItemChecked(2));
        tap(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue("a locked episode was saved", runs.isEmpty());
        assertEquals("Pick at least one episode", ShadowToast.getTextOfLatestToast());

        tap(dialog, DialogInterface.BUTTON_NEUTRAL);
        assertTrue(list.isItemChecked(0));
        assertFalse("Pick all checked a locked episode", list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));
        tap(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals(List.of(List.of(drama.get(0).post)), runs);
    }

    @Test public void moreThanOneRunsWorthIsSavedInTurns() {
        List<DramaEpisodes.Episode> list = episodes(GridPicker.MAX_PICKED + 10);
        boolean[] checked = new boolean[list.size()];
        Arrays.fill(checked, true);

        assertFalse(EpisodePicker.save(activity, list, checked, null));
        assertEquals(GridPicker.MAX_PICKED, runs.get(0).size());
        assertSame(list.get(0).post, runs.get(0).get(0));
        assertFalse("a started episode stays checked", checked[GridPicker.MAX_PICKED - 1]);
        assertTrue(checked[GridPicker.MAX_PICKED]);
        idle();
        assertEquals("Saving 50. Tap Save again for the rest.", ShadowToast.getTextOfLatestToast());

        assertTrue(EpisodePicker.save(activity, list, checked, null));
        assertEquals(10, runs.get(1).size());
        assertSame(list.get(GridPicker.MAX_PICKED).post, runs.get(1).get(0));
    }

    @Test public void aRunThatCouldntStartLeavesThePicksAsTheyWere() {
        started = false;
        List<DramaEpisodes.Episode> list = episodes(2);
        boolean[] checked = {true, true};
        assertFalse(EpisodePicker.save(activity, list, checked, null));
        assertTrue(checked[0] && checked[1]);
    }

    @Test public void aListThatWontLoadSaysSoAndShowsNoChecklist() {
        loadFailure = new IOException("TikTok said 2483");
        EpisodePicker.open(activity, new Post("e1", 7431100L));
        idle();
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals("Couldn't load the episodes. Try again.", ShadowToast.getTextOfLatestToast());

        loadFailure = null;
        drama = new ArrayList<>();
        EpisodePicker.open(activity, new Post("e1", 7431100L));
        idle();
        assertNull("an empty drama got a checklist", ShadowAlertDialog.getLatestAlertDialog());
        assertEquals("the first load let go of the second", 2, loaded.size());
    }

    @Test public void anOrdinaryVideoLoadsNothing() {
        EpisodePicker.open(activity, new Post("e1", 0L));
        EpisodePicker.open(activity, null);
        idle();
        assertTrue(loaded.isEmpty());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void aSecondTapWhileLoadingLoadsOnce() {
        List<Runnable> pending = new ArrayList<>();
        EpisodePicker.worker = task -> pending.add(task);
        drama = episodes(2);
        EpisodePicker.open(activity, new Post("e1", 7431100L));
        EpisodePicker.open(activity, new Post("e1", 7431100L));
        assertEquals(1, pending.size());
        pending.get(0).run();
        idle();
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
    }
}
