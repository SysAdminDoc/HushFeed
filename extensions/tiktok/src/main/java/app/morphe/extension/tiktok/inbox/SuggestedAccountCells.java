/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Collapses TikTok's suggestion cells wherever they are bound.
 *
 * <p>"People you may like" has no one source. The inbox, Activity and New followers lists
 * have injectors that {@link InboxControls} answers, but the profile header, the Friends tab
 * and the feed's account cards each build their own list, and every one of those lists is
 * made of cells that extend {@code AbsRecUserCell}. Its bind is the one place they all pass
 * through, and this is called at the end of it with the cell, after TikTok has laid the cell
 * out: the Rect cells write their own top margin inside the bind, so a hook ahead of it would
 * have been undone. The cell is a RecyclerView ViewHolder, so its view is the real-named
 * {@code itemView}. The list's {@code TitleAreaCell} comes through here too, so its
 * "Suggested accounts" title goes with the cells it heads.
 *
 * <p>The cell is collapsed to nothing rather than only hidden: a GONE child of a horizontal
 * list still holds its width in some layouts, and its margins hold space in every layout.
 * Size, margins and visibility are remembered so a cell recycled after the switch goes off
 * comes back exactly as TikTok had it, invisible cells included.
 */
public final class SuggestedAccountCells {
    private static final String HOOK_FAMILY = "suggested accounts";

    /**
     * What a cell was before it was collapsed: width, height, visibility, followed by the four
     * margins only when the original params carried them. Negative margins are valid values.
     */
    private static final Map<View, int[]> COLLAPSED = new WeakHashMap<>();

    private SuggestedAccountCells() {
    }

    /** The profile page, the one place the cells sit in a panel of their own. */
    static final String PROFILE = "view_profile";
    /** The "View all" end of the header above a profile's suggestions. */
    static final String PANEL_HEADER = "user_card_horizontal_right_widget";
    /** How far up from the list the profile page and the panel are looked for. */
    private static final int MAX_DEPTH = 16;

    /** A view's resource entry name. The tests name their views without resources. */
    interface Names {
        String of(View view);
    }

    static volatile Names namesForTests;

    /** Called at the end of every suggestion cell's bind and its list title's, with the cell. */
    public static void onBind(Object cell) {
        try {
            Object found = Reflect.readField(cell, "itemView");
            if (!(found instanceof View)) return;
            View view = (View) found;
            apply(view, Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.get());
            // A cell is bound before it's added to its list, so where it sits is read once it's
            // there; a post on a view that isn't attached yet runs as it attaches.
            view.post(() -> placeOnProfile(view));
        } catch (Throwable error) {
            Logger.printException(() -> "Could not collapse a suggestion cell", error);
        }
    }

    /**
     * On a profile, the cells sit in a panel with a "Suggested accounts" header and a list of
     * a fixed height, which TikTok opens after you follow someone. Collapsing the cells alone
     * left the header over an empty box (#462), so the panel goes with them. With Keep
     * suggestions on profiles on, the cells and the panel stay.
     */
    static void placeOnProfile(View cell) {
        try {
            View panel = profilePanel(cell);
            if (panel == null) return;
            boolean hide = Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.get()
                    && !Settings.KEEP_PROFILE_SUGGESTED_ACCOUNTS.get();
            apply(cell, hide);
            apply(panel, hide);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not collapse a profile's suggestions", error);
        }
    }

    /**
     * The box a profile's suggestions sit in: the nearest view above the cell's list that holds
     * the panel header, widened through the plain frames that hold only it. Null anywhere but a
     * profile page, so the Inbox, the Friends tab and the feed are left to the cells.
     */
    static View profilePanel(View cell) {
        if (!(cell.getParent() instanceof ViewGroup)) return null;
        View at = (View) cell.getParent();
        View panel = null;
        boolean onProfile = false;
        for (int depth = 0; depth < MAX_DEPTH && at.getParent() instanceof ViewGroup; depth++) {
            ViewGroup up = (ViewGroup) at.getParent();
            if (PROFILE.equals(name(up))) {
                onProfile = true;
                break;
            }
            if (panel == null && holds(up, PANEL_HEADER, 2)) panel = up;
            at = up;
        }
        if (!onProfile || panel == null) return null;
        while (panel.getParent() instanceof ViewGroup && ((ViewGroup) panel.getParent()).getChildCount() == 1
                && !PROFILE.equals(name((View) panel.getParent()))) {
            panel = (View) panel.getParent();
        }
        return panel;
    }

    /** Whether the group or a view up to {@code depth} levels into it has the entry name. */
    private static boolean holds(ViewGroup group, String entry, int depth) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (entry.equals(name(child))) return true;
            if (depth > 1 && child instanceof ViewGroup && holds((ViewGroup) child, entry, depth - 1)) return true;
        }
        return false;
    }

    private static String name(View view) {
        Names forTests = namesForTests;
        if (forTests != null) return forTests.of(view);
        int id = view.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    /** Collapses the view, or puts back what a collapse took, and says which it did. */
    public static boolean apply(View view, boolean hide) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        ViewGroup.MarginLayoutParams margins = params instanceof ViewGroup.MarginLayoutParams
                ? (ViewGroup.MarginLayoutParams) params : null;
        synchronized (COLLAPSED) {
            int[] original = COLLAPSED.get(view);
            if (hide) {
                if (original == null && params != null) {
                    COLLAPSED.put(view, margins == null
                            ? new int[]{params.width, params.height, view.getVisibility()}
                            : new int[]{params.width, params.height, view.getVisibility(),
                                    margins.leftMargin, margins.topMargin,
                                    margins.rightMargin, margins.bottomMargin});
                }
                if (params != null) {
                    params.width = 0;
                    params.height = 0;
                    if (margins != null) margins.setMargins(0, 0, 0, 0);
                    view.setLayoutParams(params);
                }
                view.setVisibility(View.GONE);
                return true;
            }
            if (original == null) return false;
            COLLAPSED.remove(view);
            if (params != null) {
                params.width = original[0];
                params.height = original[1];
                if (margins != null && original.length == 7) {
                    margins.setMargins(original[3], original[4], original[5], original[6]);
                }
                view.setLayoutParams(params);
            }
            view.setVisibility(original[2]);
            return false;
        }
    }
}
