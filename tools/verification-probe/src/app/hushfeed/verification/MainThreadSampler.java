package app.hushfeed.verification;

import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the main thread spends a few seconds, from its stack sampled on a background thread.
 *
 * A patched TikTok isn't debuggable, so neither the platform profiler nor simpleperf can attach,
 * and gfxinfo only says that frames were slow, not whose code made them slow. This samples the
 * main thread's stack every few milliseconds (ART suspends it briefly for each read) and reports:
 * how many samples found the thread busy rather than waiting for its next message, how many of
 * those had Hushfeed's code on the stack, grouped by the innermost Hushfeed method (where the time
 * went) and the outermost one (the hook TikTok called), and TikTok's own innermost frame for the
 * busy samples without Hushfeed. Start it right before the action under test:
 * {@code -e action mainsample -e ms 4000 -e every 5}.
 */
final class MainThreadSampler {
    private static final String TAG = "HushfeedProbe";
    private static final String HUSHFEED = "app.morphe.";
    private static volatile boolean running;

    private MainThreadSampler() {
    }

    static void start(Intent intent) {
        long duration = number(intent.getStringExtra("ms"), 4000);
        long every = Math.max(1, number(intent.getStringExtra("every"), 5));
        if (running) {
            Log.i(TAG, "failed mainsample: a run is still going");
            return;
        }
        running = true;
        Thread main = Looper.getMainLooper().getThread();
        new Thread(() -> {
            try {
                sample(main, duration, every);
            } catch (RuntimeException error) {
                Log.e(TAG, "failed mainsample", error);
            } finally {
                running = false;
            }
        }, "hushfeed-mainsample").start();
        Log.i(TAG, "ok mainsample started " + duration + "ms every " + every + "ms");
    }

    private static void sample(Thread main, long duration, long every) {
        Map<String, Integer> inner = new HashMap<>();
        Map<String, Integer> outer = new HashMap<>();
        Map<String, Integer> tiktok = new HashMap<>();
        int samples = 0, busy = 0, hushfeed = 0;
        long end = SystemClock.uptimeMillis() + duration;
        while (SystemClock.uptimeMillis() < end) {
            StackTraceElement[] stack = main.getStackTrace();
            samples++;
            if (!idle(stack)) {
                busy++;
                String innermost = null, outermost = null;
                for (StackTraceElement frame : stack) {
                    if (!frame.getClassName().startsWith(HUSHFEED)) continue;
                    String name = frame.getClassName() + "." + frame.getMethodName();
                    if (innermost == null) innermost = name;
                    outermost = name;
                }
                if (innermost != null) {
                    hushfeed++;
                    inner.merge(innermost, 1, Integer::sum);
                    outer.merge(outermost, 1, Integer::sum);
                } else {
                    String own = appFrame(stack);
                    if (own != null) tiktok.merge(own, 1, Integer::sum);
                }
            }
            SystemClock.sleep(every);
        }
        Log.i(TAG, "ok mainsample samples=" + samples + " busy=" + busy + " hushfeed=" + hushfeed);
        logLong("mainsample inner: ", top(inner, 30));
        logLong("mainsample entry: ", top(outer, 30));
        logLong("mainsample tiktok: ", top(tiktok, 20));
    }

    /** Waiting for the next message: the looper's poll is the top of the stack. */
    private static boolean idle(StackTraceElement[] stack) {
        return stack.length > 0 && "android.os.MessageQueue".equals(stack[0].getClassName())
                && "nativePollOnce".equals(stack[0].getMethodName());
    }

    /** The innermost frame outside the platform and its libraries: TikTok's own code. */
    private static String appFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("dalvik.")
                    || name.startsWith("libcore.") || name.startsWith("kotlin.") || name.startsWith("androidx.")
                    || name.startsWith("com.android.") || name.startsWith("sun.")) continue;
            return name + "." + frame.getMethodName();
        }
        return null;
    }

    private static String top(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            if (i > 0) out.append(", ");
            out.append(entries.get(i).getKey()).append('=').append(entries.get(i).getValue());
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** Logs a line in pieces of at most 3000 characters, since logcat cuts an entry at about 4 KB. */
    private static void logLong(String prefix, String line) {
        int start = 0;
        do {
            Log.i(TAG, prefix + (start == 0 ? "" : "... ") + line.substring(start, Math.min(line.length(), start + 3000)));
            start += 3000;
        } while (start < line.length());
    }

    private static long number(String text, long fallback) {
        if (text == null) return fallback;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
