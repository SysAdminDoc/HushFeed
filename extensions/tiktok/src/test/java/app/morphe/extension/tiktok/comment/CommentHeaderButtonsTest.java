package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class CommentHeaderButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean close, expand, compact, translateButton;

    @Before public void remember() {
        close = Settings.HIDE_COMMENT_CLOSE_BUTTON.get();
        expand = Settings.HIDE_COMMENT_EXPAND_BUTTON.get();
        compact = Settings.COMPACT_COMMENT_HEADER.get();
        translateButton = Settings.COMMENT_TRANSLATE_BUTTON.get();
        Settings.COMPACT_COMMENT_HEADER.save(false);
        Settings.COMMENT_TRANSLATE_BUTTON.save(false);
    }

    @After public void restore() {
        Settings.HIDE_COMMENT_CLOSE_BUTTON.save(close);
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(expand);
        Settings.COMPACT_COMMENT_HEADER.save(compact);
        Settings.COMMENT_TRANSLATE_BUTTON.save(translateButton);
    }

    /** The bar and, laid over the same container, the row TikTok keeps its buttons in. */
    private static final class Panel {
        final FrameLayout container;
        final View bar, closeButton, expandButton, translate;

        Panel(Activity activity) {
            container = new FrameLayout(activity);
            bar = new View(activity);
            container.addView(bar, new FrameLayout.LayoutParams(-1, 44));
            LinearLayout row = new LinearLayout(activity);
            translate = button(activity, row, "translate");
            expandButton = button(activity, row, "expand");
            closeButton = button(activity, row, "close");
            container.addView(row, new FrameLayout.LayoutParams(-2, 44));
            activity.setContentView(container);
            CommentHeaderButtons.resolveForTests(CommentHeaderButtons.CLOSE_BUTTON_ID, closeButton.getId());
            CommentHeaderButtons.resolveForTests(CommentHeaderButtons.EXPAND_BUTTON_ID, expandButton.getId());
        }

        private static View button(Activity activity, LinearLayout row, String name) {
            View view = new View(activity);
            view.setId(View.generateViewId());
            view.setContentDescription(name);
            row.addView(view, new LinearLayout.LayoutParams(40, 40));
            return view;
        }

        void layout() {
            container.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            container.layout(0, 0, 480, 900);
        }
    }

    @Test public void theCloseSwitchHidesOnlyTheCloseButton() {
        Settings.HIDE_COMMENT_CLOSE_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Panel panel = new Panel(owner.get());
            panel.layout();
            int expandLeft = panel.expandButton.getLeft();
            CompactCommentHeader.bindCountBar(panel.bar);
            idle();
            panel.layout();
            assertEquals(View.GONE, panel.closeButton.getVisibility());
            assertEquals(View.VISIBLE, panel.expandButton.getVisibility());
            assertEquals(View.VISIBLE, panel.translate.getVisibility());
            assertEquals(View.VISIBLE, panel.bar.getVisibility());
            assertEquals(expandLeft, panel.expandButton.getLeft());
        }
    }

    @Test public void theExpandSwitchHidesOnlyTheExpandButtonAndCloseMovesOver() {
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Panel panel = new Panel(owner.get());
            panel.layout();
            int translateLeft = panel.translate.getLeft(), closeLeft = panel.closeButton.getLeft();
            CompactCommentHeader.bindCountBar(panel.bar);
            idle();
            panel.layout();
            assertEquals(View.GONE, panel.expandButton.getVisibility());
            assertEquals(View.VISIBLE, panel.closeButton.getVisibility());
            assertEquals(View.VISIBLE, panel.translate.getVisibility());
            assertEquals(translateLeft, panel.translate.getLeft());
            assertTrue(panel.closeButton.getLeft() < closeLeft);
        }
    }

    @Test public void tikTokShowingAButtonAgainIsUndoneOnTheNextLayoutAndOffRestoresIt() {
        Settings.HIDE_COMMENT_CLOSE_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Panel panel = new Panel(owner.get());
            CompactCommentHeader.bindCountBar(panel.bar);
            idle();
            assertEquals(View.GONE, panel.closeButton.getVisibility());
            panel.closeButton.setVisibility(View.INVISIBLE);
            panel.bar.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(View.GONE, panel.closeButton.getVisibility());
            Settings.HIDE_COMMENT_CLOSE_BUTTON.save(false);
            panel.bar.getViewTreeObserver().dispatchOnGlobalLayout();
            // Back to the state TikTok last gave it.
            assertEquals(View.INVISIBLE, panel.closeButton.getVisibility());
        }
    }

    @Test public void detachingThePanelPutsTheButtonsBack() {
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Panel panel = new Panel(owner.get());
            CompactCommentHeader.bindCountBar(panel.bar);
            idle();
            assertEquals(View.GONE, panel.expandButton.getVisibility());
            panel.container.removeView(panel.bar);
            assertEquals(View.VISIBLE, panel.expandButton.getVisibility());
        }
    }

    @Test public void allOffLeavesThePanelAloneAndMissingButtonsFailOpen() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Panel panel = new Panel(owner.get());
            CompactCommentHeader.bindCountBar(panel.bar);
            idle();
            for (View button : new View[]{panel.closeButton, panel.expandButton, panel.translate}) {
                assertEquals(View.VISIBLE, button.getVisibility());
            }
            Settings.HIDE_COMMENT_CLOSE_BUTTON.save(true);
            View lone = new View(owner.get());
            FrameLayout parent = new FrameLayout(owner.get());
            parent.addView(lone);
            owner.get().setContentView(parent);
            CompactCommentHeader.bindCountBar(lone);
            idle();
            assertEquals(View.VISIBLE, lone.getVisibility());
        }
    }

    /**
     * TikTok's 47.9.3 header row (layout z2): the close X at the end, the expand chevron to its
     * start, and to the chevron's start the translate intro anchor (an empty frame that is always
     * visible), the translate icon and the banner holder, which is as wide as the row.
     */
    private static final class BannerRow {
        /** The top bar holder's end margin as TikTok sets it, clearing only the close X. */
        static final int TIKTOKS_MARGIN = 30;
        final FrameLayout container;
        final RelativeLayout row;
        final View bar, closeButton, expandButton, anchor, banner, holder;
        final ImageView translate;

        BannerRow(Activity activity) {
            container = new FrameLayout(activity);
            // The comment column under the row: the newer top bar's banner holder, then the bar.
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            holder = new FrameLayout(activity);
            holder.setId(View.generateViewId());
            LinearLayout.LayoutParams holderParams = new LinearLayout.LayoutParams(-1, 36);
            holderParams.setMarginEnd(TIKTOKS_MARGIN);
            column.addView(holder, holderParams);
            bar = new View(activity);
            column.addView(bar, new LinearLayout.LayoutParams(-1, 44));
            container.addView(column, new FrameLayout.LayoutParams(-1, -2));
            row = new RelativeLayout(activity);
            closeButton = new ImageView(activity);
            closeButton.setId(View.generateViewId());
            RelativeLayout.LayoutParams close = new RelativeLayout.LayoutParams(40, 40);
            close.addRule(RelativeLayout.ALIGN_PARENT_END);
            row.addView(closeButton, close);
            expandButton = new ImageView(activity);
            expandButton.setId(View.generateViewId());
            RelativeLayout.LayoutParams expand = new RelativeLayout.LayoutParams(40, 40);
            expand.addRule(RelativeLayout.START_OF, closeButton.getId());
            expand.setMarginEnd(20);
            row.addView(expandButton, expand);
            anchor = new FrameLayout(activity);
            anchor.setId(View.generateViewId());
            row.addView(anchor, besideExpand(44));
            translate = new ImageView(activity);
            translate.setId(View.generateViewId());
            row.addView(translate, besideExpand(44));
            banner = new FrameLayout(activity);
            banner.setId(View.generateViewId());
            row.addView(banner, besideExpand(ViewGroup.LayoutParams.MATCH_PARENT));
            container.addView(row, new FrameLayout.LayoutParams(-1, 44));
            activity.setContentView(container);
            CommentHeaderButtons.resolveForTests(CommentHeaderButtons.CLOSE_BUTTON_ID, closeButton.getId());
            CommentHeaderButtons.resolveForTests(CommentHeaderButtons.EXPAND_BUTTON_ID, expandButton.getId());
            CommentHeaderButtons.resolveForTests(CommentHeaderButtons.SUGGESTION_HOLDER_ID, holder.getId());
            CommentHeaderButtons.resolveForTests(CommentHeaderButtons.TOP_BAR_HOLDER_ID, View.generateViewId());
        }

        int holderMargin() {
            return ((ViewGroup.MarginLayoutParams) holder.getLayoutParams()).getMarginEnd();
        }

        void tikTokSetsHolderMargin(int margin) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.getLayoutParams();
            params.setMarginEnd(margin);
            holder.setLayoutParams(params);
        }

        /** Lays out again and lets the binding see that layout, as the panel's next pass would. */
        void relayout() {
            layout();
            bar.getViewTreeObserver().dispatchOnGlobalLayout();
            layout();
        }

        int holderEndPastIcon() {
            int[] at = new int[2], iconAt = new int[2];
            holder.getLocationInWindow(at);
            translate.getLocationInWindow(iconAt);
            return at[0] + holder.getWidth() - iconAt[0];
        }

        private RelativeLayout.LayoutParams besideExpand(int width) {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(width, 44);
            params.addRule(RelativeLayout.START_OF, expandButton.getId());
            if (width != ViewGroup.LayoutParams.MATCH_PARENT) params.setMarginEnd(14);
            return params;
        }

        /** The view the banner ends at, read after a layout the way RelativeLayout resolved it. */
        int bannerAnchor() {
            layout();
            return ((RelativeLayout.LayoutParams) banner.getLayoutParams()).getRule(RelativeLayout.LEFT_OF);
        }

        void layout() {
            container.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            container.layout(0, 0, 480, 900);
        }
    }

    @Test public void theBannerEndsAtTheTranslateIconWhileItShows() {
        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            BannerRow header = new BannerRow(owner.get());
            header.layout();
            // TikTok's own rule runs the banner under the icon (what the reporter saw).
            assertTrue(header.banner.getRight() > header.translate.getLeft());
            CompactCommentHeader.bindCountBar(header.bar);
            idle();
            header.layout();
            assertEquals(header.translate.getId(), header.bannerAnchor());
            assertTrue(header.banner.getRight() <= header.translate.getLeft());
            assertEquals(View.VISIBLE, header.translate.getVisibility());
            assertEquals(View.VISIBLE, header.expandButton.getVisibility());

            // No icon (TikTok shows it only once the gates say yes): TikTok's rule comes back.
            header.translate.setVisibility(View.GONE);
            header.bar.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(header.expandButton.getId(), header.bannerAnchor());
            header.translate.setVisibility(View.VISIBLE);
            header.bar.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(header.translate.getId(), header.bannerAnchor());

            ((ViewGroup) header.bar.getParent()).removeView(header.bar);
            assertEquals(header.expandButton.getId(), header.bannerAnchor());
        }
    }

    @Test public void theTopBarHolderStopsAtTheTranslateIconAndKeepsTikToksMargin() {
        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            BannerRow header = new BannerRow(owner.get());
            header.layout();
            // TikTok's margin clears the X only, so the holder runs under the icon (Login2, 2026-11-21).
            int reach = header.holderEndPastIcon();
            assertTrue(reach > 0);
            CompactCommentHeader.bindCountBar(header.bar);
            idle();
            header.relayout();
            assertEquals(0, header.holderEndPastIcon());
            assertEquals(BannerRow.TIKTOKS_MARGIN + reach, header.holderMargin());
            // Further layouts leave it where it is.
            header.relayout();
            assertEquals(BannerRow.TIKTOKS_MARGIN + reach, header.holderMargin());

            // TikTok works out a new margin (the expand button came): that is its margin from now on.
            header.tikTokSetsHolderMargin(BannerRow.TIKTOKS_MARGIN + 40);
            header.relayout();
            assertEquals(0, header.holderEndPastIcon());
            assertEquals(BannerRow.TIKTOKS_MARGIN + reach, header.holderMargin());

            // No icon: TikTok's latest margin comes back.
            header.translate.setVisibility(View.GONE);
            header.relayout();
            assertEquals(BannerRow.TIKTOKS_MARGIN + 40, header.holderMargin());

            header.translate.setVisibility(View.VISIBLE);
            header.relayout();
            assertEquals(0, header.holderEndPastIcon());
            ((ViewGroup) header.bar.getParent()).removeView(header.bar);
            assertEquals(BannerRow.TIKTOKS_MARGIN + 40, header.holderMargin());
        }
    }

    @Test public void theBannerKeepsTikToksRuleWithTheTranslateSwitchOff() {
        Settings.HIDE_COMMENT_CLOSE_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            BannerRow header = new BannerRow(owner.get());
            CompactCommentHeader.bindCountBar(header.bar);
            idle();
            header.relayout();
            assertEquals(View.GONE, header.closeButton.getVisibility());
            assertEquals(header.expandButton.getId(), header.bannerAnchor());
            assertEquals(BannerRow.TIKTOKS_MARGIN, header.holderMargin());
        }
    }

    @Test public void theBannerFollowsTheIconWhenTheExpandButtonIsHidden() {
        Settings.COMMENT_TRANSLATE_BUTTON.save(true);
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            BannerRow header = new BannerRow(owner.get());
            CompactCommentHeader.bindCountBar(header.bar);
            idle();
            header.layout();
            assertEquals(View.GONE, header.expandButton.getVisibility());
            assertEquals(header.translate.getId(), header.bannerAnchor());
            assertTrue(header.banner.getRight() <= header.translate.getLeft());
        }
    }

    @Test public void switchesAreOffByDefaultAndNeedNoRestart() {
        for (var setting : new app.morphe.extension.shared.settings.BooleanSetting[]{
                Settings.HIDE_COMMENT_CLOSE_BUTTON, Settings.HIDE_COMMENT_EXPAND_BUTTON}) {
            assertEquals(Boolean.FALSE, setting.defaultValue);
            assertFalse(setting.rebootApp);
        }
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
}
