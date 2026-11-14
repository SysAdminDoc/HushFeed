package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.DocumentExportProvider;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.DocumentOperation;
import app.morphe.extension.tiktok.settings.preference.DocumentOperationTest;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** What Export comments writes, when it shows, and what it does with a file app that hangs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class Author {
        public final String uniqueId, nickname;
        Author(String handle, String name) { uniqueId = handle; nickname = name; }
    }

    /** Stands in for TikTok's Comment, with the field names all three declared builds carry. */
    public static final class Comment {
        public String cid, text, rootCommentId = "0", commentLanguage = "en", awemeId;
        public Author user;
        public int createTime = 1_700_000_000, diggCount, replyCount, stickPosition;
        public long replyCommentTotal;
        public boolean authorPin, isAuthorDigged;
        public List<Object> imageList = new ArrayList<>();
        public List<Comment> replyComments = new ArrayList<>();

        Comment(String id, String said, String handle, String name) {
            cid = id;
            text = said;
            user = new Author(handle, name);
        }
    }

    public static class SettingsWindow extends Activity {
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            setTheme(android.R.style.Theme_Material_NoActionBar);
            setContentView(new LinearLayout(this));
            findViewById(android.R.id.content).setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        }
    }

    // ---- the writers --------------------------------------------------------------------

    /** A minimal RFC 4180 reader, so the test checks what a spreadsheet would see. */
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(field.toString()); field.setLength(0); }
            else if (c == '\r') { /* the \n that follows ends the record */ }
            else if (c == '\n') { row.add(field.toString()); field.setLength(0); rows.add(row); row = new ArrayList<>(); }
            else field.append(c);
        }
        return rows;
    }

    private static CommentExport.Row row(String id, String parent, String text) {
        CommentExport.Row row = new CommentExport.Row();
        row.id = id;
        row.parentId = parent;
        row.text = text;
        row.username = "user_" + id;
        row.nickname = "Nick, \"the\" " + id;
        row.createdEpoch = 1_700_000_000L;
        row.likes = 12;
        return row;
    }

    @Test public void csvEscapesCommasQuotesNewlinesAndEmoji() {
        String nasty = "plain, comma \"quoted\" line one\nline two\r\nthree 😀 café";
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", nasty), row("2", "1", "=SUM(A1)"));
        List<List<String>> parsed = parseCsv(CommentExport.toCsv(rows));

        assertEquals(3, parsed.size());
        assertEquals(Arrays.asList(CommentExport.COLUMNS), parsed.get(0));
        for (List<String> record : parsed) assertEquals(CommentExport.COLUMNS.length, record.size());
        // Every awkward character comes back exactly as it went in.
        assertEquals(nasty, parsed.get(1).get(5));
        assertEquals("Nick, \"the\" 1", parsed.get(1).get(4));
        assertEquals("comment", parsed.get(1).get(2));
        assertEquals("reply", parsed.get(2).get(2));
        assertEquals("1", parsed.get(2).get(1));
        assertEquals("2023-11-14T22:13:20Z", parsed.get(1).get(6));
        // A field with none of them is left bare.
        assertTrue(CommentExport.toCsv(rows).contains(",user_1,"));
    }

    /** A spreadsheet runs a cell that starts like a formula; the CSV keeps every one of them text. */
    @Test public void csvKeepsFormulaLookingCommentsAsText() {
        String[] formulas = {"=HYPERLINK(\"https://x\",\"hi\")", "+1+1", "-2+3", "@SUM(A1)", "\tlead", "\rlead"};
        List<CommentExport.Row> rows = new java.util.ArrayList<>();
        for (int index = 0; index < formulas.length; index++) rows.add(row(String.valueOf(index), "", formulas[index]));
        rows.add(row("9", "", "a = b, not a formula"));
        List<List<String>> parsed = parseCsv(CommentExport.toCsv(rows));
        for (int index = 0; index < formulas.length; index++) {
            assertEquals("'" + formulas[index], parsed.get(index + 1).get(5));
        }
        assertEquals("a = b, not a formula", parsed.get(formulas.length + 1).get(5));
    }

    @Test public void aTruncatedSheetSaysSoInTheJsonHeader() throws Exception {
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", "=HYPERLINK(\"x\")"));
        JSONObject whole = new JSONObject(CommentExport.toJson(rows, "2026-10-07T00:00:00Z"));
        assertEquals(false, whole.getBoolean("truncated"));
        // JSON is no spreadsheet, so its text stays exactly as written.
        assertEquals("=HYPERLINK(\"x\")", whole.getJSONArray("comments").getJSONObject(0).getString("text"));
        JSONObject cut = new JSONObject(CommentExport.toJson(rows, "2026-10-07T00:00:00Z", true));
        assertEquals(true, cut.getBoolean("truncated"));
        assertTrue(cut.getString("note"), cut.getString("note").contains("more comments loaded than Hushfeed keeps"));
    }

    @Test public void csvFileLeadsWithAByteOrderMarkAndJsonDoesNot() throws Exception {
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", "hi 😀"));
        byte[] csv = CommentExport.encode(CommentExport.Format.CSV, rows, "2026-10-07T00:00:00Z");
        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
        assertTrue(new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8).contains("😀"));
        byte[] json = CommentExport.encode(CommentExport.Format.JSON, rows, "2026-10-07T00:00:00Z");
        assertEquals('{', json[0]);
    }

    @Test public void jsonCarriesCountsInItsHeaderAndEveryField() throws Exception {
        String nasty = "say \"hi\"\nnew line 😀";
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", nasty), row("2", "1", "reply"), row("3", "1", "reply two"));
        rows.get(0).pinned = true;
        rows.get(0).creatorLiked = true;
        rows.get(0).images = 2;
        JSONObject root = new JSONObject(CommentExport.toJson(rows, "2026-10-07T00:00:00Z"));

        assertEquals(1, root.getInt("comment_count"));
        assertEquals(2, root.getInt("reply_count"));
        assertEquals(3, root.getInt("total"));
        assertEquals("2026-10-07T00:00:00Z", root.getString("exported_at"));
        JSONArray comments = root.getJSONArray("comments");
        assertEquals(3, comments.length());
        JSONObject first = comments.getJSONObject(0);
        assertEquals(nasty, first.getString("text"));
        assertTrue(first.isNull("parent_cid"));
        assertEquals("comment", first.getString("type"));
        assertTrue(first.getBoolean("pinned"));
        assertTrue(first.getBoolean("creator_liked"));
        assertEquals(2, first.getInt("images"));
        assertEquals("1", comments.getJSONObject(1).getString("parent_cid"));
        assertEquals("reply", comments.getJSONObject(1).getString("type"));
        for (String key : new String[]{"cid", "username", "nickname", "created_at", "created_epoch",
                "likes", "reply_count"}) {
            assertTrue(key, first.has(key));
        }
    }

    // ---- reading the loaded set ---------------------------------------------------------

    @Test public void previewRepliesAreFlattenedOnceAndPointAtTheirParent() {
        Comment parent = new Comment("10", "top", "a", "A");
        parent.replyCommentTotal = 7;
        parent.stickPosition = 1;
        parent.isAuthorDigged = true;
        parent.imageList.add(new Object());
        Comment preview = new Comment("11", "preview reply", "b", "B");
        parent.replyComments.add(preview);
        // The same reply, also bound as a row of its own once the thread opened.
        Comment opened = new Comment("11", "preview reply", "b", "B");
        opened.rootCommentId = "10";
        Comment second = new Comment("12", "opened reply", "c", "C");
        second.rootCommentId = "10";
        Comment other = new Comment("20", "another top", "d", "D");

        List<CommentExport.Row> rows = CommentExport.collect(Arrays.asList(parent, opened, second, other));

        assertEquals(4, rows.size());
        assertEquals(2, CommentExport.topLevel(rows));
        assertEquals("10", rows.get(0).id);
        assertEquals("", rows.get(0).parentId);
        assertEquals(7, rows.get(0).replyCount);
        assertTrue(rows.get(0).pinned);
        assertTrue(rows.get(0).creatorLiked);
        assertEquals(1, rows.get(0).images);
        assertEquals("11", rows.get(1).id);
        assertEquals("10", rows.get(1).parentId);
        assertEquals("12", rows.get(2).id);
        assertEquals("10", rows.get(2).parentId);
        assertEquals(4, CommentExport.count(Arrays.asList(parent, opened, second, other)));
    }

    @Test public void onlyThreadsShortOfTikToksCountAreLoaded() {
        Comment shortThread = new Comment("10", "top", "a", "A");
        shortThread.replyCommentTotal = 5;
        shortThread.replyComments.add(new Comment("11", "preview", "b", "B"));
        Comment whole = new Comment("20", "whole", "c", "C");
        whole.replyCount = 1;
        whole.replyComments.add(new Comment("21", "the only reply", "d", "D"));
        Comment none = new Comment("30", "no replies", "e", "E");
        // A reply TikTok counts replies under is still part of its parent's thread.
        Comment reply = new Comment("12", "reply", "f", "F");
        reply.rootCommentId = "10";
        reply.replyCount = 4;

        List<ReplyPages.Missing> missing = CommentExport.missingReplies(
                CommentExport.collect(Arrays.asList(shortThread, whole, none, reply)));

        assertEquals(1, missing.size());
        assertEquals("10", missing.get(0).threadId);
        assertEquals(5, missing.get(0).expected);
        assertEquals(new java.util.HashSet<>(Arrays.asList("11", "12")), missing.get(0).known);
    }

    @Test public void fetchedRepliesLandAfterTheirThreadOnceEach() {
        Comment first = new Comment("10", "top", "a", "A");
        first.replyComments.add(new Comment("11", "preview", "b", "B"));
        Comment second = new Comment("20", "second", "c", "C");
        Comment third = new Comment("30", "third", "d", "D");
        List<CommentExport.Row> rows = CommentExport.collect(Arrays.asList(first, second, third));
        Map<String, List<Object>> fetched = new LinkedHashMap<>();
        // The page brings the preview again, and a reply whose model doesn't name its parent.
        fetched.put("10", Arrays.asList(new Comment("11", "preview", "b", "B"), new Comment("12", "paged", "e", "E")));
        fetched.put("30", Collections.singletonList(new Comment("31", "paged too", "f", "F")));

        List<CommentExport.Row> merged = CommentExport.merge(rows, fetched);

        List<String> order = new ArrayList<>();
        for (CommentExport.Row row : merged) order.add(row.id + "<" + row.parentId);
        assertEquals(Arrays.asList("10<", "11<10", "12<10", "20<", "30<", "31<30"), order);
        // The sheet's own rows are left as they were.
        assertEquals(4, rows.size());
        assertTrue(CommentExport.merge(rows, Collections.emptyMap()) == rows);
    }

    @Test public void theVideoIsTheFirstCommentThatNamesOne() {
        Comment unnamed = new Comment("1", "one", "a", "A");
        Comment zero = new Comment("2", "two", "b", "B");
        zero.awemeId = "0";
        Comment named = new Comment("3", "three", "c", "C");
        named.awemeId = "7300000000000000001";
        assertEquals("7300000000000000001", CommentExport.itemIdOf(Arrays.asList(unnamed, zero, named)));
        assertNull(CommentExport.itemIdOf(Arrays.asList(unnamed, zero)));
        assertNull(CommentExport.itemIdOf(null));
    }

    @Test public void aJsonSaysWhetherEveryReplyThreadLoaded() throws Exception {
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", "top"), row("2", "1", "reply"));
        JSONObject whole = new JSONObject(CommentExport.toJson(rows, "2026-11-14T00:00:00Z"));
        assertTrue(whole.getBoolean("replies_complete"));
        assertTrue(whole.getString("note"), whole.getString("note").contains("Every reply thread was loaded"));
        JSONObject cut = new JSONObject(CommentExport.toJson(rows, "2026-11-14T00:00:00Z", true, false));
        assertFalse(cut.getBoolean("replies_complete"));
        assertTrue(cut.getString("note"), cut.getString("note").contains("Some replies couldn't be loaded"));
        assertTrue(cut.getString("note"), cut.getString("note").contains("more comments loaded than Hushfeed keeps"));
    }

    @Test public void aModelThatCannotBeReadStillExportsAnEmptyRowRatherThanFailing() {
        List<CommentExport.Row> rows = CommentExport.collect(Arrays.asList(new Object(), null));
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).text);
        assertEquals(0, CommentExport.collect(null).size());
    }

    // ---- gating -------------------------------------------------------------------------

    @Test public void exportIsOffByDefault() {
        assertEquals(Boolean.FALSE, Settings.COMMENT_EXPORT.defaultValue);
        assertFalse(CommentExport.enabled());
    }

    private static LinearLayout sheet(Activity activity, LinearLayout column, Comment... comments) {
        LinearLayout listView = new LinearLayout(activity);
        listView.setOrientation(LinearLayout.VERTICAL);
        column.addView(listView);
        for (Comment comment : comments) {
            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(row);
            CommentSearch.onCellBound(row, comment);
        }
        shadowOf(Looper.getMainLooper()).idle();
        return listView;
    }

    @Test public void withTheSwitchOffTheSearchBoxGainsNoButtons() {
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            Settings.COMMENT_EXPORT.save(false);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(column);
            sheet(activity, column, new Comment("1", "one", "a", "A"));

            assertNull(column.findViewWithTag(CommentSearch.EXPORT_TAG));
            // The box, its status line and the list: what the search alone adds.
            assertEquals(3, column.getChildCount());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void withTheSwitchOnTheCountMatchesWhatTheFileHolds() throws Exception {
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            Settings.COMMENT_EXPORT.save(true);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(column);
            Comment parent = new Comment("1", "top, with a comma", "a", "A");
            parent.replyComments.add(new Comment("2", "a preview reply", "b", "B"));
            sheet(activity, column, parent, new Comment("3", "second 😀", "c", "C"));

            View exports = column.findViewWithTag(CommentSearch.EXPORT_TAG);
            assertNotNull(exports);
            TextView count = (TextView) exports.findViewWithTag(CommentSearch.EXPORT_COUNT_TAG);
            assertEquals("3 comments and replies loaded", count.getText().toString());

            LinearLayout buttons = (LinearLayout) exports;
            TextView csv = (TextView) buttons.getChildAt(1);
            assertEquals("Export CSV", csv.getText().toString());
            assertEquals("Export JSON", ((TextView) buttons.getChildAt(2)).getText().toString());

            var provider = DocumentExportProvider.register(activity);
            csv.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            var started = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull("the file picker was never opened", started);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
            assertEquals("text/csv", started.intent.getType());
            assertTrue(started.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".csv"));

            answer(activity, started.requestCode, provider);
            awaitWorker(DocumentOperation.Kind.COMMENT_FILE);
            String written = new String(Files.readAllBytes(provider.file.toPath()), StandardCharsets.UTF_8);
            assertTrue(written.startsWith("﻿"));
            List<List<String>> parsed = parseCsv(written.substring(1));
            // A header and one record per comment or reply the panel counted.
            assertEquals(1 + 3, parsed.size());
            assertEquals("top, with a comma", parsed.get(1).get(5));
            assertEquals("reply", parsed.get(2).get(2));
            assertEquals("1", parsed.get(2).get(1));
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
            Settings.COMMENT_EXPORT.save(false);
        }
    }

    // ---- loading the replies the sheet didn't show --------------------------------------

    /** TikTok's reply call, holding its one page until the test lets it go. */
    private static final class HeldPages implements ReplyPages.Fetcher {
        final CountDownLatch asked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Object> items;
        final boolean more;

        HeldPages(boolean more, Object... items) {
            this.more = more;
            this.items = Arrays.asList(items);
        }

        @Override
        public ReplyPages.Page page(String threadId, String itemId, long cursor, int count, ReplyPages.Load load)
                throws InterruptedException {
            calls.add(threadId + "@" + cursor + "/" + itemId);
            asked.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) return null;
            return new ReplyPages.Page(items, cursor + items.size(), more);
        }
    }

    /** A parent showing one of {@code total} replies, under video 7300, and a second comment. */
    private static Comment[] threadShortOf(long total) {
        Comment parent = new Comment("1", "top", "a", "A");
        parent.awemeId = "7300";
        parent.replyCommentTotal = total;
        parent.replyComments.add(new Comment("2", "a preview reply", "b", "B"));
        Comment second = new Comment("3", "second", "c", "C");
        second.awemeId = "7300";
        return new Comment[]{parent, second};
    }

    private static void awaitReplies() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (CommentExport.loadingReplies() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("the replies never finished loading", CommentExport.loadingReplies());
    }

    /** Whatever a failed test left running ends here, so the next test starts with no load. */
    private static void endReplies(HeldPages pages) throws Exception {
        ReplyPages.setFetcherForTests(null);
        pages.release.countDown();
        CommentExport.stopLoadingReplies();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (CommentExport.loadingReplies() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        CommentSearch.setQuery("");
        Settings.COMMENT_SEARCH.save(false);
        Settings.COMMENT_EXPORT.save(false);
    }

    private static LinearLayout exportRow(Activity activity, Comment... comments) {
        Utils.setContext(activity);
        Settings.COMMENT_SEARCH.save(true);
        Settings.COMMENT_EXPORT.save(true);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(column);
        sheet(activity, column, comments);
        return (LinearLayout) column.findViewWithTag(CommentSearch.EXPORT_TAG);
    }

    private static List<String> csvThreads(DocumentExportProvider provider) throws Exception {
        String written = new String(Files.readAllBytes(provider.file.toPath()), StandardCharsets.UTF_8);
        List<List<String>> parsed = parseCsv(written.substring(1));
        List<String> ids = new ArrayList<>();
        for (List<String> record : parsed.subList(1, parsed.size())) ids.add(record.get(0) + "<" + record.get(1));
        return ids;
    }

    @Test public void exportLoadsEveryReplyThreadFirstAndShowsHowFarItGot() throws Exception {
        HeldPages pages = new HeldPages(false, new Comment("2", "a preview reply", "b", "B"),
                new Comment("4", "paged reply", "d", "D"), new Comment("5", "paged reply two", "e", "E"));
        ReplyPages.setFetcherForTests(pages);
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            LinearLayout exports = exportRow(activity, threadShortOf(3));
            TextView count = (TextView) exports.findViewWithTag(CommentSearch.EXPORT_COUNT_TAG);
            TextView csv = (TextView) exports.getChildAt(1);
            TextView json = (TextView) exports.getChildAt(2);
            assertEquals("3 comments and replies loaded", count.getText().toString());

            var provider = DocumentExportProvider.register(activity);
            csv.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the reply call was never made", pages.asked.await(5, TimeUnit.SECONDS));
            assertEquals("Loading replies, 0 of 2", count.getText().toString());
            assertEquals("Stop", csv.getText().toString());
            assertFalse(json.isEnabled());
            assertNull("the picker opened before the replies loaded",
                    shadowOf(activity).getNextStartedActivityForResult());
            // A second tap while they load says how far it got rather than starting over.
            json.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Loading replies, 0 of 2", bannerText(activity));

            pages.release.countDown();
            awaitReplies();
            assertEquals(Collections.singletonList("1@0/7300"), pages.calls);
            assertEquals("Export CSV", csv.getText().toString());
            assertTrue(json.isEnabled());
            assertEquals("3 comments and replies loaded", count.getText().toString());

            var started = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull("the file picker was never opened", started);
            assertEquals("text/csv", started.intent.getType());
            answer(activity, started.requestCode, provider);
            awaitWorker(DocumentOperation.Kind.COMMENT_FILE);
            // The paged replies follow the one the sheet showed, and the preview isn't written twice.
            assertEquals(Arrays.asList("1<", "2<1", "4<1", "5<1", "3<"), csvThreads(provider));
            assertEquals("Exported 2 comments and 3 replies", bannerText(activity));
        } finally {
            endReplies(pages);
        }
    }

    @Test public void stopEndsTheLoadAndTheFileSaysSomeRepliesAreMissing() throws Exception {
        HeldPages pages = new HeldPages(true, new Comment("4", "paged reply", "d", "D"));
        ReplyPages.setFetcherForTests(pages);
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            LinearLayout exports = exportRow(activity, threadShortOf(30));
            TextView csv = (TextView) exports.getChildAt(1);
            TextView json = (TextView) exports.getChildAt(2);

            var provider = DocumentExportProvider.register(activity);
            json.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the reply call was never made", pages.asked.await(5, TimeUnit.SECONDS));
            assertEquals("Stop", json.getText().toString());
            assertFalse(csv.isEnabled());
            json.performClick();
            pages.release.countDown();
            awaitReplies();
            // The page in flight is kept, and nothing after it is asked for.
            assertEquals(1, pages.calls.size());
            assertEquals("Export JSON", json.getText().toString());

            var started = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull("the stop didn't go on to the export", started);
            assertEquals("application/json", started.intent.getType());
            answer(activity, started.requestCode, provider);
            awaitWorker(DocumentOperation.Kind.COMMENT_FILE);
            JSONObject root = new JSONObject(new String(Files.readAllBytes(provider.file.toPath()), StandardCharsets.UTF_8));
            assertFalse(root.getBoolean("replies_complete"));
            assertEquals(4, root.getInt("total"));
            JSONObject paged = root.getJSONArray("comments").getJSONObject(2);
            assertEquals("4", paged.getString("cid"));
            assertEquals("1", paged.getString("parent_cid"));
            assertEquals("Exported 2 comments and 2 replies\nSome replies weren't loaded, so they aren't in the file.",
                    bannerText(activity));
        } finally {
            endReplies(pages);
        }
    }

    @Test public void aLoadThatEndsWithTikTokInTheBackgroundOpensThePickerOnItsReturn() throws Exception {
        HeldPages pages = new HeldPages(false, new Comment("4", "paged reply", "d", "D"));
        ReplyPages.setFetcherForTests(pages);
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            LinearLayout exports = exportRow(activity, threadShortOf(2));
            TextView csv = (TextView) exports.getChildAt(1);

            csv.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the reply call was never made", pages.asked.await(5, TimeUnit.SECONDS));
            controller.pause().stop();
            pages.release.countDown();
            awaitReplies();
            assertNull("the picker was started from the background",
                    shadowOf(activity).getNextStartedActivityForResult());

            controller.restart().start().resume();
            shadowOf(Looper.getMainLooper()).idle();
            var started = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull("the export was lost while TikTok was away", started);
            assertEquals("text/csv", started.intent.getType());
            // A tap on the button after the load, while the picker is up, starts nothing more.
            csv.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(activity).getNextStartedActivityForResult());
        } finally {
            endReplies(pages);
        }
    }

    @Test public void aThreadOpenedAfterScrollingOnIsExportedUnderItsComment() {
        Comment first = new Comment("10", "top", "a", "A");
        Comment second = new Comment("20", "second", "c", "C");
        // Shown when the thread was opened, after the sheet had already shown the second comment.
        Comment opened = new Comment("15", "opened later", "g", "G");
        opened.rootCommentId = "10";
        opened.createTime = 1_700_000_500;
        List<CommentExport.Row> rows = CommentExport.collect(Arrays.asList(first, second, opened));
        Comment older = new Comment("12", "paged", "e", "E");
        older.createTime = 1_700_000_100;
        Map<String, List<Object>> fetched = new LinkedHashMap<>();
        fetched.put("10", Collections.singletonList(older));

        List<String> order = new ArrayList<>();
        for (CommentExport.Row row : CommentExport.merge(rows, fetched)) order.add(row.id + "<" + row.parentId);

        assertEquals(Arrays.asList("10<", "12<10", "15<10", "20<"), order);
    }

    @Test public void closingTheSheetStopsTheLoadAndExportsWhatCame() throws Exception {
        HeldPages pages = new HeldPages(true, new Comment("4", "paged reply", "d", "D"));
        ReplyPages.setFetcherForTests(pages);
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            LinearLayout exports = exportRow(activity, threadShortOf(30));
            TextView csv = (TextView) exports.getChildAt(1);

            var provider = DocumentExportProvider.register(activity);
            csv.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the reply call was never made", pages.asked.await(5, TimeUnit.SECONDS));
            ((ViewGroup) exports.getParent()).removeView(exports);
            pages.release.countDown();
            awaitReplies();
            assertEquals(1, pages.calls.size());

            var started = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull("closing the sheet dropped the export", started);
            answer(activity, started.requestCode, provider);
            awaitWorker(DocumentOperation.Kind.COMMENT_FILE);
            assertEquals(Arrays.asList("1<", "2<1", "4<1", "3<"), csvThreads(provider));
            assertTrue(bannerText(activity), bannerText(activity).endsWith("Some replies weren't loaded, so they aren't in the file."));
        } finally {
            endReplies(pages);
        }
    }

    @Test public void aFileAppThatHangsOffersStopWaitingAndNothingIsExported() throws Exception {
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            Settings.COMMENT_EXPORT.save(true);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(column);
            sheet(activity, column, new Comment("1", "one", "a", "A"));
            TextView json = (TextView) ((LinearLayout) column.findViewWithTag(CommentSearch.EXPORT_TAG)).getChildAt(2);

            var provider = DocumentExportProvider.register(activity);
            provider.honorCancel = true;
            var release = provider.holdOpens();
            LogBufferManager.clearLogBuffer();
            try {
                json.performClick();
                shadowOf(Looper.getMainLooper()).idle();
                var started = shadowOf(activity).getNextStartedActivityForResult();
                assertEquals("application/json", started.intent.getType());
                answer(activity, started.requestCode, provider);
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                shadowOf(Looper.getMainLooper()).idle();
                assertNull("the stop was offered before the file app stalled", bannerAction(activity));

                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DocumentOperation.stallMillisForTests()));
                assertEquals("Still waiting for the file app.", bannerText(activity));
                View stop = bannerAction(activity);
                assertEquals("Stop waiting", ((TextView) stop).getText().toString());
                assertTrue(stop.performClick());
                awaitWorker(DocumentOperation.Kind.COMMENT_FILE);
                // The write was at the file app's open, past the gate, so the stop can't say it was clean.
                assertEquals("The export wasn't saved. Try again.",
                        bannerText(activity));
                DocumentOperationTest.assertStopLoggedAsInfo("Comment export failed");
            } finally {
                release.countDown();
            }
            assertEquals(0, provider.file.length());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
            Settings.COMMENT_EXPORT.save(false);
        }
    }

    private static void answer(Activity activity, int requestCode, DocumentExportProvider provider) {
        android.app.Fragment request = activity.getFragmentManager().findFragmentByTag("hushfeed_comment_export");
        assertNotNull("the picker's fragment is missing", request);
        request.onActivityResult(requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
    }

    private static void awaitWorker(DocumentOperation.Kind kind) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (DocumentOperation.busy(kind) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("the file worker never returned", DocumentOperation.busy(kind));
    }

    private static String bannerText(Activity activity) {
        View label = activity.findViewById(android.R.id.content).findViewWithTag("hushfeed_settings_action_message");
        return label instanceof TextView ? ((TextView) label).getText().toString() : null;
    }

    private static View bannerAction(Activity activity) {
        return activity.findViewById(android.R.id.content).findViewWithTag("hushfeed_settings_action_button");
    }
}
