package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;

/** The real API 29 capture rules must exclude playback belonging to other applications. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ScreenSourceAudioCaptureTest {
    private int originalUid;

    @Before public void rememberUid() {
        originalUid = Process.myUid();
    }

    @After public void restoreUid() {
        ShadowProcess.setUid(originalUid);
    }

    @Test public void captureUsesTheCurrentUidAndKeepsTheSupportedUsages() throws Exception {
        ShadowProcess.setUid(10042);
        MediaProjection projection = audioProjection();
        AudioPlaybackCaptureConfiguration capture = ScreenSource.captureConfiguration(projection);

        assertSame(projection, capture.getMediaProjection());
        assertArrayEquals(new int[]{Process.myUid()}, capture.getMatchingUids());
        int[] usages = capture.getMatchingUsages();
        Arrays.sort(usages);
        assertArrayEquals(new int[]{AudioAttributes.USAGE_UNKNOWN, AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME}, usages);
        assertArrayEquals(new int[0], capture.getExcludeUids());
        assertArrayEquals(new int[0], capture.getExcludeUsages());
    }

    @Test public void anotherInstallationUsesItsOwnUidWithoutKeepingThePreviousOne() throws Exception {
        MediaProjection projection = audioProjection();
        ShadowProcess.setUid(10042);
        AudioPlaybackCaptureConfiguration first = ScreenSource.captureConfiguration(projection);
        ShadowProcess.setUid(10084);
        AudioPlaybackCaptureConfiguration second = ScreenSource.captureConfiguration(projection);

        assertArrayEquals(new int[]{10042}, first.getMatchingUids());
        assertArrayEquals(new int[]{Process.myUid()}, second.getMatchingUids());
    }

    /** The framework constructor starts its binder; the configuration builder checks audio consent. */
    static MediaProjection audioProjection() throws Exception {
        Class<?> service = Class.forName("android.media.projection.IMediaProjection");
        Binder binder = new Binder();
        Object implementation = Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "start": return null;
                        case "canProjectAudio": return true;
                        case "asBinder": return binder;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "Audio projection test service";
                        default: throw new AssertionError("Unexpected projection call: " + method.getName());
                    }
                });
        Constructor<MediaProjection> constructor = MediaProjection.class.getDeclaredConstructor(Context.class, service);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication(), implementation);
    }
}
