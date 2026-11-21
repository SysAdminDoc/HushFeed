package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.shared.Utils;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Identify the song: the sound entry's audio is fetched from TikTok's mirrors, and only its
 * fingerprint goes to Shazam, in the request SongRec sends. Nothing is sent for a video with no
 * sound entry or a sound with nothing in it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SongRecognitionTest {
    private static final String FOUND = "{\"matches\":[{\"id\":\"123\",\"offset\":40.1}],"
            + "\"track\":{\"key\":\"123\",\"title\":\"A Song\",\"subtitle\":\"A Band\","
            + "\"url\":\"https://www.shazam.com/track/123/a-song\"}}";

    private Context context;
    private final List<String> fetched = new ArrayList<>();
    private final List<String> posted = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        byte[] m4a = new byte[32];
        System.arraycopy(new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, 0, m4a, 0, 12);
        RemoteMedia.transportForTests = MediaTransportFixtures.publicClient(url -> {
            fetched.add(url.toString());
            return MediaTransportFixtures.response(url, 200, m4a);
        });
        SongRecognition.decoderForTests = sound -> ShazamSignatureTest.bursts(1000, 2.5);
    }

    @After public void tearDown() {
        RemoteMedia.transportForTests = null;
        SongRecognition.resetForTests();
    }

    private static Object postWithSound() {
        return new OriginalSoundDownloadsTest.Post(new OriginalSoundDownloadsTest.Music(
                new OriginalSoundDownloadsTest.PlayUrl(List.of("https://v16-webapp.tiktokcdn.com/sound.m4a"), null),
                "original sound - someone"));
    }

    @Test public void theFingerprintGoesToShazamAndTheSongComesBack() throws Exception {
        SongRecognition.posterForTests = (url, body) -> {
            posted.add(url);
            posted.add(body);
            return FOUND;
        };
        assertTrue(SongRecognition.hasSound(postWithSound()));
        SongRecognition.Result result = SongRecognition.identify(context, postWithSound());

        assertEquals(SongRecognition.Outcome.FOUND, result.outcome);
        assertEquals("A Song", result.title);
        assertEquals("A Band", result.artist);
        assertEquals("the sound came from TikTok's own mirror", List.of("https://v16-webapp.tiktokcdn.com/sound.m4a"), fetched);
        assertEquals(2, posted.size());
        assertTrue(posted.get(0).startsWith(SongRecognition.TAG_URL));

        JSONObject body = new JSONObject(posted.get(1));
        String uri = body.getJSONObject("signature").getString("uri");
        assertTrue("a fingerprint, not audio", uri.startsWith(ShazamSignature.URI_PREFIX));
        assertTrue(ShazamSignature.fromUri(uri).peakCount() > 0);
        File[] left = MediaCache.cacheDirectory(context).listFiles((dir, name) -> name.startsWith("song-lookup-"));
        assertEquals("the fetched sound is gone after the lookup", 0, left == null ? 0 : left.length);
    }

    @Test public void theRequestIsTheOneSongRecSends() throws Exception {
        UUID first = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
        UUID second = UUID.fromString("ffeeddcc-bbaa-4988-8776-655443322110");
        assertEquals("https://amp.shazam.com/discovery/v5/en/US/android/-/tag/"
                        + "0A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D/ffeeddcc-bbaa-4988-8776-655443322110"
                        + "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3",
                SongRecognition.url(first, second));

        ShazamSignature signature = ShazamSignature.of(ShazamSignatureTest.bursts(1000, 2.5));
        // 2024-11-12 08:26:40 UTC, past what fits in 32 bits of milliseconds, as SongRec's own test uses.
        JSONObject body = new JSONObject(SongRecognition.requestBody(signature, 1_731_400_000_000L));
        JSONObject place = body.getJSONObject("geolocation");
        assertEquals(300, place.getInt("altitude"));
        assertEquals(45, place.getInt("latitude"));
        assertEquals(2, place.getInt("longitude"));
        assertEquals("Europe/Paris", body.getString("timezone"));
        assertEquals(1_731_400_000_000L, body.getLong("timestamp"));
        JSONObject print = body.getJSONObject("signature");
        assertEquals(1_731_400_000_000L, print.getLong("timestamp"));
        assertEquals(12000, print.getLong("samplems"));
        assertEquals(signature.uri(), print.getString("uri"));
    }

    @Test public void anAnswerWithoutMatchesIsNotFound() throws Exception {
        assertEquals(SongRecognition.Outcome.NOT_FOUND,
                SongRecognition.parse("{\"matches\":[],\"tagid\":\"0A1B\",\"timestamp\":1}").outcome);
        assertEquals(SongRecognition.Outcome.NOT_FOUND,
                SongRecognition.parse("{\"matches\":[{\"id\":\"1\"}],\"track\":{\"title\":\"  \"}}").outcome);
        SongRecognition.Result noArtist =
                SongRecognition.parse("{\"matches\":[{\"id\":\"1\"}],\"track\":{\"title\":\"Song\"}}");
        assertEquals(SongRecognition.Outcome.FOUND, noArtist.outcome);
        assertNull(noArtist.artist);
    }

    @Test public void nothingIsSentForAVideoWithoutASoundEntry() {
        SongRecognition.posterForTests = (url, body) -> {
            posted.add(url);
            return FOUND;
        };
        Object bare = new OriginalSoundDownloadsTest.BarePost();
        assertEquals(false, SongRecognition.hasSound(bare));
        assertEquals(SongRecognition.Outcome.NO_SOUND, SongRecognition.identify(context, bare).outcome);
        assertEquals(List.of(), fetched);
        assertEquals(List.of(), posted);
    }

    @Test public void aSilentSoundIsNeverSent() {
        SongRecognition.decoderForTests = sound -> new short[SongRecognition.CLIP_SAMPLES];
        SongRecognition.posterForTests = (url, body) -> {
            posted.add(url);
            return FOUND;
        };
        assertEquals(SongRecognition.Outcome.QUIET, SongRecognition.identify(context, postWithSound()).outcome);
        assertEquals(List.of(), posted);
    }

    @Test public void aRateLimitAndAFailureAreToldApart() {
        SongRecognition.posterForTests = (url, body) -> {
            throw new SongRecognition.RateLimited();
        };
        assertEquals(SongRecognition.Outcome.LIMITED, SongRecognition.identify(context, postWithSound()).outcome);
        SongRecognition.posterForTests = (url, body) -> {
            throw new IOException("Shazam answered 500");
        };
        assertEquals(SongRecognition.Outcome.FAILED, SongRecognition.identify(context, postWithSound()).outcome);
        SongRecognition.posterForTests = (url, body) -> "<html>not json</html>";
        assertEquals(SongRecognition.Outcome.FAILED, SongRecognition.identify(context, postWithSound()).outcome);
    }
}
