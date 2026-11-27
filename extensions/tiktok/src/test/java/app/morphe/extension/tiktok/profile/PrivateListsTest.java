/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.ss.android.ugc.profile.business.ur.platform.business.RelationFollowerProtocol;
import com.ss.android.ugc.profile.business.ur.platform.business.RelationFollowingProtocol;
import com.ss.android.ugc.profile.business.ur.platform.business.RelationLikeProtocol;
import com.ss.android.ugc.profile.platform.base.data.BizBaseData;
import com.ss.android.ugc.profile.platform.business.header.business.info.business.relationinfo.base.RelationInfoBaseUIComponent;
import com.ss.android.ugc.profile.platform.business.header.business.info.business.relationinfo.data.RelationData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/** Yellow counts for private lists: what the count bridge hands back for each profile count (#549). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class PrivateListsTest {
    private static final int PRIVATE = 2;
    private static final int PUBLIC = 0;

    private boolean previousSwitch, previousStatus;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Utils.setActivity(null);
        previousSwitch = Settings.YELLOW_PRIVATE_LISTS.get();
        previousStatus = SettingsStatus.followStatusEnabled;
        Settings.YELLOW_PRIVATE_LISTS.save(true);
        SettingsStatus.followStatusEnabled = true;
    }

    @After public void tearDown() {
        Settings.YELLOW_PRIVATE_LISTS.save(previousSwitch);
        SettingsStatus.followStatusEnabled = previousStatus;
        Utils.setActivity(null);
    }

    private static RelationFollowerProtocol followers(Integer visibility) {
        RelationFollowerProtocol protocol = new RelationFollowerProtocol();
        protocol.bind(new RelationData(visibility, "1.2K"));
        return protocol;
    }

    private static int yellowOf(CharSequence shown) {
        assertTrue("expected a colored count, got " + shown.getClass().getName(), shown instanceof Spanned);
        Spanned spanned = (Spanned) shown;
        ForegroundColorSpan[] colors = spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
        assertEquals(1, colors.length);
        assertEquals(0, spanned.getSpanStart(colors[0]));
        assertEquals(spanned.length(), spanned.getSpanEnd(colors[0]));
        return colors[0].getForegroundColor();
    }

    @Test public void aPrivateFollowersListShowsItsCountInYellowAndKeepsTheNumber() {
        CharSequence shown = PrivateLists.tint(followers(PRIVATE), "1.2K");
        assertEquals("1.2K", shown.toString());
        assertEquals(PrivateLists.YELLOW_ON_LIGHT, yellowOf(shown));
    }

    @Test public void tikToksDarkThemePicksTheBrighterYellow() {
        RuntimeEnvironment.setQualifiers("+night");
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            Utils.setActivity(owner.get());
            assertEquals(PrivateLists.YELLOW_ON_DARK, yellowOf(PrivateLists.tint(followers(PRIVATE), "88")));
        }
    }

    @Test public void aPublicListOrAMissingVisibilityLeavesTikToksTextAsItWas() {
        CharSequence text = "1.2K";
        assertSame(text, PrivateLists.tint(followers(PUBLIC), text));
        assertSame(text, PrivateLists.tint(followers(null), text));
        assertSame(text, PrivateLists.tint(new RelationFollowerProtocol(), text));
        RelationFollowerProtocol other = new RelationFollowerProtocol();
        other.bind(new BizBaseData());
        assertSame(text, PrivateLists.tint(other, text));
        assertSame(text, PrivateLists.tint(new Object(), text));
        assertSame(text, PrivateLists.tint(null, text));
    }

    @Test public void theFollowingCountReadsItsBoundDataNotTheOlderCopyItKeeps() {
        RelationFollowingProtocol following = new RelationFollowingProtocol();
        following.bind(new RelationData(PRIVATE, "40"));
        following.LLLI = new RelationData(PUBLIC, "40");
        assertEquals(PrivateLists.YELLOW_ON_LIGHT, yellowOf(PrivateLists.tint(following, "40")));

        RelationFollowingProtocol opened = new RelationFollowingProtocol();
        opened.bind(new RelationData(PUBLIC, "40"));
        opened.LLLI = new RelationData(PRIVATE, "40");
        CharSequence text = "40";
        assertSame(text, PrivateLists.tint(opened, text));
    }

    @Test public void theLikesCountNeverTurnsYellowInEitherHeader() {
        RelationLikeProtocol likes = new RelationLikeProtocol();
        likes.bind(new RelationData(PRIVATE, "9M"));
        likes.LLLFFI = new RelationData(PRIVATE, "9M");
        CharSequence text = "9M";
        assertSame(text, PrivateLists.tint(likes, text));
        assertSame(text, PrivateLists.tint(new RelationInfoBaseUIComponent("relation_info_like",
                new RelationData(PRIVATE, "9M")), text));
    }

    @Test public void theOlderHeaderReadsEachCountsOwnData() {
        assertEquals(PrivateLists.YELLOW_ON_LIGHT, yellowOf(PrivateLists.tint(new RelationInfoBaseUIComponent(
                "relation_info_following", new RelationData(PRIVATE, "12")), "12")));
        CharSequence text = "12";
        assertSame(text, PrivateLists.tint(new RelationInfoBaseUIComponent("relation_info_follower",
                new RelationData(PUBLIC, "12")), text));
        assertSame(text, PrivateLists.tint(new RelationInfoBaseUIComponent(null, null), text));
    }

    @Test public void theSwitchOrShowFollowStatusOffLeavesEveryCountAlone() {
        CharSequence text = "1.2K";
        Settings.YELLOW_PRIVATE_LISTS.save(false);
        assertSame(text, PrivateLists.tint(followers(PRIVATE), text));
        Settings.YELLOW_PRIVATE_LISTS.save(true);
        SettingsStatus.followStatusEnabled = false;
        assertSame(text, PrivateLists.tint(followers(PRIVATE), text));
        SettingsStatus.followStatusEnabled = true;
        assertSame("", PrivateLists.tint(followers(PRIVATE), ""));
    }
}
