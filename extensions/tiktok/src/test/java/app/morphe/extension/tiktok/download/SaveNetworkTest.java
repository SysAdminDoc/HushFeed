package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Looper;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * Save only on Wi-Fi: a network save on a metered or unknown network waits off the pool, with
 * no worker and no clock running, until Wi-Fi, a cancel, the switch going off or its bound. A
 * transfer that's under way stops at its next read once the network turns metered, and a frame
 * save, which never touches the network, runs anywhere.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class SaveNetworkTest {
    private final List<CountDownLatch> released = new ArrayList<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SaveNetwork.resetForTests();
        drain();
        ShadowToast.reset();
        Settings.DOWNLOAD_UNMETERED_ONLY.save(true);
        SaveNetwork.unmeteredForTests = false;
    }

    @After public void tearDown() throws Exception {
        for (CountDownLatch latch : released) latch.countDown();
        released.clear();
        for (MediaJobScheduler.Job job : MediaJobScheduler.parkedForTests()) job.cancel();
        Settings.DOWNLOAD_UNMETERED_ONLY.save(false);
        SaveNetwork.resetForTests();
        drain();
    }

    private static void drain() throws Exception {
        for (int wait = 0; wait < 250; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("the media pool never emptied");
    }

    private static void waitFor(AtomicInteger counter, int value) throws Exception {
        for (int wait = 0; wait < 500 && counter.get() < value; wait++) Thread.sleep(10);
        assertEquals(value, counter.get());
    }

    private static void onWifi() {
        SaveNetwork.unmeteredForTests = true;
        SaveNetwork.refresh();
    }

    @Test public void aSaveOnAMeteredNetworkWaitsOffThePoolAndGoesOnWifi() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.Job job = MediaJobScheduler.submit("video", "video 1", ran::incrementAndGet,
                ended::incrementAndGet);
        assertNotNull(job);
        assertTrue(job.parked());
        assertTrue(job.waiting());
        assertEquals("a save waiting for Wi-Fi took a place in the pool's line", 0, MediaJobScheduler.queuedJobs());
        assertEquals("a save waiting for Wi-Fi holds a worker", 0, MediaJobScheduler.runningJobs());
        assertEquals("Waiting for Wi-Fi", SaveQueue.detail(RuntimeEnvironment.getApplication(), job, 0));
        assertEquals("The last one is still waiting for Wi-Fi", MediaJobScheduler.busyMessage("video 1"));

        MediaJobScheduler.acknowledge(job, "Saving video", "Saving video");
        assertEquals("Saving video\nWaiting for Wi-Fi", ShadowToast.getTextOfLatestToast());

        Thread.sleep(100);
        assertEquals("a save ran on a metered network", 0, ran.get());
        onWifi();
        waitFor(ended, 1);
        assertEquals(1, ran.get());
        assertFalse(job.parked());
    }

    @Test public void aFrameSaveRunsOnAMeteredNetwork() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        AtomicReference<Boolean> gated = new AtomicReference<>();
        MediaJobScheduler.Job job = MediaJobScheduler.submitLocal("frame", null, () -> {
            gated.set(MediaTransport.gated());
            ran.incrementAndGet();
        }, null);
        assertNotNull(job);
        assertFalse(job.parked());
        waitFor(ran, 1);
        assertFalse("a frame save's thread was held to Wi-Fi", gated.get());
    }

    @Test public void cancelTakesASaveOutOfTheWaitForWifiOnce() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.Job job = MediaJobScheduler.submit("video", "video 2", ran::incrementAndGet,
                ended::incrementAndGet);
        assertTrue(job.parked());
        assertTrue(job.cancel());
        assertFalse(job.cancel());
        assertEquals(1, ended.get());
        assertTrue(MediaJobScheduler.parkedForTests().isEmpty());
        onWifi();
        Thread.sleep(100);
        assertEquals("a cancelled save ran once Wi-Fi came", 0, ran.get());
        drain();
    }

    @Test public void aSaveGivesUpAfterItsBound() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.clearEndedForTests();
        MediaJobScheduler.Job job = MediaJobScheduler.submit("video", "video 3", ran::incrementAndGet,
                ended::incrementAndGet);
        assertTrue(job.parked());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MediaJobScheduler.MAX_PARK_MS - 1000));
        assertEquals("it gave up early", 0, ended.get());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));
        assertEquals(1, ended.get());
        String message = "No Wi-Fi for 30 minutes. Save again on Wi-Fi.";
        assertEquals(message, ShadowToast.getTextOfLatestToast());
        assertEquals(message, MediaJobScheduler.ended().get(0).said);
        onWifi();
        Thread.sleep(100);
        assertEquals(0, ran.get());
        drain();
    }

    @Test public void turningTheSwitchOffLetsTheWaitingSavesGo() throws Exception {
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.Job job = MediaJobScheduler.submit("video", "video 4", () -> { }, ended::incrementAndGet);
        assertTrue(job.parked());
        Settings.DOWNLOAD_UNMETERED_ONLY.save(false);
        SaveQueue.networkSettingChanged();
        waitFor(ended, 1);
    }

    /** One in the pool's line when the network turns metered gives its worker back and waits again. */
    @Test public void aSaveInLineWhenWifiGoesWaitsAgainWithoutAWorker() throws Exception {
        SaveNetwork.unmeteredForTests = true;
        CountDownLatch hold = new CountDownLatch(1);
        released.add(hold);
        CountDownLatch started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            assertTrue(MediaJobScheduler.submit("hold " + index, () -> {
                started.countDown();
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        MediaJobScheduler.Job job = MediaJobScheduler.submit("video", "video 5", ran::incrementAndGet,
                ended::incrementAndGet);
        assertFalse(job.parked());
        assertEquals(1, MediaJobScheduler.queuedJobs());

        SaveNetwork.unmeteredForTests = false;
        SaveNetwork.refresh();
        hold.countDown();
        for (int wait = 0; wait < 250 && !job.parked(); wait++) Thread.sleep(20);
        assertTrue("the save ran on a metered network instead of waiting", job.parked());
        assertEquals(0, ran.get());
        for (int wait = 0; wait < 250 && MediaJobScheduler.runningJobs() > 0; wait++) Thread.sleep(20);
        assertEquals("a save waiting for Wi-Fi kept a worker", 0, MediaJobScheduler.runningJobs());

        onWifi();
        waitFor(ended, 1);
        assertEquals(1, ran.get());
    }

    /** A connection about to open on a metered network is refused before anything is resolved. */
    @Test public void aTransferStopsWhenTheNetworkTurnsMetered() throws Exception {
        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicReference<Throwable> midway = new AtomicReference<>();
        AtomicInteger firstByte = new AtomicInteger(-2);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        MediaTransport.Client client = MediaTransportFixtures.publicClient(url -> {
            opened.incrementAndGet();
            return MediaTransportFixtures.response(url, 200, new byte[]{1, 2, 3});
        });
        SaveNetwork.unmeteredForTests = true;
        MediaJobScheduler.submit("video", "video 6", () -> {
            try {
                MediaTransport.Response response = client.open("https://v16.tiktokcdn.com/media",
                        MediaBudget.deadline(), 1000, 1000, null, false);
                InputStream body = response.inputStream();
                firstByte.set(body.read());
                SaveNetwork.unmeteredForTests = false;
                try {
                    body.read();
                } catch (Throwable stop) {
                    midway.set(stop);
                }
                response.close();
                client.open("https://v16.tiktokcdn.com/media", MediaBudget.deadline(), 1000, 1000, null, false);
            } catch (Throwable stop) {
                refused.set(stop);
            }
        }, ended::incrementAndGet);
        waitFor(ended, 1);
        assertEquals(1, firstByte.get());
        assertStop(midway.get());
        assertStop(refused.get());
        assertEquals("the refused connection was opened", 1, opened.get());

        // Off a save's thread, as a share link resolves, nothing is held back.
        try (MediaTransport.Response response = client.open("https://v16.tiktokcdn.com/media",
                MediaBudget.deadline(), 1000, 1000, null, false)) {
            assertEquals(1, response.inputStream().read());
        }
    }

    private static void assertStop(Throwable thrown) {
        if (!(thrown instanceof MediaBudget.StopException)) fail("expected a network stop, got " + thrown);
        assertEquals(MediaBudget.StopException.Reason.NETWORK, ((MediaBudget.StopException) thrown).reason);
    }

    @Test public void aRunStoppedByTheNetworkSaysSo() {
        SaveProgress.Outcome outcome = new SaveProgress.Outcome(5, 2, 0, 3, SaveProgress.Stop.NETWORK);
        assertEquals("Saved 2 of 5. Save the rest on Wi-Fi.",
                SaveProgress.message(outcome, "all"));
    }

    @Test public void theNetworkAnswerFailsClosed() {
        SaveNetwork.resetForTests();
        // Robolectric's phone has no capabilities for its active network: unknown, so not unmetered.
        assertFalse(SaveNetwork.unmeteredNow());
        assertFalse(SaveNetwork.allowed());
        Settings.DOWNLOAD_UNMETERED_ONLY.save(false);
        assertTrue("the switch off still held a save back", SaveNetwork.allowed());
    }
}
