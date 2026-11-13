package app.morphe.extension.tiktok.foldable;

import static org.junit.Assert.*;
import android.content.res.Configuration;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30}, qualifiers = "w700dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FoldableSplitViewTest {
    /**
     * The newest API above, and the only run that publishes a picture.
     *
     * <p>Both runs used to write foldable-settings.png, so which one ended up in assets depended
     * on which finished last. Guarded at the call site rather than by pinning the method to one
     * API, which would give up the API 28 run of everything else the case asserts.
     */
    private static final int PUBLISHED_CAPTURE_SDK = 30;

    public static class TestActivity extends android.preference.PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
        boolean multiWindow, pictureInPicture;
        @Override public boolean isInMultiWindowMode() { return multiWindow; }
        @Override public boolean isInPictureInPictureMode() { return pictureInPicture; }
    }
    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Utils.setActivity(null);
        Settings.FOLDABLE_SPLIT_VIEW.save(false);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
    }
    @After public void tearDown() {
        SettingsStatus.foldableSplitViewEnabled = false;
        FoldableSplitView.forgetForTests();
        Utils.setActivity(null);
    }

    private static Configuration width(TestActivity activity, int dp) {
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.screenWidthDp = dp;
        return configuration;
    }

    /** A report says whether TikTok asked at all, which a phone that never splits can't show. */
    @Test public void everyAnswerTikTokGetsGoesIntoHookStatus() {
        HookStatus.clear();
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(320);
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertTrue(FoldableSplitView.shouldForceContainer());
            assertTrue(FoldableSplitView.shouldForce(activity, width(activity, 700)));
            assertTrue(String.join(" ", HookStatus.report()).contains(FoldableSplitView.FAMILY));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * TikTok's server sends 1, and the feed then registers its split container only on a
     * tablet, so a wide phone window never split. With the switch off TikTok's value stands.
     */
    @Test public void theFeedRegistersItsContainerWhateverTheServerSaysOnlyWithTheSwitchOn() {
        HookStatus.clear();
        try {
            assertEquals(1L, FoldableSplitView.splitContainerInit(1L));
            assertEquals(-1L, FoldableSplitView.splitContainerInit(-1L));
            Settings.FOLDABLE_SPLIT_VIEW.save(true);
            assertEquals(-1L, FoldableSplitView.splitContainerInit(1L));
            assertEquals(-1L, FoldableSplitView.splitContainerInit(0L));
            assertTrue(String.join(" ", HookStatus.report()).contains(FoldableSplitView.FAMILY));
        } finally {
            HookStatus.clear();
        }
    }

    public static class InsetBoundsActivity extends TestActivity {
        boolean metricsUnavailable;
        int metricsReads;
        int metricsAllowed = Integer.MAX_VALUE;
        int windowDp = 620;
        /** A navigation bar down the right side, inside the window's bounds. */
        int barDp;
        @Override public android.view.WindowManager getWindowManager() {
            android.view.WindowManager original = super.getWindowManager();
            return (android.view.WindowManager) java.lang.reflect.Proxy.newProxyInstance(
                    android.view.WindowManager.class.getClassLoader(),
                    new Class<?>[] { android.view.WindowManager.class }, (proxy, method, arguments) -> {
                        if (method.getName().equals("getCurrentWindowMetrics")) {
                            if (++metricsReads > metricsAllowed || metricsUnavailable) {
                                throw new IllegalStateException("window metrics unavailable");
                            }
                            float density = getResources().getDisplayMetrics().density;
                            int width = Math.round(windowDp * density);
                            return new android.view.WindowMetrics(new android.graphics.Rect(0, 0, width, width),
                                    new android.view.WindowInsets.Builder().setInsetsIgnoringVisibility(
                                            android.view.WindowInsets.Type.systemBars(),
                                            android.graphics.Insets.of(0, 0, Math.round(barDp * density), 0)).build());
                        }
                        return method.invoke(original, arguments);
                    });
        }
    }

    /** Window bounds include bars that the configuration width leaves out on API 30, so they come off. */
    @Test @Config(sdk = 30, qualifiers = "w599dp-h900dp")
    public void theWindowWidthLeavesOutTheBarsLikeTheConfigurationWidth() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(InsetBoundsActivity.class).setup()) {
            InsetBoundsActivity activity = owner.get();
            activity.barDp = 21;
            Utils.setActivity(activity);
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            assertEquals(599, configuration.screenWidthDp);
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            assertFalse("the container used bounds that the live split check cannot use",
                    FoldableSplitView.shouldForceContainer());
            FoldableSplitView.onConfigurationChanged(activity, configuration);
            assertEquals(0, rebuilt.size());

            // A supplied new configuration still wins while the old activity window is narrow.
            configuration.screenWidthDp = 600;
            assertTrue(FoldableSplitView.shouldForce(activity, configuration));
            FoldableSplitView.onConfigurationChanged(activity, configuration);
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test @Config(sdk = 30, qualifiers = "w599dp-h900dp")
    public void theMetricsFallbackAlsoRecordsTheWidthOfTheBuiltContainer() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(InsetBoundsActivity.class).setup()) {
            InsetBoundsActivity activity = owner.get();
            Utils.setActivity(activity);
            Configuration original = new Configuration(activity.getResources().getConfiguration());
            try {
                activity.getResources().getConfiguration().screenWidthDp = 0;
                assertTrue(FoldableSplitView.shouldForceContainer());
                FoldableSplitView.onConfigurationChanged(activity, width(activity, 620));
                assertEquals("a metrics-wide build was recorded as narrow", 0, rebuilt.size());
                FoldableSplitView.onConfigurationChanged(activity, width(activity, 599));
                assertEquals(java.util.Collections.singletonList(activity), rebuilt);
            } finally {
                activity.getResources().getConfiguration().setTo(original);
            }
        }
    }

    @Test @Config(sdk = 30, qualifiers = "w599dp-h900dp")
    public void anUnavailableWidthDoesNotRecordOrRebuildANativeContainer() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(InsetBoundsActivity.class).setup()) {
            InsetBoundsActivity activity = owner.get();
            Utils.setActivity(activity);
            Configuration original = new Configuration(activity.getResources().getConfiguration());
            try {
                activity.getResources().getConfiguration().screenWidthDp = 0;
                activity.metricsUnavailable = true;
                assertFalse(FoldableSplitView.shouldForceContainer());
                FoldableSplitView.onConfigurationChanged(activity, width(activity, 620));
                assertEquals("an unavailable width was recorded as narrow", 0, rebuilt.size());

                activity.metricsUnavailable = false;
                assertTrue(FoldableSplitView.shouldForceContainer());
                activity.metricsUnavailable = true;
                FoldableSplitView.onConfigurationChanged(activity, width(activity, 0));
                assertEquals("an unavailable width became a crossing", 0, rebuilt.size());
                FoldableSplitView.onConfigurationChanged(activity, width(activity, 599));
                assertEquals(java.util.Collections.singletonList(activity), rebuilt);
            } finally {
                activity.getResources().getConfiguration().setTo(original);
            }
        }
    }
    @Test @Config(sdk = 30, qualifiers = "w599dp-h900dp")
    public void aContainerUsesOneWidthReadForItsDecisionAndBuildRecord() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(InsetBoundsActivity.class).setup()) {
            InsetBoundsActivity activity = owner.get();
            Utils.setActivity(activity);
            Configuration original = new Configuration(activity.getResources().getConfiguration());
            try {
                activity.getResources().getConfiguration().screenWidthDp = 0;
                activity.metricsReads = 0;
                activity.metricsAllowed = 1;
                assertTrue(FoldableSplitView.shouldForceContainer());
                FoldableSplitView.onConfigurationChanged(activity, width(activity, 599));
                assertEquals("the forced-wide build lost its real narrow crossing",
                        java.util.Collections.singletonList(activity), rebuilt);
                assertEquals("the decision and build record must share one width", 1, activity.metricsReads);
            } finally {
                activity.getResources().getConfiguration().setTo(original);
            }
        }
    }

    /**
     * TikTok keeps the width its process started with in the configuration its activities
     * report, so the activity built again for a wider window still said 384 dp, the container
     * check said no, and comments opened over the video (#398). The window says what it is.
     */
    @Test @Config(sdk = 30, qualifiers = "w384dp-h823dp")
    public void theFeedBuiltAgainForAWiderWindowGetsTheSplitContainer() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(InsetBoundsActivity.class).setup()) {
            InsetBoundsActivity activity = owner.get();
            Utils.setActivity(activity);
            activity.windowDp = 711;
            assertEquals(384, activity.getResources().getConfiguration().screenWidthDp);
            assertTrue(FoldableSplitView.shouldForceContainer());
            assertTrue("the container splits when TikTok asks without a configuration",
                    FoldableSplitView.shouldForce(activity, null));
            // Recorded wide, so the next fold is a crossing.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 384));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    /** The other way round: started unfolded, the activity built again for the folded window. */
    @Test @Config(sdk = 30, qualifiers = "w711dp-h823dp")
    public void theFeedBuiltAgainForANarrowerWindowKeepsCommentsOverTheVideo() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        try (var owner = Robolectric.buildActivity(InsetBoundsActivity.class).setup()) {
            InsetBoundsActivity activity = owner.get();
            Utils.setActivity(activity);
            activity.windowDp = 384;
            assertEquals(711, activity.getResources().getConfiguration().screenWidthDp);
            assertFalse(FoldableSplitView.shouldForceContainer());
            assertFalse(FoldableSplitView.shouldForce(activity, null));
        }
    }

    /** Opened folded, then unfolded with TikTok still open: issue #26. */
    @Test public void aWindowThatCrossesTheThresholdBuildsTheFeedAgainOncePerCrossing() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // TikTok builds the comment containers once, for the 700 dp window it starts in.
            assertFalse(FoldableSplitView.shouldForceContainer());

            FoldableSplitView.onConfigurationChanged(activity, width(activity, 760));
            assertEquals("a change on the same side built the feed again", 0, rebuilt.size());

            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
            // The same crossing reported again, as an unfold passes through several sizes.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 950));
            assertEquals("one crossing built the feed twice", 1, rebuilt.size());
        }
    }

    /**
     * The rebuild let Hide seen videos take the video on screen out of the lists TikTok reads
     * again, so the feed came back on another video (issue #26, second report).
     */
    @Test public void theRebuildKeepsTheVideoThatWasPlaying() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        Settings.HIDE_SEEN_VIDEOS.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertFalse(FoldableSplitView.shouldForceContainer());
            SeenVideoHistory.onPlayProgressChange("42", 5000, 10000);
            assertTrue("watched long enough to be seen", SeenVideoHistory.shouldHide("42"));

            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(1, rebuilt.size());
            assertFalse("the video on screen was filtered out of the rebuilt feed", SeenVideoHistory.shouldHide("42"));
        } finally {
            SeenVideoHistory.clear();
        }
    }

    @Test public void theContainersWereBuiltOnTheFirstAnswerWhateverTikTokAsksLater() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertFalse(FoldableSplitView.shouldForceContainer());
            // TikTok asks again from its layout and playback paths once the window is wide; the
            // containers it built at the start are still the folded ones.
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
            assertTrue(FoldableSplitView.shouldForceContainer());
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void foldingBackIsACrossingToo() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // Started unfolded, so the split containers were built.
            assertTrue(FoldableSplitView.shouldForceContainer());
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void aSwitchTurnedOnMidSessionAppliesAtTheNextStartNotTheNextResize() {
        Settings.FOLDABLE_SPLIT_VIEW.save(false);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // TikTok built its containers with the switch off, which is no split decision.
            assertFalse(FoldableSplitView.shouldForceContainer());

            Settings.FOLDABLE_SPLIT_VIEW.save(true);
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals("the feed was built again for a switch that applies at the next start", 0, rebuilt.size());

            // The control: asked again with the switch on, the 700 dp window is recorded as the
            // narrow side of 800 dp, and the next crossing counts.
            assertFalse(FoldableSplitView.shouldForceContainer());
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void aThresholdEditedMidSessionAppliesAtTheNextStartNotTheNextResize() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            assertFalse(FoldableSplitView.shouldForceContainer());

            // The containers were built for an 800 dp threshold; 760 dp crosses only the new one.
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 760));
            assertEquals("the feed was built again for a threshold that applies at the next start", 0, rebuilt.size());

            // The control: a crossing of the recorded threshold still counts.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 900));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }

    @Test public void nothingIsBuiltAgainWithTheSwitchOffBesideOtherAppsOrForAnActivityThatNeverAsked() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup();
             var other = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // Built wide, so every change below lands on the other side; only what stands in
            // the way keeps each from building the feed again.
            assertTrue(FoldableSplitView.shouldForceContainer());

            // The switch applies at the next start, as its row says, not at the next resize.
            Settings.FOLDABLE_SPLIT_VIEW.save(false);
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            Settings.FOLDABLE_SPLIT_VIEW.save(true);

            // A window beside another app is TikTok's own business.
            activity.multiWindow = true;
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            activity.multiWindow = false;
            activity.pictureInPicture = true;
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            activity.pictureInPicture = false;

            // An activity TikTok never asked about built no comment containers.
            FoldableSplitView.onConfigurationChanged(other.get(), width(other.get(), 380));
            FoldableSplitView.onConfigurationChanged(null, width(activity, 380));
            assertEquals(0, rebuilt.size());

            // The control: the same change on the activity that asked, with nothing in the way.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);
        }
    }
    @Test public void explicitWidthControlsBothSidesOfThresholdAndRespectsWindowModes() {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.screenWidthDp = 700;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            Settings.FOLDABLE_SPLIT_VIEW.save(true);
            assertTrue(FoldableSplitView.shouldForce(activity, configuration));
            configuration.screenWidthDp = 599;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            configuration.screenWidthDp = 600;
            assertTrue(FoldableSplitView.shouldForce(activity, configuration));
            activity.multiWindow = true;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            activity.multiWindow = false;
            activity.pictureInPicture = true;
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
            activity.pictureInPicture = false;
            activity.finish();
            assertFalse(FoldableSplitView.shouldForce(activity, configuration));
        }
    }
    /** Puts a window mode on a configuration the way the system does, through the framework's own setter. */
    private static void windowingMode(Configuration configuration, String setter, int mode) throws Exception {
        Object window = Configuration.class.getField("windowConfiguration").get(configuration);
        window.getClass().getMethod(setter, int.class).invoke(window, mode);
    }

    private static final int FULLSCREEN = 1, SPLIT_SCREEN_PRIMARY = 3, FREEFORM = 5;

    @Test public void onlyAFreeformWindowIsADesktopWindow() throws Exception {
        Configuration configuration = new Configuration();
        assertFalse(FoldableSplitView.isDesktopWindow(configuration));
        windowingMode(configuration, "setWindowingMode", FREEFORM);
        assertTrue(FoldableSplitView.isDesktopWindow(configuration));
        windowingMode(configuration, "setWindowingMode", SPLIT_SCREEN_PRIMARY);
        assertFalse(FoldableSplitView.isDesktopWindow(configuration));
        windowingMode(configuration, "setWindowingMode", FULLSCREEN);
        assertFalse(FoldableSplitView.isDesktopWindow(configuration));
        assertFalse(FoldableSplitView.isDesktopWindow(null));
        // A full-screen app on a desktop display carries the display's freeform beside its own mode.
        Object window = Configuration.class.getField("windowConfiguration").get(configuration);
        boolean displayMode = java.util.Arrays.stream(window.getClass().getMethods())
                .anyMatch(method -> method.getName().equals("setDisplayWindowingMode"));
        if (displayMode) {
            windowingMode(configuration, "setDisplayWindowingMode", FREEFORM);
            assertTrue(configuration.toString().contains("mDisplayWindowingMode=freeform"));
            assertFalse(FoldableSplitView.isDesktopWindow(configuration));
        }
    }

    @Test public void aDesktopWindowSplitsByItsWidthAndIsBuiltAgainWhenResizedAcross() throws Exception {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        java.util.List<android.app.Activity> rebuilt = new java.util.ArrayList<>();
        FoldableSplitView.recreator = rebuilt::add;
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            TestActivity activity = owner.get();
            Utils.setActivity(activity);
            // A Googlebook's TikTok window reports multi-window like split screen does (#405).
            activity.multiWindow = true;
            assertFalse(FoldableSplitView.shouldForce(activity, width(activity, 900)));

            windowingMode(activity.getResources().getConfiguration(), "setWindowingMode", FREEFORM);
            assertTrue(FoldableSplitView.shouldForce(activity, width(activity, 900)));
            assertFalse(FoldableSplitView.shouldForce(activity, width(activity, 599)));
            assertTrue(FoldableSplitView.shouldForceContainer());

            // Dragged narrower than the threshold, the feed is built again for comments over the video.
            FoldableSplitView.onConfigurationChanged(activity, width(activity, 380));
            assertEquals(java.util.Collections.singletonList(activity), rebuilt);

            // Picture in picture still leaves the comments to TikTok, desktop window or not.
            activity.pictureInPicture = true;
            assertFalse(FoldableSplitView.shouldForce(activity, width(activity, 900)));
        }
    }

    @Test public void containerUsesCurrentActivityAndNeverRecreatesIt() {
        Settings.FOLDABLE_SPLIT_VIEW.save(true);
        assertFalse(FoldableSplitView.shouldForceContainer());
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setActivity(activity);
            assertTrue(FoldableSplitView.shouldForceContainer());
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(800);
            assertFalse(FoldableSplitView.shouldForceContainer());
            Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.save(600);
            activity.multiWindow = true;
            assertFalse(FoldableSplitView.shouldForceContainer());
            assertFalse(activity.isChangingConfigurations());
        }
        Utils.setActivity(null);
    }
    @Test public void controlsAreReachableAndWidthInputIsBounded() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            SettingsStatus.foldableSplitViewEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("foldable_split_view"));
            var width = (app.morphe.extension.tiktok.settings.preference.NumberInputPreference) screen.findPreference("foldable_split_view_min_width_dp");
            width.setValue("-1");
            assertEquals("320", width.getValue());
            width.setValue("600");
            activity.setPreferenceScreen(screen);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (android.os.Build.VERSION.SDK_INT == PUBLISHED_CAPTURE_SDK) {
                app.morphe.extension.tiktok.UiCapture.save(
                        activity.getWindow().getDecorView(), "foldable-settings.png");
            }
        }
    }
}
