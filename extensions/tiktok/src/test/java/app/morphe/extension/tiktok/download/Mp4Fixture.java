/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small MP4 files built box by box (ISO 14496-12), for the saves that read a download's tracks
 * before it reaches the gallery (#544). Only the boxes that check reads are filled in: a track's
 * handler, its sample entries and its sample count.
 */
final class Mp4Fixture {
    private Mp4Fixture() {}

    /** A picture track and a sound track, both with samples and neither encrypted. */
    static byte[] playable() {
        return movie(track("vide", "avc1", 3), track("soun", "mp4a", 3));
    }

    /** ftyp, a moov holding {@code tracks}, then an mdat. */
    static byte[] movie(byte[]... tracks) {
        return concat(ftyp(), box("moov", tracks), mdat());
    }

    static byte[] ftyp() {
        return box("ftyp", ascii("isom"), u32(512), ascii("isom"), ascii("mp41"));
    }

    static byte[] mdat() {
        return box("mdat", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
    }

    /** One track: its handler ("vide", "soun"), its first sample entry ("avc1", "encv") and how many samples. */
    static byte[] track(String handler, String entry, int samples) {
        return trackWith(handler, samples, box(entry, new byte[78]));
    }

    static byte[] trackWith(String handler, int samples, byte[]... entries) {
        byte[] hdlr = full("hdlr", u32(0), ascii(handler), new byte[12], ascii("fixture\0"));
        byte[] stsd = full("stsd", u32(entries.length), concat(entries));
        byte[][] sizes = new byte[samples][];
        for (int i = 0; i < samples; i++) sizes[i] = u32(100 + i);
        byte[] stsz = full("stsz", u32(0), u32(samples), concat(sizes));
        return box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd, stsz))));
    }

    static byte[] box(String type, byte[]... parts) {
        byte[] body = concat(parts);
        return concat(u32(8 + body.length), ascii(type), body);
    }

    /** A full box: version 0 and no flags ahead of the body. */
    static byte[] full(String type, byte[]... parts) {
        return box(type, u32(0), concat(parts));
    }

    static byte[] u32(long value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
