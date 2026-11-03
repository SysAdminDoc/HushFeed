/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class LocalWatchHistoryPreferenceTest {
    /** The video page opens over the list, where the app's link would close settings on the way. */
    @Test
    public void aRowOpensTheVideoPageOverTheList() {
        Activity settings = Robolectric.buildActivity(Activity.class).setup().get();

        LocalWatchHistoryPreference.open(settings, "7701777817456987405");

        Intent started = Shadows.shadowOf(settings).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.ss.android.ugc.aweme.detail.ui.DetailActivity",
                started.getComponent().getClassName());
        assertEquals("7701777817456987405", started.getStringExtra("id"));
        assertFalse((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void withoutAnActivityItStartsItsOwnTask() {
        Application app = RuntimeEnvironment.getApplication();

        LocalWatchHistoryPreference.open(app, "7701777817456987405");

        Intent started = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("7701777817456987405", started.getStringExtra("id"));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
