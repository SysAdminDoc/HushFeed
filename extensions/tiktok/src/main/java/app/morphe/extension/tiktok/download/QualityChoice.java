/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.text.format.Formatter;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Ask for the quality on each save: Download lists the renditions this video comes in and saves
 * the one picked, for that save alone. The list holds only what the save path can publish: the
 * playable renditions {@link QualitySelector#chooseForFile} picks from (no ByteVC2, which no other
 * player opens) and Automatic, TikTok's own file. Sizes are what TikTok lists for each source, so
 * they're said as about that much: the sound, tags and a caption written in all change the file
 * that lands. Nothing is fetched to fill them in.
 */
final class QualityChoice {
    /** The videos whose choice is on screen. */
    private static final Set<String> CHOOSING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** The addresses the report reads besides the gears, as getter and field. */
    private static final String[][] OTHER_ADDRESSES = {
            {"getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr"},
            {"getDownloadAddr", "downloadAddr"},
            {"getNewDownloadAddr", "newDownloadAddr"},
            {"getUIAlikeDownloadAddr", "uiAlikeAddr"},
    };

    private QualityChoice() {}

    /** One line of the choice: a rendition, or {@link VideoDownloads#AUTOMATIC}. */
    static final class Option {
        final Object pick;
        final String label;

        Option(Object pick, String label) {
            this.pick = pick;
            this.label = label;
        }
    }

    /**
     * Download on a video, before anything is fetched. True when the choice is up (or already
     * was), or a save of this video is under way, so Download goes no further; false leaves it to
     * the usual save. That's a post with nothing to choose between, a photo post, a phone that
     * still needs the storage permission (TikTok asks for it) or no screen to ask on.
     */
    static boolean offer(Object aweme, Context context) {
        if (!SettingsStatus.advancedDownloadsEnabled || !Settings.DOWNLOAD_ASK_QUALITY.get() || context == null) return false;
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        if (Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo") != null) return false;
        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) return false;
        if (VideoDownloads.saving(id)) {
            // A picked save is ours whatever else is on, and the usual path can hand an Automatic
            // Download straight to TikTok, which would make a second copy beside it.
            Utils.showToastShort(VideoDownloads.busyMessage(id));
            return true;
        }
        List<Option> options = options(Reflect.property(aweme, "getVideo", "video"), context);
        if (options.size() < 2) return false;
        Activity activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (!CHOOSING.add(id)) {
            // Said rather than swallowed, so a second tap doesn't read as a broken button.
            Utils.showToastShort(L10n.t("The quality choice is still open"));
            return true;
        }
        Utils.runOnMainThread(() -> ask(aweme, id, options));
        return true;
    }

    /**
     * Automatic first, then the playable renditions tallest first, H.264 before HEVC at a height,
     * the larger before the smaller. Two that would read the same are offered once. When no
     * playable gear reaches {@link QualitySelector#TALL_ENOUGH}, a taller H.264 or HEVC address
     * the video carries on its own is offered too (#240). What was offered and what was left out
     * goes to the report ({@link DownloadPickerReport}).
     */
    static List<Option> options(Object video, Context context) {
        List<Option> options = new ArrayList<>();
        Object raw = QualitySelector.rawGears(video);
        if (!(raw instanceof List<?>)) return options;
        List<String> report = new ArrayList<>();
        options.add(new Option(VideoDownloads.AUTOMATIC, sized(L10n.t("Automatic (TikTok's file)"),
                QualitySelector.addressSize(VideoDownloads.automaticSource(video)), context)));
        boolean dash = Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"));
        boolean needsSound = dash && !Settings.DOWNLOAD_WITHOUT_SOUND.get();
        int own = QualitySelector.ownHeight(video);
        report.add("TikTok's own file: " + (own > 0 ? own + "p" : "height unknown") + ", gears listed: "
                + ((List<?>) raw).size() + (dash ? ", separate sound track" : ""));
        reportOtherAddresses(video, report);
        List<Object> gears = new ArrayList<>();
        int tallest = 0;
        for (Object gear : (List<?>) raw) {
            String reason = null;
            if (!QualitySelector.hasUsableAddress(gear)) {
                reason = "no usable address";
            } else if (!QualitySelector.playable(gear)) {
                reason = "no other player opens " + codecName(gear);
            } else if (needsSound && VideoDownloads.audioUrls(video, gear).isEmpty()) {
                // A picture on its own track is saved only with a sound to put beside it.
                reason = "no sound track to put beside it";
            }
            if (reason != null) {
                report.add("gear " + QualitySelector.describe(gear) + " " + codecName(gear) + ": left out, " + reason);
                continue;
            }
            gears.add(gear);
            tallest = Math.max(tallest, QualitySelector.heightOf(gear));
        }
        for (QualitySelector.SourceGear source : QualitySelector.sources(video)) {
            if (source.getPlayAddr() == null) {
                report.add("source " + source.getGearName() + ": not on this video");
                continue;
            }
            String what = "source " + QualitySelector.describe(source) + " " + codecName(source) + ": ";
            if (!QualitySelector.hasUsableAddress(source)) {
                report.add(what + "left out, no usable address");
            } else if (dash) {
                report.add(what + "left out, the video has a separate sound track");
            } else if (tallest >= QualitySelector.TALL_ENOUGH) {
                report.add(what + "not needed, a gear at " + tallest + "p is offered");
            } else if (QualitySelector.heightOf(source) <= tallest) {
                report.add(what + "left out, no taller than the gears (" + tallest + "p)");
            } else {
                gears.add(source);
                report.add(what + "offered, taller than the gears (" + tallest + "p)");
            }
        }
        // Not Comparator.comparingInt, which arrived at API 24.
        Collections.sort(gears, (a, b) -> {
            int compare = Integer.compare(QualitySelector.heightOf(b), QualitySelector.heightOf(a));
            if (compare == 0) compare = Integer.compare(QualitySelector.codec(a), QualitySelector.codec(b));
            return compare != 0 ? compare : Long.compare(QualitySelector.size(b), QualitySelector.size(a));
        });
        Set<String> seen = new HashSet<>();
        for (Object gear : gears) {
            int height = QualitySelector.heightOf(gear);
            String shape = (height > 0 ? height + "p" : L10n.t("Unknown resolution"))
                    + (QualitySelector.codec(gear) == 1 ? " HEVC" : " H.264");
            String label = sized(shape, QualitySelector.size(gear), context);
            boolean offered = seen.add(label);
            if (offered) options.add(new Option(gear, label));
            if (!(gear instanceof QualitySelector.SourceGear)) {
                report.add("gear " + QualitySelector.describe(gear) + " " + codecName(gear) + ": "
                        + (offered ? "offered" : "left out, reads the same as another choice"));
            }
        }
        DownloadPickerReport.record(report);
        return options;
    }

    /**
     * Where TikTok lists a file apart from the gears, for the report: a copy better than the gears
     * would show up here (#240). Sizes and names only, since a shared report has no use for the
     * addresses.
     */
    private static void reportOtherAddresses(Object video, List<String> report) {
        for (String[] names : OTHER_ADDRESSES) {
            Object address = Reflect.property(video, names[0], names[1]);
            if (address == null) continue;
            int height = QualitySelector.dimension(address);
            report.add("address " + names[1] + ": " + (height > 0 ? height + "p" : "height unknown")
                    + ", " + QualitySelector.addressSize(address) + " bytes");
        }
        Object misc = Reflect.property(video, "getMiscDownloadAddrs", "miscDownloadAddrs");
        if (!(misc instanceof String) || ((String) misc).isEmpty()) return;
        try {
            report.add("address miscDownloadAddrs: " + new JSONObject((String) misc).names());
        } catch (JSONException notJson) {
            report.add("address miscDownloadAddrs: not JSON, " + ((String) misc).length() + " characters");
        }
    }

    private static String codecName(Object gear) {
        int code = QualitySelector.codec(gear);
        return code == 0 ? "H.264" : code == 1 ? "HEVC" : code == 2 ? "ByteVC2" : "codec " + code;
    }

    private static String sized(String what, long bytes, Context context) {
        return bytes > 0 ? L10n.f("%1$s, about %2$s", what, Formatter.formatShortFileSize(context, bytes))
                : L10n.f("%1$s, size unknown", what);
    }

    private static void ask(Object aweme, String id, List<Option> options) {
        try {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                throw new IllegalStateException("No screen to ask on");
            }
            SettingsUi.syncDarkMode(activity);
            String[] labels = new String[options.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = options.get(i).label;
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Save in which quality?"))
                    .setItems(labels, (ignored, which) -> {
                        CHOOSING.remove(id);
                        save(aweme, activity, options.get(which).pick);
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            // A screen torn down under the dialog, by a theme change say, takes it without a
            // dismiss, which would leave this video's choice open until TikTok restarts.
            Application application = activity.getApplication();
            Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityDestroyed(Activity destroyed) {
                    if (destroyed == activity) dialog.dismiss();
                }
                @Override public void onActivityCreated(Activity item, Bundle state) { }
                @Override public void onActivityStarted(Activity item) { }
                @Override public void onActivityResumed(Activity item) { }
                @Override public void onActivityPaused(Activity item) { }
                @Override public void onActivityStopped(Activity item) { }
                @Override public void onActivitySaveInstanceState(Activity item, Bundle state) { }
            };
            // Cancel, Back or a tap outside: nothing was fetched, and the next Download asks again.
            dialog.setOnDismissListener(ignored -> {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                CHOOSING.remove(id);
            });
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            application.registerActivityLifecycleCallbacks(lifecycle);
            try {
                dialog.show();
            } catch (RuntimeException failure) {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                throw failure;
            }
        } catch (RuntimeException failure) {
            CHOOSING.remove(id);
            // Download was pressed and TikTok's own save has stood aside, so Automatic runs here.
            Logger.printException(() -> "Could not ask for the save's quality", failure);
            save(aweme, Utils.getContext(), VideoDownloads.AUTOMATIC);
        }
    }

    /** The save Download would have started, at the picked rendition. */
    private static void save(Object aweme, Context context, Object pick) {
        if (!VideoDownloads.savesTheCover()) CoverSaver.beside(context, aweme);
        if (!VideoDownloads.startPicked(aweme, context, pick)) {
            Utils.showToastLong(L10n.t("Couldn't save. Try again, or choose Automatic."));
        }
    }

    static void resetForTests() {
        CHOOSING.clear();
    }
}
