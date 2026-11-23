/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * A video's timed caption lines, read the way the SRT download reads them, for Skip promo lines
 * (#493). The track is the video's own (the one TikTok marks original, else the first it sends),
 * so the words are matched in the language the video is spoken in.
 */
public final class CaptionCues {
    /** One caption line and when it shows, in milliseconds from the start of the video. */
    public static final class Cue {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private CaptionCues() {
    }

    /**
     * The lines of {@code video}'s own caption track, fetched from TikTok's caption host. Empty
     * when the video carries no track. Blocks on the network, so never on the main thread.
     */
    public static List<Cue> fetch(Object video) throws IOException {
        List<SubtitleDownloads.Track> tracks = SubtitleDownloads.tracks(video, "original", Locale.ROOT);
        if (tracks.isEmpty()) return Collections.emptyList();
        SubtitleDownloads.Track track = tracks.get(0);
        return parse(SubtitleDownloads.fetch(track.urls, track.format));
    }

    /** The cues of SRT text as {@link SubtitleFormat} writes it. */
    public static List<Cue> parse(String srt) throws IOException {
        List<Cue> cues = new ArrayList<>();
        if (srt == null) return cues;
        String[] lines = srt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            Matcher time = SubtitleFormat.TIMELINE.matcher(lines[index].trim());
            if (!time.matches()) continue;
            StringBuilder text = new StringBuilder();
            while (index + 1 < lines.length && !lines[index + 1].trim().isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(lines[++index]);
            }
            long start = SubtitleFormat.millis(time.group(1));
            long end = SubtitleFormat.millis(time.group(2));
            if (end > start) cues.add(new Cue(start, end, text.toString()));
        }
        return cues;
    }
}
