/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.widget.FrameLayout;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.profile.model.User;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;

/** Account facts from TikTok's own account model, in a fixed language and time zone. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class AccountFactsTest {
    private static final Locale LOCALE = Locale.US;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    private static String when(long seconds) {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, LOCALE);
        format.setTimeZone(UTC);
        return format.format(new Date(seconds * 1000L));
    }

    private static List<String> lines(User user) {
        List<String> lines = new ArrayList<>();
        for (AccountFacts.Fact fact : AccountFacts.of(user, LOCALE, UTC)) {
            lines.add(fact.label + "=" + fact.value);
        }
        return lines;
    }

    @Test public void aFullProfileGetsEveryFactInThePhonesWords() {
        User user = new User();
        user._createTime = 1_615_819_200L;
        user.registerTime = 1_500_000_000L;
        user.region = "de";
        user.accountRegion = "DE";
        user.language = "de";
        user.uniqueIdModifyTime = 1_700_000_000_000L;
        user.nickNameModifyTs = 1_690_000_000;
        user.isSecret = true;
        user.hasOpenFavorite = true;

        assertEquals(List.of(
                "Joined=" + when(1_615_819_200L),
                "Region=Germany (DE)",
                "Language=German (de)",
                "Username last changed=" + when(1_700_000_000L),
                "Display name last changed=" + when(1_690_000_000L),
                "Private account=Yes",
                "Liked videos=Public"
        ), lines(user));
    }

    @Test public void whatTheServerLeftOutSaysSo() {
        // secret and hasOpenFavorite are plain booleans, so left out they read false and say nothing.
        assertEquals(List.of(
                "Joined=Not sent",
                "Region=Not sent",
                "Language=Not sent",
                "Username last changed=Not sent",
                "Display name last changed=Not sent"
        ), lines(new User()));
    }

    @Test public void onlyATrueFlagGetsALine() {
        User user = new User();
        user.isSecret = true;
        List<String> lines = lines(user);
        assertTrue(lines.toString(), lines.contains("Private account=Yes"));
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Liked videos=")));

        user.isSecret = false;
        user.hasOpenFavorite = true;
        lines = lines(user);
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Private account=")));
        assertTrue(lines.toString(), lines.contains("Liked videos=Public"));
    }

    @Test public void theRegisterTimeStandsInAndADifferentAccountRegionGetsItsOwnLine() {
        User user = new User();
        user.registerTime = 1_500_000_000L;
        user.region = "US";
        user.accountRegion = "gb";
        List<String> lines = lines(user);
        assertEquals("Joined=" + when(1_500_000_000L), lines.get(0));
        assertEquals("Region=United States (US)", lines.get(1));
        assertEquals("Account region=United Kingdom (GB)", lines.get(2));
    }

    @Test public void anAccountWithNoJoinTimeTakesItFromItsId() {
        // TikTok's profile answer leaves the time out, but the ID's top 32 bits are 2020-01-22 00:50:08 UTC.
        assertEquals("Joined=" + when(1_579_654_208L), lines(account("6784563164518679557")).get(0));

        User sent = account("6784563164518679557");
        sent.registerTime = 1_500_000_000L;
        assertEquals("a time the server sent still wins", "Joined=" + when(1_500_000_000L), lines(sent).get(0));

        assertEquals("musical.ly's short IDs carry no time", "Joined=Not sent", lines(account("107955")).get(0));
        assertNull(AccountFacts.madeAt(null));
        assertEquals(Long.valueOf(1_579_000_000L), AccountFacts.madeAt("6781753360689441741"));
    }

    @Test public void theSheetCopiesAsOneLabelledLineEach() {
        List<AccountFacts.Fact> facts = List.of(
                new AccountFacts.Fact("Region", "Germany (DE)"),
                new AccountFacts.Fact("Private account", "Yes"));
        assertEquals("Region: Germany (DE)\nPrivate account: Yes", AccountFacts.format(facts));
    }

    @Test public void timesInMillisecondsAreReadAsSeconds() {
        assertNull(AccountFacts.seconds(null));
        assertNull(AccountFacts.seconds(0L));
        assertNull(AccountFacts.seconds(-5));
        assertNull(AccountFacts.seconds("1615819200"));
        assertEquals(Long.valueOf(1_615_819_200L), AccountFacts.seconds(1_615_819_200));
        assertEquals(Long.valueOf(1_615_819_200L), AccountFacts.seconds(1_615_819_200_123L));
    }

    /** Stands in for a RecyclerView: all the facts read is its adapter field, by name. */
    static final class Grid extends FrameLayout {
        Object mAdapter;

        Grid(Object adapter) {
            super(RuntimeEnvironment.getApplication());
            mAdapter = adapter;
        }
    }

    /** The profile grid's adapter keeps its videos in a field of its superclass. */
    static class BaseAdapter {
        final List<Object> mmItems = new ArrayList<>();
    }

    static final class GridAdapter extends BaseAdapter {
    }

    private static User account(String uid) {
        User user = new User();
        user.uid = uid;
        return user;
    }

    /** A video with its author under TikTok's field name; the stub video model has none. */
    static final class Video extends Aweme {
        final User author;

        Video(User author) {
            this.author = author;
        }
    }

    private static Aweme video(User author) {
        return new Video(author);
    }

    @Test public void theProfilesOwnVideosFillWhatItsModelLacks() {
        // 47.4.3's profile model has no join date, region or language (Login2, 2026-10-20).
        User profile = account("107955");
        profile.isSecret = true;
        User someoneElse = account("42");
        someoneElse.region = "US";
        User author = account("107955");
        author._createTime = 1_425_000_000L;
        author.region = "AU";
        author.language = "en";
        // A video's author carries the response's own time here, not a real change date.
        author.uniqueIdModifyTime = 1_792_501_129L;
        GridAdapter adapter = new GridAdapter();
        adapter.mmItems.add(video(someoneElse));
        adapter.mmItems.add("not a video");
        adapter.mmItems.add(video(author));
        FrameLayout screen = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout pager = new FrameLayout(RuntimeEnvironment.getApplication());
        pager.addView(new Grid(adapter));
        screen.addView(pager);

        List<Object> copies = AccountFacts.copiesOnScreen(screen, profile);
        assertEquals(List.of(author), copies);
        List<String> lines = new ArrayList<>();
        for (AccountFacts.Fact fact : AccountFacts.of(profile, copies, LOCALE, UTC)) {
            lines.add(fact.label + "=" + fact.value);
        }
        assertEquals(List.of(
                "Joined=" + when(1_425_000_000L),
                "Region=Australia (AU)",
                "Language=English (en)",
                "Username last changed=Not sent",
                "Display name last changed=Not sent",
                "Private account=Yes"
        ), lines);
    }

    @Test public void whatTheProfileHoldsWinsOverAVideosCopy() {
        User profile = account("7");
        profile.region = "de";
        profile.registerTime = 1_500_000_000L;
        User author = account("7");
        author.region = "AU";
        author._createTime = 1_400_000_000L;
        List<String> lines = new ArrayList<>();
        for (AccountFacts.Fact fact : AccountFacts.of(profile, List.of(author), LOCALE, UTC)) {
            lines.add(fact.label + "=" + fact.value);
        }
        // The real join time beats the profile's register time stand-in.
        assertEquals("Joined=" + when(1_400_000_000L), lines.get(0));
        assertEquals("Region=Germany (DE)", lines.get(1));
    }

    @Test public void withNothingBehindTheSheetThereAreNoCopies() {
        assertTrue(AccountFacts.copiesOnScreen(null, account("7")).isEmpty());
        assertTrue(AccountFacts.copiesOnScreen(
                new FrameLayout(RuntimeEnvironment.getApplication()), new User()).isEmpty());
    }

    @Test public void anUnknownCodeIsShownAsItCame() {
        assertEquals("ZZ", AccountFacts.country("zz", LOCALE));
        assertEquals("Not sent", AccountFacts.country(null, LOCALE));
    }
}
