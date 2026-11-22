package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewOutlineProvider;
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
        HookStatus.clear();
        context = RuntimeEnvironment.getApplication();
    }

    @After public void tearDown() {
        SettingsStatus.alwaysShowPublishDateEnabled = publishDate;
        Settings.ROUNDED_GRID_CORNERS.resetToDefault();
        HookStatus.clear();
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
}
