/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.NotificationChannelGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.diagnostics.DebugCapture;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadows.ShadowNotificationManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PushDeliveryReportTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final PushDeliveryReport report = PushDeliveryReport.sectionForTests();
    private Context context;
    private NotificationManager manager;
    private boolean notificationsBefore, suggestionsBefore;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        manager = context.getSystemService(NotificationManager.class);
        notificationsBefore = SettingsStatus.notificationControlsEnabled;
        suggestionsBefore = SettingsStatus.suggestedVideoPushBlockEnabled;
        SettingsStatus.notificationControlsEnabled = SettingsStatus.suggestedVideoPushBlockEnabled = true;
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(false);
        Settings.HIDE_FOLLOWER_NOTIFICATIONS.save(false);
        Settings.BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS.save(true);
        PushDeliveryReport.resetForTests();
        DebugCapture.start();
        CountingNotifications.snapshotFailure = CountingNotifications.notifyFailure = null;
        CountingNotifications.lastTag = null; CountingNotifications.lastNotification = null; CountingNotifications.lastId = 0;
    }

    @After public void after() {
        SettingsStatus.notificationControlsEnabled = notificationsBefore;
        SettingsStatus.suggestedVideoPushBlockEnabled = suggestionsBefore;
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.resetToDefault();
        Settings.HIDE_FOLLOWER_NOTIFICATIONS.resetToDefault();
        Settings.BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS.resetToDefault();
        PushDeliveryReport.resetForTests();
        DebugCapture.stop();
    }

    private String text() { return String.join("\n", report.lines()); }
    private Notification notification(String channel) {
        manager.createNotificationChannel(new NotificationChannel(channel, "category", NotificationManager.IMPORTANCE_HIGH));
        return new Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("private-title-sentinel").setContentText("private-payload-sentinel").build();
    }

    @Test public void emptyCountersAreExplicitlyUnobserved() {
        assertTrue(text().contains("Zero means unobserved, not failure"));
        assertTrue(text().contains("First observation: unobserved"));
        assertTrue(text().contains("anchors: not installed"));
        assertFalse(text().contains("NATIVE_UPLOAD_ACCEPTED UNKNOWN"));
    }

    @Test public void cachedRegistrationCannotBecomeAFreshServerAcceptance() {
        PushDeliveryReport.nativeProfileInstalled();
        PushDeliveryReport.tokenResultPresent();
        PushDeliveryReport.tokenForwarded();
        PushDeliveryReport.uploadCached();
        assertTrue(text().contains("NATIVE_UPLOAD_CACHED UNKNOWN 1"));
        assertFalse(text().contains("NATIVE_UPLOAD_ACCEPTED UNKNOWN"));
        assertTrue(text().contains("include all transports"));
        PushDeliveryReport.uploadRejected();
        PushDeliveryReport.uploadException();
        assertFalse(text().contains("NATIVE_UPLOAD_ACCEPTED UNKNOWN"));
        PushDeliveryReport.uploadAccepted();
        assertTrue(text().contains("NATIVE_UPLOAD_ACCEPTED UNKNOWN 1"));
    }

    @Test public void countersSaturateUnderConcurrentCallbacks() throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            Thread thread = new Thread(() -> { for (int i = 0; i < 100; i++) PushDeliveryReport.fcmReceived(); });
            threads.add(thread); thread.start();
        }
        for (Thread thread : threads) thread.join();
        assertTrue(text().contains("FCM_RECEIVED UNKNOWN 255"));
        assertTrue(text().contains("reached 255"));
        assertFalse(text().contains("FCM_RECEIVED UNKNOWN 256"));
    }

    @Test public void unsupportedNativeProfileCannotReadAsGlobalQualification() {
        PushDeliveryReport.nativeProfileUnsupported();
        assertTrue(text().contains("unsupported package/version"));
        assertFalse(text().contains("global 47.6.3 installed"));
        assertFalse(text().contains("NATIVE_UPLOAD_ACCEPTED UNKNOWN"));
    }

    @Test public void genuineChannelFamiliesPassWhileSuggestionAndFollowerPoliciesStayEnabled() {
        Settings.HIDE_FOLLOWER_NOTIFICATIONS.save(true);
        assertFalse(NotificationControls.shouldDropPush(new NotificationControlsTest.Push("im_push")));
        assertFalse(NotificationControls.shouldDropPush(new NotificationControlsTest.Push("live_push")));
        assertTrue(NotificationControls.shouldDropPush(new NotificationControlsTest.Push("follow_push")));
        SuggestedVideoPushBlock.notify(manager, "private-tag-sentinel", 77, notification("im_push_associated_4"));
        SuggestedVideoPushBlock.notify(manager, 78, notification("live_push_associated_4"));
        SuggestedVideoPushBlock.notify(manager, 79, notification("recommend_video_push_associated_4"));
        assertEquals(2, Shadows.shadowOf(manager).getAllNotifications().size());
        assertTrue(text().contains("HANDLER_FOLLOWER FOLLOWER 1"));
        assertTrue(text().contains("NOTIFY_RETURNED IM 1"));
        assertTrue(text().contains("NOTIFY_RETURNED LIVE 1"));
        assertTrue(text().contains("NOTIFY_SUGGESTED SUGGESTED 1"));
        assertTrue(text().contains("Latest captured notify snapshot IM app=ENABLED postPermission=NOT_REQUIRED channel=ALLOWED"));
        assertFalse(text().contains("private-"));
        assertFalse(text().contains("associated_4"));
    }

    @Test public void notifyFailureStillThrowsAndNeverBecomesAReturnedCall() {
        Notification notification = notification("im_push_associated_4");
        assertThrows(NullPointerException.class, () -> SuggestedVideoPushBlock.notify(null, 1, notification));
        assertTrue(text().contains("NOTIFY_ATTEMPTED IM 1"));
        assertTrue(text().contains("NOTIFY_DID_NOT_RETURN IM 1"));
        assertFalse(text().contains("NOTIFY_RETURNED IM"));
    }

    @Test public void intentionalPushOffStillBlocksAndReportsItsOwnDecision() {
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        assertTrue(PushShutoff.skipPushSetup());
        SuggestedVideoPushBlock.notify(manager, 1, notification("im_push_associated_4"));
        assertEquals(0, Shadows.shadowOf(manager).getAllNotifications().size());
        assertTrue(text().contains("SETUP_BLOCKED UNKNOWN 1"));
        assertTrue(text().contains("NOTIFY_PUSH_OFF IM 1"));
        assertFalse(text().contains("NOTIFY_ATTEMPTED IM"));
    }

    @Test public void blockedAndMissingChannelsAreObservationsWithoutRewritingThem() {
        String blocked = "live_push_associated_9";
        manager.createNotificationChannel(new NotificationChannel(blocked, "category", NotificationManager.IMPORTANCE_NONE));
        SuggestedVideoPushBlock.notify(manager, 1, new Notification.Builder(context, blocked).setSmallIcon(android.R.drawable.ic_dialog_info).build());
        assertTrue(text().contains("Latest captured notify snapshot LIVE app=ENABLED postPermission=NOT_REQUIRED channel=BLOCKED"));
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(blocked).getImportance());
        SuggestedVideoPushBlock.notify(manager, 2, new Notification.Builder(context, "im_push_associated_8").setSmallIcon(android.R.drawable.ic_dialog_info).build());
        assertTrue(text().contains("Latest captured notify snapshot IM app=ENABLED postPermission=NOT_REQUIRED channel=MISSING"));
        assertNull(manager.getNotificationChannel("im_push_associated_8"));
    }

    @Test public void pauseKeepsObservationAndDistinguishesSavedFromEffectivePushOff() {
        Settings.TURN_OFF_PUSH_NOTIFICATIONS.save(true);
        PausedProcess.set(true);
        try {
            assertFalse(PushShutoff.skipPushSetup());
            SuggestedVideoPushBlock.notify(manager, 1, notification("im_push_associated_4"));
            assertEquals(1, Shadows.shadowOf(manager).getAllNotifications().size());
            assertTrue(text().contains("SETUP_ALLOWED UNKNOWN 1"));
            assertTrue(text().contains("savedPushOff=true effectivePushOff=false"));
            assertTrue(text().contains("NOTIFY_RETURNED IM 1"));
        } finally { PausedProcess.set(false); }
    }

    @Test public void snapshotFailuresReplaceOlderAvailability() {
        Notification value = notification("im_push_associated_4");
        PushDeliveryReport.beforeNotify(manager, value);
        PushDeliveryReport.beforeNotify(null, value);
        assertTrue(text().contains("Latest captured notify snapshot IM app=UNKNOWN postPermission=NOT_REQUIRED channel=UNKNOWN"));
    }

    @Test public void everyCounterAndSnapshotHasAFixedExportBound() {
        for (PushDeliveryReport.Stage stage : PushDeliveryReport.Stage.values())
            for (PushDeliveryReport.Family family : PushDeliveryReport.Family.values())
                for (int i = 0; i < 300; i++) PushDeliveryReport.record(stage, family);
        assertTrue(report.lines().size() <= 180);
        assertTrue(text().length() <= 20_000);
        assertTrue(text().contains("reached 255"));
        assertFalse(text().contains(" 300"));
    }

    @Test public void captureExportsCountersWithoutAnyCallerThreadOrNotificationContent() throws Exception {
        LogBufferManager.clearLogBuffer();
        DebugCapture.start();
        Thread worker = new Thread(() -> { PushDeliveryReport.tokenRequest(); PushDeliveryReport.fcmReceived(); }, "private-thread-sentinel");
        worker.start(); worker.join();
        SuggestedVideoPushBlock.notify(manager, "private-tag-sentinel", 9901, notification("live_push_associated_4"));
        String exported = LogBufferManager.buildExportText();
        assertTrue(exported.contains("[PUSH DELIVERY]"));
        assertTrue(exported.contains("FCM_RECEIVED UNKNOWN 1"));
        assertFalse(exported.contains("private-thread-sentinel"));
        assertFalse(exported.contains("private-tag-sentinel"));
        assertFalse(exported.contains("private-payload-sentinel"));
        assertFalse(exported.contains("private-title-sentinel"));
        assertFalse(exported.contains(" | PushDelivery | "));
    }

    @Test @Config(sdk = 23) public void api23NeverReadsUnavailableChannelApis() {
        SuggestedVideoPushBlock.notify(manager, 1, new Notification.Builder(context).setSmallIcon(android.R.drawable.ic_dialog_info).build());
        assertTrue(text().contains("Latest captured notify snapshot UNKNOWN app=UNKNOWN postPermission=NOT_REQUIRED channel=NOT_REQUIRED group=NOT_REQUIRED"));
    }

    @Test @Config(sdk = 33) public void notificationPermissionIsReadWithoutRequestingIt() {
        Shadows.shadowOf((Application) context).denyPermissions("android.permission.POST_NOTIFICATIONS");
        Notification value = notification("im_push_associated_4");
        PushDeliveryReport.beforeNotify(manager, value);
        assertTrue(text().contains("postPermission=DISABLED"));
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.POST_NOTIFICATIONS"));
        Shadows.shadowOf((Application) context).grantPermissions("android.permission.POST_NOTIFICATIONS");
        PushDeliveryReport.beforeNotify(manager, value);
        assertTrue(text().contains("postPermission=ENABLED"));
    }

    public static final class CountingContext extends ContextWrapper {
        int permissionReads;
        CountingContext(Context context) { super(context); }
        @Override public int checkSelfPermission(String permission) {
            permissionReads++;
            return super.checkSelfPermission(permission);
        }
    }

    @Implements(NotificationManager.class)
    public static class CountingNotifications extends ShadowNotificationManager {
        static int snapshotReads;
        static RuntimeException snapshotFailure, notifyFailure;
        static String lastTag;
        static int lastId;
        static Notification lastNotification;
        @Implementation @Override protected void notify(String tag, int id, Notification notification) {
            lastTag = tag; lastId = id; lastNotification = notification;
            if (notifyFailure != null) throw notifyFailure;
            super.notify(tag, id, notification);
        }
        @Implementation @Override protected void notify(int id, Notification notification) {
            lastTag = null; lastId = id; lastNotification = notification;
            if (notifyFailure != null) throw notifyFailure;
            super.notify(id, notification);
        }
        @Implementation(minSdk = 24) @Override protected boolean areNotificationsEnabled() {
            snapshotReads++;
            if (snapshotFailure != null) throw snapshotFailure;
            return super.areNotificationsEnabled();
        }
        @Implementation(minSdk = 26) @Override protected NotificationChannel getNotificationChannel(String id) {
            snapshotReads++; return super.getNotificationChannel(id);
        }
        @Implementation(minSdk = 28) @Override public NotificationChannelGroup getNotificationChannelGroup(String id) {
            snapshotReads++; return super.getNotificationChannelGroup(id);
        }
    }

    @Test @Config(sdk = 33, shadows = CountingNotifications.class)
    public void captureOffPerformsNoPlatformSnapshotReadsButKeepsCounters() {
        Notification value = notification("im_push_associated_4");
        CountingContext observed = new CountingContext(context);
        Utils.setContext(observed);
        DebugCapture.stop();
        CountingNotifications.snapshotReads = 0;
        observed.permissionReads = 0;
        PushDeliveryReport.beforeNotify(manager, value);
        assertEquals(0, CountingNotifications.snapshotReads);
        assertEquals(0, observed.permissionReads);
        assertTrue(text().contains("NOTIFY_ATTEMPTED IM 1"));
        assertFalse(text().contains("Latest captured notify snapshot IM"));
        DebugCapture.start();
        PushDeliveryReport.beforeNotify(manager, value);
        assertTrue(CountingNotifications.snapshotReads >= 2);
        assertTrue(observed.permissionReads > 0);
        assertTrue(text().contains("Latest captured notify snapshot IM"));
    }

    private static final class FaultObserver implements PushDeliveryReport.Observer {
        final AtomicInteger registrations = new AtomicInteger();
        RuntimeException contextFailure, captureFailure, registrationFailure;
        LinkageError captureLinkageFailure;
        boolean retryRegistration;
        ThreadDeath fatalCapture;
        @Override public Context context() {
            if (contextFailure != null) throw contextFailure;
            return Utils.getContext();
        }
        @Override public boolean captureRunning() {
            if (fatalCapture != null) throw fatalCapture;
            if (captureLinkageFailure != null) throw captureLinkageFailure;
            if (captureFailure != null) throw captureFailure;
            return DebugCapture.isRunning();
        }
        @Override public void register(LogBufferManager.ReportSection section) {
            int attempt = registrations.incrementAndGet();
            if (registrationFailure != null && (!retryRegistration || attempt == 1)) throw registrationFailure;
            LogBufferManager.registerReportSection(section);
        }
    }

    @Test @Config(shadows = CountingNotifications.class)
    public void captureRuntimeFailureIsMarkedAndBothNotifyOverloadsKeepTheirArguments() {
        FaultObserver observer = new FaultObserver();
        observer.captureFailure = new IllegalStateException("private-observer-sentinel");
        PushDeliveryReport.setObserverForTests(observer);
        Notification value = notification("im_push_associated_4");
        SuggestedVideoPushBlock.notify(manager, "private-tag-sentinel", 123, value);
        assertEquals("private-tag-sentinel", CountingNotifications.lastTag);
        assertEquals(123, CountingNotifications.lastId);
        assertSame(value, CountingNotifications.lastNotification);
        SuggestedVideoPushBlock.notify(manager, 124, value);
        assertNull(CountingNotifications.lastTag);
        assertEquals(124, CountingNotifications.lastId);
        assertSame(value, CountingNotifications.lastNotification);
        assertEquals(2, Shadows.shadowOf(manager).getAllNotifications().size());
        String report = text();
        assertTrue(report.contains("NOTIFY_ATTEMPTED IM 2"));
        assertTrue(report.contains("NOTIFY_RETURNED IM 2"));
        assertTrue(report.contains("Observer unavailable=true"));
        assertTrue(report.contains("FIRST_CAPTURE"));
        assertTrue(report.contains("CAPTURE_GATE"));
        assertTrue(report.contains("capture=UNAVAILABLE"));
        assertFalse(report.contains("private-observer-sentinel"));
    }

    @Test @Config(shadows = CountingNotifications.class)
    public void captureLinkageFailureIsMarkedWithoutReplacingTheNativeException() {
        FaultObserver observer = new FaultObserver();
        observer.captureLinkageFailure = new NoClassDefFoundError("private-linkage-sentinel");
        PushDeliveryReport.setObserverForTests(observer);
        RuntimeException nativeFailure = new SecurityException("private-native-sentinel");
        CountingNotifications.notifyFailure = nativeFailure;
        Notification value = notification("live_push_associated_4");
        RuntimeException actual = assertThrows(SecurityException.class,
                () -> SuggestedVideoPushBlock.notify(manager, "private-tag-sentinel", 125, value));
        assertSame(nativeFailure, actual);
        assertEquals(125, CountingNotifications.lastId);
        assertSame(value, CountingNotifications.lastNotification);
        assertTrue(text().contains("NOTIFY_DID_NOT_RETURN LIVE 1"));
        assertTrue(text().contains("CAPTURE_GATE"));
        assertFalse(text().contains("private-linkage-sentinel"));
        assertFalse(text().contains("private-native-sentinel"));
    }

    @Test public void contextAndExportFailureCannotEraseACounterOrExposeErrorText() {
        FaultObserver observer = new FaultObserver();
        observer.contextFailure = new IllegalStateException("private-context-sentinel");
        PushDeliveryReport.setObserverForTests(observer);
        PushDeliveryReport.tokenRequest();
        String report = text();
        assertTrue(report.contains("FCM_TOKEN_REQUEST UNKNOWN 1"));
        assertTrue(report.contains("context=UNAVAILABLE"));
        assertTrue(report.contains("FIRST_CONTEXT"));
        assertTrue(report.contains("EXPORT_SWITCHES"));
        assertFalse(report.contains("private-context-sentinel"));
        assertFalse(LogBufferManager.buildExportText().contains("private-context-sentinel"));
    }

    @Test @Config(shadows = CountingNotifications.class)
    public void platformSnapshotFailureIsMarkedWithoutPreventingPosting() {
        Notification value = notification("im_push_associated_4");
        CountingNotifications.snapshotFailure = new IllegalStateException("private-platform-sentinel");
        SuggestedVideoPushBlock.notify(manager, 126, value);
        assertSame(value, CountingNotifications.lastNotification);
        assertTrue(text().contains("NOTIFY_RETURNED IM 1"));
        assertTrue(text().contains("ANDROID_SNAPSHOT"));
        assertTrue(text().contains("Latest captured notify snapshot IM app=UNKNOWN"));
        assertFalse(text().contains("private-platform-sentinel"));
    }

    @Test public void concurrentCallbacksRegisterTheSectionExactlyOnce() throws Exception {
        FaultObserver observer = new FaultObserver();
        PushDeliveryReport.setObserverForTests(observer);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread worker = new Thread(() -> {
                try { start.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                for (int count = 0; count < 40; count++) PushDeliveryReport.tokenRequest();
            });
            workers.add(worker); worker.start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();
        assertEquals(1, observer.registrations.get());
        assertTrue(text().contains("FCM_TOKEN_REQUEST UNKNOWN 160"));
        String exported = LogBufferManager.buildExportText();
        assertEquals(1, exported.split("\\[PUSH DELIVERY\\]", -1).length - 1);
    }

    @Test public void failedRegistrationIsMarkedAndRetriedWithoutLosingCounts() {
        FaultObserver observer = new FaultObserver();
        observer.registrationFailure = new IllegalStateException("private-registration-sentinel");
        observer.retryRegistration = true;
        PushDeliveryReport.setObserverForTests(observer);
        PushDeliveryReport.tokenRequest();
        PushDeliveryReport.tokenRequest();
        assertEquals(2, observer.registrations.get());
        assertTrue(text().contains("FCM_TOKEN_REQUEST UNKNOWN 2"));
        assertTrue(text().contains("REPORT_REGISTRATION"));
        assertFalse(text().contains("private-registration-sentinel"));
        assertTrue(LogBufferManager.buildExportText().contains("[PUSH DELIVERY]"));
    }

    @Test public void saturatedCountersStillRetryARecoveredRegistrar() {
        FaultObserver observer = new FaultObserver();
        observer.registrationFailure = new IllegalStateException("private-registration-sentinel");
        PushDeliveryReport.setObserverForTests(observer);
        for (int count = 0; count < 255; count++) PushDeliveryReport.tokenRequest();
        assertEquals(255, observer.registrations.get());
        observer.registrationFailure = null;
        PushDeliveryReport.tokenRequest();
        assertEquals(256, observer.registrations.get());
        assertTrue(text().contains("FCM_TOKEN_REQUEST UNKNOWN 255"));
        assertTrue(text().contains("REPORT_REGISTRATION"));
        assertTrue(LogBufferManager.buildExportText().contains("[PUSH DELIVERY]"));
        assertFalse(text().contains("private-registration-sentinel"));
    }

    @Test public void controlFlowErrorsAreNotSwallowedAsObserverFailures() {
        FaultObserver observer = new FaultObserver();
        ThreadDeath fatal = new ThreadDeath();
        observer.fatalCapture = fatal;
        PushDeliveryReport.setObserverForTests(observer);
        assertSame(fatal, assertThrows(ThreadDeath.class, PushDeliveryReport::tokenRequest));
    }

    @Test public void nativeDiagnosticEntrypointsCannotReceivePrivateObjectsOrText() {
        for (Method method : PushDeliveryReport.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())) continue;
            for (Class<?> parameter : method.getParameterTypes()) assertEquals(boolean.class, parameter);
        }
    }
}
