package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Remove avatar rings: what the story status and the live checks answer per screen, and the rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AvatarRingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's social avatar screens, as the entry's enum names them. */
    private enum Scene {
        FEED, COMMENT, MY_PROFILE, OTHER_PROFILE, INBOX, SEARCH, INBOX_TOP_LIST, FOLLOW_SKYLIGHT, FRIENDS_SKYLIGHT
    }

    private static final int CHAT_SCENE = 8;

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private static BooleanSetting[] surfaces() {
        return new BooleanSetting[]{Settings.HIDE_RINGS_FEED, Settings.HIDE_RINGS_COMMENTS, Settings.HIDE_RINGS_PROFILES};
    }

    @After public void tearDown() {
        Settings.HIDE_STORY_RINGS.save(false);
        Settings.HIDE_LIVE_RING.save(false);
        for (BooleanSetting surface : surfaces()) surface.save(true);
        SettingsStatus.avatarRingsEnabled = false;
    }

    @Test public void offLeavesTikTokItsOwnAnswers() {
        for (int status : new int[]{0, 1, 2, 3}) {
            assertEquals(status, AvatarRings.storyStatus(status));
            for (Scene scene : Scene.values()) assertEquals(status, AvatarRings.storyStatusIn(status, scene));
        }
        assertTrue(AvatarRings.authorLive(true));
        assertFalse(AvatarRings.authorLive(false));
        for (int scene : new int[]{AvatarRings.NO_SCENE, AvatarRings.PROFILE_SCENE, CHAT_SCENE}) {
            assertFalse("scene " + scene, AvatarRings.hideLiveIn(scene));
        }
    }

    @Test public void eachSwitchOnlyTakesItsOwnRing() {
        Settings.HIDE_STORY_RINGS.save(true);
        assertEquals(0, AvatarRings.storyStatus(1));
        assertEquals(0, AvatarRings.storyStatus(2));
        assertEquals(0, AvatarRings.storyStatusIn(1, Scene.COMMENT));
        assertEquals(0, AvatarRings.storyStatusIn(3, Scene.OTHER_PROFILE));
        assertTrue("story switch leaves LIVE alone", AvatarRings.authorLive(true));
        assertFalse(AvatarRings.hideLiveIn(AvatarRings.PROFILE_SCENE));

        Settings.HIDE_STORY_RINGS.save(false);
        Settings.HIDE_LIVE_RING.save(true);
        assertEquals("LIVE switch leaves stories alone", 1, AvatarRings.storyStatus(1));
        assertEquals(1, AvatarRings.storyStatusIn(1, Scene.COMMENT));
        assertFalse(AvatarRings.authorLive(true));
        assertFalse(AvatarRings.authorLive(false));
        assertTrue(AvatarRings.hideLiveIn(AvatarRings.PROFILE_SCENE));
        assertTrue(AvatarRings.hideLiveIn(AvatarRings.NO_SCENE));
        assertTrue(AvatarRings.hideLiveIn(CHAT_SCENE));
    }

    /** #351: the feed, comment and profile switches each keep their own screen's rings. */
    @Test public void eachSurfaceSwitchKeepsOnlyItsOwnScreensRings() {
        Settings.HIDE_STORY_RINGS.save(true);
        Settings.HIDE_LIVE_RING.save(true);

        Settings.HIDE_RINGS_FEED.save(false);
        assertEquals("feed story ring kept", 1, AvatarRings.storyStatus(1));
        assertEquals(1, AvatarRings.storyStatusIn(1, Scene.FEED));
        assertTrue("feed LIVE ring kept", AvatarRings.authorLive(true));
        assertEquals(0, AvatarRings.storyStatusIn(1, Scene.COMMENT));
        assertEquals(0, AvatarRings.storyStatusIn(1, Scene.OTHER_PROFILE));
        Settings.HIDE_RINGS_FEED.save(true);

        Settings.HIDE_RINGS_COMMENTS.save(false);
        assertEquals("comment story ring kept", 2, AvatarRings.storyStatusIn(2, Scene.COMMENT));
        assertFalse("comment LIVE ring kept", AvatarRings.hideLiveIn(AvatarRings.NO_SCENE));
        assertEquals(0, AvatarRings.storyStatus(1));
        assertTrue(AvatarRings.hideLiveIn(AvatarRings.PROFILE_SCENE));
        Settings.HIDE_RINGS_COMMENTS.save(true);

        Settings.HIDE_RINGS_PROFILES.save(false);
        assertEquals(1, AvatarRings.storyStatusIn(1, Scene.MY_PROFILE));
        assertEquals(1, AvatarRings.storyStatusIn(1, Scene.OTHER_PROFILE));
        assertFalse("profile LIVE ring kept", AvatarRings.hideLiveIn(AvatarRings.PROFILE_SCENE));
        assertEquals(0, AvatarRings.storyStatusIn(1, Scene.FEED));
        assertTrue(AvatarRings.hideLiveIn(AvatarRings.NO_SCENE));

        for (BooleanSetting surface : surfaces()) surface.save(false);
        assertEquals("the Inbox follows the ring switch alone", 0, AvatarRings.storyStatusIn(1, Scene.INBOX));
        assertEquals(0, AvatarRings.storyStatusIn(1, Scene.SEARCH));
        assertEquals("an unknown screen follows it too", 0, AvatarRings.storyStatusIn(1, null));
        assertTrue("chats follow the LIVE switch alone", AvatarRings.hideLiveIn(CHAT_SCENE));
    }

    /** #351: TikTok drops a "no story" user from a stories tray, so the trays keep their status. */
    @Test public void theStoriesTraysKeepEveryoneWithAStory() {
        Settings.HIDE_STORY_RINGS.save(true);
        for (int status : new int[]{1, 2, 3}) {
            assertEquals("Inbox tray", status, AvatarRings.storyStatusIn(status, Scene.INBOX_TOP_LIST));
            assertEquals("Following bar", status, AvatarRings.storyStatusIn(status, Scene.FOLLOW_SKYLIGHT));
            assertEquals("Friends bar", status, AvatarRings.storyStatusIn(status, Scene.FRIENDS_SKYLIGHT));
        }
        assertEquals("the Inbox's chat rows still lose theirs", 0, AvatarRings.storyStatusIn(1, Scene.INBOX));
        assertEquals(0, AvatarRings.storyStatusIn(1, Scene.FEED));
    }

    @Test public void theSwitchesAreOnTheFeedScreenPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.avatarRingsEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.HIDE_STORY_RINGS.key));
            assertNull(without.findPreference(Settings.HIDE_LIVE_RING.key));
            for (BooleanSetting surface : surfaces()) assertNull(without.findPreference(surface.key));

            SettingsStatus.avatarRingsEnabled = true;
            assertTrue(InterfacePreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.HIDE_STORY_RINGS.key));
            assertNotNull(with.findPreference(Settings.HIDE_LIVE_RING.key));
            for (BooleanSetting surface : surfaces()) assertNotNull(surface.key, with.findPreference(surface.key));
        }
    }
}
