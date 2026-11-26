/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a save ends as a video that plays (#544).
 *
 * <p>A subscriber-only video was saved as a file that played black with no sound. TikTok marks
 * such posts in its own model: Aweme.isSubOnlyVideo (is_sub_only_video) for a post only the
 * creator's subscribers can watch, and Video.enableIntertrustDrm (is_drm_source) for a stream
 * encrypted for the player's Intertrust DRM, whose key the player asks for with
 * Video.drmTokenAuth (token_auth). Either marker refuses the save before anything is fetched.
 * Nothing here reads the token or asks for a key: those videos stay in TikTok.
 *
 * <p>A download that comes down anyway is read before it reaches the gallery. Its moov has to
 * hold a picture track with samples, and no track may have a protected sample entry (encv, enca
 * and the rest, ISO 23001-7). A file that fails is left out of the save with a word saying why,
 * and the caller deletes it with its other temporary files.
 */
final class PlayableVideo {
    private PlayableVideo() {}

    enum Problem { SUBSCRIBER_ONLY, COPY_PROTECTED, UNPLAYABLE }

    /** A file left out of the gallery. The save counts it as skipped and logs it as information. */
    static final class Refused extends SaveProgress.Skip {
        final Problem problem;

        Refused(Problem problem, String why) {
            super(why);
            this.problem = problem;
        }
    }

    private static final int MOOV = Mp4Tags.fourCc("moov"), MOOF = Mp4Tags.fourCc("moof"),
            MVEX = Mp4Tags.fourCc("mvex"), TRAK = Mp4Tags.fourCc("trak"), MDIA = Mp4Tags.fourCc("mdia"),
            HDLR = Mp4Tags.fourCc("hdlr"), MINF = Mp4Tags.fourCc("minf"), STBL = Mp4Tags.fourCc("stbl"),
            STSD = Mp4Tags.fourCc("stsd"), STSZ = Mp4Tags.fourCc("stsz"), STZ2 = Mp4Tags.fourCc("stz2"),
            VIDE = Mp4Tags.fourCc("vide");

    /** Sample entries a track is encrypted under: CENC's four and the two of Apple's FairPlay. */
    private static final int[] PROTECTED = {Mp4Tags.fourCc("encv"), Mp4Tags.fourCc("enca"),
            Mp4Tags.fourCc("enct"), Mp4Tags.fourCc("encs"), Mp4Tags.fourCc("drmi"), Mp4Tags.fourCc("drms")};

    /** Why TikTok's own model keeps this post's video in TikTok, or null when nothing does. */
    static Problem marked(Object aweme) {
        if (aweme == null) return null;
        if (Boolean.TRUE.equals(Reflect.property(aweme, "isSubOnlyVideo", "isSubOnlyVideo"))) {
            return Problem.SUBSCRIBER_ONLY;
        }
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video != null && Boolean.TRUE.equals(Reflect.property(video, "enableIntertrustDrm", "enableIntertrustDrm"))) {
            return Problem.COPY_PROTECTED;
        }
        return null;
    }

    /**
     * Refuses a post TikTok marks as subscriber-only or DRM-sourced, before anything is fetched,
     * and says why. True when it was refused. That is the whole save: TikTok's own download would
     * fetch the same locked stream.
     */
    static boolean refusedUpFront(Object aweme) {
        Problem problem = marked(aweme);
        if (problem == null) return false;
        String id = Reflect.string(aweme, "getAid", "aid");
        Logger.printInfo(() -> "Not saving " + id + ": TikTok marks it "
                + (problem == Problem.SUBSCRIBER_ONLY ? "Aweme.isSubOnlyVideo" : "Video.enableIntertrustDrm"));
        tell(problem);
        return true;
    }

    /** The same refusal inside a save that counts its files, where it is one file left out. */
    static void checkMarkers(Object aweme) throws Refused {
        Problem problem = marked(aweme);
        if (problem == null) return;
        throw new Refused(problem, problem == Problem.SUBSCRIBER_ONLY
                ? "TikTok marks the post Aweme.isSubOnlyVideo"
                : "TikTok marks the video Video.enableIntertrustDrm");
    }

    /** The toast for a refusal. */
    static void tell(Problem problem) {
        Utils.showToastLong(problem == Problem.SUBSCRIBER_ONLY ? L10n.t("Subscriber-only posts can't be saved")
                : problem == Problem.COPY_PROTECTED ? L10n.t("Copy-protected videos can't be saved")
                : L10n.t("Not saved. The file wasn't a playable video."));
    }

    /** Throws {@link Refused} when the downloaded {@code file} isn't a video that plays. */
    static void check(File file) throws IOException {
        Problem problem = problemWith(file);
        if (problem == null) return;
        throw new Refused(problem, problem == Problem.COPY_PROTECTED
                ? "The download is encrypted, so it was left out"
                : "The download has no picture track that plays, so it was left out");
    }

    /**
     * What keeps {@code file} from playing as a video, or null when it has a picture track with
     * samples and nothing encrypted. A moov too large to read here is left to the player.
     */
    static Problem problemWith(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long position = 0, moovAt = -1, moovSize = 0;
            int moovHeader = 8;
            boolean fragments = false;
            while (length - position >= 8) {
                input.seek(position);
                long size = input.readInt() & 0xFFFFFFFFL;
                int type = input.readInt();
                int header = 8;
                if (size == 1) {
                    if (length - position < 16) break;
                    size = input.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = length - position;
                }
                // Not a box at all: a web page, or bytes that were never an MP4.
                if (size < header) return Problem.UNPLAYABLE;
                if (type == MOOV && moovAt < 0) {
                    moovAt = position;
                    moovSize = size;
                    moovHeader = header;
                }
                if (type == MOOF) fragments = true;
                // A last box that runs past the end is a cut-off download, read as far as it goes.
                if (size > length - position) break;
                position += size;
            }
            if (moovAt < 0 || moovSize > length - moovAt) return Problem.UNPLAYABLE;
            if (moovSize > Mp4Tags.MAX_MOOV) return null;
            byte[] moov = new byte[(int) moovSize];
            input.seek(moovAt);
            input.readFully(moov);
            return problemIn(moov, moovHeader, fragments);
        }
    }

    private static Problem problemIn(byte[] moov, int body, boolean fragments) {
        List<int[]> boxes = children(moov, body, moov.length);
        if (boxes == null) return Problem.UNPLAYABLE;
        // A fragmented file keeps its samples in the moofs after the moov, so its sample tables
        // are empty; an init segment with no moof after it has none anywhere.
        boolean fragmented = child(moov, boxes, MVEX) != null;
        boolean picture = false;
        for (int[] trak : boxes) {
            if (trak[0] != TRAK) continue;
            List<int[]> parts = children(moov, trak[1], trak[2]);
            int[] mdia = parts == null ? null : child(moov, parts, MDIA);
            List<int[]> media = mdia == null ? null : children(moov, mdia[1], mdia[2]);
            if (media == null) return Problem.UNPLAYABLE;
            int[] hdlr = child(moov, media, HDLR);
            int handler = hdlr != null && hdlr[2] - hdlr[1] >= 12 ? readInt(moov, hdlr[1] + 8) : 0;
            int[] minf = child(moov, media, MINF);
            List<int[]> info = minf == null ? null : children(moov, minf[1], minf[2]);
            int[] stbl = info == null ? null : child(moov, info, STBL);
            List<int[]> table = stbl == null ? null : children(moov, stbl[1], stbl[2]);
            if (table == null) return Problem.UNPLAYABLE;
            int[] stsd = child(moov, table, STSD);
            List<int[]> entries = stsd == null || stsd[2] - stsd[1] < 8 ? null : children(moov, stsd[1] + 8, stsd[2]);
            if (entries == null) return Problem.UNPLAYABLE;
            for (int[] entry : entries) {
                for (int kind : PROTECTED) if (entry[0] == kind) return Problem.COPY_PROTECTED;
            }
            if (handler != VIDE || entries.isEmpty()) continue;
            int[] sizes = child(moov, table, STSZ);
            if (sizes == null) sizes = child(moov, table, STZ2);
            long samples = sizes != null && sizes[2] - sizes[1] >= 12 ? readInt(moov, sizes[1] + 8) & 0xFFFFFFFFL : 0;
            if (samples > 0 || (fragmented && fragments)) picture = true;
        }
        return picture ? null : Problem.UNPLAYABLE;
    }

    private static int[] child(byte[] data, List<int[]> boxes, int type) {
        for (int[] box : boxes) if (box[0] == type) return box;
        return null;
    }

    /** The boxes in data[start, end) as {type, body start, end}, or null when one doesn't fit. */
    private static List<int[]> children(byte[] data, int start, int end) {
        List<int[]> boxes = new ArrayList<>();
        int at = start;
        while (end - at >= 8) {
            long size = readInt(data, at) & 0xFFFFFFFFL;
            int header = 8;
            if (size == 1) {
                if (end - at < 16) return null;
                size = ((readInt(data, at + 8) & 0xFFFFFFFFL) << 32) | (readInt(data, at + 12) & 0xFFFFFFFFL);
                header = 16;
            } else if (size == 0) {
                size = end - at;
            }
            if (size < header || size > end - at) return null;
            boxes.add(new int[]{readInt(data, at + 4), at + header, at + (int) size});
            at += (int) size;
        }
        return boxes;
    }

    private static int readInt(byte[] data, int at) {
        return (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8 | (data[at + 3] & 0xFF);
    }
}
