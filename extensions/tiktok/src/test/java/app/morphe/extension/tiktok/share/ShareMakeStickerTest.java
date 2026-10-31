/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

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

/** Make sticker in a video's share sheet (#88), on the same native carrier as Save cover. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ShareMakeStickerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<Object> made = new ArrayList<>();
    private Activity activity;
    private int constructed;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        SettingsStatus.stickerCreatorEnabled = true;
        ShareMakeSticker.nativeForTests = (from, post) -> {
            assertSame(activity, from);
            assertTrue(post instanceof ShareSaveCoverTest.Post);
            constructed++;
            return new ShareSaveCoverTest.Action("report");
        };
        ShareMakeSticker.makerForTests = (Context context, Object post) -> made.add(post);
    }

    @After public void tearDown() {
        ShareMakeSticker.nativeForTests = null;
        ShareMakeSticker.makerForTests = null;
        ShareSaveCover.nativeForTests = null;
        ((List<?>) ReflectionHelpers.getStaticField(ShareMakeSticker.class, "BUILT")).clear();
        ((List<?>) ReflectionHelpers.getStaticField(ShareSaveCover.class, "BUILT")).clear();
        Settings.SAVE_COVER_BUTTON.resetToDefault();
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.SHARE_HIDDEN_ITEMS.save("");
        SettingsStatus.stickerCreatorEnabled = false;
        SettingsStatus.advancedDownloadsEnabled = false;
        SettingsStatus.shareSheetEnabled = false;
        PausedProcess.set(false);
        activity.finish();
    }

    private static List<Object> row() {
        return new ArrayList<>(Arrays.asList(new ShareSaveCoverTest.Action("report"),
                new ShareSaveCoverTest.Action("save"), new ShareSaveCoverTest.Action("duet")));
    }

    private static AwemeSharePackage sheet(String id) { return new AwemeSharePackage(new ShareSaveCoverTest.Post(id, true)); }

    private static String key(Object action) { return ((ShareSaveCoverTest.Action) action).key(); }

    @Test public void goesRightAfterDownloadOnceAndKeepsTheNativeRowAsItWas() {
        AwemeSharePackage sheet = sheet("1");
        List<Object> nativeRow = row();
        List<?> finished = ShareMakeSticker.actions(sheet, nativeRow);
        assertEquals(3, nativeRow.size());
        assertEquals(4, finished.size());
        assertSame(nativeRow.get(1), finished.get(1));
        assertEquals(ShareMakeSticker.KEY, key(finished.get(2)));
        assertSame(nativeRow.get(2), finished.get(3));
        assertSame(finished, ShareMakeSticker.actions(sheet, finished));
        assertEquals(1, constructed);
        assertEquals("Make sticker", OwnedShareActions.label(finished.get(2)));
        assertTrue(ShareActionCatalog.entries(ShareSurface.VIDEO).stream().anyMatch(entry ->
                entry.key.equals("hushfeed make sticker") && entry.label.equals("Make sticker")));
    }

    @Test public void withSaveCoverOnItFollowsSaveCover() {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_COVER_BUTTON.save(true);
        ShareSaveCover.nativeForTests = (from, post) -> new ShareSaveCoverTest.Action("report");
        AwemeSharePackage sheet = sheet("2");
        List<?> finished = ShareMakeSticker.actions(sheet, ShareSaveCover.actions(sheet, row()));
        assertEquals(5, finished.size());
        assertEquals("save", key(finished.get(1)));
        assertEquals(ShareSaveCover.KEY, key(finished.get(2)));
        assertEquals(ShareMakeSticker.KEY, key(finished.get(3)));
        assertEquals("duet", key(finished.get(4)));
    }

    @Test public void withoutDownloadInTheRowItGoesLast() {
        List<Object> nativeRow = new ArrayList<>(Arrays.asList(
                new ShareSaveCoverTest.Action("report"), new ShareSaveCoverTest.Action("duet")));
        List<?> finished = ShareMakeSticker.actions(sheet("3"), nativeRow);
        assertEquals(3, finished.size());
        assertEquals(ShareMakeSticker.KEY, key(finished.get(2)));
    }

    @Test public void unpatchedPausedHiddenCoverlessAndOtherSheetsGetNoButton() {
        List<Object> nativeRow = row();
        SettingsStatus.stickerCreatorEnabled = false;
        assertSame("needs Create stickers", nativeRow, ShareMakeSticker.actions(sheet("4"), nativeRow));
        SettingsStatus.stickerCreatorEnabled = true;
        PausedProcess.set(true);
        assertSame(nativeRow, ShareMakeSticker.actions(sheet("4"), nativeRow));
        PausedProcess.set(false);

        assertSame("a photo post has no cover", nativeRow,
                ShareMakeSticker.actions(new AwemeSharePackage(new ShareSaveCoverTest.Post("5", false)), nativeRow));
        AwemeSharePackage profile = sheet("6");
        profile.itemType = "user";
        assertSame(nativeRow, ShareMakeSticker.actions(profile, nativeRow));
        assertSame(nativeRow, ShareMakeSticker.actions(new AwemeSharePackage(new Object()), nativeRow));
        assertSame(nativeRow, ShareMakeSticker.actions(null, nativeRow));
        assertNull(ShareMakeSticker.actions(sheet("6"), null));

        SettingsStatus.shareSheetEnabled = true;
        Settings.SHARE_HIDDEN_ITEMS.save("Make sticker");
        assertSame("hidden with Share sheet tools", nativeRow, ShareMakeSticker.actions(sheet("7"), nativeRow));
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.HIDE_SHARE_ACTIONS.save(true);
        assertSame(nativeRow, ShareMakeSticker.actions(sheet("7"), nativeRow));
        assertEquals(0, constructed);
    }

    @Test public void pausingTakesABuiltButtonBackOut() {
        AwemeSharePackage sheet = sheet("8");
        List<?> finished = ShareMakeSticker.actions(sheet, row());
        Object button = finished.get(2);
        PausedProcess.set(true);
        List<?> after = ShareMakeSticker.actions(sheet, finished);
        assertEquals(3, after.size());
        assertFalse(after.contains(button));
        assertTrue("retired, not forgotten, until the sheet lets go", OwnedShareActions.owns(button));
        PausedProcess.set(false);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertTrue("a retired button makes nothing", made.isEmpty());
    }

    @Test public void aTapMakesThatSheetsPostAndOnlyOwnedCarriersAreTaken() {
        AwemeSharePackage sheet = sheet("9");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals(List.of(sheet.aweme), made);

        assertFalse("TikTok's own Report keeps its click",
                OwnedShareActions.click(new ShareSaveCoverTest.Action("report"), activity, sheet));
        assertTrue("another sheet's tap is taken but makes nothing", OwnedShareActions.click(button, activity, sheet("10")));
        assertEquals(1, made.size());
    }

    @Test public void theIconIsDrawnOnlyOnTheButton() {
        Object button = ShareMakeSticker.actions(sheet("11"), row()).get(2);
        ImageView image = new ImageView(activity);
        ColorDrawable nativeIcon = new ColorDrawable();
        image.setImageDrawable(nativeIcon);
        OwnedShareActions.bindIcon(new ShareSaveCoverTest.Action("report"), image);
        assertSame(nativeIcon, image.getDrawable());
        OwnedShareActions.bindIcon(button, image);
        assertTrue(image.getDrawable() instanceof StickerGlyphDrawable);
    }

    @Test public void theButtonAsksForTikToksIconFrame() {
        Object button = ShareMakeSticker.actions(sheet("12"), row()).get(2);
        assertEquals("report", OwnedShareActions.framedKey(button, ShareMakeSticker.KEY));
    }
}
