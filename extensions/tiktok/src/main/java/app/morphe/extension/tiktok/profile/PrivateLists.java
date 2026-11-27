/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.app.Activity;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Yellow Followers and Following counts on a profile that keeps that list private (#549). Each
 * count's profile component holds TikTok's RelationData for it, whose following_visibility is 2
 * when the list is private: TikTok reads that same value before it shows its private list notice
 * on a tap of the count. The count's text passes through the profile count bridge on its way to
 * the screen, so the color goes on there. A public list, the Likes count, or the switch off leaves
 * TikTok's text as it was.
 *
 * The newer header keeps the data in ProfilePlatformProtocol's one BizBaseData field (each count's
 * protocol also keeps an older copy of its own to compare against, which isn't read). The older
 * header's RelationInfoBaseUIComponent keeps it in its one RelationData field. That one class shows
 * all three counts, so its Likes count is told apart by the component key it keeps.
 */
public final class PrivateLists {
    static final String PROTOCOL = "com.ss.android.ugc.profile.platform.standard.protocol.ProfilePlatformProtocol";
    static final String COMPONENT = "com.ss.android.ugc.profile.platform.business.header.business.info"
            + ".business.relationinfo.base.RelationInfoBaseUIComponent";
    static final String RELATION_DATA = "com.ss.android.ugc.profile.platform.business.header.business.info"
            + ".business.relationinfo.data.RelationData";
    static final String BIZ_DATA = "com.ss.android.ugc.profile.platform.base.data.BizBaseData";
    static final String LIKES = "com.ss.android.ugc.profile.business.ur.platform.business.RelationLikeProtocol";
    /** The Likes count's component key, which the older header's component keeps. */
    static final String LIKES_KEY = "relation_info_like";
    /** following_visibility for a list only its owner can open. */
    static final int PRIVATE = 2;
    /** Readable on TikTok's dark header and on its white one. */
    static final int YELLOW_ON_DARK = 0xFFFFCA28;
    static final int YELLOW_ON_LIGHT = 0xFFB58900;

    private static final Object NO_FIELD = new Object();
    /** Each owner class's data field, or NO_FIELD. */
    private static final Map<Class<?>, Object> FIELDS = new ConcurrentHashMap<>();
    /** Each owner class's String fields, where the older header's component keeps its key. */
    private static final Map<Class<?>, Field[]> KEYS = new ConcurrentHashMap<>();

    private PrivateLists() {
    }

    static boolean enabled() {
        return SettingsStatus.followStatusEnabled && Settings.YELLOW_PRIVATE_LISTS.get()
                && !HushfeedPause.isPaused();
    }

    /** From the profile count bridge: the count's text about to show, yellow when its list is private. */
    public static CharSequence tint(Object owner, CharSequence text) {
        try {
            if (text == null || text.length() == 0 || owner == null || !enabled() || !isPrivate(owner)) return text;
            SpannableString yellow = new SpannableString(text);
            yellow.setSpan(new ForegroundColorSpan(dark() ? YELLOW_ON_DARK : YELLOW_ON_LIGHT),
                    0, yellow.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return yellow;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not color a private list's count", error);
            return text;
        }
    }

    /** TikTok's own theme, which its Display setting can set apart from the phone's. */
    private static boolean dark() {
        Activity activity = Utils.getActivity();
        return activity != null ? SettingsUi.isDarkContext(activity) : Utils.isDarkModeEnabled();
    }

    /** Whether the count [owner] shows is for a list TikTok says is private. */
    static boolean isPrivate(Object owner) {
        if (likes(owner)) return false;
        Object visibility = Reflect.property(relationData(owner), "getFollowingVisibility", "followingVisibility");
        return visibility instanceof Number && ((Number) visibility).intValue() == PRIVATE;
    }

    /**
     * Whether [owner] shows the Likes count: the newer header's likes protocol, or the older
     * header's one component class when its own key names the likes count.
     */
    static boolean likes(Object owner) {
        if (LIKES.equals(owner.getClass().getName())) return true;
        Field[] keys = KEYS.get(owner.getClass());
        if (keys == null) {
            keys = stringFields(owner.getClass());
            KEYS.put(owner.getClass(), keys);
        }
        for (Field key : keys) {
            try {
                if (LIKES_KEY.equals(key.get(owner))) return true;
            } catch (IllegalAccessException | RuntimeException ignored) {
                // A key that can't be read names nothing.
            }
        }
        return false;
    }

    private static Field[] stringFields(Class<?> type) {
        List<Field> found = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    found.add(field);
                } catch (RuntimeException ignored) {
                    // Left out: a field that can't be opened can't be read.
                }
            }
        }
        return found.toArray(new Field[0]);
    }

    /** The RelationData behind the count [owner] shows, or null. */
    @Nullable
    static Object relationData(Object owner) {
        Class<?> type = owner.getClass();
        Object field = FIELDS.get(type);
        if (field == null) {
            field = dataField(type);
            FIELDS.put(type, field);
        }
        if (!(field instanceof Field)) return null;
        try {
            Object data = ((Field) field).get(owner);
            return data != null && RELATION_DATA.equals(data.getClass().getName()) ? data : null;
        } catch (IllegalAccessException | RuntimeException error) {
            return null;
        }
    }

    /** The protocol's one BizBaseData field, or the older component's one RelationData field. */
    private static Object dataField(Class<?> type) {
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            String wanted = PROTOCOL.equals(at.getName()) ? BIZ_DATA
                    : COMPONENT.equals(at.getName()) ? RELATION_DATA : null;
            if (wanted == null) continue;
            Field found = null;
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !wanted.equals(field.getType().getName())) continue;
                if (found != null) {
                    String owner = at.getName();
                    Logger.printDebug(() -> "Private lists: " + owner + " holds more than one " + wanted);
                    return NO_FIELD;
                }
                found = field;
            }
            if (found == null) return NO_FIELD;
            try {
                found.setAccessible(true);
            } catch (RuntimeException error) {
                return NO_FIELD;
            }
            return found;
        }
        return NO_FIELD;
    }
}
