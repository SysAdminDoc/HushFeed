/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.OverlayGlyphDrawable;
import app.morphe.extension.tiktok.feedfilter.FilteredCountPill;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Invisible controls button (#374): a small eye at the top left of the main feed. A tap draws
 * the controls the fade covers, the tabs included, at 0 and leaves them where they are and
 * taking taps, so an OLED screen isn't burning in the same buttons for hours while the reader
 * still knows where they are. The next tap brings them back, and the choice lasts across videos
 * and restarts.
 *
 * <p>It's the fade's own walk at a level of 0 ({@link VideoOverlayHider}), not a walk of its own:
 * this class only decides whether the mode applies this pass and keeps the button in step. The
 * button shows only while the main feed is certainly in front ({@link
 * FeedVisibility#onRecommendationFeed}), so it's gone on Profile, Inbox, Shop, under a comment
 * sheet and in a video opened from a profile or search, and the controls are back on all of
 * those. Out of Clear display only: that mode already takes the controls away.
 *
 * <p>The button never goes fully invisible itself. While the controls are, it sits at {@link
 * #GHOSTED_ALPHA} so the reader can still find the way back.
 */
public final class InvisibleControls {
    /** The button's side. */
    static final int SIZE_DP = 40;
    private static final int SIDE_DP = 12;
    /** How much of the button shows while the controls are invisible. */
    static final float GHOSTED_ALPHA = 0.3f;

    /** The button on the feed, or null. Main thread only. */
    private static WeakReference<View> buttonReference = new WeakReference<>(null);

    private InvisibleControls() {
    }

    /**
     * Whether the button belongs on screen this pass: its switch on, the overlay patch in the
     * bundle and not paused, out of Clear display, and the main feed certainly in front.
     */
    static boolean offered(Activity activity, boolean overlaysOn, boolean detailPager, boolean clearLive) {
        return overlaysOn && !detailPager && !clearLive && Settings.INVISIBLE_CONTROLS_BUTTON.get()
                && FeedVisibility.onRecommendationFeed(activity);
    }

    /** Whether the controls are drawn at 0 this pass: only where the button is there to undo it. */
    static boolean ghosting(boolean offered) {
        return offered && Settings.INVISIBLE_CONTROLS_ON.get();
    }

    /**
     * Keeps the button in step with this pass. With {@code kept} false (the switch off, Pause, or
     * no overlay patch) it comes off the window; kept but not offered it's put away until the
     * feed is back. Each write happens only on a change, since this runs on every layout pass.
     */
    static void apply(Activity activity, boolean kept, boolean offered, boolean ghosting) {
        View button = buttonReference.get();
        if (!kept) {
            remove(button);
            return;
        }
        if (!offered) {
            if (button != null && button.getVisibility() != View.GONE) button.setVisibility(View.GONE);
            return;
        }
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) content;
        if (button == null || button.getParent() != root) {
            remove(button);
            button = create(activity);
            int size = SettingsUi.dp(activity, SIZE_DP);
            root.addView(button, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));
            buttonReference = new WeakReference<>(button);
        }
        place(root, button);
        if (button.getVisibility() != View.VISIBLE) button.setVisibility(View.VISIBLE);
        show(button, ghosting);
    }

    private static View create(Activity activity) {
        View button = new View(activity);
        button.setClickable(true);
        button.setFocusable(true);
        SettingsUi.markAsButton(button);
        button.setOnClickListener(view -> {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                Settings.INVISIBLE_CONTROLS_ON.save(!Settings.INVISIBLE_CONTROLS_ON.get());
                Context context = view.getContext();
                if (context instanceof Activity) VideoOverlayHider.applyTo((Activity) context);
            } catch (Throwable error) {
                Logger.printException(() -> "Could not switch the invisible controls", error);
            }
        });
        return button;
    }

    /**
     * Under TikTok's top bar row, so it's clear of the LIVE button and the tabs, and under the
     * filtered count while that label shows.
     */
    private static void place(FrameLayout root, View button) {
        ViewGroup.LayoutParams raw = button.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
        int top = FilteredCountPill.freeTop(root);
        int start = SettingsUi.dp(root.getContext(), SIDE_DP);
        if (params.topMargin == top && params.getMarginStart() == start) return;
        params.topMargin = top;
        params.setMarginStart(start);
        button.setLayoutParams(params);
    }

    private static void show(View button, boolean ghosting) {
        if (!Boolean.valueOf(ghosting).equals(button.getTag())) {
            button.setTag(ghosting);
            Context context = button.getContext();
            Drawable glyph = new OverlayGlyphDrawable(
                    ghosting ? OverlayGlyphDrawable.Shape.EYE_OFF : OverlayGlyphDrawable.Shape.EYE,
                    SettingsUi.OVERLAY_TEXT, SettingsUi.dp(context, 2));
            button.setBackground(SettingsUi.overlayControl(context, SIZE_DP / 2, glyph));
            button.setContentDescription(L10n.t(context,
                    ghosting ? "Show the controls" : "Make the controls invisible"));
            button.setSelected(ghosting);
        }
        float alpha = ghosting ? GHOSTED_ALPHA : 1f;
        if (button.getAlpha() != alpha) button.setAlpha(alpha);
    }

    private static void remove(View button) {
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }
        buttonReference = new WeakReference<>(null);
    }

    static View buttonForTests() {
        return buttonReference.get();
    }

    static void resetForTests() {
        remove(buttonReference.get());
    }
}
