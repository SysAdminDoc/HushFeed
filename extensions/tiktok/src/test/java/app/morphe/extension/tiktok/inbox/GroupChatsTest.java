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

import android.content.Context;
import android.text.InputType;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class GroupChatsTest {
    private Context context;

    /** The shape of TikTok's conversation: the names the patch's fingerprint holds it to. */
    public static class FakeInfo {
        private final String name;

        FakeInfo(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static class FakeConversation {
        private final boolean group;
        private final String id;
        private final FakeInfo info;

        FakeConversation(boolean group, String id, String name) {
            this.group = group;
            this.id = id;
            this.info = name == null ? null : new FakeInfo(name);
        }

        public boolean isGroupChat() {
            return group;
        }

        public String getConversationId() {
            return id;
        }

        public FakeInfo getCoreInfo() {
            return info;
        }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        SignedInUser.idForTests = "200";
        GroupChats.resetForTests();
        context.getSharedPreferences(GroupChats.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        SignedInUser.idForTests = null;
        GroupChats.resetForTests();
        context.getSharedPreferences(GroupChats.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void aGroupTikTokLoadsIsFoundByItsNameEmojiAndAll() {
        GroupChats.seen(new FakeConversation(true, "0:2:99", "Squad 🔥"));

        GroupChats.Chat found = GroupChats.find(context, "200", "  squad   🔥 ");
        assertNotNull(found);
        assertEquals("0:2:99", found.id);
        assertEquals("Squad 🔥", found.name);
    }

    @Test public void aOneToOneChatAnUnnamedGroupAndOddInputAreIgnored() {
        GroupChats.seen(new FakeConversation(false, "0:1:100:200", "Friend"));
        GroupChats.seen(new FakeConversation(true, "0:2:5", ""));
        GroupChats.seen(new FakeConversation(true, "0:2:6", null));
        GroupChats.seen(null);
        GroupChats.seen("not a conversation");

        assertNull(GroupChats.find(context, "200", "Friend"));
        assertNull(GroupChats.find(context, "200", ""));
    }

    @Test public void groupsBelongToTheAccountThatSawThem() {
        GroupChats.seen(new FakeConversation(true, "0:2:99", "Squad"));
        assertNull(GroupChats.find(context, "300", "Squad"));
    }

    @Test public void aRenamedGroupKeepsItsIdUnderBothNames() {
        GroupChats.seen(new FakeConversation(true, "0:2:99", "Squad"));
        GroupChats.seen(new FakeConversation(true, "0:2:99", "Crew"));
        assertEquals("0:2:99", GroupChats.find(context, "200", "crew").id);
    }

    @Test public void entriesAreToldApartAsHandlesOrGroupNames() {
        assertEquals(Arrays.asList("friend.one", "squad", "group:squad 🔥", "group:🔥",
                        "group:the crew"),
                StreakMessenger.handlesOf("@Friend.One, https://www.tiktok.com/@squad\n"
                        + "Squad 🔥,🔥 , The   Crew"));
        assertTrue(StreakMessenger.isGroupKey("group:the crew"));
        assertFalse(StreakMessenger.isGroupKey("friend.one"));
        assertEquals("The crew", StreakMessenger.labelOf("group:The crew"));
        assertEquals("@friend", StreakMessenger.labelOf("friend"));
    }

    @Test public void theRecipientFieldTakesSpacesAndEmoji() {
        app.morphe.extension.tiktok.settings.preference.InputTextPreference preference =
                new app.morphe.extension.tiktok.settings.preference.InputTextPreference(
                        context, "t", "s", app.morphe.extension.tiktok.settings.Settings.AUTO_STREAK_RECIPIENT);
        preference.withFreeTextKeyboard();
        int type = preference.getEditText().getInputType();
        assertEquals(InputType.TYPE_CLASS_TEXT, type & InputType.TYPE_MASK_CLASS);
        assertEquals(0, type & InputType.TYPE_MASK_VARIATION);
    }
}
