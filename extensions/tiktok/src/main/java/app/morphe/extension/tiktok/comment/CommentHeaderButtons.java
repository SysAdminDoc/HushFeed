/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Hides the close and expand buttons at the top of the comments, one switch each (#371). The
 * magnifier over the shrunken video above them is a separate view TikTok shows from its own
 * method, which {@code HIDE_FEED_SEARCH_BUTTON} hides through
 * {@code FeatureControls.shrinkVideoSearchIconShown}.
 *
 * <p>The count bar's bind hands its root here. The buttons sit in the row laid over the bar's
 * container, so each one is looked up by id through the bar's nearest ancestors. A hidden button
 * goes GONE, which lets the buttons beside it take its place, and comes back as it was when its
 * switch is turned off. TikTok sets both buttons' visibility again as the panel changes, so the
 * switches are read again on every layout of the panel while it is attached.
 *
 * <p>The same layouts keep the suggested-search banner clear of the translate button (#479).
 * TikTok sizes the banner to clear the close and expand buttons but never its translate icon, so
 * with Translate button in comments on the banner's text ran under the icon. The banner has two
 * homes. The older header anchor puts it in a holder in the button row, laid out to the start of
 * the expand button, which then ends at the icon instead (BannerFit). The newer top bar puts it in
 * holders in the comment column under the row, with an end margin TikTok works out from the
 * buttons, which grows by however far the holder still reaches past the icon (HolderFit).
 */
public final class CommentHeaderButtons {
    /**
     * TikTok's ids, read from the 47.9.3 action bar layout: the close X (CommentPageActionBarAssem)
     * and the expand chevron, which DefaultCommentPageContainerFragment shows only to accounts in
     * the {@code comment_panel_full_page} experiment.
     */
    static final String CLOSE_BUTTON_ID = "48.0.2:byi";
    static final String EXPAND_BUTTON_ID = "48.0.2:i56";
    /**
     * The newer top bar's two banner holders in the comment column, both of the 47.9.3 comment
     * panel layout and kept as ViewGroups by CommentPageActionBarAssem: the top bar's own and the
     * one the suggested search lands in.
     */
    static final String TOP_BAR_HOLDER_ID = "48.0.2:et_";
    static final String SUGGESTION_HOLDER_ID = "48.0.2:evc";
    /** How many of the bar's ancestors are searched for a button: bar slot, column, container. */
    private static final int DEPTH = 4;
    private static final ResourceIdCache RESOURCE_IDS = new ResourceIdCache();
    private static final WeakHashMap<View, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();

    private CommentHeaderButtons() {
    }

    /** Called for each count bar bind, compact header or not. Never throws. */
    static void onHeaderBound(View bar) {
        try {
            if (bar == null) return;
            WeakReference<Binding> existing = BINDINGS.get(bar);
            Binding binding = existing == null ? null : existing.get();
            if (binding == null) {
                // Nothing to hide and nothing hidden yet: leave TikTok's panel without a listener.
                if (!anyOn()) return;
                binding = new Binding(bar);
                BINDINGS.put(bar, new WeakReference<>(binding));
                bar.addOnAttachStateChangeListener(binding);
            }
            binding.watch();
            // The hook runs before TikTok fills the bar in, so apply once it has.
            bar.post(binding);
        } catch (Exception error) {
            Logger.printException(() -> "Could not bind the comment header buttons", error);
        }
    }

    static boolean anyOn() {
        return Settings.HIDE_COMMENT_CLOSE_BUTTON.get() || Settings.HIDE_COMMENT_EXPAND_BUTTON.get()
                || Settings.COMMENT_TRANSLATE_BUTTON.get();
    }

    static void resolveForTests(String name, int id) {
        RESOURCE_IDS.putForTests(Utils.getContext().getPackageName(), name, id);
    }

    /** One button: which one it is, its switch, the view once found and how TikTok had it. */
    private static final class Button {
        final boolean close;
        final BooleanSetting setting;
        WeakReference<View> view;
        boolean searched, hidden;
        int visibility;

        Button(boolean close, BooleanSetting setting) {
            this.close = close;
            this.setting = setting;
        }

        void apply(View bar) {
            View button = view == null ? null : view.get();
            if (button == null && !searched && bar.isAttachedToWindow()) {
                // The buttons are inflated with the panel, so one search per attach finds them.
                searched = true;
                button = findIn(bar, RESOURCE_IDS.resolve(bar.getResources(), bar.getContext().getPackageName(),
                        close ? CLOSE_BUTTON_ID : EXPAND_BUTTON_ID, false));
                view = button == null ? null : new WeakReference<>(button);
            }
            if (button == null) return;
            if (setting.get()) {
                if (button.getVisibility() == View.GONE) return;
                // TikTok's own state, kept each time it shows the button again.
                visibility = button.getVisibility();
                hidden = true;
                button.setVisibility(View.GONE);
            } else {
                restore();
            }
        }

        void restore() {
            View button = view == null ? null : view.get();
            if (hidden && button != null && button.getVisibility() == View.GONE) button.setVisibility(visibility);
            hidden = false;
        }

        void forget() {
            searched = false;
        }
    }

    /**
     * The banner's holder in the expand button's row: the child as wide as the row that TikTok
     * lays out to the start of the expand button (its layout says so for {@code es6}, which is
     * found by that rule rather than by name). An icon that row shows to the start of the expand
     * button (the translate icons) moves the holder's end to that icon, and the holder gets
     * TikTok's own rule back once no icon shows or the switch is off.
     */
    private static final class BannerFit {
        WeakReference<View> holder;
        int tiktoksAnchor;

        void apply(View expand, View icon) {
            if (expand == null || !(expand.getParent() instanceof RelativeLayout)) return;
            RelativeLayout row = (RelativeLayout) expand.getParent();
            int expandId = expand.getId();
            View banner = holder == null ? null : holder.get();
            for (int i = 0; banner == null && i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child == expand || !(child.getLayoutParams() instanceof RelativeLayout.LayoutParams)) continue;
                RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) child.getLayoutParams();
                if (params.width == ViewGroup.LayoutParams.MATCH_PARENT && startOf(params, row) == expandId) banner = child;
            }
            if (banner == null || !(banner.getLayoutParams() instanceof RelativeLayout.LayoutParams)) return;
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) banner.getLayoutParams();
            if (holder == null || holder.get() != banner) {
                holder = new WeakReference<>(banner);
                tiktoksAnchor = startOf(params, row);
            }
            int anchor = Settings.COMMENT_TRANSLATE_BUTTON.get() && icon != null ? icon.getId() : tiktoksAnchor;
            if (startOf(params, row) == anchor) return;
            params.addRule(RelativeLayout.START_OF, anchor);
            banner.setLayoutParams(params);
        }

        void restore() {
            View banner = holder == null ? null : holder.get();
            if (banner == null || !(banner.getLayoutParams() instanceof RelativeLayout.LayoutParams)
                    || !(banner.getParent() instanceof View)) return;
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) banner.getLayoutParams();
            if (startOf(params, (View) banner.getParent()) == tiktoksAnchor) return;
            params.addRule(RelativeLayout.START_OF, tiktoksAnchor);
            banner.setLayoutParams(params);
        }
    }

    /**
     * The view a child is laid out to the start of. Once its row has laid out, RelativeLayout
     * keeps a start rule only as the left (or, right to left, right) rule it resolved it to, and
     * reads 0 for the start rule itself.
     */
    static int startOf(RelativeLayout.LayoutParams params, View row) {
        int start = params.getRule(RelativeLayout.START_OF);
        if (start != 0) return start;
        return params.getRule(row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? RelativeLayout.RIGHT_OF : RelativeLayout.LEFT_OF);
    }

    /**
     * One of the top bar's banner holders. TikTok's own end margin is kept each time TikTok sets
     * one; while the switch is on and the translate icon shows in the same band of the screen, the
     * margin grows by however far the holder would still reach past the icon's start, and it goes
     * back to TikTok's once no icon shows, the switch is off or the panel detaches.
     */
    private static final class HolderFit {
        final boolean topBar;
        WeakReference<View> view;
        boolean searched;
        int tiktoks, written = -1;

        HolderFit(boolean topBar) {
            this.topBar = topBar;
        }

        void apply(View bar, View icon) {
            View holder = view == null ? null : view.get();
            if (holder == null && !searched && bar.isAttachedToWindow()) {
                searched = true;
                holder = findIn(bar, RESOURCE_IDS.resolve(bar.getResources(), bar.getContext().getPackageName(),
                        topBar ? TOP_BAR_HOLDER_ID : SUGGESTION_HOLDER_ID, false));
                view = holder == null ? null : new WeakReference<>(holder);
            }
            if (holder == null || !(holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
            int current = params.getMarginEnd();
            // A margin other than the last one written here is TikTok's (or the first look).
            if (current != written) tiktoks = current;
            int target = tiktoks;
            if (Settings.COMMENT_TRANSLATE_BUTTON.get() && icon != null
                    && holder.getVisibility() == View.VISIBLE && holder.getHeight() > 0) {
                int[] at = new int[2], iconAt = new int[2];
                holder.getLocationInWindow(at);
                icon.getLocationInWindow(iconAt);
                boolean sameBand = at[1] < iconAt[1] + icon.getHeight() && iconAt[1] < at[1] + holder.getHeight();
                // How far the holder's end, with TikTok's margin, reaches past the icon's start.
                int added = current - tiktoks;
                int reach = holder.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? iconAt[0] + icon.getWidth() - (at[0] - added)
                        : at[0] + holder.getWidth() + added - iconAt[0];
                if (sameBand && reach > 0) target = tiktoks + reach;
            }
            if (target == current) return;
            params.setMarginEnd(target);
            written = target;
            holder.setLayoutParams(params);
        }

        void restore() {
            View holder = view == null ? null : view.get();
            if (written >= 0 && holder != null && holder.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
                if (params.getMarginEnd() == written) {
                    params.setMarginEnd(tiktoks);
                    holder.setLayoutParams(params);
                }
            }
            written = -1;
        }

        void forget() {
            searched = false;
        }
    }

    /** The translate icon the expand button's row shows to the start of that button, or null. */
    static View rowIcon(View expand) {
        if (expand == null || !(expand.getParent() instanceof RelativeLayout)) return null;
        RelativeLayout row = (RelativeLayout) expand.getParent();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child == expand || !(child instanceof ImageView) || child.getId() == View.NO_ID
                    || child.getVisibility() != View.VISIBLE
                    || !(child.getLayoutParams() instanceof RelativeLayout.LayoutParams)) continue;
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) child.getLayoutParams();
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT && startOf(params, row) == expand.getId()) return child;
        }
        return null;
    }

    /** The view with that id in the bar or its nearest ancestors, or null (also for id 0). */
    private static View findIn(View bar, int id) {
        if (id == 0) return null;
        View own = bar.findViewById(id);
        if (own != null) return own;
        ViewParent parent = bar.getParent();
        for (int depth = 0; depth < DEPTH && parent instanceof ViewGroup; depth++) {
            View found = ((ViewGroup) parent).findViewById(id);
            if (found != null) return found;
            parent = parent.getParent();
        }
        return null;
    }

    private static final class Binding implements Runnable, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<View> bar;
        final Button[] buttons = {
                new Button(true, Settings.HIDE_COMMENT_CLOSE_BUTTON),
                new Button(false, Settings.HIDE_COMMENT_EXPAND_BUTTON),
        };
        final BannerFit bannerFit = new BannerFit();
        final HolderFit[] holderFits = {new HolderFit(true), new HolderFit(false)};
        ViewTreeObserver observer;

        Binding(View view) {
            bar = new WeakReference<>(view);
        }

        void watch() {
            View view = bar.get();
            if (view == null || !view.isAttachedToWindow() || observer != null) return;
            observer = view.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
        }

        void unwatch() {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
        }

        @Override public void run() {
            View view = bar.get();
            if (view == null) return;
            try {
                for (Button button : buttons) button.apply(view);
                View expand = buttons[1].view == null ? null : buttons[1].view.get();
                View icon = rowIcon(expand);
                bannerFit.apply(expand, icon);
                for (HolderFit fit : holderFits) fit.apply(view, icon);
            } catch (Exception error) {
                unwatch();
                for (Button button : buttons) button.restore();
                bannerFit.restore();
                for (HolderFit fit : holderFits) fit.restore();
                Logger.printException(() -> "Could not hide a comment header button", error);
            }
        }

        @Override public void onGlobalLayout() { run(); }

        @Override public void onViewAttachedToWindow(View view) {
            for (Button button : buttons) button.forget();
            for (HolderFit fit : holderFits) fit.forget();
            watch();
            view.post(this);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            unwatch();
            view.removeCallbacks(this);
            for (Button button : buttons) button.restore();
            bannerFit.restore();
            for (HolderFit fit : holderFits) fit.restore();
        }
    }
}
