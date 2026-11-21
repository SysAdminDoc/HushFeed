/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Names the song in a video's sound by asking Shazam, the way SongRec does from a desktop.
 *
 * <p>The sound entry's audio is fetched from TikTok's own mirrors, 12 seconds from its middle are
 * decoded to 16 kHz mono, and {@link ShazamSignature} turns them into a fingerprint. Only that
 * fingerprint goes out: one POST to amp.shazam.com, made when the reader picks the action and at
 * no other time, with no account, no cookie and a fixed place and time zone, as SongRec sends
 * it. The audio itself never leaves the phone.
 */
public final class SongRecognition {
    static final String TAG_URL = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/";
    static final String TAG_QUERY =
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3";
    static final int CLIP_SAMPLES = 12 * ShazamSignature.SAMPLE_RATE;
    private static final long CLIP_US = 12_000_000L;
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_ANSWER_BYTES = 1 << 20;
    private static final long TIMEOUT_US = 10_000L;
    private static final int MAX_IDLE_TURNS = 500;
    /** android.media.AudioFormat.ENCODING_PCM_16BIT. */
    private static final int PCM_16BIT = 2;
    /** One of the Android user agents SongRec picks from, for a phone that names none. */
    private static final String FALLBACK_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)";

    /** How a lookup ended. */
    public enum Outcome { FOUND, NOT_FOUND, NO_SOUND, QUIET, LIMITED, FAILED }

    /** What a lookup found: the outcome, and for {@link Outcome#FOUND} the song. */
    public static final class Result {
        public final Outcome outcome;
        @Nullable public final String title;
        @Nullable public final String artist;

        Result(Outcome outcome, @Nullable String title, @Nullable String artist) {
            this.outcome = outcome;
            this.title = title;
            this.artist = artist;
        }

        static Result of(Outcome outcome) {
            return new Result(outcome, null, null);
        }
    }

    /** Shazam's endpoint, which a test answers in place of the server. */
    interface Poster {
        String post(String url, String body) throws IOException;
    }

    /** Thrown for HTTP 429, Shazam's answer to too many lookups from one address. */
    static final class RateLimited extends IOException {
        RateLimited() {
            super("Shazam answered 429");
        }
    }

    interface ClipDecoder {
        short[] clip(File sound) throws IOException;
    }

    static volatile Poster posterForTests;
    static volatile ClipDecoder decoderForTests;

    private SongRecognition() {
    }

    /** Whether the video has a sound entry to look up. */
    public static boolean hasSound(Object aweme) {
        return aweme != null && !OriginalSoundDownloads.sourceUrls(aweme).isEmpty();
    }

    /** Runs the whole lookup on the calling thread, which must not be the main one. */
    public static Result identify(Context context, Object aweme) {
        List<String> sources = aweme == null ? List.of() : OriginalSoundDownloads.sourceUrls(aweme);
        if (sources.isEmpty()) return Result.of(Outcome.NO_SOUND);
        File fetched = null;
        try {
            fetched = MediaCache.createTempFile(context.getApplicationContext(), "song-lookup-", ".tmp");
            RemoteMedia.fetch(sources, fetched, RemoteMedia.Kind.AUDIO);
            ClipDecoder decoder = decoderForTests;
            short[] clip = decoder != null ? decoder.clip(fetched) : clip(fetched);
            ShazamSignature signature = ShazamSignature.of(clip);
            // A silent or near silent sound has nothing to match on, so nothing is sent.
            if (signature.peakCount() == 0) return Result.of(Outcome.QUIET);
            Poster poster = posterForTests;
            String url = url(UUID.randomUUID(), UUID.randomUUID());
            String body = requestBody(signature, System.currentTimeMillis());
            String answer = poster != null ? poster.post(url, body) : post(url, body);
            return parse(answer);
        } catch (RateLimited limited) {
            return Result.of(Outcome.LIMITED);
        } catch (IOException | JSONException | RuntimeException failure) {
            Logger.printException(() -> "Song lookup failed", failure);
            return Result.of(Outcome.FAILED);
        } finally {
            if (fetched != null && !MediaCache.delete(fetched)) {
                Logger.printInfo(() -> "Could not remove the song lookup's temporary file");
            }
        }
    }

    /** SongRec's tag address: two fresh UUIDs, the first in capitals. */
    static String url(UUID first, UUID second) {
        return TAG_URL + first.toString().toUpperCase(Locale.ROOT) + "/" + second + TAG_QUERY;
    }

    /** SongRec's request body, with its fixed place and time zone. */
    static String requestBody(ShazamSignature signature, long nowMillis) throws JSONException {
        JSONObject place = new JSONObject().put("altitude", 300).put("latitude", 45).put("longitude", 2);
        JSONObject print = new JSONObject()
                .put("samplems", signature.sampleMillis())
                .put("timestamp", nowMillis)
                .put("uri", signature.uri());
        return new JSONObject()
                .put("geolocation", place)
                .put("signature", print)
                .put("timestamp", nowMillis)
                .put("timezone", "Europe/Paris")
                .toString();
    }

    /** The song in Shazam's answer: {@code track.title} and {@code track.subtitle}, its artist. */
    static Result parse(@Nullable String answer) throws JSONException {
        if (answer == null) throw new JSONException("No answer");
        JSONObject root = new JSONObject(answer);
        JSONArray matches = root.optJSONArray("matches");
        JSONObject track = root.optJSONObject("track");
        if (matches == null || matches.length() == 0 || track == null) return Result.of(Outcome.NOT_FOUND);
        String title = track.optString("title", "").trim();
        if (title.isEmpty()) return Result.of(Outcome.NOT_FOUND);
        String artist = track.optString("subtitle", "").trim();
        return new Result(Outcome.FOUND, title, artist.isEmpty() ? null : artist);
    }

    private static String post(String url, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Language", "en_US");
            String agent = System.getProperty("http.agent");
            connection.setRequestProperty("User-Agent",
                    agent != null && agent.startsWith("Dalvik/") ? agent : FALLBACK_AGENT);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            if (code == 429) throw new RateLimited();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Shazam answered " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream answer = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    if (answer.size() + read > MAX_ANSWER_BYTES) throw new IOException("Shazam's answer is too long");
                    answer.write(buffer, 0, read);
                }
                return new String(answer.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Twelve seconds of the sound at 16 kHz mono, from its middle when it is longer, padded with
     * silence when it is shorter, as SongRec takes them.
     */
    static short[] clip(File sound) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(sound.getAbsolutePath());
            MediaFormat input = selectAudio(extractor);
            long duration = input.containsKey(MediaFormat.KEY_DURATION) ? input.getLong(MediaFormat.KEY_DURATION) : 0;
            if (duration > CLIP_US) {
                extractor.seekTo(duration / 2 - CLIP_US / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, null, null, 0);
            decoder.start();

            short[] clip = new short[CLIP_SAMPLES];
            int have = 0, channels = 0, idle = 0;
            LiveSpeechAudio.Resampler resampler = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean fed = false, done = false, moved = true;
            while (!done && have < CLIP_SAMPLES) {
                long wait = moved ? 0 : TIMEOUT_US;
                moved = false;
                if (!fed) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                        moved = true;
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, wait);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (index >= 0 && resampler == null)) {
                    MediaFormat raw = decoder.getOutputFormat();
                    if (raw.containsKey("pcm-encoding") && raw.getInteger("pcm-encoding") != PCM_16BIT) {
                        throw new IOException("The sound decodes to samples this can't read");
                    }
                    int rate = raw.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = raw.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (rate <= 0 || channels < 1) throw new IOException("The sound has no shape to read");
                    resampler = new LiveSpeechAudio.Resampler(rate, channels);
                    moved = true;
                }
                if (index >= 0) {
                    moved = true;
                    boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    ByteBuffer data = info.size > 0 ? decoder.getOutputBuffer(index) : null;
                    if (data != null) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        ShortBuffer shorts = data.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int count = shorts.remaining() / channels * channels;
                        short[] pcm = new short[count];
                        shorts.get(pcm);
                        short[] mono = resampler.resample(pcm, count);
                        int take = Math.min(mono.length, CLIP_SAMPLES - have);
                        System.arraycopy(mono, 0, clip, have, take);
                        have += take;
                    }
                    decoder.releaseOutputBuffer(index, false);
                    if (last) done = true;
                }
                if (moved) idle = 0;
                else if (++idle > MAX_IDLE_TURNS) throw new IOException("The sound stopped decoding");
            }
            if (have == 0) throw new IOException("The sound gave nothing to listen to");
            return clip;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (RuntimeException ignored) {
                    // Already failed or never started. Release below is what matters.
                }
                try {
                    decoder.release();
                } catch (RuntimeException failure) {
                    Logger.printInfo(() -> "Could not release the song lookup's decoder: " + failure);
                }
            }
            extractor.release();
        }
    }

    private static MediaFormat selectAudio(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new IOException("The sound file has no audio track");
    }

    static void resetForTests() {
        posterForTests = null;
        decoderForTests = null;
    }
}
