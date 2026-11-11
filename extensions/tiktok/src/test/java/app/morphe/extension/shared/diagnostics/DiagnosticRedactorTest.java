/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.blockauthor.VideoAuthor;

import org.junit.Test;

/**
 * A creator's name has no shape a pattern can find, and the toast that carries it is written to
 * the buffer in whichever language the phone speaks. The bundle marks the name instead, with the
 * isolate pair {@code VideoAuthor.label()} wraps it in, and the redactor finds the marks.
 */
public class DiagnosticRedactorTest {

    private static final String FSI = "⁨";
    private static final String PDI = "⁩";

    /** Android's connect failure names the phone's own address, routable on an IPv6 network. */
    @Test public void aConnectFailureLosesBothAddressesAndKeepsItsShape() {
        String line = DiagnosticRedactor.redact("failed to connect to cdn.example/203.0.113.9 (port 443)"
                + " from /2607:fb90:8a2c:1d4e:5c1f:9b2a:77e3:41d0 (port 38754) after 15000ms;"
                + " retry via /fe80::1%wlan0 (port 443)");

        assertEquals("failed to connect to [host omitted]/[address omitted] (port 443)"
                + " from /[address omitted] (port 38754) after 15000ms;"
                + " retry via /[address omitted] (port 443)", line);
    }

    /** A proxy or a router is the reader's own server, and no list of TikTok's hosts names it. */
    @Test public void theNameInFrontOfAnAddressAndALookupsQuotedNameGoToo() {
        String line = DiagnosticRedactor.redact("failed to connect to myproxy.lan/192.168.1.5 (port 3128);"
                + " Unable to resolve host \"dana-pi.home\": No address associated with hostname;"
                + " via router.home/fd00::1 at com.example.Net.open(Net.java:42)");

        assertEquals("failed to connect to [host omitted]/[address omitted] (port 3128);"
                + " Unable to resolve host \"[host omitted]\": No address associated with hostname;"
                + " via [host omitted]/[address omitted] at com.example.Net.open(Net.java:42)", line);
    }

    /** Java prints an IPv6 address in full, while a slash in front of a clock time is just a slash. */
    @Test public void aClockTimeAfterASlashIsNotAnAddressButJavasFullIpv6Is() {
        String line = DiagnosticRedactor.redact("at 2026-11-11/10:23:11 position 00:01:05/00:02:30"
                + " via router.home/fd00:0:0:0:0:0:0:1%wlan0");

        assertEquals("at 2026-11-11/10:23:11 position 00:01:05/00:02:30 via [host omitted]/[address omitted]", line);
    }

    @Test public void aProxyNamedWithAnUnderscoreUnresolvedOrInsideJsonGoesToo() {
        String line = DiagnosticRedactor.redact("my_proxy/192.168.1.5; HTTP @ myproxy.lan:3128;"
                + " SOCKS @ socks.lan/<unresolved>:1080;"
                + " {\"msg\":\"Unable to resolve host \\\"dana-pi.home\\\": No address\"}");

        assertEquals("[host omitted]/[address omitted]; HTTP @ [host omitted]:3128;"
                + " SOCKS @ [host omitted]/<unresolved>:1080;"
                + " {\"msg\":\"Unable to resolve host \\\"[host omitted]\\\": No address\"}", line);
    }

    @Test public void clockTimesAndVersionsAreNotTakenForAddresses() {
        String line = "10:23:11.269 Hushfeed 0.63.0 for TikTok 47.1.3, Logger::printInfo";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }

    /** A saved file's name glues the video's id to the date with an underscore. */
    @Test public void anIdGluedToAnUnderscoreIsStillAnId() {
        assertEquals("file=dana_2026-09-01_[id omitted].mp4",
                DiagnosticRedactor.redact("file=dana_2026-09-01_7412345678901234567.mp4"));
    }

    @Test public void theUsAndEuServiceHostsAreOmittedToo() {
        assertEquals("Unable to resolve host \"[host omitted]\" and [host omitted]",
                DiagnosticRedactor.redact("Unable to resolve host \"v16m.tiktokcdn-us.com\""
                        + " and api16-normal-useast5.tiktokv.us"));
    }

    @Test public void theToastLineLosesTheNameAndKeepsTheAction() {
        String line = DiagnosticRedactor.redact("Showing toast: Blocked " + FSI + "Dana Q" + PDI);

        assertEquals("Showing toast: Blocked [name omitted]", line);
    }

    @Test public void theNameIsFoundByItsMarksInAnyLanguage() {
        String german = DiagnosticRedactor.redact(
                "Showing toast: " + FSI + "Dana Q" + PDI + " wird nicht mehr angezeigt");

        assertFalse("a name in a translated toast survived: " + german, german.contains("Dana Q"));
        assertTrue("the words around the name were lost: " + german,
                german.contains("[name omitted] wird nicht mehr angezeigt"));
    }

    @Test public void aHandleOrAnIdInsideTheMarksGoesWithThem() {
        String handle = DiagnosticRedactor.redact("Hidden " + FSI + "@dana.q" + PDI + " locally");
        String secUid = DiagnosticRedactor.redact(
                "Unblocked " + FSI + "MS4wLjABAAAA_ynkoP2m" + PDI);

        assertEquals("Hidden [name omitted] locally", handle);
        assertEquals("Unblocked [name omitted]", secUid);
    }

    @Test public void aNameThatLostItsClosingMarkIsCutToTheEndOfItsLine() {
        String text = DiagnosticRedactor.redact("Hidden " + FSI + "Dana Q locally\nnext line");

        assertEquals("Hidden [name omitted]\nnext line", text);
    }

    /**
     * A display name is whatever the account chose, so it can carry a line break or a copy of
     * the marks themselves. Either one used to split the run and leave the rest of the name in
     * the report. The label takes both out, so what arrives here is one run.
     */
    @Test public void aNameCarryingABreakOrTheMarksThemselvesIsStillOneRun() {
        String broken = new VideoAuthor(null, null, "Dana\nSecretHandle", null).label();
        String nested = new VideoAuthor(null, null, "A" + PDI + "B" + FSI + "C", null).label();

        assertEquals("Showing toast: Blocked [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Blocked " + broken));
        assertEquals("Showing toast: Blocked [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Blocked " + nested));
        assertFalse("half the name survived the break: " + broken,
                DiagnosticRedactor.redact(broken).contains("SecretHandle"));
        assertFalse("the middle of the name survived its own marks: " + nested,
                DiagnosticRedactor.redact(nested).contains("B"));
    }

    /**
     * The control for the pair above. Without the flattening the same two names leak, which is
     * what makes the flattening the thing being tested rather than the pattern.
     */
    @Test public void theSameTwoNamesLeakWhenTheyAreNotFlattenedFirst() {
        String rawBreak = FSI + "Dana\nSecretHandle" + PDI;
        String rawNested = FSI + "A" + PDI + "B" + FSI + "C" + PDI;

        assertTrue(DiagnosticRedactor.redact(rawBreak).contains("SecretHandle"));
        assertTrue(DiagnosticRedactor.redact(rawNested).contains("B"));
    }

    @Test public void anAccountWithNothingToNameIsNotHiddenAtAll() {
        // "this creator" names nobody, so hiding it says less than the truth.
        assertEquals("Blocked this creator",
                DiagnosticRedactor.redact("Blocked "
                        + new VideoAuthor(null, null, null, null).label()));
    }

    /** An email is not cut into a name and a handle, and it goes as a whole. */
    @Test public void aHandleOnItsOwnGoesAndAnEmailGoesWholeWhileAnObjectHashStays() {
        String text = DiagnosticRedactor.redact(
                "@dana.q wrote to dana@example.com from Foo@1a2b3c and mentioned @dana_q2");

        assertEquals("[handle omitted] wrote to [email omitted] from Foo@1a2b3c"
                + " and mentioned [handle omitted]", text);
    }

    @Test public void anInternationalPhoneNumberGoesWhileVersionsAndTimesStay() {
        String text = DiagnosticRedactor.redact("code sent to +12025550123, retry in 60s;"
                + " 10:23:11.269 TikTok 47.7.3 offset +0500");

        assertEquals("code sent to [phone omitted], retry in 60s;"
                + " 10:23:11.269 TikTok 47.7.3 offset +0500", text);
    }

    @Test public void aPhoneNumberSplitBySpacesDashesDotsOrBracketsGoesToo() {
        String text = DiagnosticRedactor.redact("to +1 202-555-0123, +44 20 7946 0958 and +1 (202) 555.0123;"
                + " 5 more, +0500, +12 likes");

        assertEquals("to [phone omitted], [phone omitted] and [phone omitted];"
                + " 5 more, +0500, +12 likes", text);
    }

    @Test public void aTimeZoneOffsetInFrontOfADateIsNotAPhoneNumber() {
        String line = "+0500 2026-11-11 10:23:11 started";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }

    /** A message ending in an empty field is followed by the trace, which has to stay readable. */
    @Test public void anEmptyFieldAtALineEndLeavesTheNextLineAlone() {
        String line = "java.lang.IllegalArgumentException: Invalid email: \n"
                + "\tat com.example.Signup.check(Signup.java:12)\nusername:\nCookie:\n"
                + "\tat com.example.Net.open(Net.java:42)";
        String trace = DiagnosticRedactor.redactThrowable(new IllegalArgumentException("Invalid email: "));

        assertEquals(line, DiagnosticRedactor.redact(line));
        assertTrue(trace, trace.contains("Invalid email: \n\tat "));
        assertFalse(trace, trace.contains("[omitted]"));
    }

    /** An address can end in a dash, and a drawable's density suffix is a file name, not a domain. */
    @Test public void anEmailEndingInADashGoesWholeAndADensitySuffixStays() {
        String text = DiagnosticRedactor.redact("sent to a-@x.com and b+@x.com; loaded ic_like@2x.webp, icon@1.5x.png");

        assertEquals("sent to [email omitted] and [email omitted]; loaded ic_like@2x.webp, icon@1.5x.png", text);
    }

    /** A name written into a log line without quotes still runs to its line's end or its object's. */
    @Test public void anUnquotedNameOfSeveralWordsGoesWhole() {
        String text = DiagnosticRedactor.redact("nickname: Dana Quinn  \nUser{nickname=Dana Quinn, uniqueId=dq}"
                + " (display_name=Dana Q) token=abc def");

        assertEquals("nickname: [omitted]  \nUser{nickname=[omitted], uniqueId=[omitted]}"
                + " (display_name=[omitted]) token=[omitted] def", text);
    }

    @Test public void aNameKeepsTheBracketsItOpensAndTheApostrophesInItsWords() {
        String text = DiagnosticRedactor.redact("nickname: Dana O'Brien\nnickname=[DQ] Dana Quinn, uniqueId=dq;"
                + " display_name='Dana'");

        assertEquals("nickname: [omitted]\nnickname=[omitted], uniqueId=[omitted]; display_name='[omitted]'", text);
    }

    @Test public void theFieldsThatNameAnAccountLoseTheirValues() {
        String text = DiagnosticRedactor.redact("{\"nickname\":\"Dana Q\",\"unique_id\":\"dana.q\","
                + "\"uniqueId\":\"dana.q2\",\"email\":\"d@x.io\",\"mobile\":\"2025550123\","
                + "\"status\":\"ready\"} copy_comments_without_username=true");

        for (String leaked : new String[]{"Dana Q", "dana.q", "d@x.io", "2025550123"}) {
            assertFalse("an account detail survived: " + text, text.contains(leaked));
        }
        assertTrue(text, text.contains("\"status\":\"ready\""));
        assertTrue("a switch named after a user name keeps its value: " + text,
                text.contains("copy_comments_without_username=true"));
    }

    @Test public void theCreatorReferenceLineKeepsThePseudonymAndDropsTheVideoId() {
        String line = DiagnosticRedactor.redact(
                "Current video: creator 3f9a2c1b0e7d aweme=7012345678901234567");

        assertTrue("the pseudonym is what makes two lines comparable: " + line,
                line.contains("creator 3f9a2c1b0e7d"));
        assertFalse("the video id names what was watched: " + line,
                line.contains("7012345678901234567"));
    }

    /** The mutation control: a line with no name, handle or id is returned as it came. */
    @Test public void aLineWithNothingToHideIsLeftAlone() {
        String line = "Sending block_type=1 for creator 3f9a2c1b0e7d";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }

    @Test public void quotedJsonCredentialsAndNamedIdsAreRemoved() {
        String text = DiagnosticRedactor.redact(
                "{\"sessionid\":\"JSON_SESSION_SENTINEL\","
                        + "\"nested\":{\"access_token\":\"JSON_TOKEN_SENTINEL\"},"
                        + "\"device_id\":\"JSON_DEVICE_SENTINEL\","
                        + "\"aweme_id\":\"JSON_POST_SENTINEL\",\"status\":\"ready\"}");

        for (String secret : new String[]{"JSON_SESSION_SENTINEL", "JSON_TOKEN_SENTINEL",
                "JSON_DEVICE_SENTINEL", "JSON_POST_SENTINEL"}) {
            assertFalse("A structured value survived: " + secret, text.contains(secret));
        }
        assertTrue("Useful non-sensitive fields disappeared", text.contains("ready"));
    }

    @Test public void escapedJsonAndSpacesInsideQuotedValuesDoNotExposeCredentials() {
        String escaped = DiagnosticRedactor.redact(
                "payload={\\\"sessionid\\\":\\\"ESCAPED_SENTINEL\\\"}");
        String quoted = DiagnosticRedactor.redact(
                "{\"password\":\"FIRST_SENTINEL SECOND_SENTINEL\",\"retry\":2}");

        assertFalse(escaped.contains("ESCAPED_SENTINEL"));
        assertFalse(quoted.contains("FIRST_SENTINEL"));
        assertFalse(quoted.contains("SECOND_SENTINEL"));
        assertTrue(quoted.contains("retry"));
    }

    @Test public void authenticationAndCookieHeadersLoseTheirEntireValues() {
        String text = DiagnosticRedactor.redact(
                "Authorization: Bearer AUTH_SENTINEL\r\n"
                        + "Cookie: locale=en; arbitrary=COOKIE_SENTINEL\r\n"
                        + "status: ready\n");

        assertFalse(text.contains("AUTH_SENTINEL"));
        assertFalse(text.contains("COOKIE_SENTINEL"));
        assertTrue("The following diagnostic line was lost", text.contains("status: ready"));
    }

    @Test public void plaintextCredentialTokensDoNotLeaveSuffixesAfterBrackets() {
        for (String value : new String[]{"prefix]PLAIN_SENTINEL", "prefix}PLAIN_SENTINEL",
                "[opaque]PLAIN_SENTINEL", "{opaque}PLAIN_SENTINEL", "[omitted]PLAIN_SENTINEL"}) {
            String text = DiagnosticRedactor.redact("sessionid=" + value + " phase=ready");
            assertFalse("An opaque credential was split at a bracket: " + text,
                    text.contains("PLAIN_SENTINEL"));
            assertTrue(text.contains("phase=ready"));
        }
        String once = DiagnosticRedactor.redact("sessionid=ordinary-token phase=ready");
        assertEquals("Repeated sink redaction should preserve its own marker",
                once, DiagnosticRedactor.redact(once));
    }

    @Test public void headersCannotUseStructuredPrefixesToLeaveCredentialTails() {
        for (String header : new String[]{"Authorization: [omitted] HEADER_SENTINEL",
                "Authorization: {scheme=Bearer} HEADER_SENTINEL",
                "Cookie: \"already-masked\"; arbitrary=HEADER_SENTINEL"}) {
            String text = DiagnosticRedactor.redact(header + "\nstatus: ready");
            assertFalse("A header kept a suffix after a structured prefix: " + text,
                    text.contains("HEADER_SENTINEL"));
            assertTrue("The next diagnostic line was lost", text.contains("status: ready"));
        }
        String json = DiagnosticRedactor.redact(
                "{\"Authorization\":\"Bearer JSON_HEADER_SENTINEL\",\"status\":\"ready\"}");
        assertFalse(json.contains("JSON_HEADER_SENTINEL"));
        assertTrue("A JSON field is not a whole HTTP header line", json.contains("ready"));
    }

    @Test public void anUnterminatedQuotedCredentialStillHidesItsRemainingValue() {
        String text = DiagnosticRedactor.redact(
                "phase=decode\n{\"sessionid\":\"BROKEN_SENTINEL SECOND_SENTINEL");

        assertFalse(text.contains("BROKEN_SENTINEL"));
        assertFalse(text.contains("SECOND_SENTINEL"));
        assertTrue(text.contains("phase=decode"));
    }

    @Test(timeout = 5000) public void aLongDottedHostIsRedactedWithoutRecursiveMatching() {
        String text = DiagnosticRedactor.redact("version=47.1.3\n"
                + "a.".repeat(4096) + "tiktokv.com/path?sessionid=DOTTED_SENTINEL\n"
                + "completed diagnostic step");

        assertFalse(text.contains("tiktokv.com"));
        assertFalse(text.contains("DOTTED_SENTINEL"));
        assertTrue(text.contains("version=47.1.3"));
        assertTrue(text.contains("completed diagnostic step"));
    }

    @Test(timeout = 5000) public void oversizedDiagnosticInputIsBoundedAndSaysItWasTruncated() {
        String input = "version=47.1.3\nsessionid=LARGE_SENTINEL\n"
                + "ordinary diagnostic detail\n".repeat(80_000);
        String text = DiagnosticRedactor.redact(input);

        assertTrue("A single diagnostic input retained more than the whole buffer's ceiling",
                text.length() <= 250_000);
        assertTrue("The reader cannot tell context was omitted",
                text.toLowerCase(java.util.Locale.ROOT).contains("truncated"));
        assertTrue(text.contains("version=47.1.3"));
        assertFalse(text.contains("LARGE_SENTINEL"));
    }
}
