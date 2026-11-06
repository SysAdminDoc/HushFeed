package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DiscoverTabTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.DISCOVER_BOTTOM_TAB.save(true);
    }

    @After public void tearDown() {
        Settings.DISCOVER_BOTTOM_TAB.save(false);
    }

    /** The US layout TikTok sent on 47.7.2: Friends, then Discover, in the second spot. */
    private static TabConfig usLayout() {
        Map<String, ArrayList<BottomTab>> slots = new LinkedHashMap<>();
        slots.put("0", list(new BottomTab("home", null)));
        slots.put("1", list(new BottomTab("friends", null), new BottomTab("discover", rules("can_show_x"))));
        slots.put("2", list(new BottomTab("shoot", null)));
        slots.put("3", list(new BottomTab("inbox", null)));
        slots.put("4", list(new BottomTab("my_profile", null)));
        return new TabConfig(slots);
    }

    @Test public void discoverAloneTakesTheSecondSpotAndTheOtherFourStayAsSent() {
        TabConfig config = usLayout();
        ArrayList<BottomTab> home = config.bottomTab.get("0");

        Object result = DiscoverTab.apply(DiscoverTab.GATE_KEY, config);

        assertSame(config, result);
        assertEquals(Arrays.asList("0", "1", "2", "3", "4"), new ArrayList<>(config.bottomTab.keySet()));
        List<BottomTab> second = config.bottomTab.get("1");
        assertEquals(1, second.size());
        assertEquals("discover", second.get(0).bottomTabKey);
        // A fresh candidate: TikTok's own Discover carried rules that may be what holds it back.
        assertNull(second.get(0).filterRules);
        assertSame(home, config.bottomTab.get("0"));
        assertEquals("inbox", config.bottomTab.get("3").get(0).bottomTabKey);
    }

    @Test public void aLayoutWithoutDiscoverGetsOneOfTikToksOwnTabClass() {
        TabConfig config = usLayout();
        config.bottomTab.put("1", list(new BottomTab("friends", null)));

        DiscoverTab.apply(DiscoverTab.GATE_KEY, config);

        Object discover = config.bottomTab.get("1").get(0);
        assertSame(BottomTab.class, discover.getClass());
        assertEquals("discover", ((BottomTab) discover).bottomTabKey);
    }

    @Test public void aSecondPassLeavesTheSameDiscoverInPlace() {
        TabConfig config = usLayout();
        DiscoverTab.apply(DiscoverTab.GATE_KEY, config);
        BottomTab first = config.bottomTab.get("1").get(0);

        DiscoverTab.apply(DiscoverTab.GATE_KEY, config);

        assertSame(first, config.bottomTab.get("1").get(0));
    }

    @Test public void aReadOnlyMapIsCopiedWithTheSpotReplaced() {
        TabConfig config = usLayout();
        Map<String, ArrayList<BottomTab>> sent = config.bottomTab;
        TabConfig locked = new TabConfig(Collections.unmodifiableMap(sent));

        DiscoverTab.apply(DiscoverTab.GATE_KEY, locked);

        assertNotSame(sent, locked.bottomTab);
        assertEquals(5, locked.bottomTab.size());
        assertEquals("discover", locked.bottomTab.get("1").get(0).bottomTabKey);
        assertEquals(2, sent.get("1").size());
    }

    @Test public void theSwitchOffOrAnotherGateLeavesTheValueAlone() {
        TabConfig other = usLayout();
        DiscoverTab.apply("some_other_gate", other);
        assertEquals(2, other.bottomTab.get("1").size());

        Settings.DISCOVER_BOTTOM_TAB.save(false);
        TabConfig off = usLayout();
        DiscoverTab.apply(DiscoverTab.GATE_KEY, off);
        assertEquals("friends", off.bottomTab.get("1").get(0).bottomTabKey);

        assertNull(DiscoverTab.apply(DiscoverTab.GATE_KEY, null));
    }

    @Test public void aLayoutWithNoSecondSpotIsLeftForTikTokToJudge() {
        TabConfig config = usLayout();
        config.bottomTab.remove("1");

        DiscoverTab.apply(DiscoverTab.GATE_KEY, config);

        assertEquals(4, config.bottomTab.size());
        assertTrue(!config.bottomTab.containsKey("1"));
    }

    @Test public void theBottomFilterKeepsDiscoverWhileTheSwitchIsOn() {
        Settings.BOTTOM_NAVIGATION.save(true);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,PROFILE," + DiscoverTab.FILTER_KEY);
        Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.save(true);
        try {
            NavigationTabsFilterTest.BottomTab home = new NavigationTabsFilterTest.BottomTab("home");
            NavigationTabsFilterTest.BottomTab discover = new NavigationTabsFilterTest.BottomTab("discover");
            NavigationTabsFilterTest.BottomTab profile = new NavigationTabsFilterTest.BottomTab("me");
            List<?> tabs = Arrays.asList(home, discover, profile);

            assertEquals(Arrays.asList(home, discover, profile), NavigationTabsFilter.filterBottomTabs(tabs));

            Settings.DISCOVER_BOTTOM_TAB.save(false);
            assertEquals(Arrays.asList(home, profile), NavigationTabsFilter.filterBottomTabs(tabs));
        } finally {
            Settings.BOTTOM_NAVIGATION.save(false);
            Settings.BOTTOM_NAVIGATION_TABS.save(BottomNavigationTabOptions.defaultEnabledKeys());
            Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,PROFILE");
            Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.save(false);
        }
    }

    @SafeVarargs
    private static <T> ArrayList<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static Map<String, Boolean> rules(String name) {
        Map<String, Boolean> rules = new LinkedHashMap<>();
        rules.put(name, true);
        return rules;
    }

    /** Shaped like TikTok's com.ss.android.ugc.aweme.experiment.TabConfig. */
    static final class TabConfig {
        public final Map<String, ArrayList<BottomTab>> bottomTab;

        TabConfig(Map<String, ArrayList<BottomTab>> bottomTab) {
            this.bottomTab = bottomTab;
        }
    }

    /** Shaped like TikTok's BottomTab, which has no constructor without arguments. */
    static final class BottomTab {
        public final String bottomTabKey;
        public final Map<String, Boolean> filterRules;

        BottomTab(String bottomTabKey, Map<String, Boolean> filterRules) {
            this.bottomTabKey = bottomTabKey;
            this.filterRules = filterRules;
        }
    }
}
