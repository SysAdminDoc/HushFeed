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

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
