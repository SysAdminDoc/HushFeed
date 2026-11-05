/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.LiveRecorder;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds what to record in a LIVE room the viewer is in: the stream address TikTok already gave
 * the room, FLV first because it is one continuous download, HLS when there is no FLV. Nothing is
 * fetched here and nothing before the viewer taps Record is looked at beyond the room's own fields.
 */
public final class LiveRoomStream {
    private LiveRoomStream() {
    }

    /** What was found: a target to record, or the reason there isn't one. */
    public static final class Pick {
        public final LiveRecorder.Target target;
        public final String problem;
        /** The stream keys the room offered, in the order read, for the diagnostics line; empty when none were read. */
        public final String offered;

        Pick(LiveRecorder.Target target, String problem) {
            this(target, problem, "");
        }

        Pick(LiveRecorder.Target target, String problem, String offered) {
            this.target = target;
            this.problem = problem;
            this.offered = offered;
        }
    }

    /** Best first. "ao" is audio only and is never chosen. */
    private static final String[] QUALITY_ORDER = {"origin", "uhd", "hd", "sd", "ld", "md"};

    /** A {@code preferred} that asks for the smallest stream the room has rather than one quality. */
    static final String LOWEST = "lowest";

    /** A {@code preferred} for one of the room's own presets: "~hd" takes hd, else the nearest one below, else the nearest above. */
    private static final String NEAREST = "~";

    /** The room's own preset keys a recording can ask for, best first. */
    static final String[] PRESETS = {"origin", "uhd", "hd", "sd", "ld"};

    static boolean isPreset(String choice) {
        for (String preset : PRESETS) {
            if (preset.equals(choice)) return true;
        }
        return false;
    }

    private static int rank(String quality) {
        String base = base(quality);
        for (int i = 0; i < QUALITY_ORDER.length; i++) {
            if (QUALITY_ORDER[i].equals(base)) return i;
        }
        return QUALITY_ORDER.length;
    }

    /**
     * The quality a key names without the frame rate TikTok puts on some: "hd_60" is hd at 60
     * fps. Read as unknown keys, a room's 60 fps streams lost to everything, so 1080p in a 1080p60
     * room recorded the 720p30 one (S22, 2026-10-24).
     */
    static String base(String quality) {
        int cut = quality == null ? -1 : quality.lastIndexOf('_');
        if (cut <= 0 || cut == quality.length() - 1) return quality;
        for (int i = cut + 1; i < quality.length(); i++) {
            if (!Character.isDigit(quality.charAt(i))) return quality;
        }
        return quality.substring(0, cut);
    }

    /** The frame rate a key names, 0 when it names none. */
    static int frameRate(String quality) {
        String base = base(quality);
        if (base == null || base.length() == quality.length()) return 0;
        try {
            return Integer.parseInt(quality.substring(base.length() + 1));
        } catch (NumberFormatException tooLong) {
            return 0;
        }
    }

    /**
     * The {@code preferred} a recording passes for the LIVE recording quality choice (#203):
     * "highest" asks for the best there is, "lowest" for the smallest, and anything else for the
     * quality being watched, {@code watching}, which can be null. One of the room's own preset
     * keys (origin, uhd, hd, sd, ld) asks for that stream, or the nearest one when the room lacks it.
     */
    public static String forRecording(String choice, String watching) {
        if ("highest".equals(choice)) return null;
        if (isPreset(choice)) return NEAREST + choice;
        if (LOWEST.equals(choice)) return LOWEST;
        return watching;
    }

    private static boolean https(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8);
    }

    private static String clean(Object value) {
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return https(text) ? text : null;
    }

    /**
     * The address and quality to record. {@code preferred} is the quality the viewer is watching
     * when known (TikTok's key such as "hd"); a stream without it falls to the best there is.
     * {@link #LOWEST} takes the smallest one instead. Any argument can be null.
     */
    public static Pick choose(String streamDataJson, Map<String, String> flvMap, Map<String, String> hlsMap,
                              String hlsUrl, String preferred, String creator, String roomId) {
        String[] best = new String[2]; // [quality, url] for flv
        String[] bestHls = new String[2];
        Set<String> offered = new LinkedHashSet<>();
        if (streamDataJson != null && !streamDataJson.isEmpty()) {
            try {
                JSONObject root = new JSONObject(streamDataJson);
                JSONObject data = root.optJSONObject("data");
                if (data != null) {
                    Iterator<String> keys = data.keys();
                    while (keys.hasNext()) {
                        String quality = keys.next();
                        JSONObject main = null;
                        JSONObject entry = data.optJSONObject(quality);
                        if (entry != null) main = entry.optJSONObject("main");
                        if (main == null) continue;
                        consider(best, quality, clean(main.optString("flv", null)), preferred, offered);
                        consider(bestHls, quality, clean(main.optString("hls", null)), preferred, offered);
                    }
                }
            } catch (Throwable ignored) {
                // The maps below are the other source.
            }
        }
        if (flvMap != null) {
            for (Map.Entry<String, String> entry : flvMap.entrySet()) {
                consider(best, entry.getKey(), clean(entry.getValue()), preferred, offered);
            }
        }
        if (hlsMap != null) {
            for (Map.Entry<String, String> entry : hlsMap.entrySet()) {
                consider(bestHls, entry.getKey(), clean(entry.getValue()), preferred, offered);
            }
        }
        String keys = join(offered);
        if (best[1] != null) {
            return new Pick(new LiveRecorder.Target(best[1], LiveRecorder.Transport.FLV, creator, roomId, best[0]), null, keys);
        }
        if (bestHls[1] != null) {
            return new Pick(new LiveRecorder.Target(bestHls[1], LiveRecorder.Transport.HLS, creator, roomId, bestHls[0]), null, keys);
        }
        String single = clean(hlsUrl);
        if (single != null) {
            return new Pick(new LiveRecorder.Target(single, LiveRecorder.Transport.HLS, creator, roomId, null), null, keys);
        }
        return new Pick(null, "No stream address", keys);
    }

    private static String join(Set<String> keys) {
        StringBuilder joined = new StringBuilder();
        for (String key : keys) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(key);
        }
        return joined.toString();
    }

    /**
     * Keeps the better of the current {@code best} and this one: the preferred quality wins, then
     * rank, then the higher frame rate (the lower for {@link #LOWEST}). Notes each key with an
     * address in {@code offered}.
     */
    private static void consider(String[] best, String quality, String url, String preferred, Set<String> offered) {
        if (url == null || quality == null || "ao".equals(quality)) return;
        offered.add(quality);
        if (best[1] == null || better(quality, best[0], preferred)) {
            best[0] = quality;
            best[1] = url;
        }
    }

    /** How far a stream is from the asked preset: 0 is it, then the ones below nearest first, then the ones above. */
    private static int distance(String quality, String preset) {
        int asked = rank(preset);
        int rank = rank(quality);
        if (rank == QUALITY_ORDER.length) return 1000;
        return rank >= asked ? rank - asked : 100 + (asked - rank);
    }

    private static boolean better(String candidate, String current, String preferred) {
        boolean faster = frameRate(candidate) > frameRate(current);
        if (preferred != null && preferred.startsWith(NEAREST)) {
            String preset = preferred.substring(NEAREST.length());
            int candidateDistance = distance(candidate, preset);
            int currentDistance = distance(current, preset);
            return candidateDistance != currentDistance ? candidateDistance < currentDistance : faster;
        }
        if (LOWEST.equals(preferred)) {
            // A key not in the order counts as the best, so a known small stream wins over it.
            int candidateLowness = lowness(candidate);
            int currentLowness = lowness(current);
            return candidateLowness != currentLowness
                    ? candidateLowness > currentLowness
                    : frameRate(candidate) < frameRate(current);
        }
        if (preferred != null) {
            boolean candidateIsIt = preferred.equals(candidate);
            boolean currentIsIt = preferred.equals(current);
            if (candidateIsIt != currentIsIt) return candidateIsIt;
        }
        int candidateRank = rank(candidate);
        int currentRank = rank(current);
        return candidateRank != currentRank ? candidateRank < currentRank : faster;
    }

    private static int lowness(String quality) {
        int rank = rank(quality);
        return rank == QUALITY_ORDER.length ? -1 : rank;
    }

    /** What to record when the screen is the source (#37): the room's creator and id, no stream address. */
    public static Pick screenTarget(Object room) {
        if (room == null) return new Pick(null, "No room");
        try {
            Object owner = Reflect.readField(room, "owner");
            String creator = owner == null ? null : Reflect.string(owner, "getUsername", "username");
            Object id = Reflect.readField(room, "idStr");
            return new Pick(new LiveRecorder.Target(null, LiveRecorder.Transport.SCREEN, creator,
                    id instanceof String ? (String) id : null, "screen"), null);
        } catch (Throwable failure) {
            return new Pick(null, "Unreadable room");
        }
    }

    /**
     * The room's pull stream data, the JSON whose {@code data} object holds one entry per quality
     * ("origin", "hd", "ao" and so on), or null. Never throws.
     */
    static String streamData(Object room) {
        if (room == null) return null;
        try {
            return streamData(room, Reflect.readField(room, "streamUrl"));
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String streamData(Object room, Object streamUrl) {
        Object core = streamUrl == null ? null : Reflect.readField(streamUrl, "liveCoreSDKData");
        Object pull = core == null ? null : Reflect.readField(core, "pullData");
        Object streamData = pull == null ? null : Reflect.readField(pull, "streamData");
        if (streamData instanceof String) return (String) streamData;
        Object fallback = Reflect.readField(room, "pullStreamData");
        return fallback instanceof String ? (String) fallback : null;
    }

    /** Reads the room off TikTok's room object. Never throws. */
    @SuppressWarnings("unchecked")
    public static Pick fromRoom(Object room, String preferred) {
        if (room == null) return new Pick(null, "No room");
        try {
            Object streamUrl = Reflect.readField(room, "streamUrl");
            if (streamUrl == null) return new Pick(null, "No stream");
            String json = streamData(room, streamUrl);
            Object flv = Reflect.readField(streamUrl, "flvPullUrl");
            Object hls = Reflect.readField(streamUrl, "hlsPullUrlMap");
            Object hlsOne = Reflect.readField(streamUrl, "hlsPullUrl");
            Object owner = Reflect.readField(room, "owner");
            String creator = owner == null ? null : Reflect.string(owner, "getUsername", "username");
            Object id = Reflect.readField(room, "idStr");
            String roomId = id instanceof String ? (String) id : null;
            return choose(json,
                    flv instanceof Map ? (Map<String, String>) flv : null,
                    hls instanceof Map ? (Map<String, String>) hls : null,
                    hlsOne instanceof String ? (String) hlsOne : null,
                    preferred, creator, roomId);
        } catch (Throwable failure) {
            return new Pick(null, "Unreadable room");
        }
    }
}
