package app.morphe.extension.tiktok.notinterested;

import static org.junit.Assert.*;

import app.morphe.extension.tiktok.feedfilter.AdvancedFeedRules;
import app.morphe.extension.tiktok.feedfilter.KeywordRules;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class AlsoFilterTest {
    @Test public void offersTheCreatorTheSoundAndThreeHashtagsInThatOrder() {
        List<AlsoFilter.Choice> choices = AlsoFilter.choices("cat.daily", "7001", "6900123",
                "original sound", "Monday mood #Cats #catsoftiktok #cats #fyp #viral", true);

        assertEquals(5, choices.size());
        assertChoice(choices.get(0), AlsoFilter.Kind.CREATOR, "@cat.daily", "@cat.daily");
        assertChoice(choices.get(1), AlsoFilter.Kind.SOUND_ID, "6900123", "original sound");
        // A tag repeated in another case is offered once, and the fourth different tag isn't.
        assertChoice(choices.get(2), AlsoFilter.Kind.HASHTAG, "#Cats", "#Cats");
        assertChoice(choices.get(3), AlsoFilter.Kind.HASHTAG, "#catsoftiktok", "#catsoftiktok");
        assertChoice(choices.get(4), AlsoFilter.Kind.HASHTAG, "#fyp", "#fyp");
    }

    @Test public void fallsBackToTheUserIdAndTheSoundName() {
        List<AlsoFilter.Choice> choices = AlsoFilter.choices(" ", "7001", null, "Song A", "", true);

        assertEquals(2, choices.size());
        assertChoice(choices.get(0), AlsoFilter.Kind.CREATOR, "7001", "7001");
        assertChoice(choices.get(1), AlsoFilter.Kind.SOUND_NAME, "Song A", "Song A");
    }

    @Test public void leavesTheSoundOutWhileBlockedSoundsAreShown() {
        List<AlsoFilter.Choice> choices = AlsoFilter.choices("@someone", null, "6900123", "Song", null, false);

        assertEquals(1, choices.size());
        assertChoice(choices.get(0), AlsoFilter.Kind.CREATOR, "@someone", "@someone");
    }

    @Test public void readsHashtagsTheWayACaptionWritesThem() {
        assertEquals(Arrays.asList("#café", "#東京", "#day_2"),
                AlsoFilter.hashtags("C# tips, issue#12 #café #東京 #day_2 ## #"));
        assertTrue(AlsoFilter.hashtags(null).isEmpty());
        assertTrue(AlsoFilter.hashtags("no tags here").isEmpty());
    }

    @Test public void eachPickLandsOnTheListTheFeedFilterReads() {
        AlsoFilter.Choice creator = new AlsoFilter.Choice(AlsoFilter.Kind.CREATOR, "@cat.daily", "@cat.daily");
        String creators = AlsoFilter.withChoice(creator, "news_bot");
        assertEquals("news_bot, @cat.daily", creators);
        assertTrue(AdvancedFeedRules.hasCreatorEntry(creators, "cat.daily"));
        assertTrue(AlsoFilter.listed(creator, creators));

        AlsoFilter.Choice sound = new AlsoFilter.Choice(AlsoFilter.Kind.SOUND_ID, "6900123", "Song");
        String sounds = AlsoFilter.withChoice(sound, "");
        assertEquals("6900123", sounds);
        assertTrue(SoundIdentity.containsEntry(sounds, "6900123"));
        assertTrue(AlsoFilter.listed(sound, sounds));

        AlsoFilter.Choice tag = new AlsoFilter.Choice(AlsoFilter.Kind.HASHTAG, "#Cats", "#Cats");
        String words = AlsoFilter.withChoice(tag, "\"spoiler\" & \"finale\"");
        assertEquals("\"spoiler\" & \"finale\", #Cats", words);
        assertTrue(KeywordRules.anyMatches(KeywordRules.parse(words), "Monday mood #cats"));
        assertFalse(KeywordRules.anyMatches(KeywordRules.parse(words), "Monday mood, no tags"));
        assertTrue(AlsoFilter.listed(tag, words));
    }

    @Test public void aChoiceAlreadyListedLeavesTheListAsItWas() {
        AlsoFilter.Choice tag = new AlsoFilter.Choice(AlsoFilter.Kind.HASHTAG, "#cats", "#cats");
        assertEquals("#CATS,  dogs", AlsoFilter.withChoice(tag, "#CATS,  dogs"));

        AlsoFilter.Choice sound = new AlsoFilter.Choice(AlsoFilter.Kind.SOUND_NAME, "Song A", "Song A");
        assertEquals("song a", AlsoFilter.withChoice(sound, "song a"));

        AlsoFilter.Choice creator = new AlsoFilter.Choice(AlsoFilter.Kind.CREATOR, "@Cat.Daily", "@Cat.Daily");
        assertTrue(AlsoFilter.listed(creator, "cat.daily"));
    }

    private static void assertChoice(AlsoFilter.Choice choice, AlsoFilter.Kind kind, String entry, String shown) {
        assertEquals(kind, choice.kind);
        assertEquals(entry, choice.entry);
        assertEquals(shown, choice.shown);
    }
}
