/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.inbox.SuggestedAccountCells;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * The Send to row in the menu a long press on a comment opens (#313). It's a view of its own, in a
 * sheet the share sheet's passes never see, so Hide the Send to row and the hidden items list both
 * missed it. The row is a child of a RecyclerView, which lays a GONE child out anyway, so it
 * collapses through its layout params. People are taken out of the list it's handed, ahead of
 * TikTok's own cut to its contact limit, so the row still fills up.
 */
public final class CommentSendToRow {
    private static final String FAMILY = "comment send to row";

    private CommentSendToRow() {
    }

    /** Called from the row's onAttachedToWindow, ahead of the list measuring it. */
    public static void onAttached(View row) {
        try {
            HookStatus.bound(FAMILY, "row attached");
            SuggestedAccountCells.apply(row, Settings.HIDE_SHARE_CONTACTS.get());
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "row attached", ex);
            Logger.printException(() -> "Could not collapse the comment menu's Send to row", ex);
        }
    }

    /** Called with the contacts the row loaded. The names are the ones the share sheet's cells show. */
    public static List<?> people(List<?> contacts) {
        try {
            if (contacts == null) return null;
            List<String> hidden = ShareSheetTools.entries(ShareModelFilter.hiddenItems(ShareSurface.VIDEO));
            if (hidden.isEmpty()) return contacts;
            List<Object> kept = new ArrayList<>(contacts.size());
            for (Object contact : contacts) {
                String name = Reflect.string(contact, "getDisplayName", "displayName");
                if (name == null || !hidden.contains(name.trim().toLowerCase(java.util.Locale.ROOT))) kept.add(contact);
            }
            return kept.size() == contacts.size() ? contacts : kept;
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "people", ex);
            Logger.printException(() -> "Could not filter the comment menu's Send to row", ex);
            return contacts;
        }
    }
}
