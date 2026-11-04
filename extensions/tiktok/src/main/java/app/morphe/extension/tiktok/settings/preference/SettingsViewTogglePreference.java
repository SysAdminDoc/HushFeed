/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;

import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The home page's row under the search that switches between pages folded under their rows and
 * every row in one list. It names the view a tap gives, not the one showing, and a tap switches
 * in place, so it gets no chevron.
 */
@SuppressWarnings("deprecation")
public final class SettingsViewTogglePreference extends Preference implements ImmediateAction {
    public static final String KEY = "hushfeed_view_toggle";

    public SettingsViewTogglePreference(Context context, OnPreferenceClickListener listener) {
        super(context);
        setKey(KEY);
        setPersistent(false);
        setOnPreferenceClickListener(listener);
        // After the search (-900) and before every row of the page (10000 and on).
        setOrder(-850);
        refresh();
    }

    /** Puts the title and the icon in step with the saved view. */
    public void refresh() {
        Context context = getContext();
        boolean list = Settings.SETTINGS_AS_LIST.savedValue();
        setTitle(L10n.t(context, list ? "Show settings by category" : "Show all settings as a list"));
        setIcon(SettingsIcons.icon(context, list ? SettingsIcons.FEED : SettingsIcons.MENU,
                ScreenColors.current().heading));
    }

    @Override public boolean actsOnTap() {
        return true;
    }
}
