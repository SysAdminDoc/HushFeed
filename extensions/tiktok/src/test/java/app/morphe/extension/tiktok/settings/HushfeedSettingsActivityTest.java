package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.util.List;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * TikTok Lite (#48) shows Hushfeed's settings in Hushfeed's own screen, opened from "Open
 * Hushfeed settings" on the app icon's long-press menu.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en-w480dp-h960dp-night-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class HushfeedSettingsActivityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test public void theSettingsActionOpensTheHomePageAndBackClosesIt() {
        Intent intent = new Intent().setAction(LauncherSettingsShortcut.ACTION);
        try (var owner = Robolectric.buildActivity(HushfeedSettingsActivity.class, intent).setup().visible()) {
            HushfeedSettingsActivity activity = owner.get();
            settle(activity);
            assertFalse(activity.isFinishing());
            ViewGroup content = activity.findViewById(android.R.id.content);
            ViewGroup surface = (ViewGroup) content.getChildAt(0);
            ViewGroup container = (ViewGroup) surface.getChildAt(0);
            assertTrue(activity.getFragmentManager().findFragmentById(container.getId()) instanceof TikTokPreferenceFragment);
            activity.onBackPressed();
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void anyOtherIntentClosesTheScreen() {
        try (var owner = Robolectric.buildActivity(HushfeedSettingsActivity.class, new Intent()).setup()) {
            assertTrue(owner.get().isFinishing());
        }
    }

    @Test public void theIconShortcutOpensTheScreenWithTheSettingsAction() {
        Context context = RuntimeEnvironment.getApplication();
        LauncherSettingsShortcut.publishNow(context);
        LauncherSettingsShortcut.publishNow(context);
        List<ShortcutInfo> shortcuts = context.getSystemService(ShortcutManager.class).getDynamicShortcuts();
        assertEquals(1, shortcuts.size());
        ShortcutInfo shortcut = shortcuts.get(0);
        assertEquals(LauncherSettingsShortcut.ID, shortcut.getId());
        assertEquals("Open Hushfeed settings", String.valueOf(shortcut.getLongLabel()));
        Intent opens = shortcut.getIntent();
        assertEquals(LauncherSettingsShortcut.ACTION, opens.getAction());
        assertEquals(HushfeedSettingsActivity.class.getName(), opens.getComponent().getClassName());
    }

    /** Lite clears every dynamic shortcut as it starts (#48); the patch calls republish after. */
    @Test public void afterTheAppClearsItsShortcutsOursComesBack() throws InterruptedException {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        LauncherSettingsShortcut.publishNow(context);
        manager.removeAllDynamicShortcuts();
        assertTrue(manager.getDynamicShortcuts().isEmpty());
        LauncherSettingsShortcut.republish();
        long until = System.currentTimeMillis() + 5000;
        while (manager.getDynamicShortcuts().isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(1, manager.getDynamicShortcuts().size());
        assertEquals(LauncherSettingsShortcut.ID, manager.getDynamicShortcuts().get(0).getId());
    }

    private static void settle(HushfeedSettingsActivity activity) {
        activity.getFragmentManager().executePendingTransactions();
        View decor = activity.getWindow().getDecorView();
        for (int frame = 0; frame < 4; frame++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
            decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 480, 960);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
