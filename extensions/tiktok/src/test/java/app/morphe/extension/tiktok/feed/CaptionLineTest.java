package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.main.MainActivity;

import java.lang.reflect.Method;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #410: the country and the post date go on a line of their own under the caption, off the
 * creator's row. The cell below is laid out the way 47.9.2's is: an info column of TikTok's own
 * holding the creator's row and then the caption's frame, and in that frame the generated
 * caption layout, a vertical column holding TikTok's caption block (a LinearLayout subclass
 * with a hidden {@code title} and the {@code desc} caption inside), then a spacer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CaptionLineTest {
    private static final int NAME_ID = 0x7f0a0101;
    private static final int POST_TIME_ID = 0x7f0a0102;
    private static final int DESC_ID = 0x7f0a0103;
    private static final int POST_TIME_COLOR = 0xB3FFFFFF;

    /** TikTok's caption block, which opens and closes on "more". */
    static final class CaptionBlock extends LinearLayout {
        CaptionBlock(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }
    }

    /** The cell's info column, one of TikTok's own LinearLayout subclasses. */
    static final class InfoColumn extends LinearLayout {
        InfoColumn(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }
    }

    /** One video's cell: the creator's row above the caption's frame. */
    private static final class Cell {
        final FrameLayout root;
        final LinearLayout overlay;
        final TextView name;
        final TextView postTime;
        final LinearLayout column;
        final CaptionBlock block;
        final TextView caption;
        /** The caption's own frame, which TikTok hides on a post with no caption. */
        final FrameLayout descRoot;
        /** The frame opening or closing the caption sets to a height of its own. */
        final FrameLayout sized;

        Cell(Context context, String creator, boolean withCaption) {
            this(context, creator, withCaption, false);
        }

        /** With {@code photo}, the name sits three groups deep beside the Photo label, as a photo post's row has it. */
        Cell(Context context, String creator, boolean withCaption, boolean photo) {
            root = new FrameLayout(context);
            overlay = new InfoColumn(context);
            root.addView(overlay);

            LinearLayout row = new LinearLayout(context);
            name = new TextView(context);
            name.setId(NAME_ID);
            name.setText(creator);
            postTime = new TextView(context);
            postTime.setId(POST_TIME_ID);
            postTime.setText("· 4h ago");
            postTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13);
            postTime.setTextColor(POST_TIME_COLOR);
            if (photo) {
                LinearLayout inner = new LinearLayout(context);
                inner.addView(name);
                FrameLayout wrapper = new FrameLayout(context);
                wrapper.addView(inner);
                LinearLayout nameGroup = new LinearLayout(context);
                nameGroup.addView(wrapper);
                TextView label = new TextView(context);
                label.setText("Photo");
                row.addView(nameGroup);
                row.addView(label);
            } else {
                row.addView(name);
            }
            row.addView(postTime);
            overlay.addView(row);

            if (!withCaption) {
                column = null;
                block = null;
                caption = null;
                descRoot = null;
                sized = null;
                return;
            }
            descRoot = new FrameLayout(context);
            LinearLayout descColumn = new LinearLayout(context);
            descColumn.setOrientation(LinearLayout.VERTICAL);
            descRoot.addView(descColumn);
            sized = new FrameLayout(context);
            descColumn.addView(sized, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.BOTTOM);
            sized.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            block = new CaptionBlock(context);
            TextView hiddenTitle = new TextView(context);
            hiddenTitle.setId(NAME_ID);
            hiddenTitle.setVisibility(View.GONE);
            caption = new TextView(context);
            caption.setId(DESC_ID);
            caption.setText("sunday in baku #travel");
            block.addView(hiddenTitle);
            block.addView(caption);
            column.addView(block);
            column.addView(new View(context));
            overlay.addView(descRoot);
        }

        /** The line right under the caption's frame, or null with none there. */
        TextView line() {
            View next = overlay.getChildAt(overlay.indexOfChild(descRoot) + 1);
            return next instanceof CaptionLine.Line ? (TextView) next : null;
        }

        /** The line right under the creator's row, or null with none there. */
        TextView rowLine() {
            View next = overlay.getChildAt(1);
            return next instanceof CaptionLine.Line ? (TextView) next : null;
        }
    }

    private Context context;
    private boolean regionPatched;
    private Locale locale;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        locale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        AuthorRegion.setViewIds(NAME_ID, POST_TIME_ID);
        AuthorRegion.restore();
        CaptionLine.setCaptionId(DESC_ID);
        regionPatched = SettingsStatus.authorRegionEnabled;
        SettingsStatus.authorRegionEnabled = true;
        Settings.COUNTRY_DATE_UNDER_CAPTION.save(true);
    }

    @After
    public void tearDown() {
        CaptionLine.clear();
        AuthorRegion.restore();
        CaptionLine.setCaptionId(0);
        Settings.COUNTRY_DATE_UNDER_CAPTION.resetToDefault();
        Settings.SHOW_AUTHOR_REGION.resetToDefault();
        Settings.REGION_AS_FLAG.resetToDefault();
        Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
        SettingsStatus.authorRegionEnabled = regionPatched;
        Locale.setDefault(locale);
    }

    @Test
    public void theCountryAndDateMoveUnderTheCaptionAndComeBack() {
        Cell cell = new Cell(context, "aittaac", true);

        CaptionLine.show(cell.name, cell.root, "Azerbaijan");
        TextView line = cell.line();
        assertEquals("Azerbaijan · 4h ago", line.getText().toString());
        assertEquals("", cell.postTime.getText().toString());
        assertEquals(View.VISIBLE, cell.postTime.getVisibility());
        assertEquals("aittaac", cell.name.getText().toString());
        // Nothing in the caption's frame is touched, and the line reads like the post time.
        assertEquals(2, cell.column.getChildCount());
        assertSame(cell.block, cell.column.getChildAt(0));
        assertSame(cell.caption, cell.block.getChildAt(1));
        assertEquals(13f, line.getTextSize(), 0f);
        assertEquals(POST_TIME_COLOR, line.getCurrentTextColor());

        // Every layout pass runs this, so it must not stack lines or lose the date.
        CaptionLine.show(cell.name, cell.root, "Azerbaijan");
        CaptionLine.show(cell.name, cell.root, "Azerbaijan");
        assertSame(line, cell.line());
        assertEquals(3, cell.overlay.getChildCount());
        assertEquals("Azerbaijan · 4h ago", line.getText().toString());

        // Switching it off, or leaving the feed, gives the row its date back.
        CaptionLine.clear();
        assertNull(cell.line());
        assertEquals(2, cell.overlay.getChildCount());
        assertEquals("· 4h ago", cell.postTime.getText().toString());
    }

    @Test
    public void aPostTimeTikTokHidLeavesTheLineWithTheCountryOnly() {
        Cell cell = new Cell(context, "aittaac", true);
        cell.postTime.setVisibility(View.GONE);

        CaptionLine.show(cell.name, cell.root, "Azerbaijan");
        assertEquals("Azerbaijan", cell.line().getText().toString());
        assertEquals("· 4h ago", cell.postTime.getText().toString());

        CaptionLine.clear();
        assertEquals(View.GONE, cell.postTime.getVisibility());
        assertEquals("· 4h ago", cell.postTime.getText().toString());
    }

    @Test
    public void withNoCountryTheDateMovesOnItsOwn() {
        Cell cell = new Cell(context, "aittaac", true);

        CaptionLine.show(cell.name, cell.root, null);
        assertEquals("4h ago", cell.line().getText().toString());
        assertEquals("", cell.postTime.getText().toString());
    }

    @Test
    public void withNothingToSayNoLineShows() {
        Cell cell = new Cell(context, "aittaac", true);
        cell.postTime.setVisibility(View.GONE);

        CaptionLine.show(cell.name, cell.root, null);
        assertFalse(hasLine(cell.root));
        assertEquals("· 4h ago", cell.postTime.getText().toString());
    }

    @Test
    public void aRebindPutsTheNewDateOnTheLine() {
        Cell cell = new Cell(context, "aittaac", true);
        CaptionLine.show(cell.name, cell.root, "Azerbaijan");

        // The cell is reused for the next video, and TikTok writes its post time.
        cell.postTime.setText("· 2d ago");
        CaptionLine.show(cell.name, cell.root, "Georgia");
        assertEquals("Georgia · 2d ago", cell.line().getText().toString());
        assertEquals("", cell.postTime.getText().toString());

        CaptionLine.clear();
        assertEquals("· 2d ago", cell.postTime.getText().toString());
    }

    @Test
    public void aRebindThatHidesThePostTimeDropsTheDate() {
        Cell cell = new Cell(context, "aittaac", true);
        CaptionLine.show(cell.name, cell.root, "Azerbaijan");

        cell.postTime.setText("· 1y ago");
        cell.postTime.setVisibility(View.GONE);
        CaptionLine.show(cell.name, cell.root, "Azerbaijan");
        assertEquals("Azerbaijan", cell.line().getText().toString());

        // What TikTok wrote since is never written over.
        CaptionLine.clear();
        assertEquals("· 1y ago", cell.postTime.getText().toString());
        assertEquals(View.GONE, cell.postTime.getVisibility());
    }

    @Test
    public void withNoCaptionTheLineGoesUnderTheRow() {
        Cell cell = new Cell(context, "aittaac", false);

        assertTrue(CaptionLine.show(cell.name, cell.root, "Azerbaijan"));
        assertEquals("Azerbaijan · 4h ago", cell.rowLine().getText().toString());
        assertEquals("", cell.postTime.getText().toString());

        CaptionLine.clear();
        assertNull(cell.rowLine());
        assertEquals("· 4h ago", cell.postTime.getText().toString());
    }

    @Test
    public void withNoCaptionAndNothingToSayNoLineShows() {
        Cell cell = new Cell(context, "aittaac", false);
        cell.postTime.setVisibility(View.GONE);

        assertFalse(CaptionLine.show(cell.name, cell.root, null));
        assertFalse(hasLine(cell.root));
    }

    @Test
    public void aCaptionTikTokHidPutsTheLineUnderTheRow() {
        // A post with no caption keeps the caption's views, in a block TikTok hides.
        Cell cell = new Cell(context, "junior", true);
        cell.descRoot.setVisibility(View.GONE);

        assertTrue(CaptionLine.show(cell.name, cell.root, "Brazil"));
        TextView underRow = cell.rowLine();
        assertEquals("Brazil · 4h ago", underRow.getText().toString());
        assertEquals("", cell.postTime.getText().toString());
        assertNull(cell.line());

        // The cell is reused for a post with a caption, and then for one without again. Both
        // spots are in the same column, which only ever holds one line.
        cell.descRoot.setVisibility(View.VISIBLE);
        assertTrue(CaptionLine.show(cell.name, cell.root, "Brazil"));
        assertEquals("Brazil · 4h ago", cell.line().getText().toString());
        assertEquals(View.VISIBLE, cell.line().getVisibility());
        assertNull(cell.rowLine());
        assertEquals(3, cell.overlay.getChildCount());

        cell.descRoot.setVisibility(View.GONE);
        assertTrue(CaptionLine.show(cell.name, cell.root, "Brazil"));
        assertEquals("Brazil · 4h ago", cell.rowLine().getText().toString());
        assertEquals(View.VISIBLE, cell.rowLine().getVisibility());
        assertNull(cell.line());
        assertEquals(3, cell.overlay.getChildCount());
    }

    @Test
    public void aPhotoPostsDateMovesToo() {
        // The photo row holds the name three groups deep, with the post time a level above them.
        Cell cell = new Cell(context, "disco..inspo", true, true);

        assertTrue(CaptionLine.show(cell.name, cell.root, "United States"));
        assertEquals("United States · 4h ago", cell.line().getText().toString());
        assertEquals("", cell.postTime.getText().toString());

        CaptionLine.clear();
        assertEquals("· 4h ago", cell.postTime.getText().toString());
    }

    @Test
    public void aCaptionTikTokSizedOnOpeningKeepsItsHeightAndTheLineItsOwn() {
        // Opening or closing the caption sets its frame to the height TikTok works out from the
        // caption's text and the "less" spacer alone, nothing else (#457).
        Cell cell = new Cell(context, "happypuppies47", true);
        View spacer = cell.column.getChildAt(1);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 67));
        cell.caption.setHeight(1320);
        cell.sized.getLayoutParams().height = 1320 + 67;

        assertTrue(CaptionLine.show(cell.name, cell.root, "United States"));
        TextView line = cell.line();
        line.setMinHeight(58);
        int margin = ((LinearLayout.LayoutParams) line.getLayoutParams()).topMargin;

        cell.overlay.measure(View.MeasureSpec.makeMeasureSpec(821, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        cell.overlay.layout(0, 0, 821, cell.overlay.getMeasuredHeight());
        assertEquals(1320, cell.block.getMeasuredHeight());
        assertEquals(67, spacer.getMeasuredHeight());
        assertEquals(58, line.getMeasuredHeight());
        assertEquals(cell.descRoot.getBottom() + margin, line.getTop());

        // Closed again, the frame gets the closed caption's height and the line follows it up.
        cell.caption.setHeight(106);
        cell.sized.getLayoutParams().height = 106;
        cell.sized.requestLayout();
        assertTrue(CaptionLine.show(cell.name, cell.root, "United States"));
        assertSame(line, cell.line());
        cell.overlay.measure(View.MeasureSpec.makeMeasureSpec(821, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        cell.overlay.layout(0, 0, 821, cell.overlay.getMeasuredHeight());
        assertEquals(106, cell.block.getMeasuredHeight());
        assertEquals(58, line.getMeasuredHeight());
        assertEquals(cell.descRoot.getBottom() + margin, line.getTop());
    }

    @Test
    public void eachCellGetsItsOwnLineAndOnlyTheVideosShows() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            activity.setContentView(root);
            VerticalViewPager pager = new VerticalViewPager(activity);
            root.addView(pager);
            Cell first = new Cell(activity, "first creator", true);
            Cell second = new Cell(activity, "second creator", true);
            pager.addView(first.root);
            pager.addView(second.root);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View content = activity.findViewById(android.R.id.content);

            CaptionLine.show(first.name, content, "Azerbaijan");
            assertEquals("Azerbaijan · 4h ago", first.line().getText().toString());
            assertNull(second.line());

            // The second cell's own caption gets it, not the first one in the tree.
            CaptionLine.show(second.name, content, "Georgia");
            assertEquals("Georgia · 4h ago", second.line().getText().toString());
            assertEquals(View.VISIBLE, second.line().getVisibility());
            assertEquals(View.GONE, first.line().getVisibility());

            // Swiping back up shows the first one's line again, date and all.
            CaptionLine.show(first.name, content, "Azerbaijan");
            assertEquals(View.VISIBLE, first.line().getVisibility());
            assertEquals("Azerbaijan · 4h ago", first.line().getText().toString());
            assertEquals(View.GONE, second.line().getVisibility());

            CaptionLine.clear();
            assertEquals("· 4h ago", first.postTime.getText().toString());
            assertEquals("· 4h ago", second.postTime.getText().toString());
            assertFalse(hasLine(root));
        }
    }

    @Test
    public void theCountryIsWrittenOutAndCanLeadWithItsFlag() {
        Settings.REGION_AS_FLAG.save(false);
        assertEquals("Azerbaijan", CaptionLine.countryLabel("AZ"));
        assertEquals("Mars", CaptionLine.countryLabel("Mars"));
        assertNull(CaptionLine.countryLabel(null));

        Settings.REGION_AS_FLAG.save(true);
        String flag = new StringBuilder().appendCodePoint(0x1F1E6)
                .appendCodePoint(0x1F1E6 + 25).toString();
        assertEquals(flag + " Azerbaijan", CaptionLine.countryLabel("AZ"));
    }

    @Test
    public void theNameLosesTheCountryWhileTheLineHasIt() {
        AuthorRegionTest.Clip clip = new AuthorRegionTest.Clip("v1", "aittaac", "aittaac", "AZ");
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);

        assertNull(AuthorRegion.decoration(clip));
        assertEquals("Azerbaijan", AuthorRegion.lineCountry(clip));

        Settings.COUNTRY_DATE_UNDER_CAPTION.save(false);
        assertEquals("AZ", AuthorRegion.decoration(clip)[1]);

        // Without Show author region the switch does nothing.
        Settings.COUNTRY_DATE_UNDER_CAPTION.save(true);
        SettingsStatus.authorRegionEnabled = false;
        assertFalse(CaptionLine.wanted());
        assertNull(AuthorRegion.lineCountry(clip));
    }

    @Test
    public void aVideoOpenedInTheDetailPagerGetsItsLine() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);
        try (ActivityController<MainActivity> feed = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            Utils.setContext(feed.get());
            AuthorRegion.install(feed.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            feed.pause();

            try (ActivityController<DetailActivity> page = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
                Cell cell = new Cell(page.get(), "aittaac", true);
                page.get().setContentView(cell.root);
                play(new AuthorRegionTest.Clip("opened", "aittaac", "aittaac", "AZ"));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                layOut(cell.root.getRootView());
                cell.root.getViewTreeObserver().dispatchOnGlobalLayout();

                assertEquals("aittaac", cell.name.getText().toString());
                assertEquals("Azerbaijan · 4h ago", cell.line().getText().toString());
                assertEquals("", cell.postTime.getText().toString());

                // With the caption hidden the line goes right under the creator's row.
                cell.descRoot.setVisibility(View.GONE);
                cell.root.getViewTreeObserver().dispatchOnGlobalLayout();
                assertEquals("aittaac", cell.name.getText().toString());
                assertEquals("Azerbaijan · 4h ago", cell.rowLine().getText().toString());
                assertEquals("", cell.postTime.getText().toString());
                cell.descRoot.setVisibility(View.VISIBLE);
                cell.root.getViewTreeObserver().dispatchOnGlobalLayout();
                assertEquals("aittaac", cell.name.getText().toString());
                assertEquals("Azerbaijan · 4h ago", cell.line().getText().toString());

                // Off again, and the country goes back beside the name with the date in its row.
                Settings.COUNTRY_DATE_UNDER_CAPTION.save(false);
                cell.root.getViewTreeObserver().dispatchOnGlobalLayout();
                assertEquals("aittaac · AZ", cell.name.getText().toString());
                assertNull(cell.line());
                assertEquals("· 4h ago", cell.postTime.getText().toString());
                page.pause().stop();
            }
        } finally {
            reset.invoke(null);
        }
    }

    private static boolean hasLine(View view) {
        if (view instanceof CaptionLine.Line) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasLine(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /** Tells CurrentVideoAuthor this clip is the one playing, the way the player hooks do. */
    private static void play(AuthorRegionTest.Clip clip) throws Exception {
        Method update = CurrentVideoAuthor.class.getDeclaredMethod("update", Object.class);
        update.setAccessible(true);
        update.invoke(null, new AuthorRegionTest.Params(clip));
        Method onPlaying = CurrentVideoAuthor.class.getDeclaredMethod("onPlaying", String.class);
        onPlaying.setAccessible(true);
        onPlaying.invoke(null, clip.aid);
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
    }
}
