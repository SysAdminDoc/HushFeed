/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ListView;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "en")
public class VideoListDialogTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final List<LocalWatchHistory.Entry> kept = new ArrayList<>();

    private final VideoListDialog.Source source = new VideoListDialog.Source() {
        @Override
        public String title(Context context) {
            return "Videos";
        }

        @Override
        public String empty(Context context) {
            return "Nothing yet";
        }

        @Override
        public void cleared(Context context) {
        }

        @Override
        public void load(LocalWatchHistory.Listener listener) {
            listener.onRows(new ArrayList<>(kept));
        }

        @Override
        public void clear(LocalWatchHistory.Listener listener) {
        }

        @Override
        public void restore(List<LocalWatchHistory.Entry> rows) {
        }
    };

    /** A like taken back on the video's page is gone from the list once Back returns to it. */
    @Test
    public void backFromAVideoTheListOpenedShowsTheListAsItIsNow() throws Exception {
        Activity page = Robolectric.buildActivity(Activity.class).setup().get();
        kept.add(new LocalWatchHistory.Entry("7701777817456987405", "TikTok", "a caption", 1_000L));
        VideoListDialog.show(page, "video_list_test", source, false);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertNotNull(list);
        assertEquals(1, list.getAdapter().getCount());

        kept.clear();
        focus(dialog, false);
        focus(dialog, true);
        assertEquals("focus alone doesn't read the list again", 1, list.getAdapter().getCount());

        list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0);
        assertNotNull(Shadows.shadowOf(page).getNextStartedActivity());
        focus(dialog, false);
        focus(dialog, true);
        assertEquals(0, list.getAdapter().getCount());
    }

    private static void focus(AlertDialog dialog, boolean focused) throws Exception {
        ViewTreeObserver observer = dialog.getWindow().getDecorView().getViewTreeObserver();
        Method dispatch = ViewTreeObserver.class.getDeclaredMethod("dispatchOnWindowFocusChange", boolean.class);
        dispatch.setAccessible(true);
        dispatch.invoke(observer, focused);
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = find(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
