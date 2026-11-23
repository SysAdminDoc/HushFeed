/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * TikTok Lite's author row (#502): the date after the creator's name, then the country the video
 * was posted from. Always show publish date and Show author region share the row's one hook, so
 * a build with either installs it and each switch adds its own part.
 *
 * <p>Lite writes the date only where its own checks let it, and leaves it out on For You. Each
 * of those checks comes through {@link #show}, then the text through {@link #text}, all on the
 * main thread while the row binds.
 */
public final class LiteAuthorRow {
    /** The video one of Lite's checks left the date off, until its text comes through. */
    private static Object dateLeftOff;

    private LiteAuthorRow() {
    }

    private static boolean dateWanted() {
        return SettingsStatus.alwaysShowPublishDateEnabled && Settings.ALWAYS_SHOW_PUBLISH_DATE.get();
    }

    /** The country as the row shows it (a flag or two letters), or null for none. */
    static String country(Object video) {
        if (!SettingsStatus.authorRegionEnabled || !Settings.SHOW_AUTHOR_REGION.get()) {
            return null;
        }
        return AuthorRegion.shown(AuthorRegion.countryCode(LiteVideoRegion.region(video)));
    }

    /**
     * One of Lite's checks for the row's date (non-zero lets it show). The row goes on to its
     * text whenever the date or the country is wanted, and remembers when Lite would have left
     * the date out, so only the country shows then.
     */
    public static boolean show(int original, Object video) {
        if (original != 0) {
            return true;
        }
        dateLeftOff = video;
        return dateWanted() || country(video) != null;
    }

    /** The row's text past Lite's own empty check: the date, the country, or both. */
    public static String text(String date, Object video) {
        boolean liteShowsDate = dateLeftOff != video || video == null;
        dateLeftOff = null;
        String shown = null;
        if (liteShowsDate || dateWanted()) {
            shown = SettingsStatus.alwaysShowPublishDateEnabled
                    ? AlwaysShowPublishDatePatch.postTime(date, video)
                    : date;
        }
        String country = country(video);
        if (country == null) {
            return shown != null ? shown : date;
        }
        return shown == null || shown.isEmpty() ? country : shown + AuthorRegion.SEPARATOR + country;
    }
}
