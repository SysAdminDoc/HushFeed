/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
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
 * <p>A freeze turned on before TikTok ever stored a value would leave it on its built-in
 * defaults, which no TikTok anyone uses runs on. So a store that goes through marks
 * {@link Settings#REMOTE_CONFIG_STORED}, and the first start with the freeze on and that unset
 * lets every store of that start through. The answer for that start is decided once, so the
 * chunks of one response are never half kept.
 */
@SuppressWarnings("unused")
public final class RemoteConfigFreeze {
    /** {@link Settings#FREEZE_REMOTE_CONFIG}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "freeze_remote_config";
    /** {@link Settings#REMOTE_CONFIG_STORED}'s key, for the same read. */
    static final String STORED_KEY = "remote_config_stored";

    /** Whether this start lets every store through, decided by its first store with the switch on. */
    @Nullable
    static Boolean firstStart;

    private RemoteConfigFreeze() {
    }

    /** True while TikTok's server settings store should return before writing anything. */
    public static boolean holdsStore() {
        if (!SettingsStatus.remoteConfigFreezeEnabled) return false;
        try {
            boolean early = Utils.getContext() == null;
            boolean on = early ? EarlySwitch.isOn(SWITCH_KEY) : Settings.FREEZE_REMOTE_CONFIG.get();
            if (!on) {
                markStored(early);
                Logger.printDebug(() -> "Freeze TikTok's experiments: off, a store goes through");
                return false;
            }
            if (firstStart(early)) {
                markStored(early);
                Logger.printInfo(() -> "Freeze TikTok's experiments: nothing stored yet, this start stores");
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
     * This start's answer, worked out by the first store that asks. Under the lock, so a chunk
     * store running beside the whole-response store on the first start can't read the flag the
     * other one just saved and hold its half of the response.
     */
    private static synchronized boolean firstStart(boolean early) {
        if (firstStart == null) {
            firstStart = !(early ? EarlySwitch.isOn(STORED_KEY) : Settings.REMOTE_CONFIG_STORED.savedValue());
        }
        return firstStart;
    }

    /**
     * Records that a store went through, once the settings context can save it. The flag is read
     * as saved, since a paused process answers every setting's default and a store then goes
     * through too.
     */
    private static void markStored(boolean early) {
        if (early || Settings.REMOTE_CONFIG_STORED.savedValue()) return;
        Settings.REMOTE_CONFIG_STORED.save(true);
    }
}
