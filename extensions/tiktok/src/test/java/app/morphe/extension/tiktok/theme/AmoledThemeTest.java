/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.webkit.WebView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.live.LivePanelTheme;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The AMOLED dark mode switch (#372). With AMOLED dark theme patched in, the switch starts on and
 * nothing changes; off, every theme TikTok builds gets TikTok's own grays back, and the Compose
 * cards, the comment bar and the web pages keep TikTok's look. Here Material's light theme
 * stands in for the restore styles, so a restored theme reads light where the stock one reads dark.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AmoledThemeTest {
    private static final int BLACK = 0xFF000000;
    private static final int MOCHA = 0xFF181825;
    private static final String ACTIVITY_CENTER =
            "https://inapp.tiktokv.com/tpp/inapp/pns_product_activity_center/ac/home.html";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        AmoledTheme.resetForTests();
        HookStatus.clear();
    }

    @After public void tearDown() {
        AmoledTheme.resetForTests();
        PausedProcess.set(false);
        Settings.AMOLED_DARK_MODE.save(true);
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
    }

    private static Application app() {
        return RuntimeEnvironment.getApplication();
    }

    /** One stock attribute, the background, and Material's light theme as its restore style. */
    private static AmoledTheme.Ids lightRestore() {
        return new AmoledTheme.Ids(
                new int[]{android.R.attr.colorBackground},
                new int[]{android.R.style.Theme_Material_Light},
                android.R.style.Theme_Material_Light);
    }

    private static Resources.Theme theme(int style) {
        Resources.Theme theme = app().getResources().newTheme();
        theme.applyStyle(style, true);
        return theme;
    }

    private static int background(Resources.Theme theme) {
        TypedValue value = new TypedValue();
        assertTrue("the theme has a background", theme.resolveAttribute(android.R.attr.colorBackground, value, true));
        return value.data;
    }

    private static void switchOff() {
        Settings.AMOLED_DARK_MODE.save(false);
    }

    @Test public void theSwitchStartsOn() {
        assertTrue(Settings.AMOLED_DARK_MODE.defaultValue);
        AmoledTheme.setForTests(BLACK, lightRestore());
        assertTrue(AmoledTheme.on());
        assertFalse(AmoledTheme.restoring());
    }

    @Test public void offPutsTikToksGraysBack() {
        switchOff();
        AmoledTheme.setForTests(BLACK, lightRestore());
        assertFalse(AmoledTheme.on());
        assertTrue(AmoledTheme.restoring());
    }

    @Test public void withoutAmoledPatchedInNothingChanges() {
        switchOff();
        assertTrue("no color was baked in", AmoledTheme.on());
        long tikToks = 0xFF1E1E1E00000000L;
        assertEquals(tikToks, AmoledTheme.composeCard(tikToks));
        Resources.Theme stock = theme(android.R.style.Theme_Material);
        int dark = background(stock);
        AmoledTheme.restore(stock);
        assertEquals(dark, background(stock));
    }

    @Test public void theAnswerHoldsUntilTheNextStart() {
        switchOff();
        AmoledTheme.setForTests(BLACK, lightRestore());
        assertFalse(AmoledTheme.on());
        Settings.AMOLED_DARK_MODE.save(true);
        assertFalse("a screen never mixes the two looks", AmoledTheme.on());
    }

    @Test public void beforeTheAppHasAContextNothingIsDecided() {
        switchOff();
        AmoledTheme.setForTests(BLACK, lightRestore());
        Utils.setContext(null);
        try {
            assertTrue(AmoledTheme.on());
        } finally {
            Utils.setContext(app());
        }
        assertFalse("decided once there's a context", AmoledTheme.on());
    }

    @Test public void pausingHushfeedReadsAsOff() {
        AmoledTheme.setForTests(BLACK, lightRestore());
        PausedProcess.set(true);
        assertFalse(AmoledTheme.on());
    }

    @Test public void composeCardsTakeTheColorOnlyWhileOn() {
        long tikToks = 0xFF1E1E1E00000000L;
        AmoledTheme.setForTests(MOCHA, lightRestore());
        assertEquals("packed as Compose packs an sRGB color", 0xFF18182500000000L, AmoledTheme.composeCard(tikToks));
        AmoledTheme.setForTests(BLACK, lightRestore());
        assertEquals(0xFF00000000000000L, AmoledTheme.composeCard(tikToks));

        switchOff();
        AmoledTheme.setForTests(MOCHA, lightRestore());
        assertEquals(tikToks, AmoledTheme.composeCard(tikToks));
    }

    /** #329: the LIVE comment panel takes a picked color while on; black, off and unpatched keep its grays. */
    @Test public void theLiveCommentPanelTakesOnlyAPickedColorWhileOn() {
        AmoledTheme.setForTests(MOCHA, lightRestore());
        assertEquals(MOCHA, AmoledTheme.customColor());
        AmoledTheme.setForTests(BLACK, lightRestore());
        assertEquals("the default black", 0, AmoledTheme.customColor());
        AmoledTheme.resetForTests();
        assertEquals("not patched in", 0, AmoledTheme.customColor());

        switchOff();
        AmoledTheme.setForTests(MOCHA, lightRestore());
        assertEquals(0, AmoledTheme.customColor());
    }

    @Test public void aThemeSettingEverySurfaceTakesTheStyleForAll() {
        AmoledTheme.Ids ids = new AmoledTheme.Ids(
                new int[]{android.R.attr.colorBackground, android.R.attr.textColorPrimary}, new int[]{11, 12}, 99);
        assertArrayEquals(new int[]{99}, AmoledTheme.plan(theme(android.R.style.Theme_Material), ids));
    }

    @Test public void aSurfaceTheThemeNeverSetStaysUnset() {
        AmoledTheme.Ids ids = new AmoledTheme.Ids(
                new int[]{android.R.attr.colorBackground, android.R.attr.layout_width}, new int[]{11, 12}, 99);
        assertArrayEquals(new int[]{11}, AmoledTheme.plan(theme(android.R.style.Theme_Material), ids));
    }

    @Test public void withoutTheStyleForAllEachSurfaceGetsItsOwn() {
        AmoledTheme.Ids ids = new AmoledTheme.Ids(
                new int[]{android.R.attr.colorBackground, android.R.attr.textColorPrimary}, new int[]{11, 12}, 0);
        assertArrayEquals(new int[]{11, 12}, AmoledTheme.plan(theme(android.R.style.Theme_Material), ids));
        assertEquals(0, AmoledTheme.plan(theme(android.R.style.Theme_Material), AmoledTheme.Ids.NONE).length);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void lookUpNumbersTheResourcesUntilOneIsMissing() {
        Map<String, Integer> named = new HashMap<>();
        named.put("attr/hushfeed_amoled_stock_0", 0x7f010001);
        named.put("attr/hushfeed_amoled_stock_1", 0x7f010002);
        named.put("attr/hushfeed_amoled_stock_2", 0x7f010003);
        named.put("style/hushfeed_amoled_restore_0", 0x7f100001);
        named.put("style/hushfeed_amoled_restore_1", 0x7f100002);
        named.put("style/hushfeed_amoled_restore_all", 0x7f100009);
        Resources base = app().getResources();
        Resources resources = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override
            public int getIdentifier(String name, String defType, String defPackage) {
                assertEquals("com.zhiliaoapp.musically", defPackage);
                Integer id = named.get(defType + "/" + name);
                return id == null ? 0 : id;
            }
        };

        AmoledTheme.Ids ids = AmoledTheme.lookUp(resources, "com.zhiliaoapp.musically");

        assertArrayEquals(new int[]{0x7f010001, 0x7f010002}, ids.stockAttributes);
        assertArrayEquals(new int[]{0x7f100001, 0x7f100002}, ids.restoreStyles);
        assertEquals(0x7f100009, ids.restoreAll);
    }

    @Test public void anApkWithoutTheResourcesHasNone() {
        assertSame(AmoledTheme.Ids.NONE, AmoledTheme.lookUp(app().getResources(), app().getPackageName()));
    }

    @Test public void whileOffEveryThemeTikTokBuildsGetsItsGraysBack() {
        int dark = background(theme(android.R.style.Theme_Material));
        int light = background(theme(android.R.style.Theme_Material_Light));
        assertNotEquals(dark, light);
        switchOff();
        AmoledTheme.setForTests(BLACK, lightRestore());

        Resources.Theme styled = app().getResources().newTheme();
        AmoledTheme.applyStyle(styled, android.R.style.Theme_Material, 1);
        assertEquals("applyStyle", light, background(styled));

        ContextThemeWrapper scene = new ContextThemeWrapper(app(), 0);
        AmoledTheme.setTheme(scene, android.R.style.Theme_Material);
        assertEquals("setTheme", light, background(scene.getTheme()));

        AmoledThemeWrapper wrapper = new AmoledThemeWrapper(app(), android.R.style.Theme_Material);
        assertEquals("a wrapper TikTok makes", light, background(wrapper.getTheme()));

        Dialog dialog = new Dialog(app(), android.R.style.Theme_Material);
        AmoledTheme.dialog(dialog);
        assertEquals("a sheet or dialog", light, background(dialog.getContext().getTheme()));

        Resources.Theme activity = theme(android.R.style.Theme_Material);
        AmoledTheme.restore(activity);
        assertEquals("a screen's theme", light, background(activity));
    }

    /**
     * The LIVE room hook takes three of TikTok's applyStyle calls for itself (#362), in either
     * patch order, so its stand-in has to put the grays back the same way.
     */
    @Test public void aLiveRoomStyleFollowsTheSwitchToo() {
        int dark = background(theme(android.R.style.Theme_Material));
        int light = background(theme(android.R.style.Theme_Material_Light));
        AmoledTheme.setForTests(BLACK, lightRestore());
        Resources.Theme on = app().getResources().newTheme();
        LivePanelTheme.roomStyle(on, android.R.style.Theme_Material, 1);
        assertEquals("on", dark, background(on));

        switchOff();
        AmoledTheme.setForTests(BLACK, lightRestore());
        Resources.Theme off = app().getResources().newTheme();
        LivePanelTheme.roomStyle(off, android.R.style.Theme_Material, 1);
        assertEquals("off", light, background(off));
    }

    @Test public void whileOnTheThemesKeepTheAmoledLook() {
        int dark = background(theme(android.R.style.Theme_Material));
        AmoledTheme.setForTests(BLACK, lightRestore());

        Resources.Theme styled = app().getResources().newTheme();
        AmoledTheme.applyStyle(styled, android.R.style.Theme_Material, 1);
        assertEquals(dark, background(styled));

        ContextThemeWrapper scene = new ContextThemeWrapper(app(), 0);
        AmoledTheme.setTheme(scene, android.R.style.Theme_Material);
        assertEquals(dark, background(scene.getTheme()));

        assertEquals(dark, background(new AmoledThemeWrapper(app(), android.R.style.Theme_Material).getTheme()));

        Dialog dialog = new Dialog(app(), android.R.style.Theme_Material);
        AmoledTheme.dialog(dialog);
        assertEquals(dark, background(dialog.getContext().getTheme()));
        AmoledTheme.dialog(null);
        AmoledTheme.restore((Resources.Theme) null);
        AmoledTheme.restore((android.content.Context) null);
    }

    /** The force flag comes in as an int, and zero keeps what the theme already set. */
    @Test public void applyStyleKeepsTikToksForceFlag() {
        AmoledTheme.setForTests(BLACK, lightRestore());
        int dark = background(theme(android.R.style.Theme_Material));
        int light = background(theme(android.R.style.Theme_Material_Light));

        Resources.Theme kept = theme(android.R.style.Theme_Material_Light);
        AmoledTheme.applyStyle(kept, android.R.style.Theme_Material, 0);
        assertEquals(light, background(kept));

        Resources.Theme forced = theme(android.R.style.Theme_Material_Light);
        AmoledTheme.applyStyle(forced, android.R.style.Theme_Material, 1);
        assertEquals(dark, background(forced));
    }

    @Test
    @Config(qualifiers = "night")
    public void theCommentBarFollowsTheSwitch() {
        AmoledTheme.setForTests(BLACK, lightRestore());
        View painted = new View(app());
        AmoledInputBar.paint(painted, MOCHA);
        assertEquals(MOCHA, ((ColorDrawable) painted.getBackground()).getColor());

        switchOff();
        AmoledTheme.setForTests(BLACK, lightRestore());
        View stock = new View(app());
        AmoledInputBar.paint(stock, MOCHA);
        assertNull("off, the bar keeps TikTok's look", stock.getBackground());
    }

    @Test public void webPagesFollowTheSwitch() {
        AmoledTheme.setForTests(BLACK, lightRestore());
        WebView painted = new WebView(app());
        AmoledWebPages.onPage(painted, ACTIVITY_CENTER, BLACK);
        assertNotNull(Shadows.shadowOf(painted).getLastEvaluatedJavascript());

        switchOff();
        AmoledTheme.setForTests(BLACK, lightRestore());
        WebView stock = new WebView(app());
        AmoledWebPages.onPage(stock, ACTIVITY_CENTER, BLACK);
        assertNull("off, the page keeps TikTok's look", Shadows.shadowOf(stock).getLastEvaluatedJavascript());
    }
}
