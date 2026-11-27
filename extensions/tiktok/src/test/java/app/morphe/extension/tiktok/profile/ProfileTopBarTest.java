package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Hide profile views and Hide Add friends (#158) on the Profile tab's top bar, built the way the
 * S22 showed it on 47.1.4: the end row holds the profile views entrance (a FrameLayout with the
 * capsule inside), Add friends (an icon with no id) and the menu button, in that order.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileTopBarTest {
    private static final int ROW_ID = 0x7f0a0f61;
    private static final int CAPSULE_ID = 0x7f0a0f62;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        ProfileTopBar.resetForTests();
        ProfileTopBar.putIdsForTests(context.getPackageName(), ROW_ID, CAPSULE_ID);
    }

    @After
    public void tearDown() {
        Settings.HIDE_PROFILE_VIEWS_BUTTON.resetToDefault();
        Settings.HIDE_PROFILE_ADD_FRIENDS.resetToDefault();
        Settings.HIDE_ADD_BIO.resetToDefault();
        ProfileTopBar.resetForTests();
    }

    /** The views of one top bar, own profile or not. */
    private static final class Bar {
        FrameLayout root;
        LinearLayout row;
        View entrance;
        ImageView addFriends;
        ImageView menu;
        ImageView edit;
    }

    private FrameLayout entrance() {
        FrameLayout entrance = new FrameLayout(context);
        entrance.setClickable(true);
        FrameLayout inflated = new FrameLayout(context);
        FrameLayout capsule = new FrameLayout(context);
        capsule.setId(CAPSULE_ID);
        inflated.addView(capsule);
        entrance.addView(inflated);
        return entrance;
    }

    private Bar bar(View... endRow) {
        Bar bar = new Bar();
        bar.root = new FrameLayout(context);
        LinearLayout navBar = new LinearLayout(context);
        LinearLayout start = new LinearLayout(context);
        bar.edit = new ImageView(context);
        start.addView(bar.edit);
        bar.row = new LinearLayout(context);
        bar.row.setId(ROW_ID);
        for (View child : endRow) bar.row.addView(child);
        navBar.addView(start);
        navBar.addView(bar.row);
        bar.root.addView(navBar);
        return bar;
    }

    private Bar ownProfile() {
        View entrance = entrance();
        ImageView addFriends = new ImageView(context);
        ImageView menu = new ImageView(context);
        menu.setContentDescription("Profile menu");
        Bar bar = bar(entrance, addFriends, menu);
        bar.entrance = entrance;
        bar.addFriends = addFriends;
        bar.menu = menu;
        return bar;
    }

    @Test
    public void bothOffLeaveTheBarAsTikTokBuiltIt() {
        Bar bar = ownProfile();
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, bar.entrance.getVisibility());
        assertEquals(View.VISIBLE, bar.addFriends.getVisibility());
        assertEquals(View.VISIBLE, bar.menu.getVisibility());
    }

    @Test
    public void hideProfileViewsTakesTheEntranceOnlyAndOffPutsItBack() {
        Bar bar = ownProfile();
        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.GONE, bar.entrance.getVisibility());
        assertEquals(View.VISIBLE, bar.addFriends.getVisibility());
        assertEquals(View.VISIBLE, bar.menu.getVisibility());
        assertSame("the menu stays the row's last button", bar.menu, bar.row.getChildAt(2));
        assertEquals(View.VISIBLE, bar.edit.getVisibility());

        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(false);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, bar.entrance.getVisibility());
    }

    @Test
    public void hideAddFriendsTakesTheIconBeforeTheMenuAndOffPutsItBack() {
        Bar bar = ownProfile();
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.GONE, bar.addFriends.getVisibility());
        assertEquals(View.VISIBLE, bar.entrance.getVisibility());
        assertEquals(View.VISIBLE, bar.menu.getVisibility());

        Settings.HIDE_PROFILE_ADD_FRIENDS.save(false);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, bar.addFriends.getVisibility());
    }

    @Test
    public void bothOnHoldAgainstTikTokShowingThemAgain() {
        Bar bar = ownProfile();
        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(true);
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.GONE, bar.entrance.getVisibility());
        assertEquals(View.GONE, bar.addFriends.getVisibility());
        assertEquals(View.VISIBLE, bar.menu.getVisibility());

        // The entrance's data arrives and TikTok shows it again; a hidden entrance still marks the row.
        bar.entrance.setVisibility(View.VISIBLE);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.GONE, bar.entrance.getVisibility());
        assertEquals(View.GONE, bar.addFriends.getVisibility());

        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(false);
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(false);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, bar.entrance.getVisibility());
        assertEquals(View.VISIBLE, bar.addFriends.getVisibility());
        assertEquals(View.VISIBLE, bar.menu.getVisibility());
    }

    @Test
    public void aButtonTikTokHidItselfStaysHiddenWhenTheSwitchGoesOff() {
        Bar bar = ownProfile();
        bar.addFriends.setVisibility(View.INVISIBLE);
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.GONE, bar.addFriends.getVisibility());
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(false);
        ProfileTopBar.apply(bar.root);
        assertEquals("back to what TikTok had", View.INVISIBLE, bar.addFriends.getVisibility());
    }

    @Test
    public void anotherProfilesBellAndMoreAreLeftAlone() {
        ImageView bell = new ImageView(context);
        ImageView more = new ImageView(context);
        Bar bar = bar(bell, more);
        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(true);
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, bell.getVisibility());
        assertEquals(View.VISIBLE, more.getVisibility());
    }

    @Test
    public void aRowOfAnotherShapeKeepsAddFriends() {
        // Two id-less icons besides the entrance and the menu: which is Add friends isn't known.
        View entrance = entrance();
        ImageView first = new ImageView(context);
        ImageView second = new ImageView(context);
        ImageView menu = new ImageView(context);
        Bar bar = bar(entrance, first, second, menu);
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, first.getVisibility());
        assertEquals(View.VISIBLE, second.getVisibility());
        assertEquals(View.VISIBLE, menu.getVisibility());

        // An icon with an id of its own isn't the one the nav bar builds.
        ImageView named = new ImageView(context);
        named.setId(0x7f0a0f63);
        Bar other = bar(entrance(), named, new ImageView(context));
        ProfileTopBar.apply(other.root);
        assertEquals(View.VISIBLE, named.getVisibility());
    }

    @Test
    public void theEntranceIsNeverTheMenuSlot() {
        View entrance = entrance();
        Bar bar = bar(new ImageView(context), entrance);
        assertNull(ProfileTopBar.entrance(bar.row, CAPSULE_ID));
        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, entrance.getVisibility());
    }

    @Test
    public void unresolvedIdsChangeNothing() {
        ProfileTopBar.resetForTests();
        ProfileTopBar.putIdsForTests(context.getPackageName(), 0, 0);
        Bar bar = ownProfile();
        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(true);
        Settings.HIDE_PROFILE_ADD_FRIENDS.save(true);
        ProfileTopBar.apply(bar.root);
        assertEquals(View.VISIBLE, bar.entrance.getVisibility());
        assertEquals(View.VISIBLE, bar.addFriends.getVisibility());
    }

    /** A profile page on screen: its top bar, then a view under it that a tip can point at. */
    private View pageWith(Bar bar, LinearLayout page) {
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(bar.root);
        ImageView tab = new ImageView(context);
        page.addView(tab);
        return tab;
    }

    /** #546: Hide tips on your profile keeps a message tip closed when it points at your own profile. */
    @Test
    public void aTipOnYourOwnProfileStaysClosedOnlyWithTheSwitchOn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Bar own = ownProfile();
        LinearLayout page = new LinearLayout(context);
        View tab = pageWith(own, page);
        activity.setContentView(page);

        assertFalse("the switch starts off", ProfileAddBio.skipTip(tab));
        Settings.HIDE_ADD_BIO.save(true);
        assertTrue(ProfileAddBio.skipTip(tab));
        assertTrue("the bar's own buttons too", ProfileAddBio.skipTip(own.edit));
        // Hide profile views takes the entrance off, and the bar is still yours.
        Settings.HIDE_PROFILE_VIEWS_BUTTON.save(true);
        ProfileTopBar.apply(own.root);
        assertEquals(View.GONE, own.entrance.getVisibility());
        assertTrue(ProfileAddBio.skipTip(tab));
        assertFalse("a tip pointing at nothing", ProfileAddBio.skipTip(null));
    }

    /** #546: tips on someone else's profile, or anywhere with your profile hidden, stay TikTok's. */
    @Test
    public void tipsAwayFromYourOwnProfileStayTikToks() {
        Settings.HIDE_ADD_BIO.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout screen = new FrameLayout(context);

        // Your profile, kept behind the page on top the way a tab keeps it.
        LinearLayout ownPage = new LinearLayout(context);
        View ownTab = pageWith(ownProfile(), ownPage);
        ownPage.setVisibility(View.GONE);
        screen.addView(ownPage);
        // Someone else's profile: its end row has no profile views entrance.
        LinearLayout theirPage = new LinearLayout(context);
        View theirTab = pageWith(bar(new ImageView(context), new ImageView(context)), theirPage);
        screen.addView(theirPage);
        // A page with no top bar at all, like the feed.
        FrameLayout feed = new FrameLayout(context);
        ImageView feedButton = new ImageView(context);
        feed.addView(feedButton);
        activity.setContentView(screen);

        assertFalse("someone else's profile", ProfileAddBio.skipTip(theirTab));
        assertFalse("your profile, hidden", ProfileAddBio.skipTip(ownTab));
        assertFalse("a view on no page", ProfileAddBio.skipTip(feedButton));
        screen.removeView(theirPage);
        screen.addView(feed);
        assertFalse("the feed over your hidden profile", ProfileAddBio.skipTip(feedButton));
    }
}
