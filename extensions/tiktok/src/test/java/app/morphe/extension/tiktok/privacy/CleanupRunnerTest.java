package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Failure;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Page;

import org.junit.Test;

/**
 * The removal loop behind Remove all reposts, Remove all likes and Clear Favorites (#332): one
 * item at a time with a person's pause before every request, every page until nothing new comes
 * back, and the first refusal, captcha, rate limit or network error ends the run with its count.
 */
public class CleanupRunnerTest {
    /** A list read by offset, as the Reposts tab is. Removing an item moves the rest up, unless it sticks. */
    private static final class Listing implements CleanupRunner.Source {
        final List<String> items = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        int pageSize = 20;
        /** A list whose removed items keep being listed, as a cached list does. */
        boolean sticky;
        String failOn;
        End failure = End.REFUSED;
        int fetchFailureAt = -1;
        RuntimeException unexpected;
        CleanupRunner.Request last;

        Listing(int count) {
            for (int index = 0; index < count; index++) items.add("v" + index);
        }

        @Override
        public Page fetch(long cursor) throws Failure {
            requests.add("fetch " + cursor);
            if (requests.size() - 1 == fetchFailureAt) throw new Failure(failure, "fetch refused");
            int from = (int) cursor;
            int to = Math.min(items.size(), from + pageSize);
            List<String> ids = from >= items.size() ? Collections.emptyList() : new ArrayList<>(items.subList(from, to));
            return new Page(ids, cursor + pageSize, to < items.size());
        }

        @Override
        public void remove(String id) throws Failure {
            requests.add("remove " + id);
            if (unexpected != null) throw unexpected;
            if (id.equals(failOn)) throw new Failure(failure, "removal refused");
            removed.add(id);
            if (!sticky) items.remove(id);
        }

        @Override
        public CleanupRunner.Request last() {
            return last;
        }
    }

    /** Records every wait instead of waiting, and runs {@link #during} inside the wait it names. */
    private static final class Clock implements CleanupRunner.Pacer {
        final List<Long> pauses = new ArrayList<>();
        int wakes;
        IntConsumer during = pause -> { };
        boolean interruptAt3;

        @Override
        public void pause(long ms) throws InterruptedException {
            pauses.add(ms);
            if (interruptAt3 && pauses.size() == 3) throw new InterruptedException("test");
            during.accept(pauses.size());
        }

        @Override
        public void wake() {
            wakes++;
        }
    }

    private static CleanupRunner runner(CleanupRunner.Source source, Clock clock, List<Integer> counts) {
        return new CleanupRunner(source, clock, new Random(332), counts::add);
    }

    @Test public void everyItemGoesOneAtATimeWithAPauseBeforeEveryRequestButTheFirst() {
        Listing listing = new Listing(45);
        Clock clock = new Clock();
        List<Integer> counts = new ArrayList<>();
        CleanupRunner runner = runner(listing, clock, counts);

        assertEquals(End.DONE, runner.run());
        assertEquals(45, runner.removed());
        assertTrue("left on the account: " + listing.items, listing.items.isEmpty());
        // An offset list moves the rest up after a removal, so the same page is read again.
        assertEquals(List.of("fetch 0", "fetch 0", "fetch 0", "fetch 0"),
                listing.requests.stream().filter(request -> request.startsWith("fetch")).toList());
        assertEquals("the first request went out at once and every later one waited",
                listing.requests.size() - 1, clock.pauses.size());
        for (long pause : clock.pauses) {
            assertTrue("a pause of " + pause + " ms", pause >= 1_500 && pause < 3_000);
        }
        assertTrue("the pauses were all the same, so the run reads as a script",
                clock.pauses.stream().distinct().count() > 10);
        List<Integer> expected = new ArrayList<>();
        for (int count = 1; count <= 45; count++) expected.add(count);
        assertEquals("each removal is counted as it lands", expected, counts);
    }

    @Test public void thePauseSpansOneAndAHalfToThreeSeconds() {
        Clock clock = new Clock();
        Random shortest = new Random() {
            @Override public double nextDouble() {
                return 0d;
            }
        };
        new CleanupRunner(new Listing(1), clock, shortest, null).run();
        assertEquals(Long.valueOf(1_500), clock.pauses.get(0));

        Clock longest = new Clock();
        Random top = new Random() {
            @Override public double nextDouble() {
                return Math.nextDown(1d);
            }
        };
        new CleanupRunner(new Listing(1), longest, top, null).run();
        assertTrue(longest.pauses.get(0) < 3_000 && longest.pauses.get(0) >= 2_999);
    }

    @Test public void itemsThatStayListedAreRemovedOnceAndTheRunMovesOn() {
        Listing listing = new Listing(45);
        listing.sticky = true;
        Clock clock = new Clock();

        assertEquals(End.DONE, runner(listing, clock, new ArrayList<>()).run());
        assertEquals(45, listing.removed.size());
        assertEquals("an item was sent twice", 45, listing.removed.stream().distinct().count());
        // Each page is read again after its removals, then the run steps past it.
        assertEquals(List.of("fetch 0", "fetch 0", "fetch 20", "fetch 20", "fetch 40", "fetch 40"),
                listing.requests.stream().filter(request -> request.startsWith("fetch")).toList());
    }

    @Test public void pagesThatBringNothingNewEndTheRunAfterTwenty() {
        CleanupRunner.Source endless = new CleanupRunner.Source() {
            int fetches;

            @Override public Page fetch(long cursor) {
                fetches++;
                return new Page(List.of("same"), cursor + 1, true);
            }

            @Override public void remove(String id) {
            }

            @Override public String toString() {
                return String.valueOf(fetches);
            }
        };
        CleanupRunner runner = runner(endless, new Clock(), new ArrayList<>());
        assertEquals(End.DONE, runner.run());
        assertEquals(1, runner.removed());
        assertEquals("one page with the item, then the stale ones",
                String.valueOf(1 + CleanupRunner.STALE_PAGES), endless.toString());
    }

    @Test public void aListThatAnswersTheSameCursorEnds() {
        List<String> requests = new ArrayList<>();
        CleanupRunner.Source stuck = new CleanupRunner.Source() {
            @Override public Page fetch(long cursor) {
                requests.add("fetch " + cursor);
                return new Page(Collections.emptyList(), cursor, true);
            }

            @Override public void remove(String id) {
                requests.add("remove " + id);
            }
        };
        assertEquals(End.DONE, runner(stuck, new Clock(), new ArrayList<>()).run());
        assertEquals(List.of("fetch 0"), requests);
    }

    @Test public void blankIdsAreNeverSent() {
        List<String> requests = new ArrayList<>();
        CleanupRunner.Source blanks = new CleanupRunner.Source() {
            @Override public Page fetch(long cursor) {
                requests.add("fetch " + cursor);
                return new Page(java.util.Arrays.asList(null, "", "v1"), cursor + 3, false);
            }

            @Override public void remove(String id) {
                requests.add("remove " + id);
            }
        };
        CleanupRunner runner = runner(blanks, new Clock(), new ArrayList<>());
        assertEquals(End.DONE, runner.run());
        assertEquals(List.of("fetch 0", "remove v1", "fetch 0"), requests);
        assertEquals(1, runner.removed());
    }

    @Test public void stopDuringAWaitSendsNothingMore() {
        Listing listing = new Listing(10);
        Clock clock = new Clock();
        CleanupRunner[] runner = new CleanupRunner[1];
        // The third wait comes before the third removal; Stop lands in it.
        clock.during = pause -> {
            if (pause == 3) runner[0].stop();
        };
        runner[0] = runner(listing, clock, new ArrayList<>());

        assertEquals(End.STOPPED, runner[0].run());
        assertEquals(2, runner[0].removed());
        assertEquals(List.of("fetch 0", "remove v0", "remove v1"), listing.requests);
        assertTrue(runner[0].stopRequested());
        assertEquals("Stop woke the wait", 1, clock.wakes);
    }

    @Test public void aLastRequestGoesOnceTheListRunsOutAndStopKeepsItBack() {
        List<String> sent = new ArrayList<>();
        Listing listing = new Listing(2);
        listing.last = () -> sent.add("last");
        Clock clock = new Clock();
        CleanupRunner runner = runner(listing, clock, new ArrayList<>());
        assertEquals(End.DONE, runner.run());
        assertEquals(List.of("last"), sent);
        assertEquals("a wait before each removal, the second read and the last request",
                4, clock.pauses.size());

        sent.clear();
        Listing again = new Listing(2);
        again.last = () -> sent.add("last");
        Clock stopping = new Clock();
        CleanupRunner[] stopped = new CleanupRunner[1];
        // Waits 1 and 2 come before the removals, 3 before the second read, 4 before the last.
        stopping.during = pause -> {
            if (pause == 4) stopped[0].stop();
        };
        stopped[0] = runner(again, stopping, new ArrayList<>());
        assertEquals(End.STOPPED, stopped[0].run());
        assertEquals(List.of(), sent);
        assertEquals(2, stopped[0].removed());

        Listing refused = new Listing(1);
        refused.last = () -> {
            throw new Failure(End.REFUSED, "no");
        };
        CleanupRunner refusedRun = runner(refused, new Clock(), new ArrayList<>());
        assertEquals(End.REFUSED, refusedRun.run());
        assertEquals(1, refusedRun.removed());
    }

    @Test public void stopBeforeTheRunSendsNothing() {
        Listing listing = new Listing(3);
        CleanupRunner runner = runner(listing, new Clock(), new ArrayList<>());
        runner.stop();
        assertEquals(End.STOPPED, runner.run());
        assertEquals(List.of(), listing.requests);
    }

    @Test public void anInterruptedWaitStopsTheRun() {
        Listing listing = new Listing(10);
        Clock clock = new Clock();
        clock.interruptAt3 = true;
        CleanupRunner runner = runner(listing, clock, new ArrayList<>());
        try {
            assertEquals(End.STOPPED, runner.run());
            assertEquals(2, runner.removed());
            assertTrue("the interrupt was swallowed", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void theFirstRefusalEndsTheRunWithItsCount() {
        for (End end : new End[]{End.REFUSED, End.NETWORK, End.UNSUPPORTED}) {
            Listing listing = new Listing(10);
            listing.failOn = "v3";
            listing.failure = end;
            CleanupRunner runner = runner(listing, new Clock(), new ArrayList<>());

            assertEquals(end, runner.run());
            assertEquals(3, runner.removed());
            assertEquals("nothing went out after the " + end, "remove v3",
                    listing.requests.get(listing.requests.size() - 1));
            assertEquals(List.of("v4", "v5", "v6", "v7", "v8", "v9"), listing.items.subList(1, 7));
        }
    }

    @Test public void aRefusedListReadEndsTheRun() {
        Listing listing = new Listing(30);
        listing.fetchFailureAt = 0;
        listing.failure = End.NETWORK;
        CleanupRunner runner = runner(listing, new Clock(), new ArrayList<>());
        assertEquals(End.NETWORK, runner.run());
        assertEquals(0, runner.removed());
        assertEquals(List.of("fetch 0"), listing.requests);
    }

    @Test public void aFailureAfterStopReadsAsStopped() {
        Listing listing = new Listing(10);
        listing.failOn = "v2";
        Clock clock = new Clock();
        CleanupRunner[] runner = new CleanupRunner[1];
        // Stop arrives while the third removal is already on its way and that request fails.
        CleanupRunner.Source source = new CleanupRunner.Source() {
            @Override public Page fetch(long cursor) throws Failure {
                return listing.fetch(cursor);
            }

            @Override public void remove(String id) throws Failure {
                if (id.equals("v2")) runner[0].stop();
                listing.remove(id);
            }
        };
        runner[0] = runner(source, clock, new ArrayList<>());
        assertEquals(End.STOPPED, runner[0].run());
        assertEquals(2, runner[0].removed());
    }

    @Test public void aBuildThatThrowsSomethingElseIsUnsupported() {
        Listing listing = new Listing(5);
        listing.unexpected = new IllegalStateException("a renamed model");
        CleanupRunner runner = runner(listing, new Clock(), new ArrayList<>());
        assertEquals(End.UNSUPPORTED, runner.run());
        assertEquals(0, runner.removed());
        assertFalse(runner.stopRequested());
    }

    @Test public void theLockPacerWaitsAndWakeCutsItShort() throws Exception {
        CleanupRunner.LockPacer pacer = new CleanupRunner.LockPacer();
        long start = System.nanoTime();
        pacer.pause(60);
        assertTrue("the wait was skipped", (System.nanoTime() - start) / 1_000_000 >= 50);

        Thread waiter = new Thread(() -> {
            try {
                pacer.pause(60_000);
            } catch (InterruptedException ignored) {
                // The join below fails the test if this is what ended the wait.
            }
        });
        waiter.start();
        Thread.sleep(50);
        pacer.wake();
        waiter.join(5_000);
        assertFalse("Stop didn't end the wait", waiter.isAlive());

        start = System.nanoTime();
        pacer.pause(60_000);
        assertTrue("a woken pacer waited again", (System.nanoTime() - start) / 1_000_000 < 1_000);
    }
}
