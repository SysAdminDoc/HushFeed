/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.view.View;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.profile.FollowLists;
import app.morphe.extension.tiktok.profile.FollowLists.End;
import app.morphe.extension.tiktok.profile.FollowLists.Kind;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Export followers and following: reads the signed-in account's followers, or the accounts it
 * follows, with a running count and Stop, and saves them as a CSV file in Download/Hushfeed.
 * Stopping keeps what was read so far, and the notice says the file is partial.
 */
@SuppressWarnings("deprecation")
public final class FollowListExportPreference extends Preference {
    static final String KEY = "action_export_follow_lists";
    static final String SUMMARY = "Saves the accounts that follow you, or the ones you follow, as a CSV file "
            + "in Download/Hushfeed. They're read at about the pace of scrolling the list, up to %1$s at a time.";

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** The run on screen now: Stop sets it. */
    private static volatile AtomicBoolean stop;
    private static final Runnable STOP = () -> {
        AtomicBoolean running = stop;
        if (running != null) running.set(true);
    };

    public FollowListExportPreference(Context context) {
        super(context);
        setKey(KEY);
        setPersistent(false);
        setTitle(L10n.t(context, "Export followers and following"));
        setSummary(L10n.f(context, SUMMARY, NumberFormat.getIntegerInstance().format(FollowLists.MAX_ROWS)));
        setOnPreferenceClickListener(preference -> {
            choose(getContext());
            return true;
        });
    }

    static void choose(Context window) {
        if (RUNNING.get()) {
            SettingsActionBanner.showNotice(window, L10n.t(window, "A list is already being read"));
            return;
        }
        CharSequence[] lists = {L10n.t(window, "Followers"), L10n.t(window, "Following")};
        AlertDialog dialog = new AlertDialog.Builder(window)
                .setTitle(L10n.t(window, "Export followers and following"))
                .setItems(lists, (ignored, which) -> start(window, which == 0 ? Kind.FOLLOWERS : Kind.FOLLOWING))
                .setNegativeButton(L10n.t(window, "Cancel"), null)
                .show();
        SettingsUi.styleStandardAlertDialog(dialog);
    }

    static void start(Context window, Kind kind) {
        if (SignedInUser.id() == null) {
            SettingsActionBanner.showNotice(window, L10n.t(window, "Sign in to TikTok first"));
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            SettingsActionBanner.showNotice(window, L10n.t(window, "A list is already being read"));
            return;
        }
        AtomicBoolean stopped = new AtomicBoolean();
        stop = stopped;
        Context app = window.getApplicationContext() != null ? window.getApplicationContext() : window;
        WeakReference<Context> shown = new WeakReference<>(window);
        String handle = SignedInUser.handle();
        SettingsActionBanner.showProgress(window, progressText(window, kind, 0), L10n.t(window, "Stop"), STOP, true);
        boolean started = Utils.runOnOwnThread("Hushfeed-follow-list", () -> {
            String notice;
            try {
                FollowLists.Result result = FollowLists.read(kind, stopped, count -> Utils.runOnMainThread(() -> {
                    Context where = where(shown, app);
                    if (stop == stopped && !stopped.get()) {
                        SettingsActionBanner.showProgress(where, progressText(where, kind, count),
                                L10n.t(where, "Stop"), STOP, false);
                    }
                }));
                notice = finish(app, kind, handle, result);
            } catch (Throwable unexpected) {
                Logger.printException(() -> "Follow list export stopped on an error", unexpected);
                notice = L10n.t(app, "Couldn't read the list. Try again in a moment.");
            }
            String said = notice;
            Utils.runOnMainThread(() -> {
                SettingsActionBanner.dismissProgress(STOP);
                if (stop == stopped) stop = null;
                RUNNING.set(false);
                SettingsActionBanner.showNotice(where(shown, app), said);
            });
        });
        if (!started) {
            SettingsActionBanner.dismissProgress(STOP);
            stop = null;
            RUNNING.set(false);
            SettingsActionBanner.showNotice(window, L10n.t(window, "Couldn't read the list. Try again in a moment."));
        }
    }

    private static Context where(WeakReference<Context> shown, Context app) {
        Context window = shown.get();
        return window != null ? window : app;
    }

    static String progressText(Context context, Kind kind, int count) {
        String shown = NumberFormat.getIntegerInstance().format(count);
        return kind == Kind.FOLLOWERS
                ? L10n.f(context, "Reading your followers (%1$s)", shown)
                : L10n.f(context, "Reading who you follow (%1$s)", shown);
    }

    /** Saves what was read, on the run's thread, and says how it went. */
    static String finish(Context context, Kind kind, String handle, FollowLists.Result result) {
        if (result.end == End.SIGNED_OUT) return L10n.t(context, "Sign in to TikTok first");
        if (result.accounts.isEmpty()) {
            if (result.end == End.FAILED) return L10n.t(context, "Couldn't read the list. Try again in a moment.");
            if (result.end == End.STOPPED) return L10n.t(context, "Stopped before anyone was read");
            return kind == Kind.FOLLOWERS
                    ? L10n.t(context, "Nobody follows this account yet")
                    : L10n.t(context, "This account doesn't follow anyone yet");
        }
        String where;
        try {
            where = GateReportExport.write(context, (char) 0xFEFF + FollowLists.toCsv(result.accounts),
                    fileName(kind, handle), "text/csv");
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "Could not save the follow list", failure);
            return L10n.t(context, "The file couldn't be saved. Try again.");
        }
        return message(context, result, where);
    }

    static String message(Context context, FollowLists.Result result, String where) {
        int count = result.accounts.size();
        String saved = L10n.quantity(context, count, "Saved one account to %2$s", "Saved %1$s accounts to %2$s",
                NumberFormat.getIntegerInstance().format(count), where);
        switch (result.end) {
            case STOPPED:
                return saved + "\n" + L10n.t(context, "It stopped before the end of the list, so the file is partial.");
            case FAILED:
                return saved + "\n" + L10n.t(context, "TikTok stopped answering before the end of the list, so the file is partial.");
            case CAPPED:
                return saved + "\n" + L10n.f(context, "Only the first %1$s are in the file.",
                        NumberFormat.getIntegerInstance().format(FollowLists.MAX_ROWS));
            default:
                return saved;
        }
    }

    /** tiktok-followers-<handle>-<time>.csv, so two accounts' lists can't be mixed up. */
    static String fileName(Kind kind, String handle) {
        String who = handle == null ? "" : handle.replaceAll("[^A-Za-z0-9._-]", "");
        return "tiktok-" + (kind == Kind.FOLLOWERS ? "followers" : "following")
                + (who.isEmpty() ? "" : "-" + who.toLowerCase(Locale.ROOT))
                + "-" + LogBufferManager.fileTimestamp() + ".csv";
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    static void resetForTests() {
        RUNNING.set(false);
        stop = null;
    }
}
