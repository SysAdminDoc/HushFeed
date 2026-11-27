/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.profile.model.User;
import com.ss.android.ugc.aweme.profile.model.UserHonor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;

/** Account facts from TikTok's own account model, in a fixed language and time zone. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class AccountFactsTest {
    private static final Locale LOCALE = Locale.US;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final String UNKNOWN = "Unknown (TikTok didn't send it)";
    private static final String SEC_UID = "MS4wLjABAAAAv7iSuuXDJGDvJkmH_vz1qkDZYo1apxgzaxdBSeIuPiM";

    private static UserHonor honor(int level) {
        UserHonor honor = new UserHonor();
        honor.level = level;
        return honor;
    }

    /** A picture's address the way TikTok's UrlModel holds it: a list of web addresses. */
    public static final class Address {
        public final List<String> urlList;

        Address(String... urls) {
            urlList = List.of(urls);
        }
    }

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
        user.userHonor = honor(12);
        user.userHonor.currentDiamond = 1_500;
        user.userHonor.nextDiamond = 2_000;
        user.userHonor.totalDiamond = 4_321;
        user.secUid = SEC_UID;
        user.awemeCount = 1_234;
        user.favoritingCount = 56_789;
        user.collectCount = 12;
        user.repostCount = 3;
        // An address that isn't https is passed over for the next one.
        user.avatarThumb = new Address("http://example.com/100.jpeg", "https://example.com/100.jpeg");
        user.avatarMedium = new Address("https://example.com/720.jpeg");
        user.avatarLarger = new Address("https://example.com/1080.jpeg", "https://example.com/1080.webp");
        user.coverUrls = List.of(new Address(), new Address("https://example.com/cover.jpeg"));

        assertEquals(List.of(
                "Joined=" + when(1_615_819_200L),
                "Region=Germany (DE)",
                "Language=German (de)",
                "Gifter level=12",
                "Gifter points=1,500",
                "Gifter points for the next level=2,000",
                "Gifter points in total=4,321",
                "Posts=1,234",
                "Liked videos=56,789 (Public)",
                "Favorites=12",
                "Reposts=3",
                "Username last changed=" + when(1_700_000_000L),
                "Display name last changed=" + when(1_690_000_000L),
                "Private account=Yes",
                "SecUID=" + SEC_UID,
                "Profile picture (small)=https://example.com/100.jpeg",
                "Profile picture (medium)=https://example.com/720.jpeg",
                "Profile picture (large)=https://example.com/1080.jpeg",
                "Profile cover=https://example.com/cover.jpeg"
        ), lines(user));
    }

    @Test public void gifterPointsShowOnlyWhenTheServerSentThem() {
        User user = new User();
        user.userHonor = honor(3);
        List<String> lines = lines(user);
        assertTrue(lines.toString(), lines.contains("Gifter level=3"));
        assertFalse("plain longs left out read 0 and say nothing",
                lines.stream().anyMatch(line -> line.startsWith("Gifter points")));

        user.userHonor.currentDiamond = 700;
        user.userHonor.totalDiamond = 700;
        lines = lines(user);
        assertTrue(lines.toString(), lines.contains("Gifter points=700"));
        assertFalse("a total the same as the points says nothing new",
                lines.stream().anyMatch(line -> line.startsWith("Gifter points in total=")));
    }

    @Test public void aPictureWithNoSecureAddressIsUnknownAndNoCoverGetsNoLine() {
        User user = new User();
        user.avatarThumb = new Address("http://example.com/100.jpeg");
        user.avatarMedium = new Address();
        user.coverUrls = List.of(new Address("http://example.com/cover.jpeg"));
        List<String> lines = lines(user);
        assertTrue(lines.toString(), lines.contains("Profile picture (small)=" + UNKNOWN));
        assertTrue(lines.toString(), lines.contains("Profile picture (medium)=" + UNKNOWN));
        assertTrue(lines.toString(), lines.contains("Profile picture (large)=" + UNKNOWN));
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Profile cover=")));
    }

    @Test public void tappingAValueOnTheSheetCopiesJustThatValue() {
        User user = account("6784563164518679557");
        user.uniqueId = "someone";
        user.secUid = SEC_UID;
        user.avatarLarger = new Address("https://example.com/1080.jpeg");
        ShadowToast.reset();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Utils.setActivity(controller.get());
            AccountFacts.show(user);
            // The dialog's show listener is posted.
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("the sheet opened", dialog);
            TextView message = dialog.findViewById(android.R.id.message);
            assertTrue("taps on the message reach its values",
                    message.getMovementMethod() instanceof LinkMovementMethod);
            assertTrue(message.getText() instanceof Spanned);
            Spanned text = (Spanned) message.getText();
            Map<String, ClickableSpan> byValue = new HashMap<>();
            for (ClickableSpan span : text.getSpans(0, text.length(), ClickableSpan.class)) {
                byValue.put(text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString(), span);
            }
            assertTrue(byValue.keySet().toString(), byValue.containsKey(SEC_UID));
            assertTrue(byValue.keySet().toString(), byValue.containsKey("https://example.com/1080.jpeg"));
            assertFalse("the label isn't part of what's copied", byValue.containsKey("SecUID: " + SEC_UID));

            byValue.get("https://example.com/1080.jpeg").onClick(message);
            shadowOf(Looper.getMainLooper()).idle();
            ClipboardManager clipboard = (ClipboardManager) RuntimeEnvironment.getApplication()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals("https://example.com/1080.jpeg",
                    clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            assertEquals("Copied to the clipboard", ShadowToast.getTextOfLatestToast());
            dialog.dismiss();
        } finally {
            Utils.setActivity(null);
        }
    }

    @Test public void aCountGetsALineOnlyWhenTheServerSentOne() {
        // The counts are plain ints, so a left-out one reads 0 like a real 0 and says nothing.
        User user = new User();
        user.favoritingCount = 7;
        user.repostCount = 0;
        List<String> lines = lines(user);
        assertTrue(lines.toString(), lines.contains("Liked videos=7"));
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Posts=")
                || line.startsWith("Favorites=") || line.startsWith("Reposts=")));

        user.favoritingCount = 0;
        user.collectCount = 2_000;
        lines = lines(user);
        assertTrue(lines.toString(), lines.contains("Favorites=2,000"));
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Liked videos=")));
    }

    @Test public void countsComeFromTheProfileNotAVideosCopy() {
        User profile = new User();
        User author = new User();
        author.awemeCount = 40;
        author.favoritingCount = 9;
        List<String> lines = new ArrayList<>();
        for (AccountFacts.Fact fact : AccountFacts.of(profile, List.of(author), LOCALE, UTC)) {
            lines.add(fact.label + "=" + fact.value);
        }
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Posts=")
                || line.startsWith("Liked videos=")));
    }

    @Test public void aCountIsInThePhonesDigits() {
        assertNull(AccountFacts.count(null, LOCALE));
        assertNull(AccountFacts.count(0, LOCALE));
        assertNull(AccountFacts.count(-1, LOCALE));
        assertNull("a number as text isn't a count", AccountFacts.count("12", LOCALE));
        assertEquals("1,234,567", AccountFacts.count(1_234_567, LOCALE));
        assertEquals("1.234.567", AccountFacts.count(1_234_567, Locale.GERMANY));
    }

    @Test public void whatTheServerLeftOutSaysItsUnknown() {
        // secret and hasOpenFavorite are plain booleans, so left out they read false and say nothing.
        assertEquals(List.of(
                "Joined=" + UNKNOWN,
                "Region=" + UNKNOWN,
                "Language=" + UNKNOWN,
                "Gifter level=" + UNKNOWN,
                "Username last changed=" + UNKNOWN,
                "Display name last changed=" + UNKNOWN,
                "SecUID=" + UNKNOWN,
                "Profile picture (small)=" + UNKNOWN,
                "Profile picture (medium)=" + UNKNOWN,
                "Profile picture (large)=" + UNKNOWN
        ), lines(new User()));
    }

    @Test public void aGifterLevelOfZeroIsWhatTheServerSent() {
        User user = new User();
        user.userHonor = honor(0);
        assertTrue(lines(user).toString(), lines(user).contains("Gifter level=0"));
        user.userHonor = null;
        assertTrue(lines(user).toString(), lines(user).contains("Gifter level=" + UNKNOWN));
        user.secUid = "   ";
        assertTrue("a blank SecUID is no SecUID", lines(user).contains("SecUID=" + UNKNOWN));
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

        assertEquals("musical.ly's short IDs carry no time", "Joined=" + UNKNOWN, lines(account("107955")).get(0));
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
        author.userHonor = honor(7);
        author.secUid = SEC_UID;
        // A video's author carries the response's own time here, not a real change date.
        author.uniqueIdModifyTime = 1_792_501_129L;
        author.avatarLarger = new Address("https://example.com/author-1080.jpeg");
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
                "Gifter level=7",
                "Username last changed=" + UNKNOWN,
                "Display name last changed=" + UNKNOWN,
                "Private account=Yes",
                "SecUID=" + SEC_UID,
                "Profile picture (small)=" + UNKNOWN,
                "Profile picture (medium)=" + UNKNOWN,
                "Profile picture (large)=https://example.com/author-1080.jpeg"
        ), lines);
    }

    @Test public void whatTheProfileHoldsWinsOverAVideosCopy() {
        User profile = account("7");
        profile.region = "de";
        profile.registerTime = 1_500_000_000L;
        profile.userHonor = honor(3);
        User author = account("7");
        author.region = "AU";
        author._createTime = 1_400_000_000L;
        author.userHonor = honor(9);
        List<String> lines = new ArrayList<>();
        for (AccountFacts.Fact fact : AccountFacts.of(profile, List.of(author), LOCALE, UTC)) {
            lines.add(fact.label + "=" + fact.value);
        }
        // The real join time beats the profile's register time stand-in.
        assertEquals("Joined=" + when(1_400_000_000L), lines.get(0));
        assertEquals("Region=Germany (DE)", lines.get(1));
        assertTrue(lines.toString(), lines.contains("Gifter level=3"));
    }

    @Test public void withNothingBehindTheSheetThereAreNoCopies() {
        assertTrue(AccountFacts.copiesOnScreen(null, account("7")).isEmpty());
        assertTrue(AccountFacts.copiesOnScreen(
                new FrameLayout(RuntimeEnvironment.getApplication()), new User()).isEmpty());
    }

    @Test public void anUnknownCodeIsShownAsItCame() {
        assertEquals("ZZ", AccountFacts.country("zz", LOCALE));
        assertEquals(UNKNOWN, AccountFacts.country(null, LOCALE));
        assertEquals(UNKNOWN, AccountFacts.language(null, LOCALE));
    }
}
