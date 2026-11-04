/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Looper;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.preference.RadiusScale;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The Lab's tabs, filter, search and rows stay on the settings radius scale, with no pill left. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LabRadiusScaleTest {
    @Before public void resetSharedState() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Utils.awaitBackgroundTasksForTests();
        FeatureGateDetailFragment.awaitChangesForTests();
        FeatureGateLabFragment.awaitSearchForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
        FeatureGateCatalog.resetForTests();
        FeatureGateLabFragment.resetForTests();
        FeatureGateLabSession.resetForTests();
        FeatureGateLabUndo.resetForTests();
        SettingsManagerObservationRecorder.clear();
    }

    @After public void leaveNothingBehind() throws Exception {
        FeatureGateLabFragment.awaitSearchForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        Utils.setIsDarkModeEnabled(true);
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void theLabAndItsTabsHaveNoPillInEitherLook() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(dark);
            String look = dark ? "dark" : "light";
            try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setContext(activity);
                FeatureGateLabStore.resetAllLabData();
                FeatureGateLabSession.begin();
                cache(entry("gate"));

                FeatureGateLabFragment lab = new FeatureGateLabFragment();
                attach(activity, lab);
                settle();
                RadiusScale.assertOnScale(lab.getView(), look + " Lab");
                ListView list = find(lab.getView(), ListView.class);
                ListAdapter adapter = list.getAdapter();
                for (int i = 0; i < adapter.getCount(); i++) {
                    RadiusScale.assertOnScale(adapter.getView(i, null, list), look + " Lab row " + i);
                }

                for (boolean selected : new boolean[]{true, false}) {
                    TextView tab = new TextView(activity);
                    FeatureGateLabUi.styleTab(tab, selected);
                    RadiusScale.assertOnScale(tab, look + (selected ? " picked tab" : " tab"));
                }
            }
        }
    }

    private static FeatureGateCatalog.Entry entry(String key) {
        return new FeatureGateCatalog.Entry(key, key, "abmock", "BOOLEAN", true, true,
                List.of("false"), List.of(), List.of(), "", "", true, "false", "BOOLEAN");
    }

    private static void cache(FeatureGateCatalog.Entry entry) throws Exception {
        var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cached.setAccessible(true);
        cached.set(null, new FeatureGateCatalog.Snapshot(
                List.of(entry), Map.of(entry.identity(), entry), 1, 0, true));
    }

    private static void attach(Activity activity, Fragment fragment) {
        FragmentTransaction transaction = activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment);
        transaction.commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void settle() throws Exception {
        FeatureGateLabFragment.awaitFileIoForTests();
        FeatureGateCatalog.awaitForTests();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
