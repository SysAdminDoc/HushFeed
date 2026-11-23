/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.captions;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Keep captions available (#217): the Captions switch on TikTok's Settings and privacy >
 * Accessibility page, and the caption controls it belongs to, on accounts TikTok leaves them off for.
 *
 * <p>Two server values decide it. {@code enable_captions_a11y_setting} places the Captions row on
 * the Accessibility page: 0 leaves it out, 1 puts it first and 2 puts it last. {@code
 * fixed_caption_v2_consumer_group} turns on the caption controls the row works with. Without it the
 * row draws nothing even where it's placed, and the player, the share panel's Captions item and the
 * caption toggle read the same answer. While the setting is on, a row left out reads as placed first
 * and the controls read as on, the answers TikTok gives the accounts it shows them to. Any other
 * answer is TikTok's, and so is every answer while the setting is off, while Hushfeed is paused and
 * before the application context exists. TikTok reads both values once per launch, so the setting
 * asks for a restart.
 *
 * <p>Captions still come from the tracks a video carries, auto generated or added by its creator.
 * A video without any shows none either way.
 */
@SuppressWarnings("unused")
public final class CaptionsAvailable {
    private static final String HOOK_FAMILY = "captions available";

    /** TikTok's answer that places the Captions row first on the Accessibility page. */
    static final int ROW_FIRST = 1;

    private CaptionsAvailable() {
    }

    /**
     * Where the Captions row goes, from the server's {@code server}: 0 (left out) becomes {@link
     * #ROW_FIRST} while the setting is on. Called once per launch, when the page first asks.
     */
    public static int captionsRow(int server) {
        if (server != 0) return server;
        try {
            if (!keepAvailable()) return server;
            HookStatus.bound(HOOK_FAMILY, "placed the Captions row");
            return ROW_FIRST;
        } catch (Exception ex) {
            Logger.printDebug(() -> "[CaptionsAvailable] left the Captions row where TikTok put it", ex);
            return server;
        }
    }

    /**
     * Whether the caption controls read as on before TikTok's own answer is asked. False hands the
     * question to TikTok. Called each time a video, the share panel or the Accessibility page asks.
     */
    public static boolean captionControls() {
        try {
            if (!keepAvailable()) return false;
            HookStatus.bound(HOOK_FAMILY, "caption controls on");
            return true;
        } catch (Exception ex) {
            Logger.printDebug(() -> "[CaptionsAvailable] left the caption controls to TikTok", ex);
            return false;
        }
    }

    /**
     * The track TikTok treats as a video's own captions, asked before TikTok's own lookup runs
     * (#217). TikTok shows captions only from the track whose language id matches the video's
     * original caption language, and with none it toasts that the video has no captions, even
     * when the video carries other tracks. While the setting is on, a video whose tracks miss
     * that match gets one of them: the one marked original, then one in the original language's
     * code, then the first with somewhere to load it from. Null hands the question to TikTok,
     * which is the answer whenever TikTok's own lookup finds its track, the video carries none,
     * or the setting is off.
     */
    public static Object originalTrack(Object aweme) {
        try {
            if (aweme == null || !keepAvailable()) return null;
            Object video = Reflect.property(aweme, "getVideo", "video");
            Object model = Reflect.property(video, "getCaptionModel", "captionModel");
            Object tracks = Reflect.readField(model, "captionList");
            if (!(tracks instanceof List<?>) || ((List<?>) tracks).isEmpty()) return null;
            Object pick = fallbackTrack((List<?>) tracks, Reflect.readField(model, "originalCaptionLanguage"));
            if (pick != null) HookStatus.bound(HOOK_FAMILY, "showed a caption track TikTok's lookup missed");
            return pick;
        } catch (Exception ex) {
            Logger.printDebug(() -> "[CaptionsAvailable] left the caption track to TikTok", ex);
            return null;
        }
    }

    /**
     * Whether TikTok's own lookup finds a track: one whose language id is the original caption
     * language's. The export's Captions line asks the same question.
     */
    public static boolean hasOriginalTrack(List<?> tracks, Object language) {
        Object id = language == null ? null : Reflect.readField(language, "languageId");
        if (!(id instanceof Number) || tracks == null) return false;
        for (Object track : tracks) {
            Object own = Reflect.readField(track, "languageId");
            if (own instanceof Number && ((Number) own).longValue() == ((Number) id).longValue()) return true;
        }
        return false;
    }

    /** The track to show when [tracks] has no match for [language]'s id, else null. */
    static Object fallbackTrack(List<?> tracks, Object language) {
        if (hasOriginalTrack(tracks, language)) return null;
        for (Object track : tracks) {
            if (loadable(track) && Boolean.TRUE.equals(Reflect.property(track, "isOriginalCaption", "isOriginalCaption"))) {
                return track;
            }
        }
        String code = language == null ? null : Reflect.string(language, "getLanguageCode", "languageCode");
        if (code != null) {
            for (Object track : tracks) {
                if (loadable(track) && code.equalsIgnoreCase(Reflect.string(track, "getLanguageCode", "languageCode"))) {
                    return track;
                }
            }
        }
        for (Object track : tracks) {
            if (loadable(track)) return track;
        }
        return null;
    }

    private static boolean loadable(Object track) {
        if (track == null) return false;
        if (Reflect.string(track, "getUrl", "url") != null) return true;
        Object mirrors = Reflect.readField(track, "urlList");
        if (!(mirrors instanceof List<?>)) return false;
        for (Object mirror : (List<?>) mirrors) {
            if (mirror instanceof String && !((String) mirror).trim().isEmpty()) return true;
        }
        return false;
    }

    private static boolean keepAvailable() {
        // TikTok can ask while it starts, before Settings can load without throwing.
        return Utils.getContext() != null && Settings.KEEP_CAPTIONS_AVAILABLE.get();
    }
}
