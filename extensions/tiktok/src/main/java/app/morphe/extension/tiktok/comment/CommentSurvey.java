/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Hides the survey TikTok sometimes puts in a comment list.
 *
 * <p>The survey comes with the video or from the server's comment_survey setting, through one
 * resolver that answers the survey to show or its empty holder when there's none. The patch asks
 * here first, and a yes makes the resolver answer that empty holder, so the list is built as it is
 * for every account the server sends no survey to. The feed's own survey cards are Hide video
 * overlays' job.
 */
public final class CommentSurvey {
    static final String FAMILY = "comment survey";

    private CommentSurvey() {
    }

    /** Whether the comment survey config answers none. */
    public static boolean hide() {
        HookStatus.bound(FAMILY, "survey config");
        if (!Settings.HIDE_COMMENT_SURVEYS.get()) return false;
        HookStatus.bound(FAMILY, "survey hidden");
        return true;
    }
}
