package app.morphe.extension.tiktok.notinterested;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ListAdapter;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.feedfilter.AdvancedFeedRules;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;
import app.morphe.extension.tiktok.feedfilter.KeywordRules;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AlsoFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean notInterestedEnabled;
    private boolean feedFilterEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        notInterestedEnabled = SettingsStatus.notInterestedEnabled;
        feedFilterEnabled = SettingsStatus.feedFilterEnabled;
    }

    @After public void tearDown() {
        Settings.NOT_INTERESTED_ALSO_FILTER.resetToDefault();
        Settings.HIDE_BLOCKED_SOUNDS.resetToDefault();
        Settings.BLOCKED_CREATORS.resetToDefault();
        Settings.BLOCKED_SOUND_IDS.resetToDefault();
        Settings.BLOCKED_SOUND_NAMES.resetToDefault();
        Settings.BLOCKED_CAPTION_WORDS.resetToDefault();
        SettingsStatus.notInterestedEnabled = notInterestedEnabled;
        SettingsStatus.feedFilterEnabled = feedFilterEnabled;
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(0));
    }

    @Test public void offersTheCreatorTheSoundAndEveryHashtagInThatOrder() {
        List<AlsoFilter.Choice> choices = AlsoFilter.choices("cat.daily", "7001", "6900123",
                "original sound", "Monday mood #Cats #catsoftiktok #cats #fyp #viral", true);

        assertEquals(6, choices.size());
        assertChoice(choices.get(0), AlsoFilter.Kind.CREATOR, "@cat.daily", "@cat.daily");
        assertChoice(choices.get(1), AlsoFilter.Kind.SOUND_ID, "6900123", "original sound");
        // A tag repeated in another case is offered once.
        assertChoice(choices.get(2), AlsoFilter.Kind.HASHTAG, "#Cats", "#Cats");
        assertChoice(choices.get(3), AlsoFilter.Kind.HASHTAG, "#catsoftiktok", "#catsoftiktok");
        assertChoice(choices.get(4), AlsoFilter.Kind.HASHTAG, "#fyp", "#fyp");
        assertChoice(choices.get(5), AlsoFilter.Kind.HASHTAG, "#viral", "#viral");
    }

    @Test public void fallsBackToTheUserIdAndTheSoundName() {
        List<AlsoFilter.Choice> choices = AlsoFilter.choices(" ", "7001", null, "Song A", "", true);

        assertEquals(2, choices.size());
        assertChoice(choices.get(0), AlsoFilter.Kind.CREATOR, "7001", "7001");
        assertChoice(choices.get(1), AlsoFilter.Kind.SOUND_NAME, "Song A", "Song A");
    }

    @Test public void aSoundNameWithACommaIsntOfferedSinceTheListWouldSplitIt() {
        List<AlsoFilter.Choice> choices = AlsoFilter.choices("@someone", null, null, "Hello, World", null, true);

        assertEquals(1, choices.size());
        assertEquals(AlsoFilter.Kind.CREATOR, choices.get(0).kind);
        assertEquals("by id it's one entry", AlsoFilter.Kind.SOUND_ID,
                AlsoFilter.choices(null, null, "6900123", "Hello, World", null, true).get(0).kind);
    }

    @Test public void leavesTheSoundOutWhileBlockedSoundsAreShown() {
        List<AlsoFilter.Choice> choices = AlsoFilter.choices("@someone", null, "6900123", "Song", null, false);

        assertEquals(1, choices.size());
        assertChoice(choices.get(0), AlsoFilter.Kind.CREATOR, "@someone", "@someone");
    }

    @Test public void readsHashtagsTheWayACaptionWritesThem() {
        String cafe = "#caf" + (char) 0xE9;
        String tokyo = "#" + (char) 0x6771 + (char) 0x4EAC;
        assertEquals(Arrays.asList(cafe, tokyo, "#day_2"),
                AlsoFilter.hashtags("C# tips, issue#12 " + cafe + " " + tokyo + " #day_2 ## #"));
        assertEquals("run together, each is its own tag", Arrays.asList("#fyp", "#cats", "#dog"),
                AlsoFilter.hashtags("#fyp#cats #Cats#dog issue#12#13"));
        assertTrue(AlsoFilter.hashtags(null).isEmpty());
        assertTrue(AlsoFilter.hashtags("no tags here").isEmpty());
    }

    @Test public void aTagRightAfterAnEmojiStillCounts() {
        String heart = "" + (char) 0x2764 + (char) 0xFE0F;
        String keycapOne = "1" + (char) 0xFE0F + (char) 0x20E3;
        String accentedE = "e" + (char) 0x301;
        assertEquals(Arrays.asList("#love", "#fyp"),
                AlsoFilter.hashtags(heart + "#love " + keycapOne + "#fyp " + accentedE + "#word"));
        String thumbsUpMedium = new String(Character.toChars(0x1F44D)) + new String(Character.toChars(0x1F3FD));
        String smileAsText = "" + (char) 0x263A + (char) 0xFE0E;
        assertEquals(Arrays.asList("#yes", "#smile"),
                AlsoFilter.hashtags(thumbsUpMedium + "#yes " + smileAsText + "#smile"));
    }

    @Test public void theKeycapNumberSignIsNotATagAndTakesNoSlot() {
        String keycap = "#" + (char) 0xFE0F + (char) 0x20E3;
        assertEquals(Arrays.asList("#a", "#b", "#c"), AlsoFilter.hashtags(keycap + " Top #a #b #c"));
        assertEquals(Arrays.asList("#love"), AlsoFilter.hashtags(keycap + "#love"));
        assertEquals(Arrays.asList("#love"), AlsoFilter.hashtags("#" + (char) 0xFE0F + "#love"));
    }

    @Test public void aTagIsOfferedOnceWhicheverWayItsCaseFolds() {
        String istanbul = "#" + (char) 0x130 + "stanbul";
        assertEquals(Arrays.asList(istanbul), AlsoFilter.hashtags(istanbul + " #istanbul"));
        String ilik = "#" + (char) 0x131 + "l" + (char) 0x131 + "k";
        assertEquals(Arrays.asList(ilik), AlsoFilter.hashtags(ilik + " #ILIK"));
    }

    @Test public void aGreekTagPickedFromACaptionHidesThatCaption() {
        // Capital sigma lower cases to its final form only at a word's end, so the tag on its own
        // and the tag inside this caption came out spelled two ways.
        String theos = "#" + (char) 0x398 + (char) 0x395 + (char) 0x39F + (char) 0x3A3;
        String caption = theos + "." + (char) 0x39A + (char) 0x391 + (char) 0x399;
        assertEquals(Arrays.asList(theos), AlsoFilter.hashtags(caption));
        AlsoFilter.Choice tag = new AlsoFilter.Choice(AlsoFilter.Kind.HASHTAG, theos, theos);
        String words = AlsoFilter.withChoice(tag, "");
        assertTrue(KeywordRules.anyMatches(KeywordRules.parse(words), caption));
        assertTrue(AlsoFilter.listed(tag, words));
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
        assertTrue(KeywordRules.anyMatches(KeywordRules.parse(words), "Monday mood #fyp#cats"));
        assertFalse(KeywordRules.anyMatches(KeywordRules.parse(words), "Monday mood, no tags"));
        assertFalse("only that tag", KeywordRules.anyMatches(KeywordRules.parse(words), "#catsoftiktok"));
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

    @Test public void aTagIsListedWhenTheListAlreadyHidesEveryCaptionWithIt() {
        AlsoFilter.Choice cats = new AlsoFilter.Choice(AlsoFilter.Kind.HASHTAG, "#cats", "#cats");
        assertTrue("the word it's made of", AlsoFilter.listed(cats, "cat"));
        assertTrue(AlsoFilter.listed(cats, "dogs, CATS"));
        assertFalse("#cat is another tag", AlsoFilter.listed(cats, "#cat"));
        assertFalse("#catsoftiktok is another tag", AlsoFilter.listed(cats, "#catsoftiktok"));
        assertFalse("a two-phrase rule hides only some", AlsoFilter.listed(cats, "\"#cats\" !& \"dog\""));
        assertEquals("\"#cats\" !& \"dog\", #cats", AlsoFilter.withChoice(cats, "\"#cats\" !& \"dog\""));
    }

    @Test public void threeHashtagsAreOfferedAfterTheListedOnesAreLeftOut() {
        Settings.BLOCKED_CAPTION_WORDS.save("#one, two");
        Settings.BLOCKED_CREATORS.save("cat.daily");
        List<AlsoFilter.Choice> offered = AlsoFilter.offered(AlsoFilter.choices("cat.daily", null, "6900123",
                "Song", "#one #two #three #four #five #six", true));

        assertEquals(4, offered.size());
        assertChoice(offered.get(0), AlsoFilter.Kind.SOUND_ID, "6900123", "Song");
        assertChoice(offered.get(1), AlsoFilter.Kind.HASHTAG, "#three", "#three");
        assertChoice(offered.get(2), AlsoFilter.Kind.HASHTAG, "#four", "#four");
        assertChoice(offered.get(3), AlsoFilter.Kind.HASHTAG, "#five", "#five");
    }

    @Test public void aTagThatOnlyARuleWithAnExceptionHidesIsOfferedAndCounted() {
        Settings.BLOCKED_CAPTION_WORDS.save("\"#one\" !& \"duet\"");
        List<AlsoFilter.Choice> offered = AlsoFilter.offered(AlsoFilter.choices(null, null, null, null,
                "#one #two #three #four", false));

        assertEquals(3, offered.size());
        assertChoice(offered.get(0), AlsoFilter.Kind.HASHTAG, "#one", "#one");
        assertChoice(offered.get(2), AlsoFilter.Kind.HASHTAG, "#three", "#three");
    }

    public static final class Caption extends Aweme {
        final String desc;
        Caption(String desc) { this.desc = desc; }
        public String getDesc() { return desc; }
    }

    @Test public void thePickedTagHidesFeedVideosWithThatWholeTag() {
        Settings.BLOCKED_CAPTION_WORDS.save("spoiler");
        AdvancedFeedRules.KeywordFilter feed = new AdvancedFeedRules.KeywordFilter();
        assertFalse(feed.getFiltered(new Caption("Monday #Cats")));
        assertNotNull(AlsoFilter.add(new AlsoFilter.Choice(AlsoFilter.Kind.HASHTAG, "#cats", "#cats")));
        idle();

        assertTrue(feed.getFiltered(new Caption("Monday #Cats")));
        assertTrue(feed.getFiltered(new Caption("#fyp#cats#dog")));
        assertFalse(feed.getFiltered(new Caption("#catsoftiktok")));
        assertFalse(feed.getFiltered(new Caption("#cat")));
    }

    @Test public void aPickGoesOnItsOwnListAndUndoTakesItOff() {
        Settings.BLOCKED_CAPTION_WORDS.save("spoiler");
        Runnable undo = AlsoFilter.add(new AlsoFilter.Choice(AlsoFilter.Kind.HASHTAG, "#cats", "#cats"));
        idle();
        assertNotNull(undo);
        assertEquals("spoiler, #cats", Settings.BLOCKED_CAPTION_WORDS.get());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("#cats"));
        undo.run();
        idle();
        assertEquals("spoiler", Settings.BLOCKED_CAPTION_WORDS.get());
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Showing"));

        assertNotNull(AlsoFilter.add(new AlsoFilter.Choice(AlsoFilter.Kind.CREATOR, "@cat.daily", "@cat.daily")));
        assertEquals("@cat.daily", Settings.BLOCKED_CREATORS.get());
        assertNotNull(AlsoFilter.add(new AlsoFilter.Choice(AlsoFilter.Kind.SOUND_ID, "6900123", "Song")));
        assertEquals("6900123", Settings.BLOCKED_SOUND_IDS.get());
        assertNotNull(AlsoFilter.add(new AlsoFilter.Choice(AlsoFilter.Kind.SOUND_NAME, "Song A", "Song A")));
        assertEquals("Song A", Settings.BLOCKED_SOUND_NAMES.get());
        assertEquals("spoiler", Settings.BLOCKED_CAPTION_WORDS.get());
    }

    @Test public void aFullListSaysSoAndKeepsWhatItHad() {
        StringBuilder full = new StringBuilder();
        for (int index = 0; index < FeedRuleLimits.MAX_ENTRIES; index++) {
            if (index > 0) full.append(',');
            full.append('w').append(index);
        }
        Settings.BLOCKED_CAPTION_WORDS.save(full.toString());
        assertNull(AlsoFilter.add(new AlsoFilter.Choice(AlsoFilter.Kind.HASHTAG, "#cats", "#cats")));
        idle();
        assertEquals("That list is full", ShadowToast.getTextOfLatestToast());
        assertEquals(full.toString(), Settings.BLOCKED_CAPTION_WORDS.get());
    }

    public static final class Person {
        public String getUniqueId() { return "cat.daily"; }
        public String getUid() { return "7001"; }
    }

    public static final class Song {
        public String getMid() { return "6900123"; }
        public String getMusicName() { return "original sound"; }
    }

    public static final class Video {
        final String caption;
        Video(String caption) { this.caption = caption; }
        public Person getAuthor() { return new Person(); }
        public Song getMusic() { return new Song(); }
        public String getDesc() { return caption; }
    }

    private static List<String> items(AlertDialog dialog) {
        ListAdapter list = dialog.getListView().getAdapter();
        List<String> out = new ArrayList<>();
        for (int index = 0; index < list.getCount(); index++) out.add(String.valueOf(list.getItem(index)));
        return out;
    }

    @Test public void theChooserOffersWhatsNotListedAndAPickSavesIt() {
        SettingsStatus.notInterestedEnabled = true;
        SettingsStatus.feedFilterEnabled = true;
        Settings.NOT_INTERESTED_ALSO_FILTER.save(true);
        Settings.BLOCKED_SOUND_IDS.save("6900123");
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            AlsoFilter.offer(controller.get(), new Video("Monday #cats #fyp"));
            idle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            List<String> labels = items(dialog);
            assertEquals(labels.toString(), 3, labels.size());
            assertTrue(labels.get(0), labels.get(0).startsWith("Videos from") && labels.get(0).contains("@cat.daily"));
            assertTrue(labels.get(1), labels.get(1).startsWith("Videos tagged") && labels.get(1).contains("#cats"));
            assertTrue(labels.get(2), labels.get(2).contains("#fyp"));

            Shadows.shadowOf(dialog).clickOnItem(1);
            idle();
            assertEquals("#cats", Settings.BLOCKED_CAPTION_WORDS.get());
            assertEquals("", Settings.BLOCKED_CREATORS.get());
        }
    }

    @Test public void nothingShowsWhileOffOrWhenEverythingIsListed() {
        SettingsStatus.notInterestedEnabled = true;
        SettingsStatus.feedFilterEnabled = true;
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertFalse("off by default", AlsoFilter.enabled());
            AlsoFilter.offer(controller.get(), new Video("#cats"));
            idle();
            assertNull("off by default", ShadowDialog.getLatestDialog());

            Settings.NOT_INTERESTED_ALSO_FILTER.save(true);
            Settings.BLOCKED_CREATORS.save("cat.daily");
            Settings.BLOCKED_SOUND_IDS.save("6900123");
            Settings.BLOCKED_CAPTION_WORDS.save("cats");
            assertTrue(AlsoFilter.enabled());
            // The same choices offer() reads off this video, so a quiet failure inside it can't
            // pass for "everything is listed".
            assertTrue(AlsoFilter.offered(AlsoFilter.choices("cat.daily", "7001", "6900123",
                    "original sound", "#cats", Settings.HIDE_BLOCKED_SOUNDS.get())).isEmpty());
            AlsoFilter.offer(controller.get(), new Video("#cats"));
            idle();
            assertNull(ShadowDialog.getLatestDialog());

            // And with something left to offer, the same call does show the chooser.
            Settings.BLOCKED_CAPTION_WORDS.save("");
            AlsoFilter.offer(controller.get(), new Video("#cats"));
            idle();
            assertNotNull(ShadowDialog.getLatestDialog());
        }
    }

    private static void assertChoice(AlsoFilter.Choice choice, AlsoFilter.Kind kind, String entry, String shown) {
        assertEquals(kind, choice.kind);
        assertEquals(entry, choice.entry);
        assertEquals(shown, choice.shown);
    }
}
