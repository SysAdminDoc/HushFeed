/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.lifecycle.ViewModel;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #233: the profile picture of a creator who is LIVE and has a Story. With the switch on the tap
 * opens the Story through the opener TikTok keeps in the picture's STORY entry, or in a STORY
 * candidate LIVE outranked when the entry is missing, and the next tap on the same picture is
 * left to TikTok, which opens the LIVE.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoryOverLiveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @org.junit.Before public void signedInReader() {
        SignedInUser.idForTests = "story-reader";
    }

    /** TikTok's enum of what a picture shows, renamed each build, with its constants' names. */
    enum Business { LIVE, STORY, PERSON }

    /** TikTok's story controller type, which the state declares its field as. */
    interface StoryController {}

    /** The controller, which is also the View.OnClickListener that opens the Story. */
    static final class Opener implements StoryController, View.OnClickListener {
        final List<View> taps = new ArrayList<>();

        @Override public void onClick(View view) {
            taps.add(view);
        }
    }

    /** The shared story state, holding the controller the way 0M6T does. */
    static class StoryState {
        private final String tag;
        private final StoryController controller;

        StoryState(String tag, StoryController controller) {
            this.tag = tag;
            this.controller = controller;
        }
    }

    /** An unseen story, with fields of its own before the shared one's. */
    static final class UnseenStory extends StoryState {
        private final Boolean flag = Boolean.TRUE;
        private final int count = 3;

        UnseenStory(StoryController controller) {
            super("has_unconsumed_story_", controller);
        }
    }

    private final Opener opener = new Opener();
    private final View avatar = new View(RuntimeEnvironment.getApplication());

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.OPEN_STORY_OVER_LIVE.resetToDefault();
        StoryOverLive.reset();
        SignedInUser.idForTests = null;
    }

    private Map<Business, Object> liveWithStory() {
        Map<Business, Object> business = new EnumMap<>(Business.class);
        business.put(Business.LIVE, new Object());
        business.put(Business.STORY, new UnseenStory(opener));
        return business;
    }

    @Test public void onlyALiveCreatorWithAStoryGetsTheStoryAndTheNextTapGoesToTheLive() {
        assertTrue(StoryOverLive.storyFirst(true, true, false));
        assertFalse("TikTok opens the Story itself without a LIVE", StoryOverLive.storyFirst(false, true, false));
        assertFalse("no Story to open", StoryOverLive.storyFirst(true, false, false));
        assertFalse("the tap after a Story opened goes to the LIVE", StoryOverLive.storyFirst(true, true, true));
    }

    @Test public void offTheTapIsLeftToTikTok() {
        assertFalse(StoryOverLive.openStory(liveWithStory(), avatar));
        assertTrue(opener.taps.isEmpty());
    }

    @Test public void onTheStoryOpensFromTheTappedPictureThenTheLiveThenTheStoryAgain() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        Map<Business, Object> business = liveWithStory();

        assertTrue("the first tap opens the Story", StoryOverLive.openStory(business, avatar));
        assertEquals(1, opener.taps.size());
        assertSame("with the view that was tapped", avatar, opener.taps.get(0));

        assertFalse("the second tap goes to the LIVE", StoryOverLive.openStory(business, avatar));
        assertEquals(1, opener.taps.size());

        assertTrue("and the third to the Story again", StoryOverLive.openStory(business, avatar));
        assertEquals(2, opener.taps.size());
    }

    @Test public void anotherPictureStartsWithTheStory() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        View other = new View(RuntimeEnvironment.getApplication());
        assertTrue(StoryOverLive.openStory(liveWithStory(), avatar));
        assertTrue("a different picture isn't the second tap", StoryOverLive.openStory(liveWithStory(), other));
        assertEquals(2, opener.taps.size());
    }

    @Test public void withoutALiveOrAStoryOpenerTikTokKeepsTheTap() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);

        Map<Business, Object> storyOnly = new EnumMap<>(Business.class);
        storyOnly.put(Business.STORY, new UnseenStory(opener));
        assertFalse("no LIVE, so TikTok opens the Story itself", StoryOverLive.openStory(storyOnly, avatar));

        Map<Business, Object> liveOnly = new EnumMap<>(Business.class);
        liveOnly.put(Business.LIVE, new Object());
        liveOnly.put(Business.PERSON, new UnseenStory(opener));
        assertFalse("a story state under another key doesn't count", StoryOverLive.openStory(liveOnly, avatar));

        Map<Business, Object> noOpener = new EnumMap<>(Business.class);
        noOpener.put(Business.LIVE, new Object());
        noOpener.put(Business.STORY, new StoryState("no_story", null));
        assertFalse("a state with no opener", StoryOverLive.openStory(noOpener, avatar));

        assertFalse("no map", StoryOverLive.openStory(null, avatar));
        assertFalse("no view", StoryOverLive.openStory(liveWithStory(), null));
        assertTrue(opener.taps.isEmpty());
    }

    @Test public void theOpenerIsFoundInTheSharedStateBehindTheStatesOwnFields() throws Exception {
        assertSame(opener, StoryOverLive.storyOpener(liveWithStory()));
        Map<Business, Object> empty = new EnumMap<>(Business.class);
        assertNull(StoryOverLive.storyOpener(empty));
        assertTrue(StoryOverLive.holds(liveWithStory(), "LIVE"));
        assertFalse(StoryOverLive.holds(empty, "LIVE"));
    }

    @Test public void pausedTheTapIsLeftToTikTok() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        PausedProcess.set(true);
        assertFalse(StoryOverLive.openStory(liveWithStory(), avatar));
        assertTrue(opener.taps.isEmpty());
    }

    @Test public void anOpenerThatThrowsLeavesTheTapToTikTok() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        Map<Business, Object> business = new EnumMap<>(Business.class);
        business.put(Business.LIVE, new Object());
        business.put(Business.STORY, new StoryState("broken", new Broken()));
        assertFalse(StoryOverLive.openStory(business, avatar));
    }

    /** AvatarComponentView as the Inbox hands it over: a View that answers getCurrentActiveBusiness. */
    public static final class Picture extends View {
        private final Map<Business, Object> business;

        Picture(Map<Business, Object> business) {
            super(RuntimeEnvironment.getApplication());
            this.business = business;
        }

        public Map<Business, Object> getCurrentActiveBusiness() {
            return business;
        }

        public String getUid() {
            return "story-creator";
        }
    }

    /** A chat row keeps its LIVE in its own field, so the picture's map only has the Story. */
    private Map<Business, Object> chatStory() {
        Map<Business, Object> business = new EnumMap<>(Business.class);
        business.put(Business.STORY, new UnseenStory(opener));
        return business;
    }

    @Test public void offTheInboxTapIsLeftToTikTok() {
        assertFalse(StoryOverLive.openChatStory(new Picture(chatStory())));
        assertTrue(opener.taps.isEmpty());
    }

    @Test public void onTheInboxPictureOpensTheStoryThenTheLiveThenTheStoryAgain() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        Picture picture = new Picture(chatStory());

        assertTrue("the first tap opens the Story", StoryOverLive.openChatStory(picture));
        assertEquals(1, opener.taps.size());
        assertSame("with the picture that was tapped", picture, opener.taps.get(0));

        assertFalse("the second tap goes to the LIVE", StoryOverLive.openChatStory(picture));
        assertEquals(1, opener.taps.size());

        assertTrue("and the third to the Story again", StoryOverLive.openChatStory(picture));
        assertEquals(2, opener.taps.size());
    }

    @Test public void anInboxPictureWithoutAStoryOrAMapGoesToTheLive() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        Map<Business, Object> noStory = new EnumMap<>(Business.class);
        noStory.put(Business.PERSON, new UnseenStory(opener));
        assertFalse("a story state under another key doesn't count", StoryOverLive.openChatStory(new Picture(noStory)));
        assertFalse("no map", StoryOverLive.openChatStory(new Picture(null)));
        assertFalse("a plain view has no map to read", StoryOverLive.openChatStory(avatar));
        assertFalse("no view", StoryOverLive.openChatStory(null));
        assertTrue(opener.taps.isEmpty());
    }

    static final class Broken implements StoryController, View.OnClickListener {
        @Override public void onClick(View view) {
            throw new IllegalStateException("the story page is gone");
        }
    }

    /** One contender for a part of the picture, like 0M8q: its business, state and ring. */
    static final class Candidate {
        private final Business business;
        private final Object state;
        private final Object ring = new Object();
        private final int rank;
        private final String key = "avatar_node";

        Candidate(Business business, Object state, int rank) {
            this.business = business;
            this.state = state;
            this.rank = rank;
        }
    }

    /** The state machine, like 0M14: ranks, winners, and every candidate listed per part. */
    static final class Machine {
        private final ConcurrentHashMap<Object, Integer> ranks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Object, Candidate> winners = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Object, List<Candidate>> candidates = new ConcurrentHashMap<>();
        private boolean changed;

        void add(Object part, Candidate... contenders) {
            List<Candidate> list = new ArrayList<>(Arrays.asList(contenders));
            candidates.put(part, list);
            winners.put(part, list.get(0));
            ranks.put(part, list.get(0).rank);
        }
    }

    /** AvatarControllerVM: its maps come first, then the state machine's getter. */
    static final class ControllerVM extends ViewModel {
        private final Machine machine = new Machine();

        Map<Object, Object> rings() {
            return new HashMap<>();
        }

        Machine machine() {
            return machine;
        }
    }

    /** AvatarDelegate, with getters around the one that returns its view model. */
    static final class Delegate {
        private final ControllerVM model = new ControllerVM();

        HashMap<Object, Object> active() {
            return new HashMap<>();
        }

        String uid() {
            return "story-creator";
        }

        ControllerVM model() {
            return model;
        }
    }

    /** AvatarComponentView, whose delegate getter is private the way TikTok's is. */
    public static final class RankedPicture extends View {
        private final Map<Business, Object> active;
        private final Delegate delegate = new Delegate();

        RankedPicture(Map<Business, Object> active) {
            super(RuntimeEnvironment.getApplication());
            this.active = active;
        }

        private Delegate getAvatarDelegate() {
            return delegate;
        }

        public Map<Business, Object> getCurrentActiveBusiness() {
            return active;
        }

        public String getUid() {
            return "story-creator";
        }

        Machine machine() {
            return delegate.model.machine;
        }
    }

    /** A LIVE creator's picture: LIVE won the part the Story wanted, so the map has no STORY. */
    private RankedPicture liveOutranksStory() {
        Map<Business, Object> active = new EnumMap<>(Business.class);
        active.put(Business.LIVE, new Object());
        RankedPicture picture = new RankedPicture(active);
        picture.machine().add("ring", new Candidate(Business.LIVE, new Object(), 1),
                new Candidate(Business.STORY, new UnseenStory(opener), 3));
        picture.machine().add("badge", new Candidate(Business.PERSON, new Object(), 2));
        return picture;
    }

    @Test public void theStoryLiveOutrankedIsOpenedFromTikToksCandidates() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        RankedPicture picture = liveOutranksStory();

        assertTrue("the first tap opens the Story", StoryOverLive.openStory(picture.getCurrentActiveBusiness(), picture));
        assertEquals(1, opener.taps.size());
        assertSame("with the picture that was tapped", picture, opener.taps.get(0));

        assertFalse("the second tap goes to the LIVE", StoryOverLive.openStory(picture.getCurrentActiveBusiness(), picture));
        assertTrue("and the third to the Story again", StoryOverLive.openStory(picture.getCurrentActiveBusiness(), picture));
        assertEquals(2, opener.taps.size());
    }

    @Test public void theInboxStoryLiveOutrankedIsOpenedFromTikToksCandidates() {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        RankedPicture picture = liveOutranksStory();
        assertTrue(StoryOverLive.openChatStory(picture));
        assertEquals(1, opener.taps.size());
        assertSame(picture, opener.taps.get(0));
    }

    @Test public void theCandidatesAreLeftAloneWhenOffOrNotLive() {
        RankedPicture picture = liveOutranksStory();
        assertFalse("off", StoryOverLive.openStory(picture.getCurrentActiveBusiness(), picture));

        Settings.OPEN_STORY_OVER_LIVE.save(true);
        Map<Business, Object> notLive = new EnumMap<>(Business.class);
        notLive.put(Business.PERSON, new Object());
        assertFalse("no LIVE, so TikTok's own tap stands", StoryOverLive.openStory(notLive, picture));
        assertTrue(opener.taps.isEmpty());
    }

    @Test public void withoutAStoryCandidateOrItsOpenerTheLiveOpens() throws Exception {
        Settings.OPEN_STORY_OVER_LIVE.save(true);
        Map<Business, Object> active = new EnumMap<>(Business.class);
        active.put(Business.LIVE, new Object());

        RankedPicture noStory = new RankedPicture(active);
        noStory.machine().add("ring", new Candidate(Business.LIVE, new Object(), 1),
                new Candidate(Business.PERSON, new UnseenStory(opener), 3));
        assertNull("a story state under another business doesn't count", StoryOverLive.rankedOutStoryOpener(noStory));
        assertFalse(StoryOverLive.openStory(active, noStory));

        RankedPicture noOpener = new RankedPicture(active);
        noOpener.machine().add("ring", new Candidate(Business.LIVE, new Object(), 1),
                new Candidate(Business.STORY, new StoryState("no_story", null), 3));
        assertNull("a Story with no opener", StoryOverLive.rankedOutStoryOpener(noOpener));

        assertNull("no candidates yet", StoryOverLive.rankedOutStoryOpener(new RankedPicture(active)));
        assertNull("a picture with no delegate", StoryOverLive.rankedOutStoryOpener(avatar));
        assertTrue(opener.taps.isEmpty());
    }
}
