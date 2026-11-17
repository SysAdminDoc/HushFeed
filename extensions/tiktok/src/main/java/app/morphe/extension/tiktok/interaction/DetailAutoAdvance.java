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
 * a video moves the pager on by one, as blocking a creator does.
 *
 * <p>Off screen the pager is left alone: TikTok's usual background session holds one video, and a
 * move ends it with nothing playing. TikTok has a second background session that plays a page's
 * videos in turn, but gives it only to your own collections. A page opened from a profile gets it
 * too while Auto-advance and Background play are both on, so its videos keep coming with the
 * screen off (#292). That session moves on by itself, so the limit and the holds are applied as
 * each video there ends, and its foreground twin, which would move the page a second time, waits
 * for this class instead.
 */
public final class DetailAutoAdvance {
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    private static final String FAMILY = "detail auto-advance";
    private static final String OPENED_FROM = "video_from";
    private static final String FROM_SEARCH = "from_search";
    /** TikTok keys a background queue by your collection's id; a profile page's key is ours. */
    static final String PROFILE_SCOPE = "hushfeed_profile_";
    /** TikTok's mode for that queue: 1 plays your collections in turn, without its bottom bar. */
    private static final int QUEUE_ON = 1;
    // One session per page, so the limit counts this page's videos, as it does for each feed.
    private static final Map<Activity, AutoAdvance.Control> SESSIONS = new WeakHashMap<>();
    // TikTok's background queue reports its ends off the page, so it keeps a count of its own.
    private static final Map<Object, AutoAdvance.Control> QUEUES = new WeakHashMap<>();

    private DetailAutoAdvance() { }

    private static boolean profileQueue() {
        return Settings.AUTO_ADVANCE.get() && Settings.BACKGROUND_PLAY.get();
    }

    /**
     * TikTok's key for a page's background queue, as the places that start that queue read it.
     * TikTok answers only for your own collections, so a page opened from a profile gets a key of
     * its own here, and leaving the app hands it to the queue rather than to the one-video session
     * that rewinds and pauses at the end (#292).
     */
    public static String backgroundScope(String scope, String profileUid) {
        try {
            if (scope != null && !scope.isEmpty()) return scope;
            if (profileUid == null || profileUid.isEmpty() || !profileQueue()) return scope;
            return PROFILE_SCOPE + profileUid;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not choose a background queue for a profile page", error);
            return scope;
        }
    }

    /**
     * The same key, read by TikTok's own move to the next video on screen. With Auto-advance on
     * this class makes that move, so TikTok's is turned away, or the page would skip a video.
     */
    public static String foregroundScope(String scope) {
        try {
            return Settings.AUTO_ADVANCE.get() ? null : scope;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not read Auto-advance for TikTok's own next video", error);
            return scope;
        }
    }

    /** TikTok's mode for the queue, read once a process; a page needs it on to use the queue. */
    public static int collectionMode(int served) {
        try {
            return served < QUEUE_ON && profileQueue() ? QUEUE_ON : served;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not read the background queue settings", error);
            return served;
        }
    }

    /**
     * Called as a video ends in TikTok's background queue, with whether the queue has another one
     * after it. The answer is whether it moves on. No ends the queue as its last video does, which
     * is how the limit and the holds stop it there.
     */
    public static boolean backgroundNext(boolean hasNext, Object queue, String id) {
        try {
            if (!hasNext || !Settings.AUTO_ADVANCE.get()) return hasNext;
            if (SessionBudget.isLocked() || FeedLock.linkVideoAlone()) return false;
            if (queue == null || id == null) return true;
            synchronized (QUEUES) {
                AutoAdvance.Control count = QUEUES.get(queue);
                if (count == null) {
                    count = new AutoAdvance.Control(null);
                    count.owned = true;
                    QUEUES.put(queue, count);
                }
                count.recordCompletion(id);
                return !count.limitReached();
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not check the limit for TikTok's background queue", error);
            return hasNext;
        }
    }

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
