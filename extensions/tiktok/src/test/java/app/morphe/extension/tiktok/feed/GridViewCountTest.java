/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * #449: Hide view counts on profile grids. The count goes from each cell's text, the play icon
 * TikTok puts in front of it goes from the view, and the date, length and rate that are on move
 * up to the first line.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GridViewCountTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** 2025-10-07 15:04:05 UTC. */
    private static final long POSTED = 1_759_849_445L;
    private static final int BAR_HEIGHT = 150;

    public static final class Stats extends AwemeStatistics {
        @Override public long getPlayCount() { return 1000; }
        @Override public long getDiggCount() { return 40; }
        @Override public long getCommentCount() { return 2; }
        @Override public long getShareCount() { return 0; }
        @Override public long getCollectCount() { return 0; }
    }

    public static final class Clip {
        public Object getDuration() { return 42_000; }
    }

    /** A grid cell's video: 1,000 views, 42 likes and comments, 42 seconds long. */
    public static final class Cell extends Aweme {
        public Object getVideo() { return new Clip(); }
        @Override public AwemeStatistics getStatistics() { return new Stats(); }
        @Override public List getImageInfos() { return null; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
        @Override public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
        @Override public long getCreateTime() { return POSTED; }
        @Override public String getAid() { return "1"; }
    }

    private Locale locale;
    private TimeZone zone;
    private boolean publishDateEnabled;
    private boolean engagementEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        publishDateEnabled = SettingsStatus.alwaysShowPublishDateEnabled;
        engagementEnabled = SettingsStatus.engagementRateEnabled;
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SettingsStatus.alwaysShowPublishDateEnabled = true;
        SettingsStatus.engagementRateEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        SettingsStatus.alwaysShowPublishDateEnabled = publishDateEnabled;
        SettingsStatus.engagementRateEnabled = engagementEnabled;
        Settings.HIDE_GRID_VIEW_COUNT.resetToDefault();
        Settings.VIDEO_LENGTH_ON_GRID.resetToDefault();
        Settings.PUBLISH_DATE_ON_GRID.resetToDefault();
        Settings.PUBLISH_DATE_GRID_SPOT.resetToDefault();
        Settings.SHOW_ENGAGEMENT_RATE.resetToDefault();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    @Test public void theSwitchStartsOffAndTheCountStays() {
        assertFalse(Settings.HIDE_GRID_VIEW_COUNT.defaultValue);
        assertEquals("1K", ProfileGridCount.text("1K", new Cell()));
    }

    @Test public void theCountGoesAndACellWithNothingElseIsBlank() {
        Settings.HIDE_GRID_VIEW_COUNT.save(true);
        assertEquals("", ProfileGridCount.text("1K", new Cell()));
        assertNull("TikTok's own null still passes through", ProfileGridCount.text(null, new Cell()));
    }

    @Test public void theDateAndLengthMoveUpToTheFirstLine() {
        Settings.HIDE_GRID_VIEW_COUNT.save(true);
        Settings.VIDEO_LENGTH_ON_GRID.save(true);
        assertEquals("0:42", ProfileGridCount.text("1K", new Cell()));
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        String date = AlwaysShowPublishDatePatch.gridDate(new Cell());
        assertNotNull(date);
        assertEquals(date + " · 0:42", ProfileGridCount.text("1K", new Cell()));
    }

    @Test public void theRateStaysOnItsOwnAboveTheDate() {
        Settings.HIDE_GRID_VIEW_COUNT.save(true);
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        assertEquals("4.2%", ProfileGridCount.text("1K", new Cell()));
        Settings.VIDEO_LENGTH_ON_GRID.save(true);
        assertEquals("4.2%\n0:42", ProfileGridCount.text("1K", new Cell()));
        Settings.HIDE_GRID_VIEW_COUNT.save(false);
        assertEquals("1K · 4.2%\n0:42", ProfileGridCount.text("1K", new Cell()));
    }

    @Test public void thePlayIconGoesOnlyWhileTheCountIsHidden() {
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        Drawable icon = new ColorDrawable();
        view.setCompoundDrawablesRelative(icon, null, null, null);
        ProfileGridCount.show(view, "1K");
        assertEquals("1K", view.getText().toString());
        assertEquals("the icon went with the count shown", icon, view.getCompoundDrawablesRelative()[0]);

        Settings.HIDE_GRID_VIEW_COUNT.save(true);
        // TikTok puts the icon back on every bind, right before this call.
        view.setCompoundDrawablesRelative(icon, null, null, null);
        ProfileGridCount.show(view, "");
        assertEquals("", view.getText().toString());
        assertArrayEquals(new Drawable[4], view.getCompoundDrawablesRelative());
    }

    /**
     * #519: with a date or length on a line under the count, Android centered the play icon on
     * the whole label, between the two lines. It stays on the count's line now.
     */
    @Test public void thePlayIconStaysOnTheCountsLineAboveADate() {
        TextView view = countInBar();
        Drawable icon = new ColorDrawable(0xFFFFFFFF);
        icon.setBounds(0, 0, 24, 24);
        view.setCompoundDrawablePadding(6);
        view.setCompoundDrawablesRelative(icon, null, null, null);
        ProfileGridCount.show(view, "1K\nOct 7, 2025");
        assertNull("no longer centered on the whole label", view.getCompoundDrawablesRelative()[0]);
        CharSequence shown = view.getText();
        assertEquals(" 1K\nOct 7, 2025", shown.toString());
        ImageSpan[] spans = ((Spanned) shown).getSpans(0, shown.length(), ImageSpan.class);
        assertEquals(1, spans.length);
        assertEquals("on the first line, before the count", 0, ((Spanned) shown).getSpanStart(spans[0]));
        assertEquals("TikTok's size plus its gap", new Rect(0, 0, 30, 24), spans[0].getDrawable().getBounds());

        // The next bind has one line: TikTok puts the icon back, and there it stays.
        view.setCompoundDrawablesRelative(icon, null, null, null);
        ProfileGridCount.show(view, "1K");
        assertEquals(icon, view.getCompoundDrawablesRelative()[0]);
        assertEquals("1K", view.getText().toString());
    }

    /** Issue #546: above the count, the date takes the length along, and after it the length stays under. */
    @Test public void theDateCanSitBesideOrAboveTheCountWithTheLength() {
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        Settings.VIDEO_LENGTH_ON_GRID.save(true);
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        String date = AlwaysShowPublishDatePatch.gridDate(new Cell());
        assertNotNull(date);
        assertEquals("1K · 4.2%\n" + date + " · 0:42", ProfileGridCount.text("1K", new Cell()));
        Settings.PUBLISH_DATE_GRID_SPOT.save("beside");
        assertEquals("1K · 4.2% · " + date + "\n0:42", ProfileGridCount.text("1K", new Cell()));
        Settings.PUBLISH_DATE_GRID_SPOT.save("above");
        assertEquals(date + " · 0:42\n1K · 4.2%", ProfileGridCount.text("1K", new Cell()));

        // With nothing on the count's line, the date and length lead as before.
        Settings.SHOW_ENGAGEMENT_RATE.save(false);
        Settings.HIDE_GRID_VIEW_COUNT.save(true);
        assertEquals(date + " · 0:42", ProfileGridCount.text("1K", new Cell()));
        // The spot follows the grid date only: with it off, the length stays under the count.
        Settings.HIDE_GRID_VIEW_COUNT.save(false);
        Settings.PUBLISH_DATE_ON_GRID.save(false);
        assertEquals("1K\n0:42", ProfileGridCount.text("1K", new Cell()));
    }

    /** Issue #546: with the date above the count, the play icon goes on the count's line, the last one. */
    @Test public void thePlayIconFollowsTheCountUnderADateAbove() {
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        Settings.PUBLISH_DATE_GRID_SPOT.save("above");
        TextView view = countInBar();
        Drawable icon = new ColorDrawable(0xFFFFFFFF);
        icon.setBounds(0, 0, 24, 24);
        view.setCompoundDrawablePadding(6);
        view.setCompoundDrawablesRelative(icon, null, null, null);
        ProfileGridCount.show(view, "Oct 7, 2025\n1K");
        assertNull(view.getCompoundDrawablesRelative()[0]);
        CharSequence shown = view.getText();
        assertEquals("Oct 7, 2025\n 1K", shown.toString());
        ImageSpan[] spans = ((Spanned) shown).getSpans(0, shown.length(), ImageSpan.class);
        assertEquals(1, spans.length);
        assertEquals("before the count", "Oct 7, 2025\n".length(), ((Spanned) shown).getSpanStart(spans[0]));
    }

    /** The count's view inside TikTok's cell: a weighted child of a bar with a fixed height. */
    private static TextView countInBar() {
        Context context = RuntimeEnvironment.getApplication();
        TextView view = new TextView(context);
        LinearLayout bar = new LinearLayout(context);
        bar.addView(view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        new FrameLayout(context).addView(bar,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT));
        return view;
    }

    private static View barOf(TextView view) {
        return (View) view.getParent();
    }

    @Test public void aSecondLineLetsTheBarGrowAndKeepsItsHeightAsTheFloor() {
        TextView view = countInBar();
        ProfileGridCount.show(view, "1K\nOct 7, 2025 · 0:42");
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, barOf(view).getLayoutParams().height);
        assertEquals(BAR_HEIGHT, barOf(view).getMinimumHeight());

        // A recycled cell comes back already grown, and its floor has to survive that.
        ProfileGridCount.show(view, "1K\n0:42");
        assertEquals(BAR_HEIGHT, barOf(view).getMinimumHeight());
    }

    @Test public void aLabelWithoutTheCountCanWrapSoItsBarGrowsToo() {
        Settings.HIDE_GRID_VIEW_COUNT.save(true);
        TextView view = countInBar();
        ProfileGridCount.show(view, "Oct 7, 2025 · 0:42");
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, barOf(view).getLayoutParams().height);
    }

    @Test public void aPlainCountLeavesTikToksBarAlone() {
        TextView view = countInBar();
        ProfileGridCount.show(view, "1K");
        assertEquals(BAR_HEIGHT, barOf(view).getLayoutParams().height);
        assertEquals(0, barOf(view).getMinimumHeight());
    }

    @Test public void pauseAndAMissingPatchKeepTheCount() {
        Settings.HIDE_GRID_VIEW_COUNT.save(true);
        setPaused(true);
        assertEquals("1K", ProfileGridCount.text("1K", new Cell()));
        setPaused(false);
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        assertEquals("1K", ProfileGridCount.text("1K", new Cell()));
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        Drawable icon = new ColorDrawable();
        view.setCompoundDrawablesRelative(icon, null, null, null);
        ProfileGridCount.show(view, "1K");
        assertEquals(icon, view.getCompoundDrawablesRelative()[0]);
    }
}
