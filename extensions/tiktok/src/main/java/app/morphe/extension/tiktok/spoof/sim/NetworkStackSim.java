/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.spoof.sim;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.region.RegionSpoof;

/**
 * Keeps TikTok's network stack on the phone's real SIM while SIM spoof shows the rest of the app
 * the preset (#415).
 *
 * <p>TTNet asks the app for the carrier region, the app region (the carrier region first), the
 * carrier code and an init-region header, sends them to its routing config server and keeps the
 * store region that comes back. With the US preset on a phone on a Bangladeshi network, the image
 * and API hosts it was handed were the US side's, and 317 of 340 image fetches came back 403 until
 * a VPN moved the phone's address to match. The patch passes every answer of those getters
 * through here. An answer still holding the preset gets the phone's own value back, in the case
 * TikTok wrote it in. Anything else came from somewhere the preset doesn't reach and stays, and so
 * does every answer when the phone has no SIM to read or the spoof is off. The app's own requests
 * and screens keep the preset, since the getters TikTok reads for them aren't these.
 *
 * <p>Two phones need the stack to keep the preset, and every answer stays for them (#523). One
 * whose SIM or network is from a country TikTok isn't offered in: with a Chinese SIM the stack
 * routed by CN and the app showed "No internet connection" for everyone in China who uses the
 * spoof to get in. And one whose traffic goes through a VPN, since the address TikTok sees is
 * then the VPN's and the preset is what matches it, which is how #415's phone loaded images too.
 */
@SuppressWarnings("unused")
public final class NetworkStackSim {
    private static final AtomicLong SWAPPED = new AtomicLong();

    /**
     * Where TikTok isn't offered, so a SIM from there is the one the spoof hides: mainland China,
     * Hong Kong (TikTok left in 2020), India (banned since 2020), Iran, Afghanistan and North Korea.
     */
    private static final String[] NO_TIKTOK = {"cn", "hk", "in", "ir", "af", "kp"};

    private NetworkStackSim() {
    }

    /** The carrier region or app region TTNet routes by. */
    public static String region(String value) {
        try {
            String preset = presetCountry();
            if (preset == null || value == null || !value.trim().equalsIgnoreCase(preset)) return value;
            TelephonyManager phone = phone();
            if (phone == null || keepsThePreset(phone)) return value;
            String real = firstOf(phone.getSimCountryIso(), phone.getNetworkCountryIso());
            if (real.isEmpty() || real.equalsIgnoreCase(preset)) return value;
            SWAPPED.incrementAndGet();
            return value.equals(value.toUpperCase(Locale.ROOT))
                    ? real.toUpperCase(Locale.ROOT) : real.toLowerCase(Locale.ROOT);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Network stack region", failure);
            return value;
        }
    }

    /** The network's carrier code, as TTNet's native side asks for it. */
    public static String networkOperator(String value) {
        return operator(value, true);
    }

    /** The SIM's carrier code. */
    public static String simOperator(String value) {
        return operator(value, false);
    }

    /**
     * The header TTNet sends to its config server:
     * {@code carrierregion=US;mccmnc=310260;sysregion=BD;appregion=US}. The system region is the
     * phone's language setting and stays as it is.
     */
    public static String initRegion(String value) {
        if (value == null || value.indexOf('=') < 0) return value;
        try {
            String[] parts = value.split(";", -1);
            for (int index = 0; index < parts.length; index++) {
                int equals = parts[index].indexOf('=');
                if (equals < 0) continue;
                String key = parts[index].substring(0, equals);
                String field = parts[index].substring(equals + 1);
                String kept;
                switch (key.trim().toLowerCase(Locale.ROOT)) {
                    case "carrierregion":
                    case "appregion":
                        kept = region(field);
                        break;
                    case "mccmnc":
                        kept = simOperator(field);
                        break;
                    default:
                        continue;
                }
                parts[index] = key + "=" + kept;
            }
            return String.join(";", parts);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Network stack init region", failure);
            return value;
        }
    }

    /** How many answers went back to the real SIM since the app started, for the diagnostic export. */
    static long swapped() {
        return SWAPPED.get();
    }

    static void resetForTests() {
        SWAPPED.set(0);
    }

    private static String operator(String value, boolean network) {
        try {
            String preset = presetOperator();
            if (preset == null || value == null || !value.trim().equals(preset)) return value;
            TelephonyManager phone = phone();
            if (phone == null || keepsThePreset(phone)) return value;
            String real = network
                    ? firstOf(phone.getNetworkOperator(), phone.getSimOperator())
                    : firstOf(phone.getSimOperator(), phone.getNetworkOperator());
            if (real.isEmpty() || real.equals(preset)) return value;
            SWAPPED.incrementAndGet();
            return real;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Network stack operator", failure);
            return value;
        }
    }

    /** The preset country the SIM getters answer with, or null when they answer the phone's own. */
    private static String presetCountry() {
        if (Utils.getContext() == null || !Settings.SIM_SPOOF.get()) return null;
        SimRotation.ensure();
        String iso = Settings.SIM_SPOOF_ISO.get();
        return RegionSpoof.validCountry(iso) ? iso.trim() : null;
    }

    private static String presetOperator() {
        if (Utils.getContext() == null || !Settings.SIM_SPOOF.get()) return null;
        SimRotation.ensure();
        String code = Settings.SIMSPOOF_MCCMNC.get();
        return SpoofSimPatch.validMccMnc(code) ? code.trim() : null;
    }

    private static TelephonyManager phone() {
        Context context = Utils.getContext();
        return context == null ? null : (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /** True when the stack should keep the preset: a SIM or network where TikTok isn't offered, or a VPN. */
    private static boolean keepsThePreset(TelephonyManager phone) {
        return noTikTok(phone.getSimCountryIso()) || noTikTok(phone.getNetworkCountryIso()) || onVpn();
    }

    static boolean noTikTok(String country) {
        if (country == null) return false;
        String code = country.trim().toLowerCase(Locale.ROOT);
        for (String blocked : NO_TIKTOK) {
            if (blocked.equals(code)) return true;
        }
        return false;
    }

    /**
     * Whether TikTok's traffic goes through a VPN. Hide VPN answers TikTok's own checks false, but
     * not this class's, so this reads the real answer. Anything unreadable counts as no VPN.
     */
    private static boolean onVpn() {
        try {
            Context context = Utils.getContext();
            ConnectivityManager connectivity = context == null
                    ? null : (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) return false;
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Network stack VPN check unreadable, counted as no VPN", failure);
            return false;
        }
    }

    private static String firstOf(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }
}
