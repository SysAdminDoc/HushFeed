/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfeedPause;

/**
 * Shows the For You page to TikTok again when TikTok left it unshown while it's on screen
 * (#322, #326).
 *
 * <p>The For You list starts the next video's player only while its list panel's user-visible
 * hint is on, and the page's {@code onNodeShow}, which TikTok's page tree (Hox) sends when it
 * shows the page, is what turns that hint on. On some starts Hox never sends it: its Home
 * container keeps another tab as its current child, or the page joined Home after Home was
 * shown, and nothing switches again because For You is already the page in front. Then every
 * swipe lands on a new video that shows only its cover while the first video's sound plays on.
 *
 * <p>Each video page TikTok picks on For You's feed checks for exactly that state: For You's own
 * feed, the page resumed and in front, Home shown, yet the hint off. Then it makes Home's own
 * switch to For You, the call a tab tap ends in, and when the hint is still off after that, it
 * sends the page's show itself with the Bundle Hox would send. The page's show always turns the
 * hint on, so an off hint means no show reached the page, and nothing is ever sent twice. A
 * healthy feed costs a hint read and is left alone, and so is every check made while TikTok is
 * inside a show or hide of its own (that one is looked at again once it's done).
 */
public final class ForYouShowRepair {
    static final String FAMILY = "for you show";
    /** The For You page's tag in Hox, which its own show and hide look up. */
    static final String FOR_YOU = "For You";
    /** For You fragments TikTok keeps at once: the one on screen and maybe a cached one. */
    private static final int MAX_REMEMBERED = 8;

    /** What a check found, in the order the checks run. */
    enum Outcome {
        PAUSED, BUSY, NOT_FOR_YOU, NO_PANEL, HEALTHY, NOT_IN_FRONT, DEFERRED, NO_HOX, HOME_HIDDEN,
        SWITCHED, SHOWN, STILL_FROZEN
    }

    /** What the check reads and calls in TikTok, so a test can stand in for it. */
    interface Host {
        View view(Object fragment);

        boolean resumed(Object fragment);

        boolean hidden(Object fragment);

        Object panel(Object fragment);

        boolean hint(Object panel);

        Object activity(Object fragment);

        Object hox(Object activity);

        String containerTag(Object hox, String tag);

        Object node(Object hox, String tag);

        Object container(Object node);

        Object currentChild(Object container);

        boolean shown(Object container);

        String tag(Object node);

        void switchTo(Object hox, Object container, String tag, Bundle bundle);

        void show(Object fragment, Bundle bundle);

        boolean inFront(View page);

        boolean insideShowOrHide();

        void post(Runnable check);
    }

    private static final List<WeakReference<Object>> FRAGMENTS = new ArrayList<>();
    private static final Host TIKTOK = new TikTok();
    private static Host host = TIKTOK;
    /** Set while the repair calls into TikTok, so a page pick that call causes isn't checked again. */
    private static boolean repairing;

    private ForYouShowRepair() {
    }

    /** Said by the patch at the end of each For You fragment's constructor. */
    public static void forYouCreated(Object fragment) {
        if (fragment == null) return;
        HookStatus.bound(FAMILY, "For You page");
        synchronized (FRAGMENTS) {
            for (Iterator<WeakReference<Object>> it = FRAGMENTS.iterator(); it.hasNext(); ) {
                Object known = it.next().get();
                if (known == null) it.remove();
                else if (known == fragment) return;
            }
            if (FRAGMENTS.size() >= MAX_REMEMBERED) FRAGMENTS.remove(0);
            FRAGMENTS.add(new WeakReference<>(fragment));
        }
    }

    /** Said by the patch when a video pager is about to pick a page, before its listeners hear of it. */
    public static void feedPageSelected(Object pager) {
        try {
            HookStatus.bound(FAMILY, "video page picked");
            check(pager, false);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "video page picked", failure);
            Logger.printException(() -> "For You show: the check threw", failure);
        }
    }

    static Outcome check(Object pager, boolean again) {
        if (HushfeedPause.isPaused()) return Outcome.PAUSED;
        if (repairing) {
            Logger.printDebug(() -> "For You show: a page picked during the repair is left alone");
            return Outcome.BUSY;
        }
        if (!(pager instanceof View)) return Outcome.NOT_FOR_YOU;
        Object fragment = null;
        View page = null;
        synchronized (FRAGMENTS) {
            for (Iterator<WeakReference<Object>> it = FRAGMENTS.iterator(); it.hasNext() && fragment == null; ) {
                Object known = it.next().get();
                if (known == null) {
                    it.remove();
                    continue;
                }
                View root = host.view(known);
                if (root != null && holds(root, (View) pager)) {
                    fragment = known;
                    page = root;
                }
            }
        }
        if (fragment == null) {
            Logger.printDebug(() -> "For You show: a pager outside For You");
            return Outcome.NOT_FOR_YOU;
        }
        Object panel = host.panel(fragment);
        if (panel == null) {
            Logger.printDebug(() -> "For You show: For You has no list panel yet");
            return Outcome.NO_PANEL;
        }
        if (host.hint(panel)) {
            Logger.printDebug(() -> "For You show: hint on, nothing to do");
            return Outcome.HEALTHY;
        }
        boolean resumed = host.resumed(fragment);
        boolean hidden = host.hidden(fragment);
        boolean inFront = resumed && !hidden && host.inFront(page);
        if (!inFront) {
            Logger.printDebug(() -> "For You show: hint off but the page isn't in front (resumed " + resumed
                    + ", hidden " + hidden + ")");
            return Outcome.NOT_IN_FRONT;
        }
        if (host.insideShowOrHide()) {
            Logger.printDebug(() -> "For You show: hint off inside TikTok's own show or hide, "
                    + (again ? "left alone" : "looked at again after it"));
            if (!again) host.post(() -> {
                try {
                    check(pager, true);
                } catch (Throwable failure) {
                    HookStatus.threw(FAMILY, "video page picked", failure);
                    Logger.printException(() -> "For You show: the second look threw", failure);
                }
            });
            return Outcome.DEFERRED;
        }
        Object activity = host.activity(fragment);
        Object hox = activity == null ? null : host.hox(activity);
        String containerTag = hox == null ? null : host.containerTag(hox, FOR_YOU);
        Object node = hox == null ? null : host.node(hox, FOR_YOU);
        Object container = node == null ? null : host.container(node);
        if (containerTag == null || container == null) {
            Logger.printDebug(() -> "For You show: hint off, no Hox node for For You (activity " + (activity != null)
                    + ", Hox " + (hox != null) + ", container tag " + containerTag + ", node " + (node != null) + ")");
            return Outcome.NO_HOX;
        }
        if (!host.shown(container)) {
            Logger.printDebug(() -> "For You show: hint off, but Hox has " + containerTag + " hidden");
            return Outcome.HOME_HIDDEN;
        }
        Object current = host.currentChild(container);
        repairing = true;
        try {
            if (current != node) {
                String from = current == null ? null : host.tag(current);
                Logger.printDebug(() -> "For You show: hint off with " + containerTag + " on " + from
                        + ", switching it to For You");
                host.switchTo(hox, container, FOR_YOU, showBundle(containerTag));
                if (host.hint(panel)) {
                    HookStatus.bound(FAMILY, "switched Home to For You");
                    Logger.printDebug(() -> "For You show: the switch turned the hint on");
                    return Outcome.SWITCHED;
                }
                Logger.printDebug(() -> "For You show: the switch left the hint off, showing the page");
            } else {
                Logger.printDebug(() -> "For You show: hint off with " + containerTag
                        + " already on For You, showing the page");
            }
            host.show(fragment, showBundle(containerTag));
            if (host.hint(panel)) {
                HookStatus.bound(FAMILY, "showed For You");
                Logger.printDebug(() -> "For You show: the page's show turned the hint on");
                return Outcome.SHOWN;
            }
            HookStatus.bound(FAMILY, "show left the hint off");
            Logger.printDebug(() -> "For You show: the hint is still off after the page's show");
            return Outcome.STILL_FROZEN;
        } finally {
            repairing = false;
        }
    }

    /** The Bundle Hox hands a page it shows: the page's container tag set. */
    private static Bundle showBundle(String containerTag) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(containerTag, true);
        return bundle;
    }

    private static boolean holds(View root, View view) {
        for (Object at = view; at != null; ) {
            if (at == root) return true;
            if (!(at instanceof View)) return false;
            ViewParent parent = ((View) at).getParent();
            at = parent;
        }
        return false;
    }

    /** Whether {@code page} is shown in a visible window with at least half of it on screen. */
    static boolean onScreen(View page) {
        if (page == null || !page.isShown() || page.getWindowVisibility() != View.VISIBLE) return false;
        long area = (long) page.getWidth() * page.getHeight();
        if (area <= 0) return false;
        Rect visible = new Rect();
        if (!page.getGlobalVisibleRect(visible)) return false;
        return 2L * visible.width() * visible.height() >= area;
    }

    /** Whether this thread is inside a page tree show or hide, TikTok's or anyone's. */
    static boolean insideNodeShowOrHide() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String method = frame.getMethodName();
            if ("onNodeShow".equals(method) || "onNodeHide".equals(method)) return true;
        }
        return false;
    }

    static void useHostForTests(Host replacement) {
        host = replacement == null ? TIKTOK : replacement;
    }

    static void resetForTests() {
        host = TIKTOK;
        repairing = false;
        synchronized (FRAGMENTS) {
            FRAGMENTS.clear();
        }
    }

    private static final class TikTok implements Host {
        @Override
        public View view(Object fragment) {
            return readView(fragment);
        }

        @Override
        public boolean resumed(Object fragment) {
            return readResumed(fragment);
        }

        @Override
        public boolean hidden(Object fragment) {
            return readHidden(fragment);
        }

        @Override
        public Object panel(Object fragment) {
            return readPanel(fragment);
        }

        @Override
        public boolean hint(Object panel) {
            return readHint(panel);
        }

        @Override
        public Object activity(Object fragment) {
            return readActivity(fragment);
        }

        @Override
        public Object hox(Object activity) {
            return readHox(activity);
        }

        @Override
        public String containerTag(Object hox, String tag) {
            return readContainerTag(hox, tag);
        }

        @Override
        public Object node(Object hox, String tag) {
            return readNode(hox, tag);
        }

        @Override
        public Object container(Object node) {
            return readContainer(node);
        }

        @Override
        public Object currentChild(Object container) {
            return readCurrentChild(container);
        }

        @Override
        public boolean shown(Object container) {
            return readShown(container);
        }

        @Override
        public String tag(Object node) {
            return readTag(node);
        }

        @Override
        public void switchTo(Object hox, Object container, String tag, Bundle bundle) {
            callHomeSwitch(hox, container, tag, bundle);
        }

        @Override
        public void show(Object fragment, Bundle bundle) {
            callNodeShow(fragment, bundle);
        }

        @Override
        public boolean inFront(View page) {
            return onScreen(page);
        }

        @Override
        public boolean insideShowOrHide() {
            return insideNodeShowOrHide();
        }

        @Override
        public void post(Runnable check) {
            new Handler(Looper.getMainLooper()).post(check);
        }
    }

    // Filled at patch time from For You's own onNodeShow and Hox's switch. Unpatched, the hint
    // reads on, so the check never gets past it.

    /** The fragment's view. */
    private static View readView(Object fragment) {
        return null;
    }

    private static boolean readResumed(Object fragment) {
        return false;
    }

    private static boolean readHidden(Object fragment) {
        return false;
    }

    /** The For You fragment's list panel, whose hint its show turns on. */
    private static Object readPanel(Object fragment) {
        return null;
    }

    /** The list panel's user-visible hint. */
    private static boolean readHint(Object panel) {
        return true;
    }

    private static Object readActivity(Object fragment) {
        return null;
    }

    /** The activity's Hox. */
    private static Object readHox(Object activity) {
        return null;
    }

    /** The tag of the container holding the node tagged {@code tag}, Home's for For You. */
    private static String readContainerTag(Object hox, String tag) {
        return null;
    }

    private static Object readNode(Object hox, String tag) {
        return null;
    }

    private static Object readContainer(Object node) {
        return null;
    }

    private static Object readCurrentChild(Object container) {
        return null;
    }

    private static boolean readShown(Object container) {
        return false;
    }

    private static String readTag(Object node) {
        return null;
    }

    /** The container's own switch, with Hox's two listener lists. */
    private static void callHomeSwitch(Object hox, Object container, String tag, Bundle bundle) {
    }

    /** The For You fragment's onNodeShow. */
    private static void callNodeShow(Object fragment, Bundle bundle) {
    }
}
