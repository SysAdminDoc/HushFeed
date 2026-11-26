package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SettingsRegistryRule;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.main.MainActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Method;

/**
 * Hushfeed's controls belong over a video in the feed. On 48.0.2 Home's top tabs hold Community,
 * a grid of videos in the same sideways pager as For You, and the Home tab stays shown and
 * selected there, so the block button floated over the grid for the last video watched (Login2,
 * 2026-11-26).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TopTabControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    private static final String BLOCK = "Block this creator";
    private static final int HOME_TAB_ID = 0x7f0a4b89;

    private String packageName;

    @Before public void setUp() {
        CurrentVideoAuthor.resetForTests();
        Settings.BLOCK_AUTHOR_BUTTON.save(true);
    }

    @After public void tearDown() throws Exception {
        Method detach = BlockAuthorOverlay.class.getDeclaredMethod("detach");
        detach.setAccessible(true);
        detach.invoke(null);
        Settings.BLOCK_AUTHOR_BUTTON.resetToDefault();
        CurrentVideoAuthor.resetForTests();
        Utils.setActivity(null);
        // The id cache is process-wide: a tab id left resolved answers for whatever runs next.
        if (packageName != null) FeedVisibility.resolveForTests(packageName, "48.0.2:ose", 0);
    }

    @Test public void theControlsLeaveForCommunitysGridAndComeBackWithTheFeed() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        packageName = activity.getPackageName();

        // Home: the top tabs' pager with For You's feed and Community's grid beside it, and the
        // Home tab under them, shown and selected on both.
        FrameLayout root = new FrameLayout(activity);
        FrameLayout topTabs = new FrameLayout(activity);
        FrameLayout forYou = new FrameLayout(activity);
        forYou.addView(new VerticalViewPager(activity), matchParent());
        FrameLayout community = new FrameLayout(activity);
        community.addView(new ScrollView(activity), matchParent());
        topTabs.addView(forYou, matchParent());
        topTabs.addView(community, matchParent());
        root.addView(topTabs, matchParent());
        View homeTab = new View(activity);
        homeTab.setId(HOME_TAB_ID);
        homeTab.setSelected(true);
        root.addView(homeTab, new FrameLayout.LayoutParams(60, 40, Gravity.BOTTOM));
        activity.setContentView(root);
        idle();
        int width = topTabs.getWidth();
        community.setTranslationX(-width);
        FeedVisibility.resolveForTests(packageName, "48.0.2:ose", HOME_TAB_ID);

        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params("clip_one", "creator"));
        CurrentVideoAuthor.onPlaying("clip_one");
        idle();
        View block = control(activity, BLOCK);
        assertEquals("no block button over the feed's video", View.VISIBLE, block.getVisibility());

        // Swiped to Community: the Home tab is still shown and selected, the feed is aside.
        topTabs.scrollTo(-width, 0);
        BlockAuthorOverlay.refresh();
        idle();
        assertEquals("the block button floated over Community's grid", View.GONE, block.getVisibility());

        // Back on For You: the controls come back with the video.
        topTabs.scrollTo(0, 0);
        BlockAuthorOverlay.refresh();
        idle();
        assertEquals(View.VISIBLE, block.getVisibility());
        controller.pause().stop().destroy();
    }

    private static View control(MainActivity activity, String description) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            if (description.contentEquals(String.valueOf(child.getContentDescription()))) return child;
        }
        fail("no control named " + description);
        return null;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
