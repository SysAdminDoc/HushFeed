/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Make sticker in a video's share sheet (#88), on the same native carrier as Save cover. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ShareMakeStickerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<Object> made = new ArrayList<>();
    private final List<Object> fetched = new ArrayList<>();
    private ActivityController<Activity> controller;
    private Activity activity;
    private int constructed;
    private File nextClip;
    private IOException nextFailure;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Utils.setActivity(activity);
        SettingsStatus.stickerCreatorEnabled = true;
        ShareMakeSticker.nativeForTests = (from, post) -> {
            assertSame(activity, from);
            assertTrue(post instanceof ShareSaveCoverTest.Post);
            constructed++;
            return new ShareSaveCoverTest.Action("report");
        };
        ShareMakeSticker.coverForTests = (Context context, Object post) -> made.add(post);
        ShareMakeSticker.worker = task -> {
            task.run();
            return true;
        };
        ShareMakeSticker.clipsForTests = (Context context, Object post) -> {
            fetched.add(post);
            if (nextFailure != null) throw nextFailure;
            return nextClip;
        };
    }

    @After public void tearDown() {
        ShareMakeSticker.nativeForTests = null;
        ShareMakeSticker.coverForTests = null;
        ShareMakeSticker.clipsForTests = null;
        ShareMakeSticker.worker = Utils::runOnBackgroundThread;
        ((AtomicReference<?>) ReflectionHelpers.getStaticField(ShareMakeSticker.class, "LAST_CLIP")).set(null);
        VideoStickerEditor.classes = Class::forName;
        Services.external = null;
        Editor.calls.clear();
        Editor.files.clear();
        Editor.onFile = null;
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

    /** A video whose creator allows stickers, the only kind TikTok's own Create sticker saves. */
    private static AwemeSharePackage allowing(String id) {
        ShareSaveCoverTest.Post post = new ShareSaveCoverTest.Post(id, true);
        post.interactPermission = new Permissions(new Permission(0));
        return new AwemeSharePackage(post);
    }

    private File clip(String name) throws IOException {
        return File.createTempFile("clip-" + name, ".mp4", activity.getCacheDir());
    }

    private static void idleForTheEditor() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShareMakeSticker.FILE_OPEN_WAIT_MS));
    }

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

    // #214: the tap opens TikTok's animated sticker editor for the video, any video.

    @Test public void aTapOpensTikToksStickerEditorForThatVideo() {
        useStandIns(new External(new Ability(new Editor())));
        AwemeSharePackage sheet = allowing("13");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals(1, Editor.calls.size());
        Editor.Call call = Editor.calls.get(0);
        assertSame(activity, call.activity);
        assertSame(sheet.aweme, call.aweme);
        assertSame(Entry.FEED, call.entry);
        assertNull(call.trackFrom);
        assertNull(call.trackMethod);
        assertTrue("no cover sticker while the editor is up", made.isEmpty());
        call.callback.onSuccess();
        assertTrue("opening the editor makes no cover sticker", made.isEmpty());
        assertTrue("nothing downloaded", fetched.isEmpty());
    }

    @Test public void whenTikTokSaysTheEditorCantOpenACopyIsTriedThenTheCoverOnce() {
        useStandIns(new External(new Ability(new Editor())));
        AwemeSharePackage sheet = allowing("14");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        Callback callback = Editor.calls.get(0).callback;
        callback.onFailed(null, "sticker DF not ready", -10001);
        callback.onFailed(null, "ve not installed", -1);
        assertEquals("one download after the first failure", List.of(sheet.aweme), fetched);
        assertEquals(List.of(sheet.aweme), made);
    }

    @Test public void aFailureReportedBeforeTheCallThrowsStillMakesOneCover() {
        Editor editor = new Editor();
        editor.failThenThrow = true;
        useStandIns(new External(new Ability(editor)));
        AwemeSharePackage sheet = allowing("15");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals(List.of(sheet.aweme), made);
        Editor.calls.get(0).callback.onFailed(null, "late", -1);
        assertEquals(1, made.size());
    }

    @Test public void withoutTikToksEditorTheCoverBecomesTheSticker() {
        AwemeSharePackage sheet = allowing("16");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        assertTrue("no editor service in this app", OwnedShareActions.click(button, activity, sheet));
        assertEquals(List.of(sheet.aweme), made);

        made.clear();
        useStandIns(new External(new Ability(null)));
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals("a missing sticker service", List.of(sheet.aweme), made);

        made.clear();
        useStandIns(null);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals("a missing external service", List.of(sheet.aweme), made);
        assertTrue("with no editor at all nothing is downloaded", fetched.isEmpty());

        made.clear();
        Editor throwing = new Editor();
        throwing.throwOnEdit = true;
        useStandIns(new External(new Ability(throwing)));
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals("the editor call threw", List.of(sheet.aweme), made);
        Editor.calls.get(0).callback.onFailed(null, "late", -1);
        assertEquals("a late failure after a throw makes no second cover", 1, made.size());
    }

    @Test public void withNoActivityTheCoverBecomesTheSticker() {
        useStandIns(new External(new Ability(new Editor())));
        Object post = new ShareSaveCoverTest.Post("17", true);
        ShareMakeSticker.make(activity.getApplicationContext(), post, (Context context, Object aweme) -> made.add(aweme));
        assertTrue(Editor.calls.isEmpty());
        assertEquals(List.of(post), made);
        assertTrue(fetched.isEmpty());
    }

    // #214: TikTok won't save a sticker from a video whose creator turned stickers off, so a copy goes in.

    @Test public void aVideoWithStickersOffGoesInAsADownloadedCopy() throws IOException {
        useStandIns(new External(new Ability(new Editor())));
        nextClip = clip("a");
        Editor.onFile = () -> controller.pause().resume();
        AwemeSharePackage sheet = sheet("18");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertTrue("the video itself would be refused", Editor.calls.isEmpty());
        assertEquals(List.of(sheet.aweme), fetched);
        assertEquals(1, Editor.files.size());
        Editor.FileCall call = Editor.files.get(0);
        assertSame(activity, call.activity);
        assertEquals(nextClip.getAbsolutePath(), call.path);
        assertEquals(0L, call.duration);
        assertEquals("favourite_tab", call.enterMethod);
        idleForTheEditor();
        assertTrue("the editor opened, so no cover sticker", made.isEmpty());
        assertTrue("the copy stays while the editor reads it", nextClip.exists());
    }

    @Test public void whenTheCreatorsVideoWontOpenACopyGoesInInstead() throws IOException {
        useStandIns(new External(new Ability(new Editor())));
        nextClip = clip("b");
        Editor.onFile = () -> controller.pause().resume();
        AwemeSharePackage sheet = allowing("19");
        assertTrue(OwnedShareActions.click(ShareMakeSticker.actions(sheet, row()).get(2), activity, sheet));
        assertTrue(Editor.files.isEmpty());
        Editor.calls.get(0).callback.onFailed(null, "download failed", 3);
        assertEquals(1, Editor.files.size());
        idleForTheEditor();
        assertTrue(made.isEmpty());
    }

    @Test public void whenTikTokLeavesTheFileUnreadTheCoverBecomesTheSticker() throws IOException {
        useStandIns(new External(new Ability(new Editor())));
        nextClip = clip("c");
        AwemeSharePackage sheet = sheet("20");
        assertTrue(OwnedShareActions.click(ShareMakeSticker.actions(sheet, row()).get(2), activity, sheet));
        assertEquals(1, Editor.files.size());
        assertTrue("TikTok gets a moment first", made.isEmpty());
        idleForTheEditor();
        assertEquals(List.of(sheet.aweme), made);
    }

    @Test public void aDownloadThatFailsOrFindsNothingMakesTheCover() {
        useStandIns(new External(new Ability(new Editor())));
        AwemeSharePackage sheet = sheet("21");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        nextFailure = new IOException("offline");
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals(List.of(sheet.aweme), made);
        nextFailure = null;
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals("no address to fetch", 2, made.size());
        assertEquals(2, fetched.size());
        assertTrue(Editor.files.isEmpty());
    }

    @Test public void theNextStickerLetsTheLastCopyGoAndAnUnopenedCopyGoesAtOnce() throws IOException {
        Editor editor = new Editor();
        useStandIns(new External(new Ability(editor)));
        Editor.onFile = () -> controller.pause().resume();
        AwemeSharePackage sheet = sheet("22");
        Object button = ShareMakeSticker.actions(sheet, row()).get(2);
        File first = nextClip = clip("d");
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        idleForTheEditor();
        assertTrue(first.exists());

        File second = nextClip = clip("e");
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertFalse("the editor is done with the last one", first.exists());
        assertTrue(second.exists());
        idleForTheEditor();

        editor.throwOnFile = true;
        File third = nextClip = clip("f");
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertFalse("a copy TikTok couldn't take goes at once", third.exists());
        assertFalse(second.exists());
        assertEquals(List.of(sheet.aweme), made);
    }

    @Test public void onlyTheCreatorsYesCounts() {
        ShareSaveCoverTest.Post post = new ShareSaveCoverTest.Post("24", true);
        assertFalse("TikTok's video says nothing", ShareMakeSticker.creatorAllows(post));
        post.interactPermission = new Permissions(null);
        assertFalse(ShareMakeSticker.creatorAllows(post));
        post.interactPermission = new Permissions(new Permission(1));
        assertFalse(ShareMakeSticker.creatorAllows(post));
        post.interactPermission = new Permissions(new Permission(0));
        assertTrue(ShareMakeSticker.creatorAllows(post));
        assertFalse(ShareMakeSticker.creatorAllows(null));
    }

    /** Shaped like 47.7.3's InteractPermission and InteractionPermission. */
    public static final class Permissions {
        public final Permission allowCreateSticker;
        Permissions(Permission allowCreateSticker) { this.allowCreateSticker = allowCreateSticker; }
        public Permission getAllowCreateSticker() { return allowCreateSticker; }
    }

    public static final class Permission {
        public int status;
        Permission(int status) { this.status = status; }
    }

    private static void useStandIns(External external) {
        Services.external = external;
        VideoStickerEditor.classes = name -> {
            if (VideoStickerEditor.SERVICE_MANAGER.equals(name)) return Services.class;
            if (VideoStickerEditor.EXTERNAL_SERVICE.equals(name)) return ExternalService.class;
            throw new ClassNotFoundException(name);
        };
    }

    /** Stand-ins for TikTok's ServiceManager and its external ability services, shaped like 47.7.3's. */
    public static final class Services {
        static ExternalService external;
        private static final Services INSTANCE = new Services();

        public static Services get() { return INSTANCE; }

        public Object getService(Class<?> type) {
            assertSame(ExternalService.class, type);
            return external;
        }
    }

    public interface ExternalService { AbilityService abilityService(); }

    public interface AbilityService { StickerService video2StickerService(); }

    public interface StickerService {
        void video2StickerEdit(Activity activity, com.ss.android.ugc.aweme.feed.model.Aweme aweme, Entry entry,
                Callback callback, String trackFrom, String trackMethod);

        void openVideoTrimForSticker(Activity activity, String path, long duration, String enterMethod);
    }

    public interface Callback {
        void onFailed(Exception error, String message, int code);
        void onSuccess();
    }

    public enum Entry { FEED, CHATROOM }

    static final class External implements ExternalService {
        private final AbilityService ability;
        External(AbilityService ability) { this.ability = ability; }
        @Override public AbilityService abilityService() { return ability; }
    }

    static final class Ability implements AbilityService {
        private final StickerService editor;
        Ability(StickerService editor) { this.editor = editor; }
        @Override public StickerService video2StickerService() { return editor; }
    }

    static final class Editor implements StickerService {
        static final List<Call> calls = new ArrayList<>();
        boolean throwOnEdit;
        boolean failThenThrow;

        static final class Call {
            Activity activity;
            Object aweme;
            Entry entry;
            Callback callback;
            String trackFrom;
            String trackMethod;
        }

        @Override public void video2StickerEdit(Activity activity, com.ss.android.ugc.aweme.feed.model.Aweme aweme,
                Entry entry, Callback callback, String trackFrom, String trackMethod) {
            Call call = new Call();
            call.activity = activity;
            call.aweme = aweme;
            call.entry = entry;
            call.callback = callback;
            call.trackFrom = trackFrom;
            call.trackMethod = trackMethod;
            calls.add(call);
            if (failThenThrow) {
                callback.onFailed(null, "download failed", 3);
                throw new IllegalStateException("after the failure");
            }
            if (throwOnEdit) throw new IllegalStateException("editor broke");
        }

        static final List<FileCall> files = new ArrayList<>();
        /** What TikTok does with the file: opening its editor pauses the video's activity. */
        static Runnable onFile;
        boolean throwOnFile;

        static final class FileCall {
            Activity activity;
            String path;
            long duration;
            String enterMethod;
        }

        @Override public void openVideoTrimForSticker(Activity activity, String path, long duration, String enterMethod) {
            FileCall call = new FileCall();
            call.activity = activity;
            call.path = path;
            call.duration = duration;
            call.enterMethod = enterMethod;
            files.add(call);
            if (throwOnFile) throw new IllegalStateException("couldn't read it");
            if (onFile != null) onFile.run();
        }
    }
}
