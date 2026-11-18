/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Whether TikTok sees a post's AI dubs. A dub reaches a post two ways (mini dramas and other videos
 * TikTok dubs automatically): a track in the app's language, read through Audio.getTtsInfos, or a
 * whole dubbed copy of the video, read through Aweme.getTranslatedVideo. With the switch on both
 * answer null, the state of a post TikTok never dubbed, so the post's own video and sound play, a
 * save keeps them too, and no dub label shows. Read as each video is prepared, so the switch needs
 * no restart. Paused, the switch answers off.
 */
@SuppressWarnings({"unused", "rawtypes"})
public final class OriginalAudio {
    private OriginalAudio() {
    }

    /** Whether dubs are hidden right now; public so the audit probe can call it. */
    public static boolean on() {
        return SettingsStatus.originalAudioEnabled && Settings.ORIGINAL_AUDIO.get();
    }

    /** TikTok reads a post's AI dub tracks here; null is a post with none. */
    public static List dubTracks(List tracks) {
        if (tracks == null || tracks.isEmpty() || !on()) return tracks;
        Logger.printDebug(() -> "Play original audio: left out " + tracks.size() + " dub tracks");
        return null;
    }

    /** TikTok reads a post's dubbed copy here; null is a post with none (#463). */
    public static Object translatedVideo(Object translated) {
        if (translated == null || !on()) return translated;
        Logger.printDebug(() -> "Play original audio: left out a dubbed copy of the video");
        return null;
    }
}
