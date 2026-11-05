package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Android drops playback capture the app doesn't read within its buffer. A screen recording used
 * to read capture only when the AAC encoder had a free input buffer, on a below-normal thread, so a
 * busy phone lost sound and the saved sound ran short and out of step with the picture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ScreenAudioBacklogTest {
    /** {@code length} bytes numbered from {@code first}, so order shows. */
    private static byte[] pcm(int first, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (first + i);
        return bytes;
    }

    @Test
    public void everythingCapturedIsKeptWhileTheEncoderTakesNothing() {
        ScreenSource.PcmBacklog backlog = new ScreenSource.PcmBacklog(64 * 1024);
        for (int pass = 0; pass < 10; pass++) backlog.write(pcm(0, 5 * 1024), 0, 5 * 1024);
        assertEquals(50 * 1024, backlog.bytes());
        assertEquals(0, backlog.droppedFrames());
    }

    @Test
    public void theEncoderGetsTheSoundInCaptureOrderAcrossTheWrap() {
        ScreenSource.PcmBacklog backlog = new ScreenSource.PcmBacklog(1024);
        int written = 0, expected = 0;
        for (int pass = 0; pass < 40; pass++) {
            int length = 4 * (33 + pass % 5);
            backlog.write(pcm(written, length), 0, length);
            written += length;
            ByteBuffer input = ByteBuffer.allocate(4 * (29 + pass % 7));
            long at = backlog.take(input, input.capacity());
            assertEquals("each buffer is stamped with its place in the capture", expected / 4, at);
            assertEquals(0, input.position() % 4);
            input.flip();
            while (input.hasRemaining()) assertEquals((byte) expected++, input.get());
        }
        assertEquals(0, backlog.droppedFrames());
        assertTrue(expected > 0);
    }

    @Test
    public void aFullBacklogGivesUpItsOldestSoundButKeepsItOnTheClock() {
        ScreenSource.PcmBacklog backlog = new ScreenSource.PcmBacklog(4096);
        for (int written = 0; written < 4096 * 3; written += 512)
            backlog.write(pcm(written, 512), 0, 512);
        long dropped = backlog.droppedFrames();
        assertTrue(dropped > 0);
        assertEquals("every captured frame is either waiting or counted", 4096 * 3 / 4,
                backlog.bytes() / 4 + dropped);
        ByteBuffer input = ByteBuffer.allocate(4);
        assertEquals("the first sound left keeps its place in time", dropped,
                backlog.take(input, 4));
        assertEquals("the oldest sound left first", (byte) (dropped * 4), input.get(0));
    }

    @Test
    public void oneReadLargerThanTheBacklogKeepsItsNewestSound() {
        ScreenSource.PcmBacklog backlog = new ScreenSource.PcmBacklog(1024);
        backlog.write(pcm(0, 256), 0, 256);
        backlog.write(pcm(256, 1536), 0, 1536);
        assertEquals(1024, backlog.bytes());
        assertEquals(192, backlog.droppedFrames());
        ByteBuffer input = ByteBuffer.allocate(4);
        assertEquals(192, backlog.take(input, 4));
        assertEquals((byte) 768, input.get(0));
    }

    @Test
    public void takeStaysWithinTheEncoderInputAndWholeFrames() {
        ScreenSource.PcmBacklog backlog = new ScreenSource.PcmBacklog(4096);
        backlog.write(pcm(0, 400), 0, 400);
        ByteBuffer input = ByteBuffer.allocate(64);
        assertEquals(0, backlog.take(input, 1_000));
        assertEquals(64, input.position());
        ByteBuffer small = ByteBuffer.allocate(64);
        assertEquals(16, backlog.take(small, 38));
        assertEquals(36, small.position());
        assertEquals(300, backlog.bytes());
        backlog.dropAll();
        assertEquals(0, backlog.bytes());
        assertEquals(75, backlog.droppedFrames());
        ByteBuffer last = ByteBuffer.allocate(64);
        assertEquals("the end of the sound comes after everything given up", 100,
                backlog.take(last, 64));
        assertEquals(0, last.position());
    }

    /** Capture that hands out numbered sound one chunk per permit, then blocks like a real read. */
    private static final class Capture implements ScreenSource.PcmReader {
        final Semaphore ready = new Semaphore(0);
        volatile int failWith;
        int next;

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            try {
                if (!ready.tryAcquire(5, TimeUnit.SECONDS)) return 0;
            } catch (InterruptedException interrupted) {
                throw new IOException(interrupted);
            }
            if (failWith != 0) return failWith;
            for (int i = 0; i < length; i++) into[offset + i] = (byte) next++;
            return length;
        }
    }

    @Test
    public void theCaptureThreadReadsWithoutWaitingForTheEncoder() throws Exception {
        ScreenSource.PcmBacklog backlog = new ScreenSource.PcmBacklog(64 * 1024);
        Capture capture = new Capture();
        ScreenSource.CaptureReader reader = new ScreenSource.CaptureReader(capture, backlog, 4096);
        reader.start();
        capture.ready.release(6);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (backlog.bytes() < 6 * 4096 && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(6 * 4096, backlog.bytes());
        reader.finish();
        capture.ready.release();
        reader.join(5_000);
        assertFalse("finish ends the thread after its read in progress", reader.isAlive());
        assertNull(reader.failure());
        assertEquals("the read in progress is kept", 7 * 4096, backlog.bytes());
        ByteBuffer input = ByteBuffer.allocate(8);
        backlog.take(input, 8);
        assertEquals(0, input.get(0));
        assertEquals(7, input.get(7));
    }

    @Test
    public void theRecorderLeavesTheBackgroundWhileItRecordsAndGoesBackToSave() {
        int tid = android.os.Process.myTid();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        int before = ScreenSource.raisePriority();
        assertEquals(android.os.Process.THREAD_PRIORITY_DEFAULT,
                android.os.Process.getThreadPriority(tid));
        ScreenSource.restorePriority(before);
        assertEquals(android.os.Process.THREAD_PRIORITY_BACKGROUND,
                android.os.Process.getThreadPriority(tid));

        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        ScreenSource.restorePriority(ScreenSource.raisePriority());
        assertEquals("a thread already above normal is left alone",
                android.os.Process.THREAD_PRIORITY_DISPLAY,
                android.os.Process.getThreadPriority(tid));
    }

    @Test
    public void aFailedCaptureReadIsReportedToTheRecording() throws Exception {
        Capture capture = new Capture();
        capture.failWith = -3;
        ScreenSource.CaptureReader reader =
                new ScreenSource.CaptureReader(capture, new ScreenSource.PcmBacklog(4096), 4096);
        reader.start();
        capture.ready.release();
        reader.join(5_000);
        assertFalse(reader.isAlive());
        assertNotNull(reader.failure());
    }
}
