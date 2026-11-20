/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.TimeZone;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/** Show what changed since your last visit: what a profile keeps, and the line under its @username. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ProfileChangesTest {
    /** 2026-11-18 15:00 UTC. */
    private static final long NOV_18 = 1_795_014_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String MINUS = String.valueOf((char) 0x2212);

    private ActivityController<Activity> owner;
    private Activity activity;
    private boolean previousFollow, previousJoin, previousChanges, previousStatus;
    private TimeZone previousZone;

    public static final class Profile {
        public String uid;
        public String username;
    }

    public static final class Relation {
        public Integer followStatus;
        public Integer followerStatus;
    }

    public static final class Statics {
        public Integer awemeCount;
    }

    /** A profile header's common info, with the members the extension reads by name. */
    public static final class CommonInfo {
        public Profile userProfileInfo = new Profile();
        public Relation userRelationInfo = new Relation();
        public Statics userStaticsInfo = new Statics();
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousFollow = Settings.SHOW_FOLLOW_STATUS.get();
        previousJoin = Settings.SHOW_JOIN_DATE.get();
        previousChanges = Settings.SHOW_PROFILE_CHANGES.get();
        previousStatus = SettingsStatus.followStatusEnabled;
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Settings.SHOW_FOLLOW_STATUS.save(true);
        Settings.SHOW_JOIN_DATE.save(false);
        Settings.SHOW_PROFILE_CHANGES.save(true);
        SettingsStatus.followStatusEnabled = true;
        SignedInUser.idForTests = "me";
        ProfileChanges.clearForTests();
        ProfileChanges.nowForTests = NOV_18;
        owner = Robolectric.buildActivity(Activity.class).setup();
        activity = owner.get();
    }

    @After public void tearDown() {
        ProfileChanges.clearForTests();
        ProfileChanges.nowForTests = null;
        Settings.SHOW_FOLLOW_STATUS.save(previousFollow);
        Settings.SHOW_JOIN_DATE.save(previousJoin);
        Settings.SHOW_PROFILE_CHANGES.save(previousChanges);
        SettingsStatus.followStatusEnabled = previousStatus;
        TimeZone.setDefault(previousZone);
        SignedInUser.idForTests = null;
        if (owner != null) owner.close();
    }

    @Test public void aSecondVisitSaysWhatChangedUnderTheFollowLabelEvenWhenTheCountsBindLate() {
        LinearLayout header = column();
        text(header, "NASA");
        TextView handle = text(header, "@nasa");

        visit(handle, 1000, 50, 5000L, 20);
        assertEquals("the first visit has nothing to compare", 3, header.getChildCount());
        assertEquals("Follows you", ((TextView) header.getChildAt(2)).getText().toString());

        // Two days on, the header binds before the relation bar does.
        ProfileChanges.nowForTests = NOV_18 + 2 * DAY;
        FollowStatus.onHeaderText(handle, info(21));
        idle();
        assertEquals(4, header.getChildCount());
        TextView changes = (TextView) header.getChildAt(3);
        assertEquals(FollowStatus.CHANGES_TAG, changes.getTag());
        assertEquals(FollowStatus.LABEL_TAG, header.getChildAt(2).getTag());
        assertDated("Since Nov 18: +1 video", changes.getText().toString());

        ProfileChanges.count("1", "relation_info_follower", 1012);
        ProfileChanges.count("1", "relation_info_following", 50);
        ProfileChanges.count("1", "relation_info_like", 12_345L);
        idle();
        assertDated("Since Nov 18: +12 followers, +7,345 likes, +1 video", changes.getText().toString());
        assertEquals(View.VISIBLE, changes.getVisibility());

        // Ten minutes later is the same visit, so it still compares with Nov 18.
        ProfileChanges.nowForTests = NOV_18 + 2 * DAY + 10 * 60 * 1000;
        visit(handle, 1012, 50, 12_345L, 21);
        assertDated("Since Nov 18: +12 followers, +7,345 likes, +1 video", changes.getText().toString());
        assertEquals(4, header.getChildCount());
    }

    @Test public void aLaterVisitTheSameDayNamesTheTimeAndADropUsesAMinus() {
        LinearLayout header = column();
        TextView handle = text(header, "@nasa");
        visit(handle, 1000, 50, 5000L, 20);

        ProfileChanges.nowForTests = NOV_18 + 60 * 60 * 1000;
        visit(handle, 999, 51, 5000L, 20);
        String line = changes(header).getText().toString();
        assertTrue(line, line.matches("Since 3:00.PM: " + MINUS + "1 follower, \\+1 following"));

        ProfileChanges.nowForTests = NOV_18 + 2 * 60 * 60 * 1000;
        visit(handle, 999, 51, 5000L, 20);
        line = changes(header).getText().toString();
        assertTrue(line, line.matches("No changes since 4:00.PM"));

        ProfileChanges.nowForTests = NOV_18 + 400 * DAY;
        visit(handle, 999, 51, 5000L, 20);
        assertDated("a visit in another year names it", "No changes since Nov 18, 2026",
                changes(header).getText().toString());
    }

    @Test public void theCountsOutliveARestartAndAZeroIsNeverKept() {
        LinearLayout header = column();
        TextView handle = text(header, "@nasa");
        // A header drawn from the short account TikTok already had, before the profile loads.
        visit(handle, 0, 0, 0L, 0);
        ProfileChanges.nowForTests = NOV_18 + DAY;
        visit(handle, 1000, 50, 5000L, 20);
        assertNull("zeros are not a last visit", changes(header));

        ProfileChanges.forgetVisitsForTests();
        ProfileChanges.nowForTests = NOV_18 + 2 * DAY;
        visit(handle, 1001, 50, 5000L, 20);
        assertDated("Since Nov 19: +1 follower", changes(header).getText().toString());
    }

    @Test public void offRecordsNothingAndHidesTheLine() {
        LinearLayout header = column();
        TextView handle = text(header, "@nasa");
        visit(handle, 1000, 50, 5000L, 20);
        ProfileChanges.nowForTests = NOV_18 + DAY;
        visit(handle, 1100, 50, 5000L, 20);
        TextView changes = changes(header);
        assertDated("Since Nov 18: +100 followers", changes.getText().toString());

        Settings.SHOW_PROFILE_CHANGES.save(false);
        ProfileChanges.nowForTests = NOV_18 + 2 * DAY;
        visit(handle, 1200, 50, 5000L, 20);
        assertEquals(View.GONE, changes.getVisibility());
        assertEquals("Follows you", ((TextView) header.getChildAt(1)).getText().toString());

        Settings.SHOW_PROFILE_CHANGES.save(true);
        ProfileChanges.nowForTests = NOV_18 + 3 * DAY;
        visit(handle, 1200, 50, 5000L, 20);
        assertDated("the visit while off wasn't kept", "Since Nov 19: +100 followers", changes.getText().toString());
    }

    @Test public void yourOwnProfileGetsTheLineWithoutAFollowLabel() {
        LinearLayout header = column();
        TextView handle = text(header, "@me_myself");
        visitOwn(handle, 10);
        ProfileChanges.nowForTests = NOV_18 + DAY;
        visitOwn(handle, 13);
        assertEquals(2, header.getChildCount());
        assertDated("Since Nov 18: +3 followers", ((TextView) header.getChildAt(1)).getText().toString());
        assertEquals(FollowStatus.CHANGES_TAG, header.getChildAt(1).getTag());
    }

    @Test public void theProfilesSeenLongestAgoAreForgottenPastTheCap() {
        for (int index = 0; index <= ProfileChanges.MAX_PROFILES; index++) {
            ProfileChanges.nowForTests = NOV_18 + index;
            ProfileChanges.observe("u" + index, ProfileChanges.Metric.FOLLOWERS, 5);
        }
        Context context = RuntimeEnvironment.getApplication();
        java.util.Map<String, ?> saved = context.getSharedPreferences(ProfileChanges.PREFS_NAME, Context.MODE_PRIVATE).getAll();
        assertEquals(ProfileChanges.MAX_PROFILES, saved.size());
        assertFalse(saved.containsKey("u0"));
        assertTrue(saved.containsKey("u1"));
        assertTrue(saved.containsKey("u" + ProfileChanges.MAX_PROFILES));
    }

    /**
     * The date's pattern is the locale's own (Robolectric hands the skeleton back as it is, so
     * "Nov 18" prints as "Nov18"), so the date is compared by its parts and the rest exactly.
     */
    private static void assertDated(String expected, String actual) {
        assertDated(null, expected, actual);
    }

    private static void assertDated(String message, String expected, String actual) {
        assertEquals(message, dateParts(expected), dateParts(actual));
    }

    private static String dateParts(String text) {
        return text.replaceAll("(Nov)[\\s,.]*(\\d{1,2})(?:[\\s,.]*(\\d{4}))?", "$1 $2 $3");
    }

    /** One visit to profile "1" (@nasa, follows you): the relation bar's three counts, then the header. */
    private void visit(TextView handle, int followers, int following, long likes, int videos) {
        ProfileChanges.count("1", "relation_info_follower", followers);
        ProfileChanges.count("1", "relation_info_following", following);
        ProfileChanges.count("1", "relation_info_like", likes);
        FollowStatus.onHeaderText(handle, info(videos));
        idle();
    }

    private void visitOwn(TextView handle, int followers) {
        ProfileChanges.count("me", "relation_info_follower", followers);
        CommonInfo own = new CommonInfo();
        own.userProfileInfo.uid = "me";
        own.userProfileInfo.username = "me_myself";
        FollowStatus.onHeaderText(handle, own);
        idle();
    }

    private static CommonInfo info(int videos) {
        CommonInfo info = new CommonInfo();
        info.userProfileInfo.uid = "1";
        info.userProfileInfo.username = "nasa";
        info.userRelationInfo.followStatus = 0;
        info.userRelationInfo.followerStatus = 1;
        info.userStaticsInfo.awemeCount = videos;
        return info;
    }

    private static TextView changes(LinearLayout header) {
        for (int index = 0; index < header.getChildCount(); index++) {
            View child = header.getChildAt(index);
            if (FollowStatus.CHANGES_TAG.equals(child.getTag())) {
                return child.getVisibility() == View.VISIBLE ? (TextView) child : null;
            }
        }
        return null;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(layout);
        return layout;
    }

    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        parent.addView(view);
        return view;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
