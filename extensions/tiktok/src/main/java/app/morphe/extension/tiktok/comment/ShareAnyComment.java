/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Lets a comment TikTok marks as not shareable be shared and sent like any other (#481).
 *
 * <p>TikTok's server answers two things on a comment: why it may not be sent to a chat (0 when it
 * may) and the code of its share ACL (0 when it may be shared outside TikTok). The long-press
 * menu, the comment's action sheet and the share step read only those two getters, and the link
 * TikTok shares is built on the phone from the video's own link and the comment's id. With the
 * switch on both getters answer 0. The comment status check before them is left alone, so a
 * deleted or held comment still has no share, and so does one on a video that can't be shared.
 */
public final class ShareAnyComment {
    static final String FAMILY = "share any comment";

    private ShareAnyComment() {
    }

    /** Whether a comment's share block reads as 0, TikTok's "can share". */
    public static boolean allow() {
        HookStatus.bound(FAMILY, "share block read");
        if (!Settings.SHARE_ANY_COMMENT.get()) return false;
        HookStatus.bound(FAMILY, "share block lifted");
        return true;
    }
}
