/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A TikTok data export's liked and favorited videos, in every layout TikTok has used: the reviewed
 * 2024-12 file (Your Activity, Like List, lowercase keys, an empty list written as null) and the
 * ones tiktok-favorites-archiver reads (Activity or Likes and Favorites, Liked Videos or Favorites,
 * capitalized keys), plain or in the zip TikTok sends.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class LikesExportTest {
    private static final String A = "7200255970008861998";
    private static final String B = "7424885637381131563";
    private static final String C = "7446620055594356014";

    @After public void tearDown() {
        LikesExport.maxBytesForTests = -1;
    }

    private static LikesExport.Videos read(String json) throws IOException {
        return LikesExport.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String link(String id) {
        return "https://www.tiktokv.com/share/video/" + id + "/";
    }

    /** The reviewed export's shape, with the watch history around it as the real file has it. */
    @Test public void readsTheReviewedExportsLikeList() throws IOException {
        LikesExport.Videos videos = read("{\"Ads and data\":{\"Ad Interests\":{\"AdInterestCategories\":\"\"}},"
                + "\"Your Activity\":{"
                + "\"Favorite Videos\":{\"App\":1,\"FavoriteVideoList\":null},"
                + "\"Like List\":{\"App\":1,\"ItemFavoriteList\":["
                + "{\"date\":\"2024-12-15 19:03:14\",\"link\":\"" + link(A) + "\"},"
                + "{\"date\":\"2024-12-14 10:00:00\",\"link\":\"" + link(B) + "\"}]},"
                + "\"Watch History\":{\"VideoList\":[{\"Date\":\"2024-12-15 18:43:41\",\"Link\":\"" + link(C) + "\"}]}}}");
        assertEquals(Arrays.asList(A, B), videos.liked);
        assertEquals(Collections.emptyList(), videos.favorites);
        assertEquals(0, videos.skipped);
    }

    @Test public void readsTheOtherLayoutsTikTokHasUsed() throws IOException {
        LikesExport.Videos favorites = read("{\"Likes and Favorites\":{\"Favorite Videos\":{\"FavoriteVideoList\":["
                + "{\"Date\":\"2025-01-02 03:04:05\",\"Link\":\"https://www.tiktok.com/@someone/video/" + C + "\"}]},"
                + "\"Like List\":{\"ItemFavoriteList\":[{\"Date\":\"x\",\"Link\":\"" + link(A) + "\"}]}}}");
        assertEquals(Collections.singletonList(A), favorites.liked);
        assertEquals(Collections.singletonList(C), favorites.favorites);

        LikesExport.Videos activity = read("{\"Activity\":{\"Liked Videos\":{\"LikedVideoList\":["
                + "{\"Link\":\"" + link(B) + "\"}]},\"Favorites\":{\"FavoriteVideoList\":[{\"Link\":\"" + link(A) + "\"}]}}}");
        assertEquals(Collections.singletonList(B), activity.liked);
        assertEquals(Collections.singletonList(A), activity.favorites);
    }

    @Test public void keepsEachVideoOnceAndCountsLinksThatArentTikTokVideos() throws IOException {
        LikesExport.Videos videos = read("{\"Your Activity\":{\"Like List\":{\"ItemFavoriteList\":["
                + "{\"link\":\"" + link(A) + "\"},{\"link\":\"" + link(A) + "\"},"
                + "{\"link\":\"https://evil.example/share/video/" + B + "/\"},"
                + "{\"link\":\"http://www.tiktok.com/share/video/" + B + "/\"},"
                + "{\"link\":42},{\"date\":\"2024-01-01 00:00:00\"},\"loose\","
                + "{\"link\":\"" + link(C) + "\"}]}}}");
        assertEquals(Arrays.asList(A, C), videos.liked);
        assertEquals(5, videos.skipped);
    }

    @Test public void pickPutsLikesFirstAndKeepsEachVideoOnce() throws IOException {
        LikesExport.Videos videos = read("{\"Your Activity\":{"
                + "\"Like List\":{\"ItemFavoriteList\":[{\"link\":\"" + link(A) + "\"},{\"link\":\"" + link(B) + "\"}]},"
                + "\"Favorite Videos\":{\"FavoriteVideoList\":[{\"Link\":\"" + link(C) + "\"},{\"Link\":\"" + link(A) + "\"}]}}}");
        assertEquals(Arrays.asList(A, B, C), videos.pick(true, true));
        assertEquals(Arrays.asList(C, A), videos.pick(false, true));
        assertEquals(Collections.emptyList(), videos.pick(false, false));
    }

    @Test public void readsTheJsonInsideTheZipTikTokSends() throws IOException {
        String json = "{\"Your Activity\":{\"Like List\":{\"ItemFavoriteList\":[{\"link\":\"" + link(B) + "\"}]}}}";
        byte[] zip = zip("TikTok_Data_1734300000/readme.txt", "not this one",
                "TikTok_Data_1734300000/user_data_tiktok.json", json);
        LikesExport.Videos videos = LikesExport.read(new ByteArrayInputStream(zip));
        assertEquals(Collections.singletonList(B), videos.liked);

        byte[] other = zip("photos/one.jpg", "jpeg");
        assertEquals(LikesExport.Reason.UNSUPPORTED, rejected(other));
    }

    @Test public void aByteOrderMarkIsSkipped() throws IOException {
        byte[] body = ("{\"Your Activity\":{\"Like List\":{\"ItemFavoriteList\":[{\"link\":\"" + link(A) + "\"}]}}}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] marked = new byte[body.length + 3];
        marked[0] = (byte) 0xef;
        marked[1] = (byte) 0xbb;
        marked[2] = (byte) 0xbf;
        System.arraycopy(body, 0, marked, 3, body.length);
        assertEquals(Collections.singletonList(A), LikesExport.read(new ByteArrayInputStream(marked)).liked);
    }

    @Test public void aFileThatIsntAnExportIsRefusedWithItsReason() {
        assertEquals(LikesExport.Reason.UNSUPPORTED, rejected("{\"Your Activity\":{\"Watch History\":{\"VideoList\":[]}}}"));
        assertEquals(LikesExport.Reason.UNSUPPORTED, rejected("{}"));
        assertEquals(LikesExport.Reason.DAMAGED, rejected("{\"Your Activity\":{\"Like List\":{\"ItemFavoriteList\":[{\"link\":"));
        assertEquals(LikesExport.Reason.DAMAGED, rejected("[1, 2, 3]"));
        assertEquals(LikesExport.Reason.DAMAGED, rejected(new byte[]{'{', '"', (byte) 0xc3, (byte) 0x28, '"', ':', '1', '}'}));
        assertThrows(LikesExport.Rejected.class, () -> LikesExport.read(null));
    }

    @Test public void aFileOverTheCapIsRefused() {
        LikesExport.maxBytesForTests = 64;
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 10; i++) rows.append(i == 0 ? "" : ",").append("{\"link\":\"").append(link(A)).append("\"}");
        assertEquals(LikesExport.Reason.TOO_LARGE,
                rejected("{\"Your Activity\":{\"Like List\":{\"ItemFavoriteList\":[" + rows + "]}}}"));
    }

    @Test public void aWholeAccountsExportWellPastTheOldTwoMegabyteCapReads() throws IOException {
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            history.append(i == 0 ? "" : ",").append("{\"Date\":\"2024-12-15 18:43:41\",\"Link\":\"").append(link(C)).append("\"}");
        }
        String json = "{\"Your Activity\":{\"Watch History\":{\"VideoList\":[" + history + "]},"
                + "\"Like List\":{\"ItemFavoriteList\":[{\"link\":\"" + link(A) + "\"}]}}}";
        assertTrue("the file isn't over 2 MB", json.length() > 2 * 1024 * 1024);
        assertEquals(Collections.singletonList(A), read(json).liked);
    }

    private static LikesExport.Reason rejected(String json) {
        return rejected(json.getBytes(StandardCharsets.UTF_8));
    }

    private static LikesExport.Reason rejected(byte[] bytes) {
        LikesExport.Rejected refused = assertThrows(LikesExport.Rejected.class,
                () -> LikesExport.read(new ByteArrayInputStream(bytes)));
        return refused.reason;
    }

    private static byte[] zip(String... namesAndBodies) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndBodies.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndBodies[i]));
                zip.write(namesAndBodies[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
