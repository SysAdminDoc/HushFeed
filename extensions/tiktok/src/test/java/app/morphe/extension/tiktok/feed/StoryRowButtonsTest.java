package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The Create circle's bind and the All button's show call, fed views shaped like TikTok's (#308, #309). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoryRowButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** What a RecyclerView ViewHolder gives the bind: a real-named, public, final itemView. */
    public static final class Cell {
        public final View itemView;

        Cell(View itemView) {
            this.itemView = itemView;
        }
    }

    @After public void restoreSwitches() {
        Settings.HIDE_STORY_CREATE.resetToDefault();
        Settings.HIDE_FOLLOWING_ALL_BUTTON.resetToDefault();
    }

    /** A recycled cell can hold the Create circle on one bind and someone's stories on the next. */
    @Test public void theCreateCircleCollapsesAndARecycledCellComesBackAtItsOwnSize() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LinearLayout row = new LinearLayout(activity);
            View item = new View(activity);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(180, 240);
            size.setMargins(4, 0, 4, 0);
            row.addView(item, size);
            Cell cell = new Cell(item);

            Settings.HIDE_STORY_CREATE.save(true);
            StoryRowButtons.onStoryCellOwner(cell, 1);
            StoryRowButtons.onOwnCellAnswer(cell, 1);
            assertEquals("the circle stayed visible", View.GONE, item.getVisibility());
            assertEquals("the circle kept its width", 0, size.width);
            assertEquals("the circle kept its margin", 0, size.leftMargin);

            StoryRowButtons.onStoryCellOwner(cell, 0);
            assertEquals("someone's stories stayed hidden", View.VISIBLE, item.getVisibility());
            assertEquals(180, size.width);
            assertEquals(240, size.height);
            assertEquals(4, size.leftMargin);
        }
    }

    /** Posting a Story turns the same cell from the Create circle into the viewer's own stories. */
    @Test public void theViewersOwnCellComesBackOnceItHasAStory() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            View item = new View(activity);
            LinearLayout row = new LinearLayout(activity);
            row.addView(item, new LinearLayout.LayoutParams(180, 240));
            Cell cell = new Cell(item);

            Settings.HIDE_STORY_CREATE.save(true);
            StoryRowButtons.onStoryCellOwner(cell, 1);
            StoryRowButtons.onOwnCellAnswer(cell, 1);
            assertEquals(View.GONE, item.getVisibility());

            StoryRowButtons.onStoryCellOwner(cell, 1);
            StoryRowButtons.onOwnCellAnswer(cell, 0);
            assertEquals(View.VISIBLE, item.getVisibility());
            assertEquals(180, item.getLayoutParams().width);
        }
    }

    @Test public void theCreateCircleStaysWithTheSwitchOffAndOtherCellsStayWithItOn() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            View item = new View(activity);
            LinearLayout row = new LinearLayout(activity);
            row.addView(item, new LinearLayout.LayoutParams(180, 240));
            Cell cell = new Cell(item);

            StoryRowButtons.onStoryCellOwner(cell, 1);
            StoryRowButtons.onOwnCellAnswer(cell, 1);
            assertEquals(View.VISIBLE, item.getVisibility());
            assertEquals(180, item.getLayoutParams().width);

            Settings.HIDE_STORY_CREATE.save(true);
            StoryRowButtons.onStoryCellOwner(cell, 0);
            assertEquals(View.VISIBLE, item.getVisibility());
            assertEquals(180, item.getLayoutParams().width);
        }
    }

    @Test public void allIsHiddenOnlyWithItsSwitchOn() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            View button = new View(activity);

            StoryRowButtons.hideAllButton(button);
            assertEquals(View.VISIBLE, button.getVisibility());

            Settings.HIDE_FOLLOWING_ALL_BUTTON.save(true);
            StoryRowButtons.hideAllButton(button);
            assertEquals(View.GONE, button.getVisibility());
        }
    }
}
