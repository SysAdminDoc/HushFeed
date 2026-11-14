/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.feed.AuthorRegion;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Reading an account's country out of TikTok's profile reply (#417). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class AccountCountryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() { AuthorRegion.forgetSeenRegions(); }

    @After public void tearDown() { AuthorRegion.forgetSeenRegions(); }

    private static void found(String body, String code) {
        AccountCountry.Answer answer = AccountCountry.parse(body);
        assertEquals(body, AccountCountry.Outcome.FOUND, answer.outcome);
        assertEquals(body, code, answer.code);
    }

    private static void outcome(String body, AccountCountry.Outcome outcome) {
        AccountCountry.Answer answer = AccountCountry.parse(body);
        assertEquals(body, outcome, answer.outcome);
        assertNull(body, answer.code);
    }

    @Test public void theUserRecordsRegionComesFirstThenItsAccountRegion() {
        found("{\"status_code\":0,\"user\":{\"uid\":\"200\",\"region\":\"de\",\"account_region\":\"US\"}}", "DE");
        found("{\"status_code\":0,\"user\":{\"region\":\"\",\"account_region\":\" us \"}}", "US");
        found("{\"user\":{\"region\":\"BR\"}}", "BR");
    }

    @Test public void aRecordWithoutACountryIsNoneAndARefusedReplyIsAFailure() {
        outcome("{\"status_code\":0,\"user\":{\"uid\":\"200\"}}", AccountCountry.Outcome.NONE);
        outcome("{\"status_code\":0,\"user\":{\"region\":\"Germany\",\"account_region\":\"U1\"}}",
                AccountCountry.Outcome.NONE);
        outcome("{\"status_code\":2190,\"status_msg\":\"Please try again\"}", AccountCountry.Outcome.FAILED);
        outcome("{\"status_code\":8,\"user\":{\"region\":\"DE\"}}", AccountCountry.Outcome.FAILED);
        outcome("{\"status_code\":0}", AccountCountry.Outcome.FAILED);
        outcome("<html>blocked</html>", AccountCountry.Outcome.FAILED);
        outcome("", AccountCountry.Outcome.FAILED);
        outcome(null, AccountCountry.Outcome.FAILED);
    }

    @Test public void onlyTwoLettersCount() {
        assertEquals("US", AccountCountry.twoLetters(" us "));
        assertNull(AccountCountry.twoLetters("USA"));
        assertNull(AccountCountry.twoLetters("U1"));
        assertNull(AccountCountry.twoLetters(""));
        assertNull(AccountCountry.twoLetters(null));
    }

    @Test public void theApiHostIsTheBareHttpsOrigin() {
        assertTrue(AccountCountry.isHost("https://api16-normal-c-useast1a.tiktokv.com"));
        assertFalse(AccountCountry.isHost("https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/upload/image/"));
        assertFalse(AccountCountry.isHost("http://api16-normal-c-useast1a.tiktokv.com"));
        assertFalse(AccountCountry.isHost("https://"));
        assertFalse(AccountCountry.isHost(null));
    }

    @Test public void eachAnswerReadsAsOneLineAndAFoundCountryIsRemembered() {
        assertEquals("Account based in Japan",
                AccountCountry.answered("200", new AccountCountry.Answer(AccountCountry.Outcome.FOUND, "JP")));
        assertEquals("JP", AuthorRegion.seenRegionByUid("200"));
        assertEquals("TikTok gives no country for this account",
                AccountCountry.answered("300", new AccountCountry.Answer(AccountCountry.Outcome.NONE, null)));
        assertEquals("Couldn't get the country. Try again.", AccountCountry.answered("300", AccountCountry.failed()));
        assertNull(AuthorRegion.seenRegionByUid("300"));
        assertEquals("Germany", AccountCountry.countryName("DE"));
        assertEquals("a code with no name shows as the code", "XY", AccountCountry.countryName("XY"));
    }
}
