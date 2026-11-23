/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

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

    /** Sections already watched, and the slots this class collapsed (only those are opened again). */
    private static final Set<View> WATCHED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> COLLAPSED = Collections.newSetFromMap(new WeakHashMap<>());

    private ProfileMenuCells() {
    }

    /**
     * A section of the menu, as TikTok makes its view (#194). Each section is a rounded card with
     * its own padding, in a slot with its own margins, so a section whose only cell is hidden
     * stayed behind as an empty sliver: the "Balance" with no label. After every layout while the
     * section is on screen, its slot collapses if nothing in it shows, and opens again once
     * something does.
     */
    public static void section(View view) {
        if (view == null) return;
        synchronized (WATCHED) {
            if (!WATCHED.add(view)) return;
        }
        ViewTreeObserver.OnGlobalLayoutListener check = () -> fit(view);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) {
                attached.getViewTreeObserver().addOnGlobalLayoutListener(check);
            }

            @Override public void onViewDetachedFromWindow(View detached) {
                detached.getViewTreeObserver().removeOnGlobalLayoutListener(check);
            }
        });
        if (view.isAttachedToWindow()) view.getViewTreeObserver().addOnGlobalLayoutListener(check);
        HookStatus.bound("profile menu sections", "section watched");
    }

    /**
     * Collapses the section's slot while either hide switch is on and nothing in the section
     * shows, and opens a slot this class collapsed once something does or both switches are off.
     */
    static void fit(View section) {
        View slot = slotOf(section);
        boolean empty = (Settings.HIDE_PROFILE_MENU_BALANCE.get() || Settings.HIDE_PROFILE_MENU_MINIS_ICONS.get())
                && holdsOnlyHidden(slot, 0);
        if (empty) {
            if (slot.getVisibility() != View.GONE) {
                slot.setVisibility(View.GONE);
                COLLAPSED.add(slot);
                HookStatus.bound("profile menu sections", "empty section collapsed");
            }
        } else if (COLLAPSED.remove(slot)) {
            slot.setVisibility(View.VISIBLE);
        }
    }

    /** The section's slot: the parent that holds nothing but the section, or the section itself. */
    static View slotOf(View section) {
        if (section.getParent() instanceof ViewGroup && ((ViewGroup) section.getParent()).getChildCount() == 1) {
            return (View) section.getParent();
        }
        return section;
    }

    /** Whether a view holds children and every one of them is gone, or holds only gone ones. */
    private static boolean holdsOnlyHidden(View view, int depth) {
        if (!(view instanceof ViewGroup) || depth >= MAX_DEPTH) return false;
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() == 0) return false;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.GONE && !holdsOnlyHidden(child, depth + 1)) return false;
        }
        return true;
    }

    /** Asked for each cell before TikTok adds it to the menu. True leaves the cell out. */
    public static boolean hide(String cell) {
        if (BALANCE.equals(cell)) {
            boolean hide = Settings.HIDE_PROFILE_MENU_BALANCE.get();
            HookStatus.bound("profile menu balance", hide ? "cell hidden" : "cell left");
            return hide;
        }
        if (MINIS_ICONS.equals(cell)) {
            boolean hide = Settings.HIDE_PROFILE_MENU_MINIS_ICONS.get();
            HookStatus.bound("profile menu minis icons", hide ? "cell hidden" : "cell left");
            return hide;
        }
        return false;
    }
}
