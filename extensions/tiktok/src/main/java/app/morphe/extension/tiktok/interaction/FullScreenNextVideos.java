/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Hands TikTok's full screen viewer the videos after the one on screen (#286). The Full screen
 * button sets the viewer's list to that one video, and off For You the viewer pages through that
 * list with no server call, so it has nothing to swipe to and its own move to the next video at a
 * video's end has nowhere to go. A longer list isn't enough by itself: the viewer takes the first
 * video as its first page and only asks for more when the page changes, which a one page viewer
 * never does. So the rest is read from the pager on screen as the button is tapped, and goes in
 * with that first page. Videos the page loads after that tap don't come along.
 */
public final class FullScreenNextVideos {
    private static final String FAMILY = "full screen next videos";
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";

    /** Plenty for a long series, and it keeps the button's tap quick on a very long feed. */
    private static final int MOST_VIDEOS = 200;

    interface Playable {
        boolean plays(Object video);
    }

    /** The video the button was tapped on and the ones after it, kept for as long as that viewer lasts. */
    private static volatile List<Object> opened;

    /** Whether the viewer took them, so its own list has nothing left to deal out. */
    private static volatile boolean handedOver;

    /** Runs as the button sets the viewer's list, while the page it was tapped on still shows. */
    public static void fill(Object video) {
        fill(video, FullScreenNextVideos::playsFullScreen);
    }

    static void fill(Object video, Playable playable) {
        opened = null;
        handedOver = false;
        try {
            HookStatus.bound(FAMILY, "viewer's list");
            if (!Settings.FULL_SCREEN_NEXT_VIDEOS.get()) return;
            Activity activity = Utils.getVisibleActivity();
            if (activity == null) return;
            List<View> pagers = new ArrayList<>();
            findPagers(activity.findViewById(android.R.id.content), pagers);
            for (View pager : pagers) {
                List<Object> list = new ArrayList<>();
                list.add(video);
                Object adapter = pager.getClass().getMethod("getAdapter").invoke(pager);
                if (addVideosAfter(adapter, video, list, playable)) {
                    Logger.printDebug(() -> "Full screen starts with " + list.size() + " video(s)");
                    opened = list;
                    return;
                }
            }
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "fill the viewer's list", ex);
        }
    }

    /** Takes the viewer's first page, its one video, and gives back that video and the ones after it. */
    public static List<Object> wholeList(List<Object> firstPage) {
        try {
            List<Object> videos = opened;
            if (videos == null || videos.size() < 2 || firstPage == null || firstPage.size() != 1
                    || !sameVideo(firstPage.get(0), videos.get(0))) {
                return firstPage;
            }
            List<Object> whole = new ArrayList<>(firstPage);
            whole.addAll(videos.subList(1, videos.size()));
            handedOver = true;
            return whole;
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "hand over the viewer's list", ex);
            return firstPage;
        }
    }

    /** Where the viewer's own list reads from next: past its end once the videos were handed over. */
    public static long readFrom(long position) {
        return handedOver ? Long.MAX_VALUE : position;
    }

    /** False when this adapter doesn't hold the video, so the video came from another pager. */
    static boolean addVideosAfter(Object adapter, Object video, List<Object> list, Playable playable)
            throws ReflectiveOperationException {
        if (adapter == null) return false;
        Method count;
        Method item;
        try {
            // Made accessible: the adapter's own class need not be public.
            count = adapter.getClass().getMethod("getCount");
            item = adapter.getClass().getMethod("getItem", int.class);
        } catch (NoSuchMethodException other) {
            return false;
        }
        count.setAccessible(true);
        item.setAccessible(true);
        int size = (Integer) count.invoke(adapter);
        int at = -1;
        for (int i = 0; i < size && at < 0; i++) {
            if (sameVideo(item.invoke(adapter, i), video)) at = i;
        }
        if (at < 0) return false;
        for (int i = at + 1; i < size && list.size() < MOST_VIDEOS; i++) {
            Object next = item.invoke(adapter, i);
            if (next != null && !sameVideo(next, video) && playable.plays(next)) list.add(next);
        }
        return true;
    }

    private static boolean sameVideo(Object one, Object other) {
        if (one == other) return true;
        if (!(one instanceof Aweme) || !(other instanceof Aweme)) return false;
        String aid = ((Aweme) one).getAid();
        return aid != null && aid.equals(((Aweme) other).getAid());
    }

    private static void findPagers(View view, List<View> pagers) {
        if (view == null || !view.isShown() || !view.getGlobalVisibleRect(new Rect())) return;
        if (PAGER.equals(view.getClass().getName())) pagers.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findPagers(group.getChildAt(i), pagers);
        }
    }

    /** TikTok's own test of what its viewer can play. The patch writes the body. */
    static boolean playsFullScreen(Object video) {
        return false;
    }

    private FullScreenNextVideos() {}
}
