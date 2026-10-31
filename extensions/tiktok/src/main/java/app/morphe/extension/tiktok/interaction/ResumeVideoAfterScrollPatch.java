/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.interaction;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

public final class ResumeVideoAfterScrollPatch {
    private ResumeVideoAfterScrollPatch() {
    }

    public static boolean shouldResumeVideoAfterScroll() {
        return Settings.RESUME_VIDEO_AFTER_SCROLL.get();
    }

    /**
     * TikTok stores a position for every feed it plays and then refuses to hand one back unless
     * the feed's event type is in its own allow list, which on 46.2.3 covers For You and the
     * profile and history pages but not Following or Friends. With the setting on, every feed
     * that has a stored position gets it.
     *
     * @param appAllowsThisFeed what TikTok's own event type check answered.
     */
    public static boolean allowResumeInThisFeed(boolean appAllowsThisFeed) {
        return appAllowsThisFeed || Settings.RESUME_VIDEO_AFTER_SCROLL.get();
    }

    public static boolean shouldClearCompletedProgress(long position, long duration) {
        return Settings.RESUME_VIDEO_AFTER_SCROLL.get() && duration > 0 && position >= duration;
    }

    /**
     * PineDrama asks its drama app-diff store where an episode starts whenever the caller has no
     * start of its own, and that store answers with the episode's series start point. An unpause
     * has no start of its own, so tapping a paused episode played it again from that point instead
     * of carrying on (#156). It happens with or without the switch, which only decides whether a
     * stored position gets there first, so this does not read it.
     *
     * @param appDiffMs what the app-diff store answered.
     * @param requestedMs the start the caller asked for, which PineDrama itself hands back when the
     *                    store has nothing.
     * @param tag the caller's name for the play: {@code resumePlay} for an unpause.
     * @param playerPaused non-zero when the player is paused in place, rather than stopped or not
     *                     yet playing this episode. An int, since the patch hands over a register.
     */
    public static int seriesResumeStart(int appDiffMs, int requestedMs, String tag, int playerPaused) {
        boolean paused = playerPaused != 0;
        boolean keepPlace = appDiffMs > 0 && paused && "resumePlay".equals(tag);
        if (appDiffMs > 0) {
            Logger.printDebug(() -> "Series start " + appDiffMs + " ms for " + tag
                    + (paused ? ", player paused" : ", player not paused")
                    + (keepPlace ? ", carrying on in place" : ""));
        }
        return keepPlace ? requestedMs : appDiffMs;
    }
}
