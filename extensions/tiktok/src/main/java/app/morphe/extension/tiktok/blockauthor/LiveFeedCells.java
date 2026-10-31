/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import app.morphe.extension.shared.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The LIVE previews bound in the For You pager, and which of them is on screen.
 *
 * <p>A LIVE preview is a pager page like a video, but the player never names it and the bind
 * that feeds {@link CurrentVideoAuthor} carries no author for it, so the previous video's
 * creator stayed armed while a LIVE filled the screen. TikTok's LIVE cell holders call one setter
 * with the cell's {@code Aweme}; that is reported here with the cell's view. Which cell is
 * current is then read off the screen: the one holding most of the pager's page.
 *
 * <p>The host is the room owner. Blocking goes through the same request a video's author does.
 */
final class LiveFeedCells {
    /** The pager every feed page, video or LIVE, is a direct child of. */
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    /** The share of a cell on screen that makes it the page being looked at. */
    private static final float CURRENT_SHARE = 0.5f;

    private static final Map<View, Entry> CELLS = new WeakHashMap<>();

    private LiveFeedCells() {
    }

    private static final class Entry {
        final String awemeId;
        final VideoAuthor host;
        final Object aweme;

        Entry(String awemeId, VideoAuthor host, Object aweme) {
            this.awemeId = awemeId;
            this.host = host;
            this.aweme = aweme;
        }
    }

    /**
     * A LIVE cell holder was given its {@code Aweme}.
     *
     * @param holder the cell holder, whose view is found by its type
     * @param aweme the LIVE item
     */
    static void onBind(Object holder, Object aweme) {
        if (holder == null || aweme == null) return;
        View view = viewOf(holder);
        if (view == null) return;
        String roomId = null;
        VideoAuthor host = null;
        for (Object room : rooms(aweme)) {
            if (roomId == null) roomId = Reflect.string(room, "getId", "id");
            host = hostOf(room);
            if (host != null) break;
        }
        String awemeId = Reflect.firstNonBlank(
                Reflect.string(aweme, "getAid", "aid"), roomId == null ? null : "live:" + roomId);
        if (awemeId == null) return;
        // A host with nobody to block still registers: the button has to hide for it rather than
        // stay armed on the video before.
        VideoAuthor named = host == null
                ? new VideoAuthor(null, null, null, awemeId)
                : new VideoAuthor(host.uid, host.secUid, host.displayName, awemeId);
        boolean first;
        synchronized (CELLS) {
            first = !CELLS.containsKey(view);
            CELLS.put(view, new Entry(awemeId, named, aweme));
        }
        if (first) watch(view);
        view.post(LiveFeedCells::evaluate);
    }

    /** Watches the cell so the choice follows a swipe, which moves it without a new bind. */
    private static void watch(View view) {
        final ViewTreeObserver.OnScrollChangedListener scrolled = LiveFeedCells::evaluate;
        view.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> evaluate());
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnScrollChangedListener(scrolled);
            }

            @Override public void onViewDetachedFromWindow(View v) {
                v.getViewTreeObserver().removeOnScrollChangedListener(scrolled);
                evaluate();
            }
        });
        if (view.isAttachedToWindow()) view.getViewTreeObserver().addOnScrollChangedListener(scrolled);
    }

    /**
     * Selects the LIVE holding the page, or lets go of the LIVE selection when none does.
     * Cheap: a handful of cells at most, and each answer is one visible-rect query.
     */
    static void evaluate() {
        try {
            List<Map.Entry<View, Entry>> cells;
            synchronized (CELLS) {
                cells = new ArrayList<>(CELLS.entrySet());
            }
            Entry best = null;
            float bestShare = 0;
            Rect visible = new Rect();
            for (Map.Entry<View, Entry> cell : cells) {
                View view = cell.getKey();
                if (view == null) continue;
                View page = pageOf(view);
                if (page == null) continue;
                float share = onScreenShare(page, visible);
                if (share > bestShare) {
                    bestShare = share;
                    best = cell.getValue();
                }
            }
            if (best != null && bestShare >= CURRENT_SHARE) {
                CurrentVideoAuthor.selectLive(best.awemeId, best.host, best.aweme);
            } else {
                CurrentVideoAuthor.releaseLive();
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not tell which LIVE is on screen", ex);
        }
    }

    /** The pager's child holding {@code view}, or null for a LIVE card that is not a feed page. */
    private static View pageOf(View view) {
        View child = view;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (PAGER.equals(parent.getClass().getName())) return child;
            child = (View) parent;
        }
        return null;
    }

    private static float onScreenShare(View page, Rect visible) {
        float area = (float) page.getWidth() * page.getHeight();
        ViewParent parent = page.getParent();
        if (area <= 0 || page.getVisibility() != View.VISIBLE || !(parent instanceof View)
                || !((View) parent).isShown() || !page.getGlobalVisibleRect(visible)) {
            return 0;
        }
        return visible.width() * (float) visible.height() / area;
    }

    /** The holder's root view: its first {@link View} field, whatever TikTok calls it. */
    private static View viewOf(Object holder) {
        for (Class<?> type = holder.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !View.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof View) return (View) value;
                } catch (Throwable ignored) {
                    // Try the next field.
                }
            }
        }
        return null;
    }

    /** Every room object a LIVE item can carry, the same ones the LIVE filter reads. */
    private static List<Object> rooms(Object aweme) {
        List<Object> rooms = new ArrayList<>(6);
        add(rooms, Reflect.readField(aweme, "newLiveRoomData"));
        add(rooms, Reflect.readField(aweme, "cachedLiveRoomStruct"));
        add(rooms, Reflect.readField(aweme, "room"));
        Object cell = Reflect.invoke(aweme, "getRoomFeedCellStruct");
        if (cell == null) cell = Reflect.readField(aweme, "mRoomFeedCellStruct");
        if (cell != null) {
            add(rooms, Reflect.invoke(cell, "getNewLiveRoomData"));
            add(rooms, Reflect.readField(cell, "room"));
            add(rooms, Reflect.readField(cell, "newLiveRoomData"));
        }
        return rooms;
    }

    private static void add(List<Object> rooms, Object room) {
        if (room != null && !rooms.contains(room)) rooms.add(room);
    }

    /** The room's owner as an author, or null when it names nobody. */
    static VideoAuthor hostOf(Object room) {
        Object owner = Reflect.readField(room, "owner");
        if (owner == null) return null;
        String id = Reflect.string(owner, "getId", "id");
        if ("0".equals(id)) id = null;
        String uid = Reflect.firstNonBlank(Reflect.string(owner, "getIdStr", "idStr"), id);
        String secUid = Reflect.string(owner, "getSecUid", "secUid");
        String name = Reflect.firstNonBlank(
                Reflect.string(owner, "getUsername", "username"),
                Reflect.string(owner, "getNickName", "nickName"));
        VideoAuthor host = new VideoAuthor(uid, secUid, name, null);
        return host.isUsable() ? host : null;
    }

    static void resetForTests() {
        synchronized (CELLS) {
            CELLS.clear();
        }
    }
}
