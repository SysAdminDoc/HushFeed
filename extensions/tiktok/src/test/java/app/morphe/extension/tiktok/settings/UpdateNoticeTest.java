/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;

/** The opt-in card that says a newer Hushfeed is out (#377). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdateNoticeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long DAY = UpdateNotice.INTERVAL_MS;

    /** The User-Agent of each request the fake GitHub got. */
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private volatile long now = 30 * DAY;
    private volatile String latest = release("v0.82.0");

    private static String release(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[{\"name\":\"patches-0.82.0.mpp\"}]}";
    }

    private static String release(String tag, String notes) {
        try {
            return new JSONObject().put("tag_name", tag).put("draft", false)
                    .put("prerelease", false).put("body", notes).toString();
        } catch (JSONException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Laid out the way the release script writes notes: a paragraph, then plain section names. */
    private static final String NOTES = "Hushfeed v0.82.0 has 148 patches for global TikTok 47.7.3. "
            + "Patch it with the same signing key.\r\n\r\n"
            + "New in this release\n\n"
            + "- `Check for Hushfeed updates` tells you in TikTok when a newer Hushfeed is out. "
            + "Once a day at most.\n"
            + "- [Startup message](https://github.com/SysAdminDoc/HushFeed/issues/347) shows a "
            + "message you write. It starts off.\n"
            + "- **Bold** move, v0.82.0 works on 47.7.3 (the `.apkm` too).\n"
            + "\nBug Fixes\n\n"
            + "- Fixed A. Detail.\n"
            + "- Fixed B!\n"
            + "- Fixed C.\n"
            + "* Fixed D.\n";

    @Before public void setUp() {
        ShadowAlertDialog.reset();
        UpdateNotice.resetForTests();
        UpdateNotice.clock = () -> now;
        UpdateNotice.fetcher = userAgent -> {
            asked.add(userAgent);
            return latest;
        };
        PausedProcess.set(false);
        Settings.CHECK_FOR_UPDATES.save(true);
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        UpdateNotice.resetForTests();
        PausedProcess.set(false);
        Settings.CHECK_FOR_UPDATES.resetToDefault();
        Settings.UPDATE_CHECKED_AT.resetToDefault();
        Settings.UPDATE_DISMISSED.resetToDefault();
    }

    private static ActivityController<Activity> screen() {
        return Robolectric.buildActivity(Activity.class).setup();
    }

    /** What an opening of TikTok does, then the worker's answer and the frames after it. */
    private static boolean open(Activity activity, String current) throws Exception {
        boolean started = UpdateNotice.onOpening(activity, current);
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        return started;
    }

    @Test public void releaseNumbersCompareAsNumbers() {
        assertTrue(UpdateNotice.compareVersions("0.10.0", "0.9.0") > 0);
        assertTrue(UpdateNotice.compareVersions("0.82.0", "0.81.9") > 0);
        assertTrue(UpdateNotice.compareVersions("1.0", "0.99.9") > 0);
        assertEquals(0, UpdateNotice.compareVersions("v0.81.0", "0.81.0"));
        assertEquals("a missing part counts as 0", 0, UpdateNotice.compareVersions("0.81", "0.81.0"));
        assertTrue("a release is newer than its own pre-release",
                UpdateNotice.compareVersions("0.82.0", "0.82.0-dev.1") > 0);
        assertTrue(UpdateNotice.compareVersions("0.82.0-dev.1", "0.81.0") > 0);
        assertTrue(UpdateNotice.compareVersions("0.82.0-dev.2", "0.82.0-dev.1") > 0);
    }

    @Test public void onlyAPublishedReleaseWithAVersionTagCounts() {
        assertEquals("v0.82.0", UpdateNotice.tagOf(release("v0.82.0")));
        assertEquals("0.82.0", UpdateNotice.tagOf(release("0.82.0")));
        assertNull(UpdateNotice.tagOf(null));
        assertNull(UpdateNotice.tagOf("<html>rate limited</html>"));
        assertNull(UpdateNotice.tagOf("[]"));
        assertNull(UpdateNotice.tagOf("{}"));
        assertNull(UpdateNotice.tagOf("{\"tag_name\":\"v0.82.0\",\"draft\":true}"));
        assertNull(UpdateNotice.tagOf("{\"tag_name\":\"v0.82.0\",\"prerelease\":true}"));
        assertNull("not a version", UpdateNotice.tagOf(release("latest")));
        assertNull("a tag carrying more than a version",
                UpdateNotice.tagOf(release("v0.82.0/../../evil")));
    }

    @Test public void onlyANewerReleaseNotDismissedIsAnswered() {
        assertEquals("v0.82.0", UpdateNotice.newerRelease(release("v0.82.0"), "0.81.0", ""));
        assertNull("the same release", UpdateNotice.newerRelease(release("v0.81.0"), "0.81.0", ""));
        assertNull("an older one", UpdateNotice.newerRelease(release("v0.80.2"), "0.81.0", ""));
        assertNull("the one dismissed",
                UpdateNotice.newerRelease(release("v0.82.0"), "0.81.0", "v0.82.0"));
        assertEquals("a newer one than the one dismissed", "v0.83.0",
                UpdateNotice.newerRelease(release("v0.83.0"), "0.81.0", "v0.82.0"));
    }

    @Test public void aNewerReleaseShowsTheCard() throws Exception {
        try (ActivityController<Activity> controller = screen()) {
            assertTrue(open(controller.get(), "0.81.0"));
            assertEquals("one request, naming Hushfeed and its version",
                    List.of("Hushfeed/0.81.0"), asked);
            assertEquals("the try is kept", now, (long) Settings.UPDATE_CHECKED_AT.get());

            AlertDialog card = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("no card for a newer release", card);
            assertTrue(card.isShowing());
            assertEquals("Hushfeed v0.82.0 is out", String.valueOf(shadowOf(card).getTitle()));
            assertEquals("You have 0.81.0. Update the patches in Morphe Manager, then patch TikTok again.",
                    String.valueOf(shadowOf(card).getMessage()));
            assertEquals("Update", card.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Dismiss", card.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        }
    }

    @Test public void theNotesStartUnderTheirSectionNames() {
        UpdateNotice.Notes notes = UpdateNotice.notesOf(NOTES);
        assertEquals(List.of(
                "New in this release",
                "• Check for Hushfeed updates tells you in TikTok when a newer Hushfeed is out.",
                "• Startup message shows a message you write.",
                "• Bold move, v0.82.0 works on 47.7.3 (the .apkm too).",
                "",
                "Bug Fixes",
                "• Fixed A.",
                "• Fixed B!"), notes.lines);
        assertEquals("the bullets left off", 2, notes.more);
    }

    @Test public void markdownHeadingsNameSectionsToo() {
        UpdateNotice.Notes notes = UpdateNotice.notesOf("### Features\n- One. Two.\n## Fixes\n* Three");
        assertEquals(List.of("Features", "• One.", "", "Fixes", "• Three"), notes.lines);
        assertEquals(0, notes.more);
    }

    @Test public void aLongBulletIsCutAtAWord() {
        StringBuilder words = new StringBuilder("- ");
        for (int i = 0; i < 60; i++) words.append("word ");
        String line = UpdateNotice.notesOf(words.toString()).lines.get(0);
        assertTrue(line, line.length() <= UpdateNotice.NOTE_LENGTH + 2);
        assertTrue(line, line.endsWith("word…"));
    }

    @Test public void noNotesLeaveTheVersionLineAlone() {
        assertTrue(UpdateNotice.notesOf(null).lines.isEmpty());
        assertTrue(UpdateNotice.notesOf("").lines.isEmpty());
        assertTrue("a paragraph alone isn't a highlight",
                UpdateNotice.notesOf("Just a paragraph about the release.").lines.isEmpty());
        assertEquals("", UpdateNotice.notesText("not json"));
        assertEquals("", UpdateNotice.notesText(release("v0.82.0")));
        assertEquals("x", UpdateNotice.notesText(release("v0.82.0", "x")));
    }

    @Test public void theCardListsTheStartOfTheNotes() throws Exception {
        latest = release("v0.82.0", NOTES);
        try (ActivityController<Activity> controller = screen()) {
            assertTrue(open(controller.get(), "0.81.0"));
            AlertDialog card = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(card);
            assertEquals("You have 0.81.0. Update the patches in Morphe Manager, then patch TikTok again.\n\n"
                            + "New in this release\n"
                            + "• Check for Hushfeed updates tells you in TikTok when a newer Hushfeed is out.\n"
                            + "• Startup message shows a message you write.\n"
                            + "• Bold move, v0.82.0 works on 47.7.3 (the .apkm too).\n\n"
                            + "Bug Fixes\n"
                            + "• Fixed A.\n"
                            + "• Fixed B!\n\n"
                            + "2 more in the release notes",
                    String.valueOf(shadowOf(card).getMessage()));
        }
    }

    @Test public void theSameOrAnOlderReleaseShowsNothing() throws Exception {
        latest = release("v0.81.0");
        try (ActivityController<Activity> controller = screen()) {
            assertTrue(open(controller.get(), "0.81.0"));
            assertEquals(1, asked.size());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void dismissKeepsThatReleaseAwayButNotTheNextOne() throws Exception {
        try (ActivityController<Activity> controller = screen()) {
            Activity activity = controller.get();
            assertTrue(open(activity, "0.81.0"));
            AlertDialog card = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(card);
            card.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(card.isShowing());
            assertEquals("v0.82.0", Settings.UPDATE_DISMISSED.get());
            assertNull("Dismiss opened something", shadowOf(activity).getNextStartedActivity());

            ShadowAlertDialog.reset();
            now += DAY;
            assertTrue(open(activity, "0.81.0"));
            assertNull("the dismissed release came back", ShadowAlertDialog.getLatestAlertDialog());

            latest = release("v0.83.0");
            now += DAY;
            assertTrue(open(activity, "0.81.0"));
            AlertDialog next = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("a newer release than the dismissed one stayed away", next);
            assertEquals("Hushfeed v0.83.0 is out", String.valueOf(shadowOf(next).getTitle()));
        }
    }

    @Test public void updateOpensMorpheManager() throws Exception {
        try (ActivityController<Activity> controller = screen()) {
            Activity activity = controller.get();
            assertTrue(open(activity, "0.81.0"));
            AlertDialog card = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(card);
            card.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();

            Intent sent = shadowOf(activity).getNextStartedActivity();
            assertNotNull("Update opened nothing", sent);
            assertEquals(Intent.ACTION_MAIN, sent.getAction());
            assertEquals(UpdateNotice.MANAGER, sent.getPackage());
            assertTrue(sent.hasCategory(Intent.CATEGORY_LAUNCHER));
            assertEquals("Update isn't a dismissal", "", Settings.UPDATE_DISMISSED.get());
        }
    }

    @Test public void withoutMorpheManagerUpdateOpensTheReleasePage() {
        try (ActivityController<Activity> controller = screen()) {
            Activity activity = controller.get();
            // Robolectric only refuses an activity it can't resolve once it's asked to.
            shadowOf(activity.getApplication()).checkActivities(true);
            Intent page = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/SysAdminDoc/HushFeed/releases/tag/v0.82.0"));
            ResolveInfo browser = new ResolveInfo();
            browser.activityInfo = new ActivityInfo();
            browser.activityInfo.packageName = "com.example.browser";
            browser.activityInfo.name = "com.example.browser.Main";
            shadowOf(activity.getPackageManager()).addResolveInfoForIntent(page, browser);

            UpdateNotice.openUpdate(activity, "v0.82.0");

            Intent sent = shadowOf(activity).getNextStartedActivity();
            assertNotNull("no release page either", sent);
            assertEquals(Intent.ACTION_VIEW, sent.getAction());
            assertEquals(page.getData(), sent.getData());
            assertNull("only the one", shadowOf(activity).getNextStartedActivity());
        }
    }

    @Test public void theSwitchOffOrPauseAsksNothing() throws Exception {
        try (ActivityController<Activity> controller = screen()) {
            Settings.CHECK_FOR_UPDATES.save(false);
            assertFalse(open(controller.get(), "0.81.0"));

            Settings.CHECK_FOR_UPDATES.save(true);
            PausedProcess.set(true);
            assertFalse("Pause let the check through", open(controller.get(), "0.81.0"));
            PausedProcess.set(false);

            assertTrue("GitHub was asked", asked.isEmpty());
            assertEquals("a try was kept", 0L, (long) Settings.UPDATE_CHECKED_AT.get());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void anUnpatchedBuildAsksNothing() throws Exception {
        try (ActivityController<Activity> controller = screen()) {
            assertFalse(open(controller.get(), ""));
            assertFalse(open(controller.get(), null));
            // Outside a patched TikTok the bundle's version reads "".
            assertFalse(UpdateNotice.onOpening(controller.get()));
            Utils.awaitBackgroundTasksForTests();
            assertTrue(asked.isEmpty());
            assertEquals(0L, (long) Settings.UPDATE_CHECKED_AT.get());
        }
    }

    @Test public void atMostOnceADay() throws Exception {
        latest = release("v0.81.0");
        try (ActivityController<Activity> controller = screen()) {
            Activity activity = controller.get();
            assertTrue(open(activity, "0.81.0"));
            now += DAY - 1;
            assertFalse("asked twice in a day", open(activity, "0.81.0"));
            now += 1;
            assertTrue("a day later it didn't ask", open(activity, "0.81.0"));
            assertEquals(2, asked.size());

            // A clock set back past the last try doesn't hold the check off for days.
            now -= 10 * DAY;
            assertTrue(open(activity, "0.81.0"));
            assertEquals(3, asked.size());
            assertEquals(now, (long) Settings.UPDATE_CHECKED_AT.get());
        }
    }

    @Test public void aFailedTryWaitsItsDayToo() throws Exception {
        UpdateNotice.fetcher = userAgent -> {
            asked.add(userAgent);
            throw new IOException("offline");
        };
        try (ActivityController<Activity> controller = screen()) {
            Activity activity = controller.get();
            assertTrue(open(activity, "0.81.0"));
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertEquals("the try wasn't kept before the request", now,
                    (long) Settings.UPDATE_CHECKED_AT.get());
            now += DAY / 2;
            assertFalse("a failure retried within the day", open(activity, "0.81.0"));
            assertEquals(1, asked.size());
        }
    }

    @Test public void aScreenClosedBeforeTheAnswerGetsNoCard() throws Exception {
        ActivityController<Activity> controller = screen();
        assertTrue(UpdateNotice.onOpening(controller.get(), "0.81.0"));
        Utils.awaitBackgroundTasksForTests();
        controller.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, asked.size());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void theAnswerIsReadUpToItsCap() throws IOException {
        byte[] body = "{\"tag_name\":\"v0.82.0\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"tag_name\":\"v0.82.0\"}",
                UpdateNotice.readCapped(new ByteArrayInputStream(body), body.length));
        try {
            UpdateNotice.readCapped(new ByteArrayInputStream(new byte[20_000]), 16_384);
            fail("read past the cap");
        } catch (IOException expected) {
            // The whole answer is refused, not cut short and parsed.
        }
    }
}
