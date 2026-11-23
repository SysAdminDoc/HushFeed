/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.captions;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.CaptionCues;
import app.morphe.extension.tiktok.feedfilter.KeywordRules;
import app.morphe.extension.tiktok.interaction.FeedSeek;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Skip promo lines (#493): skips the part of a video where a caption line holds a word from the
 * Promo words list, the way SponsorBlock skips a sponsor read, and says so with an Undo.
 *
 * <p>TikTok's progress callback runs several times a second with the video's id, where it is and
 * how long it is. On the first tick of a video this fetches that video's own caption track, the
 * one the SRT download saves, on a background thread, and keeps the stretches whose lines match a
 * rule. Lines that touch or nearly touch are one stretch, so a plug read over three lines is one
 * skip. When a tick lands inside a stretch the player moves to its end. Undo moves back to its
 * start and lets that stretch play. A video with no captions, or none that match, plays as usual,
 * and so does everything while the switch is off, the list is empty or Hushfeed is paused.
 */
@SuppressWarnings("unused")
public final class PromoSkip {
    private static final String HOOK_FAMILY = "promo skip";
    /** Lines this close together are one stretch. */
    static final long JOIN_GAP_MS = 400;
    /** How long a skip waits for its own seek to land before the same stretch can skip again. */
    static final long SETTLE_MS = 2000;
    /** Ticks to wait for the playing video's data to show up before giving up on a video. */
    static final int LOOKUP_TICKS = 40;
    private static final int REMEMBERED = 32;

    /** A stretch of a video to skip, in milliseconds. */
    static final class Span {
        final long startMs;
        final long endMs;
        final String rule;

        Span(long startMs, long endMs, String rule) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.rule = rule;
        }
    }

    /** Where a video's caption lines come from. The default fetches the playing video's own. */
    interface CueSource {
        /** Null while the video's data isn't there yet, so a later tick asks again. */
        List<CaptionCues.Cue> cues(String videoId) throws Exception;
    }

    interface Banner {
        void show(String message, Runnable undo);
    }

    interface Seeker {
        boolean seek(Object controller, long targetMs, long lengthMs);
    }

    private static final class Video {
        final String id;
        /** The list the stretches were found with. A changed list looks again. */
        final String words;
        volatile List<Span> spans;
        volatile boolean loading;
        int lookups;
        final List<Span> allowed = Collections.synchronizedList(new ArrayList<>());
        volatile Span lastSkip;
        volatile long lastSkipAt;

        Video(String id, String words) {
            this.id = id;
            this.words = words;
        }

        String key() {
            return id + '\n' + words;
        }
    }

    private static final Map<String, List<Span>> SPANS = new LinkedHashMap<String, List<Span>>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Span>> eldest) {
            return size() > REMEMBERED;
        }
    };

    static volatile CueSource source = PromoSkip::playingVideoCues;
    static volatile Banner banner = BlockAuthorOverlay::showUndoBanner;
    static volatile Seeker seeker = FeedSeek::seekTo;
    private static volatile Video current;

    private PromoSkip() {
    }

    /**
     * {@code PlayerController.onPlayProgressChange(sourceId, position, duration)}, before TikTok's
     * own handling. Never throws into TikTok's player.
     */
    public static void onProgress(Object controller, String sourceId, long position, long duration) {
        try {
            if (controller == null || sourceId == null || sourceId.isEmpty() || duration <= 0) return;
            if (!active()) return;
            String words = Settings.SKIP_PROMO_WORDS.get();
            Video video = current;
            if (video == null || !video.id.equals(sourceId) || !video.words.equals(words)) {
                video = new Video(sourceId, words);
                synchronized (SPANS) {
                    video.spans = SPANS.get(video.key());
                }
                current = video;
            }
            List<Span> spans = video.spans;
            if (spans == null) {
                load(video);
                return;
            }
            Span span = spanAt(spans, position);
            if (span == null || video.allowed.contains(span)) return;
            long now = SystemClock.uptimeMillis();
            if (span == video.lastSkip && now - video.lastSkipAt < SETTLE_MS) return;
            video.lastSkip = span;
            video.lastSkipAt = now;
            skip(controller, video, span, duration);
        } catch (Exception ex) {
            Logger.printException(() -> "[PromoSkip] progress tick", ex);
        }
    }

    private static boolean active() {
        if (Utils.getContext() == null || !Settings.SKIP_PROMO_LINES.get()) return false;
        String words = Settings.SKIP_PROMO_WORDS.get();
        return words != null && !words.trim().isEmpty();
    }

    private static void load(Video video) {
        if (video.loading || video.lookups >= LOOKUP_TICKS) return;
        video.lookups++;
        video.loading = true;
        boolean started = Utils.runOnBackgroundThread(() -> {
            try {
                List<CaptionCues.Cue> cues = source.cues(video.id);
                if (cues == null) return;
                List<Span> found = spans(cues, KeywordRules.parse(video.words));
                synchronized (SPANS) {
                    SPANS.put(video.key(), found);
                }
                video.spans = found;
                if (!found.isEmpty()) {
                    HookStatus.bound(HOOK_FAMILY, "found promo lines");
                    Logger.printDebug(() -> "[PromoSkip] " + found.size() + " stretch(es) to skip in " + video.id);
                }
            } catch (Exception ex) {
                // A video whose captions can't be read plays as usual.
                Logger.printDebug(() -> "[PromoSkip] no captions read for " + video.id, ex);
                video.spans = Collections.emptyList();
            } finally {
                video.loading = false;
            }
        });
        if (!started) video.loading = false;
    }

    private static void skip(Object controller, Video video, Span span, long duration) {
        Utils.runOnMainThreadNowOrLater(() -> {
            if (current != video) return;
            if (!seeker.seek(controller, span.endMs, duration)) return;
            HookStatus.bound(HOOK_FAMILY, "skipped a promo line");
            banner.show(L10n.t("Skipped a promo line"), () -> {
                video.allowed.add(span);
                if (current == video) seeker.seek(controller, span.startMs, duration);
            });
        });
    }

    /** The playing video's caption lines, or null while the player is ahead of the feed's data. */
    private static List<CaptionCues.Cue> playingVideoCues(String videoId) throws Exception {
        Object aweme = CurrentVideoAuthor.getAweme();
        if (aweme == null || !videoId.equals(Reflect.string(aweme, "getAid", "aid"))) return null;
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video == null) return Collections.emptyList();
        return CaptionCues.fetch(video);
    }

    /** The stretches to skip: matching lines, with lines that nearly touch joined into one. */
    static List<Span> spans(List<CaptionCues.Cue> cues, List<KeywordRules.Rule> rules) {
        List<Span> found = new ArrayList<>();
        if (cues == null || rules == null || rules.isEmpty()) return found;
        List<CaptionCues.Cue> sorted = new ArrayList<>(cues);
        Collections.sort(sorted, (a, b) -> Long.compare(a.startMs, b.startMs));
        Span open = null;
        for (CaptionCues.Cue cue : sorted) {
            KeywordRules.Rule rule = KeywordRules.firstMatch(rules, cue.text.replace('\n', ' '));
            if (rule == null) continue;
            if (open != null && cue.startMs <= open.endMs + JOIN_GAP_MS) {
                open = new Span(open.startMs, Math.max(open.endMs, cue.endMs), open.rule);
                found.set(found.size() - 1, open);
            } else {
                open = new Span(cue.startMs, cue.endMs, rule.entry());
                found.add(open);
            }
        }
        return Collections.unmodifiableList(found);
    }

    /** The stretch the position is in, or null. */
    static Span spanAt(List<Span> spans, long positionMs) {
        for (Span span : spans) {
            if (positionMs >= span.startMs && positionMs < span.endMs) return span;
        }
        return null;
    }

    static void resetForTests() {
        current = null;
        synchronized (SPANS) {
            SPANS.clear();
        }
        source = PromoSkip::playingVideoCues;
        banner = BlockAuthorOverlay::showUndoBanner;
        seeker = FeedSeek::seekTo;
    }
}
