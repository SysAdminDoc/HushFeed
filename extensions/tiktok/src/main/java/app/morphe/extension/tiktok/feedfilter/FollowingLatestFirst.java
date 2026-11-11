/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Latest first on the Following feed (#226): the posts below the video the reader is on play
 * newest first. With many follows TikTok fills the list over several reads, so a newer post often
 * arrives after older ones were placed. Everything below the current video is sorted together on
 * each read that changed the list, earlier reads' posts included, so a late newer post rises to
 * just below the reader. The current video and everything above it never move: the one playing
 * stays, and scrolling back up shows the same order. TikTok's load-more hands the pager the whole
 * list again and finds the current video in it, which is what carries the new order to the screen.
 * Ads, LIVEs and posts with no date keep their places. A card that isn't a post (the "You're all
 * caught up" divider) also splits the sort, so a suggested post after it never moves above it.
 * The pager also loads further ahead on Following, so each sort has more posts to order.
 */
public final class FollowingLatestFirst {
    /** Lists remembered at once, in the order they were last read, so the one on screen stays. */
    private static final int MAX_OWNERS = 64;
    /** Past this many entries a list is left in TikTok's order. */
    private static final int MAX_SLOTS = 4096;
    /**
     * Videos remembered as having been on screen, most recent last. Enough that an evening on
     * For You doesn't push out the Following video the reader left off on.
     */
    private static final int MAX_CURRENT = 1024;

    /**
     * Posts the first Following read asks for. TikTok asks for 20, and with a few hundred follows
     * it then fills the list over several reads within a second, so a newer post can arrive after
     * the video in slot 0 started playing. A larger first read brings the posts that decide slot
     * 0 in together.
     */
    static final int FIRST_READ_COUNT = 40;
    /**
     * Posts every later read asks for. A post that arrives in a later read can only rise to just
     * below the video the reader is on, so the fewer reads a long list takes, the fewer times a
     * newer post turns up after older ones were already scrolled past (#226).
     */
    static final int LATER_READ_COUNT = 30;

    /**
     * Posts left below the reader on Following when the next read is asked for. TikTok asks with 3
     * left, and the server sends about 3 a read whatever count is asked (#226), so a newer post
     * often turned up after the reader had passed older ones. With this many waiting, a few reads'
     * posts are sorted together before the reader reaches them.
     */
    static final int LOAD_AHEAD = 12;

    private static final String FOLLOWING_PANEL =
        "com.ss.android.ugc.aweme.feed.panel.FollowFeedFragmentPanelMT";

    /** The Following panel the last read went to, and its list's size then. */
    private static WeakReference<Object> deliveredPanel = new WeakReference<>(null);
    private static int deliveredSize = -1;

    private FollowingLatestFirst() {}

    /**
     * The count for a Following request, from the request builder just before the call goes out.
     * The first read (cursor 0) is raised to FIRST_READ_COUNT and later reads to LATER_READ_COUNT,
     * only with the switch on. A count already larger is left alone.
     */
    public static int requestCount(long cursor, int level, int count) {
        try {
            int wanted = cursor == 0 ? FIRST_READ_COUNT : LATER_READ_COUNT;
            if (count > 0 && count < wanted && enabled()) {
                // The S22 got about 3 posts back per read whatever it asked (#226): this tells a
                // read that never passed here from one the server cut short.
                Logger.printDebug(() -> "Latest first on Following: asked for " + wanted
                    + " posts instead of " + count + " at cursor " + cursor);
                return wanted;
            }
        } catch (Throwable unknown) {
            // The request goes out with TikTok's own count.
        }
        return count;
    }

    /**
     * The threshold TikTok's pager checks on every page select of every feed: it asks for the next
     * read once the reader is within {@code threshold} posts of the end. Raised to LOAD_AHEAD on
     * Following with the switch on; every other feed keeps TikTok's.
     */
    public static int loadAhead(Object panel, int threshold) {
        try {
            if (threshold < LOAD_AHEAD && enabled() && isFollowing(panel)) return LOAD_AHEAD;
        } catch (Throwable unknown) {
            // TikTok's own threshold.
        }
        return threshold;
    }

    /**
     * A read reached the Following panel, with the whole list and whether the server has more.
     * The pager only asks for the next read as the reader moves, so the first read's few posts
     * were all that waited below the first video. With the switch on this asks again once the
     * delivery is done, through the raised threshold, until LOAD_AHEAD posts wait below the
     * reader. A read that added nothing stops it, as does the end of the list.
     */
    public static void delivered(Object panel, List posts, boolean hasMore) {
        try {
            if (panel == null || posts == null || !enabled()) return;
            int size = posts.size();
            // Only a read that added posts asks again, so a list that keeps its size or a refresh
            // that came back shorter can't keep reads going by itself.
            boolean grew = deliveredPanel.get() != panel || size > deliveredSize;
            deliveredPanel = new WeakReference<>(panel);
            deliveredSize = size;
            if (!hasMore || !grew) return;
            Logger.printDebug(() -> "Latest first on Following: a read brought the list to "
                + size + " posts, asking for the next");
            // Posted, so the read that just landed is no longer in flight when the next is asked.
            Utils.runOnMainThread(() -> askForMore(panel));
        } catch (Throwable unknown) {
            // TikTok asks again as the reader moves.
        }
    }

    private static void askForMore(Object panel) {
        try {
            Class<?> type = panel.getClass();
            // TikTok's delivery checks its screen only after the hook, and its check for more
            // doesn't, so a panel whose screen is gone would still send a read.
            if (Boolean.FALSE.equals(type.getMethod("isViewValid").invoke(panel))) return;
            type.getMethod("checkLoadMore").invoke(panel);
        } catch (Throwable unknown) {
            Logger.printDebug(() -> "Latest first on Following: could not ask for the next read: "
                + unknown);
        }
    }

    private static boolean isFollowing(Object panel) {
        for (Class<?> type = panel == null ? null : panel.getClass(); type != null;
             type = type.getSuperclass()) {
            if (FOLLOWING_PANEL.equals(type.getName())) return true;
        }
        return false;
    }

    /**
     * Called as TikTok restores its saved Following list on a cold start. TikTok shows the first
     * post it saved last time as the first video and loads the fresh list under it, so with the
     * switch on that saved post played first and the newest posts were sorted below it (#226).
     * True while the switch is on, and TikTok then takes the path it takes when nothing was saved:
     * the first video comes from the fresh read, which is sorted before it plays. Paused, the
     * switch answers off.
     */
    public static boolean skipSavedFirstVideo() {
        try {
            if (!enabled()) return false;
            Logger.printDebug(() -> "Latest first on Following: left out TikTok's saved first video");
            return true;
        } catch (Throwable unknown) {
            return false;
        }
    }

    /** Puts an order into TikTok's list. True when the list TikTok reads now holds it. */
    interface OrderWriter {
        boolean write(List order);
    }

    /**
     * The videos that have been on screen, each with when it last was. The latest of them that a
     * list holds is where the reader is in that list. Kept by post id rather than by list, since
     * TikTok builds more than one FollowFeedList for the same feed and each has to keep the
     * reader's place. A video from another tab that the Following list also holds can only freeze
     * more of the list, never move anything above the reader.
     */
    private static final Map<String, Long> CURRENT =
        new LinkedHashMap<String, Long>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_CURRENT;
            }
        };
    private static long currentSequence;

    /**
     * What each list held after its last sort, by the list's identity, so a read that changed
     * nothing costs one pass and no sort: some of TikTok's loops read the list several times per
     * item. FollowFeedList is a Kotlin class from 46.9.3, so its equals can't be trusted.
     */
    private static final Map<Owner, Checked> CHECKED =
        new LinkedHashMap<Owner, Checked>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Owner, Checked> eldest) {
                return size() > MAX_OWNERS;
            }
        };

    /** A list by identity. Two lists never share an entry, and a collected one matches nothing. */
    private static final class Owner {
        final WeakReference<Object> ref;
        final int hash;

        Owner(Object owner) {
            ref = new WeakReference<>(owner);
            hash = System.identityHashCode(owner);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Owner)) return false;
            Object list = ref.get();
            return list != null && list == ((Owner) other).ref.get();
        }
    }

    private static final class Checked {
        boolean valid;
        long signature;
    }

    /**
     * Read once per run of the app. Turned on with a list already open, posts the reader already
     * scrolled past could move, so the switch asks for a restart.
     */
    private static volatile Boolean enabled;

    private static boolean enabled() {
        Boolean on = enabled;
        if (on == null) {
            on = Settings.FOLLOWING_LATEST_FIRST.get();
            enabled = on;
        }
        return on;
    }

    static void resetForTests() {
        enabled = null;
        deliveredPanel = new WeakReference<>(null);
        deliveredSize = -1;
        synchronized (CHECKED) {
            CHECKED.clear();
        }
        synchronized (CURRENT) {
            CURRENT.clear();
            currentSequence = 0;
        }
    }

    /**
     * The video on screen changed, from the player's report or the first bind of a fresh feed.
     * Any feed's video lands here: only a list that holds it is affected.
     */
    public static void onCurrentVideo(String aid) {
        if (aid == null || aid.isEmpty() || !enabled()) return;
        synchronized (CURRENT) {
            CURRENT.remove(aid);
            CURRENT.put(aid, ++currentSequence);
        }
    }

    /**
     * Sorts every post below the reader's current video in {@code list}, through {@code writer}
     * when any move. With no current video in the list (its first read), the whole list is sorted.
     * A write that missed (the list grew in between, or a read-only list was swapped for a copy
     * this read's caller doesn't hold) isn't remembered, so the next read tries again.
     */
    static void sort(Object owner, List list, FeedItemsFilter.AwemeExtractor extractor,
                     long nowMillis, OrderWriter writer) {
        if (owner == null || list == null || !enabled()) return;
        List snapshot = new ArrayList(list);
        int size = snapshot.size();
        if (size > MAX_SLOTS) return;
        Aweme[] posts = new Aweme[size];
        String[] aids = new String[size];
        for (int slot = 0; slot < size; slot++) {
            posts[slot] = extractor.extract(snapshot.get(slot));
            aids[slot] = posts[slot] == null ? null : aidOf(posts[slot]);
        }
        int current = currentSlot(aids);
        long signature = signature(snapshot, current);
        Checked checked = checkedFor(owner);
        synchronized (checked) {
            if (checked.valid && checked.signature == signature) return;
            long[] postedAt = new long[size];
            // For the debug log only, to tell which post the sort took for the one on screen.
            if (current >= 0 && posts[current] != null) {
                postedAt[current] = AlwaysShowPublishDatePatch.postedAt(posts[current], nowMillis);
            }
            List<Integer> run = new ArrayList<>();
            List order = null;
            for (int slot = current + 1; slot < size; slot++) {
                Aweme post = posts[slot];
                if (post == null) {
                    order = sortRun(snapshot, run, postedAt, current, order);
                    run.clear();
                    continue;
                }
                if (aids[slot] == null || !sortable(post)) continue;
                postedAt[slot] = AlwaysShowPublishDatePatch.postedAt(post, nowMillis);
                if (postedAt[slot] > 0) run.add(slot);
            }
            order = sortRun(snapshot, run, postedAt, current, order);
            if (order != null && !writer.write(order)) {
                checked.valid = false;
                return;
            }
            checked.valid = true;
            checked.signature = order == null ? signature : signature(order, current);
        }
    }

    /** The slot of the latest video on screen that the list holds, or -1 when it holds none. */
    private static int currentSlot(String[] aids) {
        int slot = -1;
        long latest = Long.MIN_VALUE;
        synchronized (CURRENT) {
            if (CURRENT.isEmpty()) return -1;
            for (int index = 0; index < aids.length; index++) {
                if (aids[index] == null) continue;
                Long when = CURRENT.get(aids[index]);
                if (when != null && when >= latest) {
                    latest = when;
                    slot = index;
                }
            }
        }
        return slot;
    }

    /** The entries in their order, by identity, plus the reader's slot. */
    private static long signature(List entries, int current) {
        long signature = 1125899906842597L;
        for (int slot = 0; slot < entries.size(); slot++) {
            signature = 31L * signature + System.identityHashCode(entries.get(slot));
        }
        return 31L * (31L * signature + entries.size()) + current;
    }

    /** Sorts the posts in {@code run}'s slots newest first, ties in TikTok's order. */
    private static List sortRun(List snapshot, List<Integer> run, long[] postedAt, int current,
                                List order) {
        if (run.size() < 2) return order;
        List<Integer> sorted = new ArrayList<>(run);
        Collections.sort(sorted, (a, b) -> Long.compare(postedAt[b], postedAt[a]));
        if (sorted.equals(run)) return order;
        Logger.printDebug(() -> "Latest first on Following: " + run.size() + " posts below slot "
            + current + (current >= 0 ? "@" + postedAt[current] : "") + " of " + snapshot.size()
            + " at " + slots(run, postedAt) + " play as " + slots(sorted, postedAt));
        List result = order != null ? order : new ArrayList(snapshot);
        for (int k = 0; k < run.size(); k++) {
            result.set(run.get(k), snapshot.get(sorted.get(k)));
        }
        return result;
    }

    /** Each slot with its post time (epoch seconds, as postedAt gives it), for the debug log. */
    private static String slots(List<Integer> slots, long[] postedAt) {
        StringBuilder out = new StringBuilder("[");
        for (int slot : slots) {
            if (out.length() > 1) out.append(", ");
            out.append(slot).append('@').append(postedAt[slot]);
        }
        return out.append(']').toString();
    }

    private static boolean sortable(Aweme post) {
        try {
            return !post.isAd()
                && !post.isSoftAd()
                && post.getAwemeRawAd() == null
                && !LiveFilter.isLive(post);
        } catch (Throwable unknown) {
            return false;
        }
    }

    private static String aidOf(Aweme post) {
        try {
            String aid = post.getAid();
            return aid == null || aid.isEmpty() ? null : aid;
        } catch (Throwable unknown) {
            return null;
        }
    }

    private static Checked checkedFor(Object owner) {
        Owner key = new Owner(owner);
        synchronized (CHECKED) {
            Checked entry = CHECKED.get(key);
            if (entry == null) {
                entry = new Checked();
                CHECKED.put(key, entry);
            }
            return entry;
        }
    }
}
