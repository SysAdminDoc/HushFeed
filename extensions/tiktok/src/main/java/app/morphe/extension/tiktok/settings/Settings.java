/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/Settings.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/Settings.java
 */

package app.morphe.extension.tiktok.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;

import app.morphe.extension.tiktok.offline.CustomOfflineVideosLimitPatch;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;
import app.morphe.extension.tiktok.interaction.GestureActions;
import app.morphe.extension.tiktok.navigation.BottomNavigationTabOptions;
import app.morphe.extension.tiktok.navigation.NavigationTabOptions;

import java.util.Collections;

public class Settings extends BaseSettings {
    /** Backstop for direct writes and both settings-import formats. */
    private static final class FeedRuleStringSetting extends StringSetting {
        private final boolean creatorList;

        FeedRuleStringSetting(String key, boolean creatorList) {
            super(key, "");
            this.creatorList = creatorList;
        }

        @Override
        protected String coerce(String newValue) {
            return creatorList ? FeedRuleLimits.requireCreators(newValue)
                    : FeedRuleLimits.requireCaption(newValue);
        }
    }

    public static final BooleanSetting REGION_SPOOF = new BooleanSetting(
            "region_spoof",
            FALSE,
            true,
            // Not Setting.parent(SIM_SPOOF): that switch is declared further down this file and
            // a static initializer cannot read it yet. Naming it inside the methods is the same
            // dependency, read when it is asked for rather than when this line runs. The order
            // of the declarations is what a settings backup writes its keys in, so it stays.
            new Setting.Availability() {
                @Override public boolean isAvailable() {
                    return SIM_SPOOF.savedValue();
                }

                @Override public java.util.List<Setting<?>> getParentSettings() {
                    return java.util.Collections.singletonList(SIM_SPOOF);
                }
            }
    );
    public static final BooleanSetting REGION_STORE_SPOOF = new BooleanSetting(
            "region_store_spoof",
            FALSE,
            true,
            // Both switches, and the row names the one to turn on: Match locale while it is off,
            // which is itself greyed until Override SIM details is on, so a reader is never sent
            // two steps back; Override SIM details when Match locale is on and SIM is the one
            // off. Naming Match locale then sent the reader to a switch that was already on.
            new Setting.Availability() {
                @Override public boolean isAvailable() {
                    return SIM_SPOOF.savedValue() && REGION_SPOOF.savedValue();
                }

                @Override public java.util.List<Setting<?>> getParentSettings() {
                    return java.util.Collections.singletonList(REGION_SPOOF.savedValue() ? SIM_SPOOF : REGION_SPOOF);
                }
            }
    );
    // The same two switches as the store row, and the row names the one to turn on the same way.
    public static final BooleanSetting REGION_REQUEST_SPOOF = new BooleanSetting(
            "region_request_spoof",
            FALSE,
            true,
            new Setting.Availability() {
                @Override public boolean isAvailable() {
                    return SIM_SPOOF.savedValue() && REGION_SPOOF.savedValue();
                }

                @Override public java.util.List<Setting<?>> getParentSettings() {
                    return java.util.Collections.singletonList(REGION_SPOOF.savedValue() ? SIM_SPOOF : REGION_SPOOF);
                }
            }
    );
    public static final BooleanSetting FOLDABLE_SPLIT_VIEW = new BooleanSetting("foldable_split_view", FALSE, true);
    public static final IntegerSetting FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP = new IntegerSetting("foldable_split_view_min_width_dp", 600, true).withRange(320, 1600);
    public static final BooleanSetting DOWNLOAD_SUBTITLES = new BooleanSetting("download_subtitles", FALSE);
    // Each value below is read only while the switch above it is on, so its row greys with it
    // and says which switch to turn on, instead of taking a choice that does nothing.
    public static final StringSetting SUBTITLE_LANGUAGE = new StringSetting(
            "subtitle_language", "original", false, Setting.parent(DOWNLOAD_SUBTITLES));
    public static final IntegerSetting CAPTION_TEXT_SIZE =
            new IntegerSetting("caption_text_size", 0).withRange(0, 48);
    // How far a spoken caption is lifted above TikTok's spot, in dp, with the phone upright and
    // turned sideways (#206, from OmerKurdi79's branch, whose keys these are).
    public static final IntegerSetting CAPTION_BOTTOM_OFFSET =
            new IntegerSetting("caption_bottom_offset", 0).withRange(0, 300);
    public static final IntegerSetting CAPTION_LANDSCAPE_OFFSET =
            new IntegerSetting("caption_landscape_offset", 0).withRange(0, 300);
    /** "default" for TikTok's own weight, or a font weight from 300 to 800. */
    public static final StringSetting CAPTION_FONT_WEIGHT = new StringSetting("caption_font_weight", "default");
    /**
     * The Normal-weight captions switch, which Caption boldness replaced. No row reads it now: it
     * stays so a backup from before the picker still restores, and the static block below and
     * SettingsBackup turn it into the picker's 400.
     */
    public static final BooleanSetting CAPTION_REGULAR_WEIGHT = new BooleanSetting("caption_regular_weight", FALSE);
    public static final StringSetting CAPTION_BACKGROUND = new StringSetting("caption_background", "default");
    public static final BooleanSetting KEEP_CAPTIONS_CLEAR_DISPLAY = new BooleanSetting("keep_captions_clear_display", FALSE);
    public static final IntegerSetting CAPTION_CLEAR_DISPLAY_OFFSET = new IntegerSetting(
            "caption_clear_display_offset", 0, Setting.parent(KEEP_CAPTIONS_CLEAR_DISPLAY)).withRange(0, 300);
    /** The Accessibility page's Captions switch and the caption controls, where TikTok leaves them off (CaptionsAvailable). */
    public static final BooleanSetting KEEP_CAPTIONS_AVAILABLE = new BooleanSetting("keep_captions_available", FALSE, true);
    public static final BooleanSetting ALLOW_SCREEN_CAPTURE = new BooleanSetting("allow_screen_capture", FALSE, true);
    public static final BooleanSetting SYSTEM_FONT = new BooleanSetting("system_font", FALSE, true);
    public static final BooleanSetting SYSTEM_EMOJI = new BooleanSetting("system_emoji", FALSE, true);
    // Use system font's hook draws TikTok's text in the imported font file while this is on and
    // the file loads; it wins over SYSTEM_FONT. The file itself lives in TikTok's files, not here.
    public static final BooleanSetting IMPORTED_FONT = new BooleanSetting("imported_font", FALSE, true);
    // Off by default: both patches are in the default selection, so the switch is the ask.
    public static final BooleanSetting TURN_OFF_HAPTICS = new BooleanSetting("turn_off_haptics", FALSE);
    public static final BooleanSetting TURN_OFF_SCREEN_TRANSITIONS =
            new BooleanSetting("turn_off_screen_transitions", FALSE);
    public static final BooleanSetting AUTOMATIC_CLEAR_DISPLAY = new BooleanSetting("automatic_clear_display", FALSE);
    public static final IntegerSetting AUTOMATIC_CLEAR_DISPLAY_DELAY =
            new IntegerSetting("automatic_clear_display_delay", 1000, false,
                    Setting.parent(AUTOMATIC_CLEAR_DISPLAY)).withRange(0, 30000);
    public static final StringSetting PLAYBACK_QUALITY = new StringSetting("playback_quality", "auto");
    public static final StringSetting PLAYBACK_QUALITY_METERED = new StringSetting("playback_quality_metered", "off");
    public static final BooleanSetting PLAY_SDR = new BooleanSetting("play_sdr", FALSE);
    public static final BooleanSetting PREFER_H264 = new BooleanSetting("prefer_h264", FALSE);
    public static final StringSetting DOWNLOAD_VIDEO_QUALITY = new StringSetting("download_video_quality", "auto");
    /** Download lists the video's renditions and saves the one picked, for that save alone. */
    public static final BooleanSetting DOWNLOAD_ASK_QUALITY = new BooleanSetting("download_ask_quality", FALSE);
    public static final BooleanSetting DOWNLOAD_ORIGINAL_PHOTOS = new BooleanSetting("download_original_photos", FALSE);
    /** Saved photos keep TikTok's HEIF or WebP instead of becoming a JPEG (#229). Read on each save. */
    public static final BooleanSetting KEEP_PHOTOS_AS_SENT = new BooleanSetting("keep_photos_as_sent", FALSE);
    public static final BooleanSetting DOWNLOAD_PHOTOS_AS_VIDEO = new BooleanSetting("download_photos_as_video", FALSE);
    public static final IntegerSetting PHOTO_VIDEO_SECONDS = new IntegerSetting("photo_video_seconds", 3, false,
            Setting.parent(DOWNLOAD_PHOTOS_AS_VIDEO)).withRange(1, 10);
    public static final BooleanSetting DOWNLOAD_AUDIO_TRACK = new BooleanSetting("download_audio_track", FALSE);
    /**
     * "original" keeps a saved sound in TikTok's own container; "opus" makes an .ogg where the
     * phone can; "mp3" makes an .mp3 on any phone. The row stays open everywhere, since MP3 is
     * always possible, and its note says when Opus isn't.
     */
    public static final StringSetting DOWNLOAD_SOUND_FORMAT = new StringSetting("download_sound_format", "original");
    /** TikTok's watermarked copy saved when the clean file can't be fetched, instead of nothing. */
    public static final BooleanSetting DOWNLOAD_WATERMARK_FALLBACK = new BooleanSetting("download_watermark_fallback", FALSE);
    /** The video's cover, at the largest size it comes in, saved with each Download. */
    public static final BooleanSetting DOWNLOAD_COVER = new BooleanSetting("download_cover", FALSE);
    /** A Save cover button beside Download in a video's share sheet; Download then saves only the video (#146). */
    public static final BooleanSetting SAVE_COVER_BUTTON = new BooleanSetting("save_cover_button", FALSE);
    public static final BooleanSetting DOWNLOAD_WITHOUT_SOUND =
            new BooleanSetting("download_without_sound", FALSE);
    public static final BooleanSetting DOWNLOAD_PROGRESS = new BooleanSetting("download_progress", false);
    /** The Share to sheet TikTok opens a few seconds after its own downloader saves a video. */
    public static final BooleanSetting SKIP_POST_DOWNLOAD_SHARE_SHEET =
            new BooleanSetting("skip_post_download_share_sheet", FALSE);
    /** How many of Hushfeed's saves run at once, "1" to "3"; the rest wait in line (#27). */
    public static final StringSetting DOWNLOAD_SAVES_AT_ONCE = new StringSetting("download_saves_at_once", "3");
    /** Network saves wait for an unmetered network and stop when it turns metered; local saves still run. */
    public static final BooleanSetting DOWNLOAD_UNMETERED_ONLY = new BooleanSetting("download_unmetered_only", FALSE);
    public static final BooleanSetting DOWNLOAD_DETAILS =new BooleanSetting("download_details", FALSE);
    /** The details file as JSON rather than plain text. */
    public static final BooleanSetting DOWNLOAD_DETAILS_JSON = new BooleanSetting(
            "download_details_json", FALSE, false, Setting.parent(DOWNLOAD_DETAILS));
    /** The caption, creator, date and link written into the saved MP4 as tags players read. */
    public static final BooleanSetting DOWNLOAD_TAGS = new BooleanSetting("download_tags", FALSE);
    /** The @username and the caption's first line drawn into the saved video's picture. */
    public static final BooleanSetting DOWNLOAD_BURN_CAPTION = new BooleanSetting("download_burn_caption", FALSE);
    public static final BooleanSetting CHECK_SAVED_VIDEOS = new BooleanSetting("check_saved_videos", FALSE);
    /** A check mark on profile grids for videos in the record the check above keeps. */
    public static final BooleanSetting MARK_SAVED_VIDEOS = new BooleanSetting(
            "mark_saved_videos", FALSE, false, Setting.parent(CHECK_SAVED_VIDEOS));
    public static final StringSetting EXTERNAL_DOWNLOADER_PACKAGE =
            new StringSetting("external_downloader_package", "");
    /** The only package whose documented intent extras Hushfeed knows how to request. */
    public static final String YTDLNIS_PACKAGE_NAME = "com.deniscerri.ytdl";
    private static final Setting.Availability YTDLNIS_ONLY = new Setting.Availability() {
        @Override
        public boolean isAvailable() {
            return YTDLNIS_PACKAGE_NAME.equals(EXTERNAL_DOWNLOADER_PACKAGE.savedValue().trim());
        }

        @Override
        public java.util.List<Setting<?>> getParentSettings() {
            return Collections.singletonList(EXTERNAL_DOWNLOADER_PACKAGE);
        }
    };
    public static final StringSetting YTDLNIS_DOWNLOAD_TYPE = new StringSetting(
            "ytdlnis_download_type", "video", false, YTDLNIS_ONLY);
    public static final BooleanSetting YTDLNIS_BACKGROUND = new BooleanSetting(
            "ytdlnis_background", FALSE, false, YTDLNIS_ONLY);
    public static final StringSetting DOWNLOAD_STICKER_FORMAT = new StringSetting("download_sticker_format", "mp4");
    /** Hushfeed's own Save media box after a received chat sticker's tap (#274). */
    public static final BooleanSetting DM_STICKER_SAVE_BOX = new BooleanSetting("dm_sticker_save_box", TRUE);
    public static final BooleanSetting SAVE_PROFILE_PICTURE = new BooleanSetting("save_profile_picture", FALSE);
    public static final BooleanSetting SAVE_STORY = new BooleanSetting("save_story", FALSE);
    public static final BooleanSetting SAVE_STORY_FROM_SHARE = new BooleanSetting("save_story_from_share", FALSE);
    /** A circle on each profile, Liked or Favorites grid cell, to pick posts and save them in one run (#27). */
    public static final BooleanSetting PICK_GRID_VIDEOS = new BooleanSetting("pick_grid_videos", FALSE);
    /**
     * Each quarter of the video runs its own double tap choice (#232). The plain Double tap
     * choice then only covers a tap the window can't place.
     */
    public static final BooleanSetting DOUBLE_TAP_SPLIT_ZONES = new BooleanSetting("double_tap_split_zones", FALSE);
    public static final StringSetting DOUBLE_TAP_ACTION = new StringSetting("double_tap_action", "default",
            false, Setting.parentNot(DOUBLE_TAP_SPLIT_ZONES));
    public static final StringSetting DOUBLE_TAP_TOP_LEFT_ACTION = new StringSetting("double_tap_top_left_action",
            "default", false, Setting.parent(DOUBLE_TAP_SPLIT_ZONES));
    public static final StringSetting DOUBLE_TAP_TOP_RIGHT_ACTION = new StringSetting("double_tap_top_right_action",
            "default", false, Setting.parent(DOUBLE_TAP_SPLIT_ZONES));
    public static final StringSetting DOUBLE_TAP_BOTTOM_LEFT_ACTION = new StringSetting("double_tap_bottom_left_action",
            "default", false, Setting.parent(DOUBLE_TAP_SPLIT_ZONES));
    public static final StringSetting DOUBLE_TAP_BOTTOM_RIGHT_ACTION = new StringSetting("double_tap_bottom_right_action",
            "default", false, Setting.parent(DOUBLE_TAP_SPLIT_ZONES));
    /** What settings search was last used for, newest first, as a JSON array. Kept out of backups. */
    public static final StringSetting RECENT_SETTINGS_SEARCHES =
            new StringSetting("recent_settings_searches", "[]", false, false);
    public static final StringSetting LONG_PRESS_ACTION = new StringSetting("long_press_action", "default");
    /**
     * The Long press choice runs only on the left and right thirds of the video, so a press in the
     * middle still opens TikTok's own menu (#136). It means something only while the choice is one
     * Hushfeed takes.
     */
    public static final BooleanSetting LONG_PRESS_SIDES_ONLY =
            new BooleanSetting("long_press_sides_only", FALSE, new Setting.Availability() {
                @Override
                public boolean isAvailable() {
                    return GestureActions.takesLongPress(LONG_PRESS_ACTION.savedValue());
                }
            });
    /**
     * A long press on a side button plays at the hold speed only while TikTok's hold can start
     * there: the Long press row leaves the press to TikTok, and Seek from the edges isn't seeking
     * the right third, where the buttons sit. GestureActions.allowNativeEdgeSpeedup decides each
     * press the same way.
     */
    private static final Setting.Availability RAIL_HOLD_POSSIBLE = new Setting.Availability() {
        @Override
        public boolean isAvailable() {
            return !GestureActions.takesLongPress(LONG_PRESS_ACTION.savedValue())
                    && !(EDGE_SEEK.savedValue() && EDGE_SEEK_SECONDS.savedValue() > 0);
        }
    };
    /** A long press on Comment plays at the hold speed instead of opening the emoji row. */
    public static final BooleanSetting RAIL_HOLD_COMMENT =
            new BooleanSetting("rail_hold_comment", FALSE, RAIL_HOLD_POSSIBLE);
    /** A long press on Share plays at the hold speed instead of opening the quick share row. */
    public static final BooleanSetting RAIL_HOLD_SHARE =
            new BooleanSetting("rail_hold_share", FALSE, RAIL_HOLD_POSSIBLE);
    /** A long press on Favorites plays at the hold speed instead of offering a new collection. */
    public static final BooleanSetting RAIL_HOLD_FAVORITES =
            new BooleanSetting("rail_hold_favorites", FALSE, RAIL_HOLD_POSSIBLE);
    /** A long press on a video from someone's profile opens For You's full menu (#120). */
    public static final BooleanSetting LONG_PRESS_FULL_MENU_ON_PROFILES =
            new BooleanSetting("long_press_full_menu_on_profiles", FALSE, new Setting.Availability() {
                // Only a long press left to TikTok opens its menu at all.
                @Override
                public boolean isAvailable() {
                    return !GestureActions.takesLongPress(LONG_PRESS_ACTION.savedValue());
                }
            });
    /** The same For You menu for a long press on a video opened from search results (#120). */
    public static final BooleanSetting LONG_PRESS_FULL_MENU_IN_SEARCH =
            new BooleanSetting("long_press_full_menu_in_search", FALSE, new Setting.Availability() {
                @Override
                public boolean isAvailable() {
                    return !GestureActions.takesLongPress(LONG_PRESS_ACTION.savedValue());
                }
            });
    /** What a left swipe on a feed video does: TikTok's creator profile, nothing, or the comments. */
    public static final StringSetting SWIPE_LEFT_ACTION = new StringSetting("swipe_left_action", "default");
    public static final BooleanSetting EDGE_SEEK = new BooleanSetting("edge_seek", FALSE);
    /**
     * A vertical drag along the left edge of a feed video changes the window's brightness and along
     * the right edge the music volume. Off by default; a drag starting anywhere else is untouched.
     */
    public static final BooleanSetting SWIPE_LEVELS = new BooleanSetting("swipe_levels", FALSE);
    /** How wide each edge strip is, as a percent of the screen width. */
    public static final IntegerSetting SWIPE_LEVELS_STRIP_PERCENT =
            new IntegerSetting("swipe_levels_strip_percent", 15, false, Setting.parent(SWIPE_LEVELS)).withRange(5, 30);
    /**
     * What a drag along each edge strip changes: "brightness", "volume" or "speed" (the playing
     * video's speed, which needs the Playback speed patch). The left strip starts on brightness and
     * the right on volume, which is what the switch did before the strips could be chosen.
     */
    public static final StringSetting SWIPE_LEVELS_LEFT =
            new StringSetting("swipe_levels_left", "brightness", false, Setting.parent(SWIPE_LEVELS));
    public static final StringSetting SWIPE_LEVELS_RIGHT =
            new StringSetting("swipe_levels_right", "volume", false, Setting.parent(SWIPE_LEVELS));
    public static final BooleanSetting FIT_VIDEO_TO_SCREEN =
            new BooleanSetting("fit_video_to_screen", FALSE);
    /** The opposite: crop the video until it covers the window (issue #29). Fit wins when both are on. */
    public static final BooleanSetting FILL_VIDEO_TO_SCREEN =
            new BooleanSetting("fill_video_to_screen", FALSE);
    public static final BooleanSetting UNCAP_REFRESH_RATE =
            new BooleanSetting("uncap_refresh_rate", FALSE);
    /**
     * Skips TikTok's brightness boost in the camera, editor and post screens, and the full
     * brightness the cameras' front flash and fill light store (issue #200). The patch is in the
     * default selection, so the switch starts off.
     */
    public static final BooleanSetting KEEP_CAMERA_BRIGHTNESS =
            new BooleanSetting("keep_camera_brightness", FALSE);
    /**
     * Tells the effect platform the phone is a Galaxy S24 Ultra, so effects the server turns down
     * on older phones ("This effect doesn't work on this device") get another chance (issue #176).
     */
    public static final BooleanSetting REPORT_NEWER_PHONE_TO_EFFECTS =
            new BooleanSetting("report_newer_phone_to_effects", FALSE);
    // The four Performance patches, in the default selection because each hook asks one of these
    // first. Off until the reader turns one on, and a paused build answers off. The splash, update
    // and traffic hooks act on TikTok's startup tasks, so they need a restart. The animated image
    // cache is read as each sticker or GIF is built, so it reaches the next one without one.
    public static final BooleanSetting SKIP_SPLASH_AD = new BooleanSetting("skip_splash_ad", FALSE, true);
    public static final BooleanSetting SKIP_UPDATE_CHECKS = new BooleanSetting("skip_update_checks", FALSE, true);
    public static final BooleanSetting LIMIT_BACKGROUND_TRAFFIC =
            new BooleanSetting("limit_background_traffic", FALSE, true);
    public static final BooleanSetting DROP_ANIMATED_IMAGE_CACHE =
            new BooleanSetting("drop_animated_image_cache", FALSE);
    /** Keep playing in the background (#52). TikTok reads its gate once a process, so a restart applies it. */
    public static final BooleanSetting BACKGROUND_PLAY = new BooleanSetting("background_play", FALSE, true);
    /**
     * Replay in the background (#99): a video that ends while TikTok plays in the background
     * starts again instead of waiting on the notification's Play. Read at each end, so no restart.
     */
    public static final BooleanSetting BACKGROUND_REPLAY = new BooleanSetting(
            "background_replay", FALSE, false, Setting.parent(BACKGROUND_PLAY));
    public static final BooleanSetting HIDE_LAUNCHER_SHORTCUTS =
            new BooleanSetting("hide_launcher_shortcuts", FALSE);
    /**
     * Leaves the Hushfeed row out of Settings and privacy (#264). Long pressing For You or Home
     * still opens the page, and Pause brings the row back, since a paused setting reads stock.
     */
    public static final BooleanSetting HIDE_SETTINGS_ROW =
            new BooleanSetting("hide_settings_row", FALSE);
    /**
     * On by default: the setup runs before anyone can reach this switch, so picking the patch is
     * the choice. Off brings the screens back the next time TikTok runs its setup.
     */
    public static final BooleanSetting SKIP_FIRST_LAUNCH_SETUP =
            new BooleanSetting("skip_first_launch_setup", TRUE);
    /**
     * Whether {@link #HIDE_LAUNCHER_SHORTCUTS} has taken the launcher shortcuts away and not yet
     * put them back. No row of its own: it is how turning that switch back off knows there is
     * something to ask TikTok to rebuild, rather than asking on behalf of somebody who never
     * turned it on. Kept out of import and export for the same reason. It describes what happened
     * on one phone, and restoring it onto another would have that phone ask TikTok to rebuild
     * shortcuts nothing had removed.
     */
    public static final BooleanSetting LAUNCHER_SHORTCUTS_REMOVED =
            new BooleanSetting("launcher_shortcuts_removed", FALSE, false, false);
    public static final BooleanSetting ALLOW_DUET_AND_STITCH =
            new BooleanSetting("allow_duet_and_stitch", FALSE);
    public static final BooleanSetting ALLOW_ADD_TO_STORY =
            new BooleanSetting("allow_add_to_story", FALSE);
    public static final BooleanSetting HIDE_FOLLOWER_NOTIFICATIONS =
            new BooleanSetting("hide_follower_notifications", FALSE);
    /**
     * Notification controls' push switch: no push setup at launch, nothing in the drawer but
     * ongoing notifications, and no wake locks but the kept ones. Push setup is a startup task,
     * so a change waits for the next launch. See PushShutoff.
     */
    public static final BooleanSetting TURN_OFF_PUSH_NOTIFICATIONS =
            new BooleanSetting("turn_off_push_notifications", FALSE, true);
    /** TikTok's "Videos you might like" pushes. On by default: they're promotion, not people. */
    public static final BooleanSetting BLOCK_SUGGESTED_VIDEO_NOTIFICATIONS =
            new BooleanSetting("block_suggested_video_notifications", TRUE);
    public static final BooleanSetting HIDE_MESSAGE_STREAKS =
            new BooleanSetting("hide_message_streaks", FALSE);
    /**
     * Leaves a message in the chat when the other person unsends it. Messages you unsend
     * yourself are still removed. Off by default. See KeepUnsent.
     */
    public static final BooleanSetting KEEP_UNSENT_MESSAGES =
            new BooleanSetting("keep_unsent_messages", FALSE);
    /** Adds "unsent" after the text of a message Keep unsent messages kept. On by default. See KeepUnsent. */
    public static final BooleanSetting MARK_KEPT_UNSENT_MESSAGES =
            new BooleanSetting("mark_kept_unsent_messages", TRUE);
    /**
     * Dispatches one message per selected chat a day at {@link #AUTO_STREAK_MINUTE},
     * so a streak with them keeps going on a day nobody opens the app. Off by default. See
     * AutoStreak.
     */
    public static final BooleanSetting AUTO_STREAK = new BooleanSetting("auto_streak", FALSE);
    /** Handles or profile links, separated by commas or lines. */
    public static final StringSetting AUTO_STREAK_RECIPIENT =
            new StringSetting("auto_streak_recipient", "", false, Setting.parent(AUTO_STREAK));
    /** The time of day to send, in minutes after midnight. Noon leaves room for retries. */
    public static final IntegerSetting AUTO_STREAK_MINUTE = new IntegerSetting(
            "auto_streak_minute", 12 * 60, false, Setting.parent(AUTO_STREAK)).withRange(0, 24 * 60 - 1);
    public static final StringSetting AUTO_STREAK_MESSAGE = new StringSetting(
            "auto_streak_message", "🔥", false, Setting.parent(AUTO_STREAK));
    /**
     * Per-account recipient dispatch reservations and retries. Kept out
     * of backups: it describes one phone's sends, and a restore that carried it could stop
     * today's message on another phone or send a second one.
     */
    public static final StringSetting AUTO_STREAK_STATE =
            new StringSetting("auto_streak_state", "", false, false);
        // Zero is meaningful, and the dialog takes it: it turns edge seeking off on its own.
    public static final IntegerSetting EDGE_SEEK_SECONDS =
            new IntegerSetting("edge_seek_seconds", 5, false, Setting.parent(EDGE_SEEK)).withRange(0, 60);
    public static final BooleanSetting CONFIRM_FOLLOW = new BooleanSetting("confirm_follow", FALSE);
    public static final BooleanSetting CONFIRM_LIKE = new BooleanSetting("confirm_like", FALSE);
    public static final BooleanSetting CONFIRM_COMMENT_LIKE = new BooleanSetting("confirm_comment_like", FALSE);
    public static final BooleanSetting CONFIRM_STORY_LIKE = new BooleanSetting("confirm_story_like", FALSE);
    public static final BooleanSetting CONFIRM_QUICK_REPOST = new BooleanSetting("confirm_quick_repost", FALSE);
    public static final StringSetting BLOCKED_CAPTION_WORDS =
            new FeedRuleStringSetting("blocked_caption_words", false);
    public static final BooleanSetting BLOCKED_WORDS_IN_STICKERS = new BooleanSetting("blocked_words_in_stickers", FALSE);
    public static final StringSetting BLOCKED_CREATORS =
            new FeedRuleStringSetting("blocked_creators", true);
    public static final StringSetting LOCAL_HIDDEN_CREATORS =
            new FeedRuleStringSetting("local_hidden_creators", true);
    public static final StringSetting CREATOR_FILTER_EXCEPTIONS =
            new FeedRuleStringSetting("creator_filter_exceptions", true);
    public static final StringSetting REGION_ONLY_FROM = new StringSetting("region_only_from", "", true);
    /** Caption languages to keep, by the video's original caption track; empty keeps every language. */
    public static final StringSetting CAPTION_LANGUAGES = new StringSetting("caption_languages", "");
    public static final StringSetting REGION_NEVER_FROM = new StringSetting("region_never_from", "", true);
    public static final IntegerSetting MIN_VIDEO_SECONDS =
            new IntegerSetting("min_video_seconds", 0).withRange(0, 86400);
    public static final IntegerSetting MAX_VIDEO_SECONDS =
            new IntegerSetting("max_video_seconds", 0).withRange(0, 86400);
    public static final IntegerSetting MAX_PUBLICATION_AGE_DAYS =
            new IntegerSetting("max_publication_age_days", 0).withRange(0, 3650);
    public static final IntegerSetting MAX_VIEWS_PER_LIKE =
            new IntegerSetting("max_views_per_like", 0).withRange(0, 1000000);
    public static final IntegerSetting MAX_VIEWS_PER_COMMENT =
            new IntegerSetting("max_views_per_comment", 0).withRange(0, 1000000);
    public static final BooleanSetting HIDE_PROMOTIONAL_MUSIC = new BooleanSetting("hide_promotional_music", FALSE);
    public static final BooleanSetting HIDE_LIVE_REPLAYS = new BooleanSetting("hide_live_replays", FALSE);
    public static final BooleanSetting HIDE_UNPERSONALIZED_FOR_YOU = new BooleanSetting("hide_unpersonalized_for_you", FALSE);
    /** Read on every Friends tab response, so it applies from the next page on. */
    public static final BooleanSetting FRIENDS_MUTUALS_ONLY = new BooleanSetting("friends_mutuals_only", FALSE);
    /** Each batch the Following feed loads plays newest first (#226). Read once per run of the app. */
    public static final BooleanSetting FOLLOWING_LATEST_FIRST = new BooleanSetting("following_latest_first", FALSE, true);
    public static final BooleanSetting HIDE_SHARE_CHANNELS = new BooleanSetting("hide_share_channels", FALSE);
    public static final BooleanSetting HIDE_SHARE_ACTIONS = new BooleanSetting("hide_share_actions", FALSE);
    public static final BooleanSetting SHARE_BLOCK_AUTHOR = new BooleanSetting("share_block_author", FALSE);
    public static final BooleanSetting REMOVE_ADS = new BooleanSetting("remove_ads", TRUE, true);
    public static final BooleanSetting HIDE_LIVE = new BooleanSetting("hide_live", FALSE, true);
    public static final BooleanSetting HIDE_SHOP = new BooleanSetting("hide_shop", FALSE, true);
    /** TikTok Shop cards in search results: the Products block and single product cards (issue #21). */
    public static final BooleanSetting HIDE_SEARCH_SHOP = new BooleanSetting("hide_search_shop", FALSE);
    public static final BooleanSetting HIDE_STORY = new BooleanSetting("hide_story", FALSE, true);
    public static final BooleanSetting HIDE_IMAGE = new BooleanSetting("hide_image", FALSE, true);
    /**
     * Its own key, not the old hide_captcha_popups: that row was unavailable for several
     * releases, so a true saved under it was never a choice made for the browsing rule.
     */
    public static final BooleanSetting HIDE_CAPTCHA_POPUPS = new BooleanSetting("hide_browsing_captcha_popups", FALSE, true);
    public static final BooleanSetting HIDE_HOMEPAGE_COIN = new BooleanSetting("hide_homepage_coin", FALSE, true);
    // A restart: the gift box is hidden when the profile header binds it, and a profile already
    // drawn keeps it until TikTok starts again.
    public static final BooleanSetting HIDE_PROFILE_REWARDS_SHORTCUT =
            new BooleanSetting("hide_profile_rewards_shortcut", FALSE, true);
    public static final StringSetting MIN_MAX_VIEWS = new StringSetting("min_max_views", "0-" + Long.MAX_VALUE, true);
    public static final StringSetting MIN_MAX_LIKES = new StringSetting("min_max_likes", "0-" + Long.MAX_VALUE, true);
    public static final StringSetting MIN_MAX_COMMENTS = new StringSetting("min_max_comments", "0-" + Long.MAX_VALUE, true);
    public static final StringSetting MIN_MAX_FAVOURITES = new StringSetting("min_max_favourites", "0-" + Long.MAX_VALUE, true);
    public static final StringSetting MIN_MAX_SHARES = new StringSetting("min_max_shares", "0-" + Long.MAX_VALUE, true);
    public static final BooleanSetting HIDE_OFFLINE_VIDEOS = new BooleanSetting("hide_offline_videos", FALSE);
    /** Moot while the switch above takes every offline video out. */
    public static final BooleanSetting FILTER_OFFLINE_FALLBACK_VIDEOS = new BooleanSetting(
            "filter_cached_offline_videos",
            TRUE,
            true,
            Setting.parentNot(HIDE_OFFLINE_VIDEOS)
    );
    public static final BooleanSetting FILTERED_COUNT_PILL = new BooleanSetting("feed_filter_count_pill", FALSE);
    public static final BooleanSetting FEED_NAVIGATION = new BooleanSetting("feed_navigation", FALSE, true);
    public static final StringSetting FEED_NAVIGATION_TABS = new StringSetting(
            "feed_navigation_tabs",
            NavigationTabOptions.defaultEnabledKeys(),
            true,
            Setting.parent(FEED_NAVIGATION)
    );
    public static final BooleanSetting FEED_NAVIGATION_BLOCK_NEW_TABS = new BooleanSetting(
            "feed_navigation_block_new_tabs",
            FALSE,
            true,
            Setting.parent(FEED_NAVIGATION)
    );
    public static final StringSetting FEED_NAVIGATION_OBSERVED_TABS = new StringSetting(
            "feed_navigation_observed_tabs",
            NavigationTabOptions.HOT,
            false,
            false
    );
    public static final BooleanSetting BOTTOM_NAVIGATION = new BooleanSetting("bottom_navigation", FALSE, true);
    public static final StringSetting BOTTOM_NAVIGATION_TABS = new StringSetting(
            "bottom_navigation_tabs",
            BottomNavigationTabOptions.defaultEnabledKeys(),
            true,
            Setting.parent(BOTTOM_NAVIGATION)
    );
    public static final BooleanSetting BOTTOM_NAVIGATION_BLOCK_NEW_TABS = new BooleanSetting(
            "bottom_navigation_block_new_tabs",
            FALSE,
            true,
            Setting.parent(BOTTOM_NAVIGATION)
    );
    public static final StringSetting BOTTOM_NAVIGATION_OBSERVED_TABS = new StringSetting(
            "bottom_navigation_observed_tabs",
            BottomNavigationTabOptions.HOME,
            false,
            false
    );
    /** The red count on Inbox and the dot on Profile, the pull that reopens the app. */
    public static final BooleanSetting HIDE_TAB_BADGES = new BooleanSetting("hide_tab_badges", FALSE, true);
    /** The names under the bottom tab icons, and a name of the user's own for each; empty keeps TikTok's. Read before each frame. */
    public static final BooleanSetting HIDE_BOTTOM_TAB_LABELS = new BooleanSetting("hide_bottom_tab_labels", FALSE);
    public static final StringSetting BOTTOM_TAB_NAME_HOME = new StringSetting("bottom_tab_name_home", "");
    public static final StringSetting BOTTOM_TAB_NAME_FRIENDS = new StringSetting("bottom_tab_name_friends", "");
    public static final StringSetting BOTTOM_TAB_NAME_INBOX = new StringSetting("bottom_tab_name_inbox", "");
    public static final StringSetting BOTTOM_TAB_NAME_PROFILE = new StringSetting("bottom_tab_name_profile", "");
    public static final StringSetting BOTTOM_TAB_NAME_SHOP = new StringSetting("bottom_tab_name_shop", "");
    public static final BooleanSetting KEEP_FOR_YOU_ON_TAB_TAP = new BooleanSetting("keep_for_you_on_tab_tap", FALSE);
    public static final BooleanSetting KEEP_FOR_YOU_ON_PULL_DOWN = new BooleanSetting("keep_for_you_on_pull_down", FALSE);
    /** A long press on the Home tab opens Hushfeed's settings (#45). TikTok gives that press nothing of its own. */
    public static final BooleanSetting HOME_TAB_OPENS_SETTINGS = new BooleanSetting("home_tab_opens_settings", TRUE);
    /**
     * A long press on the Profile tab opens TikTok's Watch history (#199). Off by default: on some
     * accounts that press is TikTok's account switcher, which this takes over while it's on.
     */
    public static final BooleanSetting PROFILE_TAB_OPENS_WATCH_HISTORY = new BooleanSetting("profile_tab_opens_watch_history", FALSE);
    /** The tab TikTok opens on from its icon: tiktok (its own pick), for_you, following, friends, inbox or profile. */
    public static final StringSetting START_PAGE = new StringSetting("start_page", "tiktok");
    /** TikTok's previous, pause and next buttons on the feed, shown without a screen reader. Off by default. */
    public static final BooleanSetting SHOW_FEED_BUTTONS = new BooleanSetting("show_feed_buttons", FALSE);
    public static final BooleanSetting HIDE_TAKO_AI = new BooleanSetting("hide_tako_ai", FALSE, true);
    public static final BooleanSetting HIDE_BOTTOM_SEARCH_BAR = new BooleanSetting("hide_bottom_search_bar", FALSE, true);
    public static final BooleanSetting COMMENT_BATCH_TRANSLATION = new BooleanSetting("comment_batch_translation", FALSE);
    public static final BooleanSetting DM_TRANSLATION_PREVIEW = new BooleanSetting("dm_translation_preview", FALSE);
    public static final BooleanSetting RECEIVED_DM_TRANSLATION_PREVIEW = new BooleanSetting("received_dm_translation_preview", FALSE);
    /** Languages TikTok's automatic translation leaves alone, added to its own Don't translate list (#121). */
    public static final StringSetting DONT_AUTO_TRANSLATE_LANGUAGES = new StringSetting("dont_auto_translate_languages", "");
    /** Videos left in their own language while TikTok's Always translate is on (#194). TikTok builds the state at launch. */
    public static final BooleanSetting NO_VIDEO_AUTO_TRANSLATE = new BooleanSetting("no_video_auto_translate", FALSE, true);
    /** The one language TikTok translates into instead of its own choice, empty for TikTok's (TranslateInto). */
    public static final StringSetting TRANSLATE_INTO = new StringSetting("translate_into", "");
    /**
     * TikTok's own translate button in the comment header (#85). No restart: the header asks its
     * gates each time a comment sheet opens.
     */
    public static final BooleanSetting COMMENT_TRANSLATE_BUTTON = new BooleanSetting("comment_translate_button", FALSE);
    /**
     * Whether the toggle behind that button was last turned on by the button Hushfeed showed. No
     * row of its own: it is how turning the switch off knows to answer that toggle off, rather
     * than one TikTok turned on itself. Kept out of import and export for the same reason as
     * {@link #LAUNCHER_SHORTCUTS_REMOVED}: the toggle it describes lives in TikTok's storage on
     * one phone.
     */
    public static final BooleanSetting COMMENT_TRANSLATE_BUTTON_TURNED_ON =
            new BooleanSetting("comment_translate_button_turned_on", FALSE, false, false);
    /**
     * Whether the comment header's translate toggle was last turned off by its button while
     * {@link #COMMENT_BATCH_TRANSLATION} was on (#282). No row of its own: it is how Auto
     * translate comments stops for the whole app, the way TikTok's toggle does, until the button
     * or the switch turns it back on. Not kept when paused: a pause stops the translating anyway.
     * Turning the toggle back on clears it, paused or not, while an off while paused and TikTok's
     * erase when the comment page closes leave it as it was. Kept out of import and export like
     * the mark above.
     */
    public static final BooleanSetting COMMENT_TRANSLATION_TURNED_OFF =
            new BooleanSetting("comment_translation_turned_off", FALSE, false, false);
    /**
     * TikTok's translation for accounts it takes it away from (#143): the rows on its own Settings
     * and privacy > Language page, and See translation on videos. Restart-gated: the Language page
     * asks each time it opens, but the videos' answer is read once per process.
     */
    public static final BooleanSetting SHOW_TRANSLATION_SETTINGS = new BooleanSetting("show_translation_settings", FALSE, true);
    // Restart-gated: the comment keyboard builds its slot tree once per session, and the
    // trigger that adds the emoji row is asked at that moment only.
    public static final BooleanSetting HIDE_COMMENT_QUICK_REACTIONS =
            new BooleanSetting("hide_comment_quick_reactions", FALSE, true);
    public static final StringSetting DOWNLOAD_PATH = new StringSetting("down_path", "DCIM/TikTok");
    public static final StringSetting DOWNLOAD_VIDEO_PATH = new StringSetting("download_video_path", "DCIM/TikTok");
    public static final StringSetting DOWNLOAD_PHOTO_PATH = new StringSetting("download_photo_path", "DCIM/TikTok");
    public static final StringSetting DOWNLOAD_STICKER_PATH = new StringSetting("download_sticker_path", "DCIM/TikTok");
    private static final BooleanSetting DOWNLOAD_PATHS_MIGRATED = new BooleanSetting(
            "download_paths_migrated",
            FALSE,
            false,
            false
    );
    public static final StringSetting DOWNLOAD_VIDEO_FILENAME_TEMPLATE = new StringSetting(
            "download_video_filename_template",
            "{creator}_{date}_{video_id}"
    );
    public static final StringSetting DOWNLOAD_PHOTO_FILENAME_TEMPLATE = new StringSetting(
            "download_photo_filename_template",
            "{creator}_{date}_{video_id}_{index}"
    );
    public static final StringSetting DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE = new StringSetting(
            "download_comment_media_filename_template",
            "comment_{date}_{media_id}"
    );
    public static final BooleanSetting REMOVE_DOWNLOAD_WATERMARK = new BooleanSetting("down_watermark", TRUE);
    public static final BooleanSetting CUSTOM_OFFLINE_VIDEOS = new BooleanSetting("custom_offline_videos", FALSE, true);
    public static final IntegerSetting CUSTOM_OFFLINE_VIDEO_LIMIT = new IntegerSetting(
            "custom_offline_video_limit",
            500,
            true,
            Setting.parent(CUSTOM_OFFLINE_VIDEOS)
    ).withRange(CustomOfflineVideosLimitPatch.MIN_LIMIT, CustomOfflineVideosLimitPatch.MAX_LIMIT);
    /** Offline videos stay until you clear them, instead of expiring after TikTok's lifetime (#123). */
    public static final BooleanSetting KEEP_OFFLINE_VIDEOS = new BooleanSetting("keep_offline_videos", FALSE, true);
    public static final BooleanSetting SHOW_SEEKBAR = new BooleanSetting("show_seekbar", TRUE);
    /** The time played and the video's length at the end of the progress bar (#90). */
    public static final BooleanSetting SEEKBAR_TIME = new BooleanSetting("seekbar_time", FALSE);
    /**
     * Where that time goes: "beside" (the end of the bar's row, the default) or "above" (over the
     * bar's right end, falling back to beside while text on screen reaches into that spot) (#173).
     */
    public static final StringSetting SEEKBAR_TIME_PLACEMENT = new StringSetting(
            "seekbar_time_placement", "beside", Setting.parent(SEEKBAR_TIME));
    /** The progress bar runs from screen edge to screen edge instead of stopping at TikTok's side gap (#173). */
    public static final BooleanSetting SEEKBAR_FULL_WIDTH = new BooleanSetting("seekbar_full_width", FALSE);
    /** A larger handle and a slightly thicker line on the progress bar at rest (#90). */
    public static final BooleanSetting SEEKBAR_BIG_HANDLE = new BooleanSetting("seekbar_big_handle", FALSE);
    /** A line along the bottom of a video story that can be dragged to seek (#72). */
    public static final BooleanSetting STORY_SEEKBAR = new BooleanSetting("story_seekbar", FALSE);
    /** A line along the bottom of a sound page's cover while its preview plays, which seeks the preview (#185). */
    public static final BooleanSetting SOUND_SEEKBAR = new BooleanSetting("sound_seekbar", FALSE);
    public static final BooleanSetting SHOW_SEEKBAR_THUMBNAIL = new BooleanSetting(
            "show_seekbar_thumbnail",
            TRUE
    );
    public static final BooleanSetting STOP_VIDEO_LOOPING = new BooleanSetting("stop_video_looping", FALSE, true);
    /** Keeps the full-screen viewer on a video when it ends; the Stay on the video in full screen patch. */
    public static final BooleanSetting FULL_SCREEN_HOLD = new BooleanSetting("full_screen_hold", FALSE, true);
    /** Replays a story instead of moving on when it ends; the Story controls patch. */
    public static final BooleanSetting STORY_LOOP = new BooleanSetting("story_loop", FALSE, true);
    /** Keeps a photo story on screen until you tap or swipe; the Story controls patch. */
    public static final BooleanSetting STORY_PHOTO_HOLD = new BooleanSetting("story_photo_hold", FALSE, true);
    /**
     * Keeps a LIVE preview in the feed from counting down into the room; the LIVE controls patch.
     * Read each time a preview would start its countdown, so no restart.
     */
    public static final BooleanSetting STOP_LIVE_AUTO_ENTER = new BooleanSetting("stop_live_auto_enter", FALSE);
    /**
     * Hides the Service+ row under a LIVE preview in the feed; the LIVE controls patch. Read each
     * time the row would show, so no restart.
     */
    public static final BooleanSetting HIDE_LIVE_SERVICE_PLUS = new BooleanSetting("hide_live_service_plus", FALSE);
    /**
     * Shows a LIVE's exact viewer count in its room instead of TikTok's rounded one; the LIVE
     * controls patch. Read each time the count is drawn, so no restart.
     */
    public static final BooleanSetting SHOW_EXACT_LIVE_VIEWERS = new BooleanSetting("show_exact_live_viewers", FALSE);
    /**
     * Offers TikTok's quality picker in more LIVE rooms and carries the last pick to the next
     * room; the LIVE controls patch. Read when a room builds its menu and picks its starting
     * quality, so the next room follows a change.
     */
    public static final BooleanSetting OFFER_LIVE_QUALITY = new BooleanSetting("offer_live_quality", FALSE);
    /**
     * Keeps the last LIVE quality picked after TikTok closes, so the first rooms after a cold
     * start open at it when their stream has it (#57); the LIVE controls patch, under
     * {@link #OFFER_LIVE_QUALITY}. Read as each room picks its starting quality, so no restart.
     * A pick is saved from either of TikTok's pickers: the newer one's setter and the older
     * LiveDefinitionSelectionDialog's applied switch, which Share > Video quality opens in rooms
     * whose stream asks for a quality button.
     */
    public static final BooleanSetting REMEMBER_LIVE_QUALITY = new BooleanSetting(
            "remember_live_quality", FALSE, false, Setting.parent(OFFER_LIVE_QUALITY));
    /** TikTok's key for that pick ("hd", "origin" and so on), saved from its picker. Never shown. */
    public static final StringSetting REMEMBERED_LIVE_QUALITY = new StringSetting("remembered_live_quality", "");
    /**
     * Offers clear screen in every LIVE room's menu (#57); the LIVE controls patch. Read as a
     * room builds its menu, so the next menu follows a change.
     */
    public static final BooleanSetting OFFER_LIVE_CLEAR_SCREEN = new BooleanSetting("offer_live_clear_screen", FALSE);
    /**
     * Opens LIVE panels in TikTok's dark LIVE theme while TikTok is in dark mode (#159); the LIVE
     * controls patch. Read as each panel opens, so the next panel follows a change. The provider
     * answer alone only turned the panels' navigation bar dark on the S25, so each themed
     * dialog and sheet page also inflates through the dark overlay with force (LivePanelTheme).
     */
    public static final BooleanSetting DARK_LIVE_PANELS = new BooleanSetting("dark_live_panels", FALSE);
    /**
     * Puts a Record button in LIVE rooms (#37); the LIVE controls patch. A recording starts when the
     * button is tapped and ends at Stop or when the room is left. Read as a room resumes and on the
     * chip's once-a-second check, so no restart.
     */
    public static final BooleanSetting RECORD_LIVE = new BooleanSetting("record_live", FALSE);
    /**
     * Which of a room's streams a LIVE recording takes (#203): "room" for the quality being
     * watched, "highest" or "lowest". Read as each recording starts, so no restart.
     */
    public static final StringSetting LIVE_RECORD_QUALITY = new StringSetting("live_record_quality", "room");
    /**
     * What a LIVE recording is made from (#37): "stream" saves the room's own video, "screen" records
     * the phone's screen. Read as each recording starts, so no restart.
     */
    public static final StringSetting LIVE_RECORD_SOURCE = new StringSetting("live_record_source", "stream");
    /**
     * Plays the audio TikTok mutes on a post whose sound was pulled; the Keep pulled sounds
     * patch. Read as each video starts, so no restart.
     */
    public static final BooleanSetting KEEP_PULLED_SOUNDS = new BooleanSetting("keep_pulled_sounds", FALSE);
    /**
     * Keeps the playing video going in a small window when the reader leaves TikTok; the
     * Picture-in-picture patch. Read as the reader leaves, so no restart.
     */
    public static final BooleanSetting PICTURE_IN_PICTURE = new BooleanSetting("picture_in_picture", FALSE);
    public static final BooleanSetting RESUME_VIDEO_AFTER_SCROLL = new BooleanSetting(
            "resume_video_after_scroll",
            TRUE,
            true
    );
    public static final BooleanSetting OPEN_EXTERNAL_LINKS = new BooleanSetting("open_external_links", TRUE);
    public static final BooleanSetting ALWAYS_SHOW_PUBLISH_DATE = new BooleanSetting("always_show_publish_date", TRUE, true);
    public static final BooleanSetting PUBLISH_DATE_EXACT_TIME = new BooleanSetting("publish_date_exact_time", FALSE);
    /** The time zone after the exact time (Discussion #147). On keeps the row as it always read. */
    public static final BooleanSetting PUBLISH_DATE_TIME_ZONE = new BooleanSetting(
            "publish_date_time_zone", TRUE, false, Setting.parent(PUBLISH_DATE_EXACT_TIME));
    /** The language's short date ("10/7/25") before the exact time, in place of its medium one ("Oct 7, 2025"). */
    public static final BooleanSetting PUBLISH_DATE_SHORT_DATE = new BooleanSetting(
            "publish_date_short_date", FALSE, false, Setting.parent(PUBLISH_DATE_EXACT_TIME));
    public static final BooleanSetting PUBLISH_DATE_ON_GRID = new BooleanSetting("publish_date_on_grid", FALSE);
    public static final BooleanSetting VIDEO_LENGTH_ON_GRID = new BooleanSetting("video_length_on_grid", FALSE);
    /** How loaded profile and Liked grids are ordered (#180): off, or one of the orders in GridSort. */
    public static final StringSetting PROFILE_GRID_SORT = new StringSetting("profile_grid_sort", "off");
    /** The Liked tab's own order (#180): "profile" follows PROFILE_GRID_SORT, otherwise one of GridSort's orders. */
    public static final StringSetting LIKED_GRID_SORT = new StringSetting("liked_grid_sort", "profile");
    public static final BooleanSetting SHOW_EXACT_COUNTS = new BooleanSetting("show_exact_counts", FALSE);
    public static final BooleanSetting CLEAR_DISPLAY = new BooleanSetting("clear_display", FALSE);
    /** Clear display turned on by hand carries to the next video. Off, each video starts with its controls (#232). */
    public static final BooleanSetting REMEMBER_CLEAR_DISPLAY_ON_SCROLL =
            new BooleanSetting("remember_clear_display_on_scroll", TRUE);
    public static final BooleanSetting COPY_COMMENTS_WITHOUT_USERNAME = new BooleanSetting("copy_comments_without_username", TRUE);
    public static final FloatSetting REMEMBERED_SPEED = new FloatSetting("remembered_speed_v2", 1.0f);
    public static final BooleanSetting REMEMBER_SPEED = new BooleanSetting("remember_playback_speed", TRUE);
    public static final BooleanSetting DEFAULT_SPEED_ENABLED = new BooleanSetting("default_speed_enabled", FALSE);
    public static final StringSetting DEFAULT_SPEED = new StringSetting(
            "default_speed", "1.5", false, Setting.parent(DEFAULT_SPEED_ENABLED));
    public static final StringSetting CUSTOM_SPEEDS = new StringSetting("custom_speeds", "", true);
    /** The speed the hold gesture plays at and its pull-down lock keeps; TikTok's own is 2x (upstream #52). */
    public static final StringSetting HOLD_SPEED = new StringSetting("hold_speed", "2");
    /** Sliding sideways during the hold changes its speed, with a bubble that shows it (#248). */
    public static final BooleanSetting HOLD_SPEED_SLIDE = new BooleanSetting("hold_speed_slide", FALSE);
    public static final BooleanSetting AUTO_ADVANCE = new BooleanSetting("auto_advance", FALSE, true);
    public static final IntegerSetting AUTO_ADVANCE_LIMIT = new IntegerSetting(
            "auto_advance_limit", 0, false, Setting.parent(AUTO_ADVANCE)).withRange(0, 1000);
    /**
     * Takes TikTok's own Auto scroll action out of the video panel (upstream #116). The patch
     * surfaces that action for accounts outside its rollout; a reader who only wants advance on
     * end has no use for it, and it read as a second, unexplained switch. It also takes the Auto
     * scroll switch tablets and unfolded foldables show beside the video, and it works with
     * Auto-advance off (#234).
     */
    public static final BooleanSetting AUTO_ADVANCE_HIDE_PANEL_ACTION = new BooleanSetting(
            "auto_advance_hide_panel_action", FALSE, true);
    /**
     * Answers yes to TikTok's search_auto_scroll flag. Search results get auto scroll from that
     * flag rather than the For You one, and their feed runs the same component Auto-advance starts.
     */
    public static final BooleanSetting AUTO_ADVANCE_SEARCH = new BooleanSetting(
            "auto_advance_search", FALSE, true, Setting.parent(AUTO_ADVANCE));
    /**
     * Quietens the feed while a comment sheet is open, and gives the sound back when it closes.
     * Off by default.
     */
    public static final BooleanSetting PAUSE_ON_COMMENTS = new BooleanSetting(
            "pause_on_comments", FALSE, true);
    /**
     * Holds the feed on returning to the app until the reader taps. Off by default. TikTok
     * starts playing again by itself on every return, which is the one moment nobody has asked
     * for anything.
     */
    public static final BooleanSetting NO_RESUME_ON_FOREGROUND = new BooleanSetting(
            "no_resume_on_foreground", FALSE, true);
    /**
     * Holds the first feed video of a start from the launcher until the reader taps, once per
     * start (#83). Off by default. A link, a notification or a shortcut opens what it was for.
     */
    public static final BooleanSetting PAUSE_FIRST_VIDEO = new BooleanSetting(
            "pause_first_video", FALSE, true);
    /**
     * Keep a paused video paused. It reads the player in the pre-pause callback, which arrived in
     * Android 10, so older versions have nothing to read it by and the row is greyed there.
     */
    public static final BooleanSetting KEEP_PAUSED_ON_RETURN = new BooleanSetting("keep_paused_on_return", FALSE,
            new Setting.Availability() {
                @Override public boolean isAvailable() {
                    return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q;
                }
            });
    /**
     * Sends TikTok to the background when TikTok's own daily screen-time reminder comes up,
     * instead of leaving the reminder there to be dismissed. Off by default, read at show
     * time, and it needs a daily limit set under TikTok's own Time and well-being settings
     * to have anything to react to.
     */
    public static final BooleanSetting LEAVE_ON_REST_REMINDER = new BooleanSetting(
            "leave_on_rest_reminder", FALSE);
    /**
     * Minutes a TikTok screen can go without a touch before TikTok goes to the background, or
     * zero for never. Off by default. See IdleLeave.
     */
    public static final IntegerSetting IDLE_LEAVE_MINUTES = new IntegerSetting(
            "idle_leave_minutes", 0).withRange(0, 240);
    /**
     * A daily budget for the feed, off at zero. The two counts are independent of
     * {@link #AUTO_ADVANCE_LIMIT}, which only ever counted videos Hushfeed itself advanced past.
     */
    public static final IntegerSetting SESSION_BUDGET_VIDEOS = new IntegerSetting(
            "session_budget_videos", 0).withRange(0, 2000);
    public static final IntegerSetting SESSION_BUDGET_MINUTES = new IntegerSetting(
            "session_budget_minutes", 0).withRange(0, 600);
    public static final IntegerSetting SESSION_BUDGET_LOCK_MINUTES = new IntegerSetting(
            "session_budget_lock_minutes", 0).withRange(0, 720);
    public static final IntegerSetting SESSION_BUDGET_RESET_HOUR = new IntegerSetting(
            "session_budget_reset_hour", 4).withRange(0, 23);
    /**
     * Turns the budget from advice into a commitment. Off by default, and while it is off
     * nothing about the budget changes. Switched on, the hold that starts when today's budget
     * runs out lasts until the reset hour, the way out of it is gone, and the budget, the reset
     * hour and this switch itself cannot be changed again until the day turns over. It can be
     * switched off freely at any time before the budget is spent.
     */
    public static final BooleanSetting SESSION_BUDGET_LOCK = new BooleanSetting(
            "session_budget_lock", FALSE, true);
    /**
     * How many times the hold may be opened in one day. Zero means no cap, which is what the
     * hold has always done, and the setting sits between that and {@link #SESSION_BUDGET_LOCK},
     * which takes the way out away entirely. Ignored while the lock is on, because there is then
     * nothing to cap.
     */
    public static final IntegerSetting SESSION_BUDGET_PASSES_PER_DAY = new IntegerSetting(
            "session_budget_passes_per_day", 0).withRange(0, 20);
    /**
     * Makes a change that loosens the budget wait until the day starts over, while one that
     * tightens it applies at once. Off by default. What counts as loosening, and how a restore is
     * held to it, is in BudgetChanges.
     */
    public static final BooleanSetting SESSION_BUDGET_WAIT_TO_LOOSEN = new BooleanSetting(
            "session_budget_wait_to_loosen", FALSE);
    /**
     * The loosening changes waiting for the next day and when they apply. Kept out of backups,
     * since a backup that carried them would be a way to set tomorrow's budget today.
     */
    public static final StringSetting SESSION_BUDGET_PENDING =
            new StringSetting("session_budget_pending", "", false, false);
    /**
     * Brings the hold in gradually instead of dropping it on the feed. Off by default, and it
     * only has anything to follow when {@link #SESSION_BUDGET_MINUTES} is set: a budget counted
     * in videos has no "how long is left" to draw a ramp from.
     */
    public static final BooleanSetting SESSION_BUDGET_RAMP = new BooleanSetting(
            "session_budget_ramp", FALSE, true);
    /**
     * Lets the video on screen finish before the hold covers the feed, with the feed's swipe
     * turned off until it does. Off by default, read when the budget runs out, and it only has
     * anything to do when a hold follows the budget. See FinishLastVideo.
     */
    public static final BooleanSetting SESSION_BUDGET_FINISH_VIDEO = new BooleanSetting(
            "session_budget_finish_video", FALSE);
    /**
     * Keeps the feed (For You, Following and the other feed tabs) behind a calm panel with its
     * swipe turned off, and leaves Inbox, profiles and search alone. Off by default. Paused,
     * every setting answers its unpatched value, so this is off then like the rest. See FeedLock.
     */
    public static final BooleanSetting FEED_LOCK = new BooleanSetting("feed_lock", FALSE);
    /**
     * Weekly hours the feed lock applies by itself, on the phone's clock, as typed: "Mon-Fri
     * 21:00-07:00; Sat Sun 13:00-15:00". Empty by default, which locks nothing, and empty while
     * paused like the rest. See FeedSchedule.
     */
    public static final StringSetting FEED_LOCK_HOURS = new StringSetting("feed_lock_hours", "");
    /**
     * A link to one video plays that video alone: the feed's swipe is turned down while it is the
     * one playing, and Auto-advance doesn't move on from it. Off by default, and off while
     * paused like the rest. See FeedLock#linkVideoAlone.
     */
    public static final BooleanSetting SHARED_VIDEO_ALONE = new BooleanSetting("shared_video_alone", FALSE);
    /**
     * A small label on the feed saying what is left of today's budget. Off by default, and it
     * has nothing to report unless {@link #SESSION_BUDGET_VIDEOS} or {@link #SESSION_BUDGET_MINUTES}
     * is set. No restart: it is drawn from the same callback that measures the budget.
     */
    public static final BooleanSetting SESSION_BUDGET_CUE = new BooleanSetting(
            "session_budget_cue", FALSE);
    /**
     * Minutes of watching between the quiet reminders, or zero for none.
     *
     * <p>The hold only ever fires once the day's budget has gone. This is the earlier check the
     * wellbeing tools that measured anything all have, and it is measured in watched minutes
     * rather than wall clock so time on messages or a profile does not count towards it.
     */
    public static final IntegerSetting SESSION_BUDGET_NOTICE_MINUTES = new IntegerSetting(
            "session_budget_notice_minutes", 0).withRange(0, 120);
    /**
     * Today's counts and any running hold, so both survive the process being killed. Kept out
     * of backups: it is a record of one day, and restoring last week's would either hand back a
     * day or take one away, neither of which anyone asked for.
     */
    public static final StringSetting SESSION_BUDGET_STATE =
            new StringSetting("session_budget_state", "", false, false);

    public static final BooleanSetting ENABLE_LONG_PRESS_SPEED_LOCK = new BooleanSetting("enable_long_press_speed_lock", FALSE, true);
    public static final BooleanSetting NOT_INTERESTED_BUTTON = new BooleanSetting("not_interested_button", FALSE);
    /**
     * Mute feed videos. Whether the feed is muted right now is the phone's state, like the volume,
     * so a backup doesn't carry it; paused, Hushfeed plays the feed with sound as TikTok would.
     */
    public static final BooleanSetting FEED_MUTED = new BooleanSetting("feed_muted", FALSE, false, false);
    public static final BooleanSetting FEED_MUTE_BUTTON = new BooleanSetting("feed_mute_button", FALSE);
    /** TikTok's audio focus waits for a video to play, so opening the app or a chat leaves other apps' music alone (#283). */
    public static final BooleanSetting FOCUS_WAITS_FOR_VIDEO = new BooleanSetting("focus_waits_for_video", FALSE);
    public static final BooleanSetting HIDE_FEED_CAPTION = new BooleanSetting("hide_feed_caption", FALSE);
    /** Native description and creator name, independently of spoken subtitles. Zero keeps TikTok's size. */
    public static final IntegerSetting FEED_DESCRIPTION_TEXT_SIZE =
            new IntegerSetting("feed_description_text_size", 0).withRange(0, 48);
    public static final IntegerSetting FEED_AUTHOR_TEXT_SIZE =
            new IntegerSetting("feed_author_text_size", 0).withRange(0, 48);
    public static final BooleanSetting HIDE_FEED_MUSIC = new BooleanSetting("hide_feed_music", FALSE);
    public static final BooleanSetting HIDE_FEED_ACTION_BAR = new BooleanSetting("hide_feed_action_bar", FALSE);
    public static final BooleanSetting HIDE_FEED_SURVEYS = new BooleanSetting("hide_feed_surveys", FALSE);
    /** The Footnotes banner TikTok lays over a video that carries a note. */
    public static final BooleanSetting HIDE_FOOTNOTES = new BooleanSetting("hide_footnotes", FALSE);
    /** The LIVE and stories row across the top of the Following tab and its count pill (#262). */
    public static final BooleanSetting HIDE_FOLLOWING_LIVE_ROW = new BooleanSetting("hide_following_live_row", FALSE);
    /** The Add comment bar under a video opened from a profile, a hashtag or a sound, and the strip kept for it (#50). */
    public static final BooleanSetting HIDE_DETAIL_COMMENT_BAR = new BooleanSetting("hide_detail_comment_bar", FALSE);
    /** The progress bar, close button and pause/speed pill TikTok draws while Clear display is on (#97). */
    public static final BooleanSetting HIDE_CLEAR_DISPLAY_CONTROLS = new BooleanSetting("hide_clear_display_controls", FALSE);
    /** Leaves TikTok's Clear display progress bar and its loading line out of that hiding (#237). */
    public static final BooleanSetting KEEP_CLEAR_DISPLAY_SEEK_BAR = new BooleanSetting(
            "keep_clear_display_seek_bar", FALSE, false, Setting.parent(HIDE_CLEAR_DISPLAY_CONTROLS));
    /**
     * How see-through the controls over the video are drawn, as a percentage: 100 leaves them as
     * TikTok draws them, lower fades the rail, caption, music row and tabs while they keep taking
     * taps, and 0 hides the rail and caption the way Clear display does (#84). In Clear display a
     * level between keeps the rail and caption in sight at that level and lets taps through them
     * to the video (VideoOverlayHider, TapThroughControls), unless FADE_IN_CLEAR_DISPLAY is off.
     */
    public static final IntegerSetting FADE_CONTROLS_OPACITY =
            new IntegerSetting("fade_controls_opacity", 100).withRange(0, 100);
    /**
     * Whether a fade between 0 and 100 keeps the controls in sight in Clear display (#84). Off,
     * Clear display takes them away as it does at 100, and the fade only reaches them outside it,
     * for those who want the fade on the feed and Clear display still clear.
     */
    public static final BooleanSetting FADE_IN_CLEAR_DISPLAY = new BooleanSetting("fade_in_clear_display", TRUE);
    /**
     * Lets the video draw down behind TikTok's bottom tab bar and takes the bar's own background
     * away, so the tabs sit over the picture (#145). VideoOverlayHider collapses the spacer under
     * each video and swaps the bar's background for a clear one, and puts both back when this goes
     * off or Hushfeed is paused.
     */
    public static final BooleanSetting SEE_THROUGH_BOTTOM_BAR = new BooleanSetting("see_through_bottom_bar", FALSE);
    /**
     * With See-through bottom bar, draws the tabs on a rounded see-through pill inset from the
     * bar's sides and bottom instead of nothing (#124). Only the bar's background changes, so the
     * tabs keep their places. Read on each layout.
     */
    public static final BooleanSetting FLOATING_BOTTOM_BAR = new BooleanSetting(
            "floating_bottom_bar", FALSE, false, Setting.parent(SEE_THROUGH_BOTTOM_BAR));
    /**
     * With Floating bottom bar, draws the pill as glass: a lighter tint, a frost and a sheen that
     * brighten toward the top, and a rim that catches the light along the top edge (#218). Same
     * place and size as the plain pill. Read on each layout.
     */
    public static final BooleanSetting LIQUID_GLASS_BOTTOM_BAR = new BooleanSetting(
            "liquid_glass_bottom_bar", FALSE, false, Setting.parent(FLOATING_BOTTOM_BAR));
    /**
     * With Liquid glass look, draws a smaller piece of glass behind the selected tab that slides
     * to the next tab picked (#231). Part of the bar's background, so the tabs and their taps
     * don't move. Read on each layout.
     */
    public static final BooleanSetting GLASS_TAB_LENS = new BooleanSetting(
            "glass_tab_lens", FALSE, false, Setting.parent(LIQUID_GLASS_BOTTOM_BAR));
    /**
     * With Liquid glass look on Android 12 and newer, blurs the strip of video behind the glass
     * pill instead of drawing a frost over it (#231). The strip is read back from the player's
     * surface a few times a second while the pill is on screen. Without a surface to read, the
     * plain glass stays. Read on each layout.
     */
    public static final BooleanSetting GLASS_VIDEO_BLUR = new BooleanSetting(
            "glass_video_blur", FALSE, false, Setting.parent(LIQUID_GLASS_BOTTOM_BAR));
    /**
     * With Blur the video behind the bar, how strongly it blurs: "light", "standard" or "strong",
     * the three strengths of HushGram's glass tab bar. Read on each draw.
     */
    public static final StringSetting GLASS_BLUR_STRENGTH = new StringSetting(
            "glass_blur_strength", "standard", false, Setting.parent(GLASS_VIDEO_BLUR));
    /**
     * With Liquid glass look, how solid the glass's tint is: "clear", "light", "standard" or
     * "frosted", which scale the tint by a half, three quarters, one and 1.35. Read on each draw.
     */
    public static final StringSetting GLASS_OPACITY = new StringSetting(
            "glass_opacity", "standard", false, Setting.parent(LIQUID_GLASS_BOTTOM_BAR));
    /**
     * With Floating bottom bar, how tall the pill is: "standard" fills the bar but for a hairline
     * under it and stands 4dp above its top, "compact" is 2dp slimmer on each side. The bar keeps
     * its size, so the tabs keep their taps. Read on each draw.
     */
    public static final StringSetting GLASS_BAR_HEIGHT = new StringSetting(
            "glass_bar_height", "standard", false, Setting.parent(FLOATING_BOTTOM_BAR));
    /**
     * With Floating bottom bar, the corners of the pill and the lens: "standard" (12dp and 8dp),
     * "rounder" (20dp and 14dp) or "round" (half circle ends). Read on each draw.
     */
    public static final StringSetting GLASS_CORNERS = new StringSetting(
            "glass_corners", "round", false, Setting.parent(FLOATING_BOTTOM_BAR));
    /**
     * With Glass lens on the current tab, a finger slid along the pill carries the lens, and
     * lifting it opens the tab it ended on (GlassTabSlide). Read on each touch.
     */
    public static final BooleanSetting GLASS_TAB_SLIDE = new BooleanSetting(
            "glass_tab_slide", FALSE, false, Setting.parent(GLASS_TAB_LENS));
    /** With Glass lens on the current tab, a tick each time the lens passes a tab. Read on each draw. */
    public static final BooleanSetting GLASS_HAPTICS = new BooleanSetting(
            "glass_haptics", FALSE, false, Setting.parent(GLASS_TAB_LENS));
    /** The tick Glass haptics plays: "short", "system", "soft" or "full" (GlassBarHaptics). */
    public static final StringSetting GLASS_HAPTIC_STYLE = new StringSetting(
            "glass_haptic_style", "system", false, Setting.parent(GLASS_HAPTICS));
    /**
     * Lets the main feed's video draw up under the status bar, with the clock over it (#145).
     * VideoOverlayHider collapses the spacer TikTok keeps above each video for the bar and
     * SeeThroughStatusBar clears the bar's color while the feed is in front; both go back when
     * this goes off or Hushfeed is paused. Read on each layout.
     */
    public static final BooleanSetting SEE_THROUGH_STATUS_BAR = new BooleanSetting("see_through_status_bar", FALSE);
    /**
     * Burn-in guard for OLED screens (BurnInGuard): "dim" draws the controls the fade covers at a
     * quarter after a few seconds without a touch, "dim_shift" also moves the window's content a
     * couple of dp every two minutes. Over the feed and an opened video only.
     */
    public static final StringSetting BURN_IN_GUARD = new StringSetting("burn_in_guard", "off");
    public static final BooleanSetting HIDE_SHARE_GUIDE = new BooleanSetting("hide_share_guide", FALSE);
    public static final BooleanSetting HIDE_RAIL_FOLLOW = new BooleanSetting("hide_rail_follow", FALSE);
    public static final BooleanSetting HIDE_RAIL_LIKE = new BooleanSetting("hide_rail_like", FALSE);
    public static final BooleanSetting HIDE_RAIL_COMMENTS = new BooleanSetting("hide_rail_comments", FALSE);
    public static final BooleanSetting HIDE_RAIL_FAVOURITE = new BooleanSetting("hide_rail_favourite", FALSE);
    public static final BooleanSetting HIDE_RAIL_MUSIC = new BooleanSetting("hide_rail_music", FALSE);
    public static final BooleanSetting HIDE_RAIL_SHARE = new BooleanSetting("hide_rail_share", FALSE);
    public static final BooleanSetting HIDE_RAIL_COUNTS = new BooleanSetting("hide_rail_counts", FALSE);
    /**
     * Whether the music disc turns (#68, MusicDiscSpin). TikTok reads the two settings behind it
     * once per launch, so both take a restart (Spin also lifts the holds past them, which are asked
     * on every video). Holding it still is declared first because Spin names it as the switch that
     * overrides it.
     */
    public static final BooleanSetting STOP_MUSIC_DISC_SPIN = new BooleanSetting("stop_music_disc_spin", FALSE, true);
    public static final BooleanSetting SPIN_MUSIC_DISC = new BooleanSetting("spin_music_disc", FALSE, true,
            Setting.parentNot(STOP_MUSIC_DISC_SPIN));
    public static final BooleanSetting HIDE_STATUS_BAR = new BooleanSetting("hide_status_bar", FALSE);
    /** LIVE rooms are an activity of their own, which Hide the status bar never reached (#38). */
    public static final BooleanSetting HIDE_STATUS_BAR_IN_LIVE = new BooleanSetting("hide_status_bar_in_live", FALSE);
    /** Only the black strip TikTok keeps for the bar goes; the bar stays over the stream (#137). */
    public static final BooleanSetting LIVE_UNDER_STATUS_BAR = new BooleanSetting("live_under_status_bar", FALSE);
    public static final StringSetting TOUCH_TARGET_SCALE = new StringSetting("touch_target_scale", "1");
    public static final BooleanSetting HIDE_SENSITIVE_WARNINGS = new BooleanSetting("hide_sensitive_warnings", FALSE);
    /** The Check sources banner on a video TikTok flags as unverified, and the share warnings that read it. */
    public static final BooleanSetting HIDE_UNVERIFIED_NOTICES = new BooleanSetting("hide_unverified_notices", FALSE);
    /** The Check your sources banner across the top of a video (TikTok's Know the facts), and the prompt it raises when you share one. */
    public static final BooleanSetting HIDE_CHECK_YOUR_SOURCES = new BooleanSetting("hide_check_your_sources", FALSE);
    public static final BooleanSetting SHOW_AUTHOR_REGION = new BooleanSetting("show_author_region", FALSE);
    /** The country as its flag in place of its two letters, by the creator's name and a comment's time (#157). */
    public static final BooleanSetting REGION_AS_FLAG = new BooleanSetting("region_as_flag", FALSE);
    public static final BooleanSetting SHOW_AUTHOR_HANDLE = new BooleanSetting("show_author_handle", FALSE);
    public static final BooleanSetting SHOW_ENGAGEMENT_RATE = new BooleanSetting("show_engagement_rate", FALSE);
    public static final BooleanSetting BLOCK_AUTHOR_BUTTON =
            new BooleanSetting("block_author_button", FALSE, true);
    public static final BooleanSetting LOCAL_HIDE_BUTTON =
            new BooleanSetting("local_hide_button", FALSE);
    public static final BooleanSetting BLOCK_SOUND_BUTTON =
            new BooleanSetting("block_sound_button", FALSE);
    public static final StringSetting BLOCK_AUTHOR_BUTTON_POSITION =
            new StringSetting("block_author_button_position", "");
    public static final StringSetting LOCAL_HIDE_BUTTON_POSITION =
            new StringSetting("local_hide_button_position", "");
    public static final StringSetting BLOCK_SOUND_BUTTON_POSITION =
            new StringSetting("block_sound_button_position", "");
    public static final StringSetting NOT_INTERESTED_BUTTON_POSITION =
            new StringSetting("not_interested_button_position", "");
    public static final StringSetting FEED_MUTE_BUTTON_POSITION =
            new StringSetting("feed_mute_button_position", "");
    public static final BooleanSetting HIDE_INBOX_STORIES = new BooleanSetting("hide_inbox_stories", FALSE);
    public static final BooleanSetting HIDE_INBOX_NEW_FOLLOWERS = new BooleanSetting("hide_inbox_new_followers", FALSE);
    public static final BooleanSetting HIDE_INBOX_ACTIVITY = new BooleanSetting("hide_inbox_activity", FALSE);
    public static final BooleanSetting HIDE_INBOX_ARCHIVE = new BooleanSetting("hide_inbox_archive", FALSE);
    public static final BooleanSetting HIDE_INBOX_TAKO = new BooleanSetting("hide_inbox_tako", FALSE);
    public static final BooleanSetting HIDE_INBOX_SHOP = new BooleanSetting("hide_inbox_shop", FALSE);
    public static final BooleanSetting HIDE_INBOX_BULLETIN_BOARDS =
            new BooleanSetting("hide_inbox_bulletin_boards", FALSE);
    // A restart: the Friends tab and a profile keep the suggestion cells they already drew, and
    // only a new bind collapses one.
    public static final BooleanSetting HIDE_INBOX_SUGGESTED_ACCOUNTS =
            new BooleanSetting("hide_inbox_suggested_accounts", FALSE, true);
    public static final BooleanSetting HIDE_INBOX_MESSAGE_REQUESTS =
            new BooleanSetting("hide_inbox_message_requests", FALSE);
    public static final BooleanSetting HIDE_INBOX_CONVERSATIONS =
            new BooleanSetting("hide_inbox_conversations", FALSE);
    public static final BooleanSetting HIDE_INBOX_ADD_PEOPLE = new BooleanSetting("hide_inbox_add_people", FALSE);
    public static final BooleanSetting HIDE_INBOX_SEARCH = new BooleanSetting("hide_inbox_search", FALSE);
    public static final BooleanSetting INBOX_LOADED_SEARCH = new BooleanSetting("inbox_loaded_search", FALSE);
    public static final BooleanSetting HIDE_INBOX_ACTIVITY_STATUS =
            new BooleanSetting("hide_inbox_activity_status", FALSE);
    /** The banner at the top of the Inbox that invites you to start a group chat. */
    public static final BooleanSetting HIDE_INBOX_GROUP_CHAT_BANNER =
            new BooleanSetting("hide_inbox_group_chat_banner", FALSE);
    public static final BooleanSetting EXPAND_ACTIVITY_LIST = new BooleanSetting("expand_activity_list", FALSE);
    /** Chat screen clutter. Each is off by default and read live when a chat opens. */
    public static final BooleanSetting HIDE_CHAT_CALL_BUTTONS =
            new BooleanSetting("hide_chat_call_buttons", FALSE);
    public static final BooleanSetting HIDE_CHAT_STICKER_BANNER =
            new BooleanSetting("hide_chat_sticker_banner", FALSE);
    public static final BooleanSetting HIDE_CHAT_AI_REPLIES =
            new BooleanSetting("hide_chat_ai_replies", FALSE);
    /** A message's double tap and sideways swipe in a chat, read at each gesture. */
    public static final BooleanSetting TURN_OFF_CHAT_DOUBLE_TAP =
            new BooleanSetting("turn_off_chat_double_tap", FALSE);
    public static final BooleanSetting TURN_OFF_CHAT_SWIPE_REPLY =
            new BooleanSetting("turn_off_chat_swipe_reply", FALSE);
    public static final StringSetting HIDE_INBOX_CUSTOM_TITLES =
            new StringSetting("hide_inbox_custom_titles", "");
    // Feed filter additions. The list based ones are read live, so a sound blocked from
    // the player takes effect on the next feed page without a restart.
    public static final BooleanSetting HIDE_BLOCKED_SOUNDS = new BooleanSetting("hide_blocked_sounds", TRUE);
    public static final StringSetting BLOCKED_SOUND_IDS = new StringSetting("blocked_sound_ids", "");
    public static final StringSetting BLOCKED_SOUND_NAMES = new StringSetting("blocked_sound_names", "");
    public static final BooleanSetting HIDE_PAID_PARTNERSHIP = new BooleanSetting("hide_paid_partnership", FALSE, true);
    public static final BooleanSetting FILTER_LOCATION_VIDEOS = new BooleanSetting("filter_location_videos", FALSE, true);
    public static final BooleanSetting HIDE_AI_GENERATED = new BooleanSetting("hide_ai_generated", FALSE, true);
    public static final BooleanSetting HIDE_VERIFIED = new BooleanSetting("hide_verified", FALSE, true);
    public static final BooleanSetting HIDE_SERIES = new BooleanSetting("hide_series", FALSE, true);
    /** TikTok's short dramas and the cards that promote them (upstream #155). */
    public static final BooleanSetting HIDE_MINI_DRAMAS = new BooleanSetting("hide_mini_dramas", FALSE, true);
    public static final BooleanSetting HIDE_SEEN_VIDEOS = new BooleanSetting("hide_seen_videos", FALSE, true);
    public static final IntegerSetting SEEN_VIDEO_RETENTION_DAYS =
            new IntegerSetting("seen_video_retention_days", 30).withRange(0, 3650);
    /**
     * How much of a video, in percent, has to play before it counts as seen. Zero keeps the
     * original rule (a tenth of the video, one to five seconds). 90 is the top because the
     * last progress report before a loop can land anywhere in the final second.
     */
    public static final IntegerSetting SEEN_VIDEO_MARK_PERCENT =
            new IntegerSetting("seen_video_mark_percent", 0).withRange(0, 90);
    public static final BooleanSetting HIDE_PLAYLIST_BAR = new BooleanSetting("hide_playlist_bar", FALSE, true);
    public static final BooleanSetting HIDE_EVENT_BADGE = new BooleanSetting("hide_event_badge", FALSE, true);
    public static final BooleanSetting HIDE_INSERTED_CARDS = new BooleanSetting("hide_inserted_cards", FALSE, true);
    public static final BooleanSetting HIDE_PLAYLIST_VIDEOS = new BooleanSetting("hide_playlist_videos", FALSE, true);
    // TikTok Subs posts only subscribers can watch (#219), on the feed and on profile grids.
    public static final BooleanSetting HIDE_SUBSCRIBER_ONLY = new BooleanSetting("hide_subscriber_only", FALSE, true);
    // The LIVE feed you swipe through (issue #57). Read on every page TikTok sends, so a change
    // reaches the next page without a restart.
    public static final BooleanSetting LIVE_FEED_FILTER = new BooleanSetting("live_feed_filter", FALSE);
    public static final BooleanSetting LIVE_HIDE_GAMING =
            new BooleanSetting("live_hide_gaming", FALSE, false, Setting.parent(LIVE_FEED_FILTER));
    public static final BooleanSetting LIVE_HIDE_SHOPPING =
            new BooleanSetting("live_hide_shopping", FALSE, false, Setting.parent(LIVE_FEED_FILTER));
    public static final BooleanSetting LIVE_HIDE_SPONSORED =
            new BooleanSetting("live_hide_sponsored", FALSE, false, Setting.parent(LIVE_FEED_FILTER));
    public static final BooleanSetting LIVE_HIDE_VERIFIED =
            new BooleanSetting("live_hide_verified", FALSE, false, Setting.parent(LIVE_FEED_FILTER));
    public static final StringSetting LIVE_HIDDEN_CATEGORIES =
            new StringSetting("live_hidden_categories", "", false, Setting.parent(LIVE_FEED_FILTER));
    public static final StringSetting LIVE_MIN_MAX_VIEWERS = new StringSetting(
            "live_min_max_viewers", "0-" + Long.MAX_VALUE, false, Setting.parent(LIVE_FEED_FILTER));
    public static final StringSetting LIVE_MIN_MAX_FOLLOWERS = new StringSetting(
            "live_min_max_followers", "0-" + Long.MAX_VALUE, false, Setting.parent(LIVE_FEED_FILTER));

    // Privacy.
    public static final BooleanSetting GHOST_MODE = new BooleanSetting("ghost_mode", FALSE);
    // Stops the client's own activity status report. Off by default, and only meaningful while
    // Ghost mode is on, so it sits under that switch.
    public static final BooleanSetting GHOST_HIDE_ONLINE_STATUS =
            new BooleanSetting("ghost_hide_online_status", FALSE, false, Setting.parent(GHOST_MODE));
    public static final BooleanSetting DISABLE_ANALYTICS = new BooleanSetting("disable_analytics", FALSE);
    // Stop on-device AI profiling, in the default selection with this off. TikTok starts the
    // engine during launch, so a restart applies it.
    public static final BooleanSetting STOP_AI_PROFILING = new BooleanSetting("stop_ai_profiling", FALSE, true);
    // One switch per device-access patch, off by default: the patches are in the default selection,
    // so TikTok reads as it ships until the reader turns a block on. Each is read at the
    // intercepted call, so none needs a restart.
    public static final BooleanSetting BLOCK_CONTACT_LIST = new BooleanSetting("block_contact_list", FALSE);
    public static final BooleanSetting BLOCK_INSTALLED_APPS = new BooleanSetting("block_installed_apps", FALSE);
    public static final BooleanSetting BLOCK_LOCATION = new BooleanSetting("block_location", FALSE);
    public static final BooleanSetting BLOCK_CLIPBOARD_READS = new BooleanSetting("block_clipboard_reads", FALSE);
    // Off by default like the blocks above. Hiding a VPN changes what TikTok reads your
    // connection as, and a blank advertising id can affect attribution the reader may want kept.
    // Both are read at the intercepted call, so neither needs a restart, and a paused build
    // answers TikTok's real value.
    public static final BooleanSetting HIDE_VPN = new BooleanSetting("hide_vpn", FALSE);
    public static final BooleanSetting BLOCK_ADVERTISING_ID = new BooleanSetting("block_advertising_id", FALSE);
    // Native root collectors can retain an earlier answer, so a change requires a restart.
    public static final BooleanSetting HIDE_ROOT = new BooleanSetting("hide_root", FALSE, true);
    public static final BooleanSetting BLOCK_MOTION_SENSORS = new BooleanSetting("block_motion_sensors", FALSE);
    // Off by default for the same reason, and read at each history write, so no restart.
    public static final BooleanSetting STOP_SEARCH_HISTORY = new BooleanSetting("stop_search_history", FALSE);
    // Off by default too: people use Watch history to find a video again, and the report it
    // holds back also counts views and feeds For You.
    public static final BooleanSetting STOP_WATCH_HISTORY = new BooleanSetting("stop_watch_history", FALSE);
    // Off by default: it rides on the sensor patch, so turning on the sensor block is not a
    // choice about the benchmark (#64). Put into effect by BenchmarkRuns.
    public static final BooleanSetting STOP_BENCHMARK_RUNS = new BooleanSetting("stop_benchmark_runs", FALSE);
    // Off by default: TikTok's own hybrid pages, the shop checkout and the CAPTCHA page among
    // them, are built on that bridge and stop working without it.
    public static final BooleanSetting BLOCK_WEBVIEW_JS_INTERFACES = new BooleanSetting("block_webview_js_interfaces", FALSE);
    public static final BooleanSetting CAMERA_MIC_INDICATOR = new BooleanSetting("camera_mic_indicator", FALSE);
    // On once the patch is picked, which is the opt-in. A restart, because TikTok works its
    // signature hash out once and keeps it.
    public static final BooleanSetting STORE_IDENTITY = new BooleanSetting("store_identity", TRUE, true);
    // The store those installer reads name, by package. A restart for the same reason (#112).
    public static final StringSetting STORE_IDENTITY_INSTALLER =
            new StringSetting("store_identity_installer", "com.android.vending", true);
    // Skip passkey sign-in (#102), in the default selection with this off. Read each time a
    // sign-in screen asks whether this phone can use passkeys, so no restart.
    public static final BooleanSetting SKIP_PASSKEY_SIGN_IN = new BooleanSetting("skip_passkey_sign_in", FALSE);
    // Keep the app language (#61), in the default selection with this off. TikTok's reset runs
    // once early in each start, so a change counts from the next one, with no restart asked for.
    public static final BooleanSetting KEEP_APP_LANGUAGE = new BooleanSetting("keep_app_language", FALSE);
    // Profile background (#80), in the default selection with this off. TikTok asks its gate as
    // Edit profile or a profile is built, but the allow-list value its profile requests send is
    // snapshotted once per account and process, so a change takes a restart to reach the server.
    public static final BooleanSetting PROFILE_BACKGROUND = new BooleanSetting("profile_background", FALSE, true);
    // App lock. Off until the reader turns it on, and read as each screen starts, so no restart.
    // The delay is whole minutes TikTok may spend in the background before it asks again.
    public static final BooleanSetting APP_LOCK = new BooleanSetting("app_lock", FALSE);
    public static final StringSetting APP_LOCK_TIMEOUT =
            new StringSetting("app_lock_timeout", "0", false, Setting.parent(APP_LOCK));
    // Feed toolbar controls. The LIVE button shares HIDE_LIVE_ENTRANCE with the overlay hider.
    public static final BooleanSetting HIDE_FEED_FOLLOW_BUTTON =
            new BooleanSetting("hide_feed_follow_button", FALSE, true);
    public static final BooleanSetting HIDE_FEED_SAVE_BUTTON =
            new BooleanSetting("hide_feed_save_button", FALSE, true);
    // Remove avatar rings: the story ring and the LIVE ring, each on its own switch.
    public static final BooleanSetting HIDE_STORY_RINGS = new BooleanSetting("hide_story_rings", FALSE);
    public static final BooleanSetting HIDE_LIVE_RING = new BooleanSetting("hide_live_ring", FALSE);
    // A creator's profile picture opens their Story before their LIVE, the next tap the LIVE (#233).
    public static final BooleanSetting OPEN_STORY_OVER_LIVE = new BooleanSetting("open_story_over_live", FALSE);
    // Lift text length limits: comments, repost notes and the bio.
    public static final BooleanSetting LIFT_LENGTH_LIMITS = new BooleanSetting("lift_length_limits", FALSE);
    // Enable voice comments, in the default selection with this off. A restart, since TikTok may
    // keep the answer for a comment box it has already built.
    public static final BooleanSetting ENABLE_VOICE_COMMENTS = new BooleanSetting("enable_voice_comments", FALSE, true);
    // PineDrama's comment box gallery tab, which PineDrama turns off for everyone (#156). A
    // restart, since the answer is kept per signed-in account.
    public static final BooleanSetting PINEDRAMA_COMMENT_PHOTOS = new BooleanSetting("pinedrama_comment_photos", FALSE, true);
    public static final BooleanSetting KEEP_FAVORITES_TAB =
            new BooleanSetting("keep_favorites_tab", TRUE, true);
    /** The collections popup from holding the bookmark, dark when TikTok is (issue #142). */
    public static final BooleanSetting DARK_QUICK_FAVORITES = new BooleanSetting("dark_quick_favorites", TRUE);
    /** The Following and For You names above the feed; the pager under them keeps swiping (issue #32). */
    public static final BooleanSetting HIDE_FEED_TAB_STRIP = new BooleanSetting("hide_feed_tab_strip", FALSE);
    public static final BooleanSetting HIDE_FEED_SEARCH_BUTTON =
            new BooleanSetting("hide_feed_search_button", FALSE, true);
    /** The search box at the top of an opened video, with the feed's search button (#158). Read on each layout. */
    public static final BooleanSetting HIDE_OPENED_VIDEO_SEARCH = new BooleanSetting(
            "hide_opened_video_search", FALSE, false, Setting.parent(HIDE_FEED_SEARCH_BUTTON));
    /** The Back button beside that box, with it (#251). Read on each layout. */
    public static final BooleanSetting HIDE_OPENED_VIDEO_BACK = new BooleanSetting(
            "hide_opened_video_back", FALSE, false, Setting.parent(HIDE_OPENED_VIDEO_SEARCH));
    /** The profile views button and Add friends at the top of your Profile tab (#158). Read on each layout. */
    public static final BooleanSetting HIDE_PROFILE_VIEWS_BUTTON = new BooleanSetting("hide_profile_views_button", FALSE);
    public static final BooleanSetting HIDE_PROFILE_ADD_FRIENDS = new BooleanSetting("hide_profile_add_friends", FALSE);
    /** The Balance cell in your profile's menu (#194). Read each time the menu is built. */
    public static final BooleanSetting HIDE_PROFILE_MENU_BALANCE = new BooleanSetting("hide_profile_menu_balance", FALSE);
    /** The row of app icons under TikTok Minis in your profile's menu (#230). Read each time the menu is built. */
    public static final BooleanSetting HIDE_PROFILE_MENU_MINIS_ICONS = new BooleanSetting("hide_profile_menu_minis_icons", FALSE);
    public static final BooleanSetting HIDE_VISUAL_SEARCH = new BooleanSetting("hide_visual_search", FALSE);
    public static final BooleanSetting HIDE_FULLSCREEN_BUTTON = new BooleanSetting("hide_fullscreen_button", FALSE, true);
    // Read each time TikTok asks, so it doesn't need a restart.
    public static final BooleanSetting HIDE_FEED_REPORT_BUTTON = new BooleanSetting("hide_feed_report_button", FALSE);
    // Each rewards service getter keeps its first answer until TikTok restarts (#21).
    public static final BooleanSetting HIDE_SEARCH_REWARDS = new BooleanSetting("hide_search_rewards", FALSE, true);
    public static final BooleanSetting HIDE_LOCATION_LABELS = new BooleanSetting("hide_location_labels", FALSE, true);
    // Read each time TikTok builds a video's caption strips, so no restart.
    public static final BooleanSetting HIDE_CREATION_TAGS = new BooleanSetting("hide_creation_tags", FALSE);
    public static final BooleanSetting HIDE_SEARCH_SUGGESTIONS = new BooleanSetting("hide_search_suggestions", FALSE);
    public static final BooleanSetting STOP_SEARCH_AUTOPLAY = new BooleanSetting("stop_search_autoplay", FALSE);
    /**
     * Has TikTok read its own HD upload choice as on; the Always upload in HD patch. Read as each
     * post is prepared, so no restart.
     */
    public static final BooleanSetting ALWAYS_UPLOAD_HD = new BooleanSetting("always_upload_hd", FALSE);
    /**
     * Takes the AI Cast button (AI Self, AI Theater inside TikTok) off the camera's side toolbar;
     * the Feed filter patch. TikTok asks as the camera builds its toolbar, so the next camera
     * opened follows the switch.
     */
    public static final BooleanSetting HIDE_AI_CAST = new BooleanSetting("hide_ai_cast", FALSE);
    public static final StringSetting CUSTOM_SHARE_DOMAIN = new StringSetting("custom_share_domain", "");
    // Opens a short vt/vm.tiktok.com share link once to swap the full link onto the clipboard.
    public static final BooleanSetting EXPAND_SHORT_SHARE_LINKS = new BooleanSetting("expand_short_share_links", FALSE);
    public static final BooleanSetting HIDE_LIVE_ENTRANCE = new BooleanSetting("hide_live_entrance", FALSE);
    // The side menu button beside LIVE (#128). The toolbar asks once, as it builds the feed's
    // top bar, so a change needs a restart.
    public static final BooleanSetting HIDE_FEED_SIDEBAR_BUTTON =
            new BooleanSetting("hide_feed_sidebar_button", FALSE, true);
    // A right swipe from For You opens that same side menu (#213). TikTok asks on each swipe, so
    // a change applies at once.
    public static final BooleanSetting DISABLE_FEED_SIDEBAR_SWIPE =
            new BooleanSetting("disable_feed_sidebar_swipe", FALSE);
    // Comment tools.
    public static final BooleanSetting COMMENT_KEYWORD_FILTER = new BooleanSetting("comment_keyword_filter", FALSE);
    public static final StringSetting COMMENT_BLOCKED_KEYWORDS = new StringSetting("comment_blocked_keywords", "");
    public static final StringSetting COMMENT_BLOCKED_USERS = new StringSetting("comment_blocked_users", "");
    public static final BooleanSetting BLOCK_FROM_COMMENT = new BooleanSetting("block_from_comment", FALSE);
    /** Adds Block this commenter to the menu a long press on a comment opens (#169). */
    public static final BooleanSetting BLOCK_FROM_COMMENT_MENU = new BooleanSetting("block_from_comment_menu", FALSE);
    public static final BooleanSetting COMMENT_SEARCH = new BooleanSetting("comment_search", FALSE);
    /** Adds Export CSV and Export JSON under the comment search box. */
    public static final BooleanSetting COMMENT_EXPORT = new BooleanSetting("comment_export", FALSE);
    /** Keeps the comment search box behind a button in the sheet's header until it's tapped (#254). */
    public static final BooleanSetting COMMENT_SEARCH_FOLDED = new BooleanSetting("comment_search_folded", FALSE);
    public static final BooleanSetting COMMENT_LINKS = new BooleanSetting("comment_links", FALSE);
    public static final BooleanSetting HIDE_COMMENT_MEDIA = new BooleanSetting("hide_comment_media", FALSE);
    /** Drops bot comments that point to a bio, a link or a messenger, or bait follows (#187). */
    public static final BooleanSetting HIDE_SPAM_COMMENTS = new BooleanSetting("hide_spam_comments", FALSE);
    public static final BooleanSetting HIDE_COMMENT_POLLS = new BooleanSetting("hide_comment_polls", FALSE);
    /** Drops the reply TikTok opens under a comment before View replies is tapped (#193). */
    public static final BooleanSetting HIDE_COMMENT_REPLY_PREVIEWS = new BooleanSetting("hide_comment_reply_previews", FALSE);
    /** Answers no survey from TikTok's comment survey config, so a comment list carries none. */
    public static final BooleanSetting HIDE_COMMENT_SURVEYS = new BooleanSetting("hide_comment_surveys", FALSE);
    /** Keeps the photo, @ and gift buttons in the comment box gone. Emoji and sending stay. */
    public static final BooleanSetting HIDE_COMMENT_BOX_BUTTONS = new BooleanSetting("hide_comment_box_buttons", FALSE);
    /** Draws a comment poll's results before the reader votes, from the counts TikTok already sends. */
    public static final BooleanSetting SHOW_POLL_RESULTS = new BooleanSetting("show_poll_results", FALSE);
    /**
     * A commenter's @username in place of their display name, and in a reply's label once the
     * person it answers has been drawn. Read on each comment bind, so no restart.
     */
    public static final BooleanSetting SHOW_COMMENTER_HANDLE = new BooleanSetting("show_commenter_handle", FALSE);
    /** The country a commenter's account is set to, after the comment's time. Read on each bind. */
    public static final BooleanSetting SHOW_COMMENTER_REGION = new BooleanSetting("show_commenter_region", FALSE);
    /** Each commenter's name in a color of its own, the same for that account every time. Read on each bind. */
    public static final BooleanSetting COLOR_COMMENTER_NAMES = new BooleanSetting("color_commenter_names", FALSE);
    /** Tapping "more" under a video opens its comments with the caption at the top (upstream #156). */
    public static final BooleanSetting CAPTION_OPENS_COMMENTS = new BooleanSetting("caption_opens_comments", FALSE);
    /** Every video's comments open with its caption at the top. */
    public static final BooleanSetting CAPTION_ABOVE_COMMENTS = new BooleanSetting("caption_above_comments", FALSE);
    public static final BooleanSetting HIDE_COMMENT_SEARCH_SUGGESTIONS =
            new BooleanSetting("hide_comment_search_suggestions", FALSE, true);
    public static final BooleanSetting COMPACT_COMMENT_HEADER =
            new BooleanSetting("compact_comment_header", FALSE, true);
    public static final BooleanSetting LARGER_COMMENT_LIKE_TARGET =
            new BooleanSetting("larger_comment_like_target", FALSE, true);
    public static final BooleanSetting HIDE_COMMENT_EGGS = new BooleanSetting("hide_comment_eggs", FALSE);
    public static final BooleanSetting COMMENT_SORT_CONTROLS = new BooleanSetting("comment_sort_controls", FALSE);
    public static final BooleanSetting COMMENTS_FULL_HEIGHT = new BooleanSetting("comments_full_height", FALSE);
    // Share sheet tools.
    public static final BooleanSetting HIDE_SHARE_CONTACTS = new BooleanSetting("hide_share_contacts", FALSE);
    public static final BooleanSetting SHARE_TAP_COPIES_LINK = new BooleanSetting("share_tap_copies_link", FALSE);
    public static final StringSetting SHARE_HIDDEN_ITEMS = new StringSetting("share_hidden_items", "");
    // The profile and LIVE sheets keep lists of their own. Until one is saved it holds this
    // marker and follows SHARE_HIDDEN_ITEMS, so a list chosen before the split keeps working on
    // every sheet, unknown identifiers included.
    public static final String SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO = "@video";
    public static final StringSetting SHARE_HIDDEN_ITEMS_PROFILE =
            new StringSetting("share_hidden_items_profile", SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO);
    public static final StringSetting SHARE_HIDDEN_ITEMS_LIVE =
            new StringSetting("share_hidden_items_live", SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO);
    public static final StringSetting SHARE_ACTION_CATALOG = new StringSetting("share_action_catalog", "");
    // Profile shortcuts (#49): the pills under a profile's bio. Names typed to hide, comma separated;
    // the keys picked in the checklist, kept apart so neither rewrites the other; and the ones
    // TikTok has sent to this phone, which the checklist offers.
    public static final StringSetting HIDDEN_PROFILE_SHORTCUTS = new StringSetting("hidden_profile_shortcuts", "");
    public static final StringSetting PROFILE_SHORTCUT_PICKS = new StringSetting("profile_shortcut_picks", "");
    public static final StringSetting PROFILE_SHORTCUT_CATALOG = new StringSetting("profile_shortcut_catalog", "");
    /** The Thoughts bubble above a profile picture (#122). Read when a profile's picture is built. */
    public static final BooleanSetting HIDE_PROFILE_THOUGHTS = new BooleanSetting("hide_profile_thoughts", FALSE, true);
    /** TikTok's two Thoughts gates answer yes (#186). A restart, since TikTok keeps what it built. */
    public static final BooleanSetting ENABLE_THOUGHTS = new BooleanSetting("enable_thoughts", FALSE, true);
    // Popup labels (TikTok's own popup layer): the labels ticked in the checklist, and the ones
    // TikTok has tried to show on this phone, which the checklist offers.
    public static final StringSetting POPUP_LABEL_PICKS = new StringSetting("popup_label_picks", "");
    public static final BooleanSetting HIDE_LIVE_BUBBLE = new BooleanSetting("hide_live_bubble", FALSE);
    // TikTok's bedtime wind-down, breathing exercise and daily-limit screens over the feed, kept
    // back only on an account TikTok treats as an adult's (WindDownScreens).
    public static final BooleanSetting HIDE_WIND_DOWN_SCREENS = new BooleanSetting("hide_wind_down_screens", FALSE);
    public static final StringSetting POPUP_LABEL_CATALOG = new StringSetting("popup_label_catalog", "");
    // "Follows you" under the @username on a profile, and a mark on the follow list accounts that don't follow back.
    public static final BooleanSetting SHOW_FOLLOW_STATUS = new BooleanSetting("show_follow_status", TRUE);
    // "Joined Jan 2020" in the same spot on someone else's profile, read from the account's ID.
    public static final BooleanSetting SHOW_JOIN_DATE = new BooleanSetting("show_join_date", FALSE);
    public static final BooleanSetting LOCAL_PROFILE_COUNTS = new BooleanSetting("local_profile_counts", FALSE, true);
    public static final BooleanSetting LOCAL_PROFILE_VERIFIED_BADGE = new BooleanSetting("local_profile_verified_badge", FALSE, true);
    public static final BooleanSetting HIDE_OWN_PROFILE_RESTRICTION_NOTICE = new BooleanSetting("hide_own_profile_restriction_notice", FALSE, true);
    // Account-scoped presentation choices stay on this phone, apart from portable settings backups.
    public static final StringSetting LOCAL_PROFILE_COUNT_VALUES =
            new StringSetting("local_profile_count_values", "", true, false);
    // Long-press a bio to copy it, and copy buttons for a profile's or a video's IDs on the share sheet.
    public static final BooleanSetting COPY_IDS = new BooleanSetting("copy_ids", TRUE);
    // An Account facts button on a profile's share sheet, from what TikTok already sent about the account.
    public static final BooleanSetting ACCOUNT_FACTS = new BooleanSetting("account_facts", FALSE);
    // @usernames in a profile's bio open those profiles on a tap (#247).
    public static final BooleanSetting BIO_MENTION_LINKS = new BooleanSetting("bio_mention_links", TRUE);
    // Package names of the apps added to the Share via row, comma separated, in the order picked.
    public static final StringSetting SHARE_ADDED_APPS = new StringSetting("share_added_apps", "");
    public static final BooleanSetting DISABLE_LONG_PRESS_QUICK_SHARE =
            new BooleanSetting("disable_long_press_quick_share", FALSE);
    public static final BooleanSetting DISABLE_LONG_PRESS_REPOST =
            new BooleanSetting("disable_long_press_repost", FALSE);
    public static final BooleanSetting ENABLE_NON_PERSONALIZED_SEARCH =
            new BooleanSetting("enable_non_personalized_search", FALSE, true);
    public static final BooleanSetting ENABLE_LIVE_SEARCH =
            new BooleanSetting("enable_live_search", FALSE, true);
    public static final BooleanSetting SIM_SPOOF = new BooleanSetting("simspoof", FALSE, true);
    public static final StringSetting SIM_SPOOF_ISO = new StringSetting("simspoof_iso", "us");
    public static final StringSetting SIMSPOOF_MCCMNC = new StringSetting("simspoof_mccmnc", "310260");
    public static final StringSetting SIMSPOOF_OP_NAME = new StringSetting("simspoof_op_name", "T-Mobile");
    // Read by the region patch's request hooks as each For You request is built, so no restart.
    public static final BooleanSetting SIM_SPOOF_FEED_REAL =
            new BooleanSetting("simspoof_feed_real", FALSE, false, Setting.parent(SIM_SPOOF));
    // Network proxy. Off by default, and every value is read once as TikTok starts, before its
    // network stack is built, so each one takes a restart. The user name and password stay out
    // of backups: a backup file is easy to pass around.
    public static final BooleanSetting NETWORK_PROXY = new BooleanSetting("network_proxy", FALSE, true);
    public static final StringSetting NETWORK_PROXY_TYPE = new StringSetting(
            "network_proxy_type", "http", true, Setting.parent(NETWORK_PROXY));
    public static final StringSetting NETWORK_PROXY_HOST = new StringSetting(
            "network_proxy_host", "", true, Setting.parent(NETWORK_PROXY));
    public static final StringSetting NETWORK_PROXY_PORT = new StringSetting(
            "network_proxy_port", "", true, Setting.parent(NETWORK_PROXY));
    public static final StringSetting NETWORK_PROXY_USER = new StringSetting(
            "network_proxy_user", "", true, false, null, Setting.parent(NETWORK_PROXY));
    public static final StringSetting NETWORK_PROXY_PASSWORD = new StringSetting(
            "network_proxy_password", "", true, false, null, Setting.parent(NETWORK_PROXY));

    /**
     * Made once per install so a hashed account id in a diagnostic report cannot be checked
     * against a guess. Never shown, never exported: it is excluded from the backup for the
     * same reason the installation bookkeeping is.
     */
    public static final StringSetting DIAGNOSTIC_REPORT_SALT = new StringSetting(
            "diagnostic_report_salt",
            "",
            false,
            false
    );

    static {
        // Hushfeed's own state: remembered positions and choices, lists it observed, counters.
        // They keep their values while Hushfeed is paused; every other setting answers its
        // unpatched value then (Setting#get).
        Setting.keepWhenPaused(LAUNCHER_SHORTCUTS_REMOVED, FEED_NAVIGATION_OBSERVED_TABS,
                BOTTOM_NAVIGATION_OBSERVED_TABS, DOWNLOAD_PATH, DOWNLOAD_PATHS_MIGRATED,
                REMEMBERED_SPEED, SESSION_BUDGET_STATE, BLOCK_AUTHOR_BUTTON_POSITION,
                LOCAL_HIDE_BUTTON_POSITION, BLOCK_SOUND_BUTTON_POSITION, NOT_INTERESTED_BUTTON_POSITION,
                FEED_MUTE_BUTTON_POSITION, COMMENT_TRANSLATE_BUTTON_TURNED_ON,
                SHARE_ACTION_CATALOG, PROFILE_SHORTCUT_CATALOG, POPUP_LABEL_CATALOG, DIAGNOSTIC_REPORT_SALT, AUTO_STREAK_STATE,
                // The budget's day is worked out from this hour. Paused, the budget counts
                // nothing and holds nothing, but its record still has to name the right day.
                SESSION_BUDGET_RESET_HOUR);
        // Guests cannot use Profile to reach settings. Keep their chosen Home shortcut so
        // they can reopen settings and resume Hushfeed after leaving the Pause screen.
        Setting.keepWhenPaused(HOME_TAB_OPENS_SETTINGS);
        // A lock that Pause turned off would open to anyone who can make the safe-mode file,
        // which any file manager can. The lock stays what the owner set.
        Setting.keepWhenPaused(APP_LOCK, APP_LOCK_TIMEOUT);
        // Downloads rewrite TikTok's own save folder and file name with no switch in front, so
        // pausing cannot give TikTok its own back. They keep the reader's choice instead of
        // falling back to Hushfeed's defaults. The README lists them as not paused.
        Setting.keepWhenPaused(DOWNLOAD_VIDEO_PATH, DOWNLOAD_PHOTO_PATH, DOWNLOAD_STICKER_PATH,
                DOWNLOAD_STICKER_FORMAT, DOWNLOAD_VIDEO_FILENAME_TEMPLATE, DOWNLOAD_PHOTO_FILENAME_TEMPLATE,
                DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE);

        if (!DOWNLOAD_PATHS_MIGRATED.savedValue()) {
            String legacyPath = DOWNLOAD_PATH.savedValue();
            DOWNLOAD_VIDEO_PATH.save(legacyPath);
            DOWNLOAD_PHOTO_PATH.save(legacyPath);
            DOWNLOAD_STICKER_PATH.save(legacyPath);
            DOWNLOAD_PATHS_MIGRATED.save(TRUE);
        }
        migrateCaptionWeight();
    }

    /** A Normal-weight captions switch left on becomes Caption boldness's Normal, once. */
    static void migrateCaptionWeight() {
        if (!CAPTION_REGULAR_WEIGHT.savedValue()) return;
        if ("default".equals(CAPTION_FONT_WEIGHT.savedValue())) CAPTION_FONT_WEIGHT.save("400");
        CAPTION_REGULAR_WEIGHT.save(FALSE);
    }

    /**
     * Called from the patched share guide method. Returning true stops the share prompt that
     * pops up after a like. Upstream #22.
     */
    public static boolean shouldHideShareGuide() {
        return HIDE_SHARE_GUIDE.get();
    }
}
