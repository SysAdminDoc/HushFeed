package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A long press on the Inbox tab flips Hide read receipts and a tap still opens Inbox (#333). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InboxTabReadReceiptsTest {
    private static final int INBOX_ID = 0x7f0a4b8d;

    private ActivityController<Activity> owner;
    private Activity activity;
    private View inbox;
    private final AtomicInteger taps = new AtomicInteger();
    private final List<String> toasts = new ArrayList<>();

    @Before public void setUp() {
        HomeTabSettingsShortcut.resetForTests();
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", true);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "hideReadReceiptsEnabled", true);
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
        PausedProcess.set(false);
        Utils.setToastObserver(toasts::add);
        FrameLayout content = new FrameLayout(activity);
        FrameLayout bar = new FrameLayout(activity);
        inbox = new View(activity);
        inbox.setId(INBOX_ID);
        inbox.setOnClickListener(view -> taps.incrementAndGet());
        bar.addView(inbox, new FrameLayout.LayoutParams(216, 138));
        content.addView(bar, new FrameLayout.LayoutParams(-1, 138, Gravity.BOTTOM));
        activity.setContentView(content);
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.6.3:onw", INBOX_ID);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Utils.setToastObserver(null);
        HomeTabSettingsShortcut.resetForTests();
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.6.3:onw", 0);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", false);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "hideReadReceiptsEnabled", false);
        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.resetToDefault();
        Settings.HIDE_READ_RECEIPTS.resetToDefault();
        owner.pause().stop().destroy();
    }

    @Test public void aLongPressFlipsHideReadReceiptsBothWaysAndSaysSo() {
        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.save(true);
        installAndResume();

        assertTrue(inbox.performLongClick());
        assertTrue(Settings.HIDE_READ_RECEIPTS.get());
        assertEquals("Read receipts hidden", toasts.get(0));
        assertTrue(inbox.performLongClick());
        assertFalse(Settings.HIDE_READ_RECEIPTS.get());
        assertEquals("Read receipts shown", toasts.get(1));
        assertEquals("a long press also counted as a tap", 0, taps.get());
        assertTrue(inbox.performClick());
        assertEquals("a tap on Inbox stopped reaching TikTok", 1, taps.get());
        assertEquals("a tap flipped the setting", 2, toasts.size());
    }

    @Test public void offByDefaultTheTabIsLeftAsItWas() {
        installAndResume();

        assertFalse(inbox.isLongClickable());
        assertFalse(inbox.performLongClick());
        assertFalse(Settings.HIDE_READ_RECEIPTS.get());
    }

    @Test public void withoutThePatchForReceiptsTheTabIsLeftAlone() {
        ReflectionHelpers.setStaticField(SettingsStatus.class, "hideReadReceiptsEnabled", false);
        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.save(true);
        installAndResume();

        assertFalse(inbox.isLongClickable());
    }

    @Test public void tikToksOwnLongPressIsTakenWhileOnAndGivenBackWhenOff() {
        AtomicInteger own = new AtomicInteger();
        inbox.setOnLongClickListener(view -> {
            own.incrementAndGet();
            return true;
        });
        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.save(true);
        installAndResume();

        assertTrue(inbox.performLongClick());
        assertTrue(Settings.HIDE_READ_RECEIPTS.get());
        assertEquals("TikTok's long press also ran", 0, own.get());

        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.save(false);
        layoutPass();
        assertTrue(inbox.isLongClickable());
        assertTrue(inbox.performLongClick());
        assertEquals("switched off, TikTok's own long press didn't come back", 1, own.get());
    }

    @Test public void switchedOffAfterwardsTheTabHasNoLongPress() {
        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.save(true);
        installAndResume();
        assertTrue(inbox.isLongClickable());

        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.save(false);
        layoutPass();
        assertFalse(inbox.isLongClickable());
        assertFalse(inbox.performLongClick());
    }

    @Test public void pausedTheTabIsLeftToTikTok() {
        Settings.INBOX_TAB_FLIPS_READ_RECEIPTS.save(true);
        PausedProcess.set(true);
        installAndResume();

        assertFalse(inbox.isLongClickable());
    }

    private void installAndResume() {
        HomeTabSettingsShortcut.install(activity);
        owner.pause().resume();
    }

    private void layoutPass() {
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }
}
