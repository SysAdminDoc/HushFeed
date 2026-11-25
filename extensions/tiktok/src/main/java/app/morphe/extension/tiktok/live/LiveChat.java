/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hide LIVE chat (#525): keeps the messages that scroll over a LIVE room's stream out of sight.
 * TikTok calls that list the public screen, and each room loads it as a widget whose show and
 * clear screen code set its view visible again whenever they run. So rather than hide it once,
 * the view is checked before each frame it would draw and made invisible while the switch is on.
 * Invisible keeps its place in the layout, so nothing around it moves, and an invisible view takes
 * no touches, so a tap there reaches the stream. The comment box is its own widget and stays.
 */
public final class LiveChat {
    static final String HOOK_FAMILY = "LIVE chat";

    private static final Map<View, Watch> watched = Collections.synchronizedMap(new WeakHashMap<>());

    private LiveChat() {}

    /** True while the chat should stay out of sight. Paused, the switch answers off. */
    static boolean hidden() {
        return SettingsStatus.liveControlsEnabled && Settings.HIDE_LIVE_CHAT.get();
    }

    /** Called first in the chat widget's onLoad, with the widget's view, as each room loads it. */
    public static void onLoad(@Nullable View chat) {
        if (chat == null) return;
        try {
            Watch watch = watched.get(chat);
            if (watch == null) {
                watch = new Watch(chat);
                watched.put(chat, watch);
                chat.addOnAttachStateChangeListener(watch);
                if (chat.isAttachedToWindow()) watch.observe(chat);
            }
            watch.apply(chat);
            HookStatus.bound(HOOK_FAMILY, "chat");
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "chat", failure);
        }
    }

    /** Keeps one chat view hidden while it's attached to a window. */
    static final class Watch implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final WeakReference<View> chat;
        @Nullable private ViewTreeObserver observer;
        /** Whether the view is invisible because of the switch, so turning it off shows it again. */
        private boolean hid;

        Watch(View chat) {
            this.chat = new WeakReference<>(chat);
        }

        void observe(View view) {
            stop();
            observer = view.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        private void stop() {
            ViewTreeObserver old = observer;
            observer = null;
            if (old != null && old.isAlive()) old.removeOnPreDrawListener(this);
        }

        void apply(View view) {
            if (hidden()) {
                if (view.getVisibility() == View.VISIBLE) {
                    view.setVisibility(View.INVISIBLE);
                    hid = true;
                }
            } else if (hid) {
                hid = false;
                if (view.getVisibility() == View.INVISIBLE) view.setVisibility(View.VISIBLE);
            }
        }

        @Override public boolean onPreDraw() {
            View view = chat.get();
            if (view == null) {
                stop();
                return true;
            }
            try {
                apply(view);
            } catch (RuntimeException failure) {
                stop();
                HookStatus.threw(HOOK_FAMILY, "chat", failure);
            }
            // The frame goes ahead: the view is already invisible for it, and holding frames back
            // while something sets it visible every frame would freeze the room.
            return true;
        }

        @Override public void onViewAttachedToWindow(View view) {
            observe(view);
            apply(view);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            stop();
        }
    }
}
