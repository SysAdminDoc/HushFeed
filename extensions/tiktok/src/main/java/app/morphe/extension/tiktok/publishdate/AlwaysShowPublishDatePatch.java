/*
 * Thanks to lyyako for the original implementation and help with this patch.
 *
 * Originally adapted for TikTok 43.8.3; ported to TikTok 46.2.3:
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.publishdate;

import android.content.Context;
import android.text.format.DateUtils;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.SavedVideoMark;
import app.morphe.extension.tiktok.download.StoryShareSave;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Always show publish date, and its two options: the time to the second, with the time zone, on
 * the creator's row, and each video's date on a profile's grid.
 *
 * <p>Both read the time TikTok sent with the video (Aweme.createTime, Unix seconds). A video
 * without one still carries it in its ID, whose top 32 bits are the posting time in Unix seconds,
 * so that's where it comes from then. Everything is written the way the phone's language and time
 * zone write a date.
 */
public final class AlwaysShowPublishDatePatch {
    /** A post ID's top 32 bits are its posting time in Unix seconds. */
    private static final int ID_TIME_SHIFT = 32;

    /** 2016-01-01 UTC. Nothing on TikTok was posted before it, so an earlier time isn't one. */
    static final long EARLIEST_POST_SECONDS = 1_451_606_400L;

    /** A phone clock a day behind still sees every post as already made. */
    private static final long CLOCK_SLACK_SECONDS = 86_400L;

    /** Above this a createTime is in milliseconds, the way some older models carried it. */
    private static final long MILLISECONDS_FROM = 100_000_000_000L;

    private AlwaysShowPublishDatePatch() {
    }

    public static boolean showPostTimeForMainFeeds(boolean original) {
        return Settings.ALWAYS_SHOW_PUBLISH_DATE.get() ? false : original;
    }

    /**
     * Called where the creator's row has its post time text ready ("2d ago", "10-7"). Returns
     * that text, or the full date and time to the second with the time zone while the switch is on
     * (a story's header gets {@link #storyTime}), with Mark saved videos' check before it for a
     * video saved here.
     */
    public static String postTime(String original, Object item) {
        return SavedVideoMark.rowText(exactOrOriginal(original, item), item);
    }

    private static String exactOrOriginal(String original, Object item) {
        if (!Settings.PUBLISH_DATE_EXACT_TIME.get()) {
            boolean dayFirst = Settings.PUBLISH_DATE_DAY_FIRST.get();
            if (Settings.PUBLISH_DATE_INSTEAD_OF_AGE.get()) {
                String date = insteadOfAge(original, item, Settings.PUBLISH_DATE_MONTH_NAME.get(), dayFirst);
                if (date != null) return date;
            }
            if (Settings.PUBLISH_DATE_MONTH_NAME.get()) return monthName(original, item, dayFirst);
            return dayFirst ? dayFirst(original, item) : original;
        }
        try {
            long now = System.currentTimeMillis();
            long seconds = postedAt(item, now);
            if (seconds <= 0) return original;
            Context context = Utils.getContext();
            if (context != null && StoryShareSave.isStory(item)) {
                return storyTime(context, seconds, now);
            }
            // Without a context the language's own clock stands.
            boolean hour24 = context != null
                    ? android.text.format.DateFormat.is24HourFormat(context)
                    : ((SimpleDateFormat) DateFormat.getTimeInstance(DateFormat.LONG)).toPattern().indexOf('H') >= 0;
            return exactTime(seconds, Locale.getDefault(), TimeZone.getDefault(), hour24,
                    Settings.PUBLISH_DATE_TIME_ZONE.get(), Settings.PUBLISH_DATE_SHORT_DATE.get(),
                    Settings.PUBLISH_DATE_DAY_FIRST.get());
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write the exact posting time", ex);
            return original;
        }
    }

    /**
     * TikTok's date written day first with dots (#438): "09-29" becomes "29.09", and "2024-09-29"
     * from another year becomes "29.09.24". The day and month come from the video's own time,
     * and the year only where TikTok wrote one. Anything that isn't all numbers, like "2d ago" or
     * a date in words, is TikTok's to keep.
     */
    static String dayFirst(String original, Object item) {
        try {
            int groups = numberGroups(original);
            if (groups < 2 || groups > 3) return original;
            long seconds = postedAt(item, System.currentTimeMillis());
            if (seconds <= 0) return original;
            Calendar posted = Calendar.getInstance();
            posted.setTimeInMillis(seconds * 1000L);
            String day = String.format(Locale.ROOT, "%02d.%02d",
                    posted.get(Calendar.DAY_OF_MONTH), posted.get(Calendar.MONTH) + 1);
            return groups == 2 ? day : day + String.format(Locale.ROOT, ".%02d", posted.get(Calendar.YEAR) % 100);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write the date day first", ex);
            return original;
        }
    }

    /**
     * TikTok's date with the month as a word (#461): "10-12" becomes "Oct 12", or "12 Oct" day
     * first, in the phone's language. The year stays only where TikTok wrote one, and anything
     * that isn't all numbers is TikTok's to keep, as in {@link #dayFirst}.
     */
    static String monthName(String original, Object item, boolean dayFirst) {
        try {
            int groups = numberGroups(original);
            if (groups < 2 || groups > 3) return original;
            long seconds = postedAt(item, System.currentTimeMillis());
            if (seconds <= 0) return original;
            String pattern = groups == 2
                    ? (dayFirst ? "d MMM" : "MMM d")
                    : (dayFirst ? "d MMM y" : "MMM d, y");
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(seconds * 1000L));
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write the month as a word", ex);
            return original;
        }
    }

    /**
     * Date instead of age (#461): TikTok writes a recent video's time as an age ("2d ago", "1w
     * ago", "Yesterday") before this hook sees it, so the two date options had nothing to rewrite
     * there. With the switch on, that age becomes the day the video was posted, in the shape the
     * options pick ({@link #fixedDate}). A date TikTok already wrote goes on to the options as
     * before, and a story keeps its hours: its header is about the time of day.
     */
    private static String insteadOfAge(String original, Object item, boolean monthName, boolean dayFirst) {
        if (original == null || original.trim().isEmpty()) return null;
        try {
            long now = System.currentTimeMillis();
            long seconds = postedAt(item, now);
            if (seconds <= 0 || StoryShareSave.isStory(item)) return null;
            Locale locale = Locale.getDefault();
            TimeZone zone = TimeZone.getDefault();
            if (writesADate(original, seconds, locale, zone)) return null;
            String date = fixedDate(seconds, now, monthName, dayFirst, locale, zone);
            Logger.printDebug(() -> "Publish date: " + date + " in place of TikTok's " + original);
            return date;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write the date in place of the age", ex);
            return null;
        }
    }

    /**
     * The day a video posted at {@code seconds} was posted, in {@code zone}: "10-12" the way
     * TikTok writes an older date, "12.10" day first, "Oct 12" or "12 Oct" with the month as a
     * word. A date from another year than {@code nowMillis}'s carries it: "2025-10-12",
     * "12.10.25", "Oct 12, 2025", "12 Oct 2025", the shapes the options give TikTok's own dates.
     */
    static String fixedDate(long seconds, long nowMillis, boolean monthName, boolean dayFirst, Locale locale,
            TimeZone zone) {
        long millis = seconds * 1000L;
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(millis);
        int year = calendar.get(Calendar.YEAR);
        calendar.setTimeInMillis(nowMillis);
        boolean sameYear = year == calendar.get(Calendar.YEAR);
        SimpleDateFormat format;
        if (monthName) {
            String pattern = sameYear ? (dayFirst ? "d MMM" : "MMM d") : (dayFirst ? "d MMM y" : "MMM d, y");
            format = new SimpleDateFormat(pattern, locale);
        } else {
            String pattern = sameYear ? (dayFirst ? "dd.MM" : "MM-dd") : (dayFirst ? "dd.MM.yy" : "yyyy-MM-dd");
            format = new SimpleDateFormat(pattern, Locale.ROOT);
        }
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    /**
     * Whether TikTok's text is already a date rather than an age: all numbers ("10-12",
     * "2025-10-12"), or the posted day's number with its month's name ("Oct 12", "12 oct.",
     * "10月12日") in the phone's language.
     */
    private static boolean writesADate(String text, long seconds, Locale locale, TimeZone zone) {
        int groups = numberGroups(text);
        if (groups >= 2 && groups <= 3) return true;
        Calendar posted = Calendar.getInstance(zone);
        posted.setTimeInMillis(seconds * 1000L);
        if (!hasNumber(text, posted.get(Calendar.DAY_OF_MONTH))) return false;
        int month = posted.get(Calendar.MONTH);
        DateFormatSymbols symbols = DateFormatSymbols.getInstance(locale);
        String lower = text.toLowerCase(locale);
        for (String[] names : new String[][]{symbols.getShortMonths(), symbols.getMonths()}) {
            if (month >= names.length || names[month] == null) continue;
            String name = names[month].toLowerCase(locale);
            while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
            if (!name.isEmpty() && lower.contains(name)) return true;
        }
        return false;
    }

    /** Whether {@code text} holds {@code number} as a run of digits of its own. */
    private static boolean hasNumber(String text, int number) {
        int i = 0;
        while (i < text.length()) {
            if (!Character.isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int value = 0;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                value = Math.min(value * 10 + Character.digit(text.charAt(i), 10), 1_000_000);
                i++;
            }
            if (value == number) return true;
        }
        return false;
    }

    /** How many runs of digits a date like "09-29" holds, or 0 when anything but digits and - . / is in it. */
    private static int numberGroups(String text) {
        if (text == null) return 0;
        int groups = 0;
        boolean inDigits = false;
        String trimmed = text.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isDigit(c)) {
                if (!inDigits) groups++;
                inDigits = true;
            } else if (c == '-' || c == '.' || c == '/') {
                inDigits = false;
            } else {
                return 0;
            }
        }
        return groups;
    }

    /**
     * The date a profile grid cell shows under its view count ("Oct 7", or "Oct 7, 2025" from
     * another year), or null when the switch is off or the video has no time.
     */
    public static String gridDate(Object item) {
        if (!SettingsStatus.alwaysShowPublishDateEnabled || !Settings.PUBLISH_DATE_ON_GRID.get()) {
            return null;
        }
        try {
            long now = System.currentTimeMillis();
            long seconds = postedAt(item, now);
            return seconds > 0 ? shortDate(Utils.getContext(), seconds, now) : null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write a grid cell's date", ex);
            return null;
        }
    }

    /** When the item was posted, in Unix seconds: its createTime, else what its ID says, else 0. */
    public static long postedAt(Object item, long nowMillis) {
        if (!(item instanceof Aweme)) {
            return 0;
        }
        Aweme aweme = (Aweme) item;
        long created;
        try {
            created = aweme.getCreateTime();
        } catch (Throwable ex) {
            // A build without the getter still has the ID.
            created = 0;
        }
        if (created >= MILLISECONDS_FROM) {
            created /= 1000;
        }
        if (plausible(created, nowMillis)) {
            return created;
        }
        String id;
        try {
            id = aweme.getAid();
        } catch (Throwable ex) {
            return 0;
        }
        return fromId(id, nowMillis);
    }

    /**
     * The posting time a post ID carries, in Unix seconds, or 0 when it isn't one. An account's
     * ID carries the time it was made the same way, except musical.ly's short ones, which give 0.
     */
    public static long fromId(String id, long nowMillis) {
        if (id == null) {
            return 0;
        }
        long value;
        try {
            value = Long.parseLong(id.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
        long seconds = value >>> ID_TIME_SHIFT;
        return plausible(seconds, nowMillis) ? seconds : 0;
    }

    private static boolean plausible(long seconds, long nowMillis) {
        return seconds >= EARLIEST_POST_SECONDS && seconds <= nowMillis / 1000 + CLOCK_SLACK_SECONDS;
    }

    /**
     * The date with the time to the second and the zone, the way the phone's language writes them:
     * "Oct 7, 2025, 3:04:05 PM EDT" in American English, "07.10.2025, 15:04:05 MESZ" in German.
     * The hours follow the phone's own 12 or 24 hour setting, which java.text never reads.
     */
    static String exactTime(long seconds, Locale locale, TimeZone zone, boolean hour24) {
        return exactTime(seconds, locale, zone, hour24, true, false);
    }

    /**
     * The same, with the zone left off or the date written short, both the language's own way
     * rather than cut out of the long text (Discussion #147). Without the zone the time is the
     * language's medium one, which every locale writes with the seconds and without a zone; the
     * short date is the language's short one ("10/7/25", "07.10.25").
     */
    static String exactTime(long seconds, Locale locale, TimeZone zone, boolean hour24, boolean showZone,
            boolean shortDate) {
        return exactTime(seconds, locale, zone, hour24, showZone, shortDate, false);
    }

    /**
     * The same, with Day before month (#438) writing the date day first in front of the
     * language's own time: "07.10.25" with the short date, "7 Oct 2025" without. The switch sits
     * under Short date, so a row showing the time to the second has to follow it too.
     */
    static String exactTime(long seconds, Locale locale, TimeZone zone, boolean hour24, boolean showZone,
            boolean shortDate, boolean dayFirst) {
        if (dayFirst) {
            Date when = new Date(seconds * 1000L);
            SimpleDateFormat date = new SimpleDateFormat(shortDate ? "dd.MM.yy" : "d MMM y", locale);
            date.setTimeZone(zone);
            DateFormat time = DateFormat.getTimeInstance(showZone ? DateFormat.LONG : DateFormat.MEDIUM, locale);
            if (time instanceof SimpleDateFormat) {
                SimpleDateFormat simple = (SimpleDateFormat) time;
                String pattern = simple.toPattern();
                String clock = hourStyle(pattern, hour24);
                if (!clock.equals(pattern)) simple.applyPattern(clock);
            }
            time.setTimeZone(zone);
            String text = date.format(when) + " " + time.format(when);
            return showZone ? compactOffset(text) : text;
        }
        // A new instance per call: DateFormat isn't thread safe.
        DateFormat format = DateFormat.getDateTimeInstance(shortDate ? DateFormat.SHORT : DateFormat.MEDIUM,
                showZone ? DateFormat.LONG : DateFormat.MEDIUM, locale);
        if (format instanceof SimpleDateFormat) {
            SimpleDateFormat simple = (SimpleDateFormat) format;
            String pattern = simple.toPattern();
            String clock = hourStyle(pattern, hour24);
            if (!clock.equals(pattern)) simple.applyPattern(clock);
        }
        format.setTimeZone(zone);
        String text = format.format(new Date(seconds * 1000L));
        return showZone ? compactOffset(text) : text;
    }

    /** A zone written as a padded offset ("GMT-04:00", "UTC+0530"). */
    private static final Pattern PADDED_OFFSET =
            Pattern.compile("(GMT|UTC)([+\\-\\x{2212}])0?(\\d{1,2}):?(\\d{2})");

    /**
     * The zone's offset written short ("GMT-4", "GMT+5:30"). A language with no short name for
     * the phone's zone writes the padded offset, and in Italian at 360dp that pushed the creator's
     * name out of the row and cut the time (Login2, 47.4.3, 2026-10-20). Names ("EDT", "MESZ")
     * are left alone.
     */
    static String compactOffset(String text) {
        Matcher offset = PADDED_OFFSET.matcher(text);
        if (!offset.find()) return text;
        StringBuffer out = new StringBuffer(text.length());
        do {
            String minutes = offset.group(4);
            offset.appendReplacement(out, Matcher.quoteReplacement(offset.group(1) + offset.group(2)
                    + offset.group(3) + ("00".equals(minutes) ? "" : ":" + minutes)));
        } while (offset.find());
        offset.appendTail(out);
        return out.toString();
    }

    /**
     * A date pattern with its hours written on a 24 hour clock ("HH", no AM/PM) or a 12 hour one
     * ("h" with "a" after the seconds). Quoted text is left alone, and a pattern already on the
     * asked clock comes back as it was.
     */
    static String hourStyle(String pattern, boolean hour24) {
        boolean twelve = false, twentyFour = false, marker = false, quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') quoted = !quoted;
            else if (!quoted && (c == 'h' || c == 'K')) twelve = true;
            else if (!quoted && (c == 'H' || c == 'k')) twentyFour = true;
            else if (!quoted && isDayPeriod(c)) marker = true;
        }
        if (hour24 ? !twelve && !marker : !twentyFour) return pattern;
        StringBuilder out = new StringBuilder(pattern.length() + 2);
        int afterTime = -1;
        quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                out.append(c);
                continue;
            }
            if (quoted) {
                out.append(c);
                continue;
            }
            boolean hour = isHour(c);
            if (hour) {
                while (i + 1 < pattern.length() && pattern.charAt(i + 1) == c) i++;
                out.append(hour24 ? "HH" : "h");
            } else if (isDayPeriod(c)) {
                if (!hour24) out.append(c);
                // A marker joined to its hour ("d日 ah:mm") goes alone: the space before it parts
                // the date from the time. Otherwise the space that set it apart goes with it, on
                // whichever side it was.
                else if (i + 1 < pattern.length() && isHour(pattern.charAt(i + 1))) continue;
                else if (trimSpace(out) == 0 && i + 1 < pattern.length() && isSpace(pattern.charAt(i + 1))) i++;
            } else {
                out.append(c);
            }
            if (hour || c == 'm' || c == 's') afterTime = out.length();
        }
        if (!hour24 && !marker && afterTime >= 0) out.insert(afterTime, " a");
        return out.toString();
    }

    private static boolean isHour(char c) {
        return c == 'h' || c == 'K' || c == 'H' || c == 'k';
    }

    /** AM/PM, and the noon and midnight ("b") and flexible ("B", "下午") day periods. */
    private static boolean isDayPeriod(char c) {
        return c == 'a' || c == 'b' || c == 'B';
    }

    private static int trimSpace(StringBuilder text) {
        int removed = 0;
        while (text.length() > 0 && isSpace(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
            removed++;
        }
        return removed;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == ' ' || c == ' ';
    }

    /**
     * A short date in the phone's language and time zone: the month and day for this year
     * ("Oct 7"), with the year for any other ("Oct 7, 2025").
     */
    static String shortDate(Context context, long seconds, long nowMillis) {
        long millis = seconds * 1000L;
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                | (sameYear(millis, nowMillis) ? DateUtils.FORMAT_NO_YEAR : DateUtils.FORMAT_SHOW_YEAR);
        return DateUtils.formatDateTime(context, millis, flags);
    }

    /**
     * A story's posting time to the minute ("3:04 PM", "15:04"), with the day before it ("Oct 7,
     * 3:04 PM") only when it isn't from today. A story is at most a day old, and its header shares
     * one line between the name, this time and the Follow button: the full text pushed the name
     * out (Login2, 47.4.3, 2026-10-18).
     */
    static String storyTime(Context context, long seconds, long nowMillis) {
        long millis = seconds * 1000L;
        int flags = DateUtils.FORMAT_SHOW_TIME | (sameDay(millis, nowMillis) ? 0
                : DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
        return DateUtils.formatDateTime(context, millis, flags);
    }

    private static boolean sameYear(long millis, long nowMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        int year = calendar.get(Calendar.YEAR);
        calendar.setTimeInMillis(nowMillis);
        return year == calendar.get(Calendar.YEAR);
    }

    private static boolean sameDay(long millis, long nowMillis) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMillis);
        return then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
    }
}
