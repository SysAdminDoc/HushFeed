/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Cells in the menu your profile's top right button opens (#194). Balance is the one that shows
 * your coins and gift earnings, and the TikTok Minis entry has a second cell of app icons (#230).
 *
 * <p>TikTok asks one method about each cell by its id while it builds that menu, and leaves a cell
 * out when the answer is no. The patch puts {@link #hide} in front of that question, so a hidden
 * cell is left out the way TikTok leaves out one it doesn't offer you. Nothing is sent, and your
 * coins and the Balance page itself are untouched.
 */
@SuppressWarnings("unused")
public final class ProfileMenuCells {
    /** The Balance cell's id, the same on every build that has the cell. */
    static final String BALANCE = "content_section_cell_balance";
    /**
     * The card of app icons under the TikTok Minis entry (#230). It is a cell of its own with its
     * own id, so leaving it out keeps the TikTok Minis entry and drops the icons.
     */
    static final String MINIS_ICONS = "content_section_cell_tiktok_minis_rich_card";

    /** How far down a section is searched for something that still shows. */
    private static final int MAX_DEPTH = 6;
    /** How far up from a cell list its section is looked for. */
    private static final int MAX_SECTION_DISTANCE = 6;

    /** The menu's sections as TikTok makes them, and the slots this class collapsed. */
    private static final Set<View> SECTIONS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> COLLAPSED = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The list TikTok is filling on this thread: whether the last cell asked about was hidden
     * here, and how many cells it kept and how many this class took out since the list began.
     */
    private static final ThreadLocal<int[]> FILLING = ThreadLocal.withInitial(() -> new int[3]);
    private static final int LAST_HIDDEN = 0;
    private static final int KEPT = 1;
    private static final int TAKEN_OUT = 2;

    private ProfileMenuCells() {
    }

    /**
     * A section of the menu, as TikTok makes its view (#194). Each section is a rounded card with
     * its own padding, in a slot with its own margins, so a section whose only cell is hidden
     * stayed behind as an empty sliver: the "Balance" with no label. {@link #listFilled} collapses
     * that slot once the section's list is filled with nothing.
     */
    public static void section(View view) {
        if (view == null) return;
        synchronized (SECTIONS) {
            if (!SECTIONS.add(view)) return;
        }
        HookStatus.bound("profile menu sections", "section watched");
    }

    /**
     * TikTok's answer for one cell of the list it is filling, right after it asked: true keeps the
     * cell. A cell left out right after {@link #hide} said so is one this class took out.
     */
    public static void cellAnswered(boolean shown) {
        int[] filling = FILLING.get();
        if (shown) filling[KEPT]++;
        else if (filling[LAST_HIDDEN] != 0) filling[TAKEN_OUT]++;
        filling[LAST_HIDDEN] = 0;
    }

    /**
     * TikTok finished filling one of the menu's cell lists. When this class took out a cell and the
     * list kept none, its section collapses, margins and all. A section whose list keeps a cell
     * is never touched, and one collapsed here opens again once its list keeps one.
     *
     * <p>Only what the list was filled with decides it, never how the menu looks while it lays
     * out. TikTok lays the sections out once before their cells arrive, and a slot collapsed then
     * was never laid out again, so its cells never showed and the menu stayed black.
     */
    public static void listFilled(View list) {
        int[] filling = FILLING.get();
        boolean emptied = filling[TAKEN_OUT] > 0 && filling[KEPT] == 0;
        filling[LAST_HIDDEN] = 0;
        filling[KEPT] = 0;
        filling[TAKEN_OUT] = 0;
        if (list == null) return;
        if (!place(list, emptied)) {
            // TikTok can fill a list before it's in the menu. A view not yet on screen runs this
            // once it is.
            list.post(() -> place(list, emptied));
        }
    }

    /** Collapses or opens the list's section. False when the list isn't in a known section yet. */
    private static boolean place(View list, boolean emptied) {
        View section = sectionOf(list);
        if (section == null) return false;
        View slot = slotOf(section);
        if (emptied && holdsOnlyHidden(slot, 0, list)) {
            if (slot.getVisibility() != View.GONE) {
                slot.setVisibility(View.GONE);
                COLLAPSED.add(slot);
                HookStatus.bound("profile menu sections", "empty section collapsed");
            }
        } else if (!emptied && COLLAPSED.remove(slot)) {
            slot.setVisibility(View.VISIBLE);
        }
        return true;
    }

    /** The menu section holding the list, or null. */
    private static View sectionOf(View list) {
        View view = list;
        for (int up = 0; view != null && up <= MAX_SECTION_DISTANCE; up++) {
            synchronized (SECTIONS) {
                if (SECTIONS.contains(view)) return view;
            }
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** The section's slot: the parent that holds nothing but the section, or the section itself. */
    static View slotOf(View section) {
        if (section.getParent() instanceof ViewGroup && ((ViewGroup) section.getParent()).getChildCount() == 1) {
            return (View) section.getParent();
        }
        return section;
    }

    /**
     * Whether a view holds children and every one of them is gone or holds only gone ones. The
     * emptied list counts as gone: TikTok may not have hidden it yet.
     */
    private static boolean holdsOnlyHidden(View view, int depth, View emptied) {
        if (!(view instanceof ViewGroup) || depth >= MAX_DEPTH) return false;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() == 0) return false;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == emptied || child.getVisibility() == View.GONE) continue;
            if (!holdsOnlyHidden(child, depth + 1, emptied)) return false;
        }
        return true;
    }

    /** Asked for each cell before TikTok adds it to the menu. True leaves the cell out. */
    public static boolean hide(String cell) {
        boolean hide = false;
        if (BALANCE.equals(cell)) {
            hide = Settings.HIDE_PROFILE_MENU_BALANCE.get();
            HookStatus.bound("profile menu balance", hide ? "cell hidden" : "cell left");
        } else if (MINIS_ICONS.equals(cell)) {
            hide = Settings.HIDE_PROFILE_MENU_MINIS_ICONS.get();
            HookStatus.bound("profile menu minis icons", hide ? "cell hidden" : "cell left");
        }
        FILLING.get()[LAST_HIDDEN] = hide ? 1 : 0;
        return hide;
    }
}
