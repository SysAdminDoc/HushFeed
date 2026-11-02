/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.blockauthor.VideoAuthor;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.FeedLock;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Auto-advance for a video opened from a profile or a list (#311). TikTok builds its auto scroll
 * component for the main feed and search, so its detail page has nothing to start and a video
 * there loops at its end. That page plays the same cells in the same native pager, so the end of
 * a video moves the pager on by one, as blocking a creator does. It stays put off screen, as the
 * main feed does: TikTok's background session holds one video, and a move ends it with nothing
 * playing, so the video that had finished is gone from the notification as well.
 */
public final class DetailAutoAdvance {
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    private static final String FAMILY = "detail auto-advance";
    private static final String OPENED_FROM = "video_from";
    private static final String FROM_SEARCH = "from_search";
    // One session per page, so the limit counts this page's videos, as it does for each feed.
    private static final Map<Activity, AutoAdvance.Control> SESSIONS = new WeakHashMap<>();

    private DetailAutoAdvance() { }

    /** Called from {@code PlayerController.onPlayCompleted} with the video that finished. */
    public static void onPlayCompleted(String id) {
        try {
            if (id == null || !Settings.AUTO_ADVANCE.get()) return;
            // The completion arrives on the player's thread and the pager is a view.
            Utils.runOnMainThread(() -> {
                try {
                    advance(id);
                } catch (Throwable error) {
                    Logger.printException(() -> "Could not move on from a video on its own page", error);
                }
            });
        } catch (Throwable error) {
            Logger.printException(() -> "Could not read the completed video for auto-advance", error);
        }
    }

    private static void advance(String id) {
        Activity activity = Utils.getVisibleActivity();
        if (!FeedVisibility.isDetailPager(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        // A search page can build TikTok's own component, and that one moves on by itself.
        if (AutoAdvance.componentIn(activity)) return;
        if (SessionBudget.isLocked() || FeedLock.linkVideoAlone()) return;
        if (openedFromSearch(activity) && !Settings.AUTO_ADVANCE_SEARCH.get()) return;
        // A sheet or a dialog takes the focus, and so does leaving the app.
        if (!activity.hasWindowFocus()) return;
        if (FeedVisibility.isCommentSheetVisible(activity) || FeedVisibility.isStoryVisible(activity)) return;
        VideoAuthor current = CurrentVideoAuthor.get();
        if (current == null || !id.equals(current.awemeId)) return;
        View pager = findPager(activity.findViewById(android.R.id.content));
        if (pager == null) return;
        Object position = Reflect.required(pager, "getCurrentItem", FAMILY);
        Object state = Reflect.required(pager, "getScrollState", FAMILY);
        if (!(position instanceof Integer) || (Integer) position < 0 || !Integer.valueOf(0).equals(state)
                || !pager.canScrollVertically(1)) return;

        AutoAdvance.Control session = SESSIONS.get(activity);
        if (session == null) {
            session = new AutoAdvance.Control(pager);
            session.owned = true;
            SESSIONS.put(activity, session);
        }
        session.recordCompletion(id);
        if (session.limitReached()) {
            if (session.claimLimitNotice()) {
                AutoAdvance.Control stopped = session;
                BlockAuthorOverlay.showActionBanner(
                        L10n.quantity(Utils.getContext(), stopped.completedCount,
                                "Auto-advance stopped after one video", "Auto-advance stopped after %1$d videos"),
                        L10n.t("Keep going"), stopped::keepGoing);
            }
            return;
        }
        try {
            // The native pager clamps to its adapter and performs its own selection callbacks.
            pager.getClass().getMethod("setCurrentItem", int.class).invoke(pager, (Integer) position + 1);
            HookStatus.bound(FAMILY, "native next item");
            Logger.printDebug(() -> "Detail page moved on from " + id);
        } catch (Exception error) {
            HookStatus.missingMember(FAMILY, "method", PAGER, "setCurrentItem(int)");
            Logger.printException(() -> "Could not move on from a video on its own page", error);
        }
    }

    private static boolean openedFromSearch(Activity activity) {
        String from = activity.getIntent() == null ? null : activity.getIntent().getStringExtra(OPENED_FROM);
        return from != null && from.startsWith(FROM_SEARCH);
    }

    private static View findPager(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (PAGER.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findPager(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
