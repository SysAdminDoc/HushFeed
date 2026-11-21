/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Two of TikTok's comment sheet experiments, answered where TikTok reads them (#479).
 *
 * <p>Every App AB int TikTok reads passes through {@link #answer}. Both values are read once into
 * a lazy holder and kept until TikTok restarts, so both switches are restart-gated.
 *
 * <ul>
 *   <li>{@code comment_panel_full_page}: at 1 the sheet builds the expand chevron
 *   ({@code DefaultCommentPageContainerFragment}) with its click to the full screen sheet, and the
 *   action bar shows it on every video but the title panel's. 0, the default, builds no chevron,
 *   and 2 swaps it for a pull-to-full-screen gesture with no button.</li>
 *   <li>{@code video_shrink_by_comment}: only at 1 does the feed hand the comment sheet the
 *   listeners that shrink the video into the space above it
 *   ({@code setShowHeightChangeListenerForCommentDialog}). The default is -1, so 0 is TikTok's own
 *   answer for an account outside the experiment.</li>
 * </ul>
 */
public final class CommentSheetGates {
    static final String FULL_PAGE_KEY = "comment_panel_full_page";
    static final String VIDEO_SHRINK_KEY = "video_shrink_by_comment";
    static final int FULL_PAGE_BUTTON = 1;
    static final int VIDEO_SHRINK_ON = 1;
    static final int VIDEO_SHRINK_OFF = 0;
    static final String FAMILY = "comment sheet gates";

    private CommentSheetGates() {
    }

    /** Never throws; any key but the two comes back as TikTok gave it. */
    public static int answer(String key, int value) {
        if (FULL_PAGE_KEY.equals(key)) return fullPage(value);
        if (VIDEO_SHRINK_KEY.equals(key)) return videoShrink(value);
        return value;
    }

    private static int fullPage(int value) {
        if (value == FULL_PAGE_BUTTON || !SettingsStatus.commentToolsEnabled) return value;
        // Hiding the button wins. An account at 2 trades TikTok's pull gesture for the button,
        // since the sheet builds one or the other.
        if (!Settings.SHOW_COMMENT_EXPAND_BUTTON.get() || Settings.HIDE_COMMENT_EXPAND_BUTTON.get()) return value;
        HookStatus.bound(FAMILY, "expand button kept");
        return FULL_PAGE_BUTTON;
    }

    private static int videoShrink(int value) {
        if (value != VIDEO_SHRINK_ON || !SettingsStatus.commentToolsEnabled) return value;
        if (!Settings.KEEP_VIDEO_SIZE_UNDER_COMMENTS.get()) return value;
        HookStatus.bound(FAMILY, "video size kept");
        return VIDEO_SHRINK_OFF;
    }
}
