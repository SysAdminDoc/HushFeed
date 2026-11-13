/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.text.format.DateUtils;
import android.widget.Button;
import android.widget.ListView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A checklist of the person's stories, opened by a story save with Pick which stories to save
 * on (#393), like the photo picker TikTok shows for a photo post.
 *
 * <p>The story viewer binds one model per person, whose id is the person's own. Its
 * {@code getUserStory().getStories()} holds their stories in the order the viewer plays them,
 * the one on screen among them by id. TikTok fills that list a page at a time as the viewer
 * moves on (8 of 22 at first on 47.7.3, then 18 after seven taps), so the checklist holds what
 * has loaded and its title says how many are still to come.
 *
 * <p>One pick saves the way a single save does, extras included. Several go out as one
 * {@link BatchSave} run with one progress row and one banner.
 */
public final class StoryPicker {
    static final String DIALOG_KEY = "story picker";

    /**
     * Whether a checklist is up, and the story hold release it is holding back: the monitor that
     * paused the story and the name its release goes by in this build. Main thread only, like
     * the touches and the dialog that set them.
     */
    private static boolean open;
    private static WeakReference<Object> heldMonitor = new WeakReference<>(null);
    private static String heldRelease;

    private StoryPicker() {
    }

    /**
     * First thing in the story hold's release, which TikTok sends when the finger lifts and which
     * plays the story on. The hold opens the checklist with the finger still down, so the lift
     * came while it was up, and the story played on behind it, through the person's last story
     * and out of the viewer, with the story it marked as on screen long gone (#393). While the
     * checklist is up the release waits, and {@link #closed} sends it.
     *
     * @param release the release's name in this build, so it can be sent again.
     * @return true to keep the story paused.
     */
    public static boolean holdRelease(Object monitor, String release) {
        if (!open || monitor == null || release == null) return false;
        heldMonitor = new WeakReference<>(monitor);
        heldRelease = release;
        HookStatus.bound(StoryDownloads.HOOK_FAMILY, "story kept paused for the picker");
        return true;
    }

    /** The checklist closed, however it closed: the release it held back goes through now. */
    static void closed() {
        open = false;
        Object monitor = heldMonitor.get();
        String release = heldRelease;
        heldMonitor = new WeakReference<>(null);
        heldRelease = null;
        if (monitor == null || release == null) return;
        try {
            monitor.getClass().getMethod(release).invoke(monitor);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Could not play the story on after the picker", failure);
        }
    }

    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.SAVE_STORY_PICK.get();
    }

    /** The person's loaded stories, the one on screen and how many they have in all. */
    static final class Stories {
        static final Stories NONE = new Stories(Collections.emptyList(), -1, 0);

        final List<Object> items;
        /** Where the story the save was for sits in {@link #items}, or -1. */
        final int current;
        /** How many stories TikTok says the person has, at least {@link #items}' size. */
        final int total;

        Stories(List<Object> items, int current, int total) {
            this.items = items;
            this.current = current;
            this.total = Math.max(total, items.size());
        }
    }

    /**
     * The stories of the person whose story this is: from the story's own list when it carries
     * one, else from whichever bound model's list holds a story with its id. NONE when no list
     * holds it.
     */
    static Stories find(Object story, Collection<Object> bound) {
        String id = Reflect.string(story, "getAid", "aid");
        if (id == null) return Stories.NONE;
        List<Object> holders = new ArrayList<>();
        holders.add(story);
        if (bound != null) holders.addAll(bound);
        for (Object holder : holders) {
            Object userStory = Reflect.invoke(holder, "getUserStory");
            List<Object> items = storiesIn(userStory);
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(Reflect.string(items.get(i), "getAid", "aid"))) {
                    Object total = Reflect.invoke(userStory, "getTotalCount");
                    return new Stories(items, i, total instanceof Number ? ((Number) total).intValue() : 0);
                }
            }
        }
        return Stories.NONE;
    }

    /** The list's stories that have an id, in order. TikTok can add to its list later; this is a copy. */
    static List<Object> storiesIn(Object userStory) {
        Object list = Reflect.invoke(userStory, "getStories");
        if (!(list instanceof List<?>)) return Collections.emptyList();
        List<Object> items = new ArrayList<>();
        for (Object item : new ArrayList<>((List<?>) list)) {
            if (item != null && Reflect.string(item, "getAid", "aid") != null) items.add(item);
        }
        return items;
    }

    /**
     * Opens the checklist in place of the save when the switch is on and the person has more
     * than one story loaded. False leaves the save to go ahead as it was.
     */
    static boolean offer(Context context, Object story) {
        if (!enabled() || context == null || story == null) return false;
        try {
            Stories stories = find(story, StoryDownloads.boundModels());
            if (stories.items.size() < 2) return false;
            Activity activity = activityOf(context);
            if (activity == null) activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) return false;
            if (SettingsUi.dialogOpen(DIALOG_KEY, activity)) return true;
            show(activity, stories);
            return true;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not open the story picker", failure);
            return false;
        }
    }

    /** Each row's text: its place in the viewer, how long ago it went up, and what it is. */
    static String[] labels(Context context, Stories stories, long now) {
        String[] labels = new String[stories.items.size()];
        for (int i = 0; i < labels.length; i++) {
            Object item = stories.items.get(i);
            StringBuilder label = new StringBuilder(L10n.f(context, "Story %1$s", String.valueOf(i + 1)));
            Object created = Reflect.invoke(item, "getCreateTime");
            if (created instanceof Number && ((Number) created).longValue() > 0) {
                long millis = ((Number) created).longValue() * 1000L;
                label.append(" · ").append(DateUtils.getRelativeTimeSpanString(Math.min(millis, now), now,
                        DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            }
            if (Reflect.property(item, "getPhotoModeImageInfo", "photoModeImageInfo") != null) {
                label.append(" · ").append(L10n.t(context, "Photos"));
            }
            if (i == stories.current) label.append(" · ").append(L10n.t(context, "On screen"));
            labels[i] = label.toString();
        }
        return labels;
    }

    /** The checklist's title, with how many stories are still to load when some are. */
    static String title(Context context, Stories stories) {
        String title = L10n.t(context, "Pick stories to save");
        if (stories.total <= stories.items.size()) return title;
        return title + "\n" + L10n.f(context, "%1$s of %2$s stories are loaded. Watch further to load the rest.",
                String.valueOf(stories.items.size()), String.valueOf(stories.total));
    }

    /** The checked stories, in the order the viewer plays them. */
    static List<Object> picked(Stories stories, boolean[] checked) {
        List<Object> picked = new ArrayList<>();
        for (int i = 0; i < checked.length && i < stories.items.size(); i++) {
            if (checked[i]) picked.add(stories.items.get(i));
        }
        return picked;
    }

    /** Saves the picks: one as a single save does, more as one run. */
    static void save(Context context, List<Object> picked) {
        if (picked.isEmpty()) return;
        if (picked.size() == 1) {
            StoryDownloads.saveSingle(context, picked.get(0));
            return;
        }
        BatchSave.startStories(context, picked);
    }

    private static void show(Activity activity, Stories stories) {
        boolean[] checked = new boolean[stories.items.size()];
        if (stories.current >= 0) checked[stories.current] = true;
        SettingsUi.syncDarkMode(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title(activity, stories))
                .setMultiChoiceItems(labels(activity, stories, System.currentTimeMillis()), checked,
                        (ignored, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(L10n.t(activity, "Save"), null)
                .setNegativeButton(L10n.t(activity, "Cancel"), null)
                .setNeutralButton(L10n.t(activity, "Pick all"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            SettingsUi.styleStandardAlertDialog(dialog);
            // Both stay open on a tap: Pick all leaves the picks to look over, and Save with
            // nothing checked has nothing to do yet.
            Button all = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (all != null) all.setOnClickListener(view -> {
                ListView list = dialog.getListView();
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = true;
                    if (list != null) list.setItemChecked(i, true);
                }
            });
            Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (save != null) save.setOnClickListener(view -> {
                List<Object> picks = picked(stories, checked);
                if (picks.isEmpty()) {
                    Utils.showToastShort(L10n.t(activity, "Pick at least one story"));
                    return;
                }
                dialog.dismiss();
                save(activity, picks);
            });
        });
        dialog.setOnDismissListener(ignored -> closed());
        SettingsUi.dialogOpened(DIALOG_KEY, activity, dialog);
        open = true;
        dialog.show();
    }

    private static Activity activityOf(Context context) {
        for (int depth = 0; depth < 8 && context != null; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return null;
    }
}
