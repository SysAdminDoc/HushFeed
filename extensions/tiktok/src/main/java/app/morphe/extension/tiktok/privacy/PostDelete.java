/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.GridPicker;
import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Failure;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Page;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Source;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Deleting posts of yours picked on a grid (#487). With the switch on, the picking bar of
 * {@link GridPicker} offers Delete while every picked post is your own. A bar of its own then
 * counts down with Stop, and the posts go one at a time with the request a post's own Delete
 * sends, paced the way Account cleanup paces its removals. Each post leaves the grid as TikTok
 * says yes.
 *
 * <p>There is no dialog. The countdown is the chance to back out: Stop there deletes nothing, and
 * a countdown whose bar left the screen ends without deleting anything. Only one removal runs at
 * a time, this or one of Account cleanup's.</p>
 */
public final class PostDelete {
    static final int COUNTDOWN_SECONDS = AccountCleanup.COUNTDOWN_SECONDS;
    static final String BAR_TAG = "hushfeed_post_delete_bar";

    private static final Object LOCK = new Object();
    /** The run counting down or deleting, or null. */
    private static Run active;

    /** Stand-ins for a test: TikTok's services, the waits, and where a run's thread comes from. */
    static volatile AccountCleanupSources.Services servicesForTests;
    static volatile CleanupRunner.Pacer pacerForTests;
    static volatile Executor executorForTests;

    private PostDelete() {
    }

    /** Whether Delete belongs on the picking bar: the switch is on and every picked post is yours. */
    public static boolean offered(Collection<?> posts) {
        try {
            if (posts == null || posts.isEmpty() || !Settings.DELETE_PICKED_POSTS.get()) return false;
            String me = SignedInUser.id();
            if (me == null || me.isEmpty()) return false;
            for (Object post : posts) {
                if (!me.equals(authorOf(post))) return false;
            }
            return true;
        } catch (Throwable unreadable) {
            Logger.printException(() -> "Could not check whose posts are picked", unreadable);
            return false;
        }
    }

    /** The uid of the post's author, or null. */
    static String authorOf(Object post) {
        Object author = Reflect.property(post, "getAuthor", "author");
        return Reflect.firstNonBlank(Reflect.string(author, "getUid", "uid"),
                Reflect.string(post, "getAuthorUid", "authorUid"));
    }

    /**
     * Delete on the picking bar: counts down over the screen, then deletes the posts {@code ids}
     * names. {@code cell} is the cell picked from last, whose grid each deleted post leaves.
     */
    public static void start(Activity activity, View cell, List<String> ids) {
        try {
            if (activity == null || ids == null || ids.isEmpty()) return;
            if (SignedInUser.id() == null) {
                Utils.showToastLong(L10n.t(activity, "Sign in to TikTok first"));
                return;
            }
            Run run;
            synchronized (LOCK) {
                if (active != null || AccountCleanup.running()) {
                    Utils.showToastLong(L10n.t(activity, "A removal is already running"));
                    return;
                }
                run = new Run(activity, cell == null ? null : GridPicker.adapterOf(cell), ids);
                active = run;
            }
            if (!run.show()) {
                finished(run);
                return;
            }
            run.tick(COUNTDOWN_SECONDS);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not start deleting the picked posts", failure);
        }
    }

    /** Whether a deletion is counting down or running. */
    public static boolean running() {
        synchronized (LOCK) {
            return active != null;
        }
    }

    private static void finished(Run run) {
        synchronized (LOCK) {
            if (active == run) active = null;
        }
    }

    private static AccountCleanupSources.Services services() {
        AccountCleanupSources.Services services = servicesForTests;
        return services != null ? services : AccountCleanupSources.TIKTOK;
    }

    private static void background(Runnable work) {
        Executor executor = executorForTests;
        if (executor != null) {
            executor.execute(work);
            return;
        }
        Thread thread = new Thread(work, "Hushfeed post delete");
        thread.setDaemon(true);
        thread.start();
    }

    static String countdownText(Context context, int posts, int seconds) {
        return L10n.f(context, "Posts to delete: %1$d. Starting in %2$d", posts, seconds);
    }

    static String progressText(Context context, int deleted, int posts) {
        return L10n.f(context, "Posts deleted: %1$d of %2$d", deleted, posts);
    }

    /** One deletion, from its countdown to its last word. Main thread, but for the run itself. */
    private static final class Run {
        private final Activity activity;
        /** The grid the posts were picked on, or null when it couldn't be read. */
        private final Object adapter;
        private final List<String> ids;
        private View bar;
        private TextView label;
        private TextView stopButton;
        private volatile CleanupRunner runner;
        private boolean cancelled;

        Run(Activity activity, Object adapter, List<String> ids) {
            this.activity = activity;
            this.adapter = adapter;
            this.ids = Collections.unmodifiableList(new ArrayList<>(ids));
        }

        boolean show() {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return false;
            LinearLayout row = new LinearLayout(activity);
            row.setTag(BAR_TAG);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 4),
                    SettingsUi.dp(activity, 8), SettingsUi.dp(activity, 4));
            row.setBackground(SettingsUi.overlayBanner(activity));
            // A tap beside Stop mustn't fall through to the grid under the bar.
            row.setClickable(true);

            label = new TextView(activity);
            label.setTextColor(SettingsUi.OVERLAY_TEXT);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
            label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            stopButton = GridPicker.button(activity, L10n.t(activity, "Stop"));
            stopButton.setOnClickListener(view -> stop());
            row.addView(stopButton, new LinearLayout.LayoutParams(-2, -2));

            // Leaving TikTok mid-run stops it there, as Stop would.
            row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                }

                @Override public void onViewDetachedFromWindow(View view) {
                    CleanupRunner started = runner;
                    if (started != null && (activity.isFinishing() || activity.isDestroyed())) started.stop();
                }
            });
            content.addView(row, BlockAuthorOverlay.bannerParams(activity, content));
            bar = row;
            return true;
        }

        private boolean inSight() {
            return bar != null && bar.isAttachedToWindow() && !activity.isFinishing() && !activity.isDestroyed();
        }

        private void say(String text) {
            if (label == null) return;
            label.setText(text);
            label.setContentDescription(text);
        }

        void tick(int seconds) {
            if (cancelled) return;
            if (!inSight()) {
                // The screen it counted down on is gone: nothing is deleted, and nothing is said.
                cancelled = true;
                takeDown();
                finished(this);
                return;
            }
            if (seconds == 0) {
                launch();
                return;
            }
            say(countdownText(activity, ids.size(), seconds));
            Utils.runOnMainThreadDelayed(() -> tick(seconds - 1), 1_000L);
        }

        private void stop() {
            CleanupRunner started = runner;
            if (started != null) {
                started.stop();
                if (stopButton != null) stopButton.setEnabled(false);
                return;
            }
            if (cancelled) return;
            cancelled = true;
            takeDown();
            finished(this);
            Utils.showToastShort(L10n.t(activity, "Cancelled"));
        }

        private void launch() {
            Source source;
            try {
                source = AccountCleanupSources.pickedPostsFor(ids, services());
            } catch (Failure failure) {
                end(failure.end(), 0);
                return;
            }
            Source dropping = new Source() {
                @Override
                public Page fetch(long cursor) throws Failure {
                    return source.fetch(cursor);
                }

                @Override
                public void remove(String id) throws Failure {
                    source.remove(id);
                    Utils.runOnMainThread(() -> drop(id));
                }
            };
            CleanupRunner.Pacer pacer = pacerForTests;
            CleanupRunner started = new CleanupRunner(dropping,
                    pacer != null ? pacer : new CleanupRunner.LockPacer(), new Random(),
                    count -> Utils.runOnMainThread(() -> say(progressText(activity, count, ids.size()))));
            runner = started;
            say(progressText(activity, 0, ids.size()));
            background(() -> {
                End end;
                try {
                    end = started.run();
                } catch (Throwable unexpected) {
                    Logger.printException(() -> "Deleting the picked posts stopped on an error", unexpected);
                    end = End.UNSUPPORTED;
                }
                End result = end;
                int count = started.removed();
                Logger.printInfo(() -> "Post delete ended " + result + " after " + count + " of " + ids.size());
                Utils.runOnMainThread(() -> end(result, count));
            });
        }

        /** Takes a deleted post off the grid it was picked on. */
        private void drop(String id) {
            try {
                QuickRemove.dropFromAdapter(adapter, id);
            } catch (Throwable failure) {
                Logger.printException(() -> "Could not take a deleted post off the grid", failure);
            }
        }

        private void end(End end, int count) {
            takeDown();
            finished(this);
            String text = end == End.STOPPED && count == 0 && SignedInUser.id() == null
                    ? L10n.t(activity, "Sign in to TikTok first") : AccountCleanup.endText(activity, end, count);
            Utils.showToastLong(text);
        }

        private void takeDown() {
            View shown = bar;
            bar = null;
            label = null;
            stopButton = null;
            if (shown != null && shown.getParent() instanceof ViewGroup) ((ViewGroup) shown.getParent()).removeView(shown);
        }
    }

    /** For the tests: the bar on screen, or null. */
    static View barForTests(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        return content == null ? null : content.findViewWithTag(BAR_TAG);
    }

    static void resetForTests() {
        synchronized (LOCK) {
            active = null;
        }
        servicesForTests = null;
        pacerForTests = null;
        executorForTests = null;
    }
}
