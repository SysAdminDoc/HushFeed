package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;

public class CaptureSpaceTest {
    static final long FREE = 1024L * 1024 * 1024;

    @Test
    public void captureCountsRetainedActiveAndBothFutureCopiesBeforeWriting() throws Exception {
        CaptureSpace budget = new CaptureSpace(1000);
        CaptureSpace.Footprint state = new CaptureSpace.Footprint(100, 20, 30, 400, 80, 2);
        CaptureSpace.Grant grant = budget.sourceWrite(state, 50, 30, 1, FREE);
        long bound = CaptureSpace.remuxBound(110, 3);
        assertEquals(550, grant.actualOwnedBytes);
        assertEquals(400 + 150 + 2 * bound, grant.projectedOwnedBytes);
        assertEquals(50 + (bound - 20) + (bound - 30), grant.additionalBytes);
        rejects(() -> budget.sourceWrite(state, 50, 30, 1, grant.requiredFreeBytes - 1), false);
        budget.sourceWrite(state, 50, 30, 1, grant.requiredFreeBytes);
    }

    @Test
    public void sourceLimitIsCheckedBeforeAWholeTagAndNoGrantMutatesAccounting() throws Exception {
        CaptureSpace budget = new CaptureSpace(100);
        CaptureSpace.Footprint state = new CaptureSpace.Footprint(80, 0, 0, 500, 50, 1);
        rejects(() -> budget.sourceWrite(state, 21, 10, 1, FREE), true);
        assertEquals(80, state.source);
        assertEquals(500, state.retained);
    }

    @Test
    public void nativeRemuxReservesItsRemainingOutputAndPublicationCopy() throws Exception {
        CaptureSpace.Footprint state = new CaptureSpace.Footprint(1000, 100, 0, 500, 800, 10);
        CaptureSpace budget = new CaptureSpace();
        long bound = CaptureSpace.remuxBound(800, 10);
        CaptureSpace.Grant grant = budget.nativeRemux(state, FREE);
        assertEquals(1600, grant.actualOwnedBytes);
        assertEquals(1500 + 2 * bound, grant.projectedOwnedBytes);
        assertEquals(2 * bound - 100, grant.additionalBytes);
        rejects(() -> budget.nativeRemux(state, grant.requiredFreeBytes - 1), false);
    }

    @Test
    public void publicationCountsActualCopiesAndReservesOnlyTheRemainingExactCopyPlusHeadroom()
            throws Exception {
        CaptureSpace budget = new CaptureSpace();
        CaptureSpace.Footprint state = new CaptureSpace.Footprint(1000, 900, 200, 500, 800, 10);
        CaptureSpace.Grant grant = budget.publicationWrite(state, 900, 200, 100, FREE);
        assertEquals(2600, grant.actualOwnedBytes);
        assertEquals(3300, grant.projectedOwnedBytes);
        assertEquals(700 + MediaBudget.PUBLISH_OVERHEAD_BYTES, grant.additionalBytes);
        rejects(
                () -> budget.publicationWrite(state, 900, 200, 100, grant.requiredFreeBytes - 1),
                false);
    }

    @Test
    public void tinySamplesCannotCreateUnboundedNativeSampleTables() throws Exception {
        CaptureSpace budget = new CaptureSpace();
        CaptureSpace.Footprint state =
                new CaptureSpace.Footprint(1000, 0, 0, 0, 500, CaptureSpace.MAX_SAMPLES);
        rejects(() -> budget.sourceWrite(state, 20, 4, 1, Long.MAX_VALUE), true);
        budget.sourceWrite(state, 20, 0, 0, Long.MAX_VALUE);
        CaptureSpace.Footprint older =
                new CaptureSpace.Footprint(1000, 0, 0, 0, 500, CaptureSpace.MAX_SAMPLES + 1);
        rejects(() -> budget.nativeRemux(older, Long.MAX_VALUE), true);
        assertEquals(CaptureSpace.MAX_SAMPLES + 1, older.samples);
    }

    @Test
    public void unknownFreeSpaceAndArithmeticOverflowFailClosed() throws Exception {
        CaptureSpace budget = new CaptureSpace();
        CaptureSpace.Footprint state = new CaptureSpace.Footprint(0, 0, 0, 0, 0, 0);
        rejects(() -> budget.sourceWrite(state, 13, 0, 0, 0), false);
        try {
            CaptureSpace.remuxBound(Long.MAX_VALUE, Long.MAX_VALUE);
            fail();
        } catch (IOException expected) {
        }
        try {
            budget.publicationWrite(state, 10, 0, 11, FREE);
            fail();
        } catch (IOException expected) {
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    private static void rejects(Work work, boolean size) throws Exception {
        try {
            work.run();
            fail("unsafe write admitted");
        } catch (CaptureSpace.Limit expected) {
            assertEquals(size, expected.size);
        }
    }
}
