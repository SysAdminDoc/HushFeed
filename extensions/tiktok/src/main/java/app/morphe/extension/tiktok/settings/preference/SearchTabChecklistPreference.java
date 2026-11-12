/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.DialogPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.feedfilter.SearchTabs;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Picks the search result tabs to hide from the ones TikTok has served on this phone, by the name
 * TikTok shows. The picks are keys, which stay the same in every language, and a pick the list
 * no longer holds is kept.
 */
@SuppressWarnings("deprecation")
public final class SearchTabChecklistPreference extends DialogPreference {
    private final List<SearchTabs.Entry> catalog = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private LinearLayout rows;
    private String originalPicks = "";
    private boolean selectionSaved;
    /** The unsaved picks a recreated screen hands back, while it reopens the dialog. */
    private Bundle restoring;

    public SearchTabChecklistPreference(Context context) {
        super(context);
        setKey("search_tab_checklist");
        setTitle("Hide search result tabs");
        setSummary("Pick the tabs to remove from the top of the search results, such as Shop, LIVE or Places. The list holds the tabs TikTok has shown you, and Top always stays.");
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }

    @Override
    protected View onCreateDialogView() {
        Context context = getContext();
        catalog.clear();
        catalog.addAll(SearchTabs.entries());
        // The saved value, not get(): paused, get() answers the default, and a save would wipe it.
        originalPicks = Settings.SEARCH_TAB_PICKS.savedValue();
        selected.clear();
        selected.addAll(selectedKeys(originalPicks));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        root.setPadding(padding, padding, padding, SettingsUi.dp(context, 8));

        TextView title = SettingsUi.text(context, getTitle().toString(), SettingsUi.TEXT_HEADLINE,
                SettingsUi.textPrimary(), Typeface.BOLD);
        SettingsUi.markDialogHeading(title);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView summary = SettingsUi.text(context, getSummary().toString(), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.setMargins(0, SettingsUi.dp(context, 14), 0, SettingsUi.dp(context, 10));
        root.addView(summary, summaryParams);

        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dialogListHeight(context, 300));
        scrollParams.topMargin = SettingsUi.dp(context, 10);
        root.addView(scroll, scrollParams);
        renderRows();
        return root;
    }

    private void renderRows() {
        rows.removeAllViews();
        if (catalog.isEmpty()) {
            addState("No search tabs have been seen yet",
                    "Search for something once, then return here to choose its tabs.");
            return;
        }
        for (SearchTabs.Entry entry : catalog) {
            CheckBox check = new CheckBox(getContext());
            check.setText(rowLabel(entry));
            SettingsUi.styleCheckBoxRow(check);
            check.setTag("search_tab_" + entry.key);
            check.setChecked(selected.contains(entry.key));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(entry.key);
                else selected.remove(entry.key);
            });
            rows.addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /**
     * The name TikTok served for the tab, or, for a tab served nameless (every tab on 47.7.3),
     * TikTok's name for its key in the settings language. An unknown nameless tab shows its key.
     */
    private String rowLabel(SearchTabs.Entry entry) {
        if (!entry.label.equals(entry.key)) return entry.label;
        String known = SearchTabs.knownName(entry.key);
        return known == null ? entry.label : L10n.t(getContext(), known);
    }

    private void addState(String title, String summary) {
        TextView state = SettingsUi.text(getContext(), L10n.t(getContext(), title), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        state.setPadding(SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8),
                SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8));
        rows.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView details = SettingsUi.text(getContext(), L10n.t(getContext(), summary), SettingsUi.TEXT_CAPTION,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        details.setPadding(SettingsUi.dp(getContext(), 8), 0, SettingsUi.dp(getContext(), 8),
                SettingsUi.dp(getContext(), 8));
        rows.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which)
                -> onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult && !selectionSaved) saveSelection();
        super.onDialogClosed(positiveResult);
    }

    @Override
    protected void showDialog(Bundle state) {
        selectionSaved = false;
        super.showDialog(state);
        Bundle draft = restoring;
        if (draft != null) {
            selected.clear();
            selected.addAll(DialogDraft.picks(draft));
            renderRows();
        }
        SettingsUi.styleFramedDialog(getDialog());
        AlertDialog dialog = (AlertDialog) getDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            String picks = buildPicks();
            setChoicesEnabled(dialog, false);
            boolean accepted = Utils.runOnBackgroundThread(() -> {
                boolean saved = Settings.SEARCH_TAB_PICKS.save(picks);
                Utils.runOnMainThread(() -> {
                    if (saved) notifyChanged();
                    if (getDialog() != dialog || !dialog.isShowing()) {
                        if (!saved) reportSaveFailure();
                        return;
                    }
                    setChoicesEnabled(dialog, true);
                    if (!saved) {
                        reportSaveFailure();
                        return;
                    }
                    selectionSaved = true;
                    onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                    dialog.dismiss();
                });
            });
            if (!accepted) {
                setChoicesEnabled(dialog, true);
                reportSaveFailure();
            }
        });
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        if (!DialogDraft.open(getDialog()) || rows == null) return superState;
        return new DialogDraft(superState, DialogDraft.ofPicks(selected, null));
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof DialogDraft)) {
            super.onRestoreInstanceState(state);
            return;
        }
        // DialogPreference reopens the dialog from inside this call; showDialog puts the draft back.
        restoring = ((DialogDraft) state).values;
        try {
            super.onRestoreInstanceState(((DialogDraft) state).getSuperState());
        } finally {
            restoring = null;
        }
    }

    private void setChoicesEnabled(AlertDialog dialog, boolean enabled) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(enabled);
        for (int index = 0; index < rows.getChildCount(); index++) {
            rows.getChildAt(index).setEnabled(enabled);
        }
    }

    private void reportSaveFailure() {
        Utils.showToastShort(L10n.t(getContext(), "Couldn't save these choices. Try again."));
    }

    private void saveSelection() {
        if (Settings.SEARCH_TAB_PICKS.save(buildPicks())) {
            notifyChanged();
        } else {
            reportSaveFailure();
        }
    }

    private Set<String> selectedKeys(String stored) {
        Set<String> picked = SearchTabs.picks(stored);
        Set<String> keys = new HashSet<>();
        for (SearchTabs.Entry entry : catalog) {
            if (picked.contains(entry.key)) keys.add(entry.key);
        }
        return keys;
    }

    String buildPicks() {
        Set<String> known = new HashSet<>();
        for (SearchTabs.Entry entry : catalog) known.add(entry.key);
        List<String> output = new ArrayList<>();
        for (String token : tokens(originalPicks)) {
            if (!known.contains(SearchTabs.canonical(token))) output.add(token);
        }
        for (SearchTabs.Entry entry : catalog) {
            // The key, not the name: names follow TikTok's language, keys don't.
            if (selected.contains(entry.key)) output.add(entry.key);
        }
        return String.join(", ", output);
    }

    private static List<String> tokens(String stored) {
        List<String> result = new ArrayList<>();
        if (stored == null) return result;
        for (String token : stored.split("[,\\n]")) {
            String value = token.trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }
}
