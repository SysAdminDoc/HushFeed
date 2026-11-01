/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.spoof.region;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import androidx.annotation.Nullable;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.sim.SimPreset;
import app.morphe.extension.tiktok.spoof.sim.SimPresets;
import app.morphe.extension.tiktok.spoof.sim.SpoofSimPatch;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

public final class RegionSpoof {
    private static final Set<String> COUNTRIES = new HashSet<>(Arrays.asList(Locale.getISOCountries()));

    /*
     * The patch sends every Locale.getDefault() and TimeZone.getDefault() in TikTok's code
     * through here, on every thread, and those are asked for all the time: each String.format,
     * each date and each lower-casing. Each answer used to compile a regex, build a Locale and
     * scan the presets (or ICU's whole zone table for a custom country). The last answer is kept
     * with what it was worked out from, so a changed setting still takes effect at once. The
     * extension's own classes aren't hooked.
     */
    private static volatile String[] lastCountry = {"", ""};
    private static volatile LocaleAnswer lastLocale;
    private static volatile Object[] lastZone = {"", null};

    /*
     * The whole app's time zone (#184, #198). The hook answers TikTok's own TimeZone.getDefault()
     * calls, but the framework's formatters read the process default themselves: SimpleDateFormat,
     * Calendar.getInstance(), DateFormat.getTimeFormat, DateUtils, java.time's
     * ZoneId.systemDefault() and ICU, which follows java.util's default on Android. So with Match
     * locale and timezone on, the preset's zone is made the process default too. The S22 showed
     * all three reading it (2026-10-21), while the chat's date labels stayed on the phone's zone.
     * Those come from TikTok's own BDDateFormat (the chat's date headers through X/0Hdu on
     * 47.5.3), which formats in librex.so with localtime_r. Bionic's localtime_r reads the TZ
     * variable on every call and only falls back to the phone's persist.sys.timezone without it,
     * so the preset's zone goes in TZ too, and TZ goes back to what it was (unset on a phone)
     * with the phone's zone. The switch happens as the host application attaches its context,
     * before TikTok builds a formatter, and the zone it replaced is the phone's, kept for the
     * things that run on the phone's clock (phoneTimeZone()) and put back when the setting goes
     * off or Hushfeed is paused.
     *
     * Android puts the system's zone back as the default when the phone's zone changes, so each
     * answer the hook gives checks the default is still the preset's and sets it again if not.
     * When nothing changed that's one string compare.
     */
    private static final Object ZONE_LOCK = new Object();
    /** The default this class set and the phone's zone it replaced. Null while the default is the phone's. */
    private static volatile AppliedZone applied;

    private static final class AppliedZone {
        final String id;
        final TimeZone phone;
        /** TZ as it was before this class set it, null when it wasn't set. */
        @Nullable final String nativeBefore;

        AppliedZone(String id, TimeZone phone, @Nullable String nativeBefore) {
            this.id = id;
            this.phone = phone;
            this.nativeBefore = nativeBefore;
        }
    }

    /** The TZ variable native code reads before the phone's own zone. A test swaps in its own. */
    static class NativeZone {
        @Nullable
        String get() {
            return android.system.Os.getenv("TZ");
        }

        void set(@Nullable String id) throws android.system.ErrnoException {
            if (id == null) android.system.Os.unsetenv("TZ");
            else android.system.Os.setenv("TZ", id, true);
        }
    }

    static NativeZone nativeZone = new NativeZone();

    private static final class LocaleAnswer {
        final Locale original;
        final String country;
        final Locale answer;

        LocaleAnswer(Locale original, String country, Locale answer) {
            this.original = original;
            this.country = country;
            this.answer = answer;
        }
    }

    private RegionSpoof() { }

    public static boolean validCountry(String value) {
        if (value == null) return false;
        String code = value.trim();
        if (code.length() != 2) return false;
        for (int index = 0; index < 2; index++) {
            char letter = code.charAt(index);
            if ((letter < 'A' || letter > 'Z') && (letter < 'a' || letter > 'z')) return false;
        }
        return COUNTRIES.contains(code.toUpperCase(Locale.ROOT));
    }

    /*
     * Sign-in requests. TTNet's common-parameter handler hands over each request's path just
     * before it builds that request's parameters, on the same thread, and calls requestDone()
     * right after. While a /passport/ request's parameters are built with Match region fields in
     * requests on, that thread gets TikTok's own answers: the region getters, the locale, the
     * timezone and the request fields the builder hook would set. The two values TikTok worked
     * out once as it started, sys_region and timezone_name, were worked out under the preset and
     * kept, so requestParams() puts back what their sources say now.
     *
     * For You requests get the same mark when Keep the For You feed on your real SIM is on, and
     * requestParams() gives them the phone's own SIM (#214, #215).
     */
    /**
     * This thread's mark: when it was set, in {@link SystemClock#elapsedRealtime()}, how many
     * fills it spans, and which kind of fill it is. A fill can run inside another for the same
     * request (AppLog's inside the handler's), and only the outer one's end takes the mark off.
     */
    private static final ThreadLocal<long[]> MARK = new ThreadLocal<>();
    /** Threads building a marked request's parameters now, so every other call skips the ThreadLocal. */
    private static final AtomicInteger marked = new AtomicInteger();
    private static final long SIGN_IN_FILL = 1;
    private static final long FEED_FILL = 2;
    private static final String SIGN_IN_PATH = "passport/";
    private static final String FEED_PATH_START = "aweme/v";
    private static final String FEED_PATH_END = "/feed/";
    /**
     * How long a mark lasts. A fill takes milliseconds, so a mark this old is one whose fill threw
     * before requestDone() and whose thread hasn't sent another request since; it goes, rather
     * than leaving that thread on the real region until it does.
     */
    static final long MARK_LIFETIME_MS = 10_000L;

    /** From the common-parameter handler, with the path of the request it's about to fill. */
    public static void requestPath(@Nullable String path) {
        try {
            mark(Utils.getContext() == null ? 0 : fillOf(path));
        } catch (RuntimeException error) {
            Logger.printException(() -> "Region spoof could not read a request's path", error);
        }
    }

    /**
     * With the full URL of the request about to be filled, from the routes that fill common
     * parameters without passing the handler's path read: the token interceptor (the token
     * heartbeat, token change and logout), AppLog's URL entry point, and the JS request helpers
     * that fill a POST body before they send it.
     */
    public static void requestUrl(@Nullable String url) {
        requestPath(pathOf(url));
    }

    /**
     * From the places that add common parameters to a URL TikTok is still building, and the
     * StringBuilder holds that URL so far. Null when there's nothing to read.
     */
    public static void requestUrlBuilder(@Nullable StringBuilder url) {
        requestUrl(url == null ? null : url.toString());
    }

    /** The path of a URL, without its scheme, host, query or fragment. Null when it has none. */
    @Nullable
    static String pathOf(@Nullable String url) {
        if (url == null) return null;
        int scheme = url.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        // The host ends at the first slash, query or fragment. Only a slash starts a path.
        while (start < url.length() && "/?#".indexOf(url.charAt(start)) < 0) start++;
        if (start == url.length() || url.charAt(start) != '/') return null;
        int end = start;
        while (end < url.length() && "?#".indexOf(url.charAt(end)) < 0) end++;
        return url.substring(start, end);
    }

    /** From the common-parameter handler, once the request's parameters are built. */
    public static void requestDone() {
        long[] mark = MARK.get();
        if (mark != null && --mark[1] <= 0) clearMark();
    }

    /** The kind of fill a request's path asks for, with the switch it needs on. Zero for none. */
    private static long fillOf(@Nullable String path) {
        if (isSignIn(path)) return Settings.REGION_REQUEST_SPOOF.get() ? SIGN_IN_FILL : 0;
        if (isForYouFeed(path) && Settings.SIM_SPOOF.get() && Settings.SIM_SPOOF_FEED_REAL.get()) {
            return FEED_FILL;
        }
        return 0;
    }

    /**
     * A marked fill marks the thread, or goes one deeper into a mark of the same kind already
     * there. Any other request takes the mark off, whatever its depth: that's how a fill that
     * threw before its end gets put right by the thread's next request.
     */
    private static void mark(long fill) {
        long[] mark = MARK.get();
        if (fill != 0) {
            long now = SystemClock.elapsedRealtime();
            if (mark != null && mark[2] == fill && now - mark[0] < MARK_LIFETIME_MS) {
                mark[1]++;
                return;
            }
            // A mark run out is a fill that threw long ago, so this fill starts its own.
            if (mark != null) clearMark();
            MARK.set(new long[]{now, 1, fill});
            marked.incrementAndGet();
        } else if (mark != null) {
            clearMark();
        }
    }

    private static void clearMark() {
        MARK.remove();
        marked.decrementAndGet();
    }

    /** TikTok's sign-in, sign-up and account verification endpoints all sit under /passport/. */
    static boolean isSignIn(@Nullable String path) {
        if (path == null) return false;
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') start++;
        return start > 0 && path.regionMatches(start, SIGN_IN_PATH, 0, SIGN_IN_PATH.length());
    }

    /**
     * The For You feed: /aweme/v1/feed/ and /aweme/v2/feed/ on 47.4.3. The Following and Friends
     * feeds sit under their own paths and keep the preset.
     */
    static boolean isForYouFeed(@Nullable String path) {
        if (path == null) return false;
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') start++;
        if (start == 0 || !path.regionMatches(start, FEED_PATH_START, 0, FEED_PATH_START.length())) return false;
        int digits = start + FEED_PATH_START.length();
        int end = digits;
        while (end < path.length() && path.charAt(end) >= '0' && path.charAt(end) <= '9') end++;
        return end > digits && path.length() == end + FEED_PATH_END.length()
                && path.regionMatches(end, FEED_PATH_END, 0, FEED_PATH_END.length());
    }

    /** The kind of fill this thread is in, or zero. */
    private static long markedFill() {
        if (marked.get() <= 0) return 0;
        long[] mark = MARK.get();
        if (mark == null) return 0;
        if (SystemClock.elapsedRealtime() - mark[0] < MARK_LIFETIME_MS) return mark[2];
        clearMark();
        return 0;
    }

    private static String selectedCountry() {
        return markedFill() != 0 ? "" : presetCountry();
    }

    private static String presetCountry() {
        if (Utils.getContext() == null || !Settings.SIM_SPOOF.get() || !Settings.REGION_SPOOF.get()) return "";
        String value = Settings.SIM_SPOOF_ISO.get();
        if (value == null) return "";
        String[] last = lastCountry;
        if (value.equals(last[0])) return last[1];
        String country = validCountry(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        lastCountry = new String[]{value, country};
        return country;
    }

    public static String country(String original) {
        String country = selectedCountry();
        return country.isEmpty() ? original : country;
    }

    public static String storeCountry(String original) {
        return Utils.getContext() != null && Settings.REGION_STORE_SPOOF.get() ? country(original) : original;
    }

    /*
     * The region fields AppLog's common-parameter builder puts on every request TikTok sends.
     * carrier_region, sys_region and region are the region hub's own answers, which the getter
     * hooks already turn into the preset, so they are only looked at here, never written.
     * current_region and residence are what TikTok's servers last told this phone, kept in its
     * preferences, and carrier_region_v2 is the network's country code (an MCC) read from the
     * system configuration. No getter hook reaches those three.
     *
     * Two fields in that map are copies TikTok took once as it started: sys_region, from the
     * hub's getter, and timezone_name, from TimeZone.getDefault().getID(). Both were worked out
     * with the hooks answering the preset, and the copies stay in a cache for the life of the
     * process. A sign-in puts the live answers back: the system locale's country, and the
     * phone's own zone, which is the default unless this class made the preset's the default.
     */
    static final String[] SAVED_REGION_FIELDS = {"current_region", "residence"};
    static final String NETWORK_COUNTRY_FIELD = "carrier_region_v2";
    static final String[] HUB_REGION_FIELDS = {"carrier_region", "sys_region", "region"};
    static final String SYSTEM_REGION_FIELD = "sys_region";
    static final String TIMEZONE_NAME_FIELD = "timezone_name";
    private static volatile String lastRequestReport = "";

    /**
     * Called with the parameter map at the end of the builder. Only fields TikTok already put in
     * the map are changed: one it left out stays out.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void requestParams(Map params) {
        try {
            if (params == null || Utils.getContext() == null) return;
            long fill = markedFill();
            if (fill == FEED_FILL) {
                realSimFields(params);
                return;
            }
            if (!Settings.REGION_REQUEST_SPOOF.get()) return;
            if (fill == SIGN_IN_FILL) {
                // A sign-in goes out with the fields TikTok set, so the account sees the real
                // region, and the copies it made at start-up are put back to what they are now.
                List<String> live = liveStartUpFields(params);
                String preset = presetCountry();
                if (!preset.isEmpty()) report(params, preset, null, live);
                return;
            }
            String country = selectedCountry();
            if (country.isEmpty()) return;
            List<String> set = new ArrayList<>();
            for (String field : SAVED_REGION_FIELDS) {
                if (params.get(field) instanceof String) {
                    params.put(field, country);
                    set.add(field);
                }
            }
            Object network = params.get(NETWORK_COUNTRY_FIELD);
            String mcc = presetMcc();
            if (mcc != null && network instanceof String && isMcc((String) network)) {
                params.put(NETWORK_COUNTRY_FIELD, mcc);
                set.add(NETWORK_COUNTRY_FIELD);
            }
            report(params, country, set, null);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Region spoof could not set a request's region fields", error);
        }
    }

    /*
     * For You requests with Keep the For You feed on your real SIM on. TikTok reads the SIM once
     * as it starts, through getters the SIM patch answers with the preset, and keeps the answers
     * for the life of the process, so sticker search and the feed's
     * region all saw the same preset. These are the fields that carry it in a request: the SIM
     * country in the region fields, the operator code and the carrier name. Each one still
     * holding the preset gets the phone's own value, read here, where the SIM patch doesn't reach.
     * A field with anything else came from somewhere the preset doesn't touch and stays, and so
     * does every field when the phone has no SIM to read.
     */
    static final String[] SIM_COUNTRY_FIELDS = {"carrier_region", "region", "op_region", "sim_region"};
    static final String SIM_OPERATOR_FIELD = "mcc_mnc";
    static final String SIM_OPERATOR_NAME_FIELD = "carrier";

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void realSimFields(Map params) {
        TelephonyManager phone = (TelephonyManager) Utils.getContext().getSystemService(Context.TELEPHONY_SERVICE);
        List<String> set = new ArrayList<>();
        if (phone != null) {
            String preset = Settings.SIM_SPOOF_ISO.get();
            String country = firstOf(phone.getSimCountryIso(), phone.getNetworkCountryIso());
            if (preset != null && !country.isEmpty()) {
                for (String field : SIM_COUNTRY_FIELDS) {
                    Object value = params.get(field);
                    if (!(value instanceof String) || !((String) value).equalsIgnoreCase(preset.trim())) continue;
                    String text = (String) value;
                    params.put(field, text.equals(text.toUpperCase(Locale.ROOT))
                            ? country.toUpperCase(Locale.ROOT) : country.toLowerCase(Locale.ROOT));
                    set.add(field);
                }
            }
            putReal(params, SIM_OPERATOR_FIELD, Settings.SIMSPOOF_MCCMNC.get(),
                    firstOf(phone.getNetworkOperator(), phone.getSimOperator()), set);
            putReal(params, SIM_OPERATOR_NAME_FIELD, Settings.SIMSPOOF_OP_NAME.get(),
                    firstOf(phone.getNetworkOperatorName(), phone.getSimOperatorName()), set);
        }
        // The start-up copies were worked out under the preset if Match locale was on as TikTok
        // started, and switching it off since doesn't change them, so they get the live answers
        // whatever it says now, as a sign-in's do.
        List<String> live = liveStartUpFields(params);
        if (!BaseSettings.DEBUG.get()) return;
        String line = "For You request fields put back to the phone's own SIM " + set
                + ", start-up copies put back " + live;
        if (line.equals(lastRequestReport)) return;
        lastRequestReport = line;
        Logger.printDebug(() -> line);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putReal(Map params, String field, @Nullable String preset, String real, List<String> set) {
        Object value = params.get(field);
        if (preset == null || real.isEmpty() || !(value instanceof String)) return;
        if (!((String) value).trim().equals(preset.trim())) return;
        params.put(field, real);
        set.add(field);
    }

    private static String firstOf(@Nullable String first, @Nullable String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }

    /**
     * Puts the live answer in each start-up copy the map holds, and returns the fields it did.
     * A field TikTok left out stays out, and so does one that isn't a String.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<String> liveStartUpFields(Map params) {
        List<String> live = new ArrayList<>();
        if (params.get(SYSTEM_REGION_FIELD) instanceof String) {
            String region = systemRegion();
            // TikTok's cache never takes an empty value, so a system locale with no country
            // would have left the field out altogether.
            if (region.isEmpty()) params.remove(SYSTEM_REGION_FIELD);
            else params.put(SYSTEM_REGION_FIELD, region);
            live.add(SYSTEM_REGION_FIELD);
        }
        if (params.get(TIMEZONE_NAME_FIELD) instanceof String) {
            params.put(TIMEZONE_NAME_FIELD, phoneTimeZone().getID());
            live.add(TIMEZONE_NAME_FIELD);
        }
        return live;
    }

    /** The country of the system configuration's locale, which is what the hub's sys_region getter reads. */
    @SuppressWarnings("deprecation")
    private static String systemRegion() {
        Locale locale = Resources.getSystem().getConfiguration().locale;
        return locale == null ? "" : locale.getCountry();
    }

    /** The first three digits of the SIM preset's operator code, or null when there isn't a usable one. */
    private static String presetMcc() {
        String code = Settings.SIMSPOOF_MCCMNC.get();
        if (code == null || !SpoofSimPatch.validMccMnc(code)) return null;
        return code.trim().substring(0, 3);
    }

    private static boolean isMcc(String value) {
        if (value.length() != 3) return false;
        for (int index = 0; index < 3; index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
        }
        return true;
    }

    /*
     * One debug line each time the outcome changes, not one per request. It names fields and says
     * whether the hub's fields already carry the preset, and never prints a value the phone had
     * before, since that is the real region. A null set is a sign-in request, where nothing is set
     * to the preset, every region field is looked at, and live names the start-up copies that were
     * put back to their live answers.
     */
    @SuppressWarnings("rawtypes")
    private static void report(Map params, String country, @Nullable List<String> set,
                               @Nullable List<String> live) {
        if (!BaseSettings.DEBUG.get()) return;
        List<String> matching = new ArrayList<>();
        List<String> other = new ArrayList<>();
        List<String> fields = new ArrayList<>(Arrays.asList(HUB_REGION_FIELDS));
        if (set == null) fields.addAll(Arrays.asList(SAVED_REGION_FIELDS));
        for (String field : fields) {
            Object value = params.get(field);
            if (!(value instanceof String)) continue;
            (country.equalsIgnoreCase((String) value) ? matching : other).add(field);
        }
        String line = set == null
                ? "Sign-in request region fields left as TikTok set them for " + country
                        + ", start-up copies put back " + live
                        + ". Still the preset " + matching + ", something else " + other
                : "Request region fields for " + country + ". Set to the preset " + set
                        + ", already the preset " + matching + ", something else " + other;
        if (line.equals(lastRequestReport)) return;
        lastRequestReport = line;
        Logger.printDebug(() -> line);
    }

    public static Locale locale(Locale original) {
        String country = selectedCountry();
        if (original == null || country.isEmpty() || country.equals(original.getCountry())) return original;
        LocaleAnswer last = lastLocale;
        if (last != null && last.country.equals(country)
                && (last.original == original || last.original.equals(original))) {
            return last.answer;
        }
        Locale answer = withCountry(original, country);
        lastLocale = new LocaleAnswer(original, country, answer);
        return answer;
    }

    private static Locale withCountry(Locale original, String country) {
        try {
            return new Locale.Builder().setLocale(original).setRegion(country).build();
        } catch (java.util.IllformedLocaleException error) {
            // Legacy variants can be invalid BCP 47. Keep the other locale fields intact.
            try {
                Locale.Builder builder = new Locale.Builder().setLanguage(original.getLanguage())
                        .setScript(original.getScript()).setRegion(country);
                for (Character key : original.getExtensionKeys()) {
                    builder.setExtension(key, original.getExtension(key));
                }
                return builder.build();
            } catch (java.util.IllformedLocaleException invalidLanguage) {
                return original;
            }
        }
    }

    public static TimeZone timeZone(TimeZone original) {
        if (original == null) return null;
        if (markedFill() != 0) {
            // A sign-in or a real-SIM feed fill gets the phone's own zone.
            AppliedZone now = applied;
            return now != null && now.id.equals(original.getID()) ? (TimeZone) now.phone.clone() : original;
        }
        TimeZone zone = presetZone();
        // The hooked call hands over the default, so this is where a default the system put
        // back, or one left from a setting that has changed since, is noticed.
        if (zone == null) {
            AppliedZone was = applied;
            if (was == null) return original;
            // Switched off or paused since: the phone's zone goes back, to this caller too.
            syncProcessZone(null);
            return was.id.equals(original.getID()) ? (TimeZone) was.phone.clone() : original;
        }
        if (zone.getID().equals(original.getID())) return original;
        syncProcessZone(zone);
        // A copy each time, as TimeZone.getDefault() gives: a TimeZone can be changed by whoever
        // holds it, and the kept one is shared by every caller.
        return (TimeZone) zone.clone();
    }

    /** From the host application's attachBaseContext, right after the extension gets its context. */
    public static void applyProcessZone() {
        try {
            syncProcessZone(presetZone());
        } catch (RuntimeException error) {
            Logger.printException(() -> "Region spoof could not set the app's time zone", error);
        }
    }

    /**
     * The phone's own time zone, for what runs on the phone's clock: the wellbeing day, Auto
     * streak's send time and a sign-in's timezone_name. While the preset's zone is the app's
     * default this is the zone it replaced, otherwise the default itself.
     */
    public static TimeZone phoneTimeZone() {
        TimeZone current = TimeZone.getDefault();
        AppliedZone now = applied;
        return now != null && now.id.equals(current.getID()) ? (TimeZone) now.phone.clone() : current;
    }

    /** The format, set to the phone's own zone, for a time Hushfeed shows against the phone's clock. */
    public static DateFormat phoneClock(DateFormat format) {
        format.setTimeZone(phoneTimeZone());
        return format;
    }

    /** The preset country's zone while Match locale and timezone is on and Hushfeed isn't paused, else null. */
    @Nullable
    private static TimeZone presetZone() {
        String country = presetCountry();
        if (country.isEmpty()) return null;
        Object[] last = lastZone;
        if (country.equals(last[0])) return (TimeZone) last[1];
        TimeZone zone = zoneOf(country);
        lastZone = new Object[]{country, zone};
        return zone;
    }

    /**
     * Makes the process default the preset's zone, or the phone's when there's no preset. The
     * default is read here, not taken from a caller, since it's what the framework's formatters
     * use. A default that isn't the one this class set is the phone's: the first time through,
     * or the system's own after the phone's zone changed.
     */
    static void syncProcessZone(@Nullable TimeZone preset) {
        synchronized (ZONE_LOCK) {
            AppliedZone now = applied;
            TimeZone current = TimeZone.getDefault();
            boolean ours = now != null && now.id.equals(current.getID());
            if (preset == null) {
                if (now == null) return;
                applied = null;
                // One the system set since is the phone's already.
                if (ours) TimeZone.setDefault(now.phone);
                setNativeZone(now.nativeBefore);
                reportZone("App time zone back to the phone's");
                return;
            }
            String id = preset.getID();
            if (id.equals(current.getID())) return;
            TimeZone phone = ours ? now.phone : current;
            // TZ is still this class's when the system put the phone's zone back as the default.
            String nativeBefore = now != null ? now.nativeBefore : nativeZoneNow();
            TimeZone.setDefault(preset);
            setNativeZone(id);
            applied = new AppliedZone(id, phone, nativeBefore);
            // Never the phone's zone in the log, since that's the real region.
            reportZone("App time zone set to " + id);
        }
    }

    /** Forgets the phone's zone without touching the default, so a test starts from nothing set. */
    static void forgetProcessZone() {
        synchronized (ZONE_LOCK) {
            applied = null;
        }
    }

    @Nullable
    private static String nativeZoneNow() {
        try {
            return nativeZone.get();
        } catch (RuntimeException error) {
            Logger.printException(() -> "Region spoof could not read TZ", error);
            return null;
        }
    }

    /** A TZ that can't be set leaves native dates on the phone's zone, nothing worse. */
    private static void setNativeZone(@Nullable String id) {
        try {
            nativeZone.set(id);
        } catch (android.system.ErrnoException | RuntimeException error) {
            Logger.printException(() -> "Region spoof could not set TZ", error);
        }
    }

    private static void reportZone(String line) {
        if (BaseSettings.DEBUG.get()) Logger.printDebug(() -> line);
    }

    private static TimeZone zoneOf(String country) {
        for (SimPreset preset : SimPresets.PRESETS) {
            if (country.equalsIgnoreCase(preset.iso)) return TimeZone.getTimeZone(preset.timeZone);
        }
        if (Build.VERSION.SDK_INT >= 24) {
            String[] zones = android.icu.util.TimeZone.getAvailableIDs(country);
            if (zones.length > 0) return TimeZone.getTimeZone(zones[0]);
        }
        return null;
    }
}
