/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Opens a creator's Story from the picture on their profile, or on their chat in the Inbox, while
 * they're LIVE (#233).
 *
 * <p>That picture is an AvatarComponentView, which keeps what it shows in a map keyed by an enum
 * (LIVE, STORY and the rest). Its tap asks the map for LIVE first and only then for a story, so a
 * creator who is LIVE never shows their Story from there. The patch calls {@link #openStory}
 * right before that LIVE check. With the switch on, a LIVE creator with a Story gets the Story:
 * the map's STORY entry holds TikTok's own story opener, the View.OnClickListener the picture
 * runs when no LIVE wins, and it's handed the tapped view. The next tap on the same picture is
 * left to TikTok, which opens the LIVE, so the LIVE stays one tap away.
 *
 * <p>A chat's picture in the Inbox works the same way through {@link #openChatStory}, except that
 * its tap checks the chat row's LIVE room id instead of the map, so that's where the hook sits.
 *
 * <p>The map only holds what wins each part of the picture, and LIVE outranks STORY there, so a
 * LIVE creator's map usually has no STORY entry at all. TikTok still keeps every contender: the
 * picture's delegate (getAvatarDelegate) has a view model whose state machine lists, for each
 * part, every candidate with its business and state. When the map has no Story, the opener comes
 * from a STORY candidate there ({@link #rankedOutStoryOpener}).
 *
 * <p>The enum and the story state are renamed every build, so both are found by what they hold:
 * the enum constants keep their names, and the state's opener is its one field that is a
 * View.OnClickListener. The state machine is the view model's getter whose type keeps its
 * candidates in ConcurrentHashMaps, and the candidate lists are the map whose values are lists.
 */
public final class StoryOverLive {
    private static final String LIVE = "LIVE";
    private static final String STORY = "STORY";
    static final String BINDING_UID_METHOD = "getUid";
    /** AvatarComponentView's private getter for the delegate that owns its view model. */
    static final String DELEGATE_GETTER = "getAvatarDelegate";
    private static final String VIEW_MODEL = "androidx.lifecycle.ViewModel";

    /** The picture the last Story opened from, so the next tap there goes to the LIVE. */
    private static WeakReference<View> storyOpenedFrom = new WeakReference<>(null);
    private static String storyOpenedFor;
    private static String storyOpenedBy;

    private StoryOverLive() {}

    /**
     * Called by the profile picture's tap ahead of TikTok's LIVE check, with the picture's map
     * and the tapped view. True means the Story opened and TikTok's own handling is skipped.
     */
    public static boolean openStory(Map<?, ?> business, View avatar) {
        try {
            if (!Settings.OPEN_STORY_OVER_LIVE.get() || business == null || avatar == null) {
                return false;
            }
            return open(business, avatar, holds(business, LIVE), null, null);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the Story from the profile picture", ex);
            return false;
        }
    }

    /**
     * Called by a chat's picture in the Inbox when the chat row says the creator is LIVE, right
     * before TikTok opens the LIVE. That row keeps the LIVE in its own field rather than the
     * picture's map, so the call itself means LIVE, and the map is read off the picture for its
     * STORY entry. True means the Story opened and the LIVE open is skipped.
     */
    public static boolean openChatStory(View avatar) {
        try {
            if (!Settings.OPEN_STORY_OVER_LIVE.get() || avatar == null) {
                reset();
                return false;
            }
            String uid = boundUid(avatar);
            String account = SignedInUser.id();
            if (uid == null || account == null) {
                reset();
                return false;
            }
            if (storyOpenedFrom.get() != avatar || !Objects.equals(storyOpenedFor, uid) ||
                    !Objects.equals(storyOpenedBy, account)) {
                reset();
            }
            Map<?, ?> business = business(avatar);
            return business != null && open(business, avatar, true, uid, account);
        } catch (Throwable ex) {
            reset();
            Logger.printException(() -> "Could not open the Story from the Inbox", ex);
            return false;
        }
    }

    private static boolean open(Map<?, ?> business, View avatar, boolean live,
                                String uid, String account) throws ReflectiveOperationException {
        boolean secondTap = storyOpenedFrom.get() == avatar &&
                Objects.equals(storyOpenedFor, uid) && Objects.equals(storyOpenedBy, account);
        View.OnClickListener opener = storyOpener(business);
        if (opener == null && live && !secondTap) {
            opener = rankedOutStoryOpener(avatar);
            if (opener != null) Logger.printDebug(() -> "Story over LIVE: the Story came from TikTok's ranked-out candidates");
        }
        if (!storyFirst(live, opener != null, secondTap)) {
            if (secondTap) reset();
            return false;
        }
        opener.onClick(avatar);
        storyOpenedFrom = new WeakReference<>(avatar);
        storyOpenedFor = uid;
        storyOpenedBy = account;
        return true;
    }

    /** The UID assigned when TikTok binds this retained Inbox picture to a chat row. */
    static String boundUid(View avatar) throws ReflectiveOperationException {
        Object uid = avatar.getClass().getMethod(BINDING_UID_METHOD).invoke(avatar);
        return uid instanceof String && !((String) uid).isEmpty() ? (String) uid : null;
    }

    /** The picture's map of what it shows, from AvatarComponentView's getCurrentActiveBusiness. */
    static Map<?, ?> business(View avatar) throws ReflectiveOperationException {
        Object business = avatar.getClass().getMethod("getCurrentActiveBusiness").invoke(avatar);
        return business instanceof Map ? (Map<?, ?>) business : null;
    }

    /**
     * Whether a tap opens the Story. Only a creator who is LIVE needs it, since TikTok opens the
     * Story itself otherwise, and only when the Story's opener was found. The tap right after a
     * Story opened from the same picture goes to the LIVE.
     */
    static boolean storyFirst(boolean live, boolean story, boolean secondTap) {
        return live && story && !secondTap;
    }

    /** Forgets the picture the last Story opened from. */
    static void reset() {
        storyOpenedFrom = new WeakReference<>(null);
        storyOpenedFor = null;
        storyOpenedBy = null;
    }

    /** Whether the map has an entry under the enum constant with this name. */
    static boolean holds(Map<?, ?> business, String name) {
        return entryNamed(business, name) != null;
    }

    /** The View.OnClickListener the map's STORY entry holds, or null without one. */
    static View.OnClickListener storyOpener(Map<?, ?> business) throws IllegalAccessException {
        Map.Entry<?, ?> story = entryNamed(business, STORY);
        return story == null ? null : openerIn(story.getValue());
    }

    /**
     * The opener of a STORY candidate that lost its part of the picture to a higher one (LIVE),
     * read from the state machine behind the picture: delegate, view model, state machine, then
     * the candidate lists. Null when any step is missing or no STORY candidate holds an opener.
     */
    static View.OnClickListener rankedOutStoryOpener(View avatar) throws ReflectiveOperationException {
        Method delegateGetter = declaredGetter(avatar.getClass(), DELEGATE_GETTER);
        Object delegate = delegateGetter == null ? null : delegateGetter.invoke(avatar);
        Method modelGetter = delegate == null ? null : getterReturning(delegate.getClass(), StoryOverLive::isViewModel);
        Object model = modelGetter == null ? null : modelGetter.invoke(delegate);
        Method machineGetter = model == null ? null : getterReturning(model.getClass(), StoryOverLive::keepsConcurrentMaps);
        Object machine = machineGetter == null ? null : machineGetter.invoke(model);
        if (machine == null) return null;
        for (Field field : instanceFields(machine.getClass())) {
            Object value = field.get(machine);
            if (!(value instanceof ConcurrentHashMap)) continue;
            // TikTok edits the lists under this map's lock (X.0M6G), so they're read under it too.
            List<Object> candidates = new ArrayList<>();
            synchronized (value) {
                for (Object list : ((ConcurrentHashMap<?, ?>) value).values()) {
                    if (list instanceof List) candidates.addAll((List<?>) list);
                }
            }
            for (Object candidate : candidates) {
                if (candidate == null || !holdsEnumNamed(candidate, STORY)) continue;
                for (Field part : instanceFields(candidate.getClass())) {
                    View.OnClickListener opener = openerIn(part.get(candidate));
                    if (opener != null) return opener;
                }
            }
        }
        return null;
    }

    /** The first View.OnClickListener among a state's fields, its superclasses' included. */
    private static View.OnClickListener openerIn(Object state) throws IllegalAccessException {
        if (state == null || state instanceof Enum || state instanceof String) return null;
        for (Field field : instanceFields(state.getClass())) {
            Object value = field.get(state);
            if (value instanceof View.OnClickListener) return (View.OnClickListener) value;
        }
        return null;
    }

    /** Whether one of the object's fields holds the enum constant with this name. */
    private static boolean holdsEnumNamed(Object owner, String name) throws IllegalAccessException {
        for (Field field : instanceFields(owner.getClass())) {
            Object value = field.get(owner);
            if (value instanceof Enum && name.equals(((Enum<?>) value).name())) return true;
        }
        return false;
    }

    /** The non-static, non-primitive fields of a class and its superclasses, made readable. */
    private static List<Field> instanceFields(Class<?> owner) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return fields;
    }

    /** A no-argument method with this name on the class or a superclass, made callable. */
    private static Method declaredGetter(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    /** A check on a getter's return type (java.util.function is API 24, above the floor). */
    private interface TypeCheck {
        boolean test(Class<?> type);
    }

    /** The one no-argument instance method whose return type passes the check, or null. */
    private static Method getterReturning(Class<?> owner, TypeCheck returns) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                        || method.isSynthetic() || !returns.test(method.getReturnType())) continue;
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    /** AvatarControllerVM is the delegate's one getter that returns an AndroidX ViewModel. */
    private static boolean isViewModel(Class<?> type) {
        for (Class<?> step = type; step != null; step = step.getSuperclass()) {
            if (VIEW_MODEL.equals(step.getName())) return true;
        }
        return false;
    }

    /** The state machine keeps its candidates, winners and ranks in ConcurrentHashMaps. */
    private static boolean keepsConcurrentMaps(Class<?> type) {
        if (type.isPrimitive() || type.isInterface() || type.getName().startsWith("java.")) return false;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == ConcurrentHashMap.class) return true;
        }
        return false;
    }

    private static Map.Entry<?, ?> entryNamed(Map<?, ?> business, String name) {
        for (Map.Entry<?, ?> entry : business.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof Enum && name.equals(((Enum<?>) key).name())) return entry;
        }
        return null;
    }
}
