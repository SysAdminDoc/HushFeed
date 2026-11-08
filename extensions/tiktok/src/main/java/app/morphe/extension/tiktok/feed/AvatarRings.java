/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Takes the story ring and the pulsing LIVE ring off profile pictures, on the feed, in comments
 * and on profiles each on its own switch (#351). The Inbox, search and other lists follow the two
 * ring switches alone. The stories trays (the Inbox's top row and the Following and Friends bars)
 * keep their story rings: TikTok drops anyone whose status there reads "no story" from the tray, so
 * a 0 answer emptied it.
 *
 * <p>Story rings: every profile picture with a ring belongs to TikTok's social avatar entry, which
 * turns the user's story status (1 unseen, 2 seen, 3 a hidden profile badge) into the ring it
 * draws, and knows the screen it sits on (feed, comment, my profile, someone's profile, Inbox and
 * more, an enum whose names survive R8). The patch hands that status and screen here first, and a
 * 0 answer is the status of a user with no story, so no ring is drawn and a tap opens the profile.
 * The feed avatar also attaches its story assem only when {@code User.getStoryStatus} says 1, so
 * that getter is filtered for the feed too.
 *
 * <p>LIVE rings: the feed avatar attaches FeedAvatarLiveAssem, which draws the ring and sends a tap
 * into the room, only when TikTok's author live check answers yes. That check is a static
 * {@code (Aweme, User)Z} method R8 renames each build; the patch finds it through the feed
 * avatar's bind method and filters what it returns. Every other picture asks
 * AvatarLiveDataAdapter, which TikTok builds with a scene number for profiles (1) and chats (8)
 * and with none for comments and the other lists. The patch asks here at the top of its status
 * method, and a yes answers "not live" before TikTok looks.
 */
public final class AvatarRings {
    /** AvatarLiveDataAdapter's scene numbers; NO_SCENE is the patch's stand-in for none. */
    static final int NO_SCENE = -1;
    static final int PROFILE_SCENE = 1;

    private static volatile int loggedScenes;

    private AvatarRings() {}

    /** What {@code User.getStoryStatus} answers; only the feed's story assem still keys on it. */
    public static int storyStatus(int status) {
        return Settings.HIDE_STORY_RINGS.get() && Settings.HIDE_RINGS_FEED.get() ? 0 : status;
    }

    /**
     * The story status the social avatar entry turns into a ring, given the screen it sits on, an
     * enum constant.
     */
    public static int storyStatusIn(int status, Object scene) {
        if (status == 0 || !Settings.HIDE_STORY_RINGS.get()) return status;
        String name = scene instanceof Enum ? ((Enum<?>) scene).name() : "";
        return reaches(name) ? 0 : status;
    }

    /** What TikTok's feed avatar live check answers. */
    public static boolean authorLive(boolean live) {
        return live && !(Settings.HIDE_LIVE_RING.get() && Settings.HIDE_RINGS_FEED.get());
    }

    /** Whether AvatarLiveDataAdapter, built for {@code scene}, answers "not live" straight away. */
    public static boolean hideLiveIn(int scene) {
        if (!Settings.HIDE_LIVE_RING.get()) return false;
        logScene(scene);
        if (scene == PROFILE_SCENE) return Settings.HIDE_RINGS_PROFILES.get();
        if (scene == NO_SCENE) return Settings.HIDE_RINGS_COMMENTS.get();
        return true;
    }

    /** Whether the ring switches reach the social avatar screen named {@code scene}. */
    static boolean reaches(String scene) {
        switch (scene) {
            case "FEED":
                return Settings.HIDE_RINGS_FEED.get();
            case "COMMENT":
                return Settings.HIDE_RINGS_COMMENTS.get();
            case "MY_PROFILE":
            case "OTHER_PROFILE":
                return Settings.HIDE_RINGS_PROFILES.get();
            case "INBOX_TOP_LIST":
            case "FOLLOW_SKYLIGHT":
            case "FRIENDS_SKYLIGHT":
                return false;
            default:
                return true;
        }
    }

    /** One debug line the first time each LIVE scene number shows up, for the export. */
    private static void logScene(int scene) {
        int bit = scene >= -1 && scene < 30 ? 1 << (scene + 1) : 1 << 31;
        if ((loggedScenes & bit) != 0) return;
        loggedScenes |= bit;
        Logger.printDebug(() -> "LIVE ring asked for scene " + scene);
    }
}
