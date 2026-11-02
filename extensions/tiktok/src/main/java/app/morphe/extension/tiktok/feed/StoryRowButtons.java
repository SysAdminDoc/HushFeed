/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.inbox.SuggestedAccountCells;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The two buttons around the stories in TikTok's story rows (#308, #309).
 *
 * <p>The Create circle is the viewer's own cell in the row while they have no Story up, on the
 * Following tab and in the Inbox. Each cell works that out as it binds and keeps it in a flag, so
 * this is handed the flag rather than reading the circle's text, which comes in the viewer's
 * language. The cell is a RecyclerView ViewHolder, so its view is the real-named
 * {@code itemView}, and it's collapsed the way a suggestion cell is, because a list keeps a GONE
 * child's margins. A cell recycled for someone else's stories comes back at its own size.
 *
 * <p>All is a button TikTok shows at the end of the Following row while its recent follows
 * experiment is on. It's hidden right after TikTok's own call that shows it.
 */
public final class StoryRowButtons {
    private StoryRowButtons() {
    }

    /** Called in a story cell's bind, with TikTok's own "this is the Create circle" flag. */
    public static void onStoryCellBound(Object cell, int create) {
        try {
            Object view = Reflect.readField(cell, "itemView");
            if (!(view instanceof View)) return;
            boolean hide = create != 0 && Settings.HIDE_STORY_CREATE.get();
            SuggestedAccountCells.apply((View) view, hide);
            if (create != 0) HookStatus.bound("story create circle", hide ? "circle collapsed" : "circle left");
        } catch (Throwable error) {
            Logger.printException(() -> "Could not collapse the Create circle", error);
        }
    }

    /** Called right after TikTok shows the Following row's All button, with the button. */
    public static void hideAllButton(View button) {
        try {
            boolean hide = Settings.HIDE_FOLLOWING_ALL_BUTTON.get();
            if (hide && button != null) button.setVisibility(View.GONE);
            HookStatus.bound("story row all button", hide ? "button hidden" : "button left");
        } catch (Throwable error) {
            Logger.printException(() -> "Could not hide the All button", error);
        }
    }
}
