package app.hushfeed.verification;

import android.content.Intent;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * TikTok's player log, which a release build keeps silent. TTVideoEngine writes its per-video
 * choices, the {@code bytevc1:..., bytevc1DecodeHardare:...} line among them, through one static
 * logger: a boolean switch and a listener field on one holder class (X.03c7's LIZ and LIZIZ on
 * 47.7.3). A line goes only to that listener, so the switch alone shows nothing. This sets the
 * switch and puts a listener in the field that writes each line to logcat under HushfeedTTPlayer
 * and hands it on to the listener TikTok had, if any; {@code -e on false} puts both back. The
 * holder and its fields are renamed on every build, so they come in as extras:
 * {@code -e action playerlog -e holder X.03c7 -e flag LIZ -e sink LIZIZ -e on true}.
 * Addresses in a line are masked, so a video's URL and its tokens don't reach the log, and
 * {@code -e match bytevc1,decod} keeps only lines holding one of those words, in any case.
 */
final class PlayerLog {
    private static final String TAG = "HushfeedProbe";
    private static final String LINES = "HushfeedTTPlayer";
    private static final Pattern ADDRESS = Pattern.compile("[a-z][a-z0-9+.-]*://\\S+", Pattern.CASE_INSENSITIVE);

    private static Field flag;
    private static Field sink;
    private static boolean flagBefore;
    private static Object sinkBefore;

    private PlayerLog() {
    }

    static synchronized void toggle(ClassLoader loader, Intent intent) throws ReflectiveOperationException {
        boolean wanted = Boolean.parseBoolean(required(intent, "on"));
        if (!wanted) {
            if (flag == null) {
                Log.i(TAG, "ok playerlog already off");
                return;
            }
            flag.setBoolean(null, flagBefore);
            sink.set(null, sinkBefore);
            Log.i(TAG, "ok playerlog off, switch back to " + flagBefore + ", listener back to "
                    + (sinkBefore == null ? "none" : sinkBefore.getClass().getName()));
            flag = null;
            sink = null;
            sinkBefore = null;
            return;
        }
        if (flag != null) {
            Log.i(TAG, "failed playerlog: already on");
            return;
        }
        Class<?> holder = loader.loadClass(required(intent, "holder"));
        Field flagField = holder.getDeclaredField(required(intent, "flag"));
        Field sinkField = holder.getDeclaredField(required(intent, "sink"));
        if (flagField.getType() != boolean.class || !sinkField.getType().isInterface()) {
            throw new IllegalArgumentException("expected a boolean and an interface, got "
                    + flagField.getType() + " and " + sinkField.getType());
        }
        flagField.setAccessible(true);
        sinkField.setAccessible(true);
        String[] words = matchWords(intent.getStringExtra("match"));
        Object previous = sinkField.get(null);
        Object listener = Proxy.newProxyInstance(sinkField.getType().getClassLoader(),
                new Class<?>[]{sinkField.getType()}, (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("equals") ? self == args[0]
                                : method.getName().equals("hashCode") ? System.identityHashCode(self)
                                : "playerlog";
                    }
                    if (args != null && args.length == 1 && args[0] instanceof String) {
                        String line = ADDRESS.matcher((String) args[0]).replaceAll("<address>");
                        if (matches(line, words)) Log.i(LINES, line);
                    }
                    return previous == null ? null : method.invoke(previous, args);
                });
        flagBefore = flagField.getBoolean(null);
        sinkBefore = previous;
        flag = flagField;
        sink = sinkField;
        sinkField.set(null, listener);
        flagField.setBoolean(null, true);
        Log.i(TAG, "ok playerlog on, switch was " + flagBefore + ", listener was "
                + (previous == null ? "none" : previous.getClass().getName())
                + (words.length == 0 ? "" : ", keeping lines with " + String.join("|", words)));
    }

    private static String[] matchWords(String match) {
        if (match == null || match.trim().isEmpty()) return new String[0];
        String[] words = match.toLowerCase(Locale.ROOT).split(",");
        for (int i = 0; i < words.length; i++) words[i] = words[i].trim();
        return words;
    }

    private static boolean matches(String line, String[] words) {
        if (words.length == 0) return true;
        String lower = line.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (!word.isEmpty() && lower.contains(word)) return true;
        }
        return false;
    }

    private static String required(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        if (value == null) throw new IllegalArgumentException("-e " + key + " is required");
        return value;
    }
}
