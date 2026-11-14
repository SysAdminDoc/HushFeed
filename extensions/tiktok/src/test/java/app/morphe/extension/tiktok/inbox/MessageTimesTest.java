/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Show message times: the time under each message in a chat, drawn in room added to its row. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MessageTimesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** 2026-11-14 15:07 UTC. */
    private static final long SENT = Instant.parse("2026-11-14T15:07:00Z").toEpochMilli();
    private static final int TIKTOK_PADDING = 10;

    private Locale locale;
    private TimeZone zone;

    @Before
    public void setUp() {
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Belgrade"));
    }

    @After
    public void tearDown() {
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SHOW_MESSAGE_TIMES.resetToDefault();
        MessageTimes.LABELS.clear();
        HookStatus.clear();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static void clock(String style) {
        android.provider.Settings.System.putString(context().getContentResolver(),
                android.provider.Settings.System.TIME_12_24, style);
    }

    /** A row the way TikTok's bind leaves it: laid out, its padding set a moment ago. */
    private static FrameLayout row(int width, int height) {
        FrameLayout row = new FrameLayout(context());
        row.setPadding(0, TIKTOK_PADDING, 0, TIKTOK_PADDING);
        row.layout(0, 0, width, height);
        return row;
    }

    private static View child(FrameLayout parent, int left, int top, int right, int bottom) {
        View child = new View(context());
        parent.addView(child);
        child.layout(left, top, right, bottom);
        return child;
    }

    private static FrameLayout layer(FrameLayout parent, int left, int top, int right, int bottom) {
        FrameLayout child = new FrameLayout(context());
        parent.addView(child);
        child.layout(left, top, right, bottom);
        return child;
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertEquals("show_message_times", Settings.SHOW_MESSAGE_TIMES.key);
        assertEquals(Boolean.FALSE, Settings.SHOW_MESSAGE_TIMES.defaultValue);
        assertFalse("read at each bind", Settings.SHOW_MESSAGE_TIMES.rebootApp);

        FrameLayout row = row(1000, 200);
        MessageTimes.bound(row, new Object());
        assertNull("off, no label", MessageTimes.LABELS.get(row));
        assertEquals(TIKTOK_PADDING, row.getPaddingBottom());

        // Unpatched, the bridge answers 0, so even on nothing changes.
        Settings.SHOW_MESSAGE_TIMES.save(true);
        MessageTimes.bound(row, new Object());
        assertNull(MessageTimes.LABELS.get(row));
        assertEquals(TIKTOK_PADDING, row.getPaddingBottom());
    }

    @Test
    public void aTimeAddsOneLineOfRoomUnderTheRowOncePerBind() {
        FrameLayout row = row(1000, 200);
        MessageTimes.show(row, SENT);
        MessageTimes.Label label = MessageTimes.LABELS.get(row);
        assertNotNull(label);
        assertTrue("a line of 11sp text", label.lineHeight() > 0);
        assertEquals(TIKTOK_PADDING + label.lineHeight(), row.getPaddingBottom());
        assertEquals("top padding is TikTok's", TIKTOK_PADDING, row.getPaddingTop());

        // The next bind sets TikTok's padding again first, and the row keeps its one label.
        row.setPadding(0, TIKTOK_PADDING, 0, TIKTOK_PADDING);
        MessageTimes.show(row, SENT + 60_000L);
        assertSame(label, MessageTimes.LABELS.get(row));
        assertEquals(TIKTOK_PADDING + label.lineHeight(), row.getPaddingBottom());

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("message times"));
    }

    @Test
    public void aRowWithNoTimeOrTheSwitchOffKeepsTikToksPadding() {
        FrameLayout row = row(1000, 200);
        MessageTimes.show(row, SENT);
        row.setPadding(0, TIKTOK_PADDING, 0, TIKTOK_PADDING);
        MessageTimes.show(row, 0L);
        assertNull("the label is emptied", MessageTimes.LABELS.get(row).text);
        assertEquals(TIKTOK_PADDING, row.getPaddingBottom());

        row.setPadding(0, TIKTOK_PADDING, 0, TIKTOK_PADDING);
        Settings.SHOW_MESSAGE_TIMES.save(false);
        MessageTimes.bound(row, new Object());
        assertNull(MessageTimes.LABELS.get(row).text);
        assertEquals(TIKTOK_PADDING, row.getPaddingBottom());
    }

    @Test
    public void secondsAndMillisecondsShowTheSameTime() {
        clock("24");
        FrameLayout inMillis = row(1000, 200);
        FrameLayout inSeconds = row(1000, 200);
        MessageTimes.show(inMillis, SENT);
        MessageTimes.show(inSeconds, SENT / 1000L);
        assertEquals("16:07", MessageTimes.LABELS.get(inMillis).text);
        assertEquals("16:07", MessageTimes.LABELS.get(inSeconds).text);
    }

    @Test
    public void theTimeFollowsThePhonesClockStyleAndTikToksZone() {
        clock("24");
        assertEquals("16:07", MessageTimes.format(context(), SENT));
        clock("12");
        String twelve = MessageTimes.format(context(), SENT);
        assertTrue(twelve, twelve.startsWith("4:07"));
        assertTrue(twelve, twelve.contains("PM"));

        // Region spoof sets the default zone, which TikTok's own chat dates follow too.
        clock("24");
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        assertEquals("10:07", MessageTimes.format(context(), SENT));
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        assertEquals("00:07", MessageTimes.format(context(), SENT));
    }

    @Test
    public void theBubbleIsTheWidestPartInsideAFullWidthLayer() {
        FrameLayout row = row(1000, 200);
        FrameLayout gestures = layer(row, 0, 0, 1000, 200);
        child(gestures, 20, 20, 100, 100);
        View bubble = child(gestures, 120, 10, 620, 190);
        assertSame(bubble, MessageTimes.bubble(row));

        View hidden = child(gestures, 110, 10, 900, 190);
        hidden.setVisibility(View.GONE);
        assertSame("a part that isn't shown is skipped", bubble, MessageTimes.bubble(row));

        FrameLayout notice = row(1000, 200);
        child(notice, 0, 0, 1000, 200);
        assertNull("a notice across the chat has no bubble", MessageTimes.bubble(notice));
        assertNull(MessageTimes.bubble(new View(context())));
    }

    @Test
    public void theTimeLinesUpWithTheBubblesOuterEdge() {
        FrameLayout theirs = row(1000, 200);
        child(theirs, 120, 10, 620, 190);
        MessageTimes.show(theirs, SENT);
        MessageTimes.Label left = MessageTimes.LABELS.get(theirs);
        assertEquals("a message you got starts under its bubble", 120f, left.left(theirs, 80f), 0.01f);

        FrameLayout mine = row(1000, 200);
        FrameLayout offset = layer(mine, 0, 0, 1000, 200);
        child(offset, 400, 10, 960, 190);
        MessageTimes.show(mine, SENT);
        MessageTimes.Label right = MessageTimes.LABELS.get(mine);
        assertEquals("one you sent ends under its bubble", 960f - 80f, right.left(mine, 80f), 0.01f);

        FrameLayout notice = row(1000, 200);
        MessageTimes.show(notice, SENT);
        assertEquals("centered with no bubble", 460f,
                MessageTimes.LABELS.get(notice).left(notice, 80f), 0.01f);

        FrameLayout padded = row(1000, 200);
        padded.setPadding(30, TIKTOK_PADDING, 30, TIKTOK_PADDING);
        child(padded, 0, 10, 500, 190);
        MessageTimes.show(padded, SENT);
        assertEquals("kept inside the row's padding", 30f,
                MessageTimes.LABELS.get(padded).left(padded, 80f), 0.01f);
    }

    @Test
    public void theTimeIsDrawnInTheRoomUnderTheBubble() {
        FrameLayout row = row(1000, 200);
        child(row, 120, 10, 620, 150);
        MessageTimes.show(row, SENT);
        int height = 200 + MessageTimes.LABELS.get(row).lineHeight();
        row.layout(0, 0, 1000, height);
        int bandTop = height - row.getPaddingBottom();
        int bandBottom = height - TIKTOK_PADDING;

        Bitmap drawn = Bitmap.createBitmap(1000, height, Bitmap.Config.ARGB_8888);
        row.draw(new Canvas(drawn));
        assertTrue("the time is in the band", inked(drawn, 0, bandTop, 1000, bandBottom) > 0);
        assertEquals("and starts under the bubble", 0, inked(drawn, 0, bandTop, 118, bandBottom));
        assertEquals("nothing above the band", 0, inked(drawn, 0, 0, 1000, bandTop));

        MessageTimes.show(row, 0L);
        Bitmap empty = Bitmap.createBitmap(1000, height, Bitmap.Config.ARGB_8888);
        row.draw(new Canvas(empty));
        assertEquals("an emptied label draws nothing", 0, inked(empty, 0, 0, 1000, height));
    }

    private static int inked(Bitmap bitmap, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = Math.max(0, top); y < Math.min(bitmap.getHeight(), bottom); y++) {
            for (int x = Math.max(0, left); x < Math.min(bitmap.getWidth(), right); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) count++;
            }
        }
        return count;
    }

    /** The row shows under Inbox > In a chat only where the patch hooked both message slots. */
    @Test
    public void theRowShowsOnlyWhereTheMessageRowsWereHooked() {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean chat = SettingsStatus.chatDeclutterEnabled;
        boolean hooked = SettingsStatus.messageTimesEnabled;
        try (var controller = Robolectric.buildActivity(InboxFilterTabsTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.chatDeclutterEnabled = true;

            SettingsStatus.messageTimesEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.SHOW_MESSAGE_TIMES.key));

            SettingsStatus.messageTimesEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, with);
            Preference row = with.findPreference(Settings.SHOW_MESSAGE_TIMES.key);
            assertNotNull(row);
            assertTrue(row instanceof TogglePreference);
            assertEquals("Show message times", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("time"));
            assertFalse(summary, summary.endsWith(TogglePreference.RESTART_SENTENCE));
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.chatDeclutterEnabled = chat;
            SettingsStatus.messageTimesEnabled = hooked;
        }
    }
}
