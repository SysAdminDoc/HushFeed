package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rebuild message search: what it answers before the patch fills its bridges, and the Inbox row
 * that starts it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MessageSearchRebuildTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String TITLE = "Rebuild message search";

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void unpatchedThereIsNoEngineAndNothingRuns() {
        assertEquals(MessageSearchRebuild.UNAVAILABLE, MessageSearchRebuild.run());
        assertFalse(MessageSearchRebuild.isRunning());
        assertFalse("nothing to stop", MessageSearchRebuild.stop());
    }

    @Test public void theAnswerComesBackOnTheMainThread() throws InterruptedException {
        AtomicInteger outcome = new AtomicInteger(Integer.MIN_VALUE);
        AtomicInteger thread = new AtomicInteger();
        assertTrue(MessageSearchRebuild.rebuild(result -> {
            outcome.set(result);
            thread.set(Looper.myLooper() == Looper.getMainLooper() ? 1 : 2);
        }));
        awaitMain(() -> outcome.get() != Integer.MIN_VALUE);
        assertEquals(MessageSearchRebuild.UNAVAILABLE, outcome.get());
        assertEquals("main thread", 1, thread.get());
    }

    @Test public void theRowShowsOnlyWhereThePatchReachedTheEngine() throws InterruptedException {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean rebuild = SettingsStatus.messageSearchRebuildEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.messageSearchRebuildEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, without);
            assertNull(byTitle(without, TITLE));

            SettingsStatus.messageSearchRebuildEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, with);
            Preference row = byTitle(with, TITLE);
            assertNotNull(row);
            assertEquals("action_rebuild_message_search", row.getKey());
            assertTrue(row instanceof ImmediateAction && ((ImmediateAction) row).actsOnTap());
            String summary = row.getSummary().toString();
            assertTrue(summary, summary.contains("leave search"));
            assertFalse(summary, summary.indexOf((char) 0x2014) >= 0 || summary.indexOf((char) 0x2013) >= 0);

            // A tap holds the row until the answer is back, then lets it go again.
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            assertFalse("held while it runs", row.isEnabled());
            awaitMain(row::isEnabled);
            assertFalse(MessageSearchRebuild.isRunning());
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.messageSearchRebuildEnabled = rebuild;
        }
    }

    private interface Condition {
        boolean met();
    }

    /** Runs the main looper until {@code condition} holds, for up to ten seconds. */
    private static void awaitMain(Condition condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.met()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for the main thread");
    }

    /** The one row with this title, or null when there's none. */
    private static Preference byTitle(PreferenceGroup group, String title) {
        List<Preference> found = new ArrayList<>();
        collect(group, title, found);
        assertTrue("rows titled " + title + ": " + found.size(), found.size() <= 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collect(PreferenceGroup group, String title, List<Preference> found) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child.getTitle() != null && title.contentEquals(child.getTitle())) found.add(child);
            if (child instanceof PreferenceGroup) collect((PreferenceGroup) child, title, found);
        }
    }
}
