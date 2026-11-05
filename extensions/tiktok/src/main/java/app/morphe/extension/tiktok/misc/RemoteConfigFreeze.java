/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The switch in front of Freeze TikTok's experiments.
 *
 * <p>TikTok asks its servers for its A/B settings at each start and now and then after, and
 * stores what comes back three ways: the whole response at once, a chunk of it, or a chunk
 * through its newer optimized path. Every one of them writes the values TikTok reads afterwards,
 * so with the switch on each store returns before it writes anything and TikTok keeps the values
 * it already had. Each is a parse of a response that already arrived, so returning early leaves
 * no request waiting and nothing to retry. With the switch off, and while Hushfeed is paused,
 * every store runs as TikTok ships it.
 *
 * <p>The values kept have to be ones the running TikTok and account were sent. A freeze turned on
 * before TikTok ever stored any would leave it on its built-in defaults, and one held across an
 * update or an account switch would keep what the old build or the other account got. So a store
 * that goes through saves {@link Settings#REMOTE_CONFIG_STAMP}, the TikTok version code and, with
 * the switch on, the account it came from, and a start with the switch on whose TikTok or account
 * doesn't match lets every store of that start through. The answer for a start is decided once, so the chunks of one
 * response are never half kept, except when an account the stamp doesn't name turns up after it:
 * that is a switch, and the new account's response goes through.
 */
@SuppressWarnings("unused")
public final class RemoteConfigFreeze {
    /** {@link Settings#FREEZE_REMOTE_CONFIG}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "freeze_remote_config";
    /** {@link Settings#REMOTE_CONFIG_STAMP}'s key, for the same read and its save. */
    static final String STAMP_KEY = "remote_config_stamp";

    /** Whether this start lets every store through, decided by its first store with the switch on. */
    @Nullable
    static Boolean firstStart;
    /** The running TikTok's version code, read once. Tests put their own here. */
    @Nullable
    static String version;

    private RemoteConfigFreeze() {
    }

    /** True while TikTok's server settings store should return before writing anything. */
    public static boolean holdsStore() {
        if (!SettingsStatus.remoteConfigFreezeEnabled) return false;
        try {
            Context context = Utils.getContext();
            boolean early = context == null;
            if (early) context = EarlySwitch.application();
            boolean on = early ? EarlySwitch.isOn(SWITCH_KEY) : Settings.FREEZE_REMOTE_CONFIG.get();
            if (!on) {
                // The account isn't asked with the switch off, so the stamp names none and the
                // first start turned on with a known account takes that account's values once.
                markStored(context, early, "");
                Logger.printDebug(() -> "Freeze TikTok's experiments: off, a store goes through");
                return false;
            }
            // Asked only with the switch on, and never before the settings context, while TikTok
            // may still be starting its account service.
            String account = early ? null : SignedInUser.id();
            if (firstStart(context, early, account)) {
                markStored(context, early, account);
                Logger.printInfo(() -> "Freeze TikTok's experiments: nothing stored for this TikTok and account yet, this start stores");
                return false;
            }
            Logger.printInfo(() -> "Freeze TikTok's experiments: skipped a store");
            return true;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not decide on a server settings store", error);
            return false;
        }
    }

    /**
     * This start's answer, worked out by the first store that asks, and again when an account the
     * stamp doesn't name turns up after it. Under the lock, so a chunk store running beside the
     * whole-response store on the first start can't read the stamp the other one just saved and
     * hold its half of the response.
     */
    private static synchronized boolean firstStart(Context context, boolean early, @Nullable String account) {
        String stamp = savedStamp(context, early);
        if (firstStart != null && account != null && !account.equals(accountOf(stamp))) firstStart = null;
        if (firstStart == null) firstStart = !matches(stamp, versionOf(context), account);
        return firstStart;
    }

    /**
     * Whether [stamp] names this TikTok and account. An account not known yet matches whichever
     * the stamp names, and one the stamp didn't know doesn't, so its values come in once.
     */
    static boolean matches(String stamp, String version, @Nullable String account) {
        int slash = stamp.indexOf('/');
        if (slash < 0 || !stamp.substring(0, slash).equals(version)) return false;
        return account == null || account.equals(stamp.substring(slash + 1));
    }

    /** The account [stamp] names, empty when none was known. */
    private static String accountOf(String stamp) {
        int slash = stamp.indexOf('/');
        return slash < 0 ? "" : stamp.substring(slash + 1);
    }

    /**
     * Records which TikTok and account a store that went through came from. A null account, one
     * not known yet with the switch on, keeps the one the stamp named, since the next store asks
     * again and a different one re-decides the start; an empty one names none. Before the settings
     * context it saves into the preferences file itself, so a first start that ends before any
     * later store still counts. The stamp is read as saved, since a paused process answers every
     * setting's default and a store then goes through too.
     */
    private static void markStored(@Nullable Context context, boolean early, @Nullable String account) {
        if (context == null) return;
        String saved = savedStamp(context, early);
        String stamp = versionOf(context) + '/' + (account != null ? account : accountOf(saved));
        if (stamp.equals(saved)) return;
        if (early) {
            if (!preferences(context).edit().putString(STAMP_KEY, stamp).commit()) {
                Logger.printDebug(() -> "Freeze TikTok's experiments: could not save the stamp, the next start stores again");
            }
        } else {
            Settings.REMOTE_CONFIG_STAMP.save(stamp);
        }
    }

    private static String savedStamp(Context context, boolean early) {
        if (!early) return Settings.REMOTE_CONFIG_STAMP.savedValue();
        String stamp = preferences(context).getString(STAMP_KEY, "");
        return stamp != null ? stamp : "";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** The running TikTok's version code, or empty when the package can't be read. */
    static String versionOf(Context context) {
        String known = version;
        if (known != null) return known;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            known = String.valueOf(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (Exception unreadable) {
            Logger.printException(() -> "Freeze TikTok's experiments: could not read TikTok's version", unreadable);
            known = "";
        }
        version = known;
        return known;
    }
}
