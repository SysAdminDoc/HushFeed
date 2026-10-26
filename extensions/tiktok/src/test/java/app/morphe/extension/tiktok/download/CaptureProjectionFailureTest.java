package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.*;
import android.media.projection.MediaProjection;
import android.os.Binder;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.live.ScreenCapture;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class CaptureProjectionFailureTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();

    @After
    public void clear() throws Exception {
        setPending(null);
        ScreenCapture.whenEnded(null);
    }

    @Test
    public void spoolSetupFailureReleasesOnlyTheProjectionCapturedByThisSource() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        MediaProjection projection = projection(stops);
        setPending(projection);
        ScreenSource source = source();
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        File impossible = new File(files.newFolder("not-a-file"), "missing-parent/source.flv");
        LiveRecorder recorder =
                new LiveRecorder(
                        new LiveRecorder.Target("", LiveRecorder.Transport.SCREEN, "", "", ""),
                        impossible,
                        source,
                        (file, target, start) -> {
                            throw new AssertionError("empty source was finalized");
                        },
                        result::set);
        recorder.start();
        assertTrue(recorder.join(5000));
        assertEquals(1, stops.get());
        assertNull(ScreenCapture.pending());
        assertEquals(LiveRecorder.Outcome.NOTHING_SAVED, result.get().outcome);
    }

    @Test
    public void staleSourceAbandonOrPumpCannotConsumeAnotherPendingOwner() throws Exception {
        AtomicInteger firstStops = new AtomicInteger(), otherStops = new AtomicInteger();
        MediaProjection first = projection(firstStops), other = projection(otherStops);
        setPending(first);
        ScreenSource source = source();
        setPending(other);
        source.abandon();
        assertSame(other, ScreenCapture.pending());
        assertEquals(0, firstStops.get());
        assertEquals(0, otherStops.get());
        try {
            source.pump((data, offset, count) -> {}, new ScreenSourceMuxerStartTest.Control());
            fail();
        } catch (IOException expected) {
        }
        assertSame(other, ScreenCapture.pending());
        assertEquals(0, otherStops.get());
        ScreenCapture.discardUnused(other);
        assertEquals(1, otherStops.get());
    }

    @Test
    public void expectedTakeIsOneShotAndRepeatedAbandonDoesNotStopTwice() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        MediaProjection projection = projection(stops);
        setPending(projection);
        assertSame(projection, ScreenCapture.take(projection));
        assertNull(ScreenCapture.take(projection));
        ScreenCapture.release(projection);
        assertEquals(1, stops.get());
        setPending(projection);
        ScreenSource source = source();
        source.abandon();
        source.abandon();
        assertEquals(2, stops.get());
        assertNull(ScreenCapture.pending());
    }

    ScreenSource source() {
        var app = RuntimeEnvironment.getApplication();
        return new ScreenSource(
                app,
                app.getResources().getDisplayMetrics(),
                false,
                (out, audio) -> {
                    throw new AssertionError("encoder setup reached unexpectedly");
                });
    }

    static void setPending(MediaProjection projection) throws Exception {
        Field field = ScreenCapture.class.getDeclaredField("projection");
        field.setAccessible(true);
        field.set(null, projection);
    }

    static MediaProjection projection(AtomicInteger stops) throws Exception {
        Class<?> service = Class.forName("android.media.projection.IMediaProjection");
        Binder binder = new Binder();
        Object implementation =
                Proxy.newProxyInstance(
                        service.getClassLoader(),
                        new Class<?>[] {service},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "start":
                                    return null;
                                case "stop":
                                    stops.incrementAndGet();
                                    return null;
                                case "asBinder":
                                    return binder;
                                case "canProjectAudio":
                                    return true;
                                case "equals":
                                    return proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "Projection ownership fixture";
                                default:
                                    throw new AssertionError(method.getName());
                            }
                        });
        Constructor<MediaProjection> constructor =
                MediaProjection.class.getDeclaredConstructor(Context.class, service);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication(), implementation);
    }
}
