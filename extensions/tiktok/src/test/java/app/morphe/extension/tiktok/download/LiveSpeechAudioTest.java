/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The LIVE's sound as speech recognition gets it (#424): 16 kHz mono, whatever rate and channel
 * count the room's AAC decodes to, with nothing above 8 kHz folding back over the voices.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveSpeechAudioTest {
    private static short[] tone(int frames, int rate, double hertz, int channels, double[] gains) {
        short[] pcm = new short[frames * channels];
        for (int i = 0; i < frames; i++) {
            double value = 10_000 * Math.sin(2 * Math.PI * hertz * i / rate);
            for (int c = 0; c < channels; c++) pcm[i * channels + c] = (short) Math.round(value * gains[c]);
        }
        return pcm;
    }

    private static double rms(short[] pcm, int from) {
        double sum = 0;
        for (int i = from; i < pcm.length; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / (pcm.length - from));
    }

    private static short[] concat(short[] a, short[] b) {
        short[] joined = new short[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }

    @Test public void piecesJoinWithoutASeam() {
        short[] stereo = tone(48_000, 48_000, 440, 2, new double[]{1, 0.5});
        short[] whole = new LiveSpeechAudio.Resampler(48_000, 2).resample(stereo, stereo.length);

        LiveSpeechAudio.Resampler pieces = new LiveSpeechAudio.Resampler(48_000, 2);
        Random random = new Random(424);
        short[] joined = new short[0];
        int at = 0;
        while (at < stereo.length) {
            // Whole frames, as the decoder hands them over, in uneven pieces.
            int count = Math.min(stereo.length - at, 2 * (1 + random.nextInt(1500)));
            short[] piece = new short[count];
            System.arraycopy(stereo, at, piece, 0, count);
            joined = concat(joined, pieces.resample(piece, count));
            at += count;
        }
        assertArrayEquals(whole, joined);
    }

    @Test public void aSteadyLevelStaysAndTheLengthFollowsTheRate() {
        for (int rate : new int[]{48_000, 44_100, 32_000, 16_000}) {
            short[] level = new short[rate];
            java.util.Arrays.fill(level, (short) 1_000);
            short[] out = new LiveSpeechAudio.Resampler(rate, 1).resample(level, level.length);
            assertTrue(rate + " Hz gave " + out.length, Math.abs(out.length - LiveSpeechAudio.RATE) <= 3);
            for (short sample : out) assertEquals(rate + " Hz", 1_000, sample, 1);
        }
    }

    @Test public void voicesPassAndWhatSixteenKilohertzCantHoldIsTakenOff() {
        short[] voice = tone(48_000, 48_000, 440, 1, new double[]{1});
        short[] heard = new LiveSpeechAudio.Resampler(48_000, 1).resample(voice, voice.length);
        assertEquals(rms(voice, 0), rms(heard, 200), rms(voice, 0) * 0.05);

        // Every third sample of a 12 kHz tone at 48 kHz is the tone itself, so a plain rate change
        // would hand all of it on as a 4 kHz one.
        short[] hiss = tone(48_000, 48_000, 12_000, 1, new double[]{1});
        short[] folded = new LiveSpeechAudio.Resampler(48_000, 1).resample(hiss, hiss.length);
        assertTrue("12 kHz came through at " + rms(folded, 200), rms(folded, 200) < rms(hiss, 0) * 0.3);
    }

    @Test public void theChannelsAreAveraged() {
        short[] opposite = tone(4_800, 48_000, 300, 2, new double[]{1, -1});
        for (short sample : new LiveSpeechAudio.Resampler(48_000, 2).resample(opposite, opposite.length)) {
            assertEquals(0, sample);
        }
    }

    @Test public void theSinkGetsLittleEndianBytes() {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        LiveSpeechAudio.Decoder decoder = new LiveSpeechAudio.Decoder((data, length) -> got.write(data, 0, length));
        decoder.use(LiveSpeechAudio.RATE, 1);
        decoder.feed(new short[]{1, -2, 0x1234}, 3);

        assertArrayEquals(new byte[]{1, 0, (byte) 0xFE, (byte) 0xFF, 0x34, 0x12}, got.toByteArray());
        assertEquals(6, decoder.bytes);
    }
}
