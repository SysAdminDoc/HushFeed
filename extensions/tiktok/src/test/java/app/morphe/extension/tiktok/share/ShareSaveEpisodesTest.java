/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Save episodes in a short drama episode's share sheet (#444), on the same carrier as Save cover. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ShareSaveEpisodesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's paid content struct: the drama's id and name, both at defaults on an ordinary video. */
    public static final class Paid {
        public long paidCollectionId;
        public String collectionName;

        Paid(long paidCollectionId, String collectionName) {
            this.paidCollectionId = paidCollectionId;
            this.collectionName = collectionName;
        }
    }

    /** A post with TikTok's paid content struct on it, the way an episode and a For You video both carry one. */
    public static final class Episode extends Aweme {
        public Object mPaidContentInfo;
        public final ShareSaveCoverTest.Video video = new ShareSaveCoverTest.Video();
        private final String id;

        Episode(String id, long collection) {
            this.id = id;
            mPaidContentInfo = new Paid(collection, collection == 0 ? "" : "Night Shift");
            video.cover = new ShareSaveCoverTest.UrlModel("https://p16.example/" + id + ".jpeg");
        }
        public ShareSaveCoverTest.Video getVideo() { return video; }
        @Override public String getAid() { return id; }
    }

    private final List<Object> opened = new ArrayList<>();
    private Activity activity;
    private int constructed;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_EPISODES_BUTTON.save(true);
        ShareSaveEpisodes.nativeForTests = (from, post) -> {
            assertSame(activity, from);
            constructed++;
            return new ShareSaveCoverTest.Action("report");
        };
        ShareSaveEpisodes.openerForTests = (context, post) -> opened.add(post);
    }

    @After public void tearDown() {
        ShareSaveEpisodes.nativeForTests = null;
        ShareSaveEpisodes.openerForTests = null;
        ShareMakeSticker.nativeForTests = null;
        ((List<?>) ReflectionHelpers.getStaticField(ShareSaveEpisodes.class, "BUILT")).clear();
        ((List<?>) ReflectionHelpers.getStaticField(ShareMakeSticker.class, "BUILT")).clear();
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.SAVE_EPISODES_BUTTON.resetToDefault();
        SettingsStatus.advancedDownloadsEnabled = false;
        SettingsStatus.stickerCreatorEnabled = false;
        SettingsStatus.shareSheetEnabled = false;
        PausedProcess.set(false);
        activity.finish();
    }

    private static List<Object> row() {
        return new ArrayList<>(Arrays.asList(new ShareSaveCoverTest.Action("report"),
                new ShareSaveCoverTest.Action("save"), new ShareSaveCoverTest.Action("duet")));
    }

    private static AwemeSharePackage episode(String id) { return new AwemeSharePackage(new Episode(id, 7_431_100L)); }

    private static String key(Object action) { return ((ShareSaveCoverTest.Action) action).key(); }

    @Test public void anEpisodeGetsTheButtonRightAfterDownloadOnce() {
        AwemeSharePackage sheet = episode("1");
        List<Object> nativeRow = row();
        List<?> finished = ShareSaveEpisodes.actions(sheet, nativeRow);
        assertEquals(3, nativeRow.size());
        assertEquals(4, finished.size());
        assertEquals("save", key(finished.get(1)));
        assertEquals(ShareSaveEpisodes.KEY, key(finished.get(2)));
        assertEquals("duet", key(finished.get(3)));
        assertSame(finished, ShareSaveEpisodes.actions(sheet, finished));
        assertEquals(1, constructed);
        assertEquals("Save episodes", OwnedShareActions.label(finished.get(2)));
        assertTrue(ShareActionCatalog.entries(ShareSurface.VIDEO).stream().anyMatch(entry ->
                entry.key.equals("hushfeed save episodes") && entry.label.equals("Save episodes")));
    }

    @Test public void withMakeStickerOnItFollowsMakeSticker() {
        SettingsStatus.stickerCreatorEnabled = true;
        ShareMakeSticker.nativeForTests = (from, post) -> new ShareSaveCoverTest.Action("report");
        AwemeSharePackage sheet = episode("2");
        List<?> finished = ShareSaveEpisodes.actions(sheet, ShareMakeSticker.actions(sheet, row()));
        assertEquals(5, finished.size());
        assertEquals(ShareMakeSticker.KEY, key(finished.get(2)));
        assertEquals(ShareSaveEpisodes.KEY, key(finished.get(3)));
        assertEquals("duet", key(finished.get(4)));
    }

    @Test public void onlyADramaEpisodeGetsTheButton() {
        List<Object> nativeRow = row();
        assertSame("TikTok hangs an empty paid struct on ordinary videos", nativeRow,
                ShareSaveEpisodes.actions(new AwemeSharePackage(new Episode("3", 0L)), nativeRow));
        assertSame("a post with no struct at all", nativeRow,
                ShareSaveEpisodes.actions(new AwemeSharePackage(new ShareSaveCoverTest.Post("4", true)), nativeRow));
        AwemeSharePackage profile = episode("5");
        profile.itemType = "user";
        assertSame(nativeRow, ShareSaveEpisodes.actions(profile, nativeRow));
        assertSame(nativeRow, ShareSaveEpisodes.actions(new AwemeSharePackage(new Object()), nativeRow));
        assertSame(nativeRow, ShareSaveEpisodes.actions(null, nativeRow));
        assertNull(ShareSaveEpisodes.actions(episode("5"), null));
        assertEquals(0, constructed);
    }

    @Test public void switchedOffUnpatchedPausedAndHiddenGetNoButton() {
        List<Object> nativeRow = row();
        Settings.SAVE_EPISODES_BUTTON.resetToDefault();
        assertSame("the switch starts off", nativeRow, ShareSaveEpisodes.actions(episode("6"), nativeRow));
        Settings.SAVE_EPISODES_BUTTON.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        assertSame("needs Advanced downloads", nativeRow, ShareSaveEpisodes.actions(episode("6"), nativeRow));
        SettingsStatus.advancedDownloadsEnabled = true;
        PausedProcess.set(true);
        assertSame(nativeRow, ShareSaveEpisodes.actions(episode("6"), nativeRow));
        PausedProcess.set(false);

        SettingsStatus.shareSheetEnabled = true;
        Settings.SHARE_HIDDEN_ITEMS.save("Save episodes");
        assertSame("hidden with Share sheet tools", nativeRow, ShareSaveEpisodes.actions(episode("7"), nativeRow));
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.HIDE_SHARE_ACTIONS.save(true);
        assertSame(nativeRow, ShareSaveEpisodes.actions(episode("7"), nativeRow));
        assertEquals(0, constructed);
    }

    @Test public void aTapOpensThePickerForThatSheetsEpisode() {
        AwemeSharePackage sheet = episode("8");
        Object button = ShareSaveEpisodes.actions(sheet, row()).get(2);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals(List.of(sheet.aweme), opened);

        assertFalse("TikTok's own Report keeps its click",
                OwnedShareActions.click(new ShareSaveCoverTest.Action("report"), activity, sheet));
        assertTrue("another sheet's tap is taken but opens nothing",
                OwnedShareActions.click(button, activity, episode("9")));
        assertEquals(1, opened.size());
    }

    @Test public void pausingTakesABuiltButtonBackOut() {
        AwemeSharePackage sheet = episode("10");
        List<?> finished = ShareSaveEpisodes.actions(sheet, row());
        Object button = finished.get(2);
        PausedProcess.set(true);
        List<?> after = ShareSaveEpisodes.actions(sheet, finished);
        assertEquals(3, after.size());
        assertFalse(after.contains(button));
        PausedProcess.set(false);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertTrue("a retired button opens nothing", opened.isEmpty());
    }

    @Test public void theIconIsDrawnOnlyOnTheButtonInsideTikToksFrame() {
        Object button = ShareSaveEpisodes.actions(episode("11"), row()).get(2);
        ImageView image = new ImageView(activity);
        ColorDrawable nativeIcon = new ColorDrawable();
        image.setImageDrawable(nativeIcon);
        OwnedShareActions.bindIcon(new ShareSaveCoverTest.Action("report"), image);
        assertSame(nativeIcon, image.getDrawable());
        OwnedShareActions.bindIcon(button, image);
        assertTrue(image.getDrawable() instanceof EpisodesGlyphDrawable);
        assertEquals("report", OwnedShareActions.framedKey(button, ShareSaveEpisodes.KEY));
    }
}
