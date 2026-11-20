package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.profile.FollowLists;
import app.morphe.extension.tiktok.privacy.AccountCleanupSources.Kind;
import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;
import app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

/**
 * Remove all reposts, Remove all likes, Clear Favorites (#332) and your comments (#442) from the
 * tap to the count: a
 * countdown with Cancel and no dialog, then the removal with its count and Stop, then the count
 * it reached, with TikTok's services stood in by annotated fakes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-night-mdpi")
public class AccountCleanupTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout content;
    private ShadowLooper looper;
    private final List<Long> pauses = Collections.synchronizedList(new ArrayList<>());
    private IntConsumer duringPause = pause -> { };

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        Utils.setContext(activity);
        content = new FrameLayout(activity);
        content.setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        activity.setContentView(content);
        looper = Shadows.shadowOf(Looper.getMainLooper());
        SignedInUser.idForTests = "7001";
        SignedInUser.secIdForTests = "MS4wLjABAAAAtest";
        AccountCleanup.pacerForTests = new CleanupRunner.Pacer() {
            @Override public void pause(long ms) {
                pauses.add(ms);
                duringPause.accept(pauses.size());
            }

            @Override public void wake() {
            }
        };
        AccountCleanup.executorForTests = Runnable::run;
    }

    @After public void tearDown() {
        AccountCleanup.resetForTests();
        SignedInUser.idForTests = null;
        SignedInUser.secIdForTests = null;
        SettingsStatus.accountCleanupEnabled = false;
        SettingsStatus.ghostModeEnabled = false;
        ReflectionHelpers.callStaticMethod(SettingsActionBanner.class, "dismissForTests");
        activity.finish();
    }

    private String bannerText() {
        View label = content.findViewWithTag("hushfeed_settings_action_message");
        return label instanceof TextView ? ((TextView) label).getText().toString() : null;
    }

    private TextView bannerAction() {
        return content.findViewWithTag("hushfeed_settings_action_button");
    }

    private void countDown() {
        looper.idleFor(Duration.ofSeconds(AccountCleanup.COUNTDOWN_SECONDS));
        looper.idle();
    }

    @Test public void aTapCountsDownThenRemovesEveryRepostAndSaysHowMany() {
        FakeTikTok.Account account = new FakeTikTok.Account().withReposts(3);
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.REPOSTS);
        assertEquals("Removing all reposts in 5", bannerText());
        assertEquals("Cancel", bannerAction().getText().toString());
        assertTrue(AccountCleanup.running());
        looper.idleFor(Duration.ofSeconds(1));
        assertEquals("Removing all reposts in 4", bannerText());
        assertEquals("nothing goes out during the countdown", List.of(), account.requests);

        looper.idleFor(Duration.ofSeconds(AccountCleanup.COUNTDOWN_SECONDS - 1));
        looper.idle();
        assertEquals("Done. 3 removed.", bannerText());
        assertNull("the result has nothing to press", bannerAction());
        assertFalse(AccountCleanup.running());
        assertEquals(List.of(), account.reposts);
        assertEquals(List.of(
                "getRepostVideoList {user_id=7001, offset=0, count=20, scene=0}",
                "deleteUpvote {item_id=r0, carrier_type=0}",
                "deleteUpvote {item_id=r1, carrier_type=0}",
                "deleteUpvote {item_id=r2, carrier_type=0}",
                "getRepostVideoList {user_id=7001, offset=0, count=20, scene=0}"), account.requests);
        assertEquals("every request but the first waited", account.requests.size() - 1, pauses.size());
        for (long pause : pauses) assertTrue(pause + " ms", pause >= 1_500 && pause < 3_000);
    }

    @Test public void likesGoThroughTheLikedListAndTheHeartsOwnRequest() {
        FakeTikTok.Account account = new FakeTikTok.Account().withLikes(25);
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.LIKES);
        assertEquals("Removing all likes in 5", bannerText());
        countDown();

        assertEquals("Done. 25 removed.", bannerText());
        assertEquals(List.of(), account.likes);
        assertEquals("likedVideos {max_cursor=0, sec_user_id=MS4wLjABAAAAtest, count=20, "
                + "invalid_item_count=0, is_hiding_invalid_item=1}", account.requests.get(0));
        assertEquals("likeFeed {aweme_id=l0, type=0}", account.requests.get(1));
        assertEquals(25, account.requests.stream().filter(request -> request.startsWith("likeFeed ")).count());
    }

    @Test public void favoritesStopAtTikToksFirstRefusal() {
        FakeTikTok.Account account = new FakeTikTok.Account().withFavorites(4);
        account.refuse = "f1";
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.FAVORITES);
        assertEquals("Clearing Favorites in 5", bannerText());
        countDown();

        assertEquals("TikTok refused. 1 removed.", bannerText());
        assertEquals(List.of(
                "list {cursor=0, count=20}",
                "collect {aweme_id=f0, action=0}",
                "collect {aweme_id=f1, action=0}"), account.requests);
        assertEquals(List.of("f1", "f2", "f3"), account.favorites);
        assertEquals("a refused run doesn't go on to the unavailable ones", 0,
                account.requests.stream().filter(request -> request.startsWith("removeInvalid")).count());
    }

    @Test public void clearFavoritesEndsByClearingTheUnavailableOnes() {
        FakeTikTok.Account account = new FakeTikTok.Account().withFavorites(3).withUnavailableFavorites(2);
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.FAVORITES);
        countDown();

        assertEquals("Done. 3 removed.", bannerText());
        assertEquals(List.of(), account.favorites);
        assertEquals("the saved videos deleted since are gone too", List.of(), account.unavailable);
        assertEquals(List.of(
                "list {cursor=0, count=20}",
                "collect {aweme_id=f0, action=0}",
                "collect {aweme_id=f1, action=0}",
                "collect {aweme_id=f2, action=0}",
                "list {cursor=0, count=20}",
                "removeInvalid {cursor=0}"), account.requests);
        assertEquals("the last request waited like the rest", account.requests.size() - 1, pauses.size());
    }

    @Test public void aRefusedClearOfTheUnavailableOnesSaysSo() {
        FakeTikTok.Account account = new FakeTikTok.Account().withFavorites(2).withUnavailableFavorites(1);
        account.refuseUnavailable = true;
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.FAVORITES);
        countDown();

        assertEquals("TikTok refused. 2 removed.", bannerText());
        assertEquals(List.of("u0"), account.unavailable);
    }

    @Test public void theRunShowsItsCountAndStopEndsIt() {
        FakeTikTok.Account account = new FakeTikTok.Account().withLikes(10);
        AccountCleanup.servicesForTests = account;
        List<String> seen = new ArrayList<>();
        // The third wait is before the third unlike: read the banner there and press its Stop.
        duringPause = pause -> {
            if (pause != 3) return;
            seen.add(bannerText());
            seen.add(bannerAction().getText().toString());
            assertTrue(bannerAction().performClick());
        };

        AccountCleanup.start(activity, Kind.LIKES);
        countDown();

        assertEquals(List.of("Likes removed: 0", "Stop"), seen);
        assertEquals("Stopped. 2 removed.", bannerText());
        assertEquals(8, account.likes.size());
        assertFalse(AccountCleanup.running());
    }

    @Test public void theCountUpdatesInPlaceWhileTheRunGoes() {
        FakeTikTok.Account account = new FakeTikTok.Account().withReposts(3);
        AccountCleanup.servicesForTests = account;
        List<Runnable> later = new ArrayList<>();
        AccountCleanup.executorForTests = later::add;

        AccountCleanup.start(activity, Kind.REPOSTS);
        countDown();
        assertEquals("Reposts removed: 0", bannerText());
        View banner = content.findViewWithTag("hushfeed_settings_action_banner");

        // Run the removal by hand and let only its count updates through.
        duringPause = pause -> {
            if (pause == 3) {
                looper.idle();
                assertEquals("Reposts removed: 2", bannerText());
                assertEquals("the count changed the banner's text rather than replacing it", banner,
                        content.findViewWithTag("hushfeed_settings_action_banner"));
            }
        };
        later.get(0).run();
        looper.idle();
        assertEquals("Done. 3 removed.", bannerText());
    }

    @Test public void cancelDuringTheCountdownSendsNothing() {
        FakeTikTok.Account account = new FakeTikTok.Account().withFavorites(3);
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.FAVORITES);
        looper.idleFor(Duration.ofSeconds(2));
        assertEquals("Clearing Favorites in 3", bannerText());
        assertTrue(bannerAction().performClick());
        assertEquals("Cancelled", bannerText());
        assertFalse(AccountCleanup.running());

        looper.idleFor(Duration.ofSeconds(10));
        assertEquals(List.of(), account.requests);
        assertEquals(3, account.favorites.size());

        AccountCleanup.start(activity, Kind.FAVORITES);
        assertEquals("a cancelled countdown leaves the way clear", "Clearing Favorites in 5", bannerText());
    }

    @Test public void aCountdownOutOfSightNeverStarts() {
        FakeTikTok.Account account = new FakeTikTok.Account().withReposts(3);
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.REPOSTS);
        // A tap on the banner's body takes it down.
        assertTrue(content.findViewWithTag("hushfeed_settings_action_banner").performClick());
        countDown();
        assertEquals(List.of(), account.requests);
        assertFalse(AccountCleanup.running());

        AccountCleanup.start(activity, Kind.REPOSTS);
        SettingsActionBanner.showNotice(activity, "Something else");
        countDown();
        assertEquals("a banner that replaced the countdown stopped it", List.of(), account.requests);
        assertFalse(AccountCleanup.running());

        AccountCleanup.start(activity, Kind.REPOSTS);
        looper.idleFor(Duration.ofSeconds(1));
        activity.finish();
        countDown();
        assertEquals("closing settings stopped it", List.of(), account.requests);
        assertFalse(AccountCleanup.running());
        assertEquals(3, account.reposts.size());
    }

    @Test public void onlyOneRemovalRunsAtATime() {
        FakeTikTok.Account account = new FakeTikTok.Account().withReposts(3).withLikes(3);
        AccountCleanup.servicesForTests = account;
        List<Runnable> later = new ArrayList<>();
        AccountCleanup.executorForTests = later::add;

        AccountCleanup.start(activity, Kind.REPOSTS);
        AccountCleanup.start(activity, Kind.LIKES);
        assertEquals("a second tap during the countdown changes nothing",
                "Removing all reposts in 5", bannerText());
        countDown();
        assertEquals(1, later.size());

        AccountCleanup.start(activity, Kind.LIKES);
        assertEquals("A removal is already running", bannerText());
        assertEquals("Stop", bannerAction().getText().toString());
        assertTrue(bannerAction().performClick());
        later.get(0).run();
        looper.idle();
        assertEquals("Stopped. 0 removed.", bannerText());
        assertEquals("nothing went out after Stop", List.of(), account.requests);
        assertEquals(1, later.size());
    }

    /** A check that finds {@code found} (id to secure id), or stops when Stop came first. */
    private static AccountCleanup.Checker finds(Map<String, String> found, List<String> heard) {
        return (stopped, progress) -> {
            progress.following(3);
            progress.followers(0);
            progress.followers(7);
            heard.add("checked");
            if (stopped.get()) return new FollowLists.NotFollowingBack(new LinkedHashMap<>(), FollowLists.End.STOPPED);
            return new FollowLists.NotFollowingBack(found, FollowLists.End.DONE);
        };
    }

    private static Map<String, String> notFollowingBack(String... idThenSecId) {
        Map<String, String> found = new LinkedHashMap<>();
        for (int index = 0; index < idThenSecId.length; index += 2) found.put(idThenSecId[index], idThenSecId[index + 1]);
        return found;
    }

    @Test public void unfollowChecksFirstThenCountsDownWithHowManyAndUnfollowsOnlyThose() {
        FakeTikTok.Account account = new FakeTikTok.Account().following("MS4wa", "2", "MS4wfriend");
        AccountCleanup.servicesForTests = account;
        List<String> heard = new ArrayList<>();
        AccountCleanup.checkerForTests = finds(notFollowingBack("1", "MS4wa", "2", ""), heard);
        List<Runnable> later = new ArrayList<>();
        AccountCleanup.executorForTests = later::add;

        AccountCleanup.startUnfollow(activity);
        assertEquals("Checking who you follow: 0", bannerText());
        assertEquals("Stop", bannerAction().getText().toString());
        assertTrue(AccountCleanup.running());
        later.remove(0).run();
        assertEquals(List.of("checked"), heard);
        assertEquals("nothing is unfollowed during the check", List.of(), account.requests);
        looper.idle();
        assertEquals("Accounts to unfollow: 2. Starting in 5", bannerText());
        assertEquals("Cancel", bannerAction().getText().toString());
        looper.idleFor(Duration.ofSeconds(1));
        assertEquals("Accounts to unfollow: 2. Starting in 4", bannerText());
        assertEquals(List.of(), account.requests);

        AccountCleanup.executorForTests = Runnable::run;
        looper.idleFor(Duration.ofSeconds(AccountCleanup.COUNTDOWN_SECONDS - 1));
        looper.idle();
        assertEquals("Done. 2 unfollowed.", bannerText());
        assertFalse(AccountCleanup.running());
        assertEquals("the account that follows you back is still followed", List.of("MS4wfriend"), account.following);
        assertEquals(List.of(
                "doPost /aweme/v1/commit/follow/user/ {sec_user_id=MS4wa, type=0, channel_id=-1, city=}",
                "doPost /aweme/v1/commit/follow/user/ {user_id=2, type=0, channel_id=-1, city=}"), account.requests);
        assertEquals("every unfollow waited, and so did the last look at the list", 3, pauses.size());
    }

    @Test public void theCheckShowsItsCountAndStopUnfollowsNobody() {
        FakeTikTok.Account account = new FakeTikTok.Account().following("MS4wa");
        AccountCleanup.servicesForTests = account;
        List<Runnable> later = new ArrayList<>();
        AccountCleanup.executorForTests = later::add;
        List<String> heard = new ArrayList<>();
        AccountCleanup.Checker found = finds(notFollowingBack("1", "MS4wa"), heard);
        // The check reads the following list, is stopped, and then reads the rest.
        AccountCleanup.checkerForTests = (stopped, progress) -> {
            progress.following(40);
            looper.idle();
            assertEquals("Checking who you follow: 40", bannerText());
            assertTrue(bannerAction().performClick());
            assertTrue(stopped.get());
            return found.check(stopped, progress);
        };

        AccountCleanup.startUnfollow(activity);
        later.remove(0).run();
        looper.idle();
        assertEquals("Cancelled", bannerText());
        assertFalse(AccountCleanup.running());
        countDown();
        assertEquals(List.of(), account.requests);
        assertEquals(List.of("MS4wa"), account.following);
    }

    @Test public void theCheckSaysWhenThereIsNobodyToUnfollowOrItCouldntRead() {
        AccountCleanup.servicesForTests = new FakeTikTok.Account();
        AccountCleanup.checkerForTests = finds(new LinkedHashMap<>(), new ArrayList<>());
        AccountCleanup.startUnfollow(activity);
        looper.idle();
        assertEquals("Everyone you follow follows you back", bannerText());
        assertFalse(AccountCleanup.running());

        for (FollowLists.End end : new FollowLists.End[] {FollowLists.End.FAILED, FollowLists.End.SIGNED_OUT}) {
            AccountCleanup.checkerForTests = (stopped, progress) ->
                    new FollowLists.NotFollowingBack(notFollowingBack("1", "MS4wa"), end);
            AccountCleanup.startUnfollow(activity);
            looper.idle();
            assertEquals(end.name(), end == FollowLists.End.FAILED ? "Couldn't check who follows you back. Try again."
                    : "Sign in to TikTok first", bannerText());
            countDown();
            assertFalse(AccountCleanup.running());
        }
    }

    @Test public void aCheckOutOfSightNeverCountsDown() {
        FakeTikTok.Account account = new FakeTikTok.Account().following("MS4wa");
        AccountCleanup.servicesForTests = account;
        AccountCleanup.checkerForTests = finds(notFollowingBack("1", "MS4wa"), new ArrayList<>());
        List<Runnable> later = new ArrayList<>();
        AccountCleanup.executorForTests = later::add;

        AccountCleanup.startUnfollow(activity);
        assertTrue(content.findViewWithTag("hushfeed_settings_action_banner").performClick());
        later.remove(0).run();
        countDown();
        assertNull("put away, the check ends without a word", bannerText());
        assertFalse(AccountCleanup.running());

        AccountCleanup.startUnfollow(activity);
        SettingsActionBanner.showNotice(activity, "Something else");
        later.remove(0).run();
        looper.idle();
        assertEquals("a banner that replaced the check stopped it", "Something else", bannerText());
        countDown();
        assertFalse(AccountCleanup.running());
        assertEquals(List.of(), account.requests);
        assertEquals(List.of("MS4wa"), account.following);
    }

    @Test public void anUnfollowThatDoesntTakeStopsTheRun() {
        FakeTikTok.Account account = new FakeTikTok.Account().following("MS4wa", "MS4wb");
        account.followStatusAfterUnfollow = 1;
        AccountCleanup.servicesForTests = account;
        AccountCleanup.checkerForTests = finds(notFollowingBack("1", "MS4wa", "2", "MS4wb"), new ArrayList<>());
        AccountCleanup.startUnfollow(activity);
        looper.idle();
        countDown();
        assertEquals("TikTok refused. 0 unfollowed.", bannerText());
        assertEquals("the run stopped at the first one", 1, account.requests.size());

        account.followStatusAfterUnfollow = 0;
        account.refuse = "MS4wb";
        AccountCleanup.startUnfollow(activity);
        looper.idle();
        countDown();
        assertEquals("TikTok refused. 1 unfollowed.", bannerText());
        assertEquals(List.of("MS4wb"), account.following);
    }

    @Test public void signedOutNothingStarts() {
        SignedInUser.idForTests = "";
        AccountCleanup.servicesForTests = new FakeTikTok.Account().withReposts(3);
        AccountCleanup.start(activity, Kind.REPOSTS);
        assertEquals("Sign in to TikTok first", bannerText());
        assertFalse(AccountCleanup.running());
    }

    @Test public void switchingAccountsMidRunStopsIt() {
        FakeTikTok.Account account = new FakeTikTok.Account().withLikes(5);
        AccountCleanup.servicesForTests = account;
        duringPause = pause -> {
            if (pause == 2) SignedInUser.idForTests = "9999";
        };
        AccountCleanup.start(activity, Kind.LIKES);
        countDown();
        assertEquals("Stopped. 1 removed.", bannerText());
        assertEquals(4, account.likes.size());
    }

    @Test public void signingOutBeforeTheFirstRemovalSaysToSignIn() {
        FakeTikTok.Account account = new FakeTikTok.Account().withLikes(5);
        AccountCleanup.servicesForTests = account;
        duringPause = pause -> SignedInUser.idForTests = "";
        AccountCleanup.start(activity, Kind.LIKES);
        countDown();
        assertEquals("Sign in to TikTok first", bannerText());
        assertEquals(5, account.likes.size());
    }

    @Test public void anUnpatchedBuildSaysSoForEachKind() {
        for (Kind kind : Kind.values()) {
            AccountCleanup.start(activity, kind);
            countDown();
            assertEquals(kind.name(), "Not available on this TikTok version", bannerText());
            assertFalse(AccountCleanup.running());
        }
        SignedInUser.secIdForTests = "";
        AccountCleanup.servicesForTests = new FakeTikTok.Account().withLikes(2);
        AccountCleanup.start(activity, Kind.LIKES);
        countDown();
        assertEquals("no secure uid to list likes with", "Not available on this TikTok version", bannerText());
    }

    @Test public void everyEndHasItsOwnWords() {
        assertEquals("Nothing to remove", AccountCleanup.endText(activity, End.DONE, 0));
        assertEquals("Done. 12 removed.", AccountCleanup.endText(activity, End.DONE, 12));
        assertEquals("Stopped. 3 removed.", AccountCleanup.endText(activity, End.STOPPED, 3));
        assertEquals("TikTok refused. 3 removed.", AccountCleanup.endText(activity, End.REFUSED, 3));
        assertEquals("Network error. 0 removed.", AccountCleanup.endText(activity, End.NETWORK, 0));
        assertEquals("Not available on this TikTok version", AccountCleanup.endText(activity, End.UNSUPPORTED, 4));
        assertEquals("Favorites removed: 7", AccountCleanup.progressText(activity, Kind.FAVORITES, 7));
        assertEquals("Clearing Favorites in 2", AccountCleanup.countdownText(activity, Kind.FAVORITES, false, 2));
        assertEquals("Removing all comments in 3", AccountCleanup.countdownText(activity, Kind.COMMENTS, false, 3));
        assertEquals("Removing the picked comments in 4",
                AccountCleanup.countdownText(activity, Kind.COMMENTS, true, 4));
        assertEquals("Comments removed: 9", AccountCleanup.progressText(activity, Kind.COMMENTS, 9));

        Kind unfollow = Kind.NOT_FOLLOWING_BACK;
        assertEquals("Unfollowed: 4", AccountCleanup.progressText(activity, unfollow, 4));
        assertEquals("Done. 12 unfollowed.", AccountCleanup.endText(activity, unfollow, End.DONE, 12));
        assertEquals("Stopped. 3 unfollowed.", AccountCleanup.endText(activity, unfollow, End.STOPPED, 3));
        assertEquals("TikTok refused. 3 unfollowed.", AccountCleanup.endText(activity, unfollow, End.REFUSED, 3));
        assertEquals("Network error. 0 unfollowed.", AccountCleanup.endText(activity, unfollow, End.NETWORK, 0));
        assertEquals("Not available on this TikTok version",
                AccountCleanup.endText(activity, unfollow, End.UNSUPPORTED, 0));
        assertEquals("the other kinds keep their words", "Done. 2 removed.",
                AccountCleanup.endText(activity, Kind.LIKES, End.DONE, 2));
        assertEquals("Accounts to unfollow: 1. Starting in 3", AccountCleanup.unfollowCountdownText(activity, 1, 3));
        assertEquals("Cancelled", AccountCleanup.checkEndText(activity, FollowLists.End.STOPPED));
        assertEquals("Couldn't check who follows you back. Try again.",
                AccountCleanup.checkEndText(activity, FollowLists.End.CAPPED));
    }

    @Test public void thePrivacyPageHasTheCleanupRowsOnlyWithThePatch() {
        SettingsStatus.ghostModeEnabled = true;
        assertEquals(List.of(), cleanupRows(privacyPage()));

        SettingsStatus.accountCleanupEnabled = true;
        SettingsMenuPreference privacy = privacyPage();
        List<Preference> rows = cleanupRows(privacy);
        assertEquals(6, rows.size());
        assertEquals("Remove all reposts", rows.get(0).getTitle().toString());
        assertEquals("Removes every repost from your account one after another.", rows.get(0).getSummary().toString());
        assertEquals("Remove all likes", rows.get(1).getTitle().toString());
        assertEquals("Unlikes every video one after another.", rows.get(1).getSummary().toString());
        assertEquals("Clear Favorites", rows.get(2).getTitle().toString());
        assertEquals("Removes every video from Favorites one after another.", rows.get(2).getSummary().toString());
        assertEquals("Remove all comments", rows.get(3).getTitle().toString());
        assertEquals("Deletes every comment you've posted one after another.", rows.get(3).getSummary().toString());
        assertEquals("Remove selected comments", rows.get(4).getTitle().toString());
        assertEquals("Pick comments from your comment history to delete.", rows.get(4).getSummary().toString());
        assertEquals("Unfollow accounts that don't follow you back", rows.get(5).getTitle().toString());
        assertEquals("Checks your followers first and says how many it found, then unfollows them one after "
                + "another. Accounts that follow you back stay.", rows.get(5).getSummary().toString());
        int heading = indexOf(privacy, rows.get(0)) - 1;
        assertEquals("Clean up your account", privacy.getPreference(heading).getTitle().toString());

        // A tap starts the countdown, not a dialog.
        AccountCleanup.servicesForTests = new FakeTikTok.Account();
        Preference likes = rows.get(1);
        assertTrue(likes.getOnPreferenceClickListener().onPreferenceClick(likes));
        assertEquals("Removing all likes in 5", bannerText());
        assertNotNull(bannerAction());

        // The unfollow row starts with the check, not the countdown.
        AccountCleanup.resetForTests();
        AccountCleanup.servicesForTests = new FakeTikTok.Account();
        List<Runnable> later = new ArrayList<>();
        AccountCleanup.executorForTests = later::add;
        Preference unfollow = rows.get(5);
        assertTrue(unfollow.getOnPreferenceClickListener().onPreferenceClick(unfollow));
        assertEquals("Checking who you follow: 0", bannerText());
        assertEquals(1, later.size());
    }

    @Test public void removeAllCommentsDeletesEveryCommentFromTheHistory() {
        FakeTikTok.Account account = new FakeTikTok.Account().withComments(17);
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.COMMENTS);
        assertEquals("Removing all comments in 5", bannerText());
        assertEquals("Cancel", bannerAction().getText().toString());
        countDown();

        assertEquals("Done. 17 removed.", bannerText());
        assertEquals(List.of(), account.comments);
        assertEquals("doGet /tiktok/comment/history/list/v2/ {offset=0, count=15, sort_type=0}",
                account.requests.get(0));
        assertEquals("doPost /aweme/v1/comment/delete/ {cid=c0}", account.requests.get(1));
        assertEquals(17, account.requests.stream().filter(request -> request.startsWith("doPost ")).count());
        assertFalse("a comment TikTok took down isn't asked about",
                account.requests.stream().anyMatch(request -> request.contains("taken-down")));
        assertEquals("every request but the first waited", account.requests.size() - 1, pauses.size());
    }

    @Test public void commentsStopAtTikToksFirstRefusal() {
        FakeTikTok.Account account = new FakeTikTok.Account().withComments(3);
        account.refuse = "c1";
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.COMMENTS);
        countDown();

        assertEquals("TikTok refused. 1 removed.", bannerText());
        assertEquals(List.of("c1", "c2"), account.comments);
    }

    @Test public void aCommentHistoryTikTokWontListEndsTheRun() {
        FakeTikTok.Account account = new FakeTikTok.Account().withComments(3);
        account.commentHistoryStatus = 8;
        AccountCleanup.servicesForTests = account;

        AccountCleanup.start(activity, Kind.COMMENTS);
        countDown();

        assertEquals("TikTok refused. 0 removed.", bannerText());
        assertEquals(3, account.comments.size());
        assertFalse(AccountCleanup.running());
    }

    @Test public void pickedCommentsAreTheOnlyOnesDeleted() {
        FakeTikTok.Account account = new FakeTikTok.Account().withComments(5);
        AccountCleanup.servicesForTests = account;

        AccountCleanup.startPicked(activity, List.of());
        assertFalse("nothing picked starts nothing", AccountCleanup.running());

        AccountCleanup.startPicked(activity, List.of("c3", "c1"));
        assertEquals("Removing the picked comments in 5", bannerText());
        countDown();

        assertEquals("Done. 2 removed.", bannerText());
        assertEquals(List.of("c0", "c2", "c4"), account.comments);
        assertEquals("the history isn't read again, only the picked ones go", List.of(
                "doPost /aweme/v1/comment/delete/ {cid=c3}",
                "doPost /aweme/v1/comment/delete/ {cid=c1}"), account.requests);
    }

    @Test public void thePickerListsYourCommentsAndRemovesTheTickedOnes() {
        SettingsStatus.ghostModeEnabled = true;
        SettingsStatus.accountCleanupEnabled = true;
        FakeTikTok.Account account = new FakeTikTok.Account().withComments(20);
        AccountCleanup.servicesForTests = account;
        Preference picker = row(privacyPage(), "action_pick_comments");

        assertTrue(picker.getOnPreferenceClickListener().onPreferenceClick(picker));
        looper.idle();
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("a tap opens the list", dialog);
        assertTrue(dialog.isShowing());
        ListView list = dialog.getWindow().getDecorView().findViewWithTag("hushfeed_comment_picker_list");
        Button remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button more = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        assertEquals("one page of the history", 15, list.getAdapter().getCount());
        assertEquals(View.VISIBLE, list.getVisibility());
        assertFalse("nothing ticked, nothing to remove", remove.isEnabled());
        assertEquals(View.VISIBLE, more.getVisibility());

        assertTrue(more.performClick());
        looper.idle();
        assertEquals(20, list.getAdapter().getCount());
        assertEquals("the history has no more", View.GONE, more.getVisibility());

        list.performItemClick(list.getAdapter().getView(3, null, list), 3, 3);
        list.performItemClick(list.getAdapter().getView(16, null, list), 16, 16);
        list.performItemClick(list.getAdapter().getView(5, null, list), 5, 5);
        list.performItemClick(list.getAdapter().getView(5, null, list), 5, 5);
        CheckBox fourth = (CheckBox) ((ViewGroup) list.getAdapter().getView(3, null, list)).getChildAt(0);
        assertEquals("said c3", fourth.getText().toString());
        assertTrue(fourth.isChecked());
        CheckBox sixth = (CheckBox) ((ViewGroup) list.getAdapter().getView(5, null, list)).getChildAt(0);
        assertFalse("a second tap takes the tick off", sixth.isChecked());
        assertTrue(remove.isEnabled());
        assertEquals("nothing goes out before Remove", 2, account.requests.size());

        assertTrue(remove.performClick());
        looper.idle();
        assertFalse(dialog.isShowing());
        assertEquals("Removing the picked comments in 5", bannerText());
        countDown();

        assertEquals("Done. 2 removed.", bannerText());
        assertEquals(18, account.comments.size());
        assertFalse(account.comments.contains("c3"));
        assertFalse(account.comments.contains("c16"));
        assertTrue(account.comments.contains("c5"));
    }

    @Test public void thePickerSaysWhyItHasNothingToShow() {
        SettingsStatus.ghostModeEnabled = true;
        SettingsStatus.accountCleanupEnabled = true;
        FakeTikTok.Account account = new FakeTikTok.Account();
        account.commentHistoryStatus = 8;
        AccountCleanup.servicesForTests = account;
        Preference picker = row(privacyPage(), "action_pick_comments");

        assertTrue(picker.getOnPreferenceClickListener().onPreferenceClick(picker));
        looper.idle();
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        TextView state = dialog.getWindow().getDecorView().findViewWithTag("hushfeed_comment_picker_state");
        assertEquals("TikTok refused to list your comments", state.getText().toString());
        assertEquals("asking again stays possible", View.VISIBLE,
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());

        account.commentHistoryStatus = 0;
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        looper.idle();
        assertEquals("No comments to show", state.getText().toString());
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        dialog.dismiss();

        SignedInUser.idForTests = "";
        assertTrue(picker.getOnPreferenceClickListener().onPreferenceClick(picker));
        looper.idle();
        assertEquals("Sign in to TikTok first", bannerText());
    }

    private static Preference row(SettingsMenuPreference group, String key) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference row = group.getPreference(index);
            if (key.equals(row.getKey())) return row;
        }
        throw new AssertionError("no row " + key);
    }

    private SettingsMenuPreference privacyPage() {
        FrameLayout container = new FrameLayout(activity);
        container.setId(View.generateViewId());
        content.removeAllViews();
        content.addView(container);
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "PRIVACY");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(container.getId(), fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        looper.idle();
        SettingsMenuPreference group = (SettingsMenuPreference) fragment.findPreference("section_privacy");
        assertNotNull("the main list has no Privacy category", group);
        return group;
    }

    private static List<Preference> cleanupRows(SettingsMenuPreference group) {
        List<Preference> rows = new ArrayList<>();
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference row = group.getPreference(index);
            String key = row.getKey();
            if (key != null && (key.equals("action_remove_reposts") || key.equals("action_remove_likes")
                    || key.equals("action_clear_favorites") || key.equals("action_remove_comments")
                    || key.equals("action_pick_comments") || key.equals("action_unfollow_not_following_back"))) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static int indexOf(SettingsMenuPreference group, Preference row) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            if (group.getPreference(index) == row) return index;
        }
        return -1;
    }
}
