/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.publishdate;

import static org.junit.Assert.assertEquals;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Use this date format everywhere (#536): the date rows by the creator's name also write the
 * dates on comments, chat date headers and search result cards, and only with the switch on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DatesEverywhereTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** 2025-10-07 15:04:05 UTC. */
    private static final long POSTED = 1_759_849_445L;

    /** 2025-11-20 00:00 UTC, later the same year. */
    private static final long SAME_YEAR_NOW = 1_763_596_800_000L;

    /** 2026-03-01 00:00 UTC, the year after. */
    private static final long NEXT_YEAR_NOW = 1_772_323_200_000L;

    /** The narrow no-break space ICU writes before PM. */
    private static final char NARROW_SPACE = 0x202F;

    /** A search card's video. */
    public static final class Card extends Aweme {
        final long createTime;

        Card(long createTime) {
            this.createTime = createTime;
        }

        @Override public long getCreateTime() { return createTime; }
        @Override public String getAid() { return null; }
    }

    /** TikTok's chat date item, which tells its createdAt only in its description. */
    private static final class DateItem {
        final long createdAt;

        DateItem(long createdAt) {
            this.createdAt = createdAt;
        }

        @Override public String toString() {
            return "DateViewHolderData(uuid=date_separator_7558495812464170616, createdAt=" + createdAt
                    + ", formattedTimestamp=Oct 7 15:04, isLastUnread=false, bottomPadding=Default)";
        }
    }

    private Locale locale;
    private TimeZone zone;
    private boolean publishDateEnabled;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        publishDateEnabled = SettingsStatus.alwaysShowPublishDateEnabled;
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SettingsStatus.alwaysShowPublishDateEnabled = true;
        resetDateRows();
    }

    @After
    public void tearDown() {
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        SettingsStatus.alwaysShowPublishDateEnabled = publishDateEnabled;
        resetDateRows();
    }

    private static void resetDateRows() {
        Settings.PUBLISH_DATE_DAY_FIRST.resetToDefault();
        Settings.PUBLISH_DATE_MONTH_NAME.resetToDefault();
        Settings.PUBLISH_DATE_INSTEAD_OF_AGE.resetToDefault();
        Settings.PUBLISH_DATE_EVERYWHERE.resetToDefault();
    }

    private static String utc(String pattern, long millis) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    @Test
    public void theSwitchIsOffUntilPicked() {
        assertEquals(Boolean.FALSE, Settings.PUBLISH_DATE_EVERYWHERE.defaultValue);
    }

    @Test
    public void withTheSwitchOffEveryPlaceKeepsTikToksText() {
        Settings.PUBLISH_DATE_DAY_FIRST.save(true);
        Settings.PUBLISH_DATE_INSTEAD_OF_AGE.save(true);

        assertEquals("2025-10-07", AlwaysShowPublishDatePatch.commentTime("2025-10-07", POSTED));
        assertEquals("2025-10-07", AlwaysShowPublishDatePatch.searchCardDate("2025-10-07", new Card(POSTED)));
        assertEquals("Oct 7, 2025 15:04",
                AlwaysShowPublishDatePatch.chatDate("Oct 7, 2025 15:04", new DateItem(POSTED * 1000L)));
    }

    @Test
    public void withoutThePatchTheSwitchDoesNothing() {
        Settings.PUBLISH_DATE_DAY_FIRST.save(true);
        Settings.PUBLISH_DATE_EVERYWHERE.save(true);
        SettingsStatus.alwaysShowPublishDateEnabled = false;

        assertEquals("2025-10-07", AlwaysShowPublishDatePatch.commentTime("2025-10-07", POSTED));
        assertEquals("2025-10-07", AlwaysShowPublishDatePatch.searchCardDate("2025-10-07", new Card(POSTED)));
        assertEquals("Oct 7, 2025 15:04",
                AlwaysShowPublishDatePatch.chatDate("Oct 7, 2025 15:04", new DateItem(POSTED * 1000L)));
    }

    @Test
    public void aCommentsTimeFollowsTheDateRows() {
        Settings.PUBLISH_DATE_EVERYWHERE.save(true);
        Settings.PUBLISH_DATE_DAY_FIRST.save(true);
        assertEquals("07.10.25", AlwaysShowPublishDatePatch.commentTime("2025-10-07", POSTED));
        // A createTime in milliseconds is the same time.
        assertEquals("07.10.25", AlwaysShowPublishDatePatch.commentTime("2025-10-07", POSTED * 1000L));
        // An age stays an age until Date instead of age is on.
        assertEquals("2d ago", AlwaysShowPublishDatePatch.commentTime("2d ago", POSTED));

        Settings.PUBLISH_DATE_MONTH_NAME.save(true);
        assertEquals("7 Oct 2025", AlwaysShowPublishDatePatch.commentTime("2025-10-07", POSTED));

        Settings.PUBLISH_DATE_MONTH_NAME.save(false);
        Settings.PUBLISH_DATE_INSTEAD_OF_AGE.save(true);
        long now = System.currentTimeMillis();
        long twoDaysAgo = now / 1000L - 2 * 86_400L;
        // Early in January two days ago is last year, and the date carries it.
        boolean sameYear = utc("yyyy", now).equals(utc("yyyy", twoDaysAgo * 1000L));
        assertEquals(utc(sameYear ? "dd.MM" : "dd.MM.yy", twoDaysAgo * 1000L),
                AlwaysShowPublishDatePatch.commentTime("2d ago", twoDaysAgo));
        // No time on the comment, or one from before TikTok: TikTok's text.
        assertEquals("2d ago", AlwaysShowPublishDatePatch.commentTime("2d ago", 0));
        assertEquals("2d ago", AlwaysShowPublishDatePatch.commentTime("2d ago", 1_000L));
    }

    @Test
    public void aSearchCardsDateFollowsTheDateRows() {
        Settings.PUBLISH_DATE_EVERYWHERE.save(true);
        Settings.PUBLISH_DATE_MONTH_NAME.save(true);
        assertEquals("Oct 7, 2025", AlwaysShowPublishDatePatch.searchCardDate("2025-10-7", new Card(POSTED)));
        assertEquals("2d ago", AlwaysShowPublishDatePatch.searchCardDate("2d ago", new Card(POSTED)));
        // Not a video: TikTok's text.
        assertEquals("2025-10-7", AlwaysShowPublishDatePatch.searchCardDate("2025-10-7", "card"));
    }

    @Test
    public void aChatDateHeaderGetsTheDateAndKeepsTikToksTime() {
        Settings.PUBLISH_DATE_EVERYWHERE.save(true);
        Settings.PUBLISH_DATE_DAY_FIRST.save(true);
        long created = POSTED * 1000L;
        assertEquals("07.10 15:04", AlwaysShowPublishDatePatch.chatDateAt("Oct 7 15:04", created, SAME_YEAR_NOW));
        assertEquals("07.10 15:04", AlwaysShowPublishDatePatch.chatDateAt("Oct 7, 15:04", created, SAME_YEAR_NOW));
        assertEquals("07.10 3:04 PM", AlwaysShowPublishDatePatch.chatDateAt("Oct 7 3:04 PM", created, SAME_YEAR_NOW));
        String pm = "3:04" + NARROW_SPACE + "PM";
        assertEquals("07.10 " + pm, AlwaysShowPublishDatePatch.chatDateAt("Oct 7, " + pm, created, SAME_YEAR_NOW));
        // A header from another year carries it, as the creator's row does.
        assertEquals("07.10.25 15:04",
                AlwaysShowPublishDatePatch.chatDateAt("Oct 7, 2025 15:04", created, NEXT_YEAR_NOW));
        assertEquals("07.10.25 15:04",
                AlwaysShowPublishDatePatch.chatDateAt("2025/10/07 15:04", created, NEXT_YEAR_NOW));

        Settings.PUBLISH_DATE_MONTH_NAME.save(true);
        assertEquals("7 Oct 15:04", AlwaysShowPublishDatePatch.chatDateAt("10/7 15:04", created, SAME_YEAR_NOW));
    }

    @Test
    public void aRussianChatDateHeaderIsWrittenDayFirst() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"));
        Settings.PUBLISH_DATE_EVERYWHERE.save(true);
        Settings.PUBLISH_DATE_DAY_FIRST.save(true);
        assertEquals("07.10 15:04",
                AlwaysShowPublishDatePatch.chatDateAt("7 окт. 15:04", POSTED * 1000L, SAME_YEAR_NOW));
    }

    @Test
    public void todaysTimeAndWordsForADayNeedDateInsteadOfAge() {
        Settings.PUBLISH_DATE_EVERYWHERE.save(true);
        Settings.PUBLISH_DATE_DAY_FIRST.save(true);
        long created = POSTED * 1000L;
        // Today's header is only a time, and stays one.
        assertEquals("15:04", AlwaysShowPublishDatePatch.chatDateAt("15:04", created, SAME_YEAR_NOW));
        assertEquals("Yesterday 15:04", AlwaysShowPublishDatePatch.chatDateAt("Yesterday 15:04", created, SAME_YEAR_NOW));
        assertEquals("Tue 15:04", AlwaysShowPublishDatePatch.chatDateAt("Tue 15:04", created, SAME_YEAR_NOW));

        Settings.PUBLISH_DATE_INSTEAD_OF_AGE.save(true);
        assertEquals("15:04", AlwaysShowPublishDatePatch.chatDateAt("15:04", created, SAME_YEAR_NOW));
        assertEquals("07.10 15:04", AlwaysShowPublishDatePatch.chatDateAt("Yesterday 15:04", created, SAME_YEAR_NOW));
        assertEquals("07.10 3:04 PM", AlwaysShowPublishDatePatch.chatDateAt("Tue 3:04 PM", created, SAME_YEAR_NOW));

        // Date instead of age alone keeps a date TikTok wrote and dates a word in TikTok's shape.
        Settings.PUBLISH_DATE_DAY_FIRST.save(false);
        assertEquals("Oct 7 15:04", AlwaysShowPublishDatePatch.chatDateAt("Oct 7 15:04", created, SAME_YEAR_NOW));
        assertEquals("10-07 15:04", AlwaysShowPublishDatePatch.chatDateAt("Yesterday 15:04", created, SAME_YEAR_NOW));
    }

    @Test
    public void theChatItemsTimeComesFromItsDescription() {
        assertEquals(POSTED * 1000L, AlwaysShowPublishDatePatch.createdAtOf(new DateItem(POSTED * 1000L)));
        assertEquals(0L, AlwaysShowPublishDatePatch.createdAtOf("DateViewHolderData(uuid=x)"));
        assertEquals(0L, AlwaysShowPublishDatePatch.createdAtOf(null));
        // A uuid that happens to say createdAt= isn't read as the time.
        assertEquals(42L, AlwaysShowPublishDatePatch.createdAtOf(
                "DateViewHolderData(uuid=createdAt=7, createdAt=42, formattedTimestamp=x)"));
    }

    @Test
    public void theChatHookWritesTheHeaderFromTheItemsTime() {
        Settings.PUBLISH_DATE_EVERYWHERE.save(true);
        Settings.PUBLISH_DATE_DAY_FIRST.save(true);
        // POSTED is from 2025, so a header written now carries the year.
        assertEquals("07.10.25 15:04",
                AlwaysShowPublishDatePatch.chatDate("Oct 7, 2025 15:04", new DateItem(POSTED * 1000L)));
        // An item with no time: TikTok's header.
        assertEquals("Oct 7, 2025 15:04", AlwaysShowPublishDatePatch.chatDate("Oct 7, 2025 15:04", new Object()));
    }
}
