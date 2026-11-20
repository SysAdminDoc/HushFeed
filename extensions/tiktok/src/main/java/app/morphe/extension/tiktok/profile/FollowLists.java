/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.comment.AccountCountry;
import app.morphe.extension.tiktok.comment.CommentExport;

/**
 * Your followers, or the accounts you follow, read page by page through TikTok's own client (so
 * every request is signed the way the app's follower and following lists sign theirs) and
 * written out as a CSV file.
 *
 * <p>The pages are asked for one after another with a pause between them, about as fast as a
 * person scrolls the list, and the run stops at {@link #MAX_ROWS}. Each page says whether there
 * is another and where it starts: older replies by time ({@code min_time}), newer ones by a page
 * token as well. Both are followed, and a page that brings nobody new ends the run, so a reply
 * that keeps pointing at the same page can't loop.</p>
 */
public final class FollowLists {
    static final String FOLLOWERS_PATH = "/aweme/v1/user/follower/list/";
    static final String FOLLOWING_PATH = "/aweme/v1/user/following/list/";
    /** Accounts asked for a page, what TikTok's own lists ask for. */
    static final int PAGE = 20;
    /** At most this many accounts a run: 500 pages, about ten minutes of reading. */
    public static final int MAX_ROWS = 10_000;
    static final long PAGE_GAP_MS = 1000;

    static final String[] COLUMNS = {
            "username", "nickname", "profile_url", "user_id", "sec_uid", "you_follow", "follows_you"
    };

    public enum Kind { FOLLOWERS, FOLLOWING }

    /** How a read ended. */
    public enum End {
        /** The list's last page was read. */
        DONE,
        /** Stop was tapped, or the account changed, before the last page. */
        STOPPED,
        /** The list ran past {@link #MAX_ROWS}. */
        CAPPED,
        /** TikTok's reply was an error or couldn't be read. */
        FAILED,
        /** Nobody is signed in. */
        SIGNED_OUT
    }

    /** One request. The tests answer with canned pages. */
    interface Source {
        String get(String path, Map<String, String> query) throws Exception;
    }

    /** Hears the running count after each page. */
    public interface Progress {
        void read(int count);
    }

    static volatile Source sourceForTests;
    static volatile long gapForTests = -1;

    /** One account as a follow list sends it. */
    public static final class Account {
        final String uid;
        final String handle;
        final String nickname;
        final String secUid;
        /** Yours towards them: 0 none, 1 following, 2 mutual. */
        final int followStatus;
        /** Theirs towards you: 1 when they follow you. */
        final int followerStatus;

        Account(String uid, String handle, String nickname, String secUid, int followStatus, int followerStatus) {
            this.uid = uid;
            this.handle = handle;
            this.nickname = nickname;
            this.secUid = secUid;
            this.followStatus = followStatus;
            this.followerStatus = followerStatus;
        }

        boolean youFollow() {
            return followStatus == FollowStatus.FOLLOWING || followStatus == FollowStatus.MUTUAL;
        }

        boolean followsYou() {
            return followerStatus == FollowStatus.FOLLOWING || followStatus == FollowStatus.MUTUAL;
        }
    }

    /** One reply: its accounts and where the next page starts. */
    static final class Page {
        final List<Account> accounts;
        final boolean more;
        final long minTime;
        @Nullable final String pageToken;

        Page(List<Account> accounts, boolean more, long minTime, @Nullable String pageToken) {
            this.accounts = accounts;
            this.more = more;
            this.minTime = minTime;
            this.pageToken = pageToken;
        }
    }

    /** What a run read and how it ended. */
    public static final class Result {
        public final List<Account> accounts;
        public final End end;

        Result(List<Account> accounts, End end) {
            this.accounts = accounts;
            this.end = end;
        }
    }

    private FollowLists() {
    }

    static String pathOf(Kind kind) {
        return kind == Kind.FOLLOWERS ? FOLLOWERS_PATH : FOLLOWING_PATH;
    }

    /**
     * Reads one reply. A reply with an error status, or without the list, throws: an empty list
     * the reply does carry is a page with nobody on it.
     */
    static Page parse(String json, Kind kind) throws JSONException {
        JSONObject root = new JSONObject(json);
        int status = root.optInt("status_code", 0);
        if (status != 0) throw new JSONException("status " + status);
        JSONArray list = null;
        boolean found = false;
        for (String key : kind == Kind.FOLLOWERS
                ? new String[] {"followers", "users", "user_list"}
                : new String[] {"followings", "following", "users", "user_list"}) {
            if (!root.has(key)) continue;
            found = true;
            if (!root.isNull(key)) {
                list = root.optJSONArray(key);
                if (list == null) throw new JSONException(key + " isn't a list");
            }
            break;
        }
        if (!found) throw new JSONException("no list in the reply");
        List<Account> accounts = new ArrayList<>();
        if (list != null) {
            for (int index = 0; index < list.length(); index++) {
                JSONObject user = list.optJSONObject(index);
                if (user == null) continue;
                String uid = user.optString("uid", "");
                if (uid.isEmpty()) continue;
                accounts.add(new Account(uid, user.optString("unique_id", ""), user.optString("nickname", ""),
                        user.optString("sec_uid", ""), user.optInt("follow_status", 0),
                        user.optInt("follower_status", 0)));
            }
        }
        String token = root.optString("next_page_token", "");
        return new Page(accounts, flag(root.opt("has_more")), root.optLong("min_time", 0),
                token.isEmpty() ? null : token);
    }

    private static boolean flag(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return "true".equals(value) || "1".equals(value);
    }

    /** The signed-in account's list, read through TikTok's client. */
    public static Result read(Kind kind, AtomicBoolean stopped, Progress progress) {
        String uid = SignedInUser.id();
        String secUid = SignedInUser.secId();
        if (uid == null) return new Result(Collections.emptyList(), End.SIGNED_OUT);
        Source forTests = sourceForTests;
        long gap = gapForTests >= 0 ? gapForTests : PAGE_GAP_MS;
        return read(kind, uid, secUid, forTests != null ? forTests : AccountCountry::fetch, gap, stopped, progress);
    }

    static Result read(Kind kind, String uid, @Nullable String secUid, Source source, long gap,
            AtomicBoolean stopped, @Nullable Progress progress) {
        List<Account> accounts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long maxTime = System.currentTimeMillis() / 1000L;
        String token = null;
        boolean first = true;
        int idle = 0;
        while (true) {
            if (stopped.get() || !uid.equals(SignedInUser.id())) return new Result(accounts, End.STOPPED);
            if (!first && gap > 0) {
                try {
                    Thread.sleep(gap);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Result(accounts, End.STOPPED);
                }
                if (stopped.get()) return new Result(accounts, End.STOPPED);
            }
            Map<String, String> query = new LinkedHashMap<>();
            query.put("user_id", uid);
            if (secUid != null && !secUid.isEmpty()) query.put("sec_user_id", secUid);
            query.put("count", String.valueOf(PAGE));
            query.put("max_time", String.valueOf(maxTime));
            query.put("offset", String.valueOf(accounts.size()));
            query.put("source_type", "1");
            query.put("address_book_access", "2");
            if (token != null) query.put("page_token", token);
            Page page;
            try {
                String json = source.get(pathOf(kind), query);
                if (json == null) throw new JSONException("no reply");
                if (first) logShape(kind, json);
                page = parse(json, kind);
            } catch (Exception failure) {
                int read = accounts.size();
                Logger.printException(() -> "Follow list " + kind + " stopped after " + read, failure);
                return new Result(accounts, End.FAILED);
            }
            first = false;
            int before = accounts.size();
            for (Account account : page.accounts) {
                if (!seen.add(account.uid)) continue;
                if (accounts.size() >= MAX_ROWS) return new Result(accounts, End.CAPPED);
                accounts.add(account);
            }
            if (progress != null) progress.read(accounts.size());
            if (!page.more) return new Result(accounts, End.DONE);
            long nextTime = page.minTime > 0 ? page.minTime : maxTime;
            boolean moved = nextTime != maxTime || !java.util.Objects.equals(page.pageToken, token);
            // A page of nobody new ends the run when it points back at itself, or the third
            // time in a row: TikTok leaves out accounts it hides, so one empty page can be real.
            idle = accounts.size() == before ? idle + 1 : 0;
            if (idle > 0 && (!moved || idle >= 3)) return new Result(accounts, End.DONE);
            maxTime = nextTime;
            token = page.pageToken;
        }
    }

    /** Who the check found that doesn't follow you back, and how the check ended. */
    public static final class NotFollowingBack {
        /** Each account's id with its secure id (empty when the list had none), in following-list order. */
        public final Map<String, String> accounts;
        /** DONE when both lists were read far enough to trust, otherwise why the check stopped. */
        public final End end;

        public NotFollowingBack(Map<String, String> accounts, End end) {
            this.accounts = Collections.unmodifiableMap(accounts);
            this.end = end;
        }
    }

    /** Hears the count as the following list, then the followers list, is read. */
    public interface CheckProgress {
        void following(int count);

        void followers(int count);
    }

    /**
     * Reads who you follow and then your followers, and keeps the accounts that don't follow you
     * back. The followers list is only read when the following list has someone to check.
     */
    public static NotFollowingBack notFollowingBack(AtomicBoolean stopped, CheckProgress progress) {
        Result following = read(Kind.FOLLOWING, stopped, progress::following);
        if (!complete(following.end)) return new NotFollowingBack(new LinkedHashMap<>(), following.end);
        Map<String, String> candidates = notFollowingBack(following.accounts, Collections.emptyList());
        if (candidates.isEmpty()) return new NotFollowingBack(candidates, End.DONE);
        progress.followers(0);
        Result followers = read(Kind.FOLLOWERS, stopped, progress::followers);
        if (!complete(followers.end)) return new NotFollowingBack(new LinkedHashMap<>(), followers.end);
        return new NotFollowingBack(notFollowingBack(following.accounts, followers.accounts), End.DONE);
    }

    /** A list read to its end, or to the cap: what was read can be trusted either way. */
    private static boolean complete(End end) {
        return end == End.DONE || end == End.CAPPED;
    }

    /**
     * The accounts on your following list that the list itself marks as followed and not
     * mutual, and that aren't on your followers list. The followers list only ever spares an
     * account, so one that was cut short can never add anyone to unfollow.
     */
    static Map<String, String> notFollowingBack(List<Account> following, List<Account> followers) {
        Set<String> fans = new HashSet<>();
        for (Account follower : followers) fans.add(follower.uid);
        Map<String, String> out = new LinkedHashMap<>();
        for (Account account : following) {
            if (account.followStatus != FollowStatus.FOLLOWING || account.followsYou()) continue;
            if (fans.contains(account.uid)) continue;
            out.put(account.uid, account.secUid);
        }
        return out;
    }

    /** The first reply's top-level names and paging fields, never its accounts, for a report. */
    private static void logShape(Kind kind, String json) {
        try {
            JSONObject root = new JSONObject(json);
            Set<String> keys = new TreeSet<>();
            for (Iterator<String> names = root.keys(); names.hasNext(); ) keys.add(names.next());
            Logger.printInfo(() -> "Follow list " + kind + " first reply: status=" + root.optInt("status_code", -1)
                    + " has_more=" + root.opt("has_more") + " min_time=" + (root.optLong("min_time", 0) > 0)
                    + " offset=" + root.opt("offset") + " total=" + root.opt("total")
                    + " page_token=" + root.has("next_page_token") + " keys=" + keys);
        } catch (JSONException unreadable) {
            Logger.printInfo(() -> "Follow list " + kind + " first reply isn't JSON");
        }
    }

    /** RFC 4180 rows, one an account, behind a header; the fields are quoted as the comment export quotes them. */
    public static String toCsv(List<Account> accounts) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < COLUMNS.length; index++) {
            if (index > 0) out.append(',');
            out.append(COLUMNS[index]);
        }
        out.append("\r\n");
        for (Account account : accounts) {
            CommentExport.appendField(out, account.handle).append(',');
            CommentExport.appendField(out, account.nickname).append(',');
            CommentExport.appendField(out, account.handle.isEmpty() ? "" : "https://www.tiktok.com/@" + account.handle).append(',');
            CommentExport.appendField(out, account.uid).append(',');
            CommentExport.appendField(out, account.secUid).append(',');
            CommentExport.appendField(out, Boolean.toString(account.youFollow())).append(',');
            CommentExport.appendField(out, Boolean.toString(account.followsYou()));
            out.append("\r\n");
        }
        return out.toString();
    }
}
