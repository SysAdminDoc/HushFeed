/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.profile.FollowLists;
import app.morphe.extension.tiktok.profile.FollowListsAccess;
import app.morphe.extension.tiktok.profile.FollowLists.Format;
import app.morphe.extension.tiktok.profile.FollowLists.Kind;

/** What Export followers and following says at the end of a read, and what it names the file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "en")
public class FollowListExportPreferenceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    /** A real read's result: the reply is canned, the run is FollowLists' own. */
    private static FollowLists.Result result(int accounts, boolean stopAfter, String lastReply) {
        StringBuilder users = new StringBuilder();
        for (int i = 1; i <= accounts; i++) {
            if (i > 1) users.append(',');
            users.append("{\"uid\":\"").append(i).append("\",\"unique_id\":\"u").append(i).append("\"}");
        }
        String first = "{\"status_code\":0,\"has_more\":true,\"min_time\":5,\"followers\":[" + users + "]}";
        List<String> replies = new ArrayList<>();
        replies.add(first);
        if (lastReply != null) replies.add(lastReply);
        AtomicBoolean stopped = new AtomicBoolean();
        SignedInUser.idForTests = "100";
        try {
            return FollowListsAccess.read(replies, stopped, stopAfter);
        } finally {
            SignedInUser.idForTests = null;
        }
    }

    @Test public void theNoticeSaysHowManyWereSavedWhereAndWhetherTheFileIsPartial() {
        Context context = context();
        String where = "Download/Hushfeed/f.csv";
        assertEquals("Saved one account to Download/Hushfeed/f.csv",
                FollowListExportPreference.message(context, result(1, false, "{\"status_code\":0,\"followers\":[],\"has_more\":false}"), where));
        assertEquals("Saved 3 accounts to Download/Hushfeed/f.csv\n"
                        + "It stopped before the end of the list, so the file is partial.",
                FollowListExportPreference.message(context, result(3, true, null), where));
        assertEquals("Saved 2 accounts to Download/Hushfeed/f.csv\n"
                        + "TikTok stopped answering before the end of the list, so the file is partial.",
                FollowListExportPreference.message(context, result(2, false, "{\"status_code\":8}"), where));
    }

    @Test public void aReadThatSavedNobodySaysWhy() {
        Context context = context();
        assertEquals("Sign in to TikTok first", FollowListExportPreference.finish(context, Kind.FOLLOWERS, Format.CSV, "me",
                FollowListsAccess.ended(FollowLists.End.SIGNED_OUT)));
        assertEquals("Couldn't read the list. Try again in a moment.", FollowListExportPreference.finish(context,
                Kind.FOLLOWERS, Format.CSV, "me", FollowListsAccess.ended(FollowLists.End.FAILED)));
        assertEquals("Stopped before anyone was read", FollowListExportPreference.finish(context,
                Kind.FOLLOWING, Format.JSON, "me", FollowListsAccess.ended(FollowLists.End.STOPPED)));
        assertEquals("Nobody follows this account yet", FollowListExportPreference.finish(context,
                Kind.FOLLOWERS, Format.CSV, "me", FollowListsAccess.ended(FollowLists.End.DONE)));
        assertEquals("This account doesn't follow anyone yet", FollowListExportPreference.finish(context,
                Kind.FOLLOWING, Format.JSON, "me", FollowListsAccess.ended(FollowLists.End.DONE)));
    }

    @Test public void theFileNamesTheListTheAccountAndItsType() {
        String followers = FollowListExportPreference.fileName(Kind.FOLLOWERS, Format.CSV, "Some.User_1");
        assertTrue(followers, followers.matches("tiktok-followers-some\\.user_1-[0-9_-]+\\.csv"));
        String following = FollowListExportPreference.fileName(Kind.FOLLOWING, Format.JSON, "../evil/");
        assertTrue(following, following.matches("tiktok-following-\\.\\.evil-[0-9_-]+\\.json"));
        String nobody = FollowListExportPreference.fileName(Kind.FOLLOWING, Format.TEXT, null);
        assertTrue(nobody, nobody.matches("tiktok-following-[0-9_-]+\\.txt"));
    }

    @Test public void theChooserOffersEachListInEachFileTypeFollowersFirst() {
        List<String> rows = new ArrayList<>();
        for (CharSequence row : FollowListExportPreference.rows(context())) rows.add(row.toString());
        assertEquals(List.of("Followers (CSV)", "Followers (JSON)", "Followers (Plain text)",
                "Following (CSV)", "Following (JSON)", "Following (Plain text)"), rows);
    }
}
