package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/** Whose unsends are left in the chat, what is written down about them, and how they are marked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class KeepUnsentTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private String previousUser;

    /** The part of TikTok's message the chat reads: its server id. */
    public static final class FakeMessage {
        private final long id;
        FakeMessage(long id) { this.id = id; }
        public long getMsgId() { return id; }
    }

    @Before public void start() throws Exception {
        previousUser = SignedInUser.idForTests;
        SignedInUser.idForTests = "1000";
        KeptUnsentStore.fileForTests = new File(folder.newFolder(), "kept.json");
        KeptUnsentStore.resetForTests();
        Settings.KEEP_UNSENT_MESSAGES.save(false);
        Settings.MARK_KEPT_UNSENT_MESSAGES.save(true);
    }

    @After public void finish() {
        Settings.KEEP_UNSENT_MESSAGES.save(false);
        Settings.MARK_KEPT_UNSENT_MESSAGES.save(true);
        SignedInUser.idForTests = previousUser;
        KeptUnsentStore.fileForTests = null;
        KeptUnsentStore.resetForTests();
    }

    @Test public void anotherPersonsUnsendIsKeptOnlyWhileTheSwitchIsOn() {
        assertFalse(KeepUnsent.shouldKeep(null, "5001", "2000", null, 7));
        assertEquals(0, KeptUnsentStore.count());

        Settings.KEEP_UNSENT_MESSAGES.save(true);
        assertTrue(KeepUnsent.shouldKeep(null, "5001", "2000", null, 7));
        assertEquals(1, KeptUnsentStore.count());
    }

    @Test public void yourOwnUnsendStillRemovesTheMessage() {
        Settings.KEEP_UNSENT_MESSAGES.save(true);
        assertFalse(KeepUnsent.shouldKeep(null, "5001", "1000", null, 7));
        assertEquals(0, KeptUnsentStore.count());
    }

    @Test public void anUnsendWhoseSenderCannotBeToldIsApplied() {
        Settings.KEEP_UNSENT_MESSAGES.save(true);
        // The recall names nobody: there is no telling whose it is, so it goes through.
        assertFalse(KeepUnsent.shouldKeep(null, "5001", null, null, 7));
        assertFalse(KeepUnsent.shouldKeep(null, "5001", "", null, 7));
        // No message id, nothing to keep.
        assertFalse(KeepUnsent.shouldKeep(null, "", "2000", null, 7));
        assertFalse(KeepUnsent.shouldKeep(null, null, "2000", null, 7));

        // Nobody is signed in, so there is nobody to be "the other person" to.
        SignedInUser.idForTests = "";
        assertFalse(KeepUnsent.shouldKeep(null, "5001", "2000", null, 7));
        assertEquals(0, KeptUnsentStore.count());
    }

    @Test public void theSameMessageIsOnlyWrittenDownOnce() {
        Settings.KEEP_UNSENT_MESSAGES.save(true);
        assertTrue(KeepUnsent.shouldKeep(null, "5001", "2000", null, 7));
        assertTrue(KeepUnsent.shouldKeep(null, "5001", "2000", null, 7));
        assertEquals(1, KeptUnsentStore.count());
    }

    @Test public void aKeptTextMessageSaysItWasUnsentAndNothingElseDoes() {
        Settings.KEEP_UNSENT_MESSAGES.save(true);
        KeepUnsent.shouldKeep(null, "5001", "2000", null, 7);

        String content = "{\"text\":\"see you at 5\"}";
        String marked = KeepUnsent.markContent(new FakeMessage(5001), content);
        assertEquals("{\"text\":\"see you at 5  (unsent)\"}", marked);

        // Marking twice, as a redraw would, doesn't pile it up.
        assertEquals(marked, KeepUnsent.markContent(new FakeMessage(5001), marked));
        // Another message, a message with no text, and a missing content are left alone.
        assertEquals(content, KeepUnsent.markContent(new FakeMessage(5002), content));
        assertEquals("{\"uri\":\"x\"}", KeepUnsent.markContent(new FakeMessage(5001), "{\"uri\":\"x\"}"));
        assertEquals("not json", KeepUnsent.markContent(new FakeMessage(5001), "not json"));
        assertEquals(null, KeepUnsent.markContent(new FakeMessage(5001), null));
    }

    @Test public void theMarkCanBeSwitchedOffAndKeptMessagesAreStillThere() {
        Settings.KEEP_UNSENT_MESSAGES.save(true);
        KeepUnsent.shouldKeep(null, "5001", "2000", null, 7);

        Settings.MARK_KEPT_UNSENT_MESSAGES.save(false);
        String content = "{\"text\":\"hi\"}";
        assertEquals(content, KeepUnsent.markContent(new FakeMessage(5001), content));
        assertEquals(1, KeptUnsentStore.count());
    }

    @Test public void clearingWithNothingKeptSaysSoAndClearingWithNoUpdaterAsksToTryAgain() {
        String[] heard = new String[1];
        KeepUnsent.clearKept(message -> heard[0] = message);
        assertEquals("Nothing has been kept", heard[0]);

        Settings.KEEP_UNSENT_MESSAGES.save(true);
        KeepUnsent.shouldKeep(null, "5001", "2000", null, 7);
        heard[0] = null;
        KeepUnsent.clearKept(message -> heard[0] = message);
        assertEquals("TikTok isn't ready yet. Open your Inbox, then try again.", heard[0]);
        // Nothing was removed from the record, so a second try is possible.
        assertEquals(1, KeptUnsentStore.count());
    }

    @Test public void theStatusLineCountsWhatWasKept() {
        assertEquals("Kept so far: 0", KeepUnsent.statusLine());
        Settings.KEEP_UNSENT_MESSAGES.save(true);
        KeepUnsent.shouldKeep(null, "5001", "2000", null, 7);
        KeepUnsent.shouldKeep(null, "5002", "2000", null, 7);
        assertEquals("Kept so far: 2", KeepUnsent.statusLine());
    }
}
