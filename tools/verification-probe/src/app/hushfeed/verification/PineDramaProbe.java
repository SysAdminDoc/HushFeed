package app.hushfeed.verification;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate instrumentation: no global probe dispatch, native getters, UI input or preference writes. */
public final class PineDramaProbe extends Instrumentation {
    private static final String TAG = "HushfeedPineProbe";
    private static final String ACTION = "app.hushfeed.verification.PROBE";
    private static final String UTILS = "app.morphe.extension.shared.Utils";
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    @Override public Application newApplication(ClassLoader loader, String name, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application application = super.newApplication(loader, name, context);
        if (STARTED.compareAndSet(false, true)) {
            Thread worker = new Thread(() -> listen(application), "hushfeed-pine-inspection");
            worker.setDaemon(true);
            worker.start();
        }
        return application;
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() { }

    private static void listen(Context context) {
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + 120_000L;
            Class<?> utils = context.getClassLoader().loadClass(UTILS);
            while (utils.getMethod("getContext").invoke(null) == null) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    Log.i(TAG, "pine-ready=false");
                    return;
                }
                Thread.sleep(250L);
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context received, Intent intent) {
                    if (!BUSY.compareAndSet(false, true)) {
                        Log.i(TAG, "pine-busy=true");
                        return;
                    }
                    PendingResult pending = goAsync();
                    Thread worker = new Thread(() -> {
                        try {
                            Snapshot facts = new Snapshot(context, intent);
                            Log.i(TAG, PineDramaCapabilities.inspect(facts,
                                    intent.getStringExtra("profile"), intent.getStringExtra("action")));
                        } catch (Exception | LinkageError unavailable) {
                            Log.i(TAG, "pine-inspection-unavailable=true");
                        } finally {
                            BUSY.set(false);
                            pending.finish();
                        }
                    }, "hushfeed-pine-capabilities");
                    worker.setDaemon(true);
                    worker.start();
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, "android.permission.DUMP", null);
            }
            Log.i(TAG, "pine-ready=true");
        } catch (Exception | LinkageError unavailable) {
            Log.i(TAG, "pine-ready=false");
        }
    }

    private static final class Snapshot implements PineDramaCapabilities.Facts {
        private final Context context;
        private final PackageInfo info;
        private final boolean versionMatches;
        private final boolean apkMatches;
        private final boolean singleBase;

        Snapshot(Context context, Intent intent) throws Exception {
            this.context = context;
            info = context.getPackageManager().getPackageInfo(context.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            versionMatches = Long.toString(code).equals(intent.getStringExtra("versionCode"));
            String[] splits = context.getApplicationInfo().splitSourceDirs;
            singleBase = splits == null || splits.length == 0;
            String expected = intent.getStringExtra("apkSha256");
            apkMatches = expected != null && expected.matches("[a-f0-9]{64}") && singleBase
                    && expected.equals(hash(context.getApplicationInfo().sourceDir));
        }

        @Override public String packageName() { return context.getPackageName(); }
        @Override public String versionName() { return info.versionName; }
        @Override public boolean installedVersionMatches() { return versionMatches; }
        @Override public boolean installedApkMatches() { return apkMatches; }
        @Override public boolean singleBaseApk() { return singleBase; }
        @Override public ClassLoader loader() { return context.getClassLoader(); }
        @Override public boolean photoPermissionsDeclared() {
            return info.requestedPermissions != null && Arrays.asList(info.requestedPermissions).contains("android.permission.READ_MEDIA_IMAGES")
                    && Arrays.asList(info.requestedPermissions).contains("android.permission.READ_MEDIA_VIDEO");
        }
        @Override public boolean settingsActivityDeclared() {
            try {
                context.getPackageManager().getActivityInfo(new ComponentName(context.getPackageName(), PineDramaCapabilities.SETTINGS_ACTIVITY), 0);
                return true;
            } catch (android.content.pm.PackageManager.NameNotFoundException absent) { return false; }
        }
        @Override public boolean settingsActivityForeground() {
            try {
                Object visible = loader().loadClass(UTILS).getMethod("getVisibleActivity").invoke(null);
                return visible instanceof Activity && context.getPackageName().equals(((Activity) visible).getPackageName())
                        && PineDramaCapabilities.SETTINGS_ACTIVITY.equals(visible.getClass().getName());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
        }
    }

    private static String hash(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(path))) {
            byte[] buffer = new byte[65_536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
