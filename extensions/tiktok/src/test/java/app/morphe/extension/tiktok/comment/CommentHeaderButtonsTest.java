package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    private boolean close, expand, compact;

    @Before public void remember() {
        close = Settings.HIDE_COMMENT_CLOSE_BUTTON.get();
        expand = Settings.HIDE_COMMENT_EXPAND_BUTTON.get();
        compact = Settings.COMPACT_COMMENT_HEADER.get();
        Settings.COMPACT_COMMENT_HEADER.save(false);
    }

    @After public void restore() {
        Settings.HIDE_COMMENT_CLOSE_BUTTON.save(close);
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(expand);
        Settings.COMPACT_COMMENT_HEADER.save(compact);
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

    @Test public void switchesAreOffByDefaultAndNeedNoRestart() {
        for (var setting : new app.morphe.extension.shared.settings.BooleanSetting[]{
                Settings.HIDE_COMMENT_CLOSE_BUTTON, Settings.HIDE_COMMENT_EXPAND_BUTTON}) {
            assertEquals(Boolean.FALSE, setting.defaultValue);
            assertFalse(setting.rebootApp);
        }
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
}
