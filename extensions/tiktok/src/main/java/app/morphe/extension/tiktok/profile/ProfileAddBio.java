/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The "+ Add bio" prompt on your own profile (#466), the pill TikTok puts under your name while
 * the profile has no bio.
 *
 * <p>The header's Add bio component shows its view when the bio is empty and hides it when there
 * is one, every time the header binds. The patch asks here right after the view is shown, so with
 * the switch on it goes straight back to gone, and the rest of the bind (which reads the view's
 * visibility to decide whether it was shown) sees a profile with nothing to prompt for.
 *
 * <p>The view is also the component's whole view, and TikTok's profile framework keeps a
 * visibility of its own for each component that it puts back on that view when the header
 * updates. Clearing your bio in Edit profile and coming back showed the prompt again that way
 * (S22, 2026-11-18), so every layout pass while the switch is on puts it back to gone too. The
 * view is only ever the prompt, so nothing else is hidden by that, and the switch turned off
 * leaves it to TikTok.
 */
public final class ProfileAddBio {
    private static final String FAMILY = "profile add bio";
    private static final WeakHashMap<View, Keeper> KEEPERS = new WeakHashMap<>();

    private ProfileAddBio() {
    }

    /** Called with the prompt's view right after TikTok shows it. */
    public static void shown(View prompt) {
        boolean hide = Settings.HIDE_ADD_BIO.get();
        HookStatus.bound(FAMILY, hide ? "prompt hidden" : "prompt left");
        if (prompt == null) return;
        if (hide) prompt.setVisibility(View.GONE);
        try {
            if (KEEPERS.containsKey(prompt)) return;
            Keeper keeper = new Keeper(prompt);
            KEEPERS.put(prompt, keeper);
            prompt.addOnAttachStateChangeListener(keeper);
            if (prompt.isAttachedToWindow()) keeper.onViewAttachedToWindow(prompt);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not keep the Add bio prompt hidden", error);
        }
    }

    /** Puts the prompt back to gone whenever a layout pass finds it showing with the switch on. */
    private static final class Keeper implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<View> prompt;
        private ViewTreeObserver observer;

        Keeper(View prompt) {
            this.prompt = new WeakReference<>(prompt);
        }

        @Override public void onViewAttachedToWindow(View view) {
            if (observer != null) return;
            observer = view.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
            onGlobalLayout();
        }

        @Override public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
        }

        @Override public void onGlobalLayout() {
            View view = prompt.get();
            if (view == null || view.getVisibility() == View.GONE || !Settings.HIDE_ADD_BIO.get()) return;
            view.setVisibility(View.GONE);
            Logger.printDebug(() -> "Profile add bio: TikTok showed the prompt again, hidden");
        }
    }
}
