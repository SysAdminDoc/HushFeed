/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.android.live.base.model.user.User;
import com.bytedance.android.livesdk.model.message.CaptionMessage;
import com.bytedance.android.livesdk.model.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * LIVE name alerts (#424): each message the room's message manager dispatches comes here, and a
 * chat line that mentions you or a caption that says your name raises one alert, a notification
 * while TikTok's notifications are on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveMentionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long ME = 7200000000000000001L;
    private static final long ROOM = 7571234567890123456L;
    private boolean installed;
    private final List<LiveMentions.Alert> alerts = new ArrayList<>();

    @Before public void setUp() {
        installed = SettingsStatus.liveControlsEnabled;
        SettingsStatus.liveControlsEnabled = true;
        Settings.LIVE_NAME_ALERTS.save(false);
        Settings.LIVE_NAME_ALERT_NAMES.save("");
        LiveMentions.resetForTests();
        LiveMentions.alertsForTests = alerts;
        LiveMentions.nowForTests = 1_000_000;
        SignedInUser.idForTests = String.valueOf(ME);
        SignedInUser.handleForTests = "jess_plays22";
        SignedInUser.nicknameForTests = "Jessica Moreno";
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.liveControlsEnabled = installed;
        Settings.LIVE_NAME_ALERTS.save(false);
        Settings.LIVE_NAME_ALERT_NAMES.save("");
        LiveMentions.resetForTests();
        SignedInUser.idForTests = null;
        SignedInUser.handleForTests = null;
        SignedInUser.nicknameForTests = null;
    }

    private static User host() {
        return new User(42L, "Big Host");
    }

    @Test public void theSwitchStartsOffAndNothingIsRead() {
        assertFalse(Settings.LIVE_NAME_ALERTS.defaultValue);
        LiveMentions.onMessage(new ChatMessage(ROOM, 1, host(), "hi jessica moreno"));
        assertTrue(alerts.isEmpty());
    }

    @Test public void aChatLineThatMentionsYouAlertsWithWhoSentIt() {
        Settings.LIVE_NAME_ALERTS.save(true);
        ChatMessage mention = new ChatMessage(ROOM, 1, host(), "come up here");
        mention.mentionUserList.add(ME);
        LiveMentions.onMessage(mention);
        assertEquals(1, alerts.size());
        assertEquals(String.valueOf(ROOM), alerts.get(0).room);
        assertEquals("Big Host: come up here", alerts.get(0).line());
        assertFalse(alerts.get(0).spoken);
    }

    @Test public void yourHandleOrNameTypedInTheChatAlerts() {
        Settings.LIVE_NAME_ALERTS.save(true);
        LiveMentions.onMessage(new ChatMessage(ROOM, 1, host(), "where is @Jess_Plays22 today"));
        LiveMentions.onMessage(new ChatMessage(ROOM + 1, 2, host(), "JESSICA MORENO!!"));
        assertEquals(2, alerts.size());
    }

    @Test public void yourOwnLinesAndOtherPeoplesNamesDoNot() {
        Settings.LIVE_NAME_ALERTS.save(true);
        LiveMentions.onMessage(new ChatMessage(ROOM, 1, new User(ME, "Jessica Moreno"), "jessica moreno here"));
        LiveMentions.onMessage(new ChatMessage(ROOM, 2, host(), "jessica is someone else"));
        LiveMentions.onMessage(new ChatMessage(ROOM, 3, host(), "jess_plays220 is not you"));
        ChatMessage other = new ChatMessage(ROOM, 4, host(), "hey you");
        other.mentionUserList.add(ME + 1);
        LiveMentions.onMessage(other);
        assertTrue(alerts.isEmpty());
    }

    @Test public void aCaptionThatSaysYourNameAlertsOncePerLine() {
        Settings.LIVE_NAME_ALERTS.save(true);
        Settings.LIVE_NAME_ALERT_NAMES.save("Jess, Jessie");
        // TikTok sends a caption line again as it grows, under one sentence id.
        LiveMentions.onMessage(new CaptionMessage(ROOM, 9, "Big Host", "shout out to"));
        LiveMentions.onMessage(new CaptionMessage(ROOM, 9, "Big Host", "shout out to Jess"));
        LiveMentions.onMessage(new CaptionMessage(ROOM, 9, "Big Host", "shout out to Jess for the gift"));
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).spoken);
        assertEquals("Big Host: shout out to Jess", alerts.get(0).line());
    }

    @Test public void aRoomGoesQuietForAWhileAfterAnAlert() {
        Settings.LIVE_NAME_ALERTS.save(true);
        LiveMentions.onMessage(new CaptionMessage(ROOM, 1, "Big Host", "thank you jessica moreno"));
        LiveMentions.onMessage(new CaptionMessage(ROOM, 2, "Big Host", "jessica moreno again"));
        LiveMentions.onMessage(new CaptionMessage(ROOM + 1, 3, "Other Host", "hi jessica moreno"));
        assertEquals("one per room", 2, alerts.size());
        LiveMentions.nowForTests += LiveMentions.QUIET_MS;
        LiveMentions.onMessage(new CaptionMessage(ROOM, 4, "Big Host", "jessica moreno one more time"));
        assertEquals(3, alerts.size());
    }

    @Test public void pausedOrNotInstalledReadsNothing() {
        Settings.LIVE_NAME_ALERTS.save(true);
        PausedProcess.set(true);
        LiveMentions.onMessage(new ChatMessage(ROOM, 1, host(), "jessica moreno"));
        PausedProcess.set(false);
        SettingsStatus.liveControlsEnabled = false;
        LiveMentions.onMessage(new ChatMessage(ROOM, 2, host(), "jessica moreno"));
        assertTrue(alerts.isEmpty());
    }

    @Test public void otherMessagesAndOddShapesAreLeftAlone() {
        Settings.LIVE_NAME_ALERTS.save(true);
        LiveMentions.onMessage(new Object());
        LiveMentions.onMessage(null);
        ChatMessage empty = new ChatMessage(ROOM, 1, null, null);
        empty.mentionUserList = null;
        LiveMentions.onMessage(empty);
        CaptionMessage blank = new CaptionMessage(ROOM, 2, null);
        blank.content = null;
        LiveMentions.onMessage(blank);
        assertTrue(alerts.isEmpty());
    }

    @Test public void namesMatchWholeWordsWithoutCaseOrAccents() {
        List<String> names = LiveMentions.namesFrom("ann", null, "José, Zoë");
        assertEquals("ann", LiveMentions.matchingName("Ann, come say hi", names));
        assertNull(LiveMentions.matchingName("our annual stream", names));
        assertEquals("jose", LiveMentions.matchingName("JOSE is here", names));
        assertEquals("zoe", LiveMentions.matchingName("thanks zoe!", names));
        assertNull(LiveMentions.matchingName("", names));
    }

    @Test public void scriptsWithoutSpacesMatchInsideTheLine() {
        List<String> names = LiveMentions.namesFrom(null, "小明", "さくら");
        assertEquals("小明", LiveMentions.matchingName("谢谢小明的礼物", names));
        assertEquals("さくら", LiveMentions.matchingName("さくらさん、ありがとう", names));
    }

    @Test public void shortOwnNamesAreSkippedButNamesYouAddAreKept() {
        List<String> names = LiveMentions.namesFrom("al", "Al", "Bo");
        assertEquals(List.of("bo"), names);
    }

    @Test public void theNamesFromSettingsApplyWithoutARestart() {
        Settings.LIVE_NAME_ALERTS.save(true);
        LiveMentions.onMessage(new CaptionMessage(ROOM, 1, "Big Host", "hello moon"));
        Settings.LIVE_NAME_ALERT_NAMES.save("Moon");
        LiveMentions.onMessage(new CaptionMessage(ROOM, 2, "Big Host", "hello moon"));
        assertEquals(1, alerts.size());
    }

    @Test public void withNotificationsOnItPostsASilentHeadsUp() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = Shadows.shadowOf(manager);
        shadow.setNotificationsEnabled(true);
        assertTrue(LiveMentions.notificationsOn(context));

        LiveMentions.notify(context, new LiveMentions.Alert("1", "Big Host", "shout out to Jess", true));

        List<Notification> posted = shadow.getAllNotifications();
        assertEquals(1, posted.size());
        assertEquals("Your name came up in a LIVE",
                String.valueOf(posted.get(0).extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertEquals("Big Host: shout out to Jess",
                String.valueOf(posted.get(0).extras.getCharSequence(Notification.EXTRA_TEXT)));
        NotificationChannel channel = manager.getNotificationChannel(LiveMentions.CHANNEL);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.getImportance());
        assertNull("no sound over the LIVE", channel.getSound());
        assertTrue(channel.shouldVibrate());

        shadow.setNotificationsEnabled(false);
        assertFalse("off means the banner", LiveMentions.notificationsOn(context));
    }

    @Test public void aLongLineIsCut() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60; i++) text.append("word ");
        String cut = LiveMentions.snippet(text.toString());
        assertTrue(cut.length() <= 161);
        assertTrue(cut.endsWith("…"));
    }
}
