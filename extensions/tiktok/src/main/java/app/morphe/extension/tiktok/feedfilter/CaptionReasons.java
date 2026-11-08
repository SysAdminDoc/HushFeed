/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts what TikTok's caption data says about each For You video, so an export from an account
 * whose captions never show says whether TikTok sent any and, when it sent none, the reason it
 * gave (#217). The data rides in the video itself: {@code CaptionModel.captionList} holds the
 * tracks and {@code noOriginalCaptionReasonV2} the server's reason for an empty list, read as
 * TikTok 47.7.3's own {@code CaptionModel$NoCaptionReasonEnum} values. Only the reason's name is
 * counted, never a caption's text or language.
 */
final class CaptionReasons {
    /** The export's line for the caption data the For You videos came with. */
    static final String SOURCE = "Captions";
    static final String TRACKS = "tracks";
    static final String NO_MODEL = "noCaptionData";
    private static final String FAMILY = "caption reasons";
    /** TikTok's NoCaptionReasonEnum, by value: NO_OP 0 through BEING_GENERATED 5. */
    private static final String[] REASONS = {
            "noOp", "other", "notAuthorized", "speechUnrecognized", "langNotSupported", "beingGenerated"};
    /** How many recent video ids are kept, so a page TikTok reads twice counts its videos once. */
    private static final int REMEMBERED = 256;
    private static final Map<String, Boolean> COUNTED = new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    };

    private CaptionReasons() {}

    /** Counts each video of a For You page once, whatever the filter switches say. */
    static void count(List<?> list, FeedItemsFilter.AwemeExtractor extractor) {
        if (list == null || list.isEmpty()) return;
        try {
            int fresh = 0;
            for (Object container : list.toArray()) {
                Aweme item = extractor.extract(container);
                if (item == null || !firstTime(item.getAid())) continue;
                fresh++;
                FeedFilterCounters.sawKind(SOURCE, kind(item));
            }
            if (fresh > 0) FeedFilterCounters.sawList(SOURCE, fresh);
        } catch (RuntimeException error) {
            HookStatus.threw(FAMILY, "count caption reasons", error);
        }
    }

    /** "tracks", "none:" plus TikTok's reason for sending none, or "noCaptionData". */
    static String kind(Aweme item) {
        Object video = Reflect.property(item, "getVideo", "video");
        Object model = Reflect.property(video, "getCaptionModel", "captionModel");
        if (model == null) return NO_MODEL;
        Object tracks = Reflect.readField(model, "captionList");
        if (tracks instanceof List<?> && !((List<?>) tracks).isEmpty()) return TRACKS;
        return "none:" + reasonName(Reflect.readField(model, "noOriginalCaptionReasonV2"));
    }

    static String reasonName(Object reason) {
        if (!(reason instanceof Integer)) return "unset";
        int value = (Integer) reason;
        return value >= 0 && value < REASONS.length ? REASONS[value] : "code" + value;
    }

    private static boolean firstTime(String aid) {
        if (aid == null || aid.isEmpty()) return true;
        synchronized (COUNTED) {
            return COUNTED.put(aid, Boolean.TRUE) == null;
        }
    }

    static void resetForTests() {
        synchronized (COUNTED) {
            COUNTED.clear();
        }
    }
}
