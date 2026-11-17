/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.view.View;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Folds the Likes list's like rows into one row per liked comment or video.
 *
 * <p>TikTok's server bundles likes only inside short time windows, so one comment that keeps
 * getting likes shows up as many rows ("A likes your comment", "B, C and 5 others like your
 * comment", ...). Every row carries its target in {@code template_notice.extra_data} and the
 * cell builds "A, B and N others" from the row's {@code from_users} and {@code merge_count}.
 * Keeping the newest row of each target, adding the older rows' counts to it and listing its
 * newest likers first therefore gives TikTok's own cell the whole count, with nothing invented.
 *
 * <p>The fold runs on each page before the view model builds its list, so TikTok's diffing,
 * unread markers, paging and click positions all see one consistent list. A page loaded with
 * {@code LOAD_MORE} continues the folds of the pages before it; every other load reason
 * rebuilds the list and starts over.
 */
public final class ActivityLikeBundler {
    /** {@code GroupFilterStruct.noticeGroup} of "Likes and saved posts". */
    static final int LIKES_GROUP = 3;
    /** {@code BaseNotice.type} of a like. */
    static final int LIKE_TYPE = 41;
    /** The server lists at most this many likers on a row. */
    static final int MAX_USERS = 5;
    private static final String APPEND_REASON = "LOAD_MORE";

    /** After a later page folds into rows already on screen, how long to wait before redrawing them. */
    private static final long REDRAW_DELAY_MS = 250;

    private static final Map<Object, Map<String, Object>> KEPT =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** The notice ids already counted in each view model's list, so a page that comes back adds nothing. */
    private static final Map<Object, Set<String>> COUNTED =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** The list view each view model's rows were last drawn in. */
    private static final Map<Object, WeakReference<View>> LISTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ActivityLikeBundler() {}

    /**
     * Called first in TemplateNotificationCell's bind with (cell, item). Remembers the list view
     * that draws a kept like row, so a later page that adds to that row can have it redrawn.
     */
    public static void onRowBound(Object cell, Object item) {
        try {
            if (KEPT.isEmpty() || !Settings.BUNDLE_ACTIVITY_LIKES.get()) return;
            Object notice = InboxQueryText.notice(item);
            if (notice == null) return;
            Object owner = null;
            synchronized (KEPT) {
                for (Map.Entry<Object, Map<String, Object>> entry : KEPT.entrySet()) {
                    if (containsIdentity(entry.getValue(), notice)) {
                        owner = entry.getKey();
                        break;
                    }
                }
            }
            if (owner == null) return;
            Object list = Reflect.invoke(cell, "getAttachView$powerlist_release");
            if (list instanceof View) LISTS.put(owner, new WeakReference<>((View) list));
        } catch (Throwable failure) {
            Logger.printException(() -> "Group likes per comment: row bind skipped", failure);
        }
    }

    private static boolean containsIdentity(Map<String, Object> kept, Object notice) {
        for (Object value : kept.values()) if (value == notice) return true;
        return false;
    }

    /**
     * TikTok's list compares a row by its object, and a folded row keeps its object, so the list
     * would keep showing the old count. Every row is marked changed instead; cached rows get
     * rebound too, so none comes back from the cache with the old count.
     */
    private static void redraw(Object viewModel) {
        WeakReference<View> reference = LISTS.get(viewModel);
        View list = reference == null ? null : reference.get();
        if (list == null) return;
        list.postDelayed(() -> {
            try {
                Object adapter = Reflect.invoke(list, "getAdapter");
                Object count = Reflect.invoke(adapter, "getItemCount");
                if (!(count instanceof Integer) || (Integer) count <= 0) return;
                adapter.getClass().getMethod("notifyItemRangeChanged", int.class, int.class)
                        .invoke(adapter, 0, count);
            } catch (Throwable failure) {
                Logger.printException(() -> "Group likes per comment: redraw skipped", failure);
            }
        }, REDRAW_DELAY_MS);
    }

    /** Called first in NotificationResultViewModel's page handler (response, load reason). */
    public static void onPage(Object viewModel, Object response, Object reason) {
        try {
            if (viewModel == null || response == null) return;
            if (!Settings.BUNDLE_ACTIVITY_LIKES.get()) {
                KEPT.remove(viewModel);
                COUNTED.remove(viewModel);
                return;
            }
            Object group = Reflect.invoke(viewModel, "getGroupType");
            if (!(group instanceof Integer) || (Integer) group != LIKES_GROUP) return;
            boolean append = reason instanceof Enum && APPEND_REASON.equals(((Enum<?>) reason).name());
            Map<String, Object> kept;
            Set<String> counted;
            synchronized (KEPT) {
                kept = append ? KEPT.get(viewModel) : null;
                counted = append ? COUNTED.get(viewModel) : null;
                if (kept == null || counted == null) {
                    kept = new HashMap<>();
                    counted = new HashSet<>();
                    KEPT.put(viewModel, kept);
                    COUNTED.put(viewModel, counted);
                }
            }
            Object lists = Reflect.readField(response, "notices");
            if (!(lists instanceof List)) return;
            // Rows kept from earlier pages are already on screen; a fold into one needs a redraw.
            Map<String, Object> earlier = append ? new HashMap<>(kept) : Collections.emptyMap();
            int foldedIntoEarlier = 0;
            for (Object items : (List<?>) lists) {
                Object notices = Reflect.readField(items, "items");
                if (notices instanceof List) {
                    @SuppressWarnings("unchecked") List<Object> page = (List<Object>) notices;
                    foldedIntoEarlier += fold(page, kept, earlier, counted);
                }
            }
            if (foldedIntoEarlier > 0) redraw(viewModel);
        } catch (Throwable failure) {
            Logger.printException(() -> "Group likes per comment: page skipped", failure);
        }
    }

    /**
     * Removes each like row whose target already has a kept row, adding its count and likers to
     * that row. Rows arrive newest first, so the kept row of a target is its newest one.
     * Returns how many rows were folded into a row of {@code earlier}, the rows kept by previous
     * pages, which are already on screen.
     *
     * <p>{@code counted} holds the ids of the notices already counted in this list. A notice that
     * shows up again, on a page that overlaps or repeats an earlier one, is dropped without adding
     * its likes a second time.
     */
    static int fold(List<Object> page, Map<String, Object> kept, Map<String, Object> earlier, Set<String> counted)
            throws ReflectiveOperationException {
        int foldedIntoEarlier = 0;
        for (Iterator<Object> rows = page.iterator(); rows.hasNext(); ) {
            Object row = rows.next();
            String target = targetOf(row);
            if (target == null) continue;
            Object first = kept.get(target);
            if (first == row) continue;
            String id = text(Reflect.readField(row, "nid"));
            if (id != null && !counted.add(id)) {
                rows.remove();
                continue;
            }
            if (first == null) {
                kept.put(target, row);
            } else if (first != row && absorb(first, row)) {
                rows.remove();
                if (earlier.get(target) == first) foldedIntoEarlier++;
            }
        }
        return foldedIntoEarlier;
    }

    /** "subType:videoId:commentId" for a like row with a known target, otherwise null. */
    static String targetOf(Object row) {
        Object type = Reflect.readField(row, "type");
        if (!(type instanceof Integer) || (Integer) type != LIKE_TYPE) return null;
        Object template = Reflect.readField(row, "templateNotice");
        if (template == null) return null;
        Object extra = Reflect.readField(template, "extraData");
        Object comment = Reflect.readField(extra, "comment");
        String commentId = text(Reflect.readField(comment, "cid"));
        // A comment like without its comment's id can't be told from a like on another comment.
        if (comment != null && commentId == null) return null;
        String videoId = text(Reflect.invoke(Reflect.readField(extra, "aweme"), "getAid"));
        if (videoId == null) videoId = text(Reflect.readField(comment, "awemeId"));
        if (videoId == null && commentId == null) return null;
        // Saved posts share this list; the legacy sub type keeps a save from folding into a like.
        return Reflect.readField(template, "legacySubType") + ":" + videoId + ":" + commentId;
    }

    /** Adds the older row's count and likers to the kept row. False leaves both rows as they were. */
    static boolean absorb(Object kept, Object older) throws ReflectiveOperationException {
        Object keptTitle = titleOf(kept), olderTitle = titleOf(older);
        if (keptTitle == null || olderTitle == null) return false;
        Object keptCount = Reflect.readField(keptTitle, "mergeCount");
        Object olderCount = Reflect.readField(olderTitle, "mergeCount");
        if (!(keptCount instanceof Integer) || !(olderCount instanceof Integer)) return false;
        Object keptUsers = Reflect.readField(keptTitle, "fromUsers");
        Object olderUsers = Reflect.readField(olderTitle, "fromUsers");

        List<Object> users = new ArrayList<>();
        if (keptUsers instanceof List) users.addAll((List<?>) keptUsers);
        if (olderUsers instanceof List) {
            for (Object user : (List<?>) olderUsers) {
                if (users.size() >= MAX_USERS) break;
                if (!containsUser(users, user)) users.add(user);
            }
        }
        // A row counts at least the likers it names.
        int count = Math.max((Integer) keptCount, 1) + Math.max((Integer) olderCount, 1);
        write(keptTitle, "mergeCount", count);
        write(keptTitle, "fromUsers", users);
        if (Boolean.FALSE.equals(Reflect.readField(older, "hasRead"))) {
            write(kept, "hasRead", false);
            Object template = Reflect.readField(kept, "templateNotice");
            if (template != null && Reflect.readField(template, "hasRead") instanceof Boolean) {
                write(template, "hasRead", false);
            }
        }
        return true;
    }

    private static Object titleOf(Object row) {
        Object ui = Reflect.readField(Reflect.readField(row, "templateNotice"), "uiTemplate");
        return Reflect.readField(ui, "titleTemplate");
    }

    private static boolean containsUser(List<Object> users, Object user) {
        String id = text(Reflect.readField(user, "uid"));
        for (Object present : users) {
            if (present == user) return true;
            if (id != null && id.equals(text(Reflect.readField(present, "uid")))) return true;
        }
        return false;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String string = value.toString();
        return string.isEmpty() || "0".equals(string) ? null : string;
    }

    /** Writes an instance field of a row TikTok just received; a static field is refused, not written. */
    private static void write(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = Reflect.field(target.getClass(), name);
        if (field == null || Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
