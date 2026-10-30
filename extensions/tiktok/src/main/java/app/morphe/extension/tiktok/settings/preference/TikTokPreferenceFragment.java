/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TikTokPreferenceFragment.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TikTokPreferenceFragment.java
 */

package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.playback.FeedMute;
import app.morphe.extension.tiktok.settings.L10n;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.widget.ListView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.captions.CaptionTools;
import app.morphe.extension.tiktok.comment.CommentSearch;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabFragment;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabRuntime;
import app.morphe.extension.tiktok.settings.ProblemReport;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.share.ShareUrlSanitizer;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedNavigationPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.PrivacyPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SharePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;

@SuppressWarnings("deprecation")
public class TikTokPreferenceFragment extends AbstractPreferenceFragment {
    private static final String FEATURE_GATE_LAB_KEY = "action_feature_gate_lab";
    private static final String PAUSE_SUMMARY = "Pause Hushfeed's switches after TikTok restarts. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay.";
    /** A switch from a patch left unticked in the Manager is on no page and in no index, and
     *  nothing else on the screen says so (#29 looked for Fill without its patch). */
    private static final String NO_MATCHES_SUMMARY = "Try a different word or clear the search. "
            + "Switches from patches you didn't tick in Morphe Manager aren't listed.";
    private static final int REQUEST_DOWNLOAD_PATH_FOLDER = 8841;
    private static final int HIGHLIGHT_OWNER = 0x7f7f4005;
    private static final String ARG_SECTION = "morphe_settings_section";
    private static final String ARG_HUB = "morphe_settings_hub";
    private static final String ARG_SEARCH = "morphe_settings_search";
    private static final String ARG_TARGET_KEY = "morphe_settings_target_key";
    /** A checklist row's one box: the setting key of the member search landed on. */
    private static final String ARG_TARGET_MEMBER = "morphe_settings_target_member";
    /**
     * The row a restart was pressed with at the top of the screen. The page comes back scrolled
     * to it, quietly: unlike a search result's target it isn't flashed and changes nothing else.
     */
    private static final String ARG_RETURN_ROW = "morphe_settings_return_row";
    /** Between the page and the row in a return marker: "home#hushfeed_paused". */
    private static final char ROW_MARK = '#';
    /** The page names a restart's return marker carries ({@link #pageKey}). */
    public static final String PAGE_HOME = "home";
    /** The Feature Gate Lab, which the settings screen opens over home after a restart from it. */
    public static final String PAGE_LAB = "lab";
    static final String REPORT_PROBLEM_KEY = "action_report_problem";
    static final String SUPPORT_KEY = "action_support_hushfeed";
    static final String SUPPORT_URL = "https://ko-fi.com/X8K126YVER";
    static final String PAGE_SEARCH = "search";
    private static final String PAGE_HUB = "hub:";
    private static final String PAGE_SECTION = "section:";
    // Volatile: a file read or write's worker looks it up to report in the page open now.
    private static volatile TikTokPreferenceFragment activeFragment;
    /** Pinned to the top of this page while a restart is owed; off the page otherwise. */
    private RestartPendingPreference restartPending;
    /** Rows on this page whose sentence was swapped for "Restart pending.", to swap back. */
    private final java.util.Set<Preference> pendingSummaries =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Summaries a parent's reason was appended to, so it can be taken off again. */
    private final java.util.Map<Preference, String> reasonSummaries = new java.util.IdentityHashMap<>();
    /**
     * Which folder setting the picker was opened for, by key rather than by the preference
     * itself. The picker is a separate activity, so this one is routinely destroyed behind it
     * and the object that was waiting no longer belongs to the screen that comes back.
     */
    private static String pendingDownloadPathKey;
    private static final String PENDING_DOWNLOAD_PATH_STATE = "morphe_pending_download_path";
    private static final String SEARCH_QUERY_STATE = "morphe_search_query";
    private static final String SEARCH_POSITION_STATE = "morphe_search_position";
    private static final String SEARCH_OFFSET_STATE = "morphe_search_offset";
    private static final String SEARCH_POSITION_QUERY_STATE = "morphe_search_position_query";
    private int searchPosition = -1, searchOffset;
    private String searchPositionQuery;
    private SettingsListAdapter styledAdapter;
    /**
     * Set once this page has scrolled to where it was opened for. Back to a page another one
     * opened over brings this same instance back with a new view and no saved state, which
     * looks like an arrival; the page keeps where the reader left it instead.
     */
    private boolean arrived;
    private PreferenceScreen searchScreen;
    private List<SearchResult> searchIndex;
    /** Set when a search result opens its page, where its setting may change. */
    private boolean searchIndexStale;
    private final List<Preference> searchRows = new ArrayList<>();
    /** The search the rows below the box show, trimmed. */
    private String shownSearch = "";
    private SettingsSearchInputPreference searchInput;
    private SettingsHeaderPreference masterHeader;
    private SettingsSearchEntryPreference searchEntry;
    private final java.util.Map<Section, SettingsMenuPreference> sectionMenus =
            new java.util.EnumMap<>(Section.class);
    private SettingsMenuPreference aboutMenu;
    private static final String EXPANDED_STATE = "morphe_expanded_settings";
    private static final String SEARCH_ACTIVE_STATE = "morphe_inline_search_active";
    private static final String ARG_EXPANDED = "morphe_initial_expanded_settings";
    private ArrayList<String> restoredExpanded;
    private ArrayList<String> searchExpanded;
    private boolean restoringState, restoredSearch;

    @Override public void onCreate(Bundle state) {
        restoringState = state != null;
        if (state != null) {
            restoredExpanded = state.getStringArrayList(EXPANDED_STATE);
            restoredSearch = state.getBoolean(SEARCH_ACTIVE_STATE);
        }
        super.onCreate(state);
    }

    /**
     * Each section carries one sentence, used both as the subtitle on the home row and as the
     * caption at the top of its own page. There used to be two, and they disagreed: the
     * Downloads row promised quality and subtitles while its page said path and watermark.
     */
    private enum Section {
        FEED_FILTER("Feed filter", "Choose what reaches your feed", SettingsMenuPreference.Icon.FILTER),
        FEED_NAVIGATION("Feed tabs", "Arrange your feed and bottom tabs", SettingsMenuPreference.Icon.TABS),
        INTERFACE("Feed screen", "Captions, gestures and on-screen controls", SettingsMenuPreference.Icon.LAYOUT),
        PLAYBACK("Playback", "Quality, speed and auto-advance", SettingsMenuPreference.Icon.PLAYBACK),
        SCREEN_TIME("Screen time", "Daily budgets, reminders and the hold", SettingsMenuPreference.Icon.SCREEN_TIME),
        COMMENTS("Comments", "Filters, translation and copy options", SettingsMenuPreference.Icon.COMMENTS),
        DOWNLOADS("Downloads", "Quality, files, subtitles and hand-off", SettingsMenuPreference.Icon.DOWNLOADS),
        SHARE("Share sheet", "People, shortcuts and sending controls", SettingsMenuPreference.Icon.SHARE),
        INBOX("Inbox", "Choose which rows and controls appear", SettingsMenuPreference.Icon.INBOX),
        PRIVACY("Privacy", "Tracking, device access and links", SettingsMenuPreference.Icon.PRIVACY),
        REGION("Region", "Country and network preferences", SettingsMenuPreference.Icon.REGION),
        BEHAVIOR("App", "Layout, search, profile and system", SettingsMenuPreference.Icon.BEHAVIOR),
        DIAGNOSTICS("Diagnostics", "Logging, hook status and reports", SettingsMenuPreference.Icon.DIAGNOSTICS),
        BACKUP("Backup and restore", "Save, restore, reset and undo your settings", SettingsMenuPreference.Icon.BACKUP);

        final String title;
        final String description;
        final SettingsMenuPreference.Icon icon;

        Section(String title, String description, SettingsMenuPreference.Icon icon) {
            this.title = title;
            this.description = description;
            this.icon = icon;
        }

        /** Whether the bundle carries the patches behind this page. */
        boolean isAvailable() {
            switch (this) {
                case FEED_FILTER: return FeedFilterPreferenceCategory.isAvailable();
                case FEED_NAVIGATION: return FeedNavigationPreferenceCategory.isAvailable();
                case INTERFACE: return InterfacePreferenceCategory.isAvailable();
                case PLAYBACK: return PlaybackPreferenceCategory.isAvailable();
                case SCREEN_TIME: return ScreenTimePreferenceCategory.isAvailable();
                case COMMENTS: return CommentsPreferenceCategory.isAvailable();
                case DOWNLOADS: return DownloadsPreferenceCategory.isAvailable();
                case SHARE: return SharePreferenceCategory.isAvailable();
                case INBOX: return InboxPreferenceCategory.isAvailable();
                case PRIVACY: return PrivacyPreferenceCategory.isAvailable();
                case REGION: return SimSpoofPreferenceCategory.isAvailable();
                case BEHAVIOR: return ExtensionPreferenceCategory.isAvailable();
                case DIAGNOSTICS: return DebugPreferenceCategory.isAvailable();
                case BACKUP:
                default: return true;
            }
        }
    }

    /**
     * The one page that isn't a section. Every section is a row on the home page (Matt,
     * 2026-10-12: at most one level below it), so the four groups that used to sit between them
     * are gone, and a restart marker naming one of those opens the home page.
     */
    private enum Hub {
        ABOUT("About Hushfeed", "Version, changes and licenses");

        final String title;
        final String description;

        Hub(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    private static final class SearchResult implements app.morphe.extension.tiktok.settings.SearchMatch.Row {
        final Section section;
        final String key;
        final String title;
        final String summary;
        final String category;
        /** The setting key of one box in a checklist row, or null for a row of its own. */
        final String member;
        /** Folded once when the index is built: it never changes, and a query is typed a letter
         *  at a time over about 170 of these. */
        final String normalized;
        /** The title alone, folded the same way, so a hit there ranks above a description hit. */
        final String normalizedTitle;
        /** A plain switch, which the results flip where it stands instead of opening its page. */
        boolean flipsInPlace;
        /** The row's text without its choice labels, folded, to tell a hit on one of those apart. */
        private final String normalizedShown;

        SearchResult(Section section, String key, String title, String summary, String category) {
            this(section, key, title, summary, category, "");
        }

        /** {@code keywords} are searched but never shown: words a reader types for the row that
         *  its title and description don't use. */
        SearchResult(Section section, String key, String title, String summary, String category,
                String keywords) {
            this(section, key, title, summary, category, keywords, "");
        }

        /** {@code choices} are a choice row's options, of which its summary names only the chosen one. */
        SearchResult(Section section, String key, String title, String summary, String category,
                String keywords, String choices) {
            this(section, key, null, title, summary, category,
                    title + " " + summary + " " + category + " " + keywords, choices);
        }

        private SearchResult(Section section, String key, String member, String title,
                String summary, String category, String searched, String choices) {
            this.section = section;
            this.key = key;
            this.member = member;
            this.title = title;
            this.summary = summary;
            this.category = category;
            this.normalizedShown = normalizeSearchText(searched);
            this.normalized = choices.isEmpty() ? normalizedShown : normalizeSearchText(searched + " " + choices);
            this.normalizedTitle = normalizeSearchText(title);
        }

        /** Found by an option other than the chosen one, which only the row's dialog shows. */
        boolean foundByChoiceOnly(String query) {
            String folded = normalizeSearchText(query == null ? "" : query);
            return !folded.isEmpty() && normalized.contains(folded) && !normalizedShown.contains(folded);
        }

        /**
         * One box in a checklist row. Its second line names the row it sits in, but the row's
         * title is not searched: "hide buttons" would otherwise list all seven boxes under the
         * row that already answers it.
         */
        static SearchResult member(Section section, String key, String member, String label,
                String rowTitle, String category) {
            return new SearchResult(section, key, member, label, rowTitle, category,
                    label + " " + category, "");
        }

        String displaySummary() {
            if (summary.isEmpty()) {
                return category;
            }
            return category + " · " + summary;
        }

        @Override public String searchedText() {
            return normalized;
        }

        @Override public String titleText() {
            return normalizedTitle;
        }
    }

    public static void openSeenVideoHistoryPicker() {
        if (activeFragment != null && activeFragment.isAdded()) {
            ImportSeenVideoHistoryPreference.pickFile(activeFragment);
        } else {
            SettingsActionBanner.showNotice(Utils.getContext(), L10n.t(
                    "Reopen Hushfeed settings, then choose the file"));
        }
    }

    /** Import a font file, whose picker answers the page that asked. */
    public static void openImportedFontPicker() {
        if (activeFragment != null && activeFragment.isAdded()) {
            ImportedFontPreference.pickFile(activeFragment);
        } else {
            SettingsActionBanner.showNotice(Utils.getContext(), L10n.t(
                    "Reopen Hushfeed settings, then choose the file"));
        }
    }

    /**
     * Owes a restart for a file the next start reads rather than a setting, the way turning
     * Hushfeed back on owes one for its marker file, and says so on the page open now.
     */
    static void noteFileRestartPending(String key) {
        AbstractPreferenceFragment.restartPending.add(key);
        TikTokPreferenceFragment open = active();
        if (open != null) open.refreshRestartPending();
    }

    /** Takes back a restart {@link #noteFileRestartPending} owed, once what needed it is undone. */
    static void noteFileRestartSettled(String key) {
        AbstractPreferenceFragment.restartPending.remove(key);
        TikTokPreferenceFragment open = active();
        if (open != null) open.refreshRestartPending();
    }

    /** Save or Restore seen history, whose picker answers the page that asked. */
    public static void openSeenHistoryFilePicker(int request) {
        if (activeFragment != null && activeFragment.isAdded()) {
            SeenHistoryFilePreference.pickFile(activeFragment, request);
        } else {
            SettingsActionBanner.showNotice(Utils.getContext(), L10n.t(
                    "Reopen Hushfeed settings, then choose the file"));
        }
    }

    public static void openDownloadPathFolderPicker(DownloadPathPreference preference) {
        if (activeFragment == null) {
            app.morphe.extension.shared.Utils.showToastLong(L10n.t(
                    "No folder picker here. Type the folder in the row."));
            return;
        }

        pendingDownloadPathKey = preference.getKey();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activeFragment.startActivityForResult(intent, REQUEST_DOWNLOAD_PATH_FOLDER);
        } catch (ActivityNotFoundException exception) {
            pendingDownloadPathKey = null;
            app.morphe.extension.shared.Utils.showToastLong(L10n.t(
                    "No folder picker here. Type the folder in the row."));
        }
    }

    @Override
    protected void syncPreferenceWithStoredValue(@NonNull Preference pref,
                                                @NonNull Setting<?> setting,
                                                @NonNull SharedPreferences preferences) {
        if (pref instanceof NumberInputPreference) {
            NumberInputPreference numberPref = (NumberInputPreference) pref;
            numberPref.setValueWithoutPersisting(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof CreatorListPreference) {
            CreatorListPreference creatorPref = (CreatorListPreference) pref;
            creatorPref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof RangeValuePreference) {
            RangeValuePreference rangePref = (RangeValuePreference) pref;
            rangePref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof DownloadPathPreference) {
            DownloadPathPreference pathPref = (DownloadPathPreference) pref;
            pathPref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof TabSelectionPreference) {
            TabSelectionPreference tabsPref = (TabSelectionPreference) pref;
            tabsPref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else {
            super.syncPreferenceWithStoredValue(pref, setting, preferences);
        }
    }

    @Override
    protected void syncSettingWithPreference(
            @NonNull Preference pref,
            @NonNull Setting setting,
            boolean applySettingToPreference
    ) {
        if (pref instanceof NumberInputPreference) {
            NumberInputPreference numberInputPreference = (NumberInputPreference) pref;
            if (applySettingToPreference) {
                numberInputPreference.setValueWithoutPersisting(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, numberInputPreference.getValue());
            }
        } else if (pref instanceof CreatorListPreference) {
            CreatorListPreference creatorListPreference = (CreatorListPreference) pref;
            if (applySettingToPreference) {
                creatorListPreference.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, creatorListPreference.getValue());
            }
        } else if (pref instanceof RangeValuePreference) {
            RangeValuePreference rangeValuePref = (RangeValuePreference) pref;
            if (applySettingToPreference) {
                rangeValuePref.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, rangeValuePref.getValue());
            }
        } else if (pref instanceof DownloadPathPreference) {
            DownloadPathPreference downloadPathPref = (DownloadPathPreference) pref;
            if (applySettingToPreference) {
                downloadPathPref.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, downloadPathPref.getValue());
            }
        } else if (pref instanceof TabSelectionPreference) {
            TabSelectionPreference tabSelectionPref = (TabSelectionPreference) pref;
            if (applySettingToPreference) {
                tabSelectionPref.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, tabSelectionPref.getValue());
            }
        } else {
            super.syncSettingWithPreference(pref, setting, applySettingToPreference);
        }
        if (!applySettingToPreference
                && (setting == Settings.BLOCK_AUTHOR_BUTTON
                || setting == Settings.LOCAL_HIDE_BUTTON
                || setting == Settings.BLOCK_SOUND_BUTTON
                || setting == Settings.NOT_INTERESTED_BUTTON
                || setting == Settings.FEED_MUTE_BUTTON)) {
            BlockAuthorOverlay.refresh();
        }
        if (!applySettingToPreference && setting == Settings.FEED_MUTED) {
            FeedMute.refresh();
            BlockAuthorOverlay.refresh();
        }
        if (!applySettingToPreference && (setting == Settings.COMMENT_SEARCH || setting == Settings.COMMENT_EXPORT
                || setting == Settings.COMMENT_SEARCH_FOLDED)) {
            CommentSearch.onSettingChanged();
        }
        if (!applySettingToPreference && setting == Settings.KEEP_CAPTIONS_CLEAR_DISPLAY) {
            CaptionTools.onSettingChanged();
        }
        if (!applySettingToPreference && setting == Settings.COMMENT_BATCH_TRANSLATION) {
            app.morphe.extension.tiktok.translation.CommentTranslateButton.onAutoTranslateChanged();
        }
        if (!applySettingToPreference && (setting == Settings.SHOW_COMMENTER_HANDLE
                || setting == Settings.SHOW_COMMENTER_REGION || setting == Settings.COLOR_COMMENTER_NAMES
                || setting == Settings.REGION_AS_FLAG || setting == BaseSettings.PAUSED)) {
            app.morphe.extension.tiktok.comment.CommenterDetails.onSettingChanged();
        }
        if (!applySettingToPreference && (setting == Settings.FEED_DESCRIPTION_TEXT_SIZE
                || setting == Settings.FEED_AUTHOR_TEXT_SIZE || setting == BaseSettings.PAUSED)) {
            app.morphe.extension.tiktok.feed.FeedTextSize.onSettingChanged();
        }
        if (!applySettingToPreference && setting == BaseSettings.PAUSED) {
            refreshStatusCard();
        }
    }

    @Override
    protected boolean prefIsSetToDefault(Preference pref, Setting<?> setting) {
        String defaultValue = setting.defaultValue.toString();
        if (pref instanceof NumberInputPreference) {
            return defaultValue.equals(((NumberInputPreference) pref).getValue());
        }
        if (pref instanceof CreatorListPreference) {
            return defaultValue.equals(((CreatorListPreference) pref).getValue());
        }
        if (pref instanceof RangeValuePreference) {
            return defaultValue.equals(((RangeValuePreference) pref).getValue());
        }
        if (pref instanceof DownloadPathPreference) {
            return defaultValue.equals(((DownloadPathPreference) pref).getValue());
        }
        if (pref instanceof TabSelectionPreference) {
            return defaultValue.equals(((TabSelectionPreference) pref).getValue());
        }
        return super.prefIsSetToDefault(pref, setting);
    }

    @Override
    protected boolean preferenceShowsSettingValue(@NonNull Preference pref,
                                                  @NonNull Setting<?> setting) {
        String expected = setting.savedValue() instanceof Enum<?>
                ? ((Enum<?>) setting.savedValue()).name() : String.valueOf(setting.savedValue());
        if (pref instanceof NumberInputPreference) {
            return expected.equals(((NumberInputPreference) pref).getValue());
        }
        if (pref instanceof CreatorListPreference) {
            return expected.equals(((CreatorListPreference) pref).getValue());
        }
        if (pref instanceof RangeValuePreference) {
            return expected.equals(((RangeValuePreference) pref).getValue());
        }
        if (pref instanceof DownloadPathPreference) {
            return expected.equals(((DownloadPathPreference) pref).getValue());
        }
        if (pref instanceof TabSelectionPreference) {
            return expected.equals(((TabSelectionPreference) pref).getValue());
        }
        return super.preferenceShowsSettingValue(pref, setting);
    }

    @Override protected CharSequence initializationErrorTitle(Context context) {
        return L10n.t(context, "Settings couldn't open");
    }

    @Override protected CharSequence initializationErrorSummary(Context context) {
        return L10n.t(context, "Try again, or go back to TikTok.");
    }

    @Override protected CharSequence initializationBackLabel(Context context) {
        return L10n.t(context, "Back");
    }

    @Override protected CharSequence initializationRetryLabel(Context context) {
        return L10n.t(context, "Retry");
    }

    /**
     * Draws Retry as the way forward and Back as the way out.
     *
     * <p>Both were plain rows in the same weight and colour, so the page that opens when settings
     * will not load offered two identical looking choices and no sense of which one to take.
     */
    @Override protected ErrorActionStyler errorActionStyler() {
        // SettingsListAdapter styles the rows again after this, so it applies the same helper.
        return SettingsUi::styleErrorAction;
    }

    @Override protected CharSequence preferenceChangeRecoveredMessage(Context context) {
        return L10n.t(context, "The setting couldn't finish updating. Its saved value is shown.");
    }

    @Override protected CharSequence preferenceChangeRecoveryFailedMessage(Context context) {
        return L10n.t(context,
                "Settings couldn't refresh completely. Reopen settings and try again.");
    }

    @Override
    protected void initialize() {
        final var context = getActivity();
        activeFragment = this;

        // Nothing sets one. The framework only raises that dialog for a setting carrying a
        // userDialogMessage, and no setting in this bundle has one, so this was a translated
        // string for a screen nobody could reach. The safety net Reset and Undo do want is its
        // own item on the roadmap.
        confirmDialogTitle = null;

        // The two sentences the shared library says on this bundle's behalf. It cannot reach a
        // translation table itself, so they are handed to it once, from here, where the
        // settings screen is being built and a context is at hand.
        savedMessage = L10n.t(context, "Saved. Restart TikTok to apply this.");
        AbstractPreferenceFragment.setRestartFeedbackPresenter((feedbackContext, ignored) ->
                SettingsActionBanner.showRestart(feedbackContext,
                        RestartPendingPreference.label(feedbackContext)));
        app.morphe.extension.shared.settings.preference.LogBufferManager.clearedMessage =
                L10n.t(context, "Diagnostic data cleared. Tap again to put it back.");
        app.morphe.extension.shared.settings.preference.LogBufferManager.nothingToClearMessage =
                L10n.t(context, "There is no diagnostic data to clear");
        app.morphe.extension.shared.settings.preference.LogBufferManager.restoredMessage =
                L10n.t(context, "Diagnostic data put back");
        app.morphe.extension.shared.settings.preference.LogBufferManager.nothingToRestoreMessage =
                L10n.t(context, "There is no diagnostic data to put back");
        app.morphe.extension.shared.settings.preference.LogBufferManager.restoreFailedMessage =
                L10n.t(context, "Couldn't put back the diagnostic data. Try again.");
        // The rest of what the shared export path says, on a German phone in German. Every one
        // of these reached the reader in English, branded for the library rather than the
        // bundle, and two of them carried an exception's text.
        app.morphe.extension.shared.settings.preference.LogBufferManager.nothingToExportMessage =
                L10n.t(context, "No matching diagnostics found");
        app.morphe.extension.shared.settings.preference.LogBufferManager.copiedMessage =
                L10n.t(context, "Diagnostic report copied to the clipboard");
        app.morphe.extension.shared.settings.preference.LogBufferManager.exportFailedMessage =
                L10n.t(context, "The diagnostic report couldn't be saved. Try again.");
        app.morphe.extension.shared.settings.preference.LogBufferManager.noContextMessage =
                L10n.t(context, "The diagnostic report couldn't be saved yet. Try again in a moment.");
        app.morphe.extension.shared.settings.preference.LogBufferManager.alreadySavingMessage =
                L10n.t(context, "A diagnostic report is already being saved");
        app.morphe.extension.shared.settings.preference.LogBufferManager.savedToMessage =
                L10n.t(context, "Full report saved to %1$s");
        app.morphe.extension.shared.settings.preference.LogBufferManager.couldNotStartMessage =
                L10n.t(context, "Couldn't start the report export. Try again.");
        // Four whole sentences rather than five fragments, so each one is a row a translator
        // can move the numbers around inside. The context is asked for when a line is written
        // rather than captured here: this writer is a static and outlives the screen.
        app.morphe.extension.shared.diagnostics.HookStatus.setLineWriter(
                (family, found, missing, truncated, firstMiss) -> {
                    Context lineContext = app.morphe.extension.shared.Utils.getContext();
                    if (firstMiss == null) {
                        return truncated
                                ? L10n.f(lineContext, "%1$s: %2$d found, %3$d missing, and more it "
                                        + "stopped counting", family, found, missing)
                                : L10n.f(lineContext, "%1$s: %2$d found, %3$d missing",
                                        family, found, missing);
                    }
                    return truncated
                            ? L10n.f(lineContext, "%1$s: %2$d found, %3$d missing, and more it "
                                    + "stopped counting. First missing: %4$s",
                                    family, found, missing, firstMiss)
                            : L10n.f(lineContext, "%1$s: %2$d found, %3$d missing. First missing: "
                                    + "%4$s", family, found, missing, firstMiss);
                });

        SettingsUi.syncDarkMode(context);

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        sectionMenus.clear();
        aboutMenu = null;
        searchScreen = null;
        searchInput = null;
        searchRows.clear();
        pendingSummaries.clear();
        reasonSummaries.clear();
        createMasterMenu(context, preferenceScreen);
        Bundle arguments = getArguments();
        ArrayList<String> expanded = restoringState ? restoredExpanded
                : arguments == null ? null : arguments.getStringArrayList(ARG_EXPANDED);
        restoreExpanded(expanded);
        if (!restoringState) {
            expandSection(getRequestedSection());
            if (getRequestedHub() == Hub.ABOUT) expandAbout();
            String row = getTargetKey();
            if (row == null && arguments != null) row = arguments.getString(ARG_RETURN_ROW);
            if (row != null) expandOwner(row);
        }
        if (restoringState ? restoredSearch
                : arguments != null && arguments.getBoolean(ARG_SEARCH, false)) {
            createSearchMenu(context, preferenceScreen);
        }
        restartPending = new RestartPendingPreference(context);
        refreshRestartPending();
    }

    @Override
    protected void onRestartPendingChanged() {
        refreshRestartPending();
    }

    /**
     * A choice row keeps its own summary, the chosen label with the restart note under it when
     * its setting needs one. The shared sync writes the bare label over that, which is how the
     * Proxy type and Store installer rows lost the note. A value the row has no label for still
     * takes the shared path, which shows the raw value.
     */
    @Override
    protected void updateListPreferenceSummary(android.preference.ListPreference list,
                                               Setting<?> setting) {
        if (list instanceof ChoicePreference
                && list.findIndexOfValue(setting.savedValue().toString()) >= 0) {
            ((ChoicePreference) list).showValue();
            return;
        }
        super.updateListPreferenceSummary(list, setting);
    }

    /**
     * Pins the restart row while a restart is owed and takes it away when nothing is, and lets
     * the rows that owe it say "Restart pending" in place of the sentence every restart-gated
     * row carries, so the reader can see which of their changes are still waiting.
     */
    /**
     * A row its parent has greyed says which switch would turn it on.
     *
     * <p>Four rows grey out when their parent is off and said nothing about it, so the row read
     * as broken and a screen reader announced only "dimmed". The parent's own title is read off
     * its row rather than kept in a second list, because both rows are always on the same page
     * and a title kept twice is a title that drifts.
     */
    @Override
    protected void updatePreferenceAvailability(Preference pref, Setting<?> setting) {
        super.updatePreferenceAvailability(pref, setting);
        Context context = getActivity();
        if (context == null || pref.getKey() == null) return;
        String appended = reasonSummaries.remove(pref);
        if (appended != null) {
            CharSequence current = pref.getSummary();
            if (current != null && current.toString().endsWith(appended)) {
                pref.setSummary(current.toString().substring(0, current.length() - appended.length()));
            }
        }
        if (setting.isAvailable()) return;
        String parentTitle = null;
        Setting<?> named = null;
        for (Setting<?> parent : setting.getParentSettings()) {
            Preference row = findPreference(parent.key);
            CharSequence title = row == null ? null : row.getTitle();
            if (title != null && title.length() > 0) {
                parentTitle = title.toString();
                named = parent;
                break;
            }
        }
        if (parentTitle == null) return;
        // The YTDLnis rows wait on a text field holding one app's package, not on a switch, so
        // "Turn on" sent the reader looking for a toggle that isn't there.
        String note = named == Settings.EXTERNAL_DOWNLOADER_PACKAGE
                ? L10n.f(context, "Put %1$s in %2$s first.", Settings.YTDLNIS_PACKAGE_NAME, parentTitle)
                : L10n.f(context, "Turn on %1$s first.", parentTitle);
        CharSequence summary = pref.getSummary();
        String body = summary == null ? "" : summary.toString();
        // A number row's summary is lines ("0 to 1,000 videos", "Current: Off"), and a space ran
        // the note into the last of them; it gets a line of its own there, and follows the
        // sentence on a prose row. A choice row's summary is its value, "1.5x" or "Video", which
        // read as "Video Turn on ... first." run on, so it gets its own line there too.
        boolean ownLine = body.contains("\n") || pref instanceof android.preference.ListPreference;
        String reason = body.isEmpty() ? note : (ownLine ? "\n" : " ") + note;
        if (body.endsWith(reason)) return;
        pref.setSummary(body + reason);
        reasonSummaries.put(pref, reason);
    }

    private void refreshRestartPending() {
        PreferenceScreen screen = getPreferenceScreen();
        Context context = getActivity();
        if (screen == null || restartPending == null || context == null) return;
        boolean owed = !restartPendingKeys().isEmpty();
        boolean shown = screen.findPreference(RestartPendingPreference.KEY) != null;
        // Only touched while something is owed or shown. removePreference notifies the
        // hierarchy whether or not the row was there, which rebuilt every page's list once
        // more at open and moved twenty captures by a switch frame. While owed, the row is
        // taken out and put back so the list rebinds the count.
        if (shown) screen.removePreference(restartPending);
        if (owed) screen.addPreference(restartPending);
        if (masterHeader != null) masterHeader.refreshStatus();

        String generic = L10n.t(context, TogglePreference.RESTART_SENTENCE);
        // The sentence it replaces ends in a full stop and sits inside prose ("... Restart
        // pending. If the old layout is still there, unfold again."), so this keeps one.
        String pending = L10n.t(context, "Restart pending") + ".";
        java.util.List<String> owedKeys = restartPendingKeys();
        for (Preference row : allRows(screen)) {
            if (!owedKeys.contains(row.getKey())) continue;
            CharSequence summary = row == null ? null : row.getSummary();
            if (summary == null || !summary.toString().contains(generic)) continue;
            row.setSummary(summary.toString().replace(generic, pending));
            pendingSummaries.add(row);
        }
        // A switch flipped back owes nothing, so its row says what it said before.
        for (Preference row : new java.util.ArrayList<>(pendingSummaries)) {
            if (owedKeys.contains(row.getKey())) continue;
            pendingSummaries.remove(row);
            CharSequence summary = row == null ? null : row.getSummary();
            if (summary == null || !summary.toString().contains(pending)) continue;
            row.setSummary(summary.toString().replace(pending, generic));
        }
    }

    private static List<Preference> allRows(PreferenceGroup group) {
        List<Preference> rows = new ArrayList<>();
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference row = group.getPreference(index);
            rows.add(row);
            if (row instanceof PreferenceGroup) rows.addAll(allRows((PreferenceGroup) row));
        }
        return rows;
    }

    private void removeSearchRows() {
        for (Preference row : searchRows) {
            searchScreen.removePreference(row);
            reasonSummaries.remove(row);
            pendingSummaries.remove(row);
        }
        searchRows.clear();
    }

    private void updateSearchLongClicks() {
        View view = getView();
        ListView list = view == null ? null : view.findViewById(android.R.id.list);
        if (list != null) list.setLongClickable(searchScreen != null);
    }

    private static java.util.List<String> restartPendingKeys() {
        synchronized (AbstractPreferenceFragment.restartPending) {
            return new java.util.ArrayList<>(AbstractPreferenceFragment.restartPending);
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            SettingsUi.styleScrollableList(list);
            // Keep the inline editor focused when the list rebuilds its search results.
            list.setItemsCanFocus(true);
            list.setBackgroundColor(SettingsUi.background());
            list.setCacheColorHint(SettingsUi.background());
            list.setDivider(null);
            list.setDividerHeight(0);
            int side = 0;
            list.setPadding(SettingsUi.dp(getActivity(), side), 0,
                    SettingsUi.dp(getActivity(), side), SettingsUi.dp(getActivity(), 24));
            list.setClipToPadding(false);
            list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        // setStatusBarColor and setNavigationBarColor were here. Both do nothing from target 35,
        // which TikTok is well past, so the bars are painted by the root view's own background
        // reaching behind them instead. The icon flags below still work.
        view.setBackgroundColor(SettingsUi.background());

        View decor = window.getDecorView();
        int visibility = decor.getSystemUiVisibility();
        if (SettingsUi.isDarkMode()) {
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(visibility);
    }

    @SuppressWarnings("deprecation")
    @Override public android.animation.Animator onCreateAnimator(
            int transit, boolean enter, int nextAnim) {
        if ((transit == 0 && nextAnim == 0) || getActivity() == null) {
            return super.onCreateAnimator(transit, enter, nextAnim);
        }
        float dp24 = SettingsUi.dp(getActivity(), 24);
        boolean isRtl = getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
        float endEdge = isRtl ? -dp24 : dp24;
        boolean opening = transit == android.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN;
        float fromX, toX;
        float fromAlpha, toAlpha;
        if (opening && enter) {
            fromX = endEdge; toX = 0f; fromAlpha = 0f; toAlpha = 1f;
        } else if (opening) {
            fromX = 0f; toX = -endEdge * 0.3f; fromAlpha = 1f; toAlpha = 0f;
        } else if (enter) {
            fromX = -endEdge * 0.3f; toX = 0f; fromAlpha = 0f; toAlpha = 1f;
        } else {
            fromX = 0f; toX = endEdge; fromAlpha = 1f; toAlpha = 0f;
        }
        ObjectAnimator slide = ObjectAnimator.ofFloat(null, "translationX", fromX, toX);
        ObjectAnimator fade = ObjectAnimator.ofFloat(null, "alpha", fromAlpha, toAlpha);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(slide, fade);
        set.setDuration(220);
        set.setInterpolator(new android.view.animation.DecelerateInterpolator());
        return set;
    }

    @Override public void onSaveInstanceState(Bundle outState) {
        outState.putStringArrayList(EXPANDED_STATE,
                searchExpanded == null ? expandedGroups() : new ArrayList<>(searchExpanded));
        outState.putBoolean(SEARCH_ACTIVE_STATE, searchScreen != null);
        super.onSaveInstanceState(outState);
        rememberSearchPosition();
        outState.putInt(SEARCH_POSITION_STATE, searchPosition);
        outState.putInt(SEARCH_OFFSET_STATE, searchOffset);
        outState.putString(SEARCH_POSITION_QUERY_STATE, searchPositionQuery);
        ImportSeenVideoHistoryPreference.savePickerState(outState);
        SeenHistoryFilePreference.savePickerState(outState);
        outState.putString(PENDING_DOWNLOAD_PATH_STATE, pendingDownloadPathKey);
        if (searchInput != null) {
            String query = searchInput.getQuery();
            if (!query.isEmpty()) outState.putString(SEARCH_QUERY_STATE, query);
        }
    }

    @Override public void onActivityCreated(Bundle state) {
        super.onActivityCreated(state);
        ImportSeenVideoHistoryPreference.restorePickerState(state);
        SeenHistoryFilePreference.restorePickerState(state);
        if (pendingDownloadPathKey == null && state != null) {
            pendingDownloadPathKey = state.getString(PENDING_DOWNLOAD_PATH_STATE);
        }
        if (state != null && searchInput != null) {
            searchPosition = state.getInt(SEARCH_POSITION_STATE, -1);
            searchOffset = state.getInt(SEARCH_OFFSET_STATE);
            searchPositionQuery = state.getString(SEARCH_POSITION_QUERY_STATE);
            String savedQuery = state.getString(SEARCH_QUERY_STATE);
            if (savedQuery != null && !savedQuery.isEmpty()) {
                searchInput.setQuery(savedQuery);
                updateSearchResults(savedQuery);
            }
        }
        ListView list = getView().findViewById(android.R.id.list);
        if (list != null && list.getAdapter() != null) {
            styledAdapter = new SettingsListAdapter(list.getAdapter());
            list.setAdapter(styledAdapter);
            {
                list.setOnItemLongClickListener((parent, row, position, id) -> {
                    if (row == null || row.getParent() != parent
                            || parent.getPositionForView(row) != position) return false;
                    Object item = parent.getItemAtPosition(position);
                    return item instanceof SearchTogglePreference
                            && ((SearchTogglePreference) item).openLocation();
                });
                list.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host,
                            android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setLongClickable(false);
                        info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                    }
                });
                updateSearchLongClicks();
                restoreSearchPosition(list);
            }
            String targetKey = getTargetKey();
            String returnRow = getArguments() == null ? null : getArguments().getString(ARG_RETURN_ROW);
            boolean returning = arrived;
            arrived = true;
            if (!returning && targetKey == null && returnRow != null && state == null) {
                // Only on arrival: a recreated page keeps the scroll the list saved for it.
                scrollToPreference(list, returnRow, false);
                list.post(() -> scrollToPreference(list, returnRow, false));
            }
            if (!returning && targetKey != null && state == null) {
                scrollToPreference(list, targetKey, false);
                list.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    if (findPreference(targetKey) == null) {
                        SettingsActionBanner.showNotice(getActivity(), L10n.t(getActivity(),
                                "This setting isn't available. Search for another setting."));
                    } else scrollToPreference(list, targetKey);
                });
                // Only on arrival. A recreated page brings back the dialog it had open, with the
                // boxes as they were, and one closed before the recreation stays closed.
                String member = getArguments().getString(ARG_TARGET_MEMBER);
                Preference row = findPreference(targetKey);
                if (state == null && member != null && row instanceof SwitchListPreference) {
                    list.post(() -> {
                        if (isAdded()) ((SwitchListPreference) row).showChoicesAt(member);
                    });
                }
            }
        }
    }

    @Override public void onDestroyView() {
        // The search page raises the keyboard for its field, and popping the page left it up
        // over whatever page came next until Back was pressed once more. The system Back closes
        // it because the field still has focus; taking it down with the view covers the header's
        // Back and a tapped result alike, since both replace this view.
        rememberSearchPosition();
        hideKeyboard();
        if (styledAdapter != null) {
            styledAdapter.dispose();
            styledAdapter = null;
        }
        super.onDestroyView();
    }

    private void rememberSearchPosition() {
        View view = getView();
        ListView list = view == null ? null : view.findViewById(android.R.id.list);
        if (searchInput == null || list == null || list.getChildCount() == 0) return;
        searchPosition = list.getFirstVisiblePosition();
        searchOffset = list.getChildAt(0).getTop() - list.getPaddingTop();
        searchPositionQuery = searchInput.getQuery();
    }

    private void restoreSearchPosition(ListView list) {
        int position = searchPosition, offset = searchOffset;
        String query = searchPositionQuery;
        if (position < 0 || query == null) return;
        searchPosition = -1;
        list.post(() -> {
            if (getView() != null && getView().findViewById(android.R.id.list) == list
                    && searchInput != null && query.equals(searchInput.getQuery()) && list.getCount() > 0)
                list.setSelectionFromTop(Math.min(position, list.getCount() - 1), offset);
        });
    }

    private Section getRequestedSection() {
        Bundle arguments = getArguments();
        if (arguments == null) {
            return null;
        }
        String name = arguments.getString(ARG_SECTION);
        if (name == null) {
            return null;
        }
        try {
            return Section.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isSearchRequested() {
        return searchScreen != null;
    }

    private Hub getRequestedHub() {
        Bundle arguments = getArguments();
        String name = arguments == null ? null : arguments.getString(ARG_HUB);
        if (name == null) return null;
        try {
            return Hub.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Sections stay on this screen; home() accepts old section-page markers too. */
    String pageKey() {
        return isSearchRequested() ? PAGE_SEARCH : PAGE_HOME;
    }

    String returnMarker() {
        ArrayList<String> expanded = searchExpanded == null ? expandedGroups() : searchExpanded;
        String page = pageKey();
        if (!expanded.isEmpty()) page += ":" + android.text.TextUtils.join(",", expanded);
        String row = rowInView();
        return row == null ? page : page + ROW_MARK + row;
    }

    /** The first row whole on screen, or null while the page is at its top. */
    private String rowInView() {
        View view = getView();
        ListView list = view == null ? null : view.findViewById(android.R.id.list);
        if (list == null || list.getAdapter() == null || list.getFirstVisiblePosition() == 0) return null;
        int first = list.getFirstVisiblePosition();
        for (int index = 0; index < list.getChildCount(); index++) {
            Object item = list.getAdapter().getItem(first + index);
            if (list.getChildAt(index).getTop() < 0 || !(item instanceof Preference)) continue;
            String key = ((Preference) item).getKey();
            if (key != null && !key.isEmpty() && key.indexOf(ROW_MARK) < 0) return key;
        }
        return null;
    }

    /** Restores current inline markers and older section-page links on the main screen. */
    public static TikTokPreferenceFragment home(String marker) {
        TikTokPreferenceFragment home = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        if (marker != null) {
            int mark = marker.indexOf(ROW_MARK);
            String page = mark < 0 ? marker : marker.substring(0, mark);
            if (mark >= 0 && mark < marker.length() - 1)
                arguments.putString(ARG_RETURN_ROW, marker.substring(mark + 1));
            if (page.equals(PAGE_SEARCH) || page.startsWith(PAGE_SEARCH + ":"))
                arguments.putBoolean(ARG_SEARCH, true);
            if (page.startsWith(PAGE_SECTION)) {
                arguments.putString(ARG_SECTION, page.substring(PAGE_SECTION.length()));
            } else if (page.equals(PAGE_HUB + Hub.ABOUT.name())) {
                arguments.putString(ARG_HUB, Hub.ABOUT.name());
            } else if (page.startsWith(PAGE_HOME + ":") || page.startsWith(PAGE_SEARCH + ":")) {
                arguments.putStringArrayList(ARG_EXPANDED,
                        new ArrayList<>(java.util.Arrays.asList(
                                page.substring(page.indexOf(':') + 1).split(","))));
            }
        }
        if (!arguments.isEmpty()) home.setArguments(arguments);
        return home;
    }

    /** Legacy callers resolve a page marker to a configured main screen. */
    public static TikTokPreferenceFragment forPage(String page) {
        if (page == null || page.equals(PAGE_HOME) || page.startsWith(PAGE_HOME + "#")) return null;
        String name = page.split("#", 2)[0];
        if (name.equals(PAGE_SEARCH) || name.startsWith(PAGE_SEARCH + ":")
                || name.startsWith(PAGE_HOME + ":") || name.equals(PAGE_HUB + Hub.ABOUT.name()))
            return home(page);
        if (name.startsWith(PAGE_SECTION)) {
            try {
                Section.valueOf(name.substring(PAGE_SECTION.length()));
                return home(page);
            } catch (IllegalArgumentException gone) {
                return null;
            }
        }
        return null;
    }

    private String getTargetKey() {
        Bundle arguments = getArguments();
        if (arguments == null) {
            return null;
        }
        String key = arguments.getString(ARG_TARGET_KEY);
        return key == null || key.isEmpty() ? null : key;
    }

    private void createSearchMenu(Context context, PreferenceScreen screen) {
        if (searchScreen != null) return;
        searchExpanded = expandedGroups();
        for (SettingsMenuPreference menu : sectionMenus.values()) menu.setExpanded(false);
        if (aboutMenu != null) aboutMenu.setExpanded(false);
        searchScreen = screen;
        screen.removePreference(searchEntry);
        searchInput = new SettingsSearchInputPreference(context, this::updateSearchResults);
        searchInput.setKey("action_search_settings");
        searchInput.setOrder(-900);
        screen.addPreference(searchInput);
        searchIndex = buildSearchIndex(context, FeatureGateLabRuntime.isInstalled());
        updateSearchResults("");
        updateSearchLongClicks();
    }

    private void updateSearchResults(String query) {
        if (searchScreen == null) {
            return;
        }
        removeSearchRows();

        // Folding happens before the empty check: a query of nothing but accent marks is not
        // empty as typed but folds away to nothing, and every setting contains "".
        String typed = query == null ? "" : query.trim();
        shownSearch = typed;
        String normalizedQuery = normalizeSearchText(typed);
        if (normalizedQuery.isEmpty()) {
            if (typed.isEmpty()) {
                if (searchInput != null) searchInput.hideResultCount();
                if (!addRecentSearches()) {
                    addSearchState("Start typing", "Search by name, description or category.");
                }
            } else {
                // "???" folds to nothing as well. Start typing, under a box with text in it,
                // read as if the search had ignored it.
                if (searchInput != null) searchInput.showResultCount(0);
                addSearchState("No matching settings", NO_MATCHES_SUMMARY);
            }
            return;
        }

        // Whole-word hits first, title hits ahead of description hits: SearchMatch has the why.
        List<SearchResult> matches = app.morphe.extension.tiktok.settings.SearchMatch.rank(searchIndex, typed);
        if (searchInput != null) searchInput.showResultCount(matches.size());
        if (matches.isEmpty()) {
            addSearchState("No matching settings", NO_MATCHES_SUMMARY);
            return;
        }

        int order = -700;
        java.util.Set<String> flipped = new java.util.HashSet<>();
        for (SearchResult result : matches) {
            // A plain switch is flipped right here, the way it is on its page, and the page's
            // change handling does the rest: the restart row, availability, the saved value.
            // One row per setting, so two pages binding the same switch can't disagree.
            Setting<?> setting = result.flipsInPlace ? Setting.getSettingFromPath(result.key) : null;
            if (setting instanceof app.morphe.extension.shared.settings.BooleanSetting
                    && setting.isAvailable() && flipped.add(setting.key)) {
                SearchTogglePreference toggle = new SearchTogglePreference(getActivity(), result.title,
                        result.displaySummary(), (app.morphe.extension.shared.settings.BooleanSetting) setting, typed, () -> {
                            searchIndexStale = true;
                            openSection(result.section, result.key, null);
                        });
                toggle.setOrder(order++);
                searchRows.add(toggle);
                searchScreen.addPreference(toggle);
                continue;
            }
            Preference row = new Preference(getActivity());
            row.setTitle(result.title);
            row.setSummary(result.displaySummary());
            row.setOrder(order++);
            row.setOnPreferenceClickListener(preference -> {
                app.morphe.extension.tiktok.settings.SettingsSearchHistory.add(typed);
                // A result's page can change what the rows show, so they read again on the way back.
                // A dialog or a link can't, and a plain pause (the screen going off) leaves them be.
                searchIndexStale = !LicensesPreference.KEY.equals(result.key)
                        && !MorpheTikTokAboutPreference.KEY.equals(result.key);
                if (FEATURE_GATE_LAB_KEY.equals(result.key)) {
                    FeatureGateLabFragment.open(getActivity());
                } else if (LicensesPreference.KEY.equals(result.key)) {
                    LicensesPreference.show(getActivity());
                } else if (MorpheTikTokAboutPreference.KEY.equals(result.key)) {
                    Utils.openLink(MorpheTikTokAboutPreference.SOURCE_URL);
                } else if (BaseSettings.PAUSED.key.equals(result.key)) {
                    // The home page, scrolled to the row, the way a section opens for its rows.
                    openSection(null, result.key, null);
                } else if (BuildDetailsPreference.KEY.equals(result.key)
                        || ReleaseNotes.KEY.equals(result.key)) {
                    openHub(Hub.ABOUT, result.key);
                } else if (result.foundByChoiceOnly(typed)) {
                    openChoices(result.section, result.key);
                } else {
                    openSection(result.section, result.key, result.member);
                }
                return true;
            });
            searchRows.add(row);
            searchScreen.addPreference(row);
        }
    }

    /**
     * Recent searches under the empty box, with a row that forgets them all (#232, after
     * OmerKurdi79's version). False when there are none, for Start typing to show instead.
     */
    private boolean addRecentSearches() {
        List<String> recent = app.morphe.extension.tiktok.settings.SettingsSearchHistory.recent();
        if (recent.isEmpty()) return false;
        Context context = getActivity();
        int order = -700;
        Preference heading = new SectionHeadingPreference(context, "Recent searches");
        heading.setOrder(order++);
        searchRows.add(heading);
        searchScreen.addPreference(heading);
        for (String item : recent) {
            RecentSearchPreference row = new RecentSearchPreference(context, item,
                    picked -> {
                        // A bound box searches through its own text watcher; one not yet bound doesn't.
                        if (searchInput != null) searchInput.setQuery(picked);
                        if (!picked.equals(shownSearch)) updateSearchResults(picked);
                    },
                    forgotten -> {
                        app.morphe.extension.tiktok.settings.SettingsSearchHistory.remove(forgotten);
                        updateSearchResults("");
                    });
            row.setOrder(order++);
            searchRows.add(row);
            searchScreen.addPreference(row);
        }
        Preference clear = new Preference(context);
        clear.setTitle(L10n.t(context, "Clear search history"));
        clear.setOrder(order);
        clear.setOnPreferenceClickListener(preference -> {
            app.morphe.extension.tiktok.settings.SettingsSearchHistory.clear();
            updateSearchResults("");
            return true;
        });
        searchRows.add(clear);
        searchScreen.addPreference(clear);
        return true;
    }

    private void addSearchState(String title, String summary) {
        Preference state = new Preference(getActivity());
        state.setTitle(L10n.t(getActivity(), title));
        state.setSummary(L10n.t(getActivity(), summary));
        state.setSelectable(false);
        state.setOrder(-700);
        searchRows.add(state);
        searchScreen.addPreference(state);
    }

    /** Set by tests, which see Utils' empty release version and so never a pending What's new. */
    private static volatile String releaseVersionForTests;

    public static void setReleaseVersionForTests(String version) {
        releaseVersionForTests = version;
    }

    private static String releaseVersion() {
        String forTests = releaseVersionForTests;
        return forTests != null ? forTests : Utils.getPatchesReleaseVersion();
    }

    private List<SearchResult> buildSearchIndex(Context context, boolean featureGateLabInstalled) {
        List<SearchResult> results = new ArrayList<>();
        PreferenceScreen scratch = getPreferenceManager().createPreferenceScreen(context);
        for (Section section : Section.values()) {
            // Back up, Restore, Reset and Undo are added straight to their page rather than
            // through a category, so walking categories alone never saw them: searching
            // "backup" or "restore" answered "No matching settings" for four rows one page away.
            if (section == Section.BACKUP) {
                PreferenceScreen backupRows = getPreferenceManager().createPreferenceScreen(context);
                SettingsBackupPreference.addTo(this, backupRows);
                indexRows(results, backupRows, section, L10n.t(context, section.title));
                continue;
            }
            PreferenceCategory category = createCategory(context, scratch, section);
            if (category == null) {
                continue;
            }
            String categoryTitle = String.valueOf(category.getTitle());
            indexRows(results, category, section, categoryTitle);
            scratch.removePreference(category);
        }
        // The rows below sit on the home page and the About Hushfeed page, outside the section
        // categories walked above, so each is indexed by hand. Their category line names where
        // a reader finds them, the home heading or the page; it said "Settings" for all of
        // them, which is no page.
        String homeTitle = "Hushfeed";
        String hushfeed = L10n.t(context, "Hushfeed");
        String about = L10n.t(context, Hub.ABOUT.title);
        // Without an explicit entry, the Lab's title and summary returned zero results on a
        // patched phone even though the row was visible.
        if (featureGateLabInstalled) {
            results.add(new SearchResult(
                    null,
                    FEATURE_GATE_LAB_KEY,
                    L10n.t(context, "Feature Gate Lab"),
                    L10n.t(context, "Advanced. Find and force the hidden switches TikTok uses "
                            + "to test features."),
                    homeTitle,
                    "override gates"
            ));
        }
        // Pause Hushfeed is what a reader asking whether a problem is Hushfeed's searches for.
        results.add(new SearchResult(
                null,
                BaseSettings.PAUSED.key,
                L10n.t(context, "Pause Hushfeed"),
                L10n.t(context, PAUSE_SUMMARY),
                hushfeed
        ));
        // Its summary carries the bundle version, which is what a reporter searches for.
        results.add(new SearchResult(
                null,
                MorpheTikTokAboutPreference.KEY,
                "Hushfeed",
                MorpheTikTokAboutPreference.currentSummary(context).toString(),
                about
        ));
        // Searching "build" or "patches" found nothing, and the empty state then blamed patches
        // left unticked in Morphe Manager, while the row that lists them sat on About Hushfeed.
        results.add(new SearchResult(
                null,
                BuildDetailsPreference.KEY,
                L10n.t(context, "Build details"),
                L10n.t(context, BuildDetailsPreference.SUMMARY),
                about,
                "version"
        ));
        // What's new is on About Hushfeed only until its notes are read, and so is its result.
        String releaseVersion = releaseVersion();
        if (ReleaseNotes.pending(context, releaseVersion)) {
            results.add(new SearchResult(
                    null,
                    ReleaseNotes.KEY,
                    L10n.t(context, "What's new"),
                    L10n.f(context, "Changes in Hushfeed %1$s", ReleaseNotes.rowVersion(context, releaseVersion)),
                    about,
                    "release notes changelog"
            ));
        }
        // Somebody looking for "licence" or "notice" is looking for exactly one thing.
        results.add(new SearchResult(
                null,
                LicensesPreference.KEY,
                LicensesPreference.title(context),
                LicensesPreference.summary(context),
                about
        ));
        return results;
    }

    private void indexRows(List<SearchResult> results, PreferenceGroup group, Section section,
            String categoryTitle) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);
            if (!preference.hasKey() || !preference.isSelectable()) {
                continue;
            }
            // A row greyed until its parent switch is on is still found: its page shows it with
            // the switch to turn on first, where leaving it out sent the reader to an empty
            // result that blamed patches not ticked in Morphe Manager.
            CharSequence title = preference.getTitle();
            if (title == null || title.length() == 0) {
                continue;
            }
            CharSequence summary = preference.getSummary();
            // A choice row's summary names only the chosen option, so "save the frame" found
            // nothing while Double tap was set to anything else.
            StringBuilder choices = new StringBuilder();
            if (preference instanceof android.preference.ListPreference
                    && ((android.preference.ListPreference) preference).getEntries() != null) {
                for (CharSequence choice : ((android.preference.ListPreference) preference).getEntries())
                    choices.append(choice).append(' ');
            }
            SearchResult result = new SearchResult(
                    section,
                    preference.getKey(),
                    title.toString(),
                    summary == null ? "" : summary.toString(),
                    categoryTitle,
                    searchKeywords(preference.getContext(), preference.getKey()),
                    choices.toString().trim()
            );
            result.flipsInPlace = flipsInPlace(preference);
            results.add(result);
            // The row's summary names only the boxes that are ticked, so "counts" found nothing
            // while Counts under the buttons sat unticked inside it. Every box is a result of
            // its own, in the row's place, and opening one opens the row's dialog on that box.
            if (preference instanceof SwitchListPreference) {
                for (SwitchListPreference.Item item : ((SwitchListPreference) preference).items()) {
                    results.add(SearchResult.member(section, preference.getKey(), item.setting.key,
                            item.label, title.toString(), categoryTitle));
                }
            }
        }
    }

    /**
     * Whether a found row can be flipped in the results. Only a plain switch: one with a handler
     * of its own (a locked day's refusal, Ghost mode's status line, a background job to stop) or
     * a parent switch it waits on still opens its page, where that logic lives.
     */
    static boolean flipsInPlace(Preference preference) {
        return preference.getClass() == TogglePreference.class
                && preference.getOnPreferenceChangeListener() == null
                && preference.getDependency() == null
                && Setting.getSettingFromPath(preference.getKey())
                        instanceof app.morphe.extension.shared.settings.BooleanSetting;
    }

    /** Bug report templates ask for "debug logs", and "debug" found nothing: Log diagnostics never
     *  says the word. English stays searchable beside the translation, since that's the word
     *  the templates and guides use. */
    static final String LOG_DIAGNOSTICS_KEYWORDS = "debug debugging logs logging";

    /**
     * The words people use for a feature that its row never says, by setting key: "autoplay"
     * found nothing, and "picture in picture" missed Keep watching in a small window. Title hits
     * still rank first, so an alias can't push an exact title down. A setting moved to another
     * page gets its old page's name here, so the name people remember still finds it. Each
     * alias needs a row in every l10n table.
     */
    static final java.util.Map<String, String> SEARCH_ALIASES = java.util.Map.of(
            BaseSettings.DEBUG.key, LOG_DIAGNOSTICS_KEYWORDS,
            Settings.STOP_SEARCH_AUTOPLAY.key, "autoplay auto play",
            Settings.PICTURE_IN_PICTURE.key, "picture in picture PiP",
            Settings.HIDE_SEARCH_SUGGESTIONS.key, "search suggestions",
            Settings.HIDE_INBOX_TAKO.key, "AI chat",
            Settings.HIDE_CHAT_AI_REPLIES.key, "AI Tako",
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.key, "save photos");

    private static String searchKeywords(Context context, String key) {
        String aliases = SEARCH_ALIASES.get(key);
        if (aliases == null) return "";
        return aliases + " " + L10n.t(context, aliases);
    }

    private static String normalizeSearchText(String value) {
        return app.morphe.extension.tiktok.settings.SearchText.normalize(value);
    }

    private static PreferenceCategory createCategory(
            Context context,
            PreferenceScreen screen,
            Section section
    ) {
        switch (section) {
            case FEED_FILTER:
                return new FeedFilterPreferenceCategory(context, screen);
            case FEED_NAVIGATION:
                return new FeedNavigationPreferenceCategory(context, screen);
            case INTERFACE:
                return new InterfacePreferenceCategory(context, screen);
            case COMMENTS:
                return new CommentsPreferenceCategory(context, screen);
            case DOWNLOADS:
                return new DownloadsPreferenceCategory(context, screen);
            case PLAYBACK:
                return new PlaybackPreferenceCategory(context, screen);
            case SCREEN_TIME:
                return new ScreenTimePreferenceCategory(context, screen);
            case BACKUP:
                // Its four actions are added by expandSection.
                return null;
            case INBOX:
                return new InboxPreferenceCategory(context, screen);
            case SHARE:
                return new SharePreferenceCategory(context, screen);
            case REGION:
                return new SimSpoofPreferenceCategory(context, screen);
            case PRIVACY:
                return new PrivacyPreferenceCategory(context, screen);
            case DIAGNOSTICS:
                return new DebugPreferenceCategory(context, screen);
            case BEHAVIOR:
            default:
                return new ExtensionPreferenceCategory(context, screen);
        }
    }

    /** Scrolls this page to one of its rows and flashes it, as a search result arrives. */
    private void showRow(String key) {
        View view = getView();
        scrollToPreference(view == null ? null : view.findViewById(android.R.id.list), key);
    }

    private static void scrollToPreference(ListView list, String key) {
        scrollToPreference(list, key, true);
    }

    /** A search result's row is flashed; a restart's return row comes back as it was left. */
    private static void scrollToPreference(ListView list, String key, boolean highlight) {
        if (list == null || list.getAdapter() == null) {
            return;
        }
        int top = highlight ? SettingsUi.dp(list.getContext(), 24) : 0;
        for (int position = 0; position < list.getAdapter().getCount(); position++) {
            Object item = list.getAdapter().getItem(position);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                // Selection skips nonselectable rows outside touch mode, including search
                // and headings. Scrolling still reveals those rows for accessibility callers.
                list.setSelectionFromTop(position, top);
                if (!list.isInTouchMode() && !list.getAdapter().isEnabled(position)) {
                    int target = position;
                    list.post(() -> list.smoothScrollToPositionFromTop(target, top, 0));
                }
                if (highlight) highlightRow(list, position);
                return;
            }
        }
    }

    static void highlightRow(ListView list, int position) {
        if (list.getAdapter() == null || position < 0 || position >= list.getCount()) return;
        Object expected = list.getItemAtPosition(position), token = new Object();
        list.setTag(HIGHLIGHT_OWNER, token);
        long deadline = android.os.SystemClock.uptimeMillis() + 1000;
        android.view.ViewTreeObserver observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver current = list.getViewTreeObserver();
                boolean ownsTarget = list.getTag(HIGHLIGHT_OWNER) == token
                        && list.isAttachedToWindow() && list.getAdapter() != null
                        && position < list.getCount() && list.getItemAtPosition(position) == expected;
                int index = position - list.getFirstVisiblePosition();
                // A nonselectable destination can still be scrolling into view on this frame.
                if (ownsTarget && (index < 0 || index >= list.getChildCount())
                        && android.os.SystemClock.uptimeMillis() < deadline) return true;
                if (current.isAlive()) current.removeOnPreDrawListener(this);
                if (list.getTag(HIGHLIGHT_OWNER) != token) return true;
                list.setTag(HIGHLIGHT_OWNER, null);
                if (!ownsTarget) return true;
                if (index < 0 || index >= list.getChildCount()) return true;
                View row = list.getChildAt(index);
                if (row == null) return true;
                clearRowHighlight(row);
                float scale = android.provider.Settings.Global.getFloat(
                        list.getContext().getContentResolver(),
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
                if (scale > 0f) {
                    RowHighlight owned = new RowHighlight(row, expected);
                    row.setTag(HIGHLIGHT_OWNER, owned);
                    owned.flash.setBounds(0, 0, row.getWidth(), row.getHeight());
                    row.getOverlay().add(owned.flash);
                    row.postDelayed(() -> {
                        if (row.getTag(HIGHLIGHT_OWNER) != owned) return;
                        android.animation.ObjectAnimator fade = android.animation.ObjectAnimator
                                .ofInt(owned.flash, "alpha", 0x26, 0);
                        fade.setDuration(600);
                        fade.addListener(new android.animation.AnimatorListenerAdapter() {
                            @Override public void onAnimationEnd(android.animation.Animator a) {
                                owned.clear();
                            }
                        });
                        fade.start();
                    }, 100);
                }
                row.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                return true;
            }
        });
        list.invalidate();
    }

    static void clearRowHighlight(View row) {
        if (row != null && row.getTag(HIGHLIGHT_OWNER) instanceof RowHighlight)
            ((RowHighlight) row.getTag(HIGHLIGHT_OWNER)).clear();
    }

    static void clearRecycledHighlight(View row, Object preference) {
        if (row != null && row.getTag(HIGHLIGHT_OWNER) instanceof RowHighlight
                && ((RowHighlight) row.getTag(HIGHLIGHT_OWNER)).preference != preference)
            clearRowHighlight(row);
    }

    private static final class RowHighlight {
        final View row;
        final Object preference;
        final android.graphics.drawable.ColorDrawable flash =
                new android.graphics.drawable.ColorDrawable(SettingsUi.accent());
        RowHighlight(View row, Object preference) {
            this.row = row;
            this.preference = preference;
            flash.setAlpha(0x26);
        }
        void clear() {
            if (row.getTag(HIGHLIGHT_OWNER) != this) return;
            row.setTag(HIGHLIGHT_OWNER, null);
            row.getOverlay().remove(flash);
        }
    }

    /** Each category reveals its controls directly below its compact header. */
    private void createMasterMenu(Context context, PreferenceScreen screen) {
        masterHeader = SettingsHeaderPreference.master(context, this::navigateBack,
                () -> showRow(SettingsStatusPreference.KEY));
        screen.addPreference(masterHeader);
        searchEntry = new SettingsSearchEntryPreference(context, preference -> {
            openSearch();
            return true;
        });
        searchEntry.setKey("action_search_settings");
        searchEntry.setOrder(-900);
        screen.addPreference(searchEntry);

        for (Section section : new Section[]{Section.FEED_FILTER, Section.FEED_NAVIGATION,
                Section.INTERFACE, Section.PLAYBACK, Section.SCREEN_TIME, Section.COMMENTS,
                Section.INBOX, Section.DOWNLOADS, Section.SHARE, Section.PRIVACY, Section.BEHAVIOR,
                Section.REGION, Section.BACKUP}) {
            if (section.isAvailable()) addMenu(screen, section);
        }
        if (FeatureGateLabRuntime.isInstalled()) addFeatureGateLab(screen);

        screen.addPreference(SectionHeadingPreference.compact(context, "Hushfeed"));
        // Detailed state and recovery, with Turn Hushfeed back on while paused.
        SettingsStatusPreference status = new SettingsStatusPreference(context,
                Section.DIAGNOSTICS.isAvailable() ? () -> openSection(Section.DIAGNOSTICS) : null);
        status.setTurnBackOnAction(this::turnHushfeedBackOn);
        screen.addPreference(status);
        screen.addPreference(pauseRow(context));
        if (Section.DIAGNOSTICS.isAvailable()) addMenu(screen, Section.DIAGNOSTICS);
        addAbout(screen);
        addReportProblem(screen);
        addSupport(screen);
        // Search results occupy the space between the field and these category rows.
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (row != masterHeader && row != searchEntry) row.setOrder(10000 + index);
        }
    }

    /** Report a problem, just above the Ko-fi row: a GitHub bug report with the facts filled in. */
    private void addReportProblem(PreferenceScreen screen) {
        SettingsMenuPreference row = new SettingsMenuPreference(getActivity(), "Report a problem",
                "Opens a GitHub report with your versions and settings filled in. Add what happened.",
                SettingsMenuPreference.Icon.REPORT, preference -> {
                    reportProblem();
                    return true;
                });
        row.setKey(REPORT_PROBLEM_KEY);
        row.setCompact(true);
        screen.addPreference(row);
    }

    /** The last row of the home page: the Ko-fi page, under its title alone. */
    private void addSupport(PreferenceScreen screen) {
        SettingsMenuPreference row = new SettingsMenuPreference(getActivity(), "Support Hushfeed",
                "", SettingsMenuPreference.Icon.SUPPORT, preference -> {
                    openWebPage(getActivity(), SUPPORT_URL);
                    return true;
                });
        row.setKey(SUPPORT_KEY);
        row.setCompact(true);
        screen.addPreference(row);
    }

    private void reportProblem() {
        Activity activity = getActivity();
        if (activity == null) return;
        ProblemReport.Facts facts = ProblemReport.collect(activity, switchesOnInEnglish(activity));
        openInBrowser(activity, ProblemReport.url(facts));
    }

    /** A web page in the reader's browser, in a task of its own so it never opens inside TikTok's. */
    static Intent webPageIntent(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void openWebPage(Context context, String url) {
        if (context == null) return;
        try {
            context.startActivity(webPageIntent(url));
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception would close TikTok.
            SettingsActionBanner.showNotice(context, L10n.t(context, "No app on this phone opens web links"));
        }
    }

    /**
     * What Android itself asks to find a browser: a web link with no address on it, which only
     * an app that opens every link answers. An app that claims one site's links doesn't.
     */
    static Intent anyWebPageIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.fromParts("https", "", null))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }

    /**
     * A web page in a browser even when an app claims its address. GitHub's app takes a new-issue
     * link into its own composer, which keeps the title and drops every field the bug form is
     * filled through (S25, 2026-10-13), so the report names the browser it goes to: the default
     * one, or the first that answers when none is the default. With no browser found the link is
     * left to open the way any other does.
     */
    static Intent browserPageIntent(Context context, String url) {
        Intent page = webPageIntent(url);
        String browser = browserPackage(context);
        if (browser != null) page.setPackage(browser);
        return page;
    }

    static String browserPackage(Context context) {
        try {
            PackageManager packages = context.getPackageManager();
            Intent anyPage = anyWebPageIntent();
            String chosen = browserNamed(packages.resolveActivity(anyPage, PackageManager.MATCH_DEFAULT_ONLY));
            if (chosen != null) return chosen;
            for (ResolveInfo candidate : packages.queryIntentActivities(anyPage, PackageManager.MATCH_DEFAULT_ONLY)) {
                String name = browserNamed(candidate);
                if (name != null) return name;
            }
        } catch (RuntimeException unreadable) {
            // The plain link still opens somewhere.
        }
        return null;
    }

    /** The package that answered, unless it's Android's own chooser standing in for several. */
    private static String browserNamed(ResolveInfo answer) {
        if (answer == null || answer.activityInfo == null) return null;
        String name = answer.activityInfo.packageName;
        return name == null || "android".equals(name) ? null : name;
    }

    static void openInBrowser(Context context, String url) {
        if (context == null) return;
        try {
            context.startActivity(browserPageIntent(context, url));
        } catch (ActivityNotFoundException | SecurityException refused) {
            // The browser that answered a moment ago is gone or switched off.
            openWebPage(context, url);
        }
    }

    /**
     * The title of every switch that's on, page by page, for a problem report. Read in English,
     * which is what the tracker is written in, whatever language the phone shows.
     */
    private List<String> switchesOnInEnglish(Context context) {
        Context english = context;
        try {
            android.content.res.Configuration configuration =
                    new android.content.res.Configuration(context.getResources().getConfiguration());
            configuration.setLocale(java.util.Locale.ENGLISH);
            english = context.createConfigurationContext(configuration);
        } catch (RuntimeException unavailable) {
            // The phone's own language then: a report in German still says which switches are on.
        }
        List<String> titles = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        PreferenceScreen scratch = getPreferenceManager().createPreferenceScreen(context);
        for (Section section : Section.values()) {
            try {
                PreferenceCategory category = createCategory(english, scratch, section);
                if (category == null) continue;
                collectSwitchesOn(category, titles, seen);
                scratch.removePreference(category);
            } catch (RuntimeException unreadable) {
                app.morphe.extension.shared.Logger.printException(
                        () -> "Couldn't read the switches on " + section + " for the report", unreadable);
            }
        }
        return titles;
    }

    private static void collectSwitchesOn(PreferenceGroup group, List<String> titles,
            java.util.Set<String> seen) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);
            if (preference instanceof PreferenceGroup) {
                collectSwitchesOn((PreferenceGroup) preference, titles, seen);
                continue;
            }
            if (preference instanceof SwitchListPreference) {
                for (SwitchListPreference.Item item : ((SwitchListPreference) preference).items()) {
                    if (item.setting.savedValue() && seen.add(item.setting.key)) {
                        titles.add(preference.getTitle() + ": " + item.label);
                    }
                }
                continue;
            }
            if (!(preference instanceof android.preference.SwitchPreference) || !preference.hasKey()) continue;
            Setting<?> setting = Setting.getSettingFromPath(preference.getKey());
            if (setting instanceof app.morphe.extension.shared.settings.BooleanSetting
                    && Boolean.TRUE.equals(setting.savedValue()) && seen.add(setting.key)) {
                titles.add(String.valueOf(preference.getTitle()));
            }
        }
    }

    private void addAbout(PreferenceScreen screen) {
        SettingsMenuPreference row = new SettingsMenuPreference(getActivity(), Hub.ABOUT.title,
                "", SettingsMenuPreference.Icon.ABOUT, preference -> {
                    boolean wasExpanded = aboutMenu.isExpanded();
                    closeSearch();
                    if (wasExpanded) aboutMenu.setExpanded(false);
                    else expandAbout();
                    return true;
                });
        row.setKey("hub_" + Hub.ABOUT.name().toLowerCase(java.util.Locale.ROOT));
        row.setCompact(true);
        row.setExpandable(true);
        aboutMenu = row;
        screen.addPreference(row);
    }

    private void createAboutMenu(Context context, PreferenceGroup screen) {
        screen.addPreference(new BuildDetailsPreference(context));
        addReleaseNotes(context, screen);
        screen.addPreference(new MorpheTikTokAboutPreference(context));
        screen.addPreference(new LicensesPreference(context));
    }

    private void addFeatureGateLab(PreferenceScreen screen) {
        SettingsMenuPreference lab = new SettingsMenuPreference(getActivity(), "Feature Gate Lab",
                "Advanced. Find and force the hidden switches TikTok uses to test features.",
                SettingsMenuPreference.Icon.LAB, preference -> {
                    FeatureGateLabFragment.open(getActivity());
                    return true;
                });
        lab.setKey(FEATURE_GATE_LAB_KEY);
        lab.setCompact(true);
        screen.addPreference(lab);
    }

    private void addReleaseNotes(Context context, PreferenceGroup screen) {
        String releaseVersion = releaseVersion();
        if (!ReleaseNotes.pending(context, releaseVersion)) return;
        SettingsMenuPreference notes = new SettingsMenuPreference(context, "What's new",
                L10n.f(context, "Changes in Hushfeed %1$s", ReleaseNotes.rowVersion(context, releaseVersion)),
                SettingsMenuPreference.Icon.NEWS, preference -> {
                    ReleaseNotes.show(context, releaseVersion, () -> screen.removePreference(preference));
                    return true;
                });
        notes.setKey(ReleaseNotes.KEY);
        screen.addPreference(notes);
    }

    /**
     * Pause Hushfeed. It takes the screen-time budget off with everything else, so a locked
     * day refuses it the same way it refuses every budget setting, and so does Wait a day to
     * loosen the budget, the way it refuses Start today over: paused from the next start, the
     * budget would be gone without waiting for anything.
     */
    private TogglePreference pauseRow(Context context) {
        TogglePreference pause = new TogglePreference(context, "Pause Hushfeed", PAUSE_SUMMARY,
                BaseSettings.PAUSED);
        pause.setOnPreferenceChangeListener((preference, value) -> {
            if (!Boolean.TRUE.equals(value)) return true;
            if (SessionBudget.lockedToday()) {
                SettingsActionBanner.showNotice(context, L10n.f(context,
                        "Today's budget is locked. This can be changed again at %1$s.",
                        SessionLockOverlay.resetTimeLabel()));
                return false;
            }
            if (Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.savedValue()) {
                SettingsActionBanner.showNotice(context, L10n.t(context,
                        "Wait a day to loosen the budget is on, so Hushfeed stays on"));
                return false;
            }
            return true;
        });
        return pause;
    }

    /** The paused status card's action: safe mode, the switch and the marker file all go. */
    private void turnHushfeedBackOn() {
        Context context = getActivity();
        if (context == null) return;
        boolean pausedBefore = BaseSettings.PAUSED.savedValue();
        boolean safeModeBefore = BaseSettings.SAFE_MODE.savedValue();
        // What this process runs with goes on record before the save. The store's listener
        // reaches this page as soon as the save lands and reads the value "before" the change
        // from the setting, which by then already holds the new one, so it would record the
        // switch as never having changed and the restart it owes would go unsaid.
        noteRestartPending(BaseSettings.PAUSED, pausedBefore);
        noteRestartPending(BaseSettings.SAFE_MODE, safeModeBefore);
        boolean markerGone = HushfeedPause.turnBackOn(context);
        Preference row = findPreference(BaseSettings.PAUSED.key);
        if (row instanceof TogglePreference) ((TogglePreference) row).setChecked(false);
        noteRestartPending(BaseSettings.PAUSED, pausedBefore);
        noteRestartPending(BaseSettings.SAFE_MODE, safeModeBefore);
        if (markerGone && HushfeedPause.reason() == HushfeedPause.Reason.MARKER_FILE) {
            // The file is no setting, but the start it paused owes a restart all the same.
            AbstractPreferenceFragment.restartPending.add(HushfeedPause.MARKER_FILE_NAME);
            refreshRestartPending();
        }
        refreshStatusCard();
        if (markerGone) {
            SettingsActionBanner.showRestart(context,
                    L10n.t(context, "Hushfeed turns back on when TikTok restarts."));
        } else {
            SettingsActionBanner.showNotice(context, L10n.t(context,
                    "The file hushfeed-safe-mode couldn't be removed. Delete it from TikTok's folder under Android/data to turn Hushfeed back on."));
        }
    }

    /** The status card says again whether the next start still runs paused. */
    private void refreshStatusCard() {
        Preference status = findPreference(SettingsStatusPreference.KEY);
        if (status instanceof SettingsStatusPreference) ((SettingsStatusPreference) status).refresh();
        if (masterHeader != null) masterHeader.refreshStatus();
    }

    /** A page's row on the home page, in the compact layout. */
    private void addMenu(PreferenceScreen screen, Section section) {
        String description = L10n.t(getActivity(), section == Section.SCREEN_TIME
                ? "Budgets and reminders" : section.description);
        if (description.endsWith(".")) {
            description = description.substring(0, description.length() - 1);
        }
        SettingsMenuPreference row = new SettingsMenuPreference(
                getActivity(),
                section.title,
                description,
                section.icon,
                preference -> {
                    SettingsMenuPreference menu = sectionMenus.get(section);
                    boolean wasExpanded = menu.isExpanded();
                    closeSearch();
                    if (wasExpanded) menu.setExpanded(false);
                    else {
                        expandSection(section);
                        updateUIToSettingValues();
                        refreshRestartPending();
                    }
                    return true;
                }
        );
        row.setKey("section_" + section.name().toLowerCase(java.util.Locale.ROOT));
        row.setCompact(true);
        row.setExpandable(true);
        sectionMenus.put(section, row);
        screen.addPreference(row);
    }

    private void expandSection(Section section) {
        SettingsMenuPreference menu = sectionMenus.get(section);
        if (menu == null) return;
        if (menu.getPreferenceCount() == 0) {
            PreferenceScreen scratch = getPreferenceManager().createPreferenceScreen(getActivity());
            PreferenceCategory category = createCategory(getActivity(), scratch, section);
            if (category != null) {
                while (category.getPreferenceCount() > 0) {
                    Preference child = category.getPreference(0);
                    category.removePreference(child);
                    child.setOrder(menu.getPreferenceCount());
                    menu.addPreference(child);
                }
                scratch.removePreference(category);
            }
            if (section == Section.BACKUP) {
                menu.addPreference(new SectionHeadingPreference(getActivity(), "Your settings"));
                SettingsBackupPreference.addTo(this, menu);
            }
        }
        menu.setExpanded(true);
    }

    private void expandAbout() {
        if (aboutMenu == null) return;
        if (aboutMenu.getPreferenceCount() == 0) createAboutMenu(getActivity(), aboutMenu);
        aboutMenu.setExpanded(true);
    }

    private ArrayList<String> expandedGroups() {
        ArrayList<String> result = new ArrayList<>();
        for (Section section : Section.values()) {
            SettingsMenuPreference menu = sectionMenus.get(section);
            if (menu != null && menu.isExpanded()) result.add(section.name());
        }
        if (aboutMenu != null && aboutMenu.isExpanded()) result.add(Hub.ABOUT.name());
        return result;
    }

    private void restoreExpanded(List<String> groups) {
        if (groups == null) return;
        for (String name : groups) {
            if (Hub.ABOUT.name().equals(name)) expandAbout();
            else {
                try { expandSection(Section.valueOf(name)); }
                catch (IllegalArgumentException gone) { /* Removed sections stay absent. */ }
            }
        }
    }

    private void expandOwner(String key) {
        if (BuildDetailsPreference.KEY.equals(key) || ReleaseNotes.KEY.equals(key)
                || MorpheTikTokAboutPreference.KEY.equals(key) || LicensesPreference.KEY.equals(key)) {
            expandAbout();
            return;
        }
        if (findPreference(key) != null) return;
        for (SearchResult result : buildSearchIndex(getActivity(), FeatureGateLabRuntime.isInstalled())) {
            if (key.equals(result.key) || key.equals(result.member)) {
                expandSection(result.section);
                return;
            }
        }
    }

    void refreshBackupSettings() { updateUIToSettingValues(); }

    /**
     * The settings page in front now, or null. After a rotation or a return from the file picker
     * it is not the page a file read or write started on, and that page is gone.
     */
    static TikTokPreferenceFragment active() {
        TikTokPreferenceFragment current = activeFragment;
        return current != null && current.isAdded() ? current : null;
    }

    /**
     * Where a file read or write reports its outcome: the page open now, else the window it
     * started in, else the fallback, which the banner turns into a toast.
     */
    static Context reportWindow(java.lang.ref.WeakReference<Activity> started, Context fallback) {
        TikTokPreferenceFragment open = active();
        Activity activity = open != null ? open.getActivity() : null;
        if (activity == null) activity = started.get();
        return activity != null ? activity : fallback;
    }

    /** Reconciles a preset's batch write with the controls and restart debt on this screen. */
    static void onSettingsBatchChanged(java.util.Map<Setting<?>, Object> previousValues) {
        app.morphe.extension.tiktok.feed.FeedTextSize.onSettingChanged();
        app.morphe.extension.tiktok.comment.CommenterDetails.onSettingChanged();
        TikTokPreferenceFragment current = activeFragment;
        if (current == null || !current.isAdded()) return;
        for (java.util.Map.Entry<Setting<?>, Object> entry : previousValues.entrySet()) {
            Setting<?> setting = entry.getKey();
            if (setting.rebootApp && !java.util.Objects.equals(entry.getValue(), setting.savedValue())) {
                current.noteRestartPending(setting, entry.getValue());
            }
        }
        current.updateUIToSettingValues();
        current.refreshRestartPending();
    }

    private void openSearch() {
        if (searchScreen != null) return;
        createSearchMenu(getActivity(), getPreferenceScreen());
        View view = getView();
        ListView list = view == null ? null : view.findViewById(android.R.id.list);
        if (list != null) list.post(() -> scrollToPreference(list, "action_search_settings", false));
    }

    private boolean closeSearch() {
        if (searchScreen == null) return false;
        hideKeyboard();
        removeSearchRows();
        searchScreen.removePreference(searchInput);
        searchScreen.addPreference(searchEntry);
        searchScreen = null;
        updateSearchLongClicks();
        searchInput = null;
        searchIndex = null;
        shownSearch = "";
        restoreExpanded(searchExpanded);
        searchExpanded = null;
        updateUIToSettingValues();
        refreshRestartPending();
        return true;
    }

    private void openSection(Section section) { openSection(section, null, null); }

    /** A row found by a choice it isn't set to opens with that list showing, so the match is visible. */
    private void openChoices(Section section, String key) {
        openSection(section, key, null);
        View view = getView();
        if (view == null) return;
        view.post(() -> {
            if (!isAdded() || getView() == null) return;
            Preference row = findPreference(key);
            if (row instanceof ChoicePreference) ((ChoicePreference) row).openChoices();
        });
    }

    private void openHub(Hub hub, String targetKey) {
        closeSearch();
        expandAbout();
        revealInline(targetKey == null ? aboutMenu.getKey() : targetKey, null);
    }

    private void openSection(Section section, String targetKey, String targetMember) {
        closeSearch();
        expandSection(section);
        updateUIToSettingValues();
        refreshRestartPending();
        SettingsMenuPreference menu = sectionMenus.get(section);
        revealInline(targetKey == null && menu != null ? menu.getKey() : targetKey, targetMember);
    }

    private void revealInline(String key, String member) {
        if (key == null) return;
        View view = getView();
        ListView list = view == null ? null : view.findViewById(android.R.id.list);
        if (list == null) return;
        list.post(() -> {
            if (!isAdded() || getView() == null) return;
            scrollToPreference(list, key);
            scrollUntilLaidOut(list, key);
            Preference row = findPreference(key);
            if (row == null) {
                SettingsActionBanner.showNotice(getActivity(), L10n.t(getActivity(),
                        "This setting isn't available. Search for another setting."));
                return;
            }
            if (member != null && row instanceof SwitchListPreference)
                ((SwitchListPreference) row).showChoicesAt(member);
        });
    }

    /**
     * A ListView scroll waits for the next layout, and a data change or a resize before then
     * (the rows of the opened page arriving, the keyboard closing) makes the list keep the rows
     * it was showing instead. A row deep in Feed screen opened at the page's top that way. Scroll
     * again after each layout until the row has been laid out on screen; later changes keep it.
     */
    private static void scrollUntilLaidOut(ListView list, String key) {
        long deadline = android.os.SystemClock.uptimeMillis() + 1000;
        android.view.ViewTreeObserver.OnGlobalLayoutListener listener =
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                int position = -1;
                if (list.isAttachedToWindow() && list.getAdapter() != null)
                    for (int i = 0; i < list.getCount(); i++) {
                        Object item = list.getItemAtPosition(i);
                        if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                            position = i;
                            break;
                        }
                    }
                boolean shown = position >= list.getFirstVisiblePosition()
                        && position <= list.getLastVisiblePosition();
                if (shown || !list.isAttachedToWindow()
                        || android.os.SystemClock.uptimeMillis() > deadline) {
                    android.view.ViewTreeObserver observer = list.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    return;
                }
                // Before the page's rows reach the adapter there is nothing to scroll to yet.
                if (position >= 0) scrollToPreference(list, key, false);
            }
        };
        list.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    /** Search consumes Back; expanded categories never create a navigation history. */
    public boolean closeInlineSearch() { return closeSearch(); }

    private void navigateBack() {
        if (!closeSearch()) closeSettings();
    }

    private void hideKeyboard() {
        View view = getView();
        if (view == null) return;
        InputMethodManager manager = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void closeSettings() {
        Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }

    /** Redraws the Screen time rows as this screen comes to the front and once a minute after. */
    private final android.os.Handler budgetTicks = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable budgetTick = new Runnable() {
        @Override public void run() {
            app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory.redrawOpenPages();
            budgetTicks.postDelayed(this, 60_000L);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        activeFragment = this;
        refreshStatusCard();
        // Back from a section onto the master menu: whatever was changed there is owed here.
        refreshRestartPending();
        // Back from a result's page onto the search: the index copied each row's summary when it
        // was built, so a list, number or text setting changed there still read its old value
        // (Caption background said Dark after it was set to TikTok default, S25 2026-10-18).
        if (searchIndexStale && searchScreen != null) {
            searchIndexStale = false;
            searchIndex = buildSearchIndex(getActivity(), FeatureGateLabRuntime.isInstalled());
            updateSearchResults(searchInput == null ? "" : searchInput.getQuery());
        }
        // A phone asleep over the day's start pauses this screen, so coming back is when the
        // Screen time rows are most likely to be out of date.
        budgetTicks.removeCallbacks(budgetTick);
        budgetTick.run();
    }

    @Override
    public void onPause() {
        budgetTicks.removeCallbacks(budgetTick);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        budgetTicks.removeCallbacks(budgetTick);
        if (activeFragment == this) {
            activeFragment = null;
            // The pending key deliberately survives: the picker destroys this fragment while it
            // is open, and clearing it here dropped the folder the reader had just chosen.
        }
        super.onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (ImportSeenVideoHistoryPreference.onResult(this, requestCode, resultCode, data)) return;
        if (SeenHistoryFilePreference.onResult(this, requestCode, resultCode, data)) return;
        if (SettingsBackupPreference.onResult(this, requestCode, resultCode, data)) return;
        if (ImportedFontPreference.onResult(this, requestCode, resultCode, data)) return;
        if (requestCode != REQUEST_DOWNLOAD_PATH_FOLDER) {
            return;
        }

        String pendingKey = pendingDownloadPathKey;
        pendingDownloadPathKey = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Preference found = pendingKey == null ? null : findPreference(pendingKey);
        if (!(found instanceof DownloadPathPreference)) {
            app.morphe.extension.shared.Utils.showToastLong(
                    L10n.t("Couldn't tell which folder to update. Choose it again."));
            return;
        }
        DownloadPathPreference preference = (DownloadPathPreference) found;

        String relativePath = getRelativePrimaryStoragePath(data.getData());
        if (relativePath == null) {
            preference.applyPickedPath(null);
            return;
        }

        preference.applyPickedPath(relativePath);
    }

    private static String getRelativePrimaryStoragePath(Uri uri) {
        try {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(uri);
            if (treeDocumentId == null) {
                return null;
            }

            String prefix = "primary:";
            if (!treeDocumentId.startsWith(prefix)) {
                return null;
            }

            return treeDocumentId.substring(prefix.length());
        } catch (Exception ignored) {
            return null;
        }
    }
}
