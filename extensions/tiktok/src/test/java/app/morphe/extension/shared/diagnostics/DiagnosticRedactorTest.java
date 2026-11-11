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

    /** An emoticon's lone bracket and a comma inside a phrase belong to the name, and the line after its object stays. */
    @Test public void aNameWithAnEmoticonOrACommaGoesWholeAndTheRestOfTheLineStays() {
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: Dana :) Quinn"));
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: Dana <3 Quinn"));
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: Dana, Queen of Cats"));
        assertEquals("User{nickname=[omitted], uniqueId=[omitted]} logged in at 10:23",
                DiagnosticRedactor.redact("User{nickname=Dana :(, uniqueId=dq} logged in at 10:23"));
    }

    /** A smiley before the field, or one in the name, neither holds the field nor closes its object. */
    @Test public void aSmileyNeitherEndsANameNorHoldsItsField() {
        assertEquals("status: sad :( nickname: [omitted]",
                DiagnosticRedactor.redact("status: sad :( nickname: Dana :) Quinn"));
        assertEquals("User{bio=sad :(, nickname=[omitted]} done",
                DiagnosticRedactor.redact("User{bio=sad :(, nickname=Dana :) Quinn} done"));
        assertEquals("User(nickname=[omitted], uniqueId=[omitted]) logged in at 10:23",
                DiagnosticRedactor.redact("User(nickname=Dana :) Quinn, uniqueId=dq) logged in at 10:23"));
        assertEquals("User{bio=hi :), nickname=[omitted]} done",
                DiagnosticRedactor.redact("User{bio=hi :), nickname=Dana Quinn} done"));
        assertEquals("User{bio=hi:), nickname=[omitted]} done",
                DiagnosticRedactor.redact("User{bio=hi:), nickname=Dana Quinn} done"));
        assertEquals("User{bio=sad:(, nickname=[omitted]} done",
                DiagnosticRedactor.redact("User{bio=sad:(, nickname=Dana Quinn} done"));
        assertEquals("User(nickname=[omitted], uniqueId=[omitted]) logged in",
                DiagnosticRedactor.redact("User(nickname=Dana (DQ :), uniqueId=dq) logged in"));
        assertEquals("User[nickname=[omitted]] done", DiagnosticRedactor.redact("User[nickname=Dana:] done"));
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: Dana :'( Quinn"));
        assertEquals("[nickname:[omitted], uniqueId:[omitted]]",
                DiagnosticRedactor.redact("[nickname:[DQ] Dana Quinn, uniqueId:dq]"));
        assertEquals("User(nickname=[omitted], uniqueId=[omitted]) logged in",
                DiagnosticRedactor.redact("User(nickname=Dana :(DQ) Quinn, uniqueId=dq) logged in"));
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: Dana:(DQ) Quinn"));
        assertEquals("Result:(nickname: [omitted]) ok", DiagnosticRedactor.redact("Result:(nickname: Dana Quinn) ok"));
        assertTrue(DiagnosticRedactor.redact("Conn(local=[::]:443, nickname=Dana Quinn) done")
                .endsWith(", nickname=[omitted]) done"));
    }

    /** A quote ends a name only when it closes the string the field sits in, and a path's backslashes are not escapes. */
    @Test public void aQuoteEndsANameOnlyWhenItClosesTheFieldsString() {
        for (String name : new String[]{"Dana \"DQ\" Quinn", "James' Bakery", "C:\\new\\Dana Quinn", "Dana&amp;Co: Bakery"}) {
            assertEquals(name, "nickname: [omitted]", DiagnosticRedactor.redact("nickname: " + name));
        }
        assertEquals("{\"msg\":\"nickname: [omitted]\"}",
                DiagnosticRedactor.redact("{\"msg\":\"nickname: C:\\\\new\\\\Dana Quinn\"}"));
        assertEquals("{\"nickname\":[omitted]}", DiagnosticRedactor.redact("{\"nickname\":Dana \"DQ\" Quinn}"));
        assertEquals("log('nickname: [omitted]')", DiagnosticRedactor.redact("log('nickname: James O'Brien')"));
        assertEquals("log('nickname: [omitted]')", DiagnosticRedactor.redact("log('nickname: Dana :'( Quinn')"));
        assertEquals("log('nickname: [omitted]')", DiagnosticRedactor.redact("log('nickname: C:\\new\\Dana Quinn')"));
        assertEquals("msg=\"nickname: [omitted]\" next",
                DiagnosticRedactor.redact("msg=\"nickname: C:\\new\\Dana Quinn\" next"));
        for (String path : new String[]{"C:\\Program Files\\new\\Dana Quinn", "C:\\My Docs\\new\\Dana Quinn",
                "\\\\nas\\new\\Dana Quinn", "(C:\\new\\Dana Quinn)", "Users\\new\\Dana Quinn"}) {
            assertEquals(path, "msg=\"nickname: [omitted]\" next",
                    DiagnosticRedactor.redact("msg=\"nickname: " + path + "\" next"));
            assertEquals(path, "log('nickname: [omitted]')", DiagnosticRedactor.redact("log('nickname: " + path + "')"));
        }
        assertEquals("{\"token\":\"[omitted]\",\"msg\":\"nickname: [omitted]\"} after",
                DiagnosticRedactor.redact("{\"token\":\"a\\\"b\",\"msg\":\"nickname: Dana Quinn\"} after"));
    }

    @Test public void anApostropheAfterAStyledOrAccentedLetterStaysInTheName() {
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: " + script("Dana's Quinn")));
        assertEquals("nickname: [omitted]",
                DiagnosticRedactor.redact("nickname: Chloe" + (char) 0x308 + "'s Shop Dana"));
        assertEquals("log('nickname: [omitted]')",
                DiagnosticRedactor.redact("log('nickname: " + script("Dana's Quinn") + "')"));
        assertEquals("log('nickname: [omitted]')",
                DiagnosticRedactor.redact("log('nickname: Chloe" + (char) 0x308 + "'s Shop Dana')"));
    }

    /** A log line inside a JSON string carries its line breaks as escapes, and a name ends at one. */
    @Test public void aNameInsideAJsonStringEndsAtItsEscapedLineBreak() {
        String trace = "{\"trace\":\"Invalid email: \\n\\tat com.example.Signup.check(Signup.java:12)\"}";

        assertEquals(trace, DiagnosticRedactor.redact(trace));
        assertEquals("{\"msg\":\"nickname: [omitted]\\nnext\"}",
                DiagnosticRedactor.redact("{\"msg\":\"nickname: Dana Quinn\\nnext\"}"));
    }

    /** The escape in front of the next line's key is not part of it, and a value can start on that line. */
    @Test public void aFieldOnAJsonStringsNextLineStillLosesItsValue() {
        assertEquals("D/Profile: {\"dump\":\"nickname: [omitted]\\nunique_id: [omitted]\\nmobile: [omitted]\"}",
                DiagnosticRedactor.redact("D/Profile: {\"dump\":\"nickname: Dana Quinn\\nunique_id: danaq77\\nmobile: 4155550123\"}"));
        assertEquals("{\"m\":\"display_name: [omitted]\\r\\nusername: [omitted]\"}",
                DiagnosticRedactor.redact("{\"m\":\"display_name: Dana Q\\r\\nusername: danaq77\"}"));
        assertEquals("{\"log\":\"User profile\\nnickname: [omitted]\"}",
                DiagnosticRedactor.redact("{\"log\":\"User profile\\nnickname: Dana Quinn\"}"));
        assertEquals("{\"m\":\"a\\tusername=[omitted]\"}", DiagnosticRedactor.redact("{\"m\":\"a\\tusername=dq\"}"));
        assertEquals("{\"msg\":\"phone:\\n[omitted]\"}", DiagnosticRedactor.redact("{\"msg\":\"phone:\\n2025550123\"}"));
        assertEquals("{\"dump\":\"nickname:\\n\\t[omitted]\"}",
                DiagnosticRedactor.redact("{\"dump\":\"nickname:\\n\\tDana Quinn\"}"));
        assertEquals("{\"msg\":\"phone: \\r\\n[omitted]\"}",
                DiagnosticRedactor.redact("{\"msg\":\"phone: \\r\\n(202) 555-0123\"}"));
        // Not a key with the escape's letter taken off.
        String note = "{\"m\":\"a\\nname: Dana\"}";
        assertEquals(note, DiagnosticRedactor.redact(note));
        // A next line that starts with a field of ours keeps its key for that field's own value.
        assertEquals("{\"msg\":\"phone:\\nsession=\\\"[omitted]\\\"\"}",
                DiagnosticRedactor.redact("{\"msg\":\"phone:\\nsession=\\\"abc123secret\\\"\"}"));
        assertEquals("{\"msg\":\"phone:\\nusername=[omitted]\"}",
                DiagnosticRedactor.redact("{\"msg\":\"phone:\\nusername=danaq77\"}"));
        assertEquals("{\"m\":\"x\\naid=[omitted],nickname=[omitted]\"}",
                DiagnosticRedactor.redact("{\"m\":\"x\\naid=7,nickname=Dana Quinn\"}"));
        assertEquals("aid=[omitted],nickname=[omitted]", DiagnosticRedactor.redact("aid=7,nickname=Dana Quinn"));
        assertEquals("aid=[omitted]", DiagnosticRedactor.redact("aid=7,8,9"));
        assertEquals("aid=[omitted],video:7235", DiagnosticRedactor.redact("aid=video:7234,video:7235"));
        // Any field after a comma ends an id, so a name behind it goes and the details stay.
        assertEquals("aid=[omitted],extra:nickname=[omitted]", DiagnosticRedactor.redact("aid=7,extra:nickname=Dana Quinn"));
        assertEquals("group_id=[omitted],meta:nickname=[omitted],uid=[omitted]",
                DiagnosticRedactor.redact("group_id=7,meta:nickname=Dana Quinn,uid=5"));
        assertEquals("aweme_id=[omitted],index=3,source=feed", DiagnosticRedactor.redact("aweme_id=7234,index=3,source=feed"));
        assertEquals("Item(aweme_id=[omitted],desc=hi,count=3)", DiagnosticRedactor.redact("Item(aweme_id=7234,desc=hi,count=3)"));
        // A field of ours inside a name goes with the name, quoted value and all.
        assertEquals("{\"m\":\"phone:\\n[omitted]\"}",
                DiagnosticRedactor.redact("{\"m\":\"phone:\\nlookup username=\\\"danaq77\\\" failed\"}"));
        assertEquals("{\"m\":\"phone: [omitted]\"}",
                DiagnosticRedactor.redact("{\"m\":\"phone: lookup username=\\\"danaq77\\\" failed\"}"));
        assertEquals("'phone:\\n[omitted]'", DiagnosticRedactor.redact("'phone:\\nlookup username=\\'danaq77\\' failed'"));
        assertEquals("{\"m\":\"nickname:\\n[omitted]\"}",
                DiagnosticRedactor.redact("{\"m\":\"nickname:\\nUser: Dana session=\\\"s3cr3t\\\"\"}"));
        assertEquals("{\"m\":\"x\\nnickname: [omitted]\"}",
                DiagnosticRedactor.redact("{\"m\":\"x\\nnickname: Dana token=\\\"abc def\\\"\"}"));
        assertEquals("{\"m\":\"aid=[omitted],nickname=[omitted]\"}",
                DiagnosticRedactor.redact("{\"m\":\"aid=7,nickname=Dana unique_id=\\\"danaq77\\\"\"}"));
        // The string's own closing quote right after that field isn't its value's opening one.
        assertEquals("{\"m\":\"phone:\\n[omitted]\",\"n\":\"keep\"}",
                DiagnosticRedactor.redact("{\"m\":\"phone:\\nlookup username=\",\"n\":\"keep\"}"));
        // That field after an escaped tab, form feed or backspace goes too.
        for (String escape : new String[]{"t", "f", "b"}) {
            assertEquals("{\"m\":\"phone: [omitted]\"}",
                    DiagnosticRedactor.redact("{\"m\":\"phone: x\\" + escape + "username=\\\"danaq77\\\" failed\"}"));
        }
        // A value cut short ends with its line or its string, so the lines after it stay.
        String after = "\n\tat com.foo.Bar.run(Bar.java:12)\n11-11 10:23:11.123  1234  5678 E AndroidRuntime: main";
        assertEquals("{\"m\":\"nickname: [omitted]\"}" + after,
                DiagnosticRedactor.redact("{\"m\":\"nickname: Dana session=\\\"abc\"}" + after));
        assertEquals("{\"m\":\"nickname: [omitted]\"}" + after,
                DiagnosticRedactor.redact("{\"m\":\"nickname: Dana token='abc\"}" + after));
        assertEquals("{\"m\":\"nickname: [omitted]\\nkeep\"}",
                DiagnosticRedactor.redact("{\"m\":\"nickname: Dana token=\\\"abc\\nkeep\"}"));
        // A name in front of brackets isn't a stack frame.
        for (String name : new String[]{"at Jos" + (char) 0xE9 + "(DQ)", "at Dana.Quinn(DQ)", "at dana@home(work)"}) {
            assertEquals("{\"m\":\"nickname:\\n[omitted]\"}", DiagnosticRedactor.redact("{\"m\":\"nickname:\\n" + name + "\"}"));
        }
        // Every way Java and Kotlin print the frames under an empty field stays readable.
        for (String frame : new String[]{"at com.example.Signup.<init>(Signup.java:12)",
                "at java.base/java.lang.Thread.run(Thread.java:1012)", "at app//com.example.Signup.check(Signup.java:12)",
                "at com.example.Signup.check-Xyz12(Signup.kt:12)", "... 5 more", "Caused by: java.io.IOException",
                "Suppressed: java.io.IOException", "at java.base@17.0.2/java.lang.Thread.run(Thread.java:833)",
                "at com.caf" + (char) 0xE9 + ".Signup.run(Signup.java:3)",
                "at com.example.Caf" + (char) 0xE9 + ".run(Caf" + (char) 0xE9 + ".java:3)"}) {
            String trace = "{\"trace\":\"Invalid email: \\n\\t" + frame + "\"}";
            assertEquals(trace, DiagnosticRedactor.redact(trace));
        }
    }

    /** A home network names its hosts in any language, and Java prints their addresses several ways. */
    @Test public void aLocalHostNameGoesWithItsAddressHoweverJavaPrintsIt() {
        assertEquals("failed to connect to [host omitted]/[address omitted].",
                DiagnosticRedactor.redact("failed to connect to myproxy.lan/192.168.1.5."));
        assertEquals("via -[host omitted]/[address omitted]",
                DiagnosticRedactor.redact("via -myproxy.lan/192.168.1.5"));
        assertEquals("[host omitted]/[address omitted]",
                DiagnosticRedactor.redact("m" + (char) 0xFC + "nchen-pi.home/192.168.1.5"));
        assertEquals("[host omitted]/[[address omitted]]:443",
                DiagnosticRedactor.redact("router.home/[fd00::1]:443"));
        assertEquals("HTTP @ [host omitted]/[[address omitted]]:3128",
                DiagnosticRedactor.redact("HTTP @ myproxy.lan/[fd00::1]:3128"));
        assertEquals("[host omitted]/[address omitted]",
                DiagnosticRedactor.redact("router.home/::ffff:192.168.1.5"));
        assertEquals("Unable to resolve host \\\\\\\"[host omitted]\\\\\\\": No address",
                DiagnosticRedactor.redact("Unable to resolve host \\\\\\\"myproxy.lan\\\\\\\": No address"));
        assertEquals("build 10.2.3.4.5", DiagnosticRedactor.redact("build 10.2.3.4.5"));
    }

    /** Offsets from +1000 to +1400 have a phone number's first digit, so the clock or the date next to them decides. */
    @Test public void aTimeZoneOffsetBesideAClockOrADateIsNotAPhoneNumber() {
        for (String line : new String[]{"+1100 2026-11-11 10:23:11 started", "+1000 2026-11-11 10:23:11 started",
                "11-11 10:23:11.269 +1100  1234  5678 I Tag", "retry +1 2026-11-11 10:23:11.269",
                "11-11 10:23:11.269123 +1100  1234  5678 I Tag", "11-11 10:23:11.269 +1100   555 12345 W Tag"}) {
            assertEquals(line, DiagnosticRedactor.redact(line));
        }
        assertEquals("call [phone omitted] now", DiagnosticRedactor.redact("call +44 2079-46-09-58 now"));
        // Shaped like a date, but with no real year.
        assertEquals("call [phone omitted] now", DiagnosticRedactor.redact("call +44 7700-10-20 900 now"));
        assertEquals("call [phone omitted] now", DiagnosticRedactor.redact("call +46 8123-12-31 now"));
        // A real date keeps its offset whatever follows it.
        for (String line : new String[]{"+1100 2026-11-11 2026-11-12", "+1100 2026-11-11 10.23.11"}) {
            assertEquals(line, DiagnosticRedactor.redact(line));
        }
        String address = DiagnosticRedactor.redact("+1100 2026-11-11 203.0.113.45");
        assertTrue(address, address.startsWith("+1100 2026-11-11 ") && !address.contains("phone"));
        // A phone number ends before an address that follows it, so the address goes whole.
        for (String line : new String[]{"+1100 2100-11-11 203.0.113.45", "+44 7700-10-20 203.0.113.45"}) {
            String out = DiagnosticRedactor.redact(line);
            assertTrue(out, out.startsWith("[phone omitted] ") && !out.contains("113") && !out.contains(".0."));
        }
        // logcat's pid column reaches 4,194,304.
        String pid = "11-11 10:23:11.269 +1100 4194304 12345 I Tag";
        assertEquals(pid, DiagnosticRedactor.redact(pid));
    }

    /** The clock only excuses a four-digit offset, so a phone number written after a time still goes. */
    @Test public void aPhoneNumberAfterAClockStillGoes() {
        for (String time : new String[]{"Call at 10:23 ", "meet 9:30 ", "sent 10:23:11  ", "at 10:23:11.269 "}) {
            for (String phone : new String[]{"+44 20 7946 0958", "+14155550123", "+1 202 555 0143", "+61 412 345 678",
                    "+1202 555 0143", "+1415-555-0123", "+1312.555.0100"}) {
                assertEquals(time + "[phone omitted] called", DiagnosticRedactor.redact(time + phone + " called"));
            }
        }
        // Spaced like logcat's process, thread and level columns, but not padded like them.
        assertEquals("at 10:23:11.269 [phone omitted] I called",
                DiagnosticRedactor.redact("at 10:23:11.269 +1415 555 0123 I called"));
        assertEquals("10:23:11.269 [phone omitted] A new msg",
                DiagnosticRedactor.redact("10:23:11.269 +1312 555 0100 A new msg"));
        // Seven digits, but more than any process id can be.
        assertEquals("at 10:23:11.269 [phone omitted] 12345 A x",
                DiagnosticRedactor.redact("at 10:23:11.269 +1415 5550123 12345 A x"));
        assertEquals("at 10:23:11.269 [phone omitted] 12345 I x",
                DiagnosticRedactor.redact("at 10:23:11.269 +1415 4555012 12345 I x"));
    }

    /** A long run of hyphenated words, styled letters, backslashes or quoted values is read once, not once per word. */
    @Test(timeout = 5000) public void aLongHyphenatedOrEscapedRunIsRedactedInLinearTime() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000-";
        for (String run : new String[]{"a-".repeat(32_000), "1-".repeat(32_000), uuid.repeat(1_700),
                script("a-").repeat(21_000)}) {
            assertEquals(run, DiagnosticRedactor.redact(run));
        }
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: x" + "\\".repeat(60_000)));
        assertEquals("nickname: [omitted]", DiagnosticRedactor.redact("nickname: x," + "\\".repeat(60_000)));
        assertEquals("{\"msg\":\"nickname: [omitted]\"}",
                DiagnosticRedactor.redact("{\"msg\":\"nickname: x" + "\\".repeat(60_000) + "\"}"));
        assertEquals("{\"m\":\"nickname: [omitted]\"}", DiagnosticRedactor.redact("{\"m\":\"nickname: " + "a-".repeat(31_000) + "\"}"));
        assertEquals("{\"m\":\"phone: [omitted]\"}", DiagnosticRedactor.redact("{\"m\":\"phone: x " + "a-b ".repeat(15_000) + "y\"}"));
        assertEquals("{\"m\":\"nickname: [omitted] \"}",
                DiagnosticRedactor.redact("{\"m\":\"nickname: " + "x token=\\\"a ".repeat(5_000) + "\"}"));
        assertEquals("nickname: [omitted] ", DiagnosticRedactor.redact("nickname: " + "x token=\"a ".repeat(5_000)));
        String frames = "{\"m\":\"nickname:\\nat " + "a.".repeat(31_000) + "\"}";
        assertTrue(DiagnosticRedactor.redact(frames).startsWith("{\"m\":\"nickname:\\n[omitted]"));
        assertEquals("[" + "\"nickname: [omitted]\",".repeat(2_500) + "]",
                DiagnosticRedactor.redact("[" + "\"nickname: Dana\",".repeat(2_500) + "]"));
    }

    /** The inputs nine review rounds of the old pattern chain left over, each with a name, number or account in it. */
    @Test public void everyLeftoverLeakFromTheReviewRoundsGoes() {
        String chinese = "" + (char) 0x67E5 + (char) 0x8BE2;
        String[] inputs = {
                "{\"msg\":\"update profile nickname: Dana Quinn signature=\\\"Mom of 2\\nDallas TX\\\" ok\"}",
                "{\"m\":\"phone: lookup nickname=\\\"Dana\\nQuinn\\\" failed\"}",
                "{\"m\":\"nickname:\\nat a.b(C.java:3)Dana\"}",
                "aid=7234,author.nickname=Dana Quinn",
                "user[nickname]=Dana Quinn",
                "{\"m\":\"aweme_id: 7234\\nnickname: Dana Quinn\"}",
                "token=abc123\\nnickname: Dana Quinn",
                "{\"m\":\"bind phone: session=\\\"abc123secret\\\"\"}",
                "bind phone: session=\\\"abc123secret\\\"",
                "{\"m\":\"nickname: unique_id=\\\"danaq77\\\"\"}",
                "nickname: unique_id=\"danaq77\"",
                chinese + "username=danaq77",
                "{\"m\":\"phone: " + chinese + "username=\\\"danaq77\\\" failed\"}",
                "{\"m\":\"phone:\\nat 202.555.0123(ext:204)\"}",
                "{\"m\":\"phone:\\n\\\"2025550123\\\"\"}",
                "{\"m\":\"nickname:\\n\\\"Dana Quinn\\\"\"}",
                "{\"m\":\"phone:\\nlookup username:\\ndanaq77\"}",
                "{\"body\":\"{\\\"msg\\\":\\\"x\\\\nnickname: Dana Quinn\\\"}\"}",
                "{\"m\":\"nickname:\\nCaused by: Dana Quinn\"}",
                "login password=\"hunter2 and more",
                "author=Dana Quinn",
                "nickname2=Dana Quinn; _nickname: Dana Quinn",
                "author_nickname=Dana Quinn, owner.unique_id=danaq77",
                "nickname: Dana, Mom: the best",
                "nickname: Dana, Queen :) of Cats",
                "User(nickname=Dana:) Quinn, age=3)",
                "{\"m\":\"bio=\\\"rock 'n roll\\\" nickname: Dana 'DQ' Quinn\"}",
                "tv 6\" nickname: Dana \"DQ\" Quinn",
                "{\"m\":\"nickname: Dana token=\\\"abc\\nprofile nickname=\\\"Dana\\nQuinn\\\" ok\"}"};
        StringBuilder kept = new StringBuilder();
        for (String input : inputs) {
            String text = DiagnosticRedactor.redact(input);
            for (String leaked : new String[]{"Dana", "Quinn", "danaq77", "2025550123", "202.555", "hunter2",
                    "abc123", "7234", "Mom of", "Dallas", "the best", "Cats", "DQ"}) {
                if (text.contains(leaked)) kept.append('\n').append(input).append(" kept ").append(leaked).append(": ").append(text);
            }
        }
        assertEquals("", kept.toString());
        assertEquals("{\"body\":\"{\\\"nickname\\\":\\\"[omitted]\\\"}\"}",
                DiagnosticRedactor.redact("{\"body\":\"{\\\"nickname\\\":\\\"Dana Quinn\\\"}\"}"));
        assertEquals("{\"log\":\"nickname: [omitted]\\nI/Feed: loaded 20 items\\n...tapped \\\"Following\\\" tab\"}",
                DiagnosticRedactor.redact("{\"log\":\"nickname: Dana token=\\\"abc\\nI/Feed: loaded 20 items"
                        + "\\n...tapped \\\"Following\\\" tab\"}"));
    }

    /** And the ones where the rounds hid text that names nobody. */
    @Test public void leftoverTextThatNamesNobodyStays() {
        StringBuilder changed = new StringBuilder();
        for (String line : new String[]{
                "{\"m\":\"nickname:\\nat app.Feed.load(Foo.scala)\"}",
                "{\"m\":\"nickname:\\nat app.Feed.lambda$load$0(lambda)\"}",
                "C:\\temp\\taid=7",
                "+1100 1899-12-31 x",
                "11-11 10:23:11.123456789 +1100  1234  5678 I Feed: loaded 20 items",
                "{\"m\":\"Invalid email:\\nPlease try again\"}",
                "version 1.2.3.4.",
                "updated to 4.12.0.1. Restarting",
                "loaded lib-@1.2.3_rc"}) {
            String text = DiagnosticRedactor.redact(line);
            if (!text.equals(line)) changed.append('\n').append(line).append(" became ").append(text);
        }
        assertEquals("", changed.toString());
        assertEquals("{\"m\":\"token=\\\"[omitted]\\\"\",\"n\":\"keep\"}",
                DiagnosticRedactor.redact("{\"m\":\"token=\\\"C:\\\\Temp\\\\\",\"n\":\"keep\"}"));
        assertEquals("saved to cache/[[address omitted]]", DiagnosticRedactor.redact("saved to cache/[2001:db8::1]"));
        assertEquals("via --[host omitted]/[address omitted]", DiagnosticRedactor.redact("via --router/10.0.0.1"));
    }

    /** A version or a hash after a dash, a C++ name and a density file name are not accounts. */
    @Test public void aVersionAfterADashACppNameAndADensityFileNameStay() {
        String line = "loaded lib-@1.2.3 from C++@foo and --@dana; anim@2x.json, favicon@2x.ico, lib-@v1.2.3,"
                + " b@2x.heif, d@2x.jxl";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }

    @Test public void anAddressAtADomainStartingWithADensityGoesAndAHandleAfterADashGoes() {
        assertEquals("[email omitted] and [email omitted]",
                DiagnosticRedactor.redact("dana@2x.com and dana@3x.example.org"));
        assertEquals("cc-[handle omitted] and x+[handle omitted]", DiagnosticRedactor.redact("cc-@dana.q and x+@dana_q"));
    }

    /** The text in Unicode's bold script letters, two chars each, the way a styled nickname arrives. */
    private static String script(String plain) {
        StringBuilder styled = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (c >= 'A' && c <= 'Z') styled.appendCodePoint(0x1D4D0 + c - 'A');
            else if (c >= 'a' && c <= 'z') styled.appendCodePoint(0x1D4EA + c - 'a');
            else styled.append(c);
        }
        return styled.toString();
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
