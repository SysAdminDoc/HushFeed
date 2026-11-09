/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.navigation.BottomTabIcons;
import app.morphe.extension.tiktok.navigation.StartPage;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.TabSelectionPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class FeedNavigationPreferenceCategory extends ConditionalPreferenceCategory {
    public FeedNavigationPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Feed tabs");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.feedNavigationEnabled || SettingsStatus.bottomTabLabelsEnabled
                || SettingsStatus.discoverTabEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new TogglePreference(
                context,
                "Filter feed tabs",
                "Choose which feed tabs stay visible. If only For You is left, its tab name is "
                        + "hidden.",
                Settings.FEED_NAVIGATION
        ));
        addPreference(new TabSelectionPreference(
                context,
                Settings.FEED_NAVIGATION_TABS
        ));
        addPreference(new TogglePreference(
                context,
                "Block new feed tabs",
                "Hide tabs TikTok adds later unless you allow them.",
                Settings.FEED_NAVIGATION_BLOCK_NEW_TABS
        ));
        addPreference(new TogglePreference(
                context,
                "Hide the tab names above the feed",
                "The Following and For You names above the feed go away. Swiping between the feeds and the search button stay as they are.",
                Settings.HIDE_FEED_TAB_STRIP
        ));
        addPreference(new TogglePreference(
                context,
                "Filter bottom tabs",
                "Choose which bottom tabs stay visible.",
                Settings.BOTTOM_NAVIGATION
        ));
        addPreference(new TabSelectionPreference(
                context,
                Settings.BOTTOM_NAVIGATION_TABS,
                true
        ));
        addPreference(new TogglePreference(
                context,
                "Block new bottom tabs",
                "Hide bottom tabs TikTok adds later unless you allow them.",
                Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS
        ));
        // Left out on a build where the getter the bar's layout comes through wasn't found.
        if (SettingsStatus.discoverTabEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Discover in the bottom bar",
                    "Discover takes the place of Friends in the bottom bar, the way TikTok lays it "
                            + "out in some countries. Your region and your feed stay as they are.",
                    Settings.DISCOVER_BOTTOM_TAB
            ));
            addPreference(new TogglePreference(
                    context,
                    "Friends in the top tabs",
                    "Friends joins the tabs at the top of the feed, next to Following, the way TikTok lays it "
                            + "out in some countries. Your region and your feed stay as they are.",
                    Settings.FRIENDS_TOP_TAB
            ));
        }
        addPreference(new ChoicePreference(context, "Open TikTok on", Settings.START_PAGE,
                new String[]{"TikTok default", "For You", "Following", "Friends", "Discover", "Inbox", "Profile"},
                new String[]{StartPage.TIKTOK, StartPage.FOR_YOU, StartPage.FOLLOWING, StartPage.FRIENDS, StartPage.DISCOVER, StartPage.INBOX, StartPage.PROFILE}));
        addPreference(new TogglePreference(
                context,
                "Show TikTok's feed buttons",
                "Shows TikTok's play, pause, previous and next buttons on the feed. TikTok "
                        + "normally shows them only to people who use a screen reader. TikTok "
                        + "can see when you use them.",
                Settings.SHOW_FEED_BUTTONS
        ));
        addPreference(new TogglePreference(
                context,
                "Long-press Home for Hushfeed settings",
                "Press and hold the Home tab to open these settings. A tap on Home works as before.",
                Settings.HOME_TAB_OPENS_SETTINGS
        ));
        // The tab's tap and the start tab both ride on the feed tab hooks.
        if (SettingsStatus.feedNavigationEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Search instead of Home",
                    "The Home tab becomes a search button. Its icon and name say Search, and a tap opens "
                            + "TikTok's search instead of For You. TikTok starts on Inbox, or on Profile when "
                            + "Inbox is hidden, so the feed doesn't play when you open it.",
                    Settings.SEARCH_INSTEAD_OF_HOME
            ));
        }
        addPreference(new TogglePreference(
                context,
                "Long-press Profile for Watch history",
                "Press and hold the Profile tab to open your Watch history. A tap on Profile works "
                        + "as before. If holding Profile switches accounts for you, this takes that "
                        + "press, and the name at the top of your profile still switches accounts.",
                Settings.PROFILE_TAB_OPENS_WATCH_HISTORY
        ));
        // The press rides on the feed tab hooks and flips the read receipt patch's switch, so it
        // needs both; with only the read receipt patch the page isn't offered at all.
        if (SettingsStatus.feedNavigationEnabled && SettingsStatus.hideReadReceiptsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Long-press Inbox for read receipts",
                    "Press and hold the Inbox tab to turn Hide read receipts on or off. A message says "
                            + "which. A tap on Inbox works as before. If holding Inbox does something for "
                            + "you already, this takes that press.",
                    Settings.INBOX_TAB_FLIPS_READ_RECEIPTS
            ));
        }
        addPreference(new TogglePreference(
                context,
                "Keep For You on a Home tap",
                "A tap on Home or on the For You tab while For You is showing no longer reloads it. "
                        + "The video you're on stays.",
                Settings.KEEP_FOR_YOU_ON_TAB_TAP
        ));
        addPreference(new TogglePreference(
                context,
                "Keep For You on a pull down",
                "Pulling down at the top of For You no longer reloads it.",
                Settings.KEEP_FOR_YOU_ON_PULL_DOWN
        ));
        addPreference(new TogglePreference(
                context,
                "Hide the unread badges on the bottom tabs",
                "Hide the red counts and dots on every bottom tab, Inbox and Profile included, and the "
                        + "bubble over Inbox that counts new likes, followers, comments and who's LIVE. "
                        + "The inbox itself still shows what came in.",
                Settings.HIDE_TAB_BADGES
        ));
        // Left out on a build where the getter for TikTok's icon experiment wasn't found.
        if (SettingsStatus.bottomTabIconsEnabled) {
            addPreference(new ChoicePreference(context, "Bottom bar icons", Settings.BOTTOM_TAB_ICON_STYLE,
                    new String[]{"TikTok default", "Original", "New style 1", "New style 2"},
                    new String[]{BottomTabIcons.TIKTOK, BottomTabIcons.ORIGINAL, BottomTabIcons.NEW_STYLE_1, BottomTabIcons.NEW_STYLE_2})
                    .withNote("TikTok tests more than one set of icons for the bottom bar. Pick one here, or leave it to TikTok."));
        }
        // Left out with their hooks on a build where the tab icons' names weren't found.
        if (SettingsStatus.bottomTabLabelsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the bottom tab names",
                    "The words under the bottom tab icons go away. The icons stay, and each tab "
                            + "opens what it always did.",
                    Settings.HIDE_BOTTOM_TAB_LABELS
            ));
            addTabName(context, "Home tab name", Settings.BOTTOM_TAB_NAME_HOME);
            addTabName(context, "Friends tab name", Settings.BOTTOM_TAB_NAME_FRIENDS);
            addTabName(context, "Inbox tab name", Settings.BOTTOM_TAB_NAME_INBOX);
            addTabName(context, "Profile tab name", Settings.BOTTOM_TAB_NAME_PROFILE);
            addTabName(context, "Shop tab name", Settings.BOTTOM_TAB_NAME_SHOP);
        }
        addPreference(new TogglePreference(
                context,
                "Hide the Tako bubble",
                "Hide the Tako AI bubble above the profile button or in the bottom corner of videos, "
                        + "the Ask bar under videos, "
                        + "the Ask Tako button on the search page, the Ask Tako tab on search results "
                        + "and the Tako bar above comments.",
                Settings.HIDE_TAKO_AI
        ));
    }

    private void addTabName(Context context, String title, StringSetting setting) {
        addPreference(new InputTextPreference(
                context,
                title,
                "A name of your own under this tab's icon. Leave it empty to keep TikTok's.",
                setting
        ));
    }
}
