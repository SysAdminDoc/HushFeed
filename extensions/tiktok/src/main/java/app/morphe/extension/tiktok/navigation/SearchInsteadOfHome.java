/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.comment.CommentSearch;
import app.morphe.extension.tiktok.interaction.GestureActions;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Search instead of Home (#338): the Home tab becomes a search button for a reader who comes to
 * TikTok to look things up and not to scroll.
 *
 * <p>Home can't be dropped from the bottom tabs like the others: the filter keeps it, and Profile,
 * because TikTok needs a tab to land on ({@link BottomNavigationTabOptions}). So the tab stays and
 * changes what it is. Its name reads Search ({@link BottomTabLabels#nameFor}), its icon is a
 * magnifier ({@link IconKeeper}), and a tap opens TikTok's own search page the way its home screen
 * Search shortcut does, without the tab ever being selected, so For You doesn't start playing
 * behind the search page. A start from the launcher lands on Inbox, or Profile, instead
 * ({@link StartPage#coldStartTag}).
 *
 * <p>The tap is taken on the tab's release. The press itself still reaches TikTok's view, so the
 * long press that opens Hushfeed's settings keeps working, and a release after a long press is
 * left to end the press as before. A screen reader's double tap is a click action rather than a
 * touch, so the tab's accessibility delegate answers it too.
 *
 * <p>Rides on {@link HomeTabSettingsShortcut}'s front window and layout passes, so it follows the
 * same main activity and the same rebuilt tab bars. Switched off, or paused, the tab gets back
 * the touch listener and delegate it had.
 */
public final class SearchInsteadOfHome {
    private static WeakReference<View> attached = new WeakReference<>(null);
    /** The tab views whose icon is already held, so a layout pass adds no second keeper. */
    private static final Map<View, Boolean> KEPT = new WeakHashMap<>();
    /** TikTok's own touch listener on the tab, still called for every event and given back when off. */
    @Nullable
    private static View.OnTouchListener original;
    /** TikTok's accessibility delegate on the tab, readable from Android 10, given back when off. */
    @Nullable
    private static View.AccessibilityDelegate originalDelegate;
    /** When the Home tab's long press last opened the settings, on the uptime clock touches use. */
    private static volatile long longPressAt;

    /** Whether the delegate on the tab is this class's, which only happens when TikTok's was read. */
    private static boolean delegateTaken;

    private static Field listenerInfoField;
    private static Field touchField;
    private static boolean listenerUnreadable;
    private static Field delegateField;
    private static boolean delegateUnreadable;

    static final View.OnTouchListener TOUCH = SearchInsteadOfHome::onTouch;

    private SearchInsteadOfHome() {
    }

    /**
     * Whether the switch applies. It needs the feed tab navigation hooks, which bring the main
     * activity's layout passes and the start tab, and paused it answers off like the rest.
     */
    public static boolean isOn() {
        return SettingsStatus.feedNavigationEnabled && Settings.SEARCH_INSTEAD_OF_HOME.get();
    }

    /** The Home tab's long press opened something, so the release that ends it isn't a tap. */
    static void noteLongPress() {
        longPressAt = SystemClock.uptimeMillis();
    }

    /** Called on each layout pass of the main activity in front. */
    static void apply(Activity activity) {
        try {
            View current = attached.get();
            // Off, the tab isn't even looked up.
            if (!isOn()) {
                if (current != null) giveBack(current);
                return;
            }
            View home = FeedVisibility.homeTabView(activity);
            if (current != null && current != home) giveBack(current);
            if (home == null) return;
            keepIcon(home);
            if (attached.get() == home) {
                View.OnTouchListener now = touchListener(home);
                if (listenerUnreadable || now == TOUCH) return;
                // TikTok set a touch listener of its own again: that one is called through this
                // one, and is what goes back on the tab when the switch goes off.
                original = now;
                home.setOnTouchListener(TOUCH);
                Logger.printInfo(() -> "TikTok changed the Home tab's touch listener; took it back");
                return;
            }
            original = touchListener(home);
            home.setOnTouchListener(TOUCH);
            // A delegate TikTok set that can't be read stays on, so the tab keeps what it reads
            // out; a screen reader's click on it then goes to TikTok's Home.
            delegateTaken = false;
            originalDelegate = null;
            if (readDelegate(home)) {
                home.setAccessibilityDelegate(new Delegate(originalDelegate));
                delegateTaken = true;
            }
            attached = new WeakReference<>(home);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not turn the Home tab into a search button", error);
        }
    }

    /** The main activity followed was destroyed: its tab goes with it, and so do TikTok's listeners. */
    static void release() {
        View current = attached.get();
        if (current != null) giveBack(current);
        original = null;
        originalDelegate = null;
    }

    private static void giveBack(View tab) {
        tab.setOnTouchListener(original);
        if (delegateTaken) tab.setAccessibilityDelegate(originalDelegate);
        attached = new WeakReference<>(null);
        delegateTaken = false;
        original = null;
        originalDelegate = null;
    }

    private static boolean onTouch(View view, MotionEvent event) {
        View.OnTouchListener own = original;
        if (event.getActionMasked() != MotionEvent.ACTION_UP || !isOn() || heldDown(view, event)) {
            // The press, a move and a cancel go on to the tab as before, and so does the release
            // of a long press, which has nothing left to tap.
            return own != null && own.onTouch(view, event);
        }
        // The release ends the press as a cancel, so the tab draws itself unpressed and TikTok's
        // click, which would select Home and start For You, never comes.
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            if (own != null) own.onTouch(view, cancel);
            view.onTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
        if (inside(view, event)) open(view);
        return true;
    }

    /** Whether the press was a long one that a long press listener has already answered. */
    private static boolean heldDown(View view, MotionEvent event) {
        if (longPressAt >= event.getDownTime()) return true;
        // TikTok's own long press, on a build that gives Home one, which Hushfeed leaves alone.
        return view.isLongClickable() && !HomeTabSettingsShortcut.holds(view)
                && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout();
    }

    /** A release still on the tab, give or take the slop a tap is allowed. */
    private static boolean inside(View view, MotionEvent event) {
        float slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        float x = event.getX();
        float y = event.getY();
        return x >= -slop && y >= -slop && x < view.getWidth() + slop && y < view.getHeight() + slop;
    }

    static void open(View view) {
        view.playSoundEffect(SoundEffectConstants.CLICK);
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        GestureActions.openSearchPage(activityOf(view.getContext()));
    }

    @Nullable
    private static Context activityOf(Context context) {
        Context at = context;
        for (int step = 0; step < 8 && at instanceof ContextWrapper; step++) {
            if (at instanceof Activity) return at;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return context;
    }

    /**
     * Reads the tab's accessibility delegate into {@link #originalDelegate}, by its getter from
     * Android 10 and by the field before, and says whether it could.
     */
    private static boolean readDelegate(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            originalDelegate = view.getAccessibilityDelegate();
            return true;
        }
        if (delegateUnreadable) return false;
        try {
            if (delegateField == null) {
                Field field = View.class.getDeclaredField("mAccessibilityDelegate");
                field.setAccessible(true);
                delegateField = field;
            }
            originalDelegate = (View.AccessibilityDelegate) delegateField.get(view);
            return true;
        } catch (Throwable error) {
            delegateUnreadable = true;
            Logger.printInfo(() -> "Can't read a view's accessibility delegate on this Android: " + error);
            return false;
        }
    }

    /** The view's touch listener, or null when there is none or it can't be read. */
    @Nullable
    private static View.OnTouchListener touchListener(View view) {
        if (listenerUnreadable) return null;
        try {
            if (listenerInfoField == null) {
                Field info = View.class.getDeclaredField("mListenerInfo");
                info.setAccessible(true);
                listenerInfoField = info;
            }
            Object info = listenerInfoField.get(view);
            if (info == null) return null;
            if (touchField == null) {
                Field field = info.getClass().getDeclaredField("mOnTouchListener");
                field.setAccessible(true);
                touchField = field;
            }
            return (View.OnTouchListener) touchField.get(info);
        } catch (Throwable error) {
            listenerUnreadable = true;
            Logger.printInfo(() -> "Can't read a view's touch listener on this Android: " + error);
            return null;
        }
    }

    /** A screen reader's double tap on the tab opens search too, and the tab reads as Search. */
    static final class Delegate extends View.AccessibilityDelegate {
        @Nullable
        private final View.AccessibilityDelegate own;

        Delegate(@Nullable View.AccessibilityDelegate own) {
            this.own = own;
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK && isOn()) {
                open(host);
                return true;
            }
            return own != null ? own.performAccessibilityAction(host, action, args)
                    : super.performAccessibilityAction(host, action, args);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            if (own != null) own.onInitializeAccessibilityNodeInfo(host, info);
            else super.onInitializeAccessibilityNodeInfo(host, info);
            if (isOn()) info.setContentDescription(L10n.t("Search"));
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            if (own != null) own.onInitializeAccessibilityEvent(host, event);
            else super.onInitializeAccessibilityEvent(host, event);
        }

        @Override
        public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            if (own != null) own.onPopulateAccessibilityEvent(host, event);
            else super.onPopulateAccessibilityEvent(host, event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            return own != null ? own.dispatchPopulateAccessibilityEvent(host, event)
                    : super.dispatchPopulateAccessibilityEvent(host, event);
        }

        @Override
        public void sendAccessibilityEvent(View host, int eventType) {
            if (own != null) own.sendAccessibilityEvent(host, eventType);
            else super.sendAccessibilityEvent(host, eventType);
        }

        @Override
        public void sendAccessibilityEventUnchecked(View host, AccessibilityEvent event) {
            if (own != null) own.sendAccessibilityEventUnchecked(host, event);
            else super.sendAccessibilityEventUnchecked(host, event);
        }

        @Override
        public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child, AccessibilityEvent event) {
            return own != null ? own.onRequestSendAccessibilityEvent(host, child, event)
                    : super.onRequestSendAccessibilityEvent(host, child, event);
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
            return own != null ? own.getAccessibilityNodeProvider(host) : super.getAccessibilityNodeProvider(host);
        }
    }

    /**
     * The Home tab's view shows a magnifier, and its name reads Search, while the switch is on.
     * Once a view: the keeper stays with it and follows the switch both ways.
     */
    static void keepIcon(@Nullable View tab) {
        if (tab == null) return;
        synchronized (KEPT) {
            if (KEPT.put(tab, Boolean.TRUE) != null) return;
        }
        IconKeeper keeper = new IconKeeper(tab);
        tab.addOnAttachStateChangeListener(keeper);
        if (tab.isAttachedToWindow()) keeper.onViewAttachedToWindow(tab);
        keeper.apply();
    }

    /**
     * Holds the Home tab's icon to the switch, checked before each frame while it is on screen, as
     * {@link BottomTabLabels}' keeper holds a name. TikTok's tab view hands out its icon, its
     * animated icon and its name by getters it keeps its own names for. The magnifier takes the
     * name's colour, which follows the bar's look on and off the feed. With Bottom tab names
     * patched the name is that patch's to write ({@link BottomTabLabels#nameFor}); without it the
     * keeper writes Search itself and puts TikTok's name back after.
     */
    static final class IconKeeper implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        /**
         * The tab view's other icon layers, kept out of sight while the magnifier shows: the
         * animated icon a selected tab plays and the icons TikTok's server can swap in for a
         * season. Each may be null on a given tab, and the animated one is built late.
         */
        private static final String[] OTHER_ICONS = {
                "getTabLottieIcon", "getCustomIconView$homepage_common_release", "getCustomIconViewV2"};

        private final View tab;
        /** Set once a getter turns out to be missing, so a build without them costs nothing per frame. */
        private boolean missing;
        @Nullable
        private Method iconGetter;
        @Nullable
        private Method titleGetter;
        private final Method[] otherGetters = new Method[OTHER_ICONS.length];
        @Nullable
        private ImageView image;
        @Nullable
        private TextView title;
        @Nullable
        private CommentSearch.SearchGlyphDrawable glyph;
        /** What TikTok last showed, put back when the switch goes off. */
        @Nullable
        private Drawable own;
        @Nullable
        private CharSequence ownName;
        private int ownImageVisibility;
        /** Each other layer hidden here and the visibility TikTok had given it. */
        private final Map<View, Integer> hidden = new WeakHashMap<>();
        private boolean swapped;

        IconKeeper(View tab) {
            this.tab = tab;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            view.getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            view.getViewTreeObserver().removeOnPreDrawListener(this);
        }

        /** Always lets the frame draw: a change made here shows on the next one. */
        @Override
        public boolean onPreDraw() {
            try {
                apply();
            } catch (Throwable error) {
                missing = true;
                Logger.printException(() -> "Could not draw the Home tab as search", error);
            }
            return true;
        }

        /** Brings the icon in line with the switch, and says whether anything changed. */
        boolean apply() {
            boolean on = isOn();
            if (!on && !swapped) return false;
            if (!find()) return false;
            ImageView icon = image;
            if (!on) {
                swapped = false;
                if (icon.getDrawable() == glyph) icon.setImageDrawable(own);
                icon.setVisibility(ownImageVisibility);
                for (Map.Entry<View, Integer> layer : hidden.entrySet()) {
                    if (layer.getKey().getVisibility() == View.GONE) layer.getKey().setVisibility(layer.getValue());
                }
                hidden.clear();
                TextView name = title;
                if (ownName != null && name != null && TextUtils.equals(name.getText(), L10n.t("Search"))) {
                    name.setText(ownName);
                }
                own = null;
                ownName = null;
                return true;
            }
            boolean changed = false;
            if (!swapped) {
                ownImageVisibility = icon.getVisibility();
                swapped = true;
            }
            if (glyph == null) glyph = new CommentSearch.SearchGlyphDrawable(tab.getContext(), color());
            else glyph.setColor(color());
            Drawable shown = icon.getDrawable();
            if (shown != glyph) {
                // TikTok's icon, or one it set since: that is what comes back.
                own = shown;
                icon.setImageDrawable(glyph);
                changed = true;
            }
            if (icon.getVisibility() != View.VISIBLE) {
                ownImageVisibility = icon.getVisibility();
                icon.setVisibility(View.VISIBLE);
                changed = true;
            }
            for (Method getter : otherGetters) {
                View layer = getter == null ? null : view(getter);
                if (layer == null || layer == icon || layer.getVisibility() == View.GONE) continue;
                hidden.put(layer, layer.getVisibility());
                layer.setVisibility(View.GONE);
                changed = true;
            }
            TextView name = title;
            String search = L10n.t("Search");
            if (name != null && !SettingsStatus.bottomTabLabelsEnabled
                    && !TextUtils.equals(name.getText(), search)) {
                // TikTok's name, or one it wrote since: that is what comes back.
                ownName = name.getText();
                name.setText(search);
                changed = true;
            }
            return changed;
        }

        private int color() {
            TextView name = title;
            return name != null ? name.getCurrentTextColor() : Color.WHITE;
        }

        private boolean find() {
            if (missing) return false;
            try {
                if (iconGetter == null) {
                    Class<?> type = tab.getClass();
                    iconGetter = type.getMethod("getTabIcon");
                    for (int i = 0; i < OTHER_ICONS.length; i++) otherGetters[i] = getter(type, OTHER_ICONS[i]);
                    titleGetter = getter(type, "getTabTitleView");
                }
                // Either is null until TikTok has built it: asked again on the next frame.
                if (title == null && titleGetter != null) {
                    View name = view(titleGetter);
                    if (name instanceof TextView) title = (TextView) name;
                }
                if (image != null) return true;
                View icon = view(iconGetter);
                if (!(icon instanceof ImageView)) return false;
                image = (ImageView) icon;
                return true;
            } catch (NoSuchMethodException gone) {
                missing = true;
                Logger.printInfo(() -> "The Home tab's icon can't be found on this build; it keeps TikTok's");
                return false;
            } catch (Throwable error) {
                missing = true;
                Logger.printException(() -> "Could not find the Home tab's icon", error);
                return false;
            }
        }

        @Nullable
        private static Method getter(Class<?> type, String name) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException gone) {
                return null;
            }
        }

        @Nullable
        private View view(Method getter) {
            try {
                Object value = getter.invoke(tab);
                return value instanceof View ? (View) value : null;
            } catch (ReflectiveOperationException unreadable) {
                return null;
            }
        }
    }

    static void resetForTests() {
        View current = attached.get();
        if (current != null) giveBack(current);
        attached = new WeakReference<>(null);
        original = null;
        originalDelegate = null;
        delegateTaken = false;
        longPressAt = 0;
        synchronized (KEPT) {
            KEPT.clear();
        }
    }
}
