/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The home screen icons the Custom launcher icon patch put in when Pick the icon in Hushfeed was
 * on (Discussion #114), and the switch between them.
 *
 * <p>Each icon is a launcher alias of its own, all opening {@link AppIconLaunchActivity}, and
 * exactly one is on. Android lists the app once for each enabled launcher entry, so picking one
 * turns it on and every other off. TikTok's own entry stays on and only left the launcher's list,
 * since deep links and notification taps still name it.
 */
public final class AppIcons {
    /** The patch names each alias this plus its key. */
    static final String ALIAS_PREFIX = "app.morphe.extension.tiktok.misc.AppIcon.";

    /** TikTok's launcher entry, where every icon's tap ends up. */
    static final String LAUNCHER = "com.ss.android.ugc.aweme.splash.SplashActivity";

    /** Every key the patch can write, in the order the picker lists them. */
    static final String[] KEYS = {"tiktok", "black", "white_on_black", "black_on_white", "material_you", "custom"};

    /** What each key is called in the picker, in {@link #KEYS} order. Translated where shown. */
    static final String[] LABELS = {"TikTok colors", "Black background", "White on black",
            "Black on white", "Material You", "Your icon"};

    /** One icon the patch added. */
    public static final class Icon {
        public final String key;
        public final String label;
        public final ComponentName component;
        public final boolean enabled;
        final ActivityInfo info;

        Icon(String key, String label, ComponentName component, boolean enabled, ActivityInfo info) {
            this.key = key;
            this.label = label;
            this.component = component;
            this.enabled = enabled;
            this.info = info;
        }

        /** The picture the home screen shows for this icon. */
        public Drawable load(PackageManager packages) {
            return info.loadIcon(packages);
        }
    }

    private AppIcons() {}

    /** The icons this TikTok has, in picker order, or none when it was patched without the picker. */
    @TargetApi(24)
    public static List<Icon> list(Context context) {
        PackageManager packages = context.getPackageManager();
        String pkg = context.getPackageName();
        List<Icon> icons = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) {
            ComponentName component = new ComponentName(pkg, ALIAS_PREFIX + KEYS[i]);
            ActivityInfo info;
            try {
                info = packages.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (PackageManager.NameNotFoundException absent) {
                continue;
            }
            icons.add(new Icon(KEYS[i], LABELS[i], component, isOn(packages, component, info), info));
        }
        return Collections.unmodifiableList(icons);
    }

    /** The icon on the home screen now, or null when there's none of the patch's. */
    public static Icon current(List<Icon> icons) {
        for (Icon icon : icons) {
            if (icon.enabled) return icon;
        }
        return null;
    }

    /**
     * Puts {@code key}'s icon on the home screen and takes every other one off. The new one goes
     * on first, so a failure part way never leaves the app with no icon at all.
     *
     * @return false when this TikTok has no icon by that key
     */
    @TargetApi(24)
    public static boolean pick(Context context, String key) {
        List<Icon> icons = list(context);
        Icon chosen = null;
        for (Icon icon : icons) {
            if (icon.key.equals(key)) chosen = icon;
        }
        if (chosen == null) return false;
        PackageManager packages = context.getPackageManager();
        packages.setComponentEnabledSetting(chosen.component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (Icon icon : icons) {
            if (icon == chosen) continue;
            packages.setComponentEnabledSetting(icon.component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
        return true;
    }

    /**
     * The tap an icon got, sent on to TikTok's own entry as if the launcher had named it. The
     * launcher's new-task flags are dropped: the icon's screen already started the task, and
     * TikTok's screen joins it rather than looking for another.
     */
    public static Intent forward(Intent tap, String pkg) {
        Intent forwarded = new Intent(tap);
        forwarded.setComponent(new ComponentName(pkg, LAUNCHER));
        forwarded.setFlags(tap.getFlags()
                & ~(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        return forwarded;
    }

    private static boolean isOn(PackageManager packages, ComponentName component, ActivityInfo info) {
        int state = packages.getComponentEnabledSetting(component);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
        if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return false;
        return info.enabled;
    }
}
