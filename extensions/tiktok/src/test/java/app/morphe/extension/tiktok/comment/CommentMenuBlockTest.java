/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;

import android.app.Activity;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorService;
import app.morphe.extension.tiktok.blockauthor.VideoAuthor;
import app.morphe.extension.tiktok.feed.AuthorRegion;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.comment.model.Comment;
import com.ss.android.ugc.aweme.commentv2.commentlist.viewmodel.CommentDeleteVM;
import com.ss.android.ugc.aweme.profile.model.User;

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
import java.util.Map;

/**
 * Block this commenter (#169) and Show account country (#417) in the comment menu, against
 * stand-ins shaped like 47.1.4's rows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class CommentMenuBlockTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int TOP = 0x1;
    private static final int BOTTOM = 0x10;
    private static final int BLOCK_GLYPH = 0x7f01002c;
    private static final int REPORT_GLYPH = 0x7f01078f;

    /** The panel: one Comment field among others, as CommentLongPressFragment holds it. */
    public static final class Panel {
        public String enterFrom = "comment_list";
        public Comment comment;

        Panel(Comment comment) { this.comment = comment; }
    }

    public static final class UserComment extends Comment {
        public final User user;

        UserComment(String cid, User user) {
            super(cid, false);
            this.user = user;
        }

        public User getUser() { return user; }
    }

    /** The menu's click callback and its second callback, both one-method interfaces. */
    public interface Click { Object invoke(); }
    public interface Measure { Object invoke(Object value); }

    public enum Tone { PLAIN }

    /** The row style: corner flags, a text colour switch and a tone, built with a defaults mask. */
    public static final class Style {
        public int corners;
        public final boolean alternate;
        public final Tone tone;

        public Style(int corners, boolean alternate, Tone tone, int defaults) {
            this.corners = (defaults & 1) != 0 ? 0 : corners;
            this.alternate = (defaults & 2) == 0 && alternate;
            this.tone = (defaults & 4) != 0 ? null : tone;
        }
    }

    public static class Item {
        public final int viewType;

        Item(int viewType) { this.viewType = viewType; }
    }

    public static final class Row extends Item {
        public final String key;
        public final Integer icon;
        public final String label;
        public final Click click;
        public final Style style;
        public final Measure measure;

        public Row(String key, Integer icon, String label, Click click, Style style, Measure measure) {
            super(1);
            this.key = key;
            this.icon = icon;
            this.label = label;
            this.click = click;
            this.style = style;
            this.measure = measure;
        }
    }

    private static final class Request {
        final VideoAuthor author;
        final String account;
        final boolean undo;
        final BlockAuthorService.Callback callback;

        Request(VideoAuthor author, String account, boolean undo, BlockAuthorService.Callback callback) {
            this.author = author; this.account = account; this.undo = undo; this.callback = callback;
        }
    }

    private static final class Asked {
        final String uid;
        final String secUid;
        final AccountCountry.Callback callback;

        Asked(String uid, String secUid, AccountCountry.Callback callback) {
            this.uid = uid; this.secUid = secUid; this.callback = callback;
        }
    }

    private static final int GLOBE_GLYPH = 0x7f010801;

    private final List<Request> requests = new ArrayList<>();
    private final List<Asked> lookups = new ArrayList<>();
    private final List<Runnable> undos = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private Activity activity;
    private boolean oldMenu;
    private boolean oldCountry;
    private boolean oldTools;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        oldTools = SettingsStatus.commentToolsEnabled;
        oldMenu = Settings.BLOCK_FROM_COMMENT_MENU.get();
        oldCountry = Settings.COUNTRY_FROM_COMMENT_MENU.get();
        SettingsStatus.commentToolsEnabled = true;
        Settings.BLOCK_FROM_COMMENT_MENU.save(true);
        Settings.COUNTRY_FROM_COMMENT_MENU.save(false);
        AuthorRegion.forgetSeenRegions();
        CommentMenuBlock.lookupForTests = (uid, secUid, callback) -> lookups.add(new Asked(uid, secUid, callback));
        SignedInUser.idForTests = "100";
        CommentMenuBlock.setIcon(BLOCK_GLYPH);
        CommentMenuBlock.transportForTests = (author, account, undo, callback) ->
                requests.add(new Request(author, account, undo, callback));
        CommentMenuBlock.feedbackForTests = new CommentMenuBlock.Feedback() {
            @Override public void undo(String message, Runnable undo) {
                messages.add(message);
                undos.add(undo);
            }
            @Override public void notice(String message) { notices.add(message); }
        };
        CommentDeleteVM.POSTED.clear();
    }

    @After public void tearDown() {
        CommentMenuBlock.transportForTests = null;
        CommentMenuBlock.feedbackForTests = null;
        CommentMenuBlock.lookupForTests = null;
        CommentMenuBlock.globe = -1;
        CommentMenuBlock.setIcon(0);
        AuthorRegion.forgetSeenRegions();
        Settings.COUNTRY_FROM_COMMENT_MENU.save(oldCountry);
        ((List<?>) ReflectionHelpers.getStaticField(CommentMenuBlock.class, "BUILT")).clear();
        ((Map<?, ?>) ReflectionHelpers.getStaticField(CommentMenuBlock.class, "IN_FLIGHT")).clear();
        ((List<?>) ReflectionHelpers.getStaticField(CommentMenuBlock.class, "MOVED")).clear();
        CommentDeleteVM.POSTED.clear();
        SignedInUser.idForTests = null;
        Settings.BLOCK_FROM_COMMENT_MENU.save(oldMenu);
        SettingsStatus.commentToolsEnabled = oldTools;
        activity.finish();
    }

    private static User user(String uid) {
        User user = new User();
        user.uid = uid;
        user.uniqueId = "commenter" + uid;
        return user;
    }

    private static Row row(String key, int corners) {
        return new Row(key, key.equals("report") ? REPORT_GLYPH : 0x7f010001, key, () -> null,
                new Style(corners, false, Tone.PLAIN, 0), null);
    }

    /** Copy alone in its group, then Delete and Report sharing one. */
    private static List<Object> menu() {
        return new ArrayList<>(Arrays.asList(row("copy", TOP | BOTTOM), row("delete", TOP), row("report", BOTTOM)));
    }

    private static Row owned(List<?> rows) {
        return keyed(rows, CommentMenuBlock.KEY);
    }

    private static Row country(List<?> rows) {
        return keyed(rows, CommentMenuBlock.COUNTRY_KEY);
    }

    private static Row keyed(List<?> rows, String key) {
        Row found = null;
        for (Object item : rows) {
            if (item instanceof Row && key.equals(((Row) item).key)) {
                assertNull("one " + key + " row", found);
                found = (Row) item;
            }
        }
        return found;
    }

    // ---- Show account country (#417) ----------------------------------------------------

    /**
     * At 3, TikTok's comment share revamp opens a share-style panel the rows never reach (#417).
     * 2 keeps its share button and brings the menu back, but only while a row is on.
     */
    @Test public void theShareStylePanelGivesWayToTheMenuWhileARowIsOn() {
        String gate = "comment_share_expose_revamp";
        Settings.BLOCK_FROM_COMMENT_MENU.save(false);
        Settings.COUNTRY_FROM_COMMENT_MENU.save(true);
        assertEquals(2, CommentMenuBlock.panelGate(gate, 3));
        Settings.COUNTRY_FROM_COMMENT_MENU.save(false);
        Settings.BLOCK_FROM_COMMENT_MENU.save(true);
        assertEquals(2, CommentMenuBlock.panelGate(gate, 3));
        for (int value : new int[]{0, 1, 2, 4}) assertEquals(value, CommentMenuBlock.panelGate(gate, value));
        assertEquals(3, CommentMenuBlock.panelGate("comment_share_expose", 3));
        assertEquals(3, CommentMenuBlock.panelGate(null, 3));

        Settings.BLOCK_FROM_COMMENT_MENU.save(false);
        assertEquals(3, CommentMenuBlock.panelGate(gate, 3));
        Settings.BLOCK_FROM_COMMENT_MENU.save(true);
        SettingsStatus.commentToolsEnabled = false;
        assertEquals(3, CommentMenuBlock.panelGate(gate, 3));
    }

    @Test public void theCountryRowGoesLastAndOnlyItClosesTheGroup() {
        Settings.COUNTRY_FROM_COMMENT_MENU.save(true);
        CommentMenuBlock.globe = GLOBE_GLYPH;
        Panel panel = new Panel(new UserComment("c1", user("200")));
        List<Object> nativeRows = menu();
        Row report = (Row) nativeRows.get(2);

        List<?> both = CommentMenuBlock.items(panel, nativeRows);
        assertEquals(5, both.size());
        Row block = owned(both);
        Row country = country(both);
        assertSame(report, both.get(2));
        assertSame(block, both.get(3));
        assertSame(country, both.get(4));
        assertEquals("Block sits inside the group now", 0, block.style.corners);
        assertEquals(BOTTOM, country.style.corners);
        assertEquals(0, report.style.corners);
        assertEquals("Show account country", country.label);
        assertEquals(Integer.valueOf(GLOBE_GLYPH), country.icon);
        assertSame(Tone.PLAIN, country.style.tone);
        assertNull(country.measure);
        assertTrue(CommentMenuBlock.owns(country));

        Settings.BLOCK_FROM_COMMENT_MENU.save(false);
        List<?> alone = CommentMenuBlock.items(panel, new ArrayList<>(both));
        assertEquals(4, alone.size());
        assertNull(owned(alone));
        assertSame(country(alone), alone.get(3));
        assertEquals(BOTTOM, country(alone).style.corners);
        assertEquals(0, report.style.corners);

        Settings.COUNTRY_FROM_COMMENT_MENU.save(false);
        List<?> none = CommentMenuBlock.items(panel, new ArrayList<>(alone));
        assertEquals(3, none.size());
        assertEquals("Report gets its corners back", BOTTOM, report.style.corners);
    }

    @Test public void withoutTikToksGlobeTheCountryRowBorrowsReportsGlyph() {
        Settings.BLOCK_FROM_COMMENT_MENU.save(false);
        Settings.COUNTRY_FROM_COMMENT_MENU.save(true);
        Row country = country(CommentMenuBlock.items(new Panel(new UserComment("c1", user("200"))), menu()));
        assertEquals(Integer.valueOf(REPORT_GLYPH), country.icon);
        assertEquals("a missing globe is looked up once", 0, CommentMenuBlock.globe);
    }

    @Test public void aCountryTapAsksTikTokAndShowsAndRemembersTheCountry() {
        Settings.COUNTRY_FROM_COMMENT_MENU.save(true);
        Panel panel = new Panel(new UserComment("c1", user("200")));
        Row country = country(CommentMenuBlock.items(panel, menu()));
        country.click.invoke();

        assertEquals(1, lookups.size());
        assertEquals("200", lookups.get(0).uid);
        assertTrue("the country row never blocks", requests.isEmpty());
        lookups.get(0).callback.onAnswer(new AccountCountry.Answer(AccountCountry.Outcome.FOUND, "DE"));
        assertEquals(Arrays.asList("Account based in Germany"), notices);
        assertEquals("comments by that account can show it now", "DE", AuthorRegion.seenRegionByUid("200"));

        country.click.invoke();
        assertEquals("a used row asks nothing more", 1, lookups.size());
    }

    @Test public void noCountryOrAFailedLookupSaysSo() {
        Settings.COUNTRY_FROM_COMMENT_MENU.save(true);
        country(CommentMenuBlock.items(new Panel(new UserComment("c1", user("200"))), menu())).click.invoke();
        lookups.get(0).callback.onAnswer(new AccountCountry.Answer(AccountCountry.Outcome.NONE, null));
        country(CommentMenuBlock.items(new Panel(new UserComment("c2", user("300"))), menu())).click.invoke();
        lookups.get(1).callback.onAnswer(AccountCountry.failed());
        assertEquals(Arrays.asList("TikTok gives no country for this account", "Couldn't get the country. Try again."), notices);
        assertNull(AuthorRegion.seenRegionByUid("200"));
        assertNull(AuthorRegion.seenRegionByUid("300"));

        CommentMenuBlock.lookupForTests = (uid, secUid, callback) -> { throw new IllegalStateException("no client"); };
        country(CommentMenuBlock.items(new Panel(new UserComment("c3", user("400"))), menu())).click.invoke();
        assertEquals("Couldn't get the country. Try again.", notices.get(2));
    }

    @Test public void anOldOrSwitchedOffCountryRowAsksNothing() {
        Settings.COUNTRY_FROM_COMMENT_MENU.save(true);
        Panel panel = new Panel(new UserComment("c1", user("200")));
        Row country = country(CommentMenuBlock.items(panel, menu()));
        panel.comment = new UserComment("c2", user("300"));
        country.click.invoke();

        Row second = country(CommentMenuBlock.items(new Panel(new UserComment("c3", user("200"))), menu()));
        Settings.COUNTRY_FROM_COMMENT_MENU.save(false);
        second.click.invoke();
        assertTrue(lookups.isEmpty());
        assertTrue(notices.isEmpty());
    }

    @Test public void addsTheRowAfterReportAndTakesItsBottomCorners() {
        Panel panel = new Panel(new UserComment("c1", user("200")));
        List<Object> nativeRows = menu();
        Row report = (Row) nativeRows.get(2);
        List<?> rows = CommentMenuBlock.items(panel, nativeRows);

        assertEquals("TikTok's own list is left as it was", 3, nativeRows.size());
        assertEquals(4, rows.size());
        Row block = owned(rows);
        assertSame(block, rows.get(3));
        assertSame(report, rows.get(2));
        assertEquals("Block this commenter", block.label);
        assertEquals(Integer.valueOf(BLOCK_GLYPH), block.icon);
        assertEquals(BOTTOM, block.style.corners);
        assertFalse(block.style.alternate);
        assertSame(Tone.PLAIN, block.style.tone);
        assertNull(block.measure);
        assertEquals("Report no longer rounds the group's bottom", 0, report.style.corners);
        assertTrue(CommentMenuBlock.owns(block));
    }

    @Test public void aRebuildKeepsOneRowAndTurningItOffPutsReportBack() {
        Panel panel = new Panel(new UserComment("c1", user("200")));
        List<Object> nativeRows = menu();
        Row report = (Row) nativeRows.get(2);
        List<?> first = CommentMenuBlock.items(panel, nativeRows);
        List<?> again = CommentMenuBlock.items(panel, nativeRows);
        assertEquals(4, again.size());
        assertNotSame(owned(first), owned(again));
        assertEquals(BOTTOM, owned(again).style.corners);
        assertEquals(0, report.style.corners);

        // An earlier pass's row handed back in is taken out, not doubled.
        List<?> fed = CommentMenuBlock.items(panel, new ArrayList<>(again));
        assertEquals(4, fed.size());

        Settings.BLOCK_FROM_COMMENT_MENU.save(false);
        assertSame(nativeRows, CommentMenuBlock.items(panel, nativeRows));
        assertEquals(BOTTOM, report.style.corners);
        List<?> stripped = CommentMenuBlock.items(panel, new ArrayList<>(fed));
        assertEquals(3, stripped.size());
        assertNull(owned(stripped));
    }

    @Test public void noRowForYourOwnCommentAnUnreadableOneOrWhenSignedOut() {
        List<Object> nativeRows = menu();
        assertSame(nativeRows, CommentMenuBlock.items(new Panel(new UserComment("c1", user("100"))), nativeRows));
        assertSame(nativeRows, CommentMenuBlock.items(new Panel(new UserComment("c2", null)), nativeRows));
        assertSame(nativeRows, CommentMenuBlock.items(new Panel(new Comment("c3", false)), nativeRows));
        assertSame(nativeRows, CommentMenuBlock.items(new Panel(new UserComment("c4", user("0"))), nativeRows));
        assertSame(nativeRows, CommentMenuBlock.items(new Panel(null), nativeRows));
        assertSame(nativeRows, CommentMenuBlock.items(null, nativeRows));

        SignedInUser.idForTests = "";
        assertSame(nativeRows, CommentMenuBlock.items(new Panel(new UserComment("c5", user("200"))), nativeRows));

        SignedInUser.idForTests = "100";
        SettingsStatus.commentToolsEnabled = false;
        assertSame(nativeRows, CommentMenuBlock.items(new Panel(new UserComment("c6", user("200"))), nativeRows));

        SettingsStatus.commentToolsEnabled = true;
        List<Object> noReport = new ArrayList<>(Arrays.asList(row("copy", TOP | BOTTOM)));
        assertSame(noReport, CommentMenuBlock.items(new Panel(new UserComment("c7", user("200"))), noReport));
        assertEquals(TOP | BOTTOM, ((Row) noReport.get(0)).style.corners);
        assertEquals(BOTTOM, ((Row) nativeRows.get(2)).style.corners);
    }

    @Test public void aTapBlocksTheCommenterTakesTheirCommentsOutAndUndoUnblocks() {
        User commenter = user("200");
        Panel panel = new Panel(new UserComment("c1", commenter));
        Row block = owned(CommentMenuBlock.items(panel, menu()));
        block.click.invoke();

        assertEquals(1, requests.size());
        Request request = requests.get(0);
        assertEquals("200", request.author.uid);
        assertEquals("c1", request.author.awemeId);
        assertEquals("100", request.account);
        assertFalse(request.undo);
        assertTrue("nothing leaves the list before TikTok confirms", CommentDeleteVM.POSTED.isEmpty());

        request.callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertEquals(Arrays.asList(commenter), CommentDeleteVM.POSTED);
        assertEquals(1, undos.size());
        assertTrue(messages.get(0).startsWith("Blocked "));
        assertTrue(messages.get(0).contains("commenter200"));

        undos.get(0).run();
        undos.get(0).run();
        assertEquals("Undo sends one unblock", 2, requests.size());
        Request undo = requests.get(1);
        assertTrue(undo.undo);
        assertSame(request.author, undo.author);
        assertEquals("100", undo.account);
        undo.callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertTrue(notices.get(0).startsWith("Unblocked "));

        block.click.invoke();
        assertEquals("a used row sends nothing more", 2, requests.size());
    }

    @Test public void aRefusedBlockLeavesTheCommentsAndOffersNoUndo() {
        Panel panel = new Panel(new UserComment("c1", user("200")));
        owned(CommentMenuBlock.items(panel, menu())).click.invoke();
        requests.get(0).callback.onResult(BlockAuthorService.Result.REJECTED);
        assertTrue(CommentDeleteVM.POSTED.isEmpty());
        assertTrue(undos.isEmpty());
        assertEquals(1, notices.size());
    }

    @Test public void aPanelNowShowingAnotherCommentOrAccountIgnoresTheOldRow() {
        Panel panel = new Panel(new UserComment("c1", user("200")));
        Row block = owned(CommentMenuBlock.items(panel, menu()));
        panel.comment = new UserComment("c2", user("300"));
        block.click.invoke();
        assertTrue(requests.isEmpty());

        Panel other = new Panel(new UserComment("c3", user("200")));
        Row second = owned(CommentMenuBlock.items(other, menu()));
        SignedInUser.idForTests = "400";
        second.click.invoke();
        assertTrue(requests.isEmpty());

        SignedInUser.idForTests = "100";
        Settings.BLOCK_FROM_COMMENT_MENU.save(false);
        second.click.invoke();
        assertTrue(requests.isEmpty());
    }

    @Test public void anUndoTappedWhileTheBlockIsPendingWaitsForIt() {
        Panel first = new Panel(new UserComment("c1", user("200")));
        owned(CommentMenuBlock.items(first, menu())).click.invoke();
        assertEquals(1, requests.size());
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        Runnable undo = undos.get(0);

        // A second block of the same commenter from another comment, still on its way.
        Panel second = new Panel(new UserComment("c2", user("200")));
        owned(CommentMenuBlock.items(second, menu())).click.invoke();
        assertEquals(2, requests.size());
        undo.run();
        assertEquals("the Undo waits for the pending block", 2, requests.size());
        requests.get(1).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertEquals(3, requests.size());
        assertTrue(requests.get(2).undo);
        assertEquals("the queued Undo already follows that block", 1, undos.size());
    }
}
