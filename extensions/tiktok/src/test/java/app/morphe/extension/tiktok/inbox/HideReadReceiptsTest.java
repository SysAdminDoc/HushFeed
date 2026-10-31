package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Which requests are held back, and that anything unexpected is sent as TikTok always did. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HideReadReceiptsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The part of TikTok's request body that says what kind of request it is. */
    public static final class FakeRequestBody {
        public Object mark_conversation_read_body;
        public Object batch_mark_read_body;
        public Object send_message_body;
    }

    /** A body that has none of those fields, as a different build of TikTok might have. */
    public static final class OtherBody {
        public Object something_else;
    }

    @Before public void start() {
        Settings.HIDE_READ_RECEIPTS.save(false);
    }

    @After public void finish() {
        Settings.HIDE_READ_RECEIPTS.save(false);
    }

    @Test public void aReadMarkIsHeldBackOnlyWhileTheSwitchIsOn() {
        FakeRequestBody single = new FakeRequestBody();
        single.mark_conversation_read_body = new Object();
        FakeRequestBody batch = new FakeRequestBody();
        batch.batch_mark_read_body = new Object();

        assertFalse(HideReadReceipts.shouldSkip(single));
        assertFalse(HideReadReceipts.shouldSkip(batch));

        Settings.HIDE_READ_RECEIPTS.save(true);
        assertTrue(HideReadReceipts.shouldSkip(single));
        assertTrue(HideReadReceipts.shouldSkip(batch));

        Settings.HIDE_READ_RECEIPTS.save(false);
        assertFalse(HideReadReceipts.shouldSkip(single));
    }

    @Test public void everyOtherRequestIsSent() {
        Settings.HIDE_READ_RECEIPTS.save(true);
        FakeRequestBody message = new FakeRequestBody();
        message.send_message_body = new Object();

        assertFalse(HideReadReceipts.shouldSkip(message));
        assertFalse(HideReadReceipts.shouldSkip(new FakeRequestBody()));
    }

    @Test public void aBodyShapedDifferentlyOrMissingIsSent() {
        Settings.HIDE_READ_RECEIPTS.save(true);

        assertFalse(HideReadReceipts.shouldSkip(new OtherBody()));
        assertFalse(HideReadReceipts.shouldSkip("not a body"));
        assertFalse(HideReadReceipts.shouldSkip(null));
    }

    @Test public void aReadMarkIsStillRecognisedAfterADifferentBodyWasSeen() {
        Settings.HIDE_READ_RECEIPTS.save(true);
        FakeRequestBody single = new FakeRequestBody();
        single.mark_conversation_read_body = new Object();

        assertFalse(HideReadReceipts.shouldSkip(new OtherBody()));
        assertTrue(HideReadReceipts.shouldSkip(single));
        assertFalse(HideReadReceipts.shouldSkip(new OtherBody()));
        assertTrue(HideReadReceipts.shouldSkip(single));
    }
}
