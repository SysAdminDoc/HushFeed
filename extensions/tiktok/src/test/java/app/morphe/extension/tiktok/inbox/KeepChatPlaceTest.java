/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Keep your place in chats (#503). The patch hands each yes from TikTok's "move to the newest
 * message" decider to {@link KeepChatPlace#scrollToNewest} with the chat list's layout manager and
 * the event behind the change. The fakes here have the members it reads off TikTok's objects on
 * 47.9.3: the layout manager's {@code findFirstVisibleItemPosition()}, each event's
 * {@code getName()}, and the send event's message with its {@code getReferenceInfo()}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class KeepChatPlaceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int YES = 1;
    private static final int NO = 0;
    /** Scrolled up: the newest message, position 0 in the bottom-up list, is off screen. */
    private static final FakeLayoutManager SCROLLED_UP = new FakeLayoutManager(12);
    private static final FakeLayoutManager AT_NEWEST = new FakeLayoutManager(0);

    @After
    public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.KEEP_CHAT_PLACE.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndTakesEffectRightAway() {
        assertEquals("keep_chat_place", Settings.KEEP_CHAT_PLACE.key);
        assertEquals(Boolean.FALSE, Settings.KEEP_CHAT_PLACE.defaultValue);
        assertFalse("read on every list change", Settings.KEEP_CHAT_PLACE.rebootApp);

        assertTrue("off, TikTok's yes stands", KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("OnQueryMessageEvent")));
        assertFalse("and its no", KeepChatPlace.scrollToNewest(NO, SCROLLED_UP, event("OnQueryMessageEvent")));
        assertTrue(HookStatus.report().stream().noneMatch(line -> line.contains("keep your place")));
    }

    @Test
    public void comingBackToAChatScrolledUpKeepsThePlace() {
        Settings.KEEP_CHAT_PLACE.save(true);
        assertFalse(KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("OnQueryMessageEvent")));
        assertFalse("any other change TikTok moves for", KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("NoDataMsgEvent")));
        assertFalse(KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("OnAddMessageEvent")));

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("keep your place in chats"));
    }

    @Test
    public void aReplyKeepsThePlaceAndAPlainMessageStillMoves() {
        Settings.KEEP_CHAT_PLACE.save(true);
        assertFalse("a reply quotes the message it answers",
                KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, new SendEvent(new Message(new Object()))));
        assertTrue("a plain message goes to the bottom like before",
                KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, new SendEvent(new Message(null))));
        assertTrue(KeepChatPlace.isReply(new SendEvent(new Message(new Object()))));
        assertFalse(KeepChatPlace.isReply(new SendEvent(new Message(null))));
        assertFalse("a send event with no message", KeepChatPlace.isReply(new SendEvent(null)));
    }

    @Test
    public void theDownArrowStillTakesYouToTheNewest() {
        Settings.KEEP_CHAT_PLACE.save(true);
        // While scrolled up, TikTok only moves to an incoming page because the down arrow was tapped.
        assertTrue(KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("OnGetMessageEvent")));
    }

    @Test
    public void atTheNewestMessageTikTokStillFollowsNewOnes() {
        Settings.KEEP_CHAT_PLACE.save(true);
        assertTrue(KeepChatPlace.scrollToNewest(YES, AT_NEWEST, event("OnQueryMessageEvent")));
        assertTrue(KeepChatPlace.scrollToNewest(YES, AT_NEWEST, new SendEvent(new Message(new Object()))));
        assertFalse("TikTok's no is never turned into a move",
                KeepChatPlace.scrollToNewest(NO, AT_NEWEST, new SendEvent(new Message(null))));
        assertFalse(KeepChatPlace.scrollToNewest(NO, SCROLLED_UP, event("OnGetMessageEvent")));
    }

    @Test
    public void whatCantBeReadIsLeftToTikTok() {
        Settings.KEEP_CHAT_PLACE.save(true);
        assertTrue("no event", KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, null));
        assertTrue("an event with no name", KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, new Object()));
        assertTrue("no list", KeepChatPlace.scrollToNewest(YES, null, event("OnQueryMessageEvent")));
        assertTrue("a list that can't say where it is",
                KeepChatPlace.scrollToNewest(YES, new Object(), event("OnQueryMessageEvent")));
        assertTrue("an empty list", KeepChatPlace.scrollToNewest(YES, new FakeLayoutManager(-1), event("OnQueryMessageEvent")));
    }

    @Test
    public void pausedOrWithoutAContextTikTokMoves() {
        Settings.KEEP_CHAT_PLACE.save(true);
        PausedProcess.set(true);
        assertTrue(KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("OnQueryMessageEvent")));
        PausedProcess.set(false);
        Utils.setContext(null);
        try {
            assertTrue(KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("OnQueryMessageEvent")));
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
        assertFalse(KeepChatPlace.scrollToNewest(YES, SCROLLED_UP, event("OnQueryMessageEvent")));
    }

    @Test
    public void theDecision() {
        assertTrue(KeepChatPlace.scrolls(0, "OnQueryMessageEvent", false));
        assertTrue(KeepChatPlace.scrolls(-1, "OnQueryMessageEvent", false));
        assertFalse(KeepChatPlace.scrolls(1, "OnQueryMessageEvent", false));
        assertTrue(KeepChatPlace.scrolls(1, KeepChatPlace.SEND_EVENT, false));
        assertFalse(KeepChatPlace.scrolls(1, KeepChatPlace.SEND_EVENT, true));
        assertTrue(KeepChatPlace.scrolls(1, KeepChatPlace.GET_EVENT, false));
        assertFalse(KeepChatPlace.scrolls(1, "UnknownEvent", false));
    }

    /** The row shows in a chat's section of Inbox only where the patch hooked the list. */
    @Test
    public void theRowShowsOnlyWhereTheListWasHooked() {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean chat = SettingsStatus.chatDeclutterEnabled;
        boolean hooked = SettingsStatus.keepChatPlaceEnabled;
        try (var controller = Robolectric.buildActivity(InboxFilterTabsTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.chatDeclutterEnabled = true;

            SettingsStatus.keepChatPlaceEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.KEEP_CHAT_PLACE.key));

            SettingsStatus.keepChatPlaceEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, with);
            Preference row = with.findPreference(Settings.KEEP_CHAT_PLACE.key);
            assertNotNull(row);
            assertTrue(row instanceof TogglePreference);
            assertEquals("Keep your place in chats", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("scrolled up"));
            assertFalse(summary, summary.endsWith(TogglePreference.RESTART_SENTENCE));
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.chatDeclutterEnabled = chat;
            SettingsStatus.keepChatPlaceEnabled = hooked;
        }
    }

    private static Event event(String name) {
        return new Event(name);
    }

    /** TikTok's chat layout manager as the hook reads it. */
    private static final class FakeLayoutManager {
        private final int first;

        FakeLayoutManager(int first) {
            this.first = first;
        }

        public int findFirstVisibleItemPosition() {
            return first;
        }
    }

    /** One of TikTok's message list events, known by the name it gives itself. */
    private static final class Event {
        private final String name;

        Event(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    /** TikTok's IM message: a reply carries the reference to the message it quotes. */
    private static final class Message {
        private final Object reference;

        Message(Object reference) {
            this.reference = reference;
        }

        public Object getReferenceInfo() {
            return reference;
        }
    }

    /** OnSendMessageEvent as 47.9.3 lays it out: the conversation, a code, the message and its source. */
    private static final class SendEvent {
        public final Object conversation = new Object();
        public final int code = 0;
        public final Message message;
        public final String source = "input";

        SendEvent(Message message) {
            this.message = message;
        }

        public String getName() {
            return "OnSendMessageEvent";
        }
    }
}
