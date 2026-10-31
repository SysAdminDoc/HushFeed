/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Environment;
import android.os.Looper;
import android.preference.ListPreference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * The MP3 choice under Sound format. Robolectric has no codecs, so the encoder is fed samples
 * directly, and the decoder loop is covered by the way it fails.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {TrackMuxerTest.SampleExtractor.class, TrackMuxerTest.RecordingMuxer.class})
public class Mp3SoundFormatTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Before public void prepare() {
        Utils.setActivity(null);
        TrackMuxerTest.SampleExtractor.useDefaultAudio();
        TrackMuxerTest.RecordingMuxer.resetRecording();
    }

    @After public void finish() {
        SettingsStatus.advancedDownloadsEnabled = false;
        Settings.DOWNLOAD_SOUND_FORMAT.resetToDefault();
        Settings.DOWNLOAD_AUDIO_TRACK.resetToDefault();
        Settings.DOWNLOAD_VIDEO_PATH.resetToDefault();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static ListPreference row() {
        try (var controller = Robolectric.buildActivity(AdvancedDownloadsTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.advancedDownloadsEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new DownloadsPreferenceCategory(activity, screen);
            return (ListPreference) screen.findPreference(Settings.DOWNLOAD_SOUND_FORMAT.key);
        }
    }

    private static short[] sine(int frames, int rate, int channels) {
        short[] pcm = new short[frames * channels];
        for (int i = 0; i < frames; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 12_000);
            for (int c = 0; c < channels; c++) pcm[i * channels + c] = sample;
        }
        return pcm;
    }

    private static byte[] encode(int rate, int channels, int seconds) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Mp3Transcoder.Encoder encoder = new Mp3Transcoder.Encoder(rate, channels);
        // Two writes, so the chunking and the hand-over between calls are both exercised.
        int frames = rate * seconds;
        encoder.write(sine(frames, rate, channels), frames, out);
        encoder.write(sine(1000, rate, channels), 1000, out);
        encoder.finish(out);
        return out.toByteArray();
    }

    private static void assertStartsWithFrameSync(byte[] mp3) {
        assertTrue("only " + mp3.length + " bytes came out", mp3.length > 1000);
        assertEquals("no frame sync", 0xFF, mp3[0] & 0xFF);
        assertEquals("no frame sync", 0xE0, mp3[1] & 0xE0);
        assertTrue("the layer bits say it isn't MPEG audio", (mp3[1] & 0x06) != 0);
        assertTrue(Mp3Transcoder.looksLikeMp3(mp3, mp3.length));
    }

    @Test public void stereoSamplesComeOutAsMpegFrames() throws Exception {
        byte[] mp3 = encode(44100, 2, 1);
        assertStartsWithFrameSync(mp3);
        // 192 kbps is 24,000 bytes a second, so a second and a bit lands near that.
        assertTrue("size " + mp3.length, mp3.length > 20_000 && mp3.length < 40_000);
    }

    @Test public void monoSamplesAtALowRateComeOutAsMpegFrames() throws Exception {
        byte[] mp3 = encode(22050, 1, 1);
        assertStartsWithFrameSync(mp3);
        // 96 kbps is 12,000 bytes a second.
        assertTrue("size " + mp3.length, mp3.length > 8_000 && mp3.length < 25_000);
    }

    @Test public void theBitRateStaysInsideWhatTheSampleRateAllows() {
        assertEquals(192, Mp3Transcoder.bitRateKbps(44100, 2));
        assertEquals(128, Mp3Transcoder.bitRateKbps(48000, 1));
        assertEquals(128, Mp3Transcoder.bitRateKbps(24000, 2));
        assertEquals(96, Mp3Transcoder.bitRateKbps(16000, 1));
    }

    @Test public void onlyMpegAudioLooksLikeAnMp3() {
        assertTrue(Mp3Transcoder.looksLikeMp3(new byte[]{'I', 'D', '3', 4}, 4));
        assertTrue(Mp3Transcoder.looksLikeMp3(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0}, 4));
        assertFalse("ADTS AAC shares the sync and leaves the layer at zero",
                Mp3Transcoder.looksLikeMp3(new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, 0}, 4));
        assertFalse("an MP4 has ftyp at offset four",
                Mp3Transcoder.looksLikeMp3(new byte[]{0, 0, 0, 24}, 4));
        assertFalse(Mp3Transcoder.looksLikeMp3(new byte[]{'I', 'D'}, 2));
    }

    @Test public void anMp3SoundIsSavedUntouchedWithAnMp3Ending() throws Exception {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.MP3);
        assertTrue(SoundFormat.mp3());
        assertFalse("MP3 doesn't turn Opus on", SoundFormat.opus());

        String folder = "DCIM/mp3-format-" + System.nanoTime();
        Settings.DOWNLOAD_VIDEO_PATH.save(folder);
        byte[] mp3 = encode(44100, 2, 1);
        File source = files.newFile("sound.tmp");
        Files.write(source.toPath(), mp3);
        File destination = new File(Environment.getExternalStorageDirectory(), folder);
        try {
            assertTrue(AudioDownloads.write(RuntimeEnvironment.getApplication(), "clip.m4a", source, folder, false));
            assertArrayEquals("the bytes were encoded a second time",
                    mp3, Files.readAllBytes(new File(destination, "clip.mp3").toPath()));
            assertFalse(new File(destination, "clip.m4a").exists());
            assertTrue("the caller's file was deleted", source.exists());
        } finally {
            File[] outputs = destination.listFiles();
            if (outputs != null) for (File output : outputs) assertTrue(output.delete());
            if (destination.exists()) assertTrue(destination.delete());
        }
    }

    @Test public void aFailedEncodeIsSavedAsTikTokSentItAndSaysSo() throws Exception {
        File source = files.newFile("sound.m4a");
        File output = files.newFile("sound.mp3");
        ShadowToast.reset();
        assertFalse(Mp3Transcoder.transcodeOrKeep(source, output));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(L10n.t("MP3 didn't work. Saving TikTok's own file."),
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void theRowOffersMp3EvenWhereOpusIsOutOfReach() {
        assertFalse(SoundFormat.opusPossible());
        assertTrue("the row greyed out with MP3 still possible", Settings.DOWNLOAD_SOUND_FORMAT.isAvailable());
        ListPreference row = row();
        assertEquals(3, row.getEntryValues().length);
        assertTrue(Arrays.asList(row.getEntryValues()).contains(SoundFormat.MP3));
        assertTrue(Arrays.asList(row.getEntries()).contains("MP3 (.mp3), plays anywhere"));

        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.MP3);
        row = row();
        assertEquals(SoundFormat.MP3, row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("MP3 (.mp3), plays anywhere\n"));
    }

    @Test public void theNamePreviewShowsTheMp3() {
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_AUDIO_TRACK.save(true);
        Settings.DOWNLOAD_VIDEO_PATH.save("DCIM/Clips");
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.MP3);
        String preview = DownloadNamePreview.video("{creator}/{video_id}", 1_699_963_200_000L);
        assertTrue(preview, preview.contains("7312345678901234567.mp3"));
        Settings.DOWNLOAD_SOUND_FORMAT.save(SoundFormat.ORIGINAL);
        preview = DownloadNamePreview.video("{creator}/{video_id}", 1_699_963_200_000L);
        assertTrue(preview, preview.contains("7312345678901234567.m4a"));
    }
}
