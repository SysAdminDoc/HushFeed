/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The Likes list folds into one row per liked comment, using host-shaped models with the field
 * names TikTok 47.7.3 gives them (BaseNotice, GeneralTemplateNotice, NoticeExtraData, TitleTemplate).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ActivityLikeBundlerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Reason { FIRST_REFRESH, REFRESH, LOAD_MORE }

    public static final class FakeViewModel {
        private final int group;
        FakeViewModel(int group) { this.group = group; }
        public int getGroupType() { return group; }
    }
    public static final class FakeResponse { public List<Object> notices = new ArrayList<>(); }
    public static final class FakeItems { public List<Object> items = new ArrayList<>(); }
    public static final class FakeUser {
        public final String uid;
        FakeUser(String uid) { this.uid = uid; }
    }
    public static final class FakeTitle {
        public final int mergeCount;
        public final List<Object> fromUsers;
        FakeTitle(int count, List<Object> users) { mergeCount = count; fromUsers = users; }
    }
    public static final class FakeUi { public final FakeTitle titleTemplate; FakeUi(FakeTitle t) { titleTemplate = t; } }
    public static final class FakeAweme {
        private final String aid;
        FakeAweme(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }
    public static final class FakeComment {
        public String cid;
        public String awemeId;
    }
    public static final class FakeExtra {
        public final FakeAweme aweme;
        public final FakeComment comment;
        FakeExtra(FakeAweme aweme, FakeComment comment) { this.aweme = aweme; this.comment = comment; }
    }
    public static final class FakeTemplate {
        public final Integer legacySubType;
        public boolean hasRead;
        public final FakeExtra extraData;
        public final FakeUi uiTemplate;
        FakeTemplate(Integer sub, boolean read, FakeExtra extra, FakeUi ui) {
            legacySubType = sub; hasRead = read; extraData = extra; uiTemplate = ui;
        }
    }
    public static final class FakeNotice {
        public int type;
        public boolean hasRead;
        public FakeTemplate templateNotice;
        final String label;
        FakeNotice(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    @Before public void start() { Settings.BUNDLE_ACTIVITY_LIKES.save(true); }
    @After public void finish() { Settings.BUNDLE_ACTIVITY_LIKES.save(false); }

    private static FakeNotice like(String label, String video, String comment, int count, boolean read, String... users) {
        return row(label, ActivityLikeBundler.LIKE_TYPE, 3, video, comment, count, read, users);
    }

    private static FakeNotice row(String label, int type, int sub, String video, String comment, int count,
                                  boolean read, String... users) {
        List<Object> people = new ArrayList<>();
        for (String user : users) people.add(new FakeUser(user));
        FakeComment target = null;
        if (comment != null) {
            target = new FakeComment();
            target.cid = comment;
            target.awemeId = video;
        }
        FakeNotice notice = new FakeNotice(label);
        notice.type = type;
        notice.hasRead = read;
        notice.templateNotice = new FakeTemplate(sub, read,
                new FakeExtra(video == null ? null : new FakeAweme(video), target),
                new FakeUi(new FakeTitle(count, people)));
        return notice;
    }

    private static FakeResponse page(Object... rows) {
        FakeItems items = new FakeItems();
        items.items.addAll(Arrays.asList(rows));
        FakeResponse response = new FakeResponse();
        response.notices.add(items);
        return response;
    }

    private static List<Object> rows(FakeResponse response) { return ((FakeItems) response.notices.get(0)).items; }
    private static int count(FakeNotice n) { return n.templateNotice.uiTemplate.titleTemplate.mergeCount; }
    private static List<String> users(FakeNotice n) {
        List<String> ids = new ArrayList<>();
        for (Object user : n.templateNotice.uiTemplate.titleTemplate.fromUsers) ids.add(((FakeUser) user).uid);
        return ids;
    }

    @Test public void rowsOfOneCommentFoldIntoItsNewestRow() {
        FakeNotice newest = like("a1", "v1", "c1", 1, true, "u1");
        FakeNotice other = like("b1", "v2", "c2", 2, true, "u9", "u8");
        FakeNotice older = like("a2", "v1", "c1", 7, true, "u2", "u3", "u4", "u5", "u6");
        FakeNotice oldest = like("a3", "v1", "c1", 3, true, "u1", "u7", "u10");
        FakeResponse response = page(newest, other, older, oldest);

        ActivityLikeBundler.onPage(new FakeViewModel(ActivityLikeBundler.LIKES_GROUP), response, Reason.FIRST_REFRESH);

        assertEquals(Arrays.asList(newest, other), rows(response));
        assertEquals(11, count(newest));
        // Newest likers first, each person once, at most five like the server sends.
        assertEquals(Arrays.asList("u1", "u2", "u3", "u4", "u5"), users(newest));
        assertEquals(2, count(other));
    }

    @Test public void aLoadedPageKeepsFoldingIntoTheRowsAboveItAndARefreshStartsOver() {
        FakeViewModel vm = new FakeViewModel(ActivityLikeBundler.LIKES_GROUP);
        FakeNotice first = like("a1", "v1", "c1", 2, true, "u1", "u2");
        ActivityLikeBundler.onPage(vm, page(first), Reason.FIRST_REFRESH);

        FakeNotice nextPage = like("a2", "v1", "c1", 15, true, "u3");
        FakeResponse more = page(nextPage);
        ActivityLikeBundler.onPage(vm, more, Reason.LOAD_MORE);
        assertTrue(rows(more).isEmpty());
        assertEquals(17, count(first));

        FakeNotice fresh = like("a3", "v1", "c1", 1, true, "u4");
        FakeResponse refreshed = page(fresh);
        ActivityLikeBundler.onPage(vm, refreshed, Reason.REFRESH);
        assertEquals(Arrays.asList(fresh), rows(refreshed));
        assertEquals(1, count(fresh));
    }

    @Test public void anUnreadOlderRowLeavesTheFoldedRowUnread() {
        FakeNotice newest = like("a1", "v1", "c1", 1, true, "u1");
        FakeNotice olderUnread = like("a2", "v1", "c1", 1, false, "u2");
        ActivityLikeBundler.onPage(new FakeViewModel(ActivityLikeBundler.LIKES_GROUP), page(newest, olderUnread), Reason.FIRST_REFRESH);
        assertFalse(newest.hasRead);
        assertFalse(newest.templateNotice.hasRead);
    }

    @Test public void videoLikesFoldByVideoAndSavesNeverFoldIntoLikes() {
        FakeNotice videoLike = row("v1", ActivityLikeBundler.LIKE_TYPE, 1, "v7", null, 1, true, "u1");
        FakeNotice videoLikeOlder = row("v2", ActivityLikeBundler.LIKE_TYPE, 1, "v7", null, 4, true, "u2");
        FakeNotice save = row("s1", ActivityLikeBundler.LIKE_TYPE, 9, "v7", null, 1, true, "u3");
        FakeResponse response = page(videoLike, save, videoLikeOlder);
        ActivityLikeBundler.onPage(new FakeViewModel(ActivityLikeBundler.LIKES_GROUP), response, Reason.FIRST_REFRESH);
        assertEquals(Arrays.asList(videoLike, save), rows(response));
        assertEquals(5, count(videoLike));
        assertEquals(1, count(save));
    }

    @Test public void otherListsRowsWithoutATargetAndTheSwitchOffAreLeftAlone() {
        FakeNotice a = like("a1", "v1", "c1", 1, true, "u1");
        FakeNotice b = like("a2", "v1", "c1", 1, true, "u2");
        FakeResponse comments = page(a, b);
        ActivityLikeBundler.onPage(new FakeViewModel(44), comments, Reason.FIRST_REFRESH);
        assertEquals(2, rows(comments).size());

        FakeNotice noTarget = row("x", ActivityLikeBundler.LIKE_TYPE, 3, null, null, 1, true, "u1");
        FakeNotice noTarget2 = row("y", ActivityLikeBundler.LIKE_TYPE, 3, null, null, 1, true, "u2");
        FakeNotice comment = row("z", 31, 2, "v1", "c1", 1, true, "u3");
        FakeResponse mixed = page(noTarget, noTarget2, comment);
        ActivityLikeBundler.onPage(new FakeViewModel(ActivityLikeBundler.LIKES_GROUP), mixed, Reason.FIRST_REFRESH);
        assertEquals(3, rows(mixed).size());

        Settings.BUNDLE_ACTIVITY_LIKES.save(false);
        FakeNotice c = like("c1", "v1", "c1", 1, true, "u1");
        FakeNotice d = like("c2", "v1", "c1", 1, true, "u2");
        FakeResponse off = page(c, d);
        ActivityLikeBundler.onPage(new FakeViewModel(ActivityLikeBundler.LIKES_GROUP), off, Reason.FIRST_REFRESH);
        assertEquals(2, rows(off).size());
        assertEquals(1, count(c));
    }

    @Test public void aRowWithoutACountIsNeverDropped() throws Exception {
        FakeNotice kept = like("a1", "v1", "c1", 1, true, "u1");
        FakeNotice broken = like("a2", "v1", "c1", 1, true, "u2");
        broken.templateNotice = new FakeTemplate(3, true, broken.templateNotice.extraData, new FakeUi(null));
        List<Object> list = new ArrayList<>(Arrays.asList(kept, broken));
        assertEquals(0, ActivityLikeBundler.fold(list, new java.util.HashMap<>(), new java.util.HashMap<>()));
        assertEquals(2, list.size());
        assertSame(kept, list.get(0));
    }

    @Test public void onlyFoldsIntoRowsOfEarlierPagesCountAsNeedingARedraw() throws Exception {
        java.util.Map<String, Object> kept = new java.util.HashMap<>();
        FakeNotice shown = like("a1", "v1", "c1", 1, true, "u1");
        assertEquals(0, ActivityLikeBundler.fold(new ArrayList<>(Arrays.asList(shown)), kept, new java.util.HashMap<>()));

        java.util.Map<String, Object> earlier = new java.util.HashMap<>(kept);
        FakeNotice intoShown = like("a2", "v1", "c1", 4, true, "u2");
        FakeNotice fresh = like("b1", "v2", "c2", 1, true, "u3");
        FakeNotice intoFresh = like("b2", "v2", "c2", 1, true, "u4");
        List<Object> next = new ArrayList<>(Arrays.asList(intoShown, fresh, intoFresh));
        // One fold lands on a row already on screen; the other on a row of this same page.
        assertEquals(1, ActivityLikeBundler.fold(next, kept, earlier));
        assertEquals(Arrays.asList(fresh), next);
        assertEquals(5, count(shown));
        assertEquals(2, count(fresh));
    }
}
