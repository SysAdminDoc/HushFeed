package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

/**
 * See-through bottom bar (#145): the bar's background goes clear and the spacer under each video
 * goes, and turning the switch off puts both back exactly. If collapsing the spacer pushes the
 * caption under the bar, the spacer is put back and only the bar stays clear.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeeThroughBottomBarTest {
    private static final int CELL_ID = 0x7f0a0201;
    private static final int SPACER_ID = 0x7f0a0c21;
    private static final int BAR_ID = 0x7f0a0c22;
    private static final int ANCHOR_ID = 0x7f0a0c23;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.6.3:dv4", SPACER_ID);
        VideoOverlayHider.resolveForTests("47.6.3:oo3", BAR_ID);
        VideoOverlayHider.resolveForTests("47.6.3:bsb", ANCHOR_ID);
        VideoOverlayHider.resolveForTests("47.6.3:o4_", LIVE_CAPTION_ID);
        SeeThroughBottomBar.reset();
    }

    private static final int LIVE_CAPTION_ID = 0x7f0a0c24;

    /**
     * A LIVE preview in the feed has no widget layer, so with the see-through bar its caption
     * block sat behind the tabs. It goes up until its bottom meets the bar's top, and back down
     * with the switch off.
     */
    @Test
    public void aLivePreviewsCaptionStaysAboveTheSeeThroughBar() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.GONE, Color.BLACK);
            FrameLayout cell = (FrameLayout) scene.root.getChildAt(0);
            View caption = new View(activity);
            caption.setId(LIVE_CAPTION_ID);
            cell.addView(caption);
            cell.layout(0, 0, 1080, 1000);
            caption.layout(0, 700, 1080, 950);
            scene.bar.layout(0, 900, 1080, 1000);

            VideoOverlayHider.applyTo(activity);
            assertEquals("the caption still reaches under the bar", -50f, caption.getTranslationY(), 0f);
            VideoOverlayHider.applyTo(activity);
            assertEquals("a second pass lifted it again", -50f, caption.getTranslationY(), 0f);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, caption.getTranslationY(), 0f);
        }
    }

    @After
    public void tearDown() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
        Settings.FLOATING_BOTTOM_BAR.save(false);
        Settings.LIQUID_GLASS_BOTTOM_BAR.save(false);
        Settings.GLASS_TAB_LENS.save(false);
        Settings.GLASS_TAB_SLIDE.save(false);
        SeeThroughBottomBar.reset();
        VideoOverlayHider.resolveForTests("view_rootview", 0);
    }

    /** The scene: a cell holding the video area, the anchor frame and the spacer, and the bar. */
    private static final class Scene {
        final FrameLayout root;
        final View spacer;
        final View bar;
        final View anchor;

        Scene(Activity activity, int spacerVisibility, int barColor) {
            root = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            LinearLayout area = new LinearLayout(activity);
            area.setOrientation(LinearLayout.VERTICAL);
            area.addView(new View(activity), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            spacer = new View(activity);
            spacer.setId(SPACER_ID);
            spacer.setVisibility(spacerVisibility);
            area.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 136));
            cell.addView(area);
            anchor = new FrameLayout(activity);
            anchor.setId(ANCHOR_ID);
            cell.addView(anchor);
            root.addView(cell);
            bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(barColor);
            root.addView(bar);
            activity.setContentView(root);
        }
    }

    @Test
    public void theBarGoesClearAndTheSpacerGoesThenBothComeBack() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // TikTok keeps the spacer INVISIBLE (its space, no picture): it must come back so.
            Scene scene = new Scene(activity, View.INVISIBLE, Color.BLACK);
            android.graphics.drawable.Drawable original = scene.bar.getBackground();

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
            assertTrue(scene.bar.getBackground() instanceof ColorDrawable);
            assertNotSame(original, scene.bar.getBackground());
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            // A pass is repeated on every layout; a second one changes nothing.
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, scene.spacer.getVisibility());
            assertSame("the bar's own background must be the very drawable TikTok set",
                    original, scene.bar.getBackground());
        }
    }

    /**
     * Floating bottom bar (#124): with See-through bottom bar on, the bar's background is a
     * rounded see-through pill drawn in from its sides and bottom. The bar keeps its padding, so
     * the tabs stay where TikTok put them, and every way out gives TikTok's own background back.
     */
    @Test
    public void aFloatingBarIsAPillInsideTheBarAndEveryWayOutGivesTikToksBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.setPadding(5, 6, 7, 8);
            android.graphics.drawable.Drawable original = scene.bar.getBackground();

            Settings.FLOATING_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertSame("the pill showed without See-through bottom bar", original, scene.bar.getBackground());

            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);
            SeeThroughBottomBar.FloatingBackground floating = (SeeThroughBottomBar.FloatingBackground) scene.bar.getBackground();
            assertEquals("the pill moved the tabs", Arrays.asList(5, 6, 7, 8), padding(scene.bar));
            GradientDrawable pill = (GradientDrawable) floating.getDrawable();
            assertEquals(SeeThroughBottomBar.PILL_COLOR, pill.getColor().getDefaultColor());
            float density = activity.getResources().getDisplayMetrics().density;
            floating.setBounds(0, 0, 1080, 150);
            assertEquals(standardPill(density), pill.getBounds());
            android.view.ViewGroup parent = (android.view.ViewGroup) scene.bar.getParent();
            assertFalse("the bar's parent clips the pill's top", parent.getClipChildren());

            VideoOverlayHider.applyTo(activity);
            assertSame("a pass swapped the pill for a new one", floating, scene.bar.getBackground());

            Settings.FLOATING_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof ColorDrawable);
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());
            assertTrue("the parent kept drawing past its children with no pill", parent.getClipChildren());

            Settings.FLOATING_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);
            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertSame("Pause kept the pill", original, scene.bar.getBackground());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertSame("the bar's own background must be the very drawable TikTok set",
                    original, scene.bar.getBackground());
            assertEquals(Arrays.asList(5, 6, 7, 8), padding(scene.bar));
        } finally {
            PausedProcess.set(false);
            Settings.FLOATING_BOTTOM_BAR.save(false);
        }
    }

    /**
     * Liquid glass look (#218): with Floating bottom bar on, the pill is drawn as glass in the same
     * place. Turning it off gives the plain pill back, and every way out still gives TikTok's own
     * background back, never a pill kept as if it were TikTok's.
     */
    @Test
    public void aGlassBarTakesThePillsPlaceAndEveryWayOutGivesTikToksBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.setPadding(5, 6, 7, 8);
            android.graphics.drawable.Drawable original = scene.bar.getBackground();

            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            Settings.LIQUID_GLASS_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertFalse("the glass showed without Floating bottom bar",
                    scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);
            assertTrue(scene.bar.getBackground() instanceof ColorDrawable);

            Settings.FLOATING_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassBackground);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) scene.bar.getBackground();
            assertTrue(glass.getDrawable() instanceof SeeThroughBottomBar.GlassPill);
            assertEquals("the glass moved the tabs", Arrays.asList(5, 6, 7, 8), padding(scene.bar));
            float density = activity.getResources().getDisplayMetrics().density;
            glass.setBounds(0, 0, 1080, 150);
            assertEquals(standardPill(density), glass.getDrawable().getBounds());

            VideoOverlayHider.applyTo(activity);
            assertSame("a pass swapped the glass for a new one", glass, scene.bar.getBackground());

            Settings.LIQUID_GLASS_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);
            assertFalse("the glass stayed with the switch off",
                    scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassBackground);

            Settings.LIQUID_GLASS_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassBackground);
            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertSame("Pause kept the glass", original, scene.bar.getBackground());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassBackground);

            Settings.FLOATING_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof ColorDrawable);
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            Settings.FLOATING_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertSame("the bar's own background must be the very drawable TikTok set",
                    original, scene.bar.getBackground());
            assertEquals(Arrays.asList(5, 6, 7, 8), padding(scene.bar));
        } finally {
            PausedProcess.set(false);
            Settings.FLOATING_BOTTOM_BAR.save(false);
            Settings.LIQUID_GLASS_BOTTOM_BAR.save(false);
        }
    }

    private static java.util.List<Integer> padding(View view) {
        return Arrays.asList(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
    }

    /** The standard pill on a 1080 x 150 bar: HushGram's side margins, 4dp above the top and a hairline under. */
    private static Rect standardPill(float density) {
        int side = SeeThroughBottomBar.sidePx(1080, density);
        return new Rect(side, -Math.round(4 * density), 1080 - side, 150 - Math.round(density));
    }

    /**
     * Glass lens (#231): with Liquid glass look on, the lens switch swaps the glass for the glass
     * with a lens in the same place, and every way out still gives TikTok's own background back.
     */
    @Test
    public void theLensSwitchAddsTheLensToTheGlassAndEveryWayOutGivesTikToksBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.setPadding(5, 6, 7, 8);
            android.graphics.drawable.Drawable original = scene.bar.getBackground();

            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            Settings.FLOATING_BOTTOM_BAR.save(true);
            Settings.GLASS_TAB_LENS.save(true);
            VideoOverlayHider.applyTo(activity);
            assertFalse("the lens showed without Liquid glass look",
                    scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassBackground);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.FloatingBackground);

            Settings.LIQUID_GLASS_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassLensBackground);
            android.graphics.drawable.Drawable lens = scene.bar.getBackground();
            assertEquals("the lens moved the tabs", Arrays.asList(5, 6, 7, 8), padding(scene.bar));
            VideoOverlayHider.applyTo(activity);
            assertSame("a pass swapped the lens for a new one", lens, scene.bar.getBackground());

            Settings.GLASS_TAB_LENS.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassBackground);
            assertFalse("the lens stayed with the switch off",
                    scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassLensBackground);

            Settings.GLASS_TAB_LENS.save(true);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassLensBackground);
            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertSame("Pause kept the lens", original, scene.bar.getBackground());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(scene.bar.getBackground() instanceof SeeThroughBottomBar.GlassLensBackground);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertSame("the bar's own background must be the very drawable TikTok set",
                    original, scene.bar.getBackground());
            assertEquals(Arrays.asList(5, 6, 7, 8), padding(scene.bar));
        } finally {
            PausedProcess.set(false);
        }
    }

    /** Five 216 px tabs on a 1080 x 150 bar, the second one selected, with the lens as its background. */
    private static LinearLayout tabBar(Context context, SeeThroughBottomBar.GlassLensBackground lens) {
        LinearLayout bar = new LinearLayout(context);
        for (int i = 0; i < 5; i++) bar.addView(new View(context), new LinearLayout.LayoutParams(216, 150));
        bar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, 1080, 150);
        bar.getChildAt(1).setSelected(true);
        bar.setBackground(lens);
        lens.setBounds(0, 0, 1080, 150);
        return bar;
    }

    private static void select(LinearLayout bar, int tab) {
        for (int i = 0; i < bar.getChildCount(); i++) bar.getChildAt(i).setSelected(i == tab);
    }

    private static void animatorScale(float scale) {
        org.robolectric.util.ReflectionHelpers.callStaticMethod(android.animation.ValueAnimator.class, "setDurationScale",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(float.class, scale));
    }

    private static boolean hasPreDrawListeners(View bar) {
        return org.robolectric.util.ReflectionHelpers.callInstanceMethod(bar.getViewTreeObserver(), "hasOnPreDrawListeners");
    }

    /** Where the lens settles behind tab {@code index} of the bar, once its tabs are spread over the pill. */
    private static android.graphics.RectF lensAt(LinearLayout bar, int index) {
        SeeThroughBottomBar.FloatingBackground background = (SeeThroughBottomBar.FloatingBackground) bar.getBackground();
        View tab = bar.getChildAt(index);
        float left = tab.getLeft() + tab.getTranslationX();
        android.graphics.RectF out = new android.graphics.RectF();
        assertTrue(SeeThroughBottomBar.lensSpan(new android.graphics.RectF(left, tab.getTop(), left + tab.getWidth(),
                tab.getBottom()), background.pillRect, bar.getChildCount(), background.density, out));
        return out;
    }

    /** A tab's middle as drawn, its offset included. */
    private static float middle(View tab) {
        return (tab.getLeft() + tab.getRight()) / 2f + tab.getTranslationX();
    }

    /**
     * TikTok lays its tabs out over the bar's whole width, so on the narrower pill the outer
     * icons sat near its ends and the lens off their middles. The tabs are spread evenly over the
     * pill instead, the lens sits on the selected tab's middle, a tab TikTok offset itself keeps
     * its offset, and the tabs go back once the bar has another background.
     */
    @Test
    public void theTabsSpreadOverThePillAndTheLensSitsOnTheirMiddles() {
        animatorScale(0f);
        try {
            float density = context.getResources().getDisplayMetrics().density;
            SeeThroughBottomBar.GlassLensBackground lens = new SeeThroughBottomBar.GlassLensBackground(density);
            LinearLayout bar = tabBar(context, lens);
            android.graphics.Canvas canvas = new android.graphics.Canvas(
                    android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888));
            Rect pill = lens.pillRect;
            float inner = SeeThroughBottomBar.TAB_INNER_DP * density;

            lens.draw(canvas);
            for (int k = 0; k < 5; k++) {
                assertEquals("tab " + k, SeeThroughBottomBar.slotMiddle(pill.left + inner, pill.right - inner, 5, k),
                        middle(bar.getChildAt(k)), 1f);
            }
            assertEquals("the lens is off the selected tab's middle",
                    middle(bar.getChildAt(1)), lens.lensBounds().centerX(), 1f);

            // A tab TikTok takes away leaves four slots.
            bar.getChildAt(4).setVisibility(View.GONE);
            bar.getViewTreeObserver().dispatchOnPreDraw();
            for (int k = 0; k < 4; k++) {
                assertEquals("tab " + k, SeeThroughBottomBar.slotMiddle(pill.left + inner, pill.right - inner, 4, k),
                        middle(bar.getChildAt(k)), 1f);
            }

            // An offset of TikTok's own stays.
            bar.getChildAt(0).setTranslationX(3f);
            bar.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(3f, bar.getChildAt(0).getTranslationX(), 0f);

            bar.setBackground(new ColorDrawable(Color.BLACK));
            bar.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("TikTok's offset was taken back", 3f, bar.getChildAt(0).getTranslationX(), 0f);
            for (int k = 1; k < 4; k++) {
                assertEquals("tab " + k + " kept its offset", 0f, bar.getChildAt(k).getTranslationX(), 0f);
            }
        } finally {
            animatorScale(1f);
        }
    }

    /**
     * With Hide the bottom tab names, TikTok keeps each icon where it sat above its name. The tab
     * moves so the icon sits in the pill's middle, a count badge doesn't count as the icon, and
     * the tab goes back with the switch off.
     */
    @Test
    public void withTabNamesHiddenTheIconsSitInThePillsMiddle() {
        Settings.HIDE_BOTTOM_TAB_LABELS.save(true);
        try {
            float density = context.getResources().getDisplayMetrics().density;
            SeeThroughBottomBar.GlassLensBackground lens = new SeeThroughBottomBar.GlassLensBackground(density);
            LinearLayout bar = new LinearLayout(context);
            for (int i = 0; i < 4; i++) {
                FrameLayout tab = new FrameLayout(context);
                View icon = new View(context);
                tab.addView(icon, new FrameLayout.LayoutParams(60, 60));
                ((FrameLayout.LayoutParams) icon.getLayoutParams()).topMargin = 20;
                android.widget.TextView badge = new android.widget.TextView(context);
                tab.addView(badge, new FrameLayout.LayoutParams(30, 30));
                android.widget.TextView name = new android.widget.TextView(context);
                name.setVisibility(View.GONE);
                tab.addView(name);
                bar.addView(tab, new LinearLayout.LayoutParams(270, 150));
            }
            bar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY));
            bar.layout(0, 0, 1080, 150);
            bar.setBackground(lens);
            lens.setBounds(0, 0, 1080, 150);
            lens.draw(new android.graphics.Canvas(
                    android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888)));

            View tab = bar.getChildAt(2);
            assertEquals(50f, SeeThroughBottomBar.iconMiddle(tab), 0f);
            assertEquals("the icon is off the pill's middle", lens.pillRect.exactCenterY(),
                    tab.getTop() + tab.getTranslationY() + 50f, 1f);

            Settings.HIDE_BOTTOM_TAB_LABELS.save(false);
            bar.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("with the names back the tab, not its icon, sits in the pill's middle",
                    lens.pillRect.exactCenterY(), tab.getTop() + tab.getTranslationY() + 75f, 1f);
            assertTrue("the tab didn't go up with the pill", tab.getTranslationY() < 0f);
        } finally {
            Settings.HIDE_BOTTOM_TAB_LABELS.save(false);
        }
    }

    /** HushGram's proportions: 5% of the width each side, at least 16dp, the pill no wider than 420dp. */
    @Test
    public void thePillKeepsHushGramsProportions() {
        // A 1080 px phone at 2.8125: 5% of the width.
        assertEquals(54, SeeThroughBottomBar.sidePx(1080, 2.8125f));
        // A narrow screen: at least 16dp.
        assertEquals(32, SeeThroughBottomBar.sidePx(400, 2f));
        // A wide one: the pill stops at 420dp.
        assertEquals((2000 - 840) / 2, SeeThroughBottomBar.sidePx(2000, 2f));

        Rect out = new Rect();
        SeeThroughBottomBar.pillBounds(new Rect(0, 0, 1080, 150), 2.8125f, false, out);
        assertEquals("the pill stands 4dp above the bar", new Rect(54, -11, 1026, 147), out);
        SeeThroughBottomBar.pillBounds(new Rect(0, 0, 1080, 150), 2.8125f, true, out);
        assertEquals("a compact pill is 2dp slimmer each side", new Rect(54, -5, 1026, 141), out);
        SeeThroughBottomBar.pillBounds(new Rect(0, 0, 1080, 80), 2f, true, out);
        assertEquals("a compact pill stays 40dp tall", 80, out.height());
    }

    /**
     * The S22's bar (1080 x 138 at density 3, 2026-10-27): the Home icon's roof (20 px into the
     * bar) and its name's foot (117 px) touched the lens, the roof poking over it in Compact. The
     * taller pill, the tabs centered on it and the thinner lens inset leave room on both.
     */
    @Test
    public void theHomeTabClearsTheLensOnTheS22sBar() {
        float density = 3f;
        int roof = 20, foot = 117, barHeight = 138;
        for (boolean compact : new boolean[] {false, true}) {
            Rect pill = new Rect();
            SeeThroughBottomBar.pillBounds(new Rect(0, 0, 1080, barHeight), density, compact, pill);
            android.graphics.RectF lens = new android.graphics.RectF();
            assertTrue(SeeThroughBottomBar.lensSpan(new android.graphics.RectF(0, 0, 216, barHeight), pill, 5,
                    density, lens));
            float shift = Math.round(pill.exactCenterY() - barHeight / 2f);
            String which = compact ? "compact" : "standard";
            assertTrue(which + ": the roof touches the lens, " + (roof + shift - lens.top),
                    roof + shift - lens.top >= 3 * density);
            assertTrue(which + ": the name touches the lens, " + (lens.bottom - foot - shift),
                    lens.bottom - foot - shift >= 3 * density);
        }
    }

    /** The feed's layers go up by the pill's rise in step with the room their cell reached. */
    @Test
    public void theLiftCarriesThePillsRiseInStep() {
        assertEquals(150f, VideoOverlayHider.withRise(138f, 138f, 12), 0f);
        assertEquals("half the room, half the rise", 75f, VideoOverlayHider.withRise(69f, 138f, 12), 0f);
        assertEquals("no pill, no rise", 69f, VideoOverlayHider.withRise(69f, 138f, 0), 0f);
        assertEquals("a strip with no height of its own", 50f, VideoOverlayHider.withRise(50f, 0f, 12), 0f);
    }

    /** The choices' values: HushGram's corners, opacity steps and blur strengths. */
    @Test
    public void theChoicesGiveHushGramsSteps() {
        assertEquals(24f, SeeThroughBottomBar.cornerPx("standard", false, 100f, 2f), 0f);
        assertEquals(16f, SeeThroughBottomBar.cornerPx("standard", true, 100f, 2f), 0f);
        assertEquals(40f, SeeThroughBottomBar.cornerPx("rounder", false, 100f, 2f), 0f);
        assertEquals(28f, SeeThroughBottomBar.cornerPx("rounder", true, 100f, 2f), 0f);
        assertEquals(50f, SeeThroughBottomBar.cornerPx("round", false, 100f, 2f), 0f);
        assertEquals("never past half the height", 10f, SeeThroughBottomBar.cornerPx("rounder", false, 20f, 2f), 0f);
        assertEquals(0.5f, SeeThroughBottomBar.opacityScale("clear"), 0f);
        assertEquals(0.75f, SeeThroughBottomBar.opacityScale("light"), 0f);
        assertEquals(1f, SeeThroughBottomBar.opacityScale("standard"), 0f);
        assertEquals(1.35f, SeeThroughBottomBar.opacityScale("frosted"), 0f);
        assertEquals(GlassVideoBlur.BLUR_DP / 2f, GlassVideoBlur.blurDp("light"), 0f);
        assertEquals(GlassVideoBlur.BLUR_DP, GlassVideoBlur.blurDp("standard"), 0f);
        assertEquals(GlassVideoBlur.BLUR_DP * 5f / 3f, GlassVideoBlur.blurDp("strong"), 0.001f);
    }

    /** The bubble swells by 8% midway through a slide and settles back, carrying a finger's swell on. */
    @Test
    public void theBubbleSwellsMidSlideAndSettles() {
        assertEquals(1f, SeeThroughBottomBar.bubbleScale(0, 1f), 0.0001f);
        assertEquals(1f + SeeThroughBottomBar.BUBBLE_SWELL,
                SeeThroughBottomBar.bubbleScale(SeeThroughBottomBar.LENS_SLIDE_MS / 2, 1f), 0.0001f);
        assertEquals(1f, SeeThroughBottomBar.bubbleScale(SeeThroughBottomBar.LENS_SLIDE_MS, 1.08f), 0f);
        assertEquals("a finger's swell carries on", 1.08f, SeeThroughBottomBar.bubbleScale(0, 1.08f), 0.0001f);
    }

    /**
     * Slide across the tabs: a finger down on the pill goes on to TikTok; once it moves sideways
     * past the slop TikTok gets a cancel, the lens follows the finger, and lifting it opens the tab
     * under it. A finger that goes up or down first is never a slide.
     */
    @Test
    public void aSidewaysFingerOnThePillOpensTheTabItEndsOn() {
        Settings.GLASS_TAB_SLIDE.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            SeeThroughBottomBar.GlassLensBackground lens = new SeeThroughBottomBar.GlassLensBackground(density);
            LinearLayout bar = tabBar(activity, lens);
            boolean[] clicked = new boolean[5];
            for (int i = 0; i < 5; i++) {
                int tab = i;
                bar.getChildAt(i).setOnClickListener(view -> clicked[tab] = true);
            }
            FrameLayout root = new FrameLayout(activity);
            root.addView(bar, new FrameLayout.LayoutParams(1080, 150));
            activity.setContentView(root);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            lens.setBounds(0, 0, 1080, 150);
            lens.draw(new android.graphics.Canvas(
                    android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888)));
            GlassTabSlide slide = GlassTabSlide.installedIn(activity.getWindow());
            assertTrue("no slide watches the window", slide != null && slide.bar() == bar);

            java.util.List<Integer> passed = new java.util.ArrayList<>();
            android.view.Window.Callback inner = (android.view.Window.Callback) java.lang.reflect.Proxy.newProxyInstance(
                    android.view.Window.Callback.class.getClassLoader(), new Class<?>[]{android.view.Window.Callback.class},
                    (proxy, method, args) -> {
                        if ("dispatchTouchEvent".equals(method.getName())) {
                            passed.add(((android.view.MotionEvent) args[0]).getActionMasked());
                        }
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    });
            int[] at = new int[2];
            bar.getLocationOnScreen(at);
            float y = at[1] + lens.pillRect.centerY();
            float from = at[0] + middle(bar.getChildAt(1)), to = at[0] + middle(bar.getChildAt(3));
            float slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();

            assertFalse(slide.onTouch(touch(android.view.MotionEvent.ACTION_DOWN, from, y), inner));
            assertEquals(GlassTabSlide.WATCHING, slide.state());
            assertTrue(slide.onTouch(touch(android.view.MotionEvent.ACTION_MOVE, from + slop + 5, y), inner));
            assertEquals(GlassTabSlide.SLIDING, slide.state());
            assertEquals("TikTok never had its cancel",
                    Arrays.asList(android.view.MotionEvent.ACTION_CANCEL), passed);
            assertTrue(slide.onTouch(touch(android.view.MotionEvent.ACTION_MOVE, to, y), inner));
            assertEquals(to - at[0], lens.dragX, 0.5f);
            assertTrue(slide.onTouch(touch(android.view.MotionEvent.ACTION_UP, to, y), inner));
            assertTrue("the tab the finger ended on didn't open", clicked[3]);
            assertFalse(clicked[1]);
            assertTrue("the lens stayed on the finger", Float.isNaN(lens.dragX));
            assertEquals(GlassTabSlide.IDLE, slide.state());

            // Up or down first: TikTok's.
            assertFalse(slide.onTouch(touch(android.view.MotionEvent.ACTION_DOWN, from, y), inner));
            assertFalse(slide.onTouch(touch(android.view.MotionEvent.ACTION_MOVE, from, y - slop - 5), inner));
            assertEquals(GlassTabSlide.REFUSED, slide.state());
            assertFalse(slide.onTouch(touch(android.view.MotionEvent.ACTION_MOVE, to, y - slop - 5), inner));
            assertFalse(slide.onTouch(touch(android.view.MotionEvent.ACTION_UP, to, y), inner));
        } finally {
            Settings.GLASS_TAB_SLIDE.save(false);
        }
    }

    private static android.view.MotionEvent touch(int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        return android.view.MotionEvent.obtain(now, now, action, x, y, 0);
    }

    /** {@code ms} of frames 10 ms apart, each one a draw, the way a running slide asks for them. */
    private static void frames(SeeThroughBottomBar.GlassLensBackground lens, android.graphics.Canvas canvas, long ms) {
        assertEquals("frames come 10 ms apart", 0, ms % 10);
        for (long done = 0; done < ms; done += 10) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));
            lens.draw(canvas);
        }
    }

    /**
     * The lens sits behind the selected tab, in from the pill's top and bottom and the tab's
     * sides, and with animations off it jumps straight to the next tab picked. A selection change
     * is noticed before the next frame, and the listener that notices it goes once the bar has
     * another background.
     */
    @Test
    public void theLensFollowsTheSelectedTabAndLetsGoOfTheBarAfterwards() {
        animatorScale(0f);
        try {
            float density = context.getResources().getDisplayMetrics().density;
            float inset = SeeThroughBottomBar.LENS_INSET_DP * density;
            SeeThroughBottomBar.GlassLensBackground lens = new SeeThroughBottomBar.GlassLensBackground(density);
            assertTrue("an empty lens before any draw", lens.lensBounds().isEmpty());
            LinearLayout bar = tabBar(context, lens);
            Rect pill = lens.getDrawable().getBounds();
            android.graphics.Canvas canvas = new android.graphics.Canvas(
                    android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888));

            lens.draw(canvas);
            assertEquals(lensAt(bar, 1),
                    lens.lensBounds());
            assertFalse(lens.selectionMoved(bar));
            assertTrue("nothing watches the bar for a new tab", hasPreDrawListeners(bar));

            select(bar, 3);
            assertTrue(lens.selectionMoved(bar));
            assertFalse("the lens held up a frame", bar.getViewTreeObserver().dispatchOnPreDraw());
            lens.draw(canvas);
            assertEquals("with animations off the lens jumps",
                    lensAt(bar, 3),
                    lens.lensBounds());
            assertFalse(lens.selectionMoved(bar));

            // The first tab: the lens keeps inside the pill's rounded end.
            select(bar, 0);
            lens.draw(canvas);
            assertEquals(lensAt(bar, 0).left, lens.lensBounds().left, 0.001f);
            assertTrue("the lens ran into the pill's end", lens.lensBounds().left >= pill.left + inset - 0.001f);

            // No tab selected: the glass alone.
            select(bar, -1);
            assertTrue(lens.selectionMoved(bar));
            lens.draw(canvas);
            assertTrue(lens.lensBounds().isEmpty());
            assertFalse(lens.selectionMoved(bar));

            bar.setBackground(new ColorDrawable(Color.BLACK));
            bar.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse("the lens kept watching a bar it no longer draws", hasPreDrawListeners(bar));
        } finally {
            animatorScale(1f);
        }
    }

    /** With animations on, the lens slides to the next tab, quick off the mark and easing in. */
    @Test
    public void withAnimationsOnTheLensSlidesToTheNextTab() {
        animatorScale(1f);
        float density = context.getResources().getDisplayMetrics().density;
        float inset = SeeThroughBottomBar.LENS_INSET_DP * density;
        SeeThroughBottomBar.GlassLensBackground lens = new SeeThroughBottomBar.GlassLensBackground(density);
        LinearLayout bar = tabBar(context, lens);
        android.graphics.Canvas canvas = new android.graphics.Canvas(
                android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888));

        lens.draw(canvas);
        assertEquals("the first draw puts the lens in place, no slide in from nowhere",
                lensAt(bar, 1).left, lens.lensBounds().left, 0.001f);

        select(bar, 3);
        lens.draw(canvas);
        assertEquals("the slide starts where the lens was", lensAt(bar, 1).left, lens.lensBounds().left, 0.001f);

        frames(lens, canvas, SeeThroughBottomBar.LENS_SLIDE_MS / 2);
        // Halfway through the time, a cubic ease-out is seven eighths of the way there.
        assertEquals(lensAt(bar, 1).left + (lensAt(bar, 3).left - lensAt(bar, 1).left) * 0.875f, lens.lensBounds().left, 0.5f);

        frames(lens, canvas, SeeThroughBottomBar.LENS_SLIDE_MS);
        assertEquals(lensAt(bar, 3).left, lens.lensBounds().left, 0.001f);
        assertEquals(lensAt(bar, 3).right, lens.lensBounds().right, 0.001f);
    }

    /**
     * Picking a tab can hand the bar a new lens. It slides on from where the bar's last lens was
     * moments ago, and a lens that comes back after a while just appears behind its tab.
     */
    @Test
    public void aNewLensSlidesOnFromWhereTheBarsLastLensWas() {
        animatorScale(1f);
        float density = context.getResources().getDisplayMetrics().density;
        SeeThroughBottomBar.GlassLensBackground first = new SeeThroughBottomBar.GlassLensBackground(density);
        LinearLayout bar = tabBar(context, first);
        android.graphics.Canvas canvas = new android.graphics.Canvas(
                android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888));
        first.draw(canvas);
        assertEquals(lensAt(bar, 1).left, first.lensBounds().left, 0.001f);

        select(bar, 3);
        SeeThroughBottomBar.GlassLensBackground second = new SeeThroughBottomBar.GlassLensBackground(density);
        bar.setBackground(second);
        second.setBounds(0, 0, 1080, 150);
        second.draw(canvas);
        assertEquals("the new lens jumped instead of sliding on", lensAt(bar, 1).left, second.lensBounds().left, 0.001f);
        frames(second, canvas, SeeThroughBottomBar.LENS_SLIDE_MS);
        assertEquals(lensAt(bar, 3).left, second.lensBounds().left, 0.001f);

        // The bar takes another look for a while, with frames going by, then the glass again.
        bar.setBackground(new ColorDrawable(Color.BLACK));
        bar.getViewTreeObserver().dispatchOnPreDraw();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(SeeThroughBottomBar.LENS_MEMORY_MS + 1));
        bar.getViewTreeObserver().dispatchOnPreDraw();
        select(bar, 0);
        SeeThroughBottomBar.GlassLensBackground third = new SeeThroughBottomBar.GlassLensBackground(density);
        bar.setBackground(third);
        third.setBounds(0, 0, 1080, 150);
        third.draw(canvas);
        assertEquals("a lens back after a while slid in from an old spot",
                lensAt(bar, 0).left, third.lensBounds().left, 0.001f);
    }

    /**
     * A still screen draws nothing, so a lens that sat behind its tab for a while had last been
     * drawn long before the next tap. The lens that replaced it then jumped (S22, 2026-10-23, #231).
     * The frame after the tap counts as the old lens's last moment on screen.
     */
    @Test
    public void aNewLensSlidesOnAfterTheOldOneSatStillForAWhile() {
        animatorScale(1f);
        float density = context.getResources().getDisplayMetrics().density;
        float inset = SeeThroughBottomBar.LENS_INSET_DP * density;
        SeeThroughBottomBar.GlassLensBackground first = new SeeThroughBottomBar.GlassLensBackground(density);
        LinearLayout bar = tabBar(context, first);
        android.graphics.Canvas canvas = new android.graphics.Canvas(
                android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888));
        first.draw(canvas);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(SeeThroughBottomBar.LENS_MEMORY_MS * 3));

        select(bar, 3);
        SeeThroughBottomBar.GlassLensBackground second = new SeeThroughBottomBar.GlassLensBackground(density);
        bar.setBackground(second);
        second.setBounds(0, 0, 1080, 150);
        bar.getViewTreeObserver().dispatchOnPreDraw();
        second.draw(canvas);
        assertEquals("the new lens jumped after a still screen", lensAt(bar, 1).left, second.lensBounds().left, 0.001f);
        frames(second, canvas, SeeThroughBottomBar.LENS_SLIDE_MS);
        assertEquals(lensAt(bar, 3).left, second.lensBounds().left, 0.001f);
    }

    /** A frame with no tab selected between two taps doesn't make the lens jump either. */
    @Test
    public void theLensSlidesOnPastAFrameWithNoTabAfterSittingStill() {
        animatorScale(1f);
        float density = context.getResources().getDisplayMetrics().density;
        float inset = SeeThroughBottomBar.LENS_INSET_DP * density;
        SeeThroughBottomBar.GlassLensBackground lens = new SeeThroughBottomBar.GlassLensBackground(density);
        LinearLayout bar = tabBar(context, lens);
        android.graphics.Canvas canvas = new android.graphics.Canvas(
                android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888));
        lens.draw(canvas);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(SeeThroughBottomBar.LENS_MEMORY_MS * 3));

        select(bar, -1);
        bar.getViewTreeObserver().dispatchOnPreDraw();
        lens.draw(canvas);
        assertTrue(lens.lensBounds().isEmpty());
        select(bar, 3);
        bar.getViewTreeObserver().dispatchOnPreDraw();
        lens.draw(canvas);
        assertEquals("the lens jumped past a frame with no tab", lensAt(bar, 1).left, lens.lensBounds().left, 0.001f);
        frames(lens, canvas, SeeThroughBottomBar.LENS_SLIDE_MS);
        assertEquals(lensAt(bar, 3).left, lens.lensBounds().left, 0.001f);
    }

    /**
     * TikTok's main thread stalls for a few hundred ms building a tab's page the first time it
     * opens, and a slide timed by the clock alone finished unseen: the frame after the stall
     * showed the lens already behind the new tab (S22, 2026-10-23, the first tap from Home onto
     * Inbox, #231). The slide carries on from where it was shown, and a bar out of sight for
     * longer than its memory lets the slide finish.
     */
    @Test
    public void aSlideCarriesOnPastAStalledMainThread() {
        animatorScale(1f);
        float density = context.getResources().getDisplayMetrics().density;
        SeeThroughBottomBar.GlassLensBackground lens = new SeeThroughBottomBar.GlassLensBackground(density);
        LinearLayout bar = tabBar(context, lens);
        android.graphics.Canvas canvas = new android.graphics.Canvas(
                android.graphics.Bitmap.createBitmap(1080, 150, android.graphics.Bitmap.Config.ARGB_8888));
        lens.draw(canvas);

        select(bar, 3);
        lens.draw(canvas);
        assertEquals(lensAt(bar, 1).left, lens.lensBounds().left, 0.001f);
        // The stall: no frame at all for longer than the whole slide.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(SeeThroughBottomBar.LENS_SLIDE_MS + 10));
        lens.draw(canvas);
        float left = lens.lensBounds().left;
        assertTrue("the slide finished unseen during the stall: " + left, left < lensAt(bar, 1).left + (lensAt(bar, 3).left - lensAt(bar, 1).left) * 0.25f);
        assertTrue("the slide stood still after the stall: " + left, left > lensAt(bar, 1).left);
        frames(lens, canvas, SeeThroughBottomBar.LENS_SLIDE_MS);
        assertEquals(lensAt(bar, 3).left, lens.lensBounds().left, 0.001f);

        // A slide under way when the bar went out of sight for longer than its memory.
        select(bar, 0);
        lens.draw(canvas);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(SeeThroughBottomBar.LENS_MEMORY_MS + 10));
        lens.draw(canvas);
        assertEquals("a slide resumed after the bar was out of sight",
                lensAt(bar, 0).left, lens.lensBounds().left, 0.001f);
    }

    @Test
    public void aBackgroundTikTokSetInTheMeantimeIsNotOverwrittenOnTheWayBack() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            VideoOverlayHider.applyTo(activity);
            ColorDrawable theirs = new ColorDrawable(Color.RED);
            scene.bar.setBackground(theirs);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertSame(theirs, scene.bar.getBackground());
            assertEquals(View.VISIBLE, scene.spacer.getVisibility());
        }
    }

    @Test
    public void aSpacerTikTokHadAlreadyRemovedStaysRemovedWhenTheSwitchGoesOff() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.GONE, Color.BLACK);
            VideoOverlayHider.applyTo(activity);
            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
        }
    }

    @Test
    public void theSpacerIsPutBackWhenTheCollapseMovedTheCaptionUnderTheBar() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.layout(0, 900, 1080, 1000);
            // With the spacer in place the caption frame ends above the bar.
            scene.anchor.layout(0, 700, 1080, 880);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());

            // The collapse let the cell grow and TikTok's layout carried the caption down with it.
            scene.anchor.layout(0, 800, 1080, 1000);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the spacer must come back when the caption ended up under the bar",
                    View.VISIBLE, scene.spacer.getVisibility());
            assertFalse(SeeThroughBottomBar.collapseAllowed());
            assertEquals("the bar stays clear",
                    Color.TRANSPARENT, ((ColorDrawable) scene.bar.getBackground()).getColor());

            // Later passes leave the spacer be, until the switch is turned off and on again.
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, scene.spacer.getVisibility());
            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertTrue(SeeThroughBottomBar.collapseAllowed());
            Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
        }
    }

    @Test
    public void aCaptionThatStaysAboveTheBarKeepsTheCollapse() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Scene scene = new Scene(activity, View.VISIBLE, Color.BLACK);
            scene.bar.layout(0, 900, 1080, 1000);
            scene.anchor.layout(0, 700, 1080, 880);
            VideoOverlayHider.applyTo(activity);
            VideoOverlayHider.applyTo(activity);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, scene.spacer.getVisibility());
            assertTrue(SeeThroughBottomBar.collapseAllowed());
        }
    }

    private static final int STRIP_ID = 0x7f0a0c24;
    private static final int SEEK_BAR_ID = 0x7f0a0c2f;
    private static final int COLUMN_ID = 0x7f0a0c25;
    private static final int LAYER_ID = 0x7f0a0c26;

    /**
     * The main feed as the S22 lays it out (2026-10-13): the cell's own spacer is already gone,
     * and the room kept for the bar is the 138 px strip under the pager in a 2181 px column, with
     * the bar over it. The strip goes, the pager gets its room once the column is laid out again,
     * and the cell's widget layer goes up by the same 138 so the caption stays where it was. The
     * guard, measuring after that lift, keeps the collapse. Off puts the strip and the layer back.
     */
    @Test
    public void theMainFeedStripGoesAndTheCaptionStaysAboveTheBar() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        VideoOverlayHider.resolveForTests("video_seek_bar", SEEK_BAR_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            column.addView(cell, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            FrameLayout layer = new FrameLayout(activity);
            layer.setId(LAYER_ID);
            cell.addView(layer);
            View anchor = new FrameLayout(activity);
            anchor.setId(ANCHOR_ID);
            layer.addView(anchor, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 161, android.view.Gravity.BOTTOM));
            root.addView(column);
            View bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            root.addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            View seekBar = new View(activity);
            seekBar.setId(SEEK_BAR_ID);
            FrameLayout.LayoutParams seekAt = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 79, android.view.Gravity.BOTTOM);
            seekAt.bottomMargin = 101;
            root.addView(seekBar, seekAt);
            activity.setContentView(root);
            layOut(root);
            assertEquals(1882, anchor.getTop() + cell.getTop());

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, strip.getVisibility());
            // The pass gives the pager the room and lifts the layer by it before the frame is
            // drawn; lifted a pass later, the caption sat 138 low over the bar in between (#232).
            assertEquals("the pager didn't get the room", 2181, cell.getHeight());
            assertEquals("the room came without the lift", -138f, layer.getTranslationY(), 0f);

            layOut(root);
            assertEquals(2181, cell.getHeight());
            VideoOverlayHider.applyTo(activity);
            assertEquals(-138f, layer.getTranslationY(), 0f);
            assertEquals("the caption ends where it did, on the bar's top edge",
                    2043f, anchor.getBottom() + layer.getTranslationY(), 0f);
            assertTrue("the guard kept the collapse", SeeThroughBottomBar.collapseAllowed());
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals(Color.TRANSPARENT, ((ColorDrawable) bar.getBackground()).getColor());

            // A floating pill stands above the bar, and the caption ends on its top instead.
            Settings.FLOATING_BOTTOM_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            VideoOverlayHider.applyTo(activity);
            int rise = SeeThroughBottomBar.riseOver(bar);
            assertEquals(Math.round(4 * activity.getResources().getDisplayMetrics().density), rise);
            assertEquals("the caption ends on the pill's top", 2043f - rise,
                    anchor.getBottom() + layer.getTranslationY(), 0f);
            assertEquals("the progress line rides on the pill's top",
                    -(rise + Math.round(4 * activity.getResources().getDisplayMetrics().density)),
                    seekBar.getTranslationY(), 0f);
            Settings.FLOATING_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the rise outlived the pill", -138f, layer.getTranslationY(), 0f);
            assertEquals("the progress line stayed up without the pill", 0f, seekBar.getTranslationY(), 0f);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, strip.getVisibility());
            assertEquals(0f, layer.getTranslationY(), 0f);
        } finally {
            Settings.FLOATING_BOTTOM_BAR.save(false);
            VideoOverlayHider.resolveForTests("video_seek_bar", 0);
            VideoOverlayHider.resolveForTests("47.6.3:cot", 0);
            VideoOverlayHider.resolveForTests("viewpager_container", 0);
            VideoOverlayHider.resolveForTests("widget_container", 0);
        }
    }

    private static final int LIVE_NAME_ID = 0x7f0a0c27;

    /**
     * A LIVE preview in For You (#250, the reporter's S23 Ultra layout): the cell has no widget
     * layer, and the room's name and card sit in a cell-wide layer of their own, laid out against
     * the cell's bottom. That layer goes up by the strip's height like a video's widget layer, the
     * picture behind it stays where it is, and off puts it back.
     */
    @Test
    public void aLivePreviewsCardStaysAboveTheBar() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        VideoOverlayHider.resolveForTests("tv_live_nickname", LIVE_NAME_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            column.addView(cell, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            View picture = new FrameLayout(activity);
            cell.addView(picture);
            FrameLayout info = new FrameLayout(activity);
            cell.addView(info);
            FrameLayout card = new FrameLayout(activity);
            info.addView(card, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 305, android.view.Gravity.BOTTOM));
            View name = new View(activity);
            name.setId(LIVE_NAME_ID);
            card.addView(name, new FrameLayout.LayoutParams(191, 62));
            root.addView(column);
            View bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            root.addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            activity.setContentView(root);
            layOut(root);
            assertEquals(2043, card.getBottom());

            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals(2181, cell.getHeight());
            assertEquals("the LIVE card went under the bar with the room", -138f, info.getTranslationY(), 0f);
            assertEquals("the picture fills the room", 0f, picture.getTranslationY(), 0f);

            layOut(root);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the card ends where it did, on the bar's top edge",
                    2043f, card.getBottom() + info.getTranslationY(), 0f);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, strip.getVisibility());
            assertEquals(0f, info.getTranslationY(), 0f);
        } finally {
            VideoOverlayHider.resolveForTests("47.6.3:cot", 0);
            VideoOverlayHider.resolveForTests("viewpager_container", 0);
            VideoOverlayHider.resolveForTests("widget_container", 0);
            VideoOverlayHider.resolveForTests("tv_live_nickname", 0);
        }
    }

    private static final int DRAMA_TIPS_ID = 0x7f0a0c28;

    /**
     * A mini drama in For You (#250): its "Tap to watch this drama" row and title sit in a layer
     * beside the widget layer, so both go up by the strip's height, and a cell whose drama row
     * TikTok left gone keeps that layer where it is.
     */
    @Test
    public void aDramasInfoBesideTheWidgetLayerGoesUpWithIt() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        VideoOverlayHider.resolveForTests("tv_drama_tips", DRAMA_TIPS_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            StackedPager pager = new StackedPager(activity);
            column.addView(pager, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            FrameLayout[] layers = new FrameLayout[2];
            FrameLayout[] infos = new FrameLayout[2];
            View[] tips = new View[2];
            for (int i = 0; i < 2; i++) {
                FrameLayout cell = new FrameLayout(activity);
                cell.setId(CELL_ID);
                layers[i] = new FrameLayout(activity);
                layers[i].setId(LAYER_ID);
                cell.addView(layers[i]);
                infos[i] = new FrameLayout(activity);
                cell.addView(infos[i]);
                tips[i] = new View(activity);
                tips[i].setId(DRAMA_TIPS_ID);
                infos[i].addView(tips[i], new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, 62, android.view.Gravity.BOTTOM));
                pager.addView(cell);
            }
            tips[1].setVisibility(View.GONE);
            root.addView(column);
            View bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            root.addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            activity.setContentView(root);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals(-138f, layers[0].getTranslationY(), 0f);
            assertEquals("the drama row went under the bar with the room", -138f, infos[0].getTranslationY(), 0f);
            assertEquals("a layer with no drama row shown stays put", 0f, infos[1].getTranslationY(), 0f);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, infos[0].getTranslationY(), 0f);
            assertEquals(0f, layers[0].getTranslationY(), 0f);
        } finally {
            VideoOverlayHider.resolveForTests("47.6.3:cot", 0);
            VideoOverlayHider.resolveForTests("viewpager_container", 0);
            VideoOverlayHider.resolveForTests("widget_container", 0);
            VideoOverlayHider.resolveForTests("tv_drama_tips", 0);
        }
    }

    private static final int SWIPE_HINT_ID = 0x7f0a95f2;

    /**
     * A suggested account card in For You (#261, S22 2026-10-27): the page has no cell id and its
     * "Swipe up to skip" line sits on the page's bottom edge inside one full-size layout, so that
     * layout goes up by the strip's height. A card whose line TikTok left gone stays put.
     */
    @Test
    public void aCardsSwipeHintGoesUpWithTheRoom() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        VideoOverlayHider.resolveForTests("tv_swipe_up", SWIPE_HINT_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            StackedPager pager = new StackedPager(activity);
            column.addView(pager, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            FrameLayout layer = new FrameLayout(activity);
            layer.setId(LAYER_ID);
            cell.addView(layer);
            View cellHint = new View(activity);
            cellHint.setId(SWIPE_HINT_ID);
            cell.addView(cellHint);
            pager.addView(cell);
            FrameLayout[] cards = new FrameLayout[2];
            View[] hints = new View[2];
            for (int i = 0; i < 2; i++) {
                FrameLayout page = new FrameLayout(activity);
                cards[i] = new FrameLayout(activity);
                page.addView(cards[i]);
                hints[i] = new View(activity);
                hints[i].setId(SWIPE_HINT_ID);
                cards[i].addView(hints[i], new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, 51, android.view.Gravity.BOTTOM));
                pager.addView(page);
            }
            hints[1].setVisibility(View.GONE);
            root.addView(column);
            View bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            root.addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            activity.setContentView(root);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals(-138f, layer.getTranslationY(), 0f);
            assertEquals("the swipe hint went under the bar with the room", -138f, cards[0].getTranslationY(), 0f);
            assertEquals("a card with no hint shown stays put", 0f, cards[1].getTranslationY(), 0f);
            assertEquals("a video cell holding the line moves by its layers only", 0f, cell.getTranslationY(), 0f);

            hints[0].setVisibility(View.GONE);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the card goes back once its line is gone", 0f, cards[0].getTranslationY(), 0f);
            hints[0].setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertEquals(-138f, cards[0].getTranslationY(), 0f);

            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, cards[0].getTranslationY(), 0f);
            assertEquals(0f, layer.getTranslationY(), 0f);
        } finally {
            VideoOverlayHider.resolveForTests("47.6.3:cot", 0);
            VideoOverlayHider.resolveForTests("viewpager_container", 0);
            VideoOverlayHider.resolveForTests("widget_container", 0);
            VideoOverlayHider.resolveForTests("tv_swipe_up", 0);
        }
    }

    /**
     * The switch turned on with TikTok running (S22, 2026-10-20): the guard had measured the
     * caption clear of the bar, so it was armed, and TikTok then showed the strip again. The pass
     * before TikTok's layout found the strip shown and the layer no longer lifted while the cell
     * still had the strip's room, blamed the caption and kept the strip until a restart. Until
     * the column has given the pager the room again nothing is judged.
     */
    @Test
    public void aStripTikTokShowsAgainDoesNotEndTheCollapse() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            column.addView(cell, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            FrameLayout layer = new FrameLayout(activity);
            layer.setId(LAYER_ID);
            cell.addView(layer);
            View anchor = new FrameLayout(activity);
            anchor.setId(ANCHOR_ID);
            layer.addView(anchor, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 161, android.view.Gravity.BOTTOM));
            root.addView(column);
            View bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            root.addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            activity.setContentView(root);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            assertEquals(-138f, layer.getTranslationY(), 0f);

            strip.setVisibility(View.VISIBLE);
            VideoOverlayHider.applyTo(activity);
            assertTrue("a strip waiting for TikTok's layout isn't blamed on the caption",
                    SeeThroughBottomBar.collapseAllowed());
            assertEquals(View.GONE, strip.getVisibility());

            layOut(root);
            VideoOverlayHider.applyTo(activity);
            assertEquals(2181, cell.getHeight());
            assertEquals(-138f, layer.getTranslationY(), 0f);
            assertTrue(SeeThroughBottomBar.collapseAllowed());
        } finally {
            VideoOverlayHider.resolveForTests("47.6.3:cot", 0);
            VideoOverlayHider.resolveForTests("viewpager_container", 0);
            VideoOverlayHider.resolveForTests("widget_container", 0);
        }
    }

    /**
     * TikTok's pager in short: the cells stacked a page apart and the pager scrolled to the
     * current one, the way VerticalViewPager lays them out (S22, 2026-10-21: cells at 6129, 8172
     * and 10215 in a 2043 pager).
     */
    private static final class StackedPager extends FrameLayout {
        StackedPager(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int page = bottom - top;
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).layout(0, i * page, right - left, (i + 1) * page);
            }
        }
    }

    /**
     * The switch turned on with TikTok running, then a swipe (S22, 2026-10-21): the first pass
     * measured the next cell's caption clear of the bar while it waited a page below, and halfway
     * through the swipe that caption crossed the bar's top edge (1555 to 2744 against 2043), so
     * the guard blamed the collapse and kept the strip until a restart. Only a cell the pager has
     * settled on is judged.
     */
    @Test
    public void aCellHalfwayThroughASwipeDoesNotEndTheCollapse() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            FrameLayout root = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            StackedPager pager = new StackedPager(activity);
            column.addView(pager, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            FrameLayout[] layers = new FrameLayout[2];
            View[] anchors = new View[2];
            for (int i = 0; i < layers.length; i++) {
                FrameLayout cell = new FrameLayout(activity);
                cell.setId(CELL_ID);
                layers[i] = new FrameLayout(activity);
                layers[i].setId(LAYER_ID);
                cell.addView(layers[i]);
                anchors[i] = new FrameLayout(activity);
                anchors[i].setId(ANCHOR_ID);
                layers[i].addView(anchors[i], new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, 1189, android.view.Gravity.BOTTOM));
                pager.addView(cell);
            }
            root.addView(column);
            View bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            root.addView(bar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, android.view.Gravity.BOTTOM));
            activity.setContentView(root);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            layOut(root);
            VideoOverlayHider.applyTo(activity);
            assertEquals(2181, pager.getHeight());
            assertEquals(-138f, layers[1].getTranslationY(), 0f);
            assertTrue(SeeThroughBottomBar.collapseAllowed());

            // Halfway up to the next video, that cell's top at 701.
            pager.scrollTo(0, 2181 - 701);
            VideoOverlayHider.applyTo(activity);
            assertTrue("a cell still moving isn't blamed on the collapse",
                    SeeThroughBottomBar.collapseAllowed());
            assertEquals(View.GONE, strip.getVisibility());

            pager.scrollTo(0, 2181);
            VideoOverlayHider.applyTo(activity);
            assertTrue(SeeThroughBottomBar.collapseAllowed());
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals("the next caption ends on the bar's top edge", 2043f,
                    ((View) anchors[1].getParent().getParent()).getTop() - pager.getScrollY()
                            + anchors[1].getBottom() + layers[1].getTranslationY(), 0f);
        } finally {
            VideoOverlayHider.resolveForTests("47.6.3:cot", 0);
            VideoOverlayHider.resolveForTests("viewpager_container", 0);
            VideoOverlayHider.resolveForTests("widget_container", 0);
        }
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2181);
    }

    @Test
    public void onlyAnOverlayThatWasClearBeforeTheCollapseIsBlamedAfterIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            View bar = new View(activity);
            View tall = new View(activity);
            View fresh = new View(activity);
            root.addView(bar);
            root.addView(tall);
            root.addView(fresh);
            activity.setContentView(root);
            bar.layout(0, 900, 1080, 1000);
            // Already reaches under the bar with the spacer in place: nothing to learn from it.
            tall.layout(0, 0, 1080, 1000);
            SeeThroughBottomBar.judge(bar, Arrays.asList(tall), false);
            assertFalse(SeeThroughBottomBar.judge(bar, Arrays.asList(tall), true));
            // Never measured before, so never blamed.
            fresh.layout(0, 800, 1080, 1000);
            assertFalse(SeeThroughBottomBar.judge(bar, Arrays.asList(fresh), true));
            assertTrue(SeeThroughBottomBar.collapseAllowed());
        }
    }

    @Test
    public void anOverlayInAnotherCellIsNotJudged() {
        assertFalse(SeeThroughBottomBar.reachesUnder(1200f, 1900f, 900f));
        assertFalse(SeeThroughBottomBar.reachesUnder(-800f, -100f, 900f));
        assertFalse(SeeThroughBottomBar.reachesUnder(700f, 901f, 900f));
        assertTrue(SeeThroughBottomBar.reachesUnder(700f, 960f, 900f));
    }
}
