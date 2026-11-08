/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The profile's Stickers tab (#214): TikTok's owned-set answer, or the profile's own flag as on 47.6.3. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ProfileStickerTabTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void tearDown() {
        PausedProcess.set(false);
        ProfileStickerTab.forgetForTests();
    }

    @Test public void anOwnedSetKeepsTheTab() {
        assertTrue(ProfileStickerTab.show(1, profile(null)));
        assertTrue(ProfileStickerTab.show(1, null));
    }

    @Test public void theProfilesOwnFlagBringsTheTabBack() {
        assertTrue(ProfileStickerTab.show(0, profile(Boolean.TRUE)));
    }

    @Test public void noFlagAFalseFlagOrNoProfileKeepsItHidden() {
        assertFalse(ProfileStickerTab.show(0, profile(Boolean.FALSE)));
        assertFalse(ProfileStickerTab.show(0, profile(null)));
        assertFalse(ProfileStickerTab.show(0, new User("1", null)));
        assertFalse(ProfileStickerTab.show(0, new User("1", new ProfileTabs(null))));
        assertFalse(ProfileStickerTab.show(0, null));
        assertFalse("not a profile at all", ProfileStickerTab.show(0, new Object()));
    }

    @Test public void pausedLeavesTikToksAnswer() {
        PausedProcess.set(true);
        assertFalse(ProfileStickerTab.show(0, profile(Boolean.TRUE)));
        assertTrue(ProfileStickerTab.show(1, profile(Boolean.TRUE)));
    }

    @Test public void aProfileWithNoCuratedSetGetsTheStickerSetsPage() {
        ProfileStickerTab.show(0, new User("7", new ProfileTabs(new StickerTab(Boolean.TRUE))));
        assertFalse(ProfileStickerTab.curatedPage(1, "7"));
    }

    @Test public void anOwnedSetOrAProfileTheGateNeverSawKeepsTheCuratedPage() {
        ProfileStickerTab.show(1, new User("8", null));
        assertTrue(ProfileStickerTab.curatedPage(1, "8"));
        assertTrue("never seen", ProfileStickerTab.curatedPage(1, "9"));
        assertTrue("no uid", ProfileStickerTab.curatedPage(1, null));
    }

    @Test public void withTheExperimentOffTheTabKeepsItsOwnPage() {
        ProfileStickerTab.show(1, new User("10", null));
        assertFalse(ProfileStickerTab.curatedPage(0, "10"));
        assertFalse(ProfileStickerTab.curatedPage(0, null));
    }

    @Test public void theLatestAnswerForAProfileWins() {
        ProfileStickerTab.show(1, new User("11", null));
        ProfileStickerTab.show(0, new User("11", null));
        assertFalse(ProfileStickerTab.curatedPage(1, "11"));
        ProfileStickerTab.show(1, new User("11", null));
        assertTrue(ProfileStickerTab.curatedPage(1, "11"));
    }

    @Test public void onlyTheMostRecentProfilesAreRemembered() {
        ProfileStickerTab.show(0, new User("first", null));
        for (int i = 0; i < 64; i++) ProfileStickerTab.show(1, new User("later" + i, null));
        assertTrue("forgotten, so TikTok's answer stands", ProfileStickerTab.curatedPage(1, "first"));
    }

    @Test public void pausedKeepsTikToksPage() {
        ProfileStickerTab.show(0, new User("12", null));
        PausedProcess.set(true);
        assertTrue(ProfileStickerTab.curatedPage(1, "12"));
    }

    private static User profile(Boolean shown) {
        return new User("1", new ProfileTabs(new StickerTab(shown)));
    }

    /** Shaped like TikTok's profile model: User.getUid(), User.getTabSetting(), ProfileTabs.stickerTab, StickerTab.showStickerTab. */
    static final class User {
        private final String uid;
        private final ProfileTabs tabSetting;
        User(String uid, ProfileTabs tabSetting) {
            this.uid = uid;
            this.tabSetting = tabSetting;
        }
        public String getUid() { return uid; }
        public ProfileTabs getTabSetting() { return tabSetting; }
    }

    static final class ProfileTabs {
        public final StickerTab stickerTab;
        ProfileTabs(StickerTab stickerTab) { this.stickerTab = stickerTab; }
    }

    static final class StickerTab {
        public final Boolean showStickerTab;
        StickerTab(Boolean showStickerTab) { this.showStickerTab = showStickerTab; }
    }
}
