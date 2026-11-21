/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #482: the update task's two counts reach TikTok's loader marked as counting, so the loader
 * answers a list of the right length without decoding a video, and nothing else is marked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OfflineUnwatchedCountTest {
    @After
    public void reset() {
        OfflineUnwatchedCount.resetForTests();
    }

    /** The loader as the patch leaves it: counts while marked, otherwise the videos themselves. */
    private static OfflineUnwatchedCount.Host loader(int saved, int watched, List<?> videos) {
        return () -> OfflineUnwatchedCount.counting()
            ? OfflineUnwatchedCount.unwatched(saved, watched)
            : videos;
    }

    @Test
    public void theUpdateTasksAskGetsTheCountWithoutTheVideos() {
        List<String> videos = Arrays.asList("a", "b");
        OfflineUnwatchedCount.hostForTests = loader(5_000, 1_200, videos);

        List<?> counted = OfflineUnwatchedCount.countedUnwatched();

        assertEquals(3_800, counted.size());
        assertNull("the counted list holds nothing", counted.get(0));
        assertFalse("the mark is gone after the ask", OfflineUnwatchedCount.counting());
    }

    @Test
    public void everyOtherAskStillGetsTheVideos() {
        List<String> videos = Arrays.asList("a", "b");
        OfflineUnwatchedCount.Host host = loader(5_000, 1_200, videos);

        assertFalse(OfflineUnwatchedCount.counting());
        assertSame(videos, host.load());
    }

    @Test
    public void moreWatchedThanSavedCountsNone() {
        assertEquals(0, OfflineUnwatchedCount.unwatched(2, 5).size());
        assertEquals(0, OfflineUnwatchedCount.unwatched(0, 0).size());
    }

    @Test
    public void aLoaderThatThrowsLeavesNoMarkBehind() {
        OfflineUnwatchedCount.hostForTests = () -> {
            assertTrue(OfflineUnwatchedCount.counting());
            throw new IllegalStateException("database closed");
        };
        try {
            OfflineUnwatchedCount.countedUnwatched();
            fail("the loader's failure reaches the task as before");
        } catch (IllegalStateException expected) {
            // TikTok's own task handles a failed load the way it always did.
        }
        assertFalse(OfflineUnwatchedCount.counting());
    }

    @Test
    public void anotherThreadLoadingMeanwhileGetsTheVideos() throws Exception {
        List<String> videos = Arrays.asList("a", "b");
        OfflineUnwatchedCount.Host other = loader(5_000, 1_200, videos);
        AtomicBoolean otherGotVideos = new AtomicBoolean();
        OfflineUnwatchedCount.hostForTests = () -> {
            Thread thread = new Thread(() -> otherGotVideos.set(other.load() == videos));
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            return OfflineUnwatchedCount.unwatched(3, 1);
        };

        assertEquals(2, OfflineUnwatchedCount.countedUnwatched().size());
        assertTrue("the mark belongs to the counting thread alone", otherGotVideos.get());
    }
}
