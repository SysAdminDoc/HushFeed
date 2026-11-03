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
 * video's end has nowhere to go. The hook runs right after the button sets the list, and reads the
 * rest from the pager on screen.
 */
public final class FullScreenNextVideos {
    private static final String FAMILY = "full screen next videos";
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";

    /** Plenty for a long series, and it keeps the button's tap quick on a very long feed. */
    private static final int MOST_VIDEOS = 200;

    interface Playable {
        boolean plays(Object video);
    }

    /** Takes the viewer's list as the button left it, holding the video on screen alone. */
    public static void fill(Object video, List<Object> list) {
        try {
            HookStatus.bound(FAMILY, "viewer's list");
            if (!Settings.FULL_SCREEN_NEXT_VIDEOS.get()) return;
            Activity activity = Utils.getVisibleActivity();
            if (activity == null) return;
            List<View> pagers = new ArrayList<>();
            findPagers(activity.findViewById(android.R.id.content), pagers);
            for (View pager : pagers) {
                Object adapter = pager.getClass().getMethod("getAdapter").invoke(pager);
                if (addVideosAfter(adapter, video, list, FullScreenNextVideos::playsFullScreen)) {
                    Logger.printDebug(() -> "Full screen starts with " + list.size() + " video(s)");
                    return;
                }
            }
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "fill the viewer's list", ex);
        }
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
