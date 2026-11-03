/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.view.View;

import app.morphe.extension.tiktok.privacy.DeviceIdentity;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Arms a new device identity for the next start, and a second tap takes it back. Arming owes a
 * restart, so the restart row appears at once, and the wipe itself runs as TikTok starts again
 * ({@link DeviceIdentity#start}). No dialog: the row says what it will do, and what's armed can
 * be disarmed until the restart.
 *
 * <p>This isn't a switch, and a reader took it for one that's always on (#325), so the row says it
 * only runs when tapped and whether an identity is in use. With one in use a second row of this
 * class goes back to the phone's own ids, armed and undone the same way. Only one can be armed.
 */
@SuppressWarnings("deprecation")
public final class DeviceIdentityPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    static final String KEY = "action_new_device_identity";
    static final String TITLE = "New device identity";
    static final String SUMMARY = "Off until you tap it. Wipes TikTok's data once, on the next start, and "
            + "gives it a new Android ID and advertising ID, so it sees a phone it hasn't met. You'll be "
            + "signed out and lose your drafts. Hushfeed's settings stay.";
    static final String IN_USE_SUMMARY = "In use: TikTok sees the identity you made here, and it stays the "
            + "same on every start. Tap to make another one, which wipes TikTok's data again.";
    static final String ARMED_SUMMARY = "TikTok starts as a new phone after the restart. Tap again to cancel.";
    static final String GAVE_UP_SUMMARY = "Couldn't finish wiping TikTok's data. Tap to try again.";
    static final String FAILED = "Couldn't set up a new device identity. Try again.";

    static final String OWN_IDS_KEY = "action_own_device_ids";
    static final String OWN_IDS_TITLE = "Use this phone's own IDs";
    static final String OWN_IDS_SUMMARY = "Wipes TikTok's data once, on the next start, and goes back to this "
            + "phone's real Android ID and advertising ID. You'll be signed out and lose your drafts.";
    static final String OWN_IDS_ARMED_SUMMARY = "TikTok goes back to this phone's IDs after the restart. "
            + "Tap again to cancel.";

    private final boolean ownIds;

    public DeviceIdentityPreference(Context context) {
        this(context, false);
    }

    /** The row back to the phone's own ids, for a phone with an identity in use. */
    public static DeviceIdentityPreference ownIds(Context context) {
        return new DeviceIdentityPreference(context, true);
    }

    private DeviceIdentityPreference(Context context, boolean ownIds) {
        super(context);
        this.ownIds = ownIds;
        setKey(ownIds ? OWN_IDS_KEY : KEY);
        setPersistent(false);
        setTitle(ownIds ? OWN_IDS_TITLE : TITLE);
        applyState();
        setOnPreferenceClickListener(preference -> {
            toggle(getContext());
            return true;
        });
    }

    private void toggle(Context context) {
        boolean arm = !(ownIds ? DeviceIdentity.isOwnIdsArmed(context) : DeviceIdentity.isArmed(context));
        boolean saved = ownIds ? DeviceIdentity.setOwnIdsArmed(context, arm) : DeviceIdentity.setArmed(context, arm);
        if (!saved) {
            SettingsActionBanner.showNotice(context, L10n.t(context, FAILED));
            return;
        }
        if (arm) {
            TikTokPreferenceFragment.noteFileRestartPending(KEY);
        } else {
            TikTokPreferenceFragment.noteFileRestartSettled(KEY);
        }
        applyState();
        // Arming one row cancels the other, whose summary would still say it was armed.
        PreferenceManager manager = getPreferenceManager();
        Preference other = manager == null ? null : manager.findPreference(ownIds ? KEY : OWN_IDS_KEY);
        if (other instanceof DeviceIdentityPreference) ((DeviceIdentityPreference) other).applyState();
    }

    private void applyState() {
        Context context = getContext();
        if (ownIds) {
            setSummary(DeviceIdentity.isOwnIdsArmed(context) ? OWN_IDS_ARMED_SUMMARY : OWN_IDS_SUMMARY);
        } else if (DeviceIdentity.isArmed(context)) {
            setSummary(ARMED_SUMMARY);
        } else if (DeviceIdentity.gaveUp(context)) {
            setSummary(GAVE_UP_SUMMARY);
        } else {
            setSummary(DeviceIdentity.hasIdentity(context) ? IN_USE_SUMMARY : SUMMARY);
        }
    }

    @Override
    protected void onBindView(View view) {
        applyState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }

    @Override public void setTitle(CharSequence title) { super.setTitle(L10n.t(getContext(), title)); }
    @Override public void setSummary(CharSequence summary) { super.setSummary(L10n.t(getContext(), summary)); }
}
