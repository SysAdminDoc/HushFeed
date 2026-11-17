/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import java.util.List;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Whether TikTok sees a post's AI dub tracks. TikTok plays a dub when the post carries a track in
 * the app's language (mini dramas and other videos it dubs automatically), and every reader of
 * those tracks goes through Audio.getTtsInfos. With the switch on that answers null, the state of
 * a post TikTok never dubbed, so the original audio plays and no dub label shows. Read as each
 * video is prepared, so the switch needs no restart. Paused, the switch answers off.
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
        return on() ? null : tracks;
    }
}
