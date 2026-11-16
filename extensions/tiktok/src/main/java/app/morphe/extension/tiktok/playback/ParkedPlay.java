/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * TikTok's feed player holds a selected video back while the pager is scrolling and starts it
 * once the scroll ends: PlayerController keeps a flag that the pager's scroll callback raises and
 * its scroll-state callback lowers at rest, and a page selected while the flag is up is parked
 * there instead of played. The window's arrows (#413) move the pager from code, and the flag can
 * be up already (the pager reports a scroll when the window resizes it, with no rest after), so
 * the new video was parked with nothing to let it go and the window stayed black.
 *
 * <p>The flag and the parked play sit in an object under {@link #FIELD}, whose own fields TikTok
 * renames each build, so they're found by type: the boolean is the flag, and the parked play is
 * the field whose object carries the post. Lowering the flag goes through the controller's public
 * {@code onPageScrollStateChanged(0)}, the call TikTok makes itself when a swipe comes to rest,
 * which also starts a parked play of the video the player has on screen.
 */
final class ParkedPlay {
    /** PlayerController's field for the scroll-end hold. TikTok keeps this name unobfuscated. */
    static final String FIELD = "playOnScrollEndController";

    private static final int AT_REST = 0;

    private ParkedPlay() {
    }

    /** Whether the player holds new videos back as if the pager were scrolling, or null when unreadable. */
    static Boolean holding(Object controller) {
        Object hold = Reflect.readField(controller, FIELD);
        if (hold == null) return null;
        for (Field field : hold.getClass().getDeclaredFields()) {
            if (field.getType() != boolean.class || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                return field.getBoolean(hold);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /** The post a parked play waits to start, or null. TikTok leaves the last one in place after it plays. */
    static Aweme waiting(Object controller) {
        Object hold = Reflect.readField(controller, FIELD);
        if (hold == null) return null;
        for (Field field : hold.getClass().getDeclaredFields()) {
            Class<?> type = field.getType();
            if (type.isPrimitive() || Modifier.isStatic(field.getModifiers())) continue;
            if (controller.getClass() == type || type.isInstance(controller)) continue;
            Object parked;
            try {
                field.setAccessible(true);
                parked = field.get(hold);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                continue;
            }
            Aweme post = postIn(parked);
            if (post != null) return post;
        }
        return null;
    }

    private static Aweme postIn(Object parked) {
        if (parked == null) return null;
        for (Field field : parked.getClass().getDeclaredFields()) {
            if (!Aweme.class.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object post = field.get(parked);
                if (post instanceof Aweme) return (Aweme) post;
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /**
     * Tells the player its pager is at rest, as TikTok does when a swipe settles: the flag comes
     * down and a parked play of the video on screen starts.
     *
     * @return whether the player took the call
     */
    static boolean release(Object controller) {
        if (controller == null) return false;
        try {
            Method rest = controller.getClass().getMethod("onPageScrollStateChanged", int.class);
            rest.setAccessible(true);
            rest.invoke(controller, AT_REST);
            return true;
        } catch (NoSuchMethodException notTikToksPlayer) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Picture-in-picture couldn't settle TikTok's player", failure);
            return false;
        }
    }

    /** For the debug log: the flag, and whether a parked play names the video the player has on screen. */
    static String describe(Object controller, Object onScreen) {
        if (controller == null) return "no player";
        Aweme parked = waiting(controller);
        String held = parked == null ? "none"
                : sameAid(parked, onScreen) ? "this video" : "another video";
        return "hold " + holding(controller) + ", parked " + held;
    }

    static boolean sameAid(Object one, Object other) {
        Object aid = Reflect.invoke(one, "getAid");
        return aid instanceof String && !((String) aid).isEmpty() && aid.equals(Reflect.invoke(other, "getAid"));
    }
}
