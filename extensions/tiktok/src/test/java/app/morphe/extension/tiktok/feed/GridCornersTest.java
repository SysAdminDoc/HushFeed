package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "xxhdpi")
public class GridCornersTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean publishDate;
    private Context context;

    @Before public void setUp() {
        publishDate = SettingsStatus.alwaysShowPublishDateEnabled;
        SettingsStatus.alwaysShowPublishDateEnabled = true;
        Settings.ROUNDED_GRID_CORNERS.resetToDefault();
        Settings.GRID_CORNER_RADIUS.resetToDefault();
        Settings.GRID_GAP.resetToDefault();
        HookStatus.clear();
        context = RuntimeEnvironment.getApplication();
    }

    @After public void tearDown() {
        SettingsStatus.alwaysShowPublishDateEnabled = publishDate;
        Settings.ROUNDED_GRID_CORNERS.resetToDefault();
        Settings.GRID_CORNER_RADIUS.resetToDefault();
        Settings.GRID_GAP.resetToDefault();
        HookStatus.clear();
    }

    @Test public void theCornerSizeSettingSetsTheRadiusAndAChangeReachesABoundCell() {
        Settings.ROUNDED_GRID_CORNERS.save(true);
        Settings.GRID_CORNER_RADIUS.save(12);
        View view = cell(ViewOutlineProvider.BOUNDS, false);
        GridCorners.cell(view);
        assertEquals("12dp at xxhdpi", 36f, outlineOf(view).getRadius(), 0.01f);

        Settings.GRID_CORNER_RADIUS.save(4);
        GridCorners.cell(view);
        assertEquals("the next bind takes the new size", 12f, outlineOf(view).getRadius(), 0.01f);

        Settings.ROUNDED_GRID_CORNERS.save(false);
        GridCorners.cell(view);
        assertSame("still gives back TikTok's own outline", ViewOutlineProvider.BOUNDS, view.getOutlineProvider());
        assertFalse(view.getClipToOutline());
    }

    /** A cell in its grid with the margins TikTok gave it. */
    private View spacedCell(int left, int top, int right, int bottom) {
        FrameLayout grid = new FrameLayout(context);
        View view = new View(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(120, 160);
        params.setMargins(left, top, right, bottom);
        grid.addView(view, params);
        return view;
    }

    private static void assertMargins(String message, View view, int left, int top, int right, int bottom) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        assertEquals(message, new Rect(left, top, right, bottom),
                new Rect(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin));
    }

    @Test public void aGapAddsHalfToEachOwnMarginOnceAndZeroGivesThemBack() {
        View view = spacedCell(3, 0, 3, 6);
        GridCorners.cell(view);
        assertMargins("no gap set leaves TikTok's own", view, 3, 0, 3, 6);

        Settings.GRID_GAP.save(4);
        GridCorners.cell(view);
        assertMargins("4dp at xxhdpi is 12px, half on each side", view, 9, 6, 9, 12);
        GridCorners.cell(view);
        assertMargins("a second bind adds nothing more", view, 9, 6, 9, 12);

        Settings.GRID_GAP.save(8);
        GridCorners.cell(view);
        assertMargins("a new gap starts from TikTok's own margins", view, 15, 12, 15, 18);

        Settings.GRID_GAP.save(0);
        GridCorners.cell(view);
        assertMargins("0 gives the cell its own margins back", view, 3, 0, 3, 6);
    }

    @Test public void theGapWorksWithTheCornersOffAndReachesCollectionCells() {
        Settings.GRID_GAP.save(2);
        View view = spacedCell(0, 0, 0, 0);
        GridCorners.collectionCell(new CollectionHolder(view));
        assertMargins("2dp is 6px, 3 a side", view, 3, 3, 3, 3);
        assertFalse("no corners without their switch", view.getClipToOutline());

        SettingsStatus.alwaysShowPublishDateEnabled = false;
        View other = spacedCell(1, 1, 1, 1);
        GridCorners.cell(other);
        assertMargins("without the patch nothing changes", other, 1, 1, 1, 1);
    }

    /** A cell laid out at 120 by 160 pixels, with whatever outline TikTok gave it. */
    private View cell(ViewOutlineProvider provider, boolean clip) {
        View view = new View(context);
        view.setOutlineProvider(provider);
        view.setClipToOutline(clip);
        view.layout(0, 0, 120, 160);
        return view;
    }

    private static Outline outlineOf(View view) {
        Outline outline = new Outline();
        view.getOutlineProvider().getOutline(view, outline);
        return outline;
    }

    @Test public void offByDefaultTheCellKeepsTikToksOutline() {
        View view = cell(ViewOutlineProvider.BOUNDS, false);
        GridCorners.cell(view);
        assertSame(ViewOutlineProvider.BOUNDS, view.getOutlineProvider());
        assertFalse(view.getClipToOutline());
    }

    @Test public void onTheCellIsClippedToEightDpCorners() {
        Settings.ROUNDED_GRID_CORNERS.save(true);
        View view = cell(ViewOutlineProvider.BACKGROUND, false);
        GridCorners.cell(view);
        assertTrue("the cover and its overlays are clipped", view.getClipToOutline());
        Outline outline = outlineOf(view);
        assertEquals("8dp at xxhdpi", 24f, outline.getRadius(), 0.01f);
        Rect bounds = new Rect();
        assertTrue(outline.getRect(bounds));
        assertEquals(new Rect(0, 0, 120, 160), bounds);
    }

    @Test public void aRecycledCellIsRoundedOnceAndGivenBackWhatItHadWhenTurnedOff() {
        Settings.ROUNDED_GRID_CORNERS.save(true);
        View view = cell(ViewOutlineProvider.PADDED_BOUNDS, true);
        GridCorners.cell(view);
        ViewOutlineProvider rounded = view.getOutlineProvider();
        GridCorners.cell(view);
        assertSame("a second bind keeps the one outline", rounded, view.getOutlineProvider());

        Settings.ROUNDED_GRID_CORNERS.save(false);
        GridCorners.cell(view);
        assertSame(ViewOutlineProvider.PADDED_BOUNDS, view.getOutlineProvider());
        assertTrue("TikTok's own clipping stays", view.getClipToOutline());
    }

    @Test public void withoutAlwaysShowPublishDateTheSwitchCantShowSoNothingChanges() {
        Settings.ROUNDED_GRID_CORNERS.save(true);
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        View view = cell(ViewOutlineProvider.BACKGROUND, false);
        GridCorners.cell(view);
        assertSame(ViewOutlineProvider.BACKGROUND, view.getOutlineProvider());
        assertFalse(view.getClipToOutline());
    }

    @Test public void aMissingViewIsLeftAlone() {
        Settings.ROUNDED_GRID_CORNERS.save(true);
        GridCorners.cell(null);
        assertTrue(HookStatus.missing(GridCorners.FAMILY).isEmpty());
    }

    /** A Favorites collection folder's holder: like RecyclerView.ViewHolder, its root view is itemView. */
    static class FolderHolder {
        public final View itemView;

        FolderHolder(View itemView) {
            this.itemView = itemView;
        }
    }

    /** The folder's own holder, a subclass the way TikTok's sits under ViewHolder. */
    static final class CollectionHolder extends FolderHolder {
        CollectionHolder(View itemView) {
            super(itemView);
        }
    }

    @Test public void aCollectionFolderCellIsRoundedThroughItsHolder() {
        Settings.ROUNDED_GRID_CORNERS.save(true);
        View view = cell(ViewOutlineProvider.BACKGROUND, false);
        GridCorners.collectionCell(new CollectionHolder(view));
        assertTrue("the folder's cover is clipped", view.getClipToOutline());
        assertEquals("8dp at xxhdpi", 24f, outlineOf(view).getRadius(), 0.01f);

        Settings.ROUNDED_GRID_CORNERS.save(false);
        GridCorners.collectionCell(new CollectionHolder(view));
        assertSame(ViewOutlineProvider.BACKGROUND, view.getOutlineProvider());
        assertFalse(view.getClipToOutline());
    }

    @Test public void aCollectionHolderWithoutItsViewIsLeftAlone() {
        Settings.ROUNDED_GRID_CORNERS.save(true);
        GridCorners.collectionCell(null);
        GridCorners.collectionCell(new Object());
        GridCorners.collectionCell(new CollectionHolder(null));
        assertTrue(HookStatus.missing(GridCorners.FAMILY).isEmpty());
    }
}
