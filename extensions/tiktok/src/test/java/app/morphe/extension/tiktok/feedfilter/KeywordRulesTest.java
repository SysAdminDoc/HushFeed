package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The blocked word lists take two operators as well as plain phrases. A list is typed by hand
 * into a settings box, so what it does with a line nobody finished matters as much as what it
 * does with a good one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class KeywordRulesTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    private static boolean hides(String list, String text) {
        return KeywordRules.anyMatches(KeywordRules.parse(list), text);
    }

    @Test public void aPlainPhraseStillMeansWhatItAlwaysDid() {
        assertTrue(hides("crypto, giveaway", "free giveaway today"));
        assertTrue("case is still ignored", hides("Crypto", "a CRYPTO video"));
        assertFalse(hides("crypto, giveaway", "a video about cats"));
        assertFalse("an empty list hides nothing", hides("", "anything at all"));
        assertFalse("a list of nothing but separators hides nothing", hides(" , , \\n ", "anything"));
    }

    @Test public void bothPhrasesHaveToBeThereForTheAndRule() {
        assertTrue(hides("\"cat\" & \"dog\"", "my cat and my dog"));
        assertFalse("one of the two was enough", hides("\"cat\" & \"dog\"", "just my cat"));
        assertFalse(hides("\"cat\" & \"dog\"", "just my dog"));
        assertFalse(hides("\"cat\" & \"dog\"", "neither one"));
    }

    @Test public void theSecondPhraseKeepsTheRuleOffForTheAndNotRule() {
        assertTrue(hides("\"cat\" !& \"dog\"", "just my cat"));
        assertFalse("the second phrase did not hold the rule off",
                hides("\"cat\" !& \"dog\"", "my cat and my dog"));
        assertFalse("the first phrase was not needed", hides("\"cat\" !& \"dog\"", "just my dog"));

        // Written the other way round, which is how half the people who use it will type it.
        assertTrue(hides("\"cat\" &! \"dog\"", "just my cat"));
        assertFalse(hides("\"cat\" &! \"dog\"", "my cat and my dog"));
    }

    @Test public void aRuleNobodyFinishedIsLeftOutAndSaidSo() {
        // Matched literally it would hide nothing, and say nothing about why.
        assertFalse(hides("\"cat\" & ", "a cat and a \"cat\" & "));
        assertNotNull("nothing was said about the unfinished rule",
                KeywordRules.problem("\"cat\" & "));
        assertTrue("the refusal does not name the line: " + KeywordRules.problem("\"cat\" & "),
                KeywordRules.problem("\"cat\" & ").contains("\"cat\" &"));

        // And the rest of the list still works, so one bad line does not cost the others.
        assertTrue(hides("crypto\n\"cat\" & ", "a crypto video"));
    }

    @Test public void aStrayQuoteDoesNotSwallowTheRestOfTheList() {
        // One unmatched quote used to make every comma and newline after it part of the phrase,
        // so a three line list became one entry that matched nothing, with nothing said about it.
        String list = "5\" screen, crypto, giveaway";
        assertEquals("the list collapsed into one entry", 3, KeywordRules.split(list).size());
        assertTrue("a later phrase stopped matching", hides(list, "a crypto video"));
        assertTrue(hides(list, "free giveaway"));
        assertNotNull("nothing was said about the stray quote", KeywordRules.problem(list));

        // Newlines separate the same way.
        String lines = "he said \"hi\ncrypto\ngiveaway";
        assertEquals(3, KeywordRules.split(lines).size());
        assertTrue(hides(lines, "a crypto video"));
    }

    @Test public void aPhraseWithAnAmpersandAndQuotesIsNotMistakenForARule() {
        // Asking only for an ampersand and a quote anywhere threw these away with no way to
        // tell, and they were working phrases before.
        for (String phrase : new String[]{"\"Q&A\"", "R&B \"remix\"", "he said \"hi\" & bye"}) {
            assertNull("refused a working phrase: " + phrase, KeywordRules.problem(phrase));
            assertEquals("the phrase was not kept: " + phrase, 1, KeywordRules.parse(phrase).size());
        }
        assertTrue(hides("\"Q&A\"", "a \"q&a\" video"));
        assertTrue(hides("R&B \"remix\"", "an r&b \"remix\" of it"));

        // And an entry that really did open a rule is still refused.
        assertNotNull(KeywordRules.problem("\"cat\" & "));
        assertNotNull("an empty phrase is not a rule", KeywordRules.problem("\"\" & \"b\""));
    }

    @Test public void anAmpersandInAPhraseIsStillAPhrase() {
        assertNull("AT&T was read as a rule nobody finished", KeywordRules.problem("AT&T"));
        assertTrue(hides("AT&T", "an at&t advert"));
    }

    @Test public void aCommaInsideQuotesIsNotASeparator() {
        // Split on it, the two halves are a quote that never closes and a rule with no left
        // hand side, and the entry hides nothing at all.
        List<String> entries = KeywordRules.split("\"hello, world\" & \"goodbye\", crypto");
        assertEquals(2, entries.size());
        assertEquals("\"hello, world\" & \"goodbye\"", entries.get(0));
        assertEquals("crypto", entries.get(1));
        assertTrue(hides("\"hello, world\" & \"goodbye\"", "hello, world and goodbye"));
    }

    @Test public void aNewlineSeparatesTheSameWayACommaDoes() {
        assertTrue(hides("crypto\n\"cat\" & \"dog\"", "my cat and my dog"));
        assertTrue(hides("crypto\n\"cat\" & \"dog\"", "a crypto video"));
    }

    @Test public void aHashtagHidesOnlyThatWholeTag() {
        assertTrue(hides("#cat", "my #Cat"));
        assertTrue(hides("#cat", "#cat, #dog"));
        assertTrue(hides("#cat", "#fyp#cat#dog"));
        assertTrue("a later whole tag still counts", hides("#cat", "#catholic and #cat"));
        assertFalse(hides("#cat", "#catholic"));
        assertFalse(hides("#cat", "#cats"));
        assertFalse(hides("#cat", "#cat_videos"));
        assertFalse("a combining accent carries the tag on", hides("#cafe", "#cafe" + (char) 0x301));
        // Without the # it's a plain phrase, and a phrase with words after the tag is too.
        assertTrue(hides("cat", "#catholic"));
        assertTrue(hides("#cat video", "#cat videos"));
        // Both halves of a rule follow the same reading.
        assertTrue(hides("\"#news\" !& \"#cat\"", "#news #cats"));
        assertFalse(hides("\"#news\" !& \"#cat\"", "#news #cat"));
        assertTrue(hides("\"#news\" & \"#cat\"", "#news #cat"));
        assertFalse(hides("\"#news\" & \"#cat\"", "#newsroom #cat"));
    }

    @Test public void aTagWithSpacesInsideItsQuotesIsStillThatWholeTag() {
        assertTrue(hides("\" #cat \" & \"dog\"", "#cat and a dog"));
        assertFalse(hides("\" #cat \" & \"dog\"", "#cats and a dog"));
        assertFalse(hides("\"#news\" !& \" #cat\"", "#news #cat"));
        // Spaces around a plain phrase still mark a word's edge.
        assertFalse(hides("\" cat\" & \"dog\"", "concat dog"));
        assertTrue(hides("\" cat\" & \"dog\"", "a cat dog"));
    }

    @Test public void aTagMadeOfSupplementaryLettersEndsWhereTheyDo() {
        String tag = "#" + new String(Character.toChars(0x20000));
        String next = new String(Character.toChars(0x20001));
        assertTrue(hides(tag, tag + " today"));
        assertFalse(hides(tag, tag + next));
    }

    @Test public void listsAndTextFoldCaseTheSameWay() {
        String dottedCapitalI = String.valueOf((char) 0x130);
        String dotlessI = String.valueOf((char) 0x131);
        assertEquals("#ali", KeywordRules.fold("#AL" + dottedCapitalI));
        assertTrue("Turkish capital dotted I", hides("#ali", "#AL" + dottedCapitalI + " yes"));
        assertTrue(hides("#AL" + dottedCapitalI, "#ali"));
        assertFalse(hides("#ali", "#AL" + dottedCapitalI + "m"));
        assertTrue("dotless i", hides("#ilik", "#" + dotlessI + "l" + dotlessI + "k"));
        String dotlessWithADot = "#al" + dotlessI + (char) 0x307;
        assertEquals("#ali", KeywordRules.fold(dotlessWithADot));
        assertTrue(hides("#ali", dotlessWithADot + " yes"));

        String finalSigma = "#" + (char) 0x3B8 + (char) 0x3B5 + (char) 0x3BF + (char) 0x3C2;
        String middleSigma = "#" + (char) 0x3B8 + (char) 0x3B5 + (char) 0x3BF + (char) 0x3C3;
        String capitals = "#" + (char) 0x398 + (char) 0x395 + (char) 0x39F + (char) 0x3A3;
        String insideACaption = capitals + "." + (char) 0x39A + (char) 0x391 + (char) 0x399;
        assertTrue(hides(finalSigma, insideACaption));
        assertTrue(hides(capitals, insideACaption));
        assertTrue(hides(capitals, finalSigma + " #fyp"));
        assertTrue("a list sigma meets a caption's final one", hides(middleSigma, finalSigma + " #fyp"));
    }

    @Test public void theKeycapEmojiIsNotATag() {
        String keycap = "#" + (char) 0xFE0F + (char) 0x20E3;
        // As a phrase it is a plain substring, so it hides only captions that carry that emoji.
        assertTrue(hides(keycap, "Top 5 " + keycap + "cats"));
        assertFalse(hides(keycap, "#cats"));
    }

    @Test public void aHindiOrThaiTagGoesOnThroughItsVowelSigns() {
        // Hindi "hind" and "hindi": the closing vowel sign is a spacing mark, not a letter.
        String hind = "#" + text(0x939, 0x93F, 0x902, 0x926);
        String hindi = hind + text(0x940);
        assertTrue(hides(hindi, "my " + hindi + " video"));
        assertTrue(hides(hind, hind + " " + hindi));
        assertFalse(hides(hind, "my " + hindi + " video"));
        // Thai "nam": a tone mark over the first letter.
        String nam = "#" + text(0xE19, 0xE49, 0xE33);
        assertTrue(hides(nam, nam));
        assertFalse(hides("#" + text(0xE19), nam));
    }

    private static String text(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }
}
