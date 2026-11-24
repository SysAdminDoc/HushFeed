/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.profile.FollowLists.Account;
import app.morphe.extension.tiktok.profile.FollowLists.End;
import app.morphe.extension.tiktok.profile.FollowLists.Kind;
import app.morphe.extension.tiktok.profile.FollowLists.Result;

/**
 * Your followers and following read page by page as TikTok's lists read them: each page's own
 * cursor followed, nobody listed twice, a reply that points back at itself or a run of empty
 * pages ending the read, an error or Stop keeping what was read, and the file's fields quoted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "en")
public class FollowListsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<Map<String, String>> asked = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    @Before public void setUp() {
        SignedInUser.idForTests = "100";
    }

    @After public void tearDown() {
        SignedInUser.idForTests = null;
    }

    private static JSONObject user(String uid, int followStatus, int followerStatus) throws JSONException {
        return new JSONObject().put("uid", uid).put("unique_id", "user" + uid).put("nickname", "Name " + uid)
                .put("sec_uid", "MS4w" + uid).put("follow_status", followStatus).put("follower_status", followerStatus);
    }

    private static String page(String list, boolean more, long minTime, String token, String... uids) throws JSONException {
        JSONArray users = new JSONArray();
        for (String uid : uids) users.put(user(uid, 0, 1));
        JSONObject root = new JSONObject().put("status_code", 0).put(list, users).put("has_more", more)
                .put("min_time", minTime);
        if (token != null) root.put("next_page_token", token);
        return root.toString();
    }

    /** Answers the pages in order, noting each request. */
    private FollowLists.Source pages(String... replies) {
        int[] next = {0};
        return (path, query) -> {
            paths.add(path);
            asked.add(new LinkedHashMap<>(query));
            if (next[0] >= replies.length) throw new AssertionError("asked for page " + (next[0] + 1));
            return replies[next[0]++];
        };
    }

    private Result read(Kind kind, FollowLists.Source source) {
        return FollowLists.read(kind, "100", "MS4wown", source, 0, new AtomicBoolean(), null);
    }

    private static List<String> uids(Result result) {
        List<String> out = new ArrayList<>();
        for (Account account : result.accounts) out.add(account.uid);
        return out;
    }

    @Test public void aPageGivesItsAccountsAndWhereTheNextStarts() throws JSONException {
        JSONObject root = new JSONObject(page("followers", true, 1700000000L, "tok", "1", "2"));
        root.getJSONArray("followers").put(new JSONObject().put("nickname", "no uid"));
        FollowLists.Page parsed = FollowLists.parse(root.toString(), Kind.FOLLOWERS);
        assertEquals(2, parsed.accounts.size());
        assertEquals("user1", parsed.accounts.get(0).handle);
        assertEquals("MS4w2", parsed.accounts.get(1).secUid);
        assertTrue(parsed.more);
        assertEquals(1700000000L, parsed.minTime);
        assertEquals("tok", parsed.pageToken);

        FollowLists.Page following = FollowLists.parse(
                "{\"status_code\":0,\"followings\":null,\"has_more\":0}", Kind.FOLLOWING);
        assertTrue(following.accounts.isEmpty());
        assertFalse(following.more);
        assertNull(following.pageToken);
    }

    @Test public void anErrorOrAReplyWithoutTheListIsNoPage() {
        for (String reply : new String[] {"{\"status_code\":8,\"followers\":[]}", "{\"status_code\":0}",
                "{\"status_code\":0,\"followers\":{}}", "not json"}) {
            try {
                FollowLists.parse(reply, Kind.FOLLOWERS);
                fail(reply);
            } catch (JSONException expected) {
                // The run ends with what it read before this page.
            }
        }
    }

    @Test public void everyPageIsAskedForFromWhereTheLastOneSaid() throws JSONException {
        Result result = read(Kind.FOLLOWERS, pages(
                page("followers", true, 1700000300L, null, "1", "2"),
                page("followers", true, 1700000200L, "next", "2", "3"),
                page("followers", false, 0, null, "4")));

        assertEquals(End.DONE, result.end);
        assertEquals(Arrays.asList("1", "2", "3", "4"), uids(result));
        assertEquals(Arrays.asList(FollowLists.FOLLOWERS_PATH, FollowLists.FOLLOWERS_PATH, FollowLists.FOLLOWERS_PATH), paths);
        assertEquals("100", asked.get(0).get("user_id"));
        assertEquals("MS4wown", asked.get(0).get("sec_user_id"));
        assertEquals("20", asked.get(0).get("count"));
        assertEquals("0", asked.get(0).get("offset"));
        assertFalse(asked.get(0).containsKey("page_token"));
        assertEquals("1700000300", asked.get(1).get("max_time"));
        assertEquals("2", asked.get(1).get("offset"));
        assertEquals("1700000200", asked.get(2).get("max_time"));
        assertEquals("3", asked.get(2).get("offset"));
        assertEquals("next", asked.get(2).get("page_token"));
    }

    @Test public void theFollowingListIsReadFromItsOwnPath() throws JSONException {
        Result result = read(Kind.FOLLOWING, pages(page("followings", false, 0, null, "7")));
        assertEquals(Arrays.asList("7"), uids(result));
        assertEquals(Arrays.asList(FollowLists.FOLLOWING_PATH), paths);
    }

    @Test public void aReplyThatPointsBackAtItselfEndsTheRead() throws JSONException {
        String same = page("followers", true, 1700000100L, null, "1", "2");
        Result result = read(Kind.FOLLOWERS, pages(same, same, same));
        assertEquals(End.DONE, result.end);
        assertEquals(Arrays.asList("1", "2"), uids(result));
        assertEquals(2, asked.size());
    }

    @Test public void anEmptyPageThatMovesOnIsFollowedButThreeInARowEndTheRead() throws JSONException {
        Result result = read(Kind.FOLLOWERS, pages(
                page("followers", true, 300, null, "1"),
                page("followers", true, 200, null),
                page("followers", false, 0, null, "2")));
        assertEquals(Arrays.asList("1", "2"), uids(result));

        asked.clear();
        result = read(Kind.FOLLOWERS, pages(
                page("followers", true, 500, null, "1"),
                page("followers", true, 400, null),
                page("followers", true, 300, null),
                page("followers", true, 200, null),
                page("followers", true, 100, null, "never asked for")));
        assertEquals(End.DONE, result.end);
        assertEquals(Arrays.asList("1"), uids(result));
        assertEquals(4, asked.size());
    }

    @Test public void anErrorKeepsWhatWasReadBeforeIt() throws JSONException {
        Result result = read(Kind.FOLLOWERS, pages(
                page("followers", true, 300, null, "1", "2"),
                "{\"status_code\":2483,\"status_msg\":\"too fast\"}"));
        assertEquals(End.FAILED, result.end);
        assertEquals(Arrays.asList("1", "2"), uids(result));

        result = read(Kind.FOLLOWERS, (path, query) -> {
            throw new java.io.IOException("offline");
        });
        assertEquals(End.FAILED, result.end);
        assertTrue(result.accounts.isEmpty());
    }

    @Test public void stopAndASwitchedAccountEndTheReadWithWhatItHas() throws JSONException {
        AtomicBoolean stopped = new AtomicBoolean();
        Result result = FollowLists.read(Kind.FOLLOWERS, "100", null, pages(
                page("followers", true, 300, null, "1"),
                page("followers", false, 0, null, "2")), 0, stopped, count -> stopped.set(true));
        assertEquals(End.STOPPED, result.end);
        assertEquals(Arrays.asList("1"), uids(result));
        assertFalse("no secure uid is sent when there is none", asked.get(0).containsKey("sec_user_id"));

        asked.clear();
        result = FollowLists.read(Kind.FOLLOWERS, "100", null, pages(
                page("followers", true, 300, null, "1"),
                page("followers", false, 0, null, "2")), 0, new AtomicBoolean(),
                count -> SignedInUser.idForTests = "101");
        assertEquals(End.STOPPED, result.end);
        assertEquals(1, asked.size());
    }

    @Test public void nobodySignedInReadsNothing() {
        SignedInUser.idForTests = "";
        FollowLists.sourceForTests = pages();
        try {
            Result result = FollowLists.read(Kind.FOLLOWERS, new AtomicBoolean(), null);
            assertEquals(End.SIGNED_OUT, result.end);
            assertTrue(asked.isEmpty());
        } finally {
            FollowLists.sourceForTests = null;
        }
    }

    @Test public void aListPastTheCapIsCutThere() {
        int[] served = {0};
        Result result = FollowLists.read(Kind.FOLLOWING, "100", null, (path, query) -> {
            JSONArray users = new JSONArray();
            for (int i = 0; i < FollowLists.PAGE; i++) users.put(user(String.valueOf(++served[0]), 1, 0));
            return new JSONObject().put("status_code", 0).put("followings", users).put("has_more", true)
                    .put("min_time", 2_000_000_000L - served[0]).toString();
        }, 0, new AtomicBoolean(), null);
        assertEquals(End.CAPPED, result.end);
        assertEquals(FollowLists.MAX_ROWS, result.accounts.size());
        assertEquals(FollowLists.MAX_ROWS + FollowLists.PAGE, served[0]);
    }

    @Test public void onlyAccountsTheListMarksFollowedAndNotOnYourFollowersAreFound() {
        List<Account> following = Arrays.asList(
                new Account("1", "plain", "", "MS4w1", FollowStatus.FOLLOWING, 0),
                new Account("2", "friend", "", "MS4w2", FollowStatus.MUTUAL, 1),
                new Account("3", "fan", "", "MS4w3", FollowStatus.FOLLOWING, FollowStatus.FOLLOWING),
                new Account("4", "quiet_fan", "", "MS4w4", FollowStatus.FOLLOWING, 0),
                new Account("5", "unmarked", "", "MS4w5", 0, 0),
                new Account("6", "no_sec", "", "", FollowStatus.FOLLOWING, 0));
        // The followers list knows #4 follows you even though the following list didn't say so.
        List<Account> followers = Arrays.asList(new Account("4", "quiet_fan", "", "MS4w4", 0, 1),
                new Account("9", "stranger", "", "MS4w9", 0, 1));
        Map<String, String> found = FollowLists.notFollowingBack(following, followers);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("1", "MS4w1");
        expected.put("6", "");
        assertEquals(expected, found);
    }

    @Test public void theCheckReadsYourFollowersOnlyWhenSomeoneIsLeftToCheck() throws JSONException {
        List<Integer> followingCounts = new ArrayList<>();
        List<Integer> followerCounts = new ArrayList<>();
        FollowLists.CheckProgress progress = new FollowLists.CheckProgress() {
            @Override public void following(int count) {
                followingCounts.add(count);
            }

            @Override public void followers(int count) {
                followerCounts.add(count);
            }
        };
        String following = new JSONObject().put("status_code", 0).put("has_more", false)
                .put("followings", new JSONArray().put(user("1", 1, 0)).put(user("2", 2, 1)).put(user("3", 1, 0)))
                .toString();
        String followers = new JSONObject().put("status_code", 0).put("has_more", false)
                .put("followers", new JSONArray().put(user("3", 0, 1)).put(user("2", 2, 1))).toString();
        FollowLists.sourceForTests = byPath(following, followers);
        FollowLists.gapForTests = 0;
        try {
            FollowLists.NotFollowingBack check = FollowLists.notFollowingBack(new AtomicBoolean(), progress);
            assertEquals(End.DONE, check.end);
            assertEquals(Arrays.asList("1"), new ArrayList<>(check.accounts.keySet()));
            assertEquals("MS4w1", check.accounts.get("1"));
            assertEquals(Arrays.asList(FollowLists.FOLLOWING_PATH, FollowLists.FOLLOWERS_PATH), paths);
            assertEquals(Arrays.asList(3), followingCounts);
            assertEquals(Arrays.asList(0, 2), followerCounts);

            // Everyone you follow is a friend: the followers list isn't read at all.
            paths.clear();
            FollowLists.sourceForTests = byPath(new JSONObject().put("status_code", 0).put("has_more", false)
                    .put("followings", new JSONArray().put(user("2", 2, 1))).toString(), null);
            check = FollowLists.notFollowingBack(new AtomicBoolean(), progress);
            assertEquals(End.DONE, check.end);
            assertTrue(check.accounts.isEmpty());
            assertEquals(Arrays.asList(FollowLists.FOLLOWING_PATH), paths);
        } finally {
            FollowLists.sourceForTests = null;
            FollowLists.gapForTests = -1;
        }
    }

    @Test public void aFollowersListThatCantBeReadFindsNobody() throws JSONException {
        String following = new JSONObject().put("status_code", 0).put("has_more", false)
                .put("followings", new JSONArray().put(user("1", 1, 0))).toString();
        FollowLists.sourceForTests = byPath(following, "{\"status_code\":8}");
        FollowLists.gapForTests = 0;
        try {
            FollowLists.NotFollowingBack check = FollowLists.notFollowingBack(new AtomicBoolean(), quiet());
            assertEquals(End.FAILED, check.end);
            assertTrue("a half-read check never offers anyone", check.accounts.isEmpty());

            FollowLists.sourceForTests = byPath("{\"status_code\":2483}", null);
            check = FollowLists.notFollowingBack(new AtomicBoolean(), quiet());
            assertEquals(End.FAILED, check.end);
            assertTrue(check.accounts.isEmpty());

            AtomicBoolean stopped = new AtomicBoolean(true);
            FollowLists.sourceForTests = byPath(following, following);
            assertEquals(End.STOPPED, FollowLists.notFollowingBack(stopped, quiet()).end);
        } finally {
            FollowLists.sourceForTests = null;
            FollowLists.gapForTests = -1;
        }
    }

    private static FollowLists.CheckProgress quiet() {
        return new FollowLists.CheckProgress() {
            @Override public void following(int count) {
            }

            @Override public void followers(int count) {
            }
        };
    }

    /** Answers the following list with one reply and the followers list with another, noting each path. */
    private FollowLists.Source byPath(String following, String followers) {
        return (path, query) -> {
            paths.add(path);
            String reply = FollowLists.FOLLOWING_PATH.equals(path) ? following : followers;
            if (reply == null) throw new AssertionError("asked for " + path);
            return reply;
        };
    }

    @Test public void theFileHasOneQuotedRowAnAccount() {
        Account plain = new Account("1", "alice", "Alice", "MS4w1", FollowStatus.MUTUAL, 0);
        Account tricky = new Account("2", "", "=HYPERLINK(\"x\"), \"Bob\"", "", FollowStatus.FOLLOWING, 0);
        Account fan = new Account("3", "carol", "Carol", "MS4w3", 0, FollowStatus.FOLLOWING);
        assertEquals("username,nickname,profile_url,user_id,sec_uid,you_follow,follows_you\r\n"
                        + "alice,Alice,https://www.tiktok.com/@alice,1,MS4w1,true,true\r\n"
                        + ",\"'=HYPERLINK(\"\"x\"\"), \"\"Bob\"\"\",,2,,true,false\r\n"
                        + "carol,Carol,https://www.tiktok.com/@carol,3,MS4w3,false,true\r\n",
                FollowLists.toCsv(Arrays.asList(plain, tricky, fan)));
    }

    @Test public void theJsonFileHasOneObjectAnAccountUnderTheCsvsColumnNames() throws JSONException {
        String separator = String.valueOf((char) 0x2028);
        String name = "Bob \"B\" \\ line\nbreak" + separator + "\t" + (char) 1;
        Account plain = new Account("1", "alice", "Alice", "MS4w1", FollowStatus.MUTUAL, 0);
        Account tricky = new Account("2", "", name, "", FollowStatus.FOLLOWING, 0);
        String json = FollowLists.toJson(Arrays.asList(plain, tricky));
        // The first account as it's written: the CSV's columns in the CSV's order, slashes left alone.
        assertTrue(json, json.startsWith("[\n  {\"username\": \"alice\", \"nickname\": \"Alice\", "
                + "\"profile_url\": \"https://www.tiktok.com/@alice\", \"user_id\": \"1\", \"sec_uid\": \"MS4w1\", "
                + "\"you_follow\": true, \"follows_you\": true},\n  {"));
        assertTrue(json, json.endsWith("}\n]\n"));
        // An account a line: nothing in a name can break the line or the string it sits in.
        assertEquals(json, 4, json.split("\n", -1).length - 1);
        assertFalse(json, json.contains(separator) || json.contains("\t") || json.contains("\r"));
        JSONObject second = new JSONArray(json).getJSONObject(1);
        assertEquals(name, second.getString("nickname"));
        assertEquals("", second.getString("username"));
        assertEquals("", second.getString("profile_url"));
        assertEquals("2", second.getString("user_id"));
        assertTrue(second.getBoolean("you_follow"));
        assertFalse(second.getBoolean("follows_you"));
        assertEquals(FollowLists.COLUMNS.length, second.length());
        assertEquals("[]\n", FollowLists.toJson(new ArrayList<>()));
    }

    @Test public void theTextFileHasOneUsernameALineOrTheIdWhenThereIsNoUsername() {
        Account plain = new Account("1", "alice", "Alice", "MS4w1", FollowStatus.MUTUAL, 0);
        Account nameless = new Account("2", "", "Bob", "", FollowStatus.FOLLOWING, 0);
        Account fan = new Account("3", "carol", "Carol", "MS4w3", 0, FollowStatus.FOLLOWING);
        assertEquals("@alice\n2\n@carol\n", FollowLists.toText(Arrays.asList(plain, nameless, fan)));
        assertEquals("", FollowLists.toText(new ArrayList<>()));
    }

    @Test public void onlyTheCsvFileOpensWithAByteOrderMark() throws JSONException {
        List<Account> one = Arrays.asList(new Account("1", "alice", "Alice", "MS4w1", FollowStatus.MUTUAL, 0));
        char mark = (char) 0xFEFF;
        assertEquals(mark + FollowLists.toCsv(one), FollowLists.Format.CSV.file(one));
        assertEquals(FollowLists.toJson(one), FollowLists.Format.JSON.file(one));
        assertEquals(1, new JSONArray(FollowLists.Format.JSON.file(one)).length());
        assertEquals("@alice\n", FollowLists.Format.TEXT.file(one));
        assertEquals("csv text/csv", FollowLists.Format.CSV.extension + " " + FollowLists.Format.CSV.mimeType);
        assertEquals("json application/json", FollowLists.Format.JSON.extension + " " + FollowLists.Format.JSON.mimeType);
        assertEquals("txt text/plain", FollowLists.Format.TEXT.extension + " " + FollowLists.Format.TEXT.mimeType);
    }
}
