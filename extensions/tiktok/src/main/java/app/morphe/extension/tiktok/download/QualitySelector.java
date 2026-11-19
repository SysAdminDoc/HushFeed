/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.util.Base64;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.base.model.UrlModel;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects only actual video gears, never cover or thumbnail URLs. */
public final class QualitySelector {
    private static final Pattern HEIGHT = Pattern.compile("(?<![0-9])(2160|1440|1080|960|720|640|576|540|480|432|360|288|240)(?![0-9])");
    private QualitySelector() {}

    /**
     * Every rendition of the video as TikTok received it, never the playback getter, which
     * Playback quality may narrow. getRawBitRate returns the backing list whole (Playback quality
     * leaves it alone for exactly this); without it the field is read under both names it has
     * had: bitRate on 46.2.3, bitRateList on 47.0.3. Reading bitRate alone found nothing on
     * 47.0.3, so every chosen quality fell back to TikTok's own save (S22, 2026-09-23).
     */
    static Object rawGears(Object video) {
        if (video == null) return null;
        Object raw = Reflect.invoke(video, "getRawBitRate");
        if (raw == null) raw = Reflect.readField(video, "bitRateList");
        if (raw == null) raw = Reflect.readField(video, "bitRate");
        if (!(raw instanceof List<?>)) {
            List<Object> combined = new ArrayList<>(modelGears(video));
            for (SourceGear source : sources(video)) {
                if (hasUsableAddress(source) && !sameRendition(combined, source)) combined.add(source);
            }
            return combined;
        }

        List<Object> combined = new ArrayList<>((List<?>) raw);
        for (SourceGear model : modelGears(video)) {
            if (!sameRendition(combined, model)) combined.add(model);
        }
        return combined;
    }

    /** Every saved-file candidate: native gears, stored model variants and video-owned sources. */
    static List<Object> allGears(Object video) {
        List<Object> combined = new ArrayList<>();
        Object raw = rawGears(video);
        if (raw instanceof List<?>) combined.addAll((List<?>) raw);
        for (SourceGear source : sources(video)) {
            if (hasUsableAddress(source) && !sameRendition(combined, source)) combined.add(source);
        }
        return combined;
    }

    private static boolean sameRendition(List<?> gears, SourceGear candidate) {
        int candidateHeight = height(candidate);
        long candidateSize = size(candidate);
        for (Object gear : gears) {
            if (height(gear) == candidateHeight && codec(gear) == codec(candidate)) {
                long gearSize = size(gear);
                if (candidateSize == 0 || gearSize == 0 || candidateSize == gearSize) return true;
            }
        }
        return false;
    }

    static List<SourceGear> modelGears(Object video) {
        List<SourceGear> found = new ArrayList<>();
        if (video == null) return found;
        collectModel(new String[]{"getVideoModelStr", "videoModelStr"}, video, found);
        collectModel(new String[]{"getDashVideoModelStr", "dashVideoModelStr"}, video, found);
        return found;
    }

    private static void collectModel(String[] names, Object video, List<SourceGear> found) {
        Object raw = Reflect.property(video, names[0], names[1]);
        if (!(raw instanceof String) || ((String) raw).isEmpty()) return;
        try {
            JSONObject root = new JSONObject((String) raw);
            collectVariantArray(root.optJSONArray("dynamic_video_list"), found);
            JSONObject dynamic = root.optJSONObject("dynamic_video");
            if (dynamic != null) collectVariantArray(dynamic.optJSONArray("dynamic_video_list"), found);
            for (String key : new String[]{"video_list", "play_list", "origin_video"}) {
                collectVariantArray(root.optJSONArray(key), found);
                collectVariantObject(root.optJSONObject(key), found);
            }
        } catch (JSONException ignored) {
            // A model that is not JSON is ignored; native gears remain authoritative.
        }
    }

    private static void collectVariantArray(JSONArray variants, List<SourceGear> found) {
        if (variants == null) return;
        for (int i = 0; i < variants.length(); i++) {
            collectVariantObject(variants.optJSONObject(i), found);
        }
    }

    private static void collectVariantObject(JSONObject variant, List<SourceGear> found) {
        if (variant == null) return;
        List<String> urls = new ArrayList<>();
        for (String key : new String[]{"main_url", "backup_url_1", "backup_url_2", "backup_url_3"}) {
            Object url = variant.opt(key);
            if (url instanceof String && !((String) url).isEmpty()) {
                String decoded = decodeUrl((String) url);
                if (!decoded.isEmpty()) urls.add(decoded);
            }
        }
        JSONObject playAddr = variant.optJSONObject("play_addr");
        if (playAddr != null) {
            JSONArray urlList = playAddr.optJSONArray("url_list");
            if (urlList != null) {
                for (int i = 0; i < urlList.length(); i++) {
                    String url = urlList.optString(i);
                    String decoded = decodeUrl(url);
                    if (!decoded.isEmpty() && !urls.contains(decoded)) urls.add(decoded);
                }
            }
            mergeModelAddress(variant, playAddr, urls, found);
            return;
        }
        mergeModelAddress(variant, null, urls, found);
    }

    private static String decodeUrl(String value) {
        if (value == null || value.isEmpty()) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
        try {
            byte[] decoded = Base64.decode(trimmed, Base64.DEFAULT);
            String url = new String(decoded, java.nio.charset.StandardCharsets.UTF_8).trim();
            return url.startsWith("http://") || url.startsWith("https://") ? url : "";
        } catch (RuntimeException ignored) {
            try {
                byte[] decoded = Base64.decode(trimmed, Base64.URL_SAFE);
                String url = new String(decoded, java.nio.charset.StandardCharsets.UTF_8).trim();
                return url.startsWith("http://") || url.startsWith("https://") ? url : "";
            } catch (RuntimeException ignoredAgain) {
                return "";
            }
        }
    }

    private static void mergeModelAddress(JSONObject variant, JSONObject playAddr,
                                          List<String> urls, List<SourceGear> found) {
        if (urls.isEmpty()) return;
        String name = variant.optString("gear_name");
        if (name.isEmpty()) name = "model_variant";
        int width = valueInt(variant, "vwidth");
        int height = valueInt(variant, "vheight");
        long size = valueLong(variant, "size");
        if (playAddr != null) {
            if (width == 0) width = valueInt(playAddr, "width");
            if (height == 0) height = valueInt(playAddr, "height");
            if (size == 0) size = valueLong(playAddr, "size");
        }
        int codec = name.endsWith("_1") ? 1 : name.endsWith("_2") ? 2 : 0;
        found.add(new SourceGear(name, new ModelAddress(urls, width, height, size), codec));
    }

    private static int valueInt(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long valueLong(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /**
     * The usual height of TikTok's own file: a rendition past it is taller than a file whose
     * height isn't listed (#240).
     */
    static final int TALL_ENOUGH = 720;

    /**
     * One of the video's own play addresses, shaped like a gear so the save path takes it as a
     * pick: it has a name, a play address and a codec code, and nothing else.
     */
    public static final class SourceGear {
        private final String name;
        private final Object address;
        private final int codec;

        SourceGear(String name, Object address, int codec) {
            this.name = name;
            this.address = address;
            this.codec = codec;
        }

        public String getGearName() { return name; }
        public Object getPlayAddr() { return address; }
        public int isBytevc1() { return codec; }
    }

    /** Name, getter, field and codec code of each play address the Video model carries besides its gears. */
    private static final String[][] SOURCES = {
            {"play_addr_h264", "getPlayAddrH264", "playAddrValue", "0"},
            {"h264_play_addr", "getH264PlayAddr", "h264PlayAddrValue", "0"},
            {"play_addr_bytevc1", "getPlayAddrBytevc1", "playAddrBytevc1Value", "1"},
            {"download_no_watermark", "getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr", "0"},
            {"new_download_addr", "getNewDownloadAddr", "newDownloadAddr", "0"},
            {"ui_alike_download_addr", "getUIAlikeDownloadAddr", "uiAlikeAddr", "0"},
    };

    /**
     * The video's own H.264 and HEVC play addresses (H.264 and HEVC only), each
     * wrapped as a gear. A source the video lacks has a null address, so a report can say so. The
     * field is read before the getter: the getters tidy the model's codec fields as they answer.
     */
    static List<SourceGear> sources(Object video) {
        List<SourceGear> found = new ArrayList<>();
        if (video == null) return found;
        for (String[] source : SOURCES) {
            Object address = Reflect.readField(video, source[2]);
            if (address == null) address = Reflect.invoke(video, source[1]);
            found.add(new SourceGear(source[0], address, Integer.parseInt(source[3])));
        }
        Object misc = Reflect.property(video, "getMiscDownloadAddrs", "miscDownloadAddrs");
        if (misc instanceof String && !((String) misc).isEmpty()) {
            try {
                collectMiscSources(new JSONObject((String) misc), "misc_download_addr", found);
            } catch (JSONException ignored) {
                // The diagnostic report already carries malformed misc address strings.
            }
        }
        return found;
    }

    private static void collectMiscSources(JSONObject root, String name, List<SourceGear> found) {
        JSONArray names = root.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            Object value = root.opt(key);
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                Object rawUrls = object.opt("url_list");
                if (rawUrls instanceof JSONArray) {
                    List<String> urls = new ArrayList<>();
                    JSONArray urlList = (JSONArray) rawUrls;
                    for (int j = 0; j < urlList.length(); j++) {
                        String url = urlList.optString(j);
                        if (!url.isEmpty()) urls.add(url);
                    }
                    if (!urls.isEmpty()) {
                        int width = object.optInt("width");
                        int height = object.optInt("height");
                        int size = object.optInt("size");
                        found.add(new SourceGear(name + '_' + key,
                                new MiscAddress(urls, width, height, size), 0));
                    }
                }
                collectMiscSources(object, name + '_' + key, found);
            }
        }
    }

    private static final class MiscAddress extends UrlModel {
        private final List<String> urls;
        private final int width;
        private final int height;
        private final long size;

        private MiscAddress(List<String> urls, int width, int height, int size) {
            this.urls = urls;
            this.width = width;
            this.height = height;
            this.size = size;
        }

        public List<String> getUrlList() { return urls; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public long getSize() { return size; }
    }

    private static final class ModelAddress extends UrlModel {
        private final List<String> urls;
        private final int width;
        private final int height;
        private final long size;

        private ModelAddress(List<String> urls, int width, int height, long size) {
            this.urls = urls;
            this.width = width;
            this.height = height;
            this.size = size;
        }

        public List<String> getUrlList() { return urls; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public long getSize() { return size; }
    }

    public static UrlModel download(Object video) {
        String mode = Settings.DOWNLOAD_VIDEO_QUALITY.get();
        if ("auto".equals(mode)) return null;
        // Separate DASH tracks need the full download path and muxer, not a video-only URL.
        if (Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"))) return null;
        Object raw = rawGears(video);
        Object gear = raw instanceof List<?> ? chooseForFile(video, (List<?>) raw, mode) : null;
        Object address = Reflect.property(gear, "getPlayAddr", "playAddr");
        return address instanceof UrlModel ? (UrlModel) address : null;
    }

    /** The rendition TikTok's player is to play: any codec, since TikTok decodes them all. */
    public static Object choose(List<?> gears, String mode) {
        return choose(gears, mode, false);
    }

    /**
     * The rendition a saved file is made from: only one other players open ({@link #playable}),
     * and at the same height H.264 over HEVC.
     */
    static Object chooseForFile(List<?> gears, String mode) {
        return choose(gears, mode, true);
    }

    /**
     * The rendition a saved file of this video is made from, or null for TikTok's own file. When
     * the asked height is served only as ByteVC2 the playable choice is a shorter one, and
     * TikTok's own watermark-free download, which is H.264, can be the asked height: it wins when
     * its address says it is taller than the choice and no taller than asked (refutation review
     * of bd52abf1). An address that says nothing keeps the playable choice.
     */
    static Object chooseForFile(Object video, List<?> gears, String mode) {
        Object chosen = chooseForFile(gears, mode);
        if (chosen == null || "lowest".equals(mode)) return chosen;
        int chosenHeight = height(chosen);
        Object tallest = choose(gears, mode, false);
        if (tallest == null || height(tallest) <= chosenHeight) return chosen;
        int own = ownHeight(video);
        int asked = "highest".equals(mode) ? 0 : Integer.parseInt(mode);
        return own > chosenHeight && (asked == 0 || own <= asked) ? null : chosen;
    }

    /**
     * How tall TikTok's own watermark-free download is, as the shorter side the way gear names
     * count; 0 when unknown or when only the watermarked address exists. Only the clean address
     * counts: a deferred save falls to the download addresses, and deferring onto a video with
     * nothing but the watermarked one would put the watermark on a save the rendition path kept
     * clean (refutation review of f792e71e).
     */
    static int ownHeight(Object video) {
        Object address = Reflect.property(video, "getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr");
        return usable(address) ? dimension(address) : 0;
    }

    private static Object choose(List<?> gears, String mode, boolean forFile) {
        if (gears == null || "auto".equals(mode)) return null;
        int target;
        switch (mode == null ? "" : mode) {
            case "highest": case "lowest": target = 0; break;
            case "1080": case "720": case "540": case "480": case "360": target = Integer.parseInt(mode); break;
            default: return null;
        }
        // Each gear's height is read once: the name's pattern and the address reads ran for both
        // sides of every comparison, three player hooks a video, on the main thread (#344).
        boolean lowest = "lowest".equals(mode);
        Object best = null;
        int bestHeight = 0;
        for (Object gear : gears) {
            if (!hasUsableAddress(gear)) continue;
            if (forFile && !playable(gear)) continue;
            int height = height(gear);
            if (target > 0 && height == 0) continue;
            if (best == null || better(gear, height, best, bestHeight, target, lowest, forFile)) {
                best = gear;
                bestHeight = height;
            }
        }
        return best;
    }

    /**
     * TikTok's codec code for a rendition (is_bytevc1): 0 is H.264, 1 is ByteVC1, which is HEVC
     * and plays on Android and in most players, and 2 is ByteVC2, which only TikTok's own player
     * decodes. A rendition without the code reads as H.264.
     */
    static int codec(Object gear) {
        Object code = Reflect.property(gear, "isBytevc1", "isBytevc1");
        return code instanceof Number ? ((Number) code).intValue() : 0;
    }

    /**
     * Whether a saved file of this rendition plays outside TikTok. 47.0.3 serves some heights only
     * as ByteVC2 (gear names ending in _2): saved, those came out with a video track no player
     * knew (codec tag bvc2, S22, 2026-09-23). None of them is chosen for a file; when nothing else
     * is left, TikTok's own save, which is H.264, runs instead. Playback keeps them.
     */
    static boolean playable(Object gear) {
        int code = codec(gear);
        return code == 0 || code == 1;
    }

    private static boolean better(Object candidate, int a, Object current, int b,
                                  int target, boolean lowest, boolean forFile) {
        if (target > 0) {
            if ((a <= target) != (b <= target)) return a <= target;
            if (a != b) return a <= target ? a > b : a < b;
        }
        // At the same height, the file more players can open: H.264 over HEVC.
        if (forFile && a == b && codec(candidate) != codec(current)) return codec(candidate) < codec(current);
        int compare = Integer.compare(a, b);
        if (compare == 0) compare = Long.compare(number(candidate, "getBitRate", "bitRate"), number(current, "getBitRate", "bitRate"));
        if (compare == 0) compare = Long.compare(size(candidate), size(current));
        return lowest ? compare < 0 : compare > 0;
    }

    /** A gear the way a report names it: its gear name and the height it plays at. */
    public static String describe(Object gear) {
        String name = Reflect.string(gear, "getGearName", "gearName");
        int height = height(gear);
        return (name == null || name.isEmpty() ? "unnamed" : name) + (height > 0 ? " " + height + "p" : "");
    }

    /** A gear's size as the shorter side, from its name or its address, or 0. */
    public static int heightOf(Object gear) {
        return height(gear);
    }

    private static int height(Object gear) {
        String name = Reflect.string(gear, "getGearName", "gearName");
        if (name != null) {
            Matcher matcher = HEIGHT.matcher(name);
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }
        return dimension(Reflect.property(gear, "getPlayAddr", "playAddr"));
    }

    /** An address's size as the shorter side, or the one side it gives, or 0. */
    static int dimension(Object address) {
        long width = number(address, "getWidth", "width"), height = number(address, "getHeight", "height");
        return (int) (width > 0 && height > 0 ? Math.min(width, height) : Math.max(width, height));
    }

    static long size(Object gear) {
        return addressSize(Reflect.property(gear, "getPlayAddr", "playAddr"));
    }

    /** The size TikTok lists for an address, or 0. */
    static long addressSize(Object address) {
        return number(address, "getSize", "size");
    }

    private static long number(Object value, String getter, String field) {
        Object raw = Reflect.property(value, getter, field);
        return raw instanceof Number ? ((Number) raw).longValue() : 0;
    }

    /**
     * Whether a rendition's play address holds a URL this would choose: the same test for the
     * player's gears and a save's, and nothing about the codec, so ByteVC2 passes it. A missing,
     * empty or unreadable address does not.
     */
    public static boolean hasUsableAddress(Object gear) {
        return usable(Reflect.property(gear, "getPlayAddr", "playAddr"));
    }

    private static boolean usable(Object address) {
        Object urls = Reflect.property(address, "getUrlList", "urlList");
        if (!(urls instanceof List<?>)) return false;
        for (Object url : (List<?>) urls) {
            if (url instanceof String && MediaTransport.hasAllowedShape((String) url)) return true;
        }
        return false;
    }
}
