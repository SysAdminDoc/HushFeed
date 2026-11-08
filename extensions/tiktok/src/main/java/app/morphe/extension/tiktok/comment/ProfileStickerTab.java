/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Stickers tab on a profile (#214).
 *
 * <p>Up to TikTok 47.6.3 the tab showed whenever the profile said so, through
 * {@code user.getTabSetting().stickerTab.showStickerTab}. 47.7.3 put an experiment in front of that
 * ({@code tt_im_ugc_sticker_set}): for accounts in it, the tab shows only when a sticker set cache
 * says the profile owns a curated set, so an account with its own stickers lost the tab when it
 * updated. The patch hands that answer here as the gate returns it, and the profile's own flag
 * counts again.
 *
 * <p>The tab's page asks the same experiment again and opens TikTok's curated set page, which only
 * says "Something went wrong" for a profile with no curated set. The gate's answer is remembered
 * per profile, so such a profile gets the sticker sets and video stickers page it had on 47.6.3.
 */
public final class ProfileStickerTab {
    private static final int REMEMBERED = 64;

    /** The gate's owned-set answer by uid, the most recent profiles only. */
    private static final Map<String, Boolean> OWNED = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBERED;
                }
            });

    private ProfileStickerTab() {}

    /** Whether the profile gets its Stickers tab, given TikTok's {@code owned} answer (non-zero is yes). */
    public static boolean show(int owned, Object user) {
        try {
            Object uid = user == null ? null : Reflect.property(user, "getUid", "uid");
            if (uid instanceof String) OWNED.put((String) uid, owned != 0);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not note the profile's sticker set", error);
        }
        if (owned != 0) return true;
        try {
            if (user == null || HushfeedPause.isPaused()) return false;
            Object tabs = Reflect.property(user, "getTabSetting", "tabSetting");
            Object stickers = tabs == null ? null : Reflect.readField(tabs, "stickerTab");
            return stickers != null && Boolean.TRUE.equals(Reflect.readField(stickers, "showStickerTab"));
        } catch (Throwable error) {
            Logger.printException(() -> "Could not read the profile's Stickers tab", error);
            return false;
        }
    }

    /**
     * Whether the Stickers tab opens TikTok's curated set page, given TikTok's {@code curated} answer
     * (non-zero is yes). A profile the gate said owns no set gets the sticker sets page instead.
     */
    public static boolean curatedPage(int curated, String uid) {
        if (curated == 0) return false;
        if (uid == null || HushfeedPause.isPaused()) return true;
        Boolean owned = OWNED.get(uid);
        return owned == null || owned;
    }

    static void forgetForTests() {
        OWNED.clear();
    }
}
