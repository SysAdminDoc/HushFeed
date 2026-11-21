package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The fingerprint Shazam is sent. The layout is checked against SongRec's own test signature,
 * read and written back byte for byte, and the generator against tones whose pitch is known.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ShazamSignatureTest {
    /** SongRec's STUPEFLIP_DATA_URI_SAMPLE (python-version tests.py), a 6 second fingerprint. */
    static final String SONGREC_SAMPLE = "data:audio/vnd.shazam.sig;base64,gCX+ynzKnegoBQAAAJwRlAAAAAAAAAAAAAAAAAAAABgAAAAAAAAAAACGAQAAAHw"
            + "AAAAAQCgFAABAAANgeAAAACdVdK4MAT15RAgN3XWPCjsqeFUPHjR5JQ4VQXh5CR6/dF0OS3h2zg0MvHaHD1FneFgMEtdmRQ4"
            + "PZ2z4DhF2dZIMNHZwTQ834XZIDAOkaYwQBqpqLA8wVHKtDCWfczEPNGF90AwZXnmqCRPEejgJBY18xA8nGnDzDEEAA2ByAQA"
            + "AGVlxRRkJinLDGgazd8QQAbN0aCsBOm5iJQoXbXwWBQRsBRMDyHY6Hh9fc88QBt1qaSgIdXeLIQUGdgcmBRxwsRYBRnU8Hgo"
            + "7anIsBBJxpSQCNXT5FR9ja1MdAkt2iBIK+3QwJgZTdWwZDNFq0xUMG3OqIQNOeDMtJzhv6R4AaG4wKwVDd84XAb50UScDi24"
            + "/HgllbRgZDz5tliwFWXIDFQB+cQkjITltLRQDpHYcGAEIc8QnAQluqB8SIHMFGRE3bmocBiVxuCoIw3HOFgCZdD4tIoB0ER4"
            + "VSWzWJQ/CbYQcAYhyvCoKKnv/GAksbc4eJ/d9PSUBg3nrGAFxcSstLeJwkSYKF3QrHgAyb/olDzRvOCAAB3k2LRPhcYIsASl"
            + "z/RIA0G9MJBgWcB0cAc5wjyUGCnNhGQSVd/EmA9B0uhoIl3IYLQE4cFImFZJ1RR4GonRPKAHBeIgUFWlsEB8II4AKEQiSdpA"
            + "hFAtpLB4BZmSFKQAAQgADYIsBAAAMA3IWPAWTcoo2DiRwiE0BwnDpMgZecVNLAfpxkUMAVnQJSALmb88zCqd03jcABHLvTgZ"
            + "Db7w/ANJw+08T+3ISPhKGcH9DD6VzyzIA9XKaRTV3bnIvATRysjwC5XG7OAmNcT8yBEBwhD8PL3R/MwDNcBttEMRy7DImfHv"
            + "BMwb2dL87Dl91Jm5O/XTIZADKcwVrAQJzEl4Oa3RdVgEIeck9ATR6+UkGrnbvVAMPdRpQAQV0ZTgB+3bHMRSEeMU6Ez5+cko"
            + "AM324VgUFfYg8BZ90r0Qf7H8zMgPWdXE2Aeh7kU8BfHgAQwHxe6dLAHl4SlgIEX9rPg6denRGE+l5+0oCr4KWMQWxebRLDax"
            + "/cDwB93mESRN5e0BMAq6Cbj8JMoR7PgBbenNXA858zkoI5H36SA43et5FE/h50TERoHcbTwH4eNg0AdZ3TToL1HeaOACEeNQ"
            + "8APqBNEEBqXc/TgZ8ebBNATeDvUBbKWuJPAHhYTBiBbdtDUsBkmW0XgICdL44AB1r/FQSeWu7OgBDAANghgEAAAv0aDh4ACt"
            + "neoYBcGbUjwA0Zy6dALtoQqca4mfRcgHUZhSGAepqEKQKZmp6cADXZwabAeRlqpEFLmq3cVYWbv5wFHZof3cAimZphwANZjO"
            + "eAU9rQKcIv2WscSX0aMCcABRq0J8APWZVrQFzaoCFAKNsh5gB6myJdgzCaQJ7DwJwWosAJWqQjwCnb5KSAPptCZwB4G56fwZ"
            + "9bhmVDp9rU3cWBW3DrgcgazCbBkFt3ZIT+Wm5qA2zaXJ+ALVpF5YA8mnBmQFKaz+KAGZs65ESWmxvhACIafeqAbZqQHEAA20"
            + "ffADaao+kAc5q+XQAHGpGkMLkYkylDMlrBHIWUmRUdgMxZWWPA8tr2a4J8mY2eQD4ai2bBAdq0ZITDGhLhgBUZl2eAFhng6g"
            + "CfGLIgQtKZ4xwGc1j5ZsBK2QBeQFQanV0AfRjsaEBeGItiQEDZeF8DS9kDHoSe2UsdwFtZU5+CmFpLngOZGQ4hw0rXOeOAJ1"
            + "gmagB4F4DmwEJYQKACANd6J4VuFJtrAAA";

    @Test public void songRecsOwnSampleReadsBackByteForByte() throws Exception {
        ShazamSignature sample = ShazamSignature.fromUri(SONGREC_SAMPLE);
        // What SongRec's own decoder reads from it.
        assertEquals(96000, sample.numberSamples);
        assertEquals(6000, sample.sampleMillis());
        assertEquals(24, sample.bands.get(0).size());
        assertEquals(74, sample.bands.get(1).size());
        assertEquals(79, sample.bands.get(2).size());
        assertEquals(78, sample.bands.get(3).size());
        ShazamSignature.Peak first = sample.bands.get(0).get(0);
        assertEquals(39, first.pass);
        assertEquals(29781, first.magnitude);
        assertEquals(3246, first.bin);
        ShazamSignature.Peak last = sample.bands.get(3).get(77);
        assertEquals(755, last.pass);
        assertEquals(21176, last.magnitude);
        assertEquals(44141, last.bin);

        assertEquals("written back, it is the same signature", SONGREC_SAMPLE, sample.uri());
    }

    @Test public void aDamagedSignatureIsRefused() throws Exception {
        byte[] bytes = ShazamSignature.fromUri(SONGREC_SAMPLE).encode();
        bytes[bytes.length - 5] ^= 0x10;
        assertThrows(IOException.class, () -> ShazamSignature.decode(bytes));
        assertThrows(IOException.class, () -> ShazamSignature.fromUri("data:audio/other;base64,AAAA"));
    }

    @Test public void theHeaderIsSongRecsLayout() {
        ShazamSignature signature = ShazamSignature.of(bursts(1000, 2.5));
        byte[] bytes = signature.encode();
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xCAFE2580, in.getInt(0));
        CRC32 crc = new CRC32();
        crc.update(bytes, 8, bytes.length - 8);
        assertEquals((int) crc.getValue(), in.getInt(4));
        assertEquals(bytes.length - 48, in.getInt(8));
        assertEquals(0x94119C00, in.getInt(12));
        assertEquals("16 kHz is SampleRate 3, shifted by 27", 0x18000000, in.getInt(28));
        assertEquals("the samples plus 0.24 s of 16 kHz", 192000 + 3840, in.getInt(40));
        assertEquals(0x007C0000, in.getInt(44));
        assertEquals(0x40000000, in.getInt(48));
        assertEquals(bytes.length - 48, in.getInt(52));
        assertEquals("one band, 520 to 1450 Hz", 0x60030041, in.getInt(56));
        assertEquals(12000, signature.sampleMillis());
    }

    @Test public void toneBurstsArePeaksAtTheirPitch() {
        ShazamSignature low = ShazamSignature.of(bursts(1000, 2.5));
        assertTrue("a 1 kHz tone has peaks", low.peakCount() > 0);
        assertEquals("all of them between 520 and 1450 Hz", low.peakCount(), low.bands.get(1).size());
        for (ShazamSignature.Peak peak : low.bands.get(1)) {
            assertEquals(1000, peak.hertz(), 25);
        }

        ShazamSignature high = ShazamSignature.of(bursts(3000, 2.5));
        assertTrue(high.peakCount() > 0);
        assertEquals("a 3 kHz tone is in the 1450 to 3500 Hz band", high.peakCount(), high.bands.get(2).size());
        for (ShazamSignature.Peak peak : high.bands.get(2)) {
            assertEquals(3000, peak.hertz(), 25);
        }
    }

    @Test public void silenceHasNoPeaks() {
        assertEquals(0, ShazamSignature.of(new short[SongRecognition.CLIP_SAMPLES]).peakCount());
    }

    @Test public void aFreshFingerprintReadsBackAsItWasMade() throws Exception {
        // Bursts 2.5 s apart leave gaps of over 255 windows, which the format writes as a jump.
        ShazamSignature made = ShazamSignature.of(bursts(1000, 2.5));
        List<ShazamSignature.Peak> peaks = made.bands.get(1);
        assertTrue(peaks.size() > 1);
        assertTrue("a gap of 255 windows or more", peaks.get(1).pass - peaks.get(0).pass >= 255);

        ShazamSignature read = ShazamSignature.fromUri(made.uri());
        assertEquals(made.numberSamples, read.numberSamples);
        for (int band = 0; band < ShazamSignature.BANDS; band++) {
            assertEquals(made.bands.get(band).size(), read.bands.get(band).size());
            for (int i = 0; i < made.bands.get(band).size(); i++) {
                ShazamSignature.Peak a = made.bands.get(band).get(i);
                ShazamSignature.Peak b = read.bands.get(band).get(i);
                assertEquals(a.pass, b.pass);
                assertEquals(a.magnitude, b.magnitude);
                assertEquals(a.bin, b.bin);
            }
        }
    }

    /** Twelve seconds of short, smooth bursts of a tone, one every {@code everySeconds}, from 1 s in. */
    static short[] bursts(double hertz, double everySeconds) {
        int rate = ShazamSignature.SAMPLE_RATE;
        short[] samples = new short[SongRecognition.CLIP_SAMPLES];
        int length = rate / 10;
        for (double start = 1; start * rate + length < samples.length; start += everySeconds) {
            int at = (int) (start * rate);
            for (int i = 0; i < length; i++) {
                double envelope = 0.5 * (1 - Math.cos(2 * Math.PI * i / length));
                samples[at + i] = (short) Math.round(12000 * envelope * Math.sin(2 * Math.PI * hertz * i / rate));
            }
        }
        return samples;
    }
}
