/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.content.Context;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.privacy.AccountCleanupSources.Kind;
import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Failure;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Source;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;

/**
 * Runs one of the Privacy page's removals: a countdown with Cancel, then the removal in the
 * background with a running count and Stop, then the count it reached.
 *
 * <p>There is no dialog. The countdown is the chance to back out, so it has to be seen to the
 * end: if its banner is tapped away, replaced or the settings window closes, nothing starts.
 * Only one removal runs at a time.</p>
 */
public final class AccountCleanup {
    static final int COUNTDOWN_SECONDS = 5;

    private static final Object LOCK = new Object();
    /** The run in progress or counting down, or null. */
    private static Run active;

    /** Stand-ins for a test: TikTok's services, the waits, and where a run's thread comes from. */
    static volatile AccountCleanupSources.Services servicesForTests;
    static volatile CleanupRunner.Pacer pacerForTests;
    static volatile Executor executorForTests;

    private AccountCleanup() {
    }

    /** Starts the countdown for {@code kind}, or says why it can't. */
    public static void start(Context context, Kind kind) {
        Utils.runOnMainThreadNowOrLater(() -> begin(context, kind, null));
    }

    /** Starts the countdown for deleting the comments picked from the history (#442). */
    public static void startPicked(Context context, Collection<String> cids) {
        if (cids == null || cids.isEmpty()) return;
        List<String> picked = new ArrayList<>(cids);
        Utils.runOnMainThreadNowOrLater(() -> begin(context, Kind.COMMENTS, picked));
    }

    /** TikTok's services, or a test's stand-ins. */
    static AccountCleanupSources.Services services() {
        AccountCleanupSources.Services services = servicesForTests;
        return services != null ? services : AccountCleanupSources.TIKTOK;
    }

    /** Hears, on the main thread, a page of the comment history or why it couldn't be read. */
    public interface CommentsLoaded {
        void loaded(AccountCleanupSources.Comments page);

        void failed(End end);
    }

    /** Reads one page of the comment history from {@code offset} off the main thread, for the picker. */
    public static void loadComments(long offset, CommentsLoaded callback) {
        background(() -> {
            try {
                if (SignedInUser.id() == null) throw new Failure(End.STOPPED, "signed out");
                AccountCleanupSources.Comments page = AccountCleanupSources.commentPage(services(), offset);
                Utils.runOnMainThread(() -> callback.loaded(page));
            } catch (Failure failure) {
                Logger.printInfo(() -> "Comment history not read: " + failure.getMessage());
                Utils.runOnMainThread(() -> callback.failed(failure.end()));
            } catch (Throwable unexpected) {
                Logger.printException(() -> "Comment history not read", unexpected);
                Utils.runOnMainThread(() -> callback.failed(End.UNSUPPORTED));
            }
        });
    }

    /** What the picker says when the comment history couldn't be read. */
    public static String loadFailedText(Context context, End end) {
        switch (end) {
            case STOPPED:
                return L10n.t(context, "Sign in to TikTok first");
            case UNSUPPORTED:
                return L10n.t(context, "Not available on this TikTok version");
            case REFUSED:
                return L10n.t(context, "TikTok refused to list your comments");
            default:
                return L10n.t(context, "Couldn't load your comments. Try again.");
        }
    }

    private static void background(Runnable work) {
        Executor executor = executorForTests;
        if (executor != null) {
            executor.execute(work);
            return;
        }
        Thread thread = new Thread(work, "Hushfeed account cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void begin(Context context, Kind kind, List<String> picked) {
        if (SignedInUser.id() == null) {
            SettingsActionBanner.showNotice(context, L10n.t(context, "Sign in to TikTok first"));
            return;
        }
        Run run;
        synchronized (LOCK) {
            if (active != null) {
                Run running = active;
                // A countdown is on screen with its own Cancel; a running removal gets a Stop here.
                if (running.launched()) {
                    SettingsActionBanner.showAction(context, L10n.t(context, "A removal is already running"),
                            L10n.t(context, "Stop"), running::stop, null);
                }
                return;
            }
            run = new Run(context, kind, picked);
            active = run;
        }
        run.tick(COUNTDOWN_SECONDS);
    }

    /** Whether a removal is counting down or running. */
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

    static String countdownText(Context context, Kind kind, boolean picked, int seconds) {
        if (picked) return L10n.f(context, "Removing the picked comments in %1$d", seconds);
        switch (kind) {
            case REPOSTS:
                return L10n.f(context, "Removing all reposts in %1$d", seconds);
            case LIKES:
                return L10n.f(context, "Removing all likes in %1$d", seconds);
            case COMMENTS:
                return L10n.f(context, "Removing all comments in %1$d", seconds);
            default:
                return L10n.f(context, "Clearing Favorites in %1$d", seconds);
        }
    }

    static String progressText(Context context, Kind kind, int count) {
        switch (kind) {
            case REPOSTS:
                return L10n.f(context, "Reposts removed: %1$d", count);
            case LIKES:
                return L10n.f(context, "Likes removed: %1$d", count);
            case COMMENTS:
                return L10n.f(context, "Comments removed: %1$d", count);
            default:
                return L10n.f(context, "Favorites removed: %1$d", count);
        }
    }

    static String endText(Context context, End end, int count) {
        switch (end) {
            case DONE:
                return count == 0 ? L10n.t(context, "Nothing to remove")
                        : L10n.f(context, "Done. %1$d removed.", count);
            case STOPPED:
                return L10n.f(context, "Stopped. %1$d removed.", count);
            case REFUSED:
                return L10n.f(context, "TikTok refused. %1$d removed.", count);
            case NETWORK:
                return L10n.f(context, "Network error. %1$d removed.", count);
            default:
                return L10n.t(context, "Not available on this TikTok version");
        }
    }

    /** One removal, from its countdown to its last word. */
    private static final class Run {
        private final WeakReference<Context> context;
        /** For text once the settings window is gone; never a screen. */
        private final Context application;
        private final Kind kind;
        /** The comments picked from the history, or null for a whole list. */
        private final List<String> picked;
        private final Runnable cancel = this::cancel;
        private final Runnable stop = this::stop;
        private volatile CleanupRunner runner;
        private boolean cancelled;

        Run(Context context, Kind kind, List<String> picked) {
            this.context = new WeakReference<>(context);
            Context app = context.getApplicationContext();
            this.application = app != null ? app : context;
            this.kind = kind;
            this.picked = picked;
        }

        private Context ui() {
            Context shown = context.get();
            return shown != null ? shown : application;
        }

        void tick(int seconds) {
            if (cancelled) return;
            Context ui = ui();
            // The first second puts the banner up; after that the countdown only goes on while
            // its banner is still in sight, so a run never starts unseen.
            boolean first = seconds == COUNTDOWN_SECONDS;
            if (!first && !SettingsActionBanner.showingProgressOf(cancel)) {
                cancelled = true;
                finished(this);
                return;
            }
            if (seconds == 0) {
                launch();
                return;
            }
            if (!SettingsActionBanner.showProgress(ui, countdownText(ui, kind, picked != null, seconds),
                    L10n.t(ui, "Cancel"), cancel, first)) {
                cancelled = true;
                finished(this);
                return;
            }
            Utils.runOnMainThreadDelayed(() -> tick(seconds - 1), 1_000L);
        }

        private void cancel() {
            if (cancelled || runner != null) return;
            cancelled = true;
            finished(this);
            SettingsActionBanner.showNotice(ui(), L10n.t(ui(), "Cancelled"));
        }

        boolean launched() {
            return runner != null;
        }

        void stop() {
            CleanupRunner started = runner;
            if (started != null) {
                started.stop();
            } else {
                cancel();
            }
        }

        private void launch() {
            Source source;
            try {
                source = picked != null ? AccountCleanupSources.pickedCommentsFor(picked, services())
                        : AccountCleanupSources.sourceFor(kind, services());
            } catch (Failure failure) {
                end(failure.end(), 0);
                return;
            }
            CleanupRunner.Pacer pacer = pacerForTests;
            CleanupRunner started = new CleanupRunner(source,
                    pacer != null ? pacer : new CleanupRunner.LockPacer(), new Random(),
                    count -> Utils.runOnMainThread(() -> {
                        Context ui = ui();
                        SettingsActionBanner.showProgress(ui, progressText(ui, kind, count),
                                L10n.t(ui, "Stop"), stop, false);
                    }));
            runner = started;
            Context ui = ui();
            SettingsActionBanner.showProgress(ui, progressText(ui, kind, 0), L10n.t(ui, "Stop"), stop, true);
            Runnable work = () -> {
                End end;
                try {
                    end = started.run();
                } catch (Throwable unexpected) {
                    Logger.printException(() -> "Account cleanup stopped on an error", unexpected);
                    end = End.UNSUPPORTED;
                }
                End result = end;
                Logger.printInfo(() -> "Account cleanup " + kind + " ended " + result
                        + " after " + started.removed());
                Utils.runOnMainThread(() -> end(result, started.removed()));
            };
            background(work);
        }

        private void end(End end, int count) {
            finished(this);
            SettingsActionBanner.dismissProgress(stop);
            Context ui = ui();
            String text = end == End.STOPPED && count == 0 && SignedInUser.id() == null
                    ? L10n.t(ui, "Sign in to TikTok first") : endText(ui, end, count);
            // A notice in the settings window when it's open, a toast anywhere else.
            SettingsActionBanner.showNotice(ui, text);
        }
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
