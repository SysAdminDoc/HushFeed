/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static app.morphe.extension.tiktok.download.Mp4Fixture.*;
import static org.junit.Assert.*;

import app.morphe.extension.tiktok.download.PlayableVideo.Problem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The read a download gets before it reaches the gallery (#544), on files built box by box and on
 * a real DASH stream.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlayableVideoTest {
    private final List<File> files = new ArrayList<>();

    @After public void tearDown() {
        for (File file : files) file.delete();
    }

    @Test public void aVideoWithAPictureTrackPlays() throws Exception {
        assertNull(problem(playable()));
        assertNull("a picture track alone is a muted video", problem(movie(track("vide", "hvc1", 1))));
        // The moov after the media, as a file that was never made fast-start has it.
        assertNull(problem(concat(ftyp(), mdat(), box("moov", track("vide", "avc1", 2)))));
        // A 64-bit media box.
        byte[] large = concat(u32(1), ascii("mdat"), u32(0), u32(24), new byte[8]);
        assertNull(problem(concat(ftyp(), box("moov", track("vide", "avc1", 2)), large)));
        assertNull("the picture needn't be the first track",
                problem(movie(track("soun", "mp4a", 4), track("vide", "avc1", 2))));
    }

    @Test public void aRealDashStreamPlays() throws Exception {
        // TikTok's shape for a DASH picture stream: an empty moov with mvex, samples in moofs.
        byte[] stream;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("download/dash-video-only-90.mp4")) {
            assertNotNull(input);
            stream = input.readAllBytes();
        }
        assertNull(problem(stream));
    }

    @Test public void anEncryptedTrackIsCopyProtected() throws Exception {
        assertEquals(Problem.COPY_PROTECTED, problem(movie(track("vide", "encv", 3), track("soun", "enca", 3))));
        assertEquals("clear picture, encrypted sound", Problem.COPY_PROTECTED,
                problem(movie(track("vide", "avc1", 3), track("soun", "enca", 3))));
        assertEquals("a protected entry after a clear one", Problem.COPY_PROTECTED,
                problem(movie(trackWith("vide", 3, box("avc1", new byte[78]), box("encv", new byte[78])))));
        for (String kind : new String[]{"enct", "encs", "drmi", "drms"}) {
            assertEquals(kind, Problem.COPY_PROTECTED, problem(movie(track("vide", kind, 3))));
        }
        // Encrypted and fragmented, the way a DRM stream is served.
        byte[] fragmented = concat(ftyp(), box("moov", box("mvex", full("trex", new byte[20])),
                track("vide", "encv", 0)), box("moof", full("mfhd", u32(1))), mdat());
        assertEquals(Problem.COPY_PROTECTED, problem(fragmented));
    }

    @Test public void aFileWithNoPictureToPlayIsUnplayable() throws Exception {
        assertEquals("nothing", Problem.UNPLAYABLE, problem(new byte[0]));
        assertEquals("three bytes", Problem.UNPLAYABLE, problem(new byte[]{1, 2, 3}));
        assertEquals("a web page", Problem.UNPLAYABLE,
                problem("<html><body>Access denied</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("a file type box and no moov", Problem.UNPLAYABLE, problem(ftyp()));
        assertEquals("a box shorter than its header", Problem.UNPLAYABLE, problem(concat(ftyp(), u32(4), ascii("moov"))));
        assertEquals("sound only", Problem.UNPLAYABLE, problem(movie(track("soun", "mp4a", 3))));
        assertEquals("a picture track with no samples", Problem.UNPLAYABLE, problem(movie(track("vide", "avc1", 0))));
        assertEquals("a picture track with no sample entry", Problem.UNPLAYABLE, problem(movie(trackWith("vide", 3))));
        assertEquals("an empty moov", Problem.UNPLAYABLE, problem(movie()));
        // An init segment with no fragment after it holds no samples anywhere.
        byte[] init = concat(ftyp(), box("moov", box("mvex", full("trex", new byte[20])), track("vide", "avc1", 0)));
        assertEquals("an init segment alone", Problem.UNPLAYABLE, problem(init));
        // A moov cut off partway, as an interrupted download leaves it at the end of the file.
        byte[] whole = concat(ftyp(), mdat(), box("moov", track("vide", "avc1", 2)));
        assertEquals("a cut-off moov", Problem.UNPLAYABLE, problem(java.util.Arrays.copyOf(whole, whole.length - 10)));
        // A child box that claims more than its parent holds.
        byte[] trak = track("vide", "avc1", 2);
        byte[] broken = trak.clone();
        broken[8 + 3] = (byte) 0x7F;
        assertEquals("a box that overruns its parent", Problem.UNPLAYABLE, problem(movie(broken)));
    }

    @Test public void checkRefusesWithTheProblemAndPassesAVideo() throws Exception {
        PlayableVideo.check(write(playable()));
        PlayableVideo.Refused refused = assertThrows(PlayableVideo.Refused.class,
                () -> PlayableVideo.check(write(movie(track("vide", "encv", 3)))));
        assertEquals(Problem.COPY_PROTECTED, refused.problem);
        assertTrue("a save counts it as a file left out, logged as information",
                refused instanceof SaveProgress.Skip);
        refused = assertThrows(PlayableVideo.Refused.class, () -> PlayableVideo.check(write(ftyp())));
        assertEquals(Problem.UNPLAYABLE, refused.problem);
    }

    private Problem problem(byte[] bytes) throws IOException {
        return PlayableVideo.problemWith(write(bytes));
    }

    private File write(byte[] bytes) throws IOException {
        File file = File.createTempFile("playable-", ".mp4");
        files.add(file);
        Files.write(file.toPath(), bytes);
        return file;
    }
}
