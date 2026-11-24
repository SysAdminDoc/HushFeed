/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.region.RegionSpoof;

/** The daily send time and the hours an automatic catch-up is allowed to use. */
final class StreakSchedule {
    static final long NO_TIME = Long.MAX_VALUE;
    private static final int MINUTES_PER_DAY = 24 * 60;

    final boolean randomWindow = Settings.AUTO_STREAK_RANDOM_WINDOW.get();
    final int startMinute = randomWindow ? Settings.AUTO_STREAK_WINDOW_START.get() : Settings.AUTO_STREAK_MINUTE.get();
    final int endMinute = randomWindow ? Settings.AUTO_STREAK_WINDOW_END.get() : startMinute;
    private final long activeSince;
    private final TimeZone zone = RegionSpoof.phoneTimeZone();

    StreakSchedule(long activeSince) {
        this.activeSince = activeSince;
    }

    long timeOn(long now, int daysAhead) {
        Calendar date = calendar(now);
        date.add(Calendar.DATE, daysAhead);
        if (!randomWindow) return atMinute(date, startMinute).getTimeInMillis();

        // The activation time and local date keep today's choice stable across restarts.
        // Each calendar day has one choice, including when the range spans midnight.
        int length = (endMinute - startMinute + MINUTES_PER_DAY) % MINUTES_PER_DAY + 1;
        long day = date.get(Calendar.YEAR) * 366L + date.get(Calendar.DAY_OF_YEAR);
        int offset = new Random(activeSince ^ (day * 0x9E3779B97F4A7C15L)).nextInt(length);
        for (int index = 0; index < length; index++) {
            int minute = (startMinute + (offset + index) % length) % MINUTES_PER_DAY;
            Calendar time = atMinute(date, minute);
            // A clock change can erase this minute. Try another minute in the window.
            if (time.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR) && minuteOfDay(time) == minute) {
                return time.getTimeInMillis();
            }
        }
        return NO_TIME;
    }

    long nextDay(long now) {
        int daysAhead = 1;
        long time = timeOn(now, daysAhead);
        while (time == NO_TIME) time = timeOn(now, ++daysAhead);
        return time;
    }

    long earliest(long time) {
        return randomWindow ? time : time - AutoStreak.EARLY_MILLIS;
    }

    boolean isDue(long now, long time) {
        return time != NO_TIME && now >= earliest(time)
                && (!randomWindow || contains(minuteOfDay(calendar(now))));
    }

    boolean allowsDispatch(long now, long recipientActiveSince) {
        long time = timeOn(now, 0);
        return time != NO_TIME && time >= Math.max(activeSince, recipientActiveSince)
                && (!randomWindow || isDue(now, time));
    }

    long retryTime(long now) {
        long retry = now + AutoStreak.RETRY_MILLIS;
        if (!randomWindow) return retry;
        int today = calendar(now).get(Calendar.DAY_OF_YEAR);
        Calendar candidate = calendar(retry);
        if (candidate.get(Calendar.DAY_OF_YEAR) != today) return NO_TIME;
        if (contains(minuteOfDay(candidate))) return retry;

        // An overnight window can have another opening later on the same calendar day.
        long partialMinute = candidate.get(Calendar.SECOND) * 1_000L + candidate.get(Calendar.MILLISECOND);
        candidate.setTimeInMillis(retry - partialMinute);
        do {
            candidate.add(Calendar.MINUTE, 1);
            if (candidate.get(Calendar.DAY_OF_YEAR) != today) return NO_TIME;
        } while (!contains(minuteOfDay(candidate)));
        return candidate.getTimeInMillis();
    }

    private boolean contains(int minute) {
        return startMinute <= endMinute ? minute >= startMinute && minute <= endMinute
                : minute >= startMinute || minute <= endMinute;
    }

    private Calendar calendar(long millis) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(millis);
        return calendar;
    }

    private static Calendar atMinute(Calendar date, int minute) {
        Calendar time = (Calendar) date.clone();
        time.set(Calendar.HOUR_OF_DAY, minute / 60);
        time.set(Calendar.MINUTE, minute % 60);
        time.set(Calendar.SECOND, 0);
        time.set(Calendar.MILLISECOND, 0);
        return time;
    }

    private static int minuteOfDay(Calendar time) {
        return time.get(Calendar.HOUR_OF_DAY) * 60 + time.get(Calendar.MINUTE);
    }
}
