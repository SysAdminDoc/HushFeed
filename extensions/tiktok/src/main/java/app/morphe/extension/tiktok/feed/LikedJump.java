/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.view.View;

import java.util.List;
import java.util.Locale;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Jump to a date on a Liked grid (#350). TikTok pages a Liked list with a cursor that is the time
 * of a like, newest first, and starts a fresh list from cursor 0. {@link DateJump} holds the jump
 * and the chip; this is the Liked request's way in, and the refresh the patch fills in.
 */
@SuppressWarnings("unused")
public final class LikedJump {
    /** The first year the picker offers: musical.ly accounts carried their likes into TikTok. */
    static final int FIRST_YEAR = DateJump.FIRST_YEAR;
    static final String CHIP_TAG = "hushfeed_liked_jump_chip";

    static final DateJump JUMP = new DateJump("Liked", "liked jump", Settings.LIKED_JUMP_TO_DATE, CHIP_TAG,
            "Newest likes", fragment -> refreshGrid(fragment, 0));

    private LikedJump() {
    }

    /**
     * The {@code max_cursor} TikTok's Liked list request goes out with. Called first thing in that
     * request with the profile's ids and the cursor TikTok chose. A later page (any cursor but 0)
     * goes as TikTok asked, and so does a fresh list with no jump, or for another profile.
     */
    public static long cursor(String userId, String secUserId, int type, long maxCursor) {
        String who = userId != null && !userId.isEmpty() ? userId : secUserId == null ? "" : secUserId;
        return JUMP.cursor(who, maxCursor);
    }

    /**
     * Asks the grid's fragment for a fresh list, the way TikTok refreshes it, and says whether it
     * could. The patch replaces this body with the fragment's own refresh, made only when
     * {@code fragment} is a profile grid fragment. Where the patch found none this stays false.
     * {@code spare} is always 0: the patched body uses it as its working register, since the
     * compiled stub may have no register of its own. The Favorites jump uses it too, since a
     * Favorites grid is the same profile tab fragment.
     */
    static boolean refreshGrid(Object fragment, int spare) {
        return false;
    }

    static void listIn(Object adapter, boolean fresh) {
        JUMP.listIn(adapter, fresh);
    }

    static String shownFor(Object adapter) {
        return JUMP.shownFor(adapter);
    }

    static boolean chosen() {
        return JUMP.chosen();
    }

    static void choose(long at, String name) {
        JUMP.choose(at, name);
    }

    static void clear() {
        JUMP.clear();
    }

    static long startOf(int year, int month) {
        return DateJump.startOf(year, month);
    }

    static String nameOf(int year, int month, Locale locale) {
        return DateJump.nameOf(year, month, locale);
    }

    static List<Integer> years(int thisYear) {
        return DateJump.years(thisYear);
    }

    static int monthsOf(int year, int thisYear, int thisMonth) {
        return DateJump.monthsOf(year, thisYear, thisMonth);
    }

    static Activity shownActivity(View anchor) {
        return DateJump.shownActivity(anchor);
    }

    static void resetForTests() {
        JUMP.reset();
    }
}
