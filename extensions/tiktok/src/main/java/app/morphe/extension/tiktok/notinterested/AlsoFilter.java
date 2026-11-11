/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.notinterested;

import android.app.Activity;
import android.app.AlertDialog;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.feedfilter.AdvancedFeedRules;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;
import app.morphe.extension.tiktok.feedfilter.KeywordRules;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Also filter it: after a Not interested tap, offers the video's creator, its sound and up to three
 * of its hashtags for the feed filter's own lists.
 *
 * <p>TikTok lets a topic back within minutes of the last Not interested (the 2026 Northeastern
 * sock-puppet audit). A rule on one of these lists holds until the reader takes it off. The
 * feedback is sent either way, so skipping the chooser costs nothing.
 */
public final class AlsoFilter {
    static final int MAX_HASHTAGS = 3;

    /**
     * A hashtag the way a caption carries it: a # with letters, marks, digits or underscores after
     * it, and nothing word-like right before it, so "C#" and "issue#12" aren't read as tags.
     */
    private static final Pattern HASHTAG =
            Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}_#])#[\\p{L}\\p{M}\\p{N}_]+");

    enum Kind { CREATOR, SOUND_ID, SOUND_NAME, HASHTAG }

    static final class Choice {
        final Kind kind;
        /** What goes on the list: a handle or user id, a sound id or name, or a #tag. */
        final String entry;
        /** How the choice is named on screen. */
        final String shown;

        Choice(Kind kind, String entry, String shown) {
            this.kind = kind;
            this.entry = entry;
            this.shown = shown;
        }
    }

    private AlsoFilter() {}

    public static boolean enabled() {
        return SettingsStatus.notInterestedEnabled && SettingsStatus.feedFilterEnabled
                && Settings.NOT_INTERESTED_ALSO_FILTER.get();
    }

    /** Called after the Not interested button sent its feedback. Main thread. */
    public static void offer(Activity activity) {
        try {
            if (!enabled() || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            Object video = CurrentVideoAuthor.getAweme();
            if (video == null) return;
            Object author = Reflect.property(video, "getAuthor", "author");
            Object music = Reflect.property(video, "getMusic", "music");
            List<Choice> offered = new ArrayList<>();
            for (Choice choice : choices(
                    Reflect.string(author, "getUniqueId", "uniqueId"),
                    Reflect.firstNonBlank(Reflect.string(author, "getUid", "uid"),
                            Reflect.string(video, "getAuthorUid", "authorUid")),
                    music == null ? null : SoundIdentity.idOf(music),
                    music == null ? null : SoundIdentity.nameOf(music),
                    Reflect.string(video, "getDesc", "desc"),
                    Settings.HIDE_BLOCKED_SOUNDS.get())) {
                if (!listed(choice, setting(choice.kind).get())) offered.add(choice);
            }
            if (offered.isEmpty()) return;

            SettingsUi.syncDarkMode(activity);
            String[] labels = new String[offered.size()];
            for (int index = 0; index < labels.length; index++) labels[index] = label(offered.get(index));
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Also filter it"))
                    .setItems(labels, (shown, index) -> add(offered.get(index)))
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not offer the Not interested filters", failure);
        }
    }

    /**
     * What one video offers, in the chooser's order: the creator, the sound when blocked sounds
     * are hidden, then the first three different hashtags of the caption.
     */
    static List<Choice> choices(String handle, String uid, String soundId, String soundName,
            String caption, boolean soundsHidden) {
        List<Choice> choices = new ArrayList<>();
        if (!blank(handle)) {
            String trimmed = handle.trim();
            String named = trimmed.startsWith("@") ? trimmed : "@" + trimmed;
            choices.add(new Choice(Kind.CREATOR, named, named));
        } else if (!blank(uid)) {
            choices.add(new Choice(Kind.CREATOR, uid.trim(), uid.trim()));
        }
        if (soundsHidden) {
            if (!blank(soundId)) {
                choices.add(new Choice(Kind.SOUND_ID, soundId.trim(),
                        blank(soundName) ? soundId.trim() : soundName.trim()));
            } else if (!blank(soundName)) {
                choices.add(new Choice(Kind.SOUND_NAME, soundName.trim(), soundName.trim()));
            }
        }
        for (String tag : hashtags(caption)) choices.add(new Choice(Kind.HASHTAG, tag, tag));
        return choices;
    }

    /** The first {@link #MAX_HASHTAGS} different hashtags in a caption, as written. */
    static List<String> hashtags(String caption) {
        List<String> tags = new ArrayList<>();
        if (caption == null) return tags;
        Set<String> seen = new HashSet<>();
        Matcher matcher = HASHTAG.matcher(caption);
        while (tags.size() < MAX_HASHTAGS && matcher.find()) {
            String tag = matcher.group();
            if (seen.add(tag.toLowerCase(Locale.ROOT))) tags.add(tag);
        }
        return tags;
    }

    /** Whether the list a choice belongs on already holds it. */
    static boolean listed(Choice choice, String stored) {
        switch (choice.kind) {
            case CREATOR:
                return AdvancedFeedRules.hasCreatorEntry(stored, choice.entry);
            case SOUND_ID:
            case SOUND_NAME:
                return SoundIdentity.containsEntry(stored, choice.entry);
            default:
                for (String entry : KeywordRules.split(stored)) {
                    if (entry.equalsIgnoreCase(choice.entry)) return true;
                }
                return false;
        }
    }

    /** The list with the choice added, in the format its editor writes. */
    static String withChoice(Choice choice, String stored) {
        switch (choice.kind) {
            case CREATOR:
                return AdvancedFeedRules.addCreatorEntry(stored, choice.entry);
            case SOUND_ID:
            case SOUND_NAME:
                return SoundIdentity.withEntry(stored, choice.entry);
            default:
                if (listed(choice, stored)) return stored == null ? "" : stored;
                String base = stored == null ? "" : stored.trim();
                return base.isEmpty() ? choice.entry : base + ", " + choice.entry;
        }
    }

    private static StringSetting setting(Kind kind) {
        switch (kind) {
            case CREATOR: return Settings.BLOCKED_CREATORS;
            case SOUND_ID: return Settings.BLOCKED_SOUND_IDS;
            case SOUND_NAME: return Settings.BLOCKED_SOUND_NAMES;
            default: return Settings.BLOCKED_CAPTION_WORDS;
        }
    }

    private static String label(Choice choice) {
        String shown = L10n.isolate(choice.shown);
        switch (choice.kind) {
            case CREATOR: return L10n.f("Videos from %1$s", shown);
            case SOUND_ID:
            case SOUND_NAME: return L10n.f("Videos with the sound %1$s", shown);
            default: return L10n.f("Videos tagged %1$s", shown);
        }
    }

    private static void add(Choice choice) {
        StringSetting setting = setting(choice.kind);
        String before = setting.get();
        String after = withChoice(choice, before);
        String problem = choice.kind == Kind.CREATOR ? FeedRuleLimits.creatorProblem(after)
                : choice.kind == Kind.HASHTAG ? FeedRuleLimits.captionProblem(after) : null;
        if (problem != null) {
            // The editor's own refusal names the limit, but it runs past a toast's two lines.
            Utils.showToastLong(L10n.t("That list is full"));
            return;
        }
        if (!BlockAuthorOverlay.saveAction(setting, after)) return;
        String shown = L10n.isolate(choice.shown);
        String done = choice.kind == Kind.CREATOR ? L10n.f("Skipping videos from %1$s", shown)
                : choice.kind == Kind.HASHTAG ? L10n.f("Skipping videos tagged %1$s", shown)
                : L10n.f("Skipping videos with %1$s", shown);
        BlockAuthorOverlay.showUndoBanner(done, () -> {
            if (BlockAuthorOverlay.saveAction(setting, before)) {
                Utils.showToastShort(L10n.f("Showing %1$s again", shown));
            }
        });
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
