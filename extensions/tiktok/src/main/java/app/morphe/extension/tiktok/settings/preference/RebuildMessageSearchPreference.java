/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.inbox.MessageSearchRebuild;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Clears TikTok's message search index and has TikTok index every message saved on the phone
 * again, with the search page closed. See {@link MessageSearchRebuild}. The banner that says it
 * started offers Stop.
 */
@SuppressWarnings("deprecation")
public final class RebuildMessageSearchPreference extends Preference implements ImmediateAction {
    static final String TITLE = "Rebuild message search";

    @Override public boolean actsOnTap() {
        return true;
    }

    public RebuildMessageSearchPreference(Context context) {
        super(context);
        // A key so the settings search can index this row; no Setting has it.
        setKey("action_rebuild_message_search");
        setTitle(L10n.t(context, TITLE));
        setSummary(L10n.t(context,
                "Clears TikTok's message search and adds back every message saved on this phone, newest first. It keeps going after you leave search."));
        setOnPreferenceClickListener(preference -> {
            setEnabled(false);
            boolean queued = MessageSearchRebuild.rebuild(outcome -> {
                setEnabled(true);
                report(context, outcome);
            });
            if (!queued) {
                setEnabled(true);
                SettingsActionBanner.showNotice(context, L10n.t(context, "Couldn't rebuild search. Try again."));
            }
            return true;
        });
    }

    static void report(Context context, int outcome) {
        if (outcome == MessageSearchRebuild.STARTED) {
            SettingsActionBanner.showAction(context,
                    L10n.t(context, "Rebuilding message search"),
                    L10n.t(context, "Stop"),
                    () -> {
                        if (MessageSearchRebuild.stop()) {
                            SettingsActionBanner.showNotice(context, L10n.t(context, "Stopped. Search carries on later."));
                        }
                    },
                    L10n.t(context, "Couldn't stop it. Try again."));
        } else if (outcome == MessageSearchRebuild.FAILED) {
            SettingsActionBanner.showNotice(context, L10n.t(context, "Couldn't rebuild search. Try again."));
        } else {
            SettingsActionBanner.showNotice(context, L10n.t(context, "Message search isn't on for this account"));
        }
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}
