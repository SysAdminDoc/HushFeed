/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * OPPO's push service on OPPO, OnePlus and realme phones (#496). Global TikTok carries OPPO's
 * HeyTap push next to Google's, and on a phone with HeyTap it registers both and tells its server
 * it can take OPPO's. OPPO's service checks the app against the signature TikTok registered with
 * OPPO, which a patched TikTok can't have, so whatever the server sends that way never arrives.
 *
 * <p>TikTok's OPPO adapter asks here first, both when TikTok lists the push services this phone
 * has and before it registers with OPPO. Answering yes makes the adapter say OPPO's service isn't
 * there and register nothing, so the phone lists the services any other Android phone does and
 * TikTok's notifications come through Google's push, which a patched TikTok gets. Phones without
 * HeyTap already get no from TikTok's own check, so nothing changes on them. Pause leaves the
 * adapter to TikTok.
 */
public final class OppoPush {
    static final String FAMILY = "OPPO push";

    private OppoPush() {
    }

    /** Whether TikTok's OPPO push adapter answers as if this phone had no OPPO push service. */
    public static boolean skipOppoPush() {
        boolean skip = !Setting.isPaused();
        HookStatus.bound(FAMILY, skip ? "reported missing, Google push used" : "left to TikTok");
        return skip;
    }
}
