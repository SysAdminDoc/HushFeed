/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.privacy.AccountCleanup;
import app.morphe.extension.tiktok.privacy.AccountCleanupSources.Kind;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * One of the Privacy page's removals (#332). A tap starts a short countdown with Cancel rather
 * than asking first; the removal then runs in the background with a count and Stop.
 */
@SuppressWarnings("deprecation")
public final class AccountCleanupPreference extends Preference implements ImmediateAction {
    static final String REPOSTS_KEY = "action_remove_reposts";
    static final String LIKES_KEY = "action_remove_likes";
    static final String FAVORITES_KEY = "action_clear_favorites";

    @Override public boolean actsOnTap() {
        return true;
    }

    public AccountCleanupPreference(Context context, Kind kind) {
        super(context);
        // A key so the settings search can index this row; no Setting has it.
        switch (kind) {
            case REPOSTS:
                setKey(REPOSTS_KEY);
                setTitle(L10n.t(context, "Remove all reposts"));
                setSummary(L10n.t(context, "Deletes all reposts from your account sequentially."));
                break;
            case LIKES:
                setKey(LIKES_KEY);
                setTitle(L10n.t(context, "Remove all likes"));
                setSummary(L10n.t(context, "Unlikes every video one after another."));
                break;
            default:
                setKey(FAVORITES_KEY);
                setTitle(L10n.t(context, "Clear Favorites"));
                setSummary(L10n.t(context, "Removes every video from Favorites one after another."));
                break;
        }
        setOnPreferenceClickListener(preference -> {
            AccountCleanup.start(context, kind);
            return true;
        });
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }
}
