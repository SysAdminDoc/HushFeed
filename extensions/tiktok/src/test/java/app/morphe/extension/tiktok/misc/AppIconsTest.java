package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** The home screen icons the picker switches between (Discussion #114). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppIconsTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final PackageManager packages = context.getPackageManager();

    /** An icon alias as the patch writes it: enabled only for the one the options chose. */
    private ComponentName alias(String key, boolean enabled) {
        ActivityInfo info = new ActivityInfo();
        info.packageName = context.getPackageName();
        info.name = AppIcons.ALIAS_PREFIX + key;
        info.targetActivity = AppIconLaunchActivity.class.getName();
        info.enabled = enabled;
        info.exported = true;
        info.applicationInfo = context.getApplicationInfo();
        shadowOf(packages).addOrUpdateActivity(info);
        return new ComponentName(context.getPackageName(), info.name);
    }

    private static List<String> keys(List<AppIcons.Icon> icons) {
        List<String> keys = new ArrayList<>();
        for (AppIcons.Icon icon : icons) keys.add(icon.key);
        return keys;
    }

    @Test public void theKeysAreTheOnesThePatchWrites() {
        assertEquals(List.of("tiktok", "black", "white_on_black", "black_on_white", "material_you", "custom"),
                List.of(AppIcons.KEYS));
        assertEquals(AppIcons.KEYS.length, AppIcons.LABELS.length);
        assertEquals("com.ss.android.ugc.aweme.splash.SplashActivity", AppIcons.LAUNCHER);
    }

    @Test public void aTikTokPatchedWithoutThePickerHasNoIcons() {
        assertTrue(AppIcons.list(context).isEmpty());
        assertNull(AppIcons.current(AppIcons.list(context)));
        assertFalse(AppIcons.pick(context, "black"));
    }

    @Test public void theIconsAreListedInPickerOrderWithTheOneThatsOn() {
        alias("custom", true);
        alias("black", false);
        alias("tiktok", false);
        List<AppIcons.Icon> icons = AppIcons.list(context);
        assertEquals(List.of("tiktok", "black", "custom"), keys(icons));
        assertEquals("Your icon", icons.get(2).label);
        assertEquals("custom", AppIcons.current(icons).key);
    }

    @Test public void pickingTurnsThatIconOnAndEveryOtherOff() {
        ComponentName custom = alias("custom", true);
        ComponentName black = alias("black", false);
        ComponentName tiktok = alias("tiktok", false);

        assertTrue(AppIcons.pick(context, "black"));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, packages.getComponentEnabledSetting(black));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packages.getComponentEnabledSetting(custom));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packages.getComponentEnabledSetting(tiktok));
        assertEquals("black", AppIcons.current(AppIcons.list(context)).key);

        // An icon this TikTok doesn't have changes nothing.
        assertFalse(AppIcons.pick(context, "white_on_black"));
        assertEquals("black", AppIcons.current(AppIcons.list(context)).key);

        assertTrue(AppIcons.pick(context, "custom"));
        assertEquals("custom", AppIcons.current(AppIcons.list(context)).key);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packages.getComponentEnabledSetting(black));
    }

    private Intent launcherTap() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(context.getPackageName(), AppIcons.ALIAS_PREFIX + "black"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                .putExtra("profile", 7);
    }

    @Test public void aTapGoesOnToTikToksEntryAsTheLauncherSentIt() {
        Intent tap = launcherTap();
        Intent forwarded = AppIcons.forward(tap, context.getPackageName());
        assertEquals(new ComponentName(context.getPackageName(), AppIcons.LAUNCHER), forwarded.getComponent());
        assertEquals(Intent.ACTION_MAIN, forwarded.getAction());
        assertTrue(forwarded.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(7, forwarded.getIntExtra("profile", 0));
        assertEquals(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT, forwarded.getFlags());
        // The tap itself is left as it came.
        assertEquals(AppIcons.ALIAS_PREFIX + "black", tap.getComponent().getClassName());
        assertTrue((tap.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test public void theIconsScreenOpensTikTokAndCloses() {
        ActivityController<AppIconLaunchActivity> controller =
                Robolectric.buildActivity(AppIconLaunchActivity.class, launcherTap());
        AppIconLaunchActivity screen = controller.create().get();
        Intent started = shadowOf(screen).getNextStartedActivity();
        assertEquals(AppIcons.LAUNCHER, started.getComponent().getClassName());
        assertTrue(screen.isFinishing());
    }

    @Test public void overTikTokAlreadyOpenTheScreenJustCloses() {
        // After a switch, the launcher brings TikTok back and starts the new icon's screen on top.
        ActivityController<AppIconLaunchActivity> controller =
                Robolectric.buildActivity(AppIconLaunchActivity.class, launcherTap());
        shadowOf(controller.get()).setIsTaskRoot(false);
        AppIconLaunchActivity screen = controller.create().get();
        assertNull(shadowOf(screen).getNextStartedActivity());
        assertTrue(screen.isFinishing());
    }
}
