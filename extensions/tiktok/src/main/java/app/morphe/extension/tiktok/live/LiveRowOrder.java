/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Swipe LIVEs in the Following row's order (#511).
 *
 * <p>A tap on a LIVE in the row at the top of Following builds the room's EnterRoomConfig from
 * the row's list of LIVEs and opens it. The pager most rooms open in fills itself from the
 * server's own order after the first room, and only puts rooms ahead of that when the config
 * names them in {@code mLogData.moreConfig}, which this path leaves empty. So the patch calls
 * {@link #seed} right before the room opens, and the row's other LIVEs go into moreConfig in the
 * row's order: the ones after the tapped LIVE first, then the ones before it. Each carries the
 * room data the row already has, as TikTok's own moreConfig entries do, so it opens like the
 * first. TikTok's picks follow once those run out. The server's pages can hold the row's LIVEs
 * again, and the pager only drops a repeat of the room it opened on, so {@link #page} takes the
 * lined-up rooms back out of the pages that pager loads.
 */
public final class LiveRowOrder {
    private LiveRowOrder() {
    }

    /**
     * How long after a tap the pager it opens has to load its first page to be taken for that
     * pager. It loads within a few seconds; this only keeps a stale tap from claiming a pager.
     */
    static final long CLAIM_MS = 60_000L;

    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final AtomicInteger PAGE_FAILURES = new AtomicInteger();

    /**
     * The rooms the last tap lined up, the tapped one included, and the pager that shows them.
     * That pager keeps the id of the room it opened on in a long field of its own (LLLIZZ on
     * 47.9.3, the room its "Remove duplicate room" check compares with), which is how it's told
     * apart: its request type is the pager's own and says nothing about the row.
     */
    static final class Lineup {
        final long tapped;
        final Set<Long> rooms;
        final long at;
        @Nullable volatile WeakReference<Object> pager;

        Lineup(long tapped, Set<Long> rooms, long at) {
            this.tapped = tapped;
            this.rooms = rooms;
            this.at = at;
        }
    }

    @Nullable static volatile Lineup lineup;

    /** One LIVE in the row: its room, its host and the room data the row came with. */
    static final class RowLive {
        final long roomId;
        @Nullable final String uid;
        @Nullable final String roomData;

        RowLive(long roomId, @Nullable String uid, @Nullable String roomData) {
            this.roomId = roomId;
            this.uid = uid;
            this.roomData = roomData;
        }
    }

    /**
     * Called by the patch right before TikTok opens the LIVE tapped in the Following row, with
     * the room's config, the tapped entry and the row's list of LIVEs.
     */
    public static void seed(Object config, Object tapped, List<?> row) {
        // Every tap starts over, so the last tap's rooms never reach this one's pager.
        lineup = null;
        if (!Settings.LIVE_FOLLOWING_ROW_ORDER.get() || config == null || row == null) return;
        try {
            Object logData = Reflect.readField(config, "mLogData");
            Object roomsData = Reflect.readField(config, "mRoomsData");
            if (logData == null || roomsData == null) return;
            // A config that already names the rooms after it keeps TikTok's list.
            Object existing = Reflect.readField(logData, "moreConfig");
            if (existing instanceof List && !((List<?>) existing).isEmpty()) return;

            List<RowLive> lives = new ArrayList<>(row.size());
            for (Object entry : row) {
                RowLive live = liveOf(entry);
                if (live != null) lives.add(live);
            }
            RowLive first = liveOf(tapped);
            List<RowLive> next = after(lives, first);
            if (next.isEmpty()) return;

            Class<?> type = config.getClass();
            String enterFromMerge = (String) Reflect.readField(roomsData, "enterFromMerge");
            String enterMethod = (String) Reflect.readField(roomsData, "enterMethod");
            String enterType = (String) Reflect.readField(roomsData, "enterType");
            String requestId = (String) Reflect.readField(logData, "requestId");
            ArrayList<Object> more = new ArrayList<>(next.size());
            for (RowLive live : next) {
                Object entryConfig = type.getDeclaredConstructor().newInstance();
                Object entryRooms = Reflect.readField(entryConfig, "mRoomsData");
                Object entryLog = Reflect.readField(entryConfig, "mLogData");
                if (entryRooms == null || entryLog == null) return;
                set(entryRooms, "roomId", live.roomId);
                set(entryRooms, "userId", live.uid);
                set(entryRooms, "enterFromMerge", enterFromMerge);
                set(entryRooms, "enterMethod", enterMethod);
                set(entryRooms, "enterType", enterType);
                set(entryRooms, "preLoadRoomInfo", live.roomData);
                set(entryLog, "requestId", requestId);
                more.add(entryConfig);
            }
            set(logData, "moreConfig", more);
            if (first != null) {
                Set<Long> rooms = new HashSet<>();
                rooms.add(first.roomId);
                for (RowLive live : next) rooms.add(live.roomId);
                lineup = new Lineup(first.roomId, rooms, SystemClock.elapsedRealtime());
            }
            Logger.printDebug(() -> "LIVE row order: " + more.size() + " rooms after " + (first == null ? "?" : first.roomId));
        } catch (Throwable failure) {
            if (FAILURES.getAndIncrement() < 3) {
                Logger.printException(() -> "Could not line the LIVE row up; TikTok's order stays", failure);
            }
        }
    }

    /**
     * Called by the patch at the top of TikTok's LIVE page handler with the pager, the page it
     * loaded and the pager's request type. The pager the last row tap opened, found by the room
     * it opened on within a minute of the tap, gets the page back without the rooms that tap
     * lined up. Every other pager, and every page on any failure, gets the page as it came.
     */
    public static List<?> page(Object pager, List<?> items, @Nullable String requestType) {
        Lineup current = lineup;
        if (current == null || pager == null || items == null || items.isEmpty()) return items;
        try {
            WeakReference<Object> claim = current.pager;
            if (claim == null) {
                if (SystemClock.elapsedRealtime() - current.at > CLAIM_MS || !opensOn(pager, current.tapped)) {
                    return items;
                }
                current.pager = new WeakReference<>(pager);
            } else if (claim.get() != pager) {
                return items;
            }
            List<Object> kept = null;
            for (int index = 0; index < items.size(); index++) {
                Object item = items.get(index);
                boolean repeat = current.rooms.contains(roomIdOf(item));
                if (repeat && kept == null) kept = new ArrayList<>(items.subList(0, index));
                else if (!repeat && kept != null) kept.add(item);
            }
            int dropped = kept == null ? 0 : items.size() - kept.size();
            Logger.printDebug(() -> "LIVE row order: a " + requestType + " page of " + items.size()
                    + " rooms for the row's pager, " + dropped + " already lined up");
            return kept == null ? items : kept;
        } catch (Throwable failure) {
            if (PAGE_FAILURES.getAndIncrement() < 3) {
                Logger.printException(() -> "Could not check a LIVE page for the row's rooms; it was left as it came", failure);
            }
            return items;
        }
    }

    /** Whether {@code pager} has a long field of its own class chain holding {@code roomId}. */
    static boolean opensOn(Object pager, long roomId) throws IllegalAccessException {
        for (Class<?> type = pager.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != long.class || Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                if (field.getLong(pager) == roomId) return true;
            }
        }
        return false;
    }

    /** The id of the room a page item carries, or 0 for an item without one. */
    static long roomIdOf(@Nullable Object item) {
        if (item == null) return 0L;
        Object room = Reflect.invoke(item, "getRoom");
        if (room == null) return 0L;
        Object id = Reflect.invoke(room, "getId");
        return id instanceof Long ? (Long) id : 0L;
    }

    /**
     * The row's LIVEs to come after {@code first}: the ones after it in the row, then the ones
     * before it, each room once. With {@code first} not in the row, the whole row in order.
     */
    static List<RowLive> after(List<RowLive> row, @Nullable RowLive first) {
        int at = -1;
        if (first != null) {
            for (int index = 0; index < row.size(); index++) {
                if (row.get(index).roomId == first.roomId) {
                    at = index;
                    break;
                }
            }
        }
        Set<Long> seen = new HashSet<>();
        if (first != null) seen.add(first.roomId);
        List<RowLive> order = new ArrayList<>(row.size());
        for (int step = 1; step <= row.size(); step++) {
            RowLive live = row.get((at + step) % row.size());
            if (seen.add(live.roomId)) order.add(live);
        }
        return order;
    }

    /** The LIVE a row entry (a FollowingInterestUser) stands for, or null when it isn't live. */
    @Nullable
    static RowLive liveOf(Object entry) {
        Object user = Reflect.invoke(entry, "getUser");
        if (user == null) return null;
        Object room = Reflect.readField(user, "roomId");
        long roomId = room instanceof Long ? (Long) room : 0L;
        if (roomId == 0L) return null;
        Object uid = Reflect.invoke(user, "getUid");
        Object roomData = Reflect.invoke(user, "getRoomData");
        return new RowLive(roomId, uid instanceof String ? (String) uid : null,
                roomData instanceof String ? (String) roomData : null);
    }

    private static void set(Object target, String name, Object value) throws IllegalAccessException {
        Field field = Reflect.field(target.getClass(), name);
        if (field == null) throw new IllegalStateException(target.getClass().getName() + " has no " + name);
        field.set(target, value);
    }
}
