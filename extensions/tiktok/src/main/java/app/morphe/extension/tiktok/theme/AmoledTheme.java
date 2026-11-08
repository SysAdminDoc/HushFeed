/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The AMOLED dark mode switch in Hushfeed settings (#372).
 *
 * <p>AMOLED dark theme paints TikTok's dark grays at patch time. Next to every surface it
 * repaints, the resource side keeps TikTok's own value under a theme attribute of its own
 * ({@code hushfeed_amoled_stock_<i>}), and it adds a style per surface
 * ({@code hushfeed_amoled_restore_<i>}) that points the surface back at that value, plus one
 * style that does all of them. While the switch is on, nothing here changes anything. While it's
 * off, each theme TikTok builds gets those styles applied right after TikTok builds it, so its
 * surfaces read TikTok's own grays again. The patch calls in from AppCompatActivity's setTheme,
 * every Resources.Theme.applyStyle and Context.setTheme in TikTok's code, every
 * ContextThemeWrapper TikTok makes (which becomes {@link AmoledThemeWrapper}) and every Dialog
 * constructor. The Compose cards, the comment bar and the web pages ask {@link #on()}.
 *
 * <p>The answer is read once per process, so a screen never mixes the two looks: the switch
 * takes effect at the next start. Pausing Hushfeed reads as off.
 */
public final class AmoledTheme {
    static final String HOOK_FAMILY = "amoled dark mode";
    static final String STOCK_ATTRIBUTE = "hushfeed_amoled_stock_";
    static final String RESTORE_STYLE = "hushfeed_amoled_restore_";
    static final String RESTORE_ALL_STYLE = "hushfeed_amoled_restore_all";

    /** More than any build has: 47.7.3 keeps about twenty. */
    private static final int MAX_SURFACES = 256;

    /** The restore resources, read by name once. */
    static final class Ids {
        static final Ids NONE = new Ids(new int[0], new int[0], 0);

        final int[] stockAttributes;
        final int[] restoreStyles;
        final int restoreAll;

        Ids(int[] stockAttributes, int[] restoreStyles, int restoreAll) {
            this.stockAttributes = stockAttributes;
            this.restoreStyles = restoreStyles;
            this.restoreAll = restoreAll;
        }
    }

    @Nullable
    private static volatile Boolean decided;
    @Nullable
    private static volatile Ids ids;
    private static volatile int colorForTests;

    private AmoledTheme() {}

    /** True unless the switch is off (or Hushfeed is paused) in a build with AMOLED dark theme. */
    public static boolean on() {
        return !restoring();
    }

    /** Whether TikTok's own grays go back on the themes it builds. */
    static boolean restoring() {
        Boolean known = decided;
        if (known != null) return known;
        // Settings can't load before the app has a context; TikTok builds no screen before then.
        if (amoledColor() == 0 || Utils.getContext() == null) return false;
        boolean restore = !Settings.AMOLED_DARK_MODE.get();
        decided = restore;
        HookStatus.bound(HOOK_FAMILY, restore ? "TikTok's dark grays" : "AMOLED");
        return restore;
    }

    /** Stands in for Resources.Theme.applyStyle in TikTok's code. The force flag comes as an int. */
    public static void applyStyle(Resources.Theme theme, int resId, int force) {
        theme.applyStyle(resId, force != 0);
        restore(theme);
    }

    /** Stands in for setTheme(int) on a Context in TikTok's code. */
    public static void setTheme(Context context, int resId) {
        context.setTheme(resId);
        restore(context);
    }

    /** Called right after a Dialog's constructor, before its window reads the theme. */
    public static void dialog(Dialog dialog) {
        if (dialog == null || !restoring()) return;
        try {
            restore(dialog.getContext());
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "dialog", failure);
        }
    }

    /** Called at the end of AppCompatActivity's setTheme with the activity. */
    public static void restore(Context context) {
        if (context == null || !restoring()) return;
        try {
            restore(context.getTheme());
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "setTheme", failure);
        }
    }

    /** Puts TikTok's own value back on each surface this theme sets. Never throws. */
    public static void restore(Resources.Theme theme) {
        if (theme == null || !restoring()) return;
        try {
            for (int style : plan(theme, ids(theme.getResources()))) {
                theme.applyStyle(style, true);
            }
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "applyStyle", failure);
        }
    }

    /**
     * The restore styles this theme takes: the one for all surfaces when it sets every one, or
     * the one for each surface it sets. A surface the theme never set stays unset, since its
     * restore style would point it at a value that isn't there.
     */
    static int[] plan(Resources.Theme theme, Ids ids) {
        int count = ids.stockAttributes.length;
        if (count == 0) return new int[0];
        boolean[] present = new boolean[count];
        int found = 0;
        TypedValue value = new TypedValue();
        for (int i = 0; i < count; i++) {
            if (theme.resolveAttribute(ids.stockAttributes[i], value, false)) {
                present[i] = true;
                found++;
            }
        }
        if (found == count && ids.restoreAll != 0) return new int[]{ids.restoreAll};
        int[] styles = new int[found];
        int at = 0;
        for (int i = 0; i < count; i++) {
            if (present[i]) styles[at++] = ids.restoreStyles[i];
        }
        return styles;
    }

    private static Ids ids(Resources resources) {
        Ids known = ids;
        if (known != null) return known;
        Context context = Utils.getContext();
        Ids found = context == null ? Ids.NONE : lookUp(resources, context.getPackageName());
        if (found.stockAttributes.length == 0) {
            HookStatus.missingMember(HOOK_FAMILY, "style", "res", RESTORE_ALL_STYLE);
        }
        ids = found;
        return found;
    }

    /** The restore resources in [packageName], numbered from 0 until one is missing. */
    @SuppressWarnings("DiscouragedApi")
    static Ids lookUp(Resources resources, String packageName) {
        int[] stock = new int[MAX_SURFACES];
        int[] restore = new int[MAX_SURFACES];
        int count = 0;
        while (count < MAX_SURFACES) {
            int attribute = resources.getIdentifier(STOCK_ATTRIBUTE + count, "attr", packageName);
            int style = resources.getIdentifier(RESTORE_STYLE + count, "style", packageName);
            if (attribute == 0 || style == 0) break;
            stock[count] = attribute;
            restore[count] = style;
            count++;
        }
        if (count == 0) return Ids.NONE;
        int[] stockAttributes = new int[count];
        int[] restoreStyles = new int[count];
        System.arraycopy(stock, 0, stockAttributes, 0, count);
        System.arraycopy(restore, 0, restoreStyles, 0, count);
        return new Ids(stockAttributes, restoreStyles,
                resources.getIdentifier(RESTORE_ALL_STYLE, "style", packageName));
    }

    /**
     * A Compose card color in TikTok's dark palette, packed the way Compose packs one: the AMOLED
     * color while the switch is on, TikTok's own while it's off.
     */
    public static long composeCard(long packed) {
        int argb = amoledColor();
        if (argb == 0 || restoring()) return packed;
        return ((long) argb & 0xFFFFFFFFL) << 32;
    }

    private static int amoledColor() {
        int forTests = colorForTests;
        return forTests != 0 ? forTests : color();
    }

    /** The AMOLED color, filled in by the patch. Zero means AMOLED dark theme isn't in this build. */
    private static int color() {
        return 0;
    }

    static void setForTests(int argb, @Nullable Ids lookedUp) {
        colorForTests = argb;
        ids = lookedUp;
        decided = null;
    }

    static void resetForTests() {
        setForTests(0, null);
    }
}
