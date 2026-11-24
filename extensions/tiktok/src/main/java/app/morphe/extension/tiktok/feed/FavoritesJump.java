/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Jump to a date on a Favorites grid (#508). A profile's Favorites tab asks for its list through
 * the same profile list request as the other tabs, with tab number 4, which goes out to TikTok's
 * Favorites list with a {@code cursor} that is the time of a save in seconds, newest first. While a jump is
 * on, a fresh list starts at the chosen date, so the oldest saves are a year and a month away
 * instead of thousands of videos down. {@link DateJump} holds the jump and the chip, and the
 * grid refreshes through the profile tab refresh the Liked jump has.
 */
@SuppressWarnings("unused")
public final class FavoritesJump {
    static final String CHIP_TAG = "hushfeed_favorites_jump_chip";

    static final DateJump JUMP = new DateJump("Favorites", "favorites jump", Settings.FAVORITES_JUMP_TO_DATE,
            CHIP_TAG, "Newest saves", fragment -> LikedJump.refreshGrid(fragment, 0));

    private FavoritesJump() {
    }

    /**
     * The cursor TikTok's profile list request goes out with. Called first thing in the method
     * that builds the request's address, with the profile's ids, the tab number and both cursors.
     * Only a fresh Favorites list (tab 4, cursor 0) can start at a date: any other tab and a later
     * page go as TikTok asked. TikTok never sends {@code minCursor} for tab 4, so it plays no part.
     */
    public static long cursor(String userId, String secUserId, int type, long minCursor, long maxCursor) {
        // Remember saved videos (#516) reads only the reader's own Favorites, and this says whose these are.
        SavedVideoMemory.requested(userId, secUserId, type);
        if (type != GridSort.TAB_FAVORITES) return maxCursor;
        String who = userId != null && !userId.isEmpty() ? userId : secUserId == null ? "" : secUserId;
        long sent = JUMP.cursor(who, maxCursor);
        // The Favorites list's cursor is a save time in seconds, where the Liked one's is in
        // milliseconds. A cursor TikTok sent goes back as it came.
        return sent == maxCursor ? maxCursor : sent / 1000;
    }

    static void listIn(Object adapter, boolean fresh) {
        JUMP.listIn(adapter, fresh);
    }

    static void resetForTests() {
        JUMP.reset();
    }
}
