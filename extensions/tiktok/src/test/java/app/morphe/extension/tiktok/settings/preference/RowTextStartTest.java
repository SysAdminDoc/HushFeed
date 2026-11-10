package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Hush has no right-to-left translation, so an Arabic or Hebrew phone shows the settings in
 * English inside mirrored rows. Seen on a phone in forced RTL: the switch row's title sat at the
 * right while its English summary started at the left, across the row from it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class RowTextStartTest {
    private Activity activity;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test public void aRowsTitleAndSummaryBothStartOnTheMirroredSide() {
        SwitchPreference pause = new SwitchPreference(activity);
        pause.setKey("pause_row");
        pause.setTitle("Pause Hushfeed");
        pause.setSummary("Turns every Hushfeed change off until you turn it back on.");
        Preference opensAPage = new Preference(activity);
        opensAPage.setKey("opens_a_page");
        opensAPage.setTitle("Diagnostics");
        opensAPage.setSummary("Logs and the report to send");
        PreferenceScreen screen = screenWith(pause, opensAPage);

        SettingsListAdapter adapter = new SettingsListAdapter(screen.getRootAdapter());
        try {
            for (int position = 0; position < 2; position++) {
                View row = laidOutInRtl(adapter, position);
                for (int id : new int[]{android.R.id.title, android.R.id.summary}) {
                    TextView line = row.findViewById(id);
                    assertNotNull("row " + position + " has no line " + id, line);
                    assertEquals("row " + position + " did not take the page's direction",
                            View.LAYOUT_DIRECTION_RTL, line.getLayoutDirection());
                    assertEquals("row " + position + "'s line " + id
                                    + " still follows its English text instead of the row",
                            View.TEXT_ALIGNMENT_VIEW_START, line.getTextAlignment());
                }
                // A short summary in a wide row is where it showed: the line ended mid-row.
                TextView summary = row.findViewById(android.R.id.summary);
                assertTrue("row " + position + "'s summary was never laid out",
                        summary.getLayout() != null && summary.getLayout().getWidth() > 0);
                assertEquals("row " + position + "'s summary doesn't end at the row's start edge",
                        summary.getLayout().getWidth(), summary.getLayout().getLineRight(0), 1f);
            }
        } finally {
            adapter.dispose();
        }
    }

    @Test public void aSectionsHeadingStartsOnTheSameSideAsItsRows() {
        PreferenceCategory category = new PreferenceCategory(activity);
        category.setKey("right_column");
        category.setTitle("RIGHT COLUMN");
        SectionHeadingPreference heading = new SectionHeadingPreference(activity, "LIVE");
        PreferenceScreen screen = screenWith(category, heading);

        SettingsListAdapter adapter = new SettingsListAdapter(screen.getRootAdapter());
        try {
            for (int position = 0; position < 2; position++) {
                TextView title = laidOutInRtl(adapter, position).findViewById(android.R.id.title);
                assertNotNull("heading " + position + " has no title", title);
                assertEquals("heading " + position + " still follows its English text",
                        View.TEXT_ALIGNMENT_VIEW_START, title.getTextAlignment());
                assertEquals("heading " + position + " doesn't end at the row's start edge",
                        title.getLayout().getWidth(), title.getLayout().getLineRight(0), 1f);
            }
        } finally {
            adapter.dispose();
        }
    }

    private View laidOutInRtl(SettingsListAdapter adapter, int position) {
        FrameLayout page = new FrameLayout(activity);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ListView list = new ListView(activity);
        page.addView(list);
        View row = adapter.getView(position, null, list);
        page.addView(row, new FrameLayout.LayoutParams(1000, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        page.layout(0, 0, page.getMeasuredWidth(), page.getMeasuredHeight());
        return row;
    }

    private PreferenceScreen screenWith(Preference... rows) {
        HostFragment fragment = new HostFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        PreferenceScreen screen = fragment.getPreferenceManager().createPreferenceScreen(activity);
        fragment.setPreferenceScreen(screen);
        for (Preference row : rows) screen.addPreference(row);
        return screen;
    }

    /** A preference fragment only so the framework will hand out a PreferenceScreen. */
    public static class HostFragment extends android.preference.PreferenceFragment {
    }
}
