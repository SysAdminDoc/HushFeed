package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.view.Surface;

import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaCodec;

/** Exercise acquisition and cleanup through the real setup method, without a native recorder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = {
        ScreenSourceAudioLifecycleTest.RecordBuilder.class,
        ScreenSourceAudioLifecycleTest.Record.class,
        ScreenSourceAudioLifecycleTest.Codec.class})
public class ScreenSourceAudioLifecycleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private enum Failure {
        NONE, RECORD_CREATE, CODEC_CREATE, CODEC_CONFIGURE,
        CODEC_START, RECORD_START, RECORD_STAYS_STOPPED, RECORD_UNINITIALIZED
    }

    private static Failure failure;
    private static int cleanupFailures;
    private static final List<Record> records = new ArrayList<>();
    private static final List<Codec> codecs = new ArrayList<>();

    @Before public void reset() {
        failure = Failure.NONE;
        cleanupFailures = 0;
        records.clear();
        codecs.clear();
    }

    private static ScreenSource source(boolean audio) {
        var app = RuntimeEnvironment.getApplication();
        return new ScreenSource(app,
                app.getResources().getDisplayMetrics(), audio);
    }

    @Test public void eachAcquisitionFailureReleasesWhatItOwnsAndAllowsAnotherRecording() throws Exception {
        MediaProjection projection = ScreenSourceAudioCaptureTest.audioProjection();
        for (Failure stage : Failure.values()) {
            if (stage == Failure.NONE) continue;
            reset();
            failure = stage;
            ScreenSource failed = source(true);
            ScreenSource.Encoder encoder = failed.new Encoder(projection);
            encoder.setUpAudio(projection);

            assertEquals(stage.name(), LiveRecorder.ScreenAudio.VIDEO_ONLY, failed.screenAudio());
            assertEquals(stage + " record ownership", stage == Failure.RECORD_CREATE ? 0 : 1, records.size());
            int expectedCodecs = stage == Failure.RECORD_CREATE || stage == Failure.RECORD_UNINITIALIZED
                    || stage == Failure.CODEC_CREATE ? 0 : 1;
            assertEquals(stage + " codec ownership", expectedCodecs, codecs.size());
            assertReleasedOnce(stage.name());
            encoder.releaseAudio();
            assertReleasedOnce(stage + " repeated cleanup");

            failure = Failure.NONE;
            ScreenSource next = source(true);
            ScreenSource.Encoder retry = next.new Encoder(projection);
            retry.setUpAudio(projection);
            assertEquals(stage + " next recording", LiveRecorder.ScreenAudio.ENABLED, next.screenAudio());
            Record nextRecord = records.get(records.size() - 1);
            Codec nextCodec = codecs.get(codecs.size() - 1);
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, nextRecord.recordingState);
            assertEquals(0, nextRecord.releases);
            assertEquals(1, nextCodec.starts);
            assertEquals(0, nextCodec.releases);
            retry.releaseAudio();
            assertReleasedOnce(stage + " next recording cleanup");
            assertEquals("cleanup must preserve the completion status",
                    LiveRecorder.ScreenAudio.ENABLED, next.screenAudio());
        }
    }

    @Test public void everyStopAndReleaseIsAttemptedWhenOtherCleanupCallsThrow() throws Exception {
        MediaProjection projection = ScreenSourceAudioCaptureTest.audioProjection();
        for (int failures = 0; failures < 16; failures++) {
            reset();
            ScreenSource source = source(true);
            ScreenSource.Encoder encoder = source.new Encoder(projection);
            encoder.setUpAudio(projection);
            assertEquals(LiveRecorder.ScreenAudio.ENABLED, source.screenAudio());

            cleanupFailures = failures;
            encoder.releaseAudio();
            assertReleasedOnce("cleanup mask " + failures);
            encoder.releaseAudio();
            assertReleasedOnce("detached fields for cleanup mask " + failures);
        }
    }

    @Test public void disabledAudioDoesNotAcquireAnything() {
        ScreenSource source = source(false);
        source.new Encoder(null).setUpAudio(null);
        assertEquals(LiveRecorder.ScreenAudio.VIDEO_ONLY, source.screenAudio());
        assertEquals(0, records.size());
        assertEquals(0, codecs.size());
    }

    @Test @Config(sdk = 28)
    public void unsupportedPlaybackCaptureDoesNotAcquireAnything() {
        ScreenSource source = source(true);
        source.new Encoder(null).setUpAudio(null);
        assertEquals(LiveRecorder.ScreenAudio.VIDEO_ONLY, source.screenAudio());
        assertEquals(0, records.size());
        assertEquals(0, codecs.size());
    }

    private static void assertReleasedOnce(String label) {
        for (Record record : records) {
            assertEquals(label + " record release", 1, record.releases);
            assertEquals(label + " record stop", 1, record.stops);
        }
        for (Codec codec : codecs) {
            assertEquals(label + " codec stop", 1, codec.stops);
            assertEquals(label + " codec release", 1, codec.releases);
        }
    }

    @Implements(AudioRecord.Builder.class)
    public static class RecordBuilder {
        @Implementation protected AudioRecord build() {
            if (failure == Failure.RECORD_CREATE) throw new IllegalStateException("record creation");
            return new AudioRecord(MediaRecorder.AudioSource.DEFAULT, 44100,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, 4096);
        }
    }

    @Implements(AudioRecord.class)
    public static class Record {
        int recordingState = AudioRecord.RECORDSTATE_STOPPED;
        int stops, releases;

        @Implementation protected void __constructor__(int source, int rate, int channels, int format, int bytes) {
            records.add(this);
        }

        @Implementation protected int getState() {
            return failure == Failure.RECORD_UNINITIALIZED
                    ? AudioRecord.STATE_UNINITIALIZED : AudioRecord.STATE_INITIALIZED;
        }

        @Implementation protected void startRecording() {
            if (failure == Failure.RECORD_START) throw new IllegalStateException("record start");
            // Android can return from startRecording() without changing this state.
            if (failure != Failure.RECORD_STAYS_STOPPED) recordingState = AudioRecord.RECORDSTATE_RECORDING;
        }

        @Implementation protected int getRecordingState() { return recordingState; }

        @Implementation protected void stop() {
            stops++;
            recordingState = AudioRecord.RECORDSTATE_STOPPED;
            if ((cleanupFailures & 1) != 0) throw new IllegalStateException("record stop");
        }

        @Implementation protected void release() {
            releases++;
            if ((cleanupFailures & 2) != 0) throw new IllegalStateException("record release");
        }
    }

    @Implements(MediaCodec.class)
    public static class Codec extends ShadowMediaCodec {
        int starts, stops, releases;

        @Override @Implementation protected void __constructor__(String name, boolean nameIsType, boolean encoder) {
            if (failure == Failure.CODEC_CREATE) throw new IllegalStateException("codec creation");
            super.__constructor__(name, nameIsType, encoder);
            codecs.add(this);
        }

        @Implementation protected void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
            if (failure == Failure.CODEC_CONFIGURE) throw new IllegalStateException("codec configure");
        }

        @Implementation protected void start() {
            starts++;
            if (failure == Failure.CODEC_START) throw new IllegalStateException("codec start");
        }

        @Implementation protected void stop() {
            stops++;
            if ((cleanupFailures & 4) != 0) throw new IllegalStateException("codec stop");
        }

        @Implementation protected void release() {
            releases++;
            if ((cleanupFailures & 8) != 0) throw new IllegalStateException("codec release");
        }
    }
}
