/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DebugCapture;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/** Passive current-process observations. No event proves that a server sent a notification. */
public final class PushDeliveryReport implements LogBufferManager.ReportSection {
    enum Family { UNKNOWN, IM, LIVE, FOLLOWER, SUGGESTED, OTHER }
    enum Stage {
        SETUP_ALLOWED, SETUP_BLOCKED, SETUP_BODY,
        FCM_TOKEN_REQUEST, FCM_TOKEN_RESULT_PRESENT, FCM_TOKEN_RESULT_REJECTED_OR_EMPTY,
        FCM_TOKEN_EMPTY, FCM_TOKEN_FORWARDED, FCM_RECEIVED, NATIVE_HANDLER_ENTERED,
        NATIVE_UPLOAD_ENTERED, NATIVE_UPLOAD_IGNORED, NATIVE_UPLOAD_CACHED,
        NATIVE_UPLOAD_ATTEMPTED, NATIVE_UPLOAD_ACCEPTED, NATIVE_UPLOAD_REJECTED,
        NATIVE_UPLOAD_EXCEPTION_PATH, NATIVE_UPLOAD_EMPTY,
        HANDLER_ALLOWED, HANDLER_PUSH_OFF, HANDLER_FOLLOWER,
        NOTIFY_PUSH_OFF, NOTIFY_SUGGESTED, NOTIFY_ATTEMPTED,
        NOTIFY_RETURNED, NOTIFY_DID_NOT_RETURN
    }
    private enum State { UNKNOWN, ENABLED, DISABLED, NOT_REQUIRED, MISSING, BLOCKED, ALLOWED }
    private enum ReadinessState { UNOBSERVED, READY, NOT_READY, UNAVAILABLE }
    private enum Problem { COUNTER, REPORT_REGISTRATION, PROFILE_REGISTRATION, FIRST_CONTEXT,
        FIRST_CAPTURE, CHANNEL_READ, CAPTURE_GATE, ANDROID_SNAPSHOT, EXPORT_SWITCHES, EXPORT_RENDER }

    interface Observer {
        Context context();
        boolean captureRunning();
        void register(LogBufferManager.ReportSection section);
    }
    private static final Observer DEVICE_OBSERVER = new Observer() {
        @Override public Context context() { return Utils.getContext(); }
        @Override public boolean captureRunning() { return DebugCapture.isRunning(); }
        @Override public void register(LogBufferManager.ReportSection section) { LogBufferManager.registerReportSection(section); }
    };
    private static volatile Observer observer = DEVICE_OBSERVER;
    private static final PushDeliveryReport INSTANCE = new PushDeliveryReport();
    private static final int LIMIT = 255;
    private static final AtomicIntegerArray COUNTS = new AtomicIntegerArray(Stage.values().length * Family.values().length);
    private static final AtomicReferenceArray<Snapshot> SNAPSHOTS = new AtomicReferenceArray<>(Family.values().length);
    private static final AtomicBoolean FIRST = new AtomicBoolean();
    private static final AtomicInteger UNAVAILABLE = new AtomicInteger();
    private static final Object REGISTRATION_LOCK = new Object();
    private static volatile boolean registered;
    private static volatile boolean truncated, firstObservationComplete;
    private static volatile ReadinessState contextReadiness = ReadinessState.UNOBSERVED;
    private static volatile ReadinessState captureReadiness = ReadinessState.UNOBSERVED;
    // 0 means no native profile was installed, 1 is the verified global 47.6.3 profile, 2 unsupported.
    private static volatile int nativeProfile;

    private PushDeliveryReport() {}

    public static void nativeProfileInstalled() {
        nativeProfile = 1;
        install();
        try {
            HookStatus.bound("Push delivery diagnostics", "global 47.6.3 native anchors");
            HookStatus.runsWhilePaused("Push delivery diagnostics");
        } catch (RuntimeException | LinkageError failure) { unavailable(Problem.PROFILE_REGISTRATION); }
    }

    public static void nativeProfileUnsupported() {
        nativeProfile = 2;
        install();
    }

    private static void unavailable(Problem problem) {
        int previous, next;
        do {
            previous = UNAVAILABLE.get();
            next = previous | (1 << problem.ordinal());
        } while (!UNAVAILABLE.compareAndSet(previous, next));
    }

    private static void install() {
        if (registered) return;
        synchronized (REGISTRATION_LOCK) {
            if (registered) return;
            try {
                observer.register(INSTANCE);
                registered = true;
            } catch (RuntimeException | LinkageError failure) { unavailable(Problem.REPORT_REGISTRATION); }
        }
    }

    private static int slot(Stage stage, Family family) {
        return stage.ordinal() * Family.values().length + family.ordinal();
    }

    /** Counts first. Optional report/readiness failures cannot erase a native branch observation. */
    static void record(Stage stage, Family family) {
        try {
            int index = slot(stage, family), previous;
            do {
                previous = COUNTS.get(index);
                if (previous >= LIMIT) { truncated = true; break; }
            } while (!COUNTS.compareAndSet(index, previous, previous + 1));
        } catch (RuntimeException | LinkageError failure) {
            unavailable(Problem.COUNTER);
            return;
        }
        install();
        if (FIRST.compareAndSet(false, true)) {
            try { contextReadiness = observer.context() != null ? ReadinessState.READY : ReadinessState.NOT_READY; }
            catch (RuntimeException | LinkageError failure) {
                contextReadiness = ReadinessState.UNAVAILABLE;
                unavailable(Problem.FIRST_CONTEXT);
            }
            try { captureReadiness = observer.captureRunning() ? ReadinessState.READY : ReadinessState.NOT_READY; }
            catch (RuntimeException | LinkageError failure) {
                captureReadiness = ReadinessState.UNAVAILABLE;
                unavailable(Problem.FIRST_CAPTURE);
            }
            firstObservationComplete = true;
        }
    }

    static Family family(String channel) {
        if (channel == null || channel.isEmpty()) return Family.UNKNOWN;
        if (channel.equals("im_push") || channel.startsWith("im_push_")) return Family.IM;
        if (channel.equals("live_push") || channel.startsWith("live_push_")) return Family.LIVE;
        if (channel.equals("follow_push") || channel.startsWith("follow_push_")) return Family.FOLLOWER;
        if (channel.startsWith("recommend_video_push")) return Family.SUGGESTED;
        return Family.OTHER;
    }

    static void notification(Stage stage, Notification notification) {
        Family family = Family.UNKNOWN;
        try {
            if (notification != null && Build.VERSION.SDK_INT >= 26) family = family(notification.getChannelId());
        } catch (RuntimeException | LinkageError failure) { unavailable(Problem.CHANNEL_READ); }
        record(stage, family);
    }

    static void beforeNotify(NotificationManager manager, Notification notification) {
        notification(Stage.NOTIFY_ATTEMPTED, notification);
        try {
            if (!observer.captureRunning()) return;
        } catch (RuntimeException | LinkageError failure) {
            unavailable(Problem.CAPTURE_GATE);
            return;
        }
        Family family = Family.UNKNOWN;
        try {
            if (notification != null && Build.VERSION.SDK_INT >= 26) family = family(notification.getChannelId());
        } catch (RuntimeException | LinkageError failure) { unavailable(Problem.CHANNEL_READ); }
        try {
            SNAPSHOTS.set(family.ordinal(), new Snapshot(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN, State.UNKNOWN));
            SNAPSHOTS.set(family.ordinal(), snapshot(manager, notification));
        }
        catch (RuntimeException | LinkageError failure) { unavailable(Problem.ANDROID_SNAPSHOT); }
    }

    private static Snapshot snapshot(NotificationManager manager, Notification notification) {
        State app = State.UNKNOWN, permission = State.UNKNOWN, channel = State.UNKNOWN, group = State.UNKNOWN;
        Context context = observer.context();
        if (Build.VERSION.SDK_INT < 33) permission = State.NOT_REQUIRED;
        else if (context != null) permission = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED ? State.ENABLED : State.DISABLED;
        if (manager != null && Build.VERSION.SDK_INT >= 24) {
            app = manager.areNotificationsEnabled() ? State.ENABLED : State.DISABLED;
        }
        if (Build.VERSION.SDK_INT < 26) {
            channel = State.NOT_REQUIRED;
            group = State.NOT_REQUIRED;
        } else if (manager != null && notification != null) {
            String id = notification.getChannelId();
            NotificationChannel value = id == null ? null : manager.getNotificationChannel(id);
            channel = value == null ? State.MISSING
                    : value.getImportance() == NotificationManager.IMPORTANCE_NONE ? State.BLOCKED : State.ALLOWED;
            if (Build.VERSION.SDK_INT < 28) group = State.NOT_REQUIRED;
            else if (value != null) {
                String groupId = value.getGroup();
                NotificationChannelGroup selected = groupId == null ? null : manager.getNotificationChannelGroup(groupId);
                group = selected == null ? State.NOT_REQUIRED : selected.isBlocked() ? State.BLOCKED : State.ALLOWED;
            }
        }
        return new Snapshot(app, permission, channel, group);
    }

    private static final class Snapshot {
        final State app, permission, channel, group;
        Snapshot(State app, State permission, State channel, State group) {
            this.app = app; this.permission = permission; this.channel = channel; this.group = group;
        }
        @Override public String toString() {
            return "app=" + app + " postPermission=" + permission + " channel=" + channel + " group=" + group;
        }
    }

    public static void setupDecision(boolean blocked) { record(blocked ? Stage.SETUP_BLOCKED : Stage.SETUP_ALLOWED, Family.UNKNOWN); }
    public static void setupBody() { record(Stage.SETUP_BODY, Family.UNKNOWN); }
    public static void tokenRequest() { record(Stage.FCM_TOKEN_REQUEST, Family.UNKNOWN); }
    public static void tokenResultPresent() { record(Stage.FCM_TOKEN_RESULT_PRESENT, Family.UNKNOWN); }
    public static void tokenResultRejectedOrEmpty() { record(Stage.FCM_TOKEN_RESULT_REJECTED_OR_EMPTY, Family.UNKNOWN); }
    public static void tokenEmpty() { record(Stage.FCM_TOKEN_EMPTY, Family.UNKNOWN); }
    public static void tokenForwarded() { record(Stage.FCM_TOKEN_FORWARDED, Family.UNKNOWN); }
    public static void fcmReceived() { record(Stage.FCM_RECEIVED, Family.UNKNOWN); }
    public static void nativeHandlerEntered() { record(Stage.NATIVE_HANDLER_ENTERED, Family.UNKNOWN); }
    public static void uploadEntered() { record(Stage.NATIVE_UPLOAD_ENTERED, Family.UNKNOWN); }
    public static void uploadIgnored() { record(Stage.NATIVE_UPLOAD_IGNORED, Family.UNKNOWN); }
    public static void uploadCached() { record(Stage.NATIVE_UPLOAD_CACHED, Family.UNKNOWN); }
    public static void uploadAttempted() { record(Stage.NATIVE_UPLOAD_ATTEMPTED, Family.UNKNOWN); }
    public static void uploadAccepted() { record(Stage.NATIVE_UPLOAD_ACCEPTED, Family.UNKNOWN); }
    public static void uploadRejected() { record(Stage.NATIVE_UPLOAD_REJECTED, Family.UNKNOWN); }
    public static void uploadException() { record(Stage.NATIVE_UPLOAD_EXCEPTION_PATH, Family.UNKNOWN); }
    public static void uploadEmpty() { record(Stage.NATIVE_UPLOAD_EMPTY, Family.UNKNOWN); }

    @Override public String title() { return "PUSH DELIVERY"; }

    @Override public List<String> lines() {
        try { return renderLines(); }
        catch (RuntimeException | LinkageError failure) {
            unavailable(Problem.EXPORT_RENDER);
            List<String> fallback = new ArrayList<>();
            fallback.add("Current process only. Observer unavailable=true.");
            fallback.add("Observer reasons=[EXPORT_RENDER]");
            return fallback;
        }
    }

    private static List<String> renderLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Current process only. Zero means unobserved, not failure. No server delivery is inferred.");
        lines.add("Native registration anchors: " + (nativeProfile == 1 ? "global 47.6.3 installed"
                : nativeProfile == 2 ? "unsupported package/version" : "not installed"));
        lines.add("Native upload counters include all transports. Tokens and messages are not correlated.");
        lines.add("Android snapshots cover timed-capture attempts only. None means unobserved.");
        try { if (observer.context() != null) {
            lines.add("Build switches: notificationControls=" + SettingsStatus.notificationControlsEnabled
                    + " suggestionFilter=" + SettingsStatus.suggestedVideoPushBlockEnabled
                    + " limitBackgroundTraffic=" + SettingsStatus.limitBackgroundTrafficEnabled
                    + " skipPushSetup=" + SettingsStatus.skipPushSetupEnabled);
            lines.add("Switches at export: savedPushOff=" + Settings.TURN_OFF_PUSH_NOTIFICATIONS.savedValue()
                    + " effectivePushOff=" + Settings.TURN_OFF_PUSH_NOTIFICATIONS.get()
                    + " savedTraffic=" + Settings.LIMIT_BACKGROUND_TRAFFIC.savedValue()
                    + " effectiveTraffic=" + Settings.LIMIT_BACKGROUND_TRAFFIC.get()
                    + " followerFilter=" + Settings.HIDE_FOLLOWER_NOTIFICATIONS.get()
                    + " suggestionFilter=" + Settings.BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS.get());
        } } catch (RuntimeException | LinkageError failure) { unavailable(Problem.EXPORT_SWITCHES); }
        lines.add(firstObservationComplete ? "First observation: context=" + contextReadiness + " capture=" + captureReadiness
                : "First observation: " + (FIRST.get() ? "readiness unobserved" : "unobserved"));
        int problems = UNAVAILABLE.get();
        lines.add("Observer unavailable=" + (problems != 0));
        if (problems != 0) {
            List<String> reasons = new ArrayList<>();
            for (Problem problem : Problem.values()) if ((problems & (1 << problem.ordinal())) != 0) reasons.add(problem.name());
            lines.add("Observer reasons=" + reasons);
        }
        for (Stage stage : Stage.values()) for (Family family : Family.values()) {
            int count = COUNTS.get(slot(stage, family));
            if (count > 0) lines.add(stage.name() + " " + family.name() + " " + count);
        }
        for (Family family : Family.values()) {
            Snapshot snapshot = SNAPSHOTS.get(family.ordinal());
            if (snapshot != null) lines.add("Latest captured notify snapshot " + family.name() + " " + snapshot);
        }
        if (truncated) lines.add("A counter reached 255. Further observations were not counted.");
        return lines;
    }

    static void setObserverForTests(Observer value) {
        synchronized (REGISTRATION_LOCK) { observer = value; registered = false; }
    }

    static PushDeliveryReport sectionForTests() { return INSTANCE; }

    static void resetForTests() {
        for (int i = 0; i < COUNTS.length(); i++) COUNTS.set(i, 0);
        for (int i = 0; i < SNAPSHOTS.length(); i++) SNAPSHOTS.set(i, null);
        FIRST.set(false); UNAVAILABLE.set(0); truncated = firstObservationComplete = false; nativeProfile = 0;
        synchronized (REGISTRATION_LOCK) { observer = DEVICE_OBSERVER; registered = false; }
        contextReadiness = captureReadiness = ReadinessState.UNOBSERVED;
    }
}
