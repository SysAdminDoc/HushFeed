/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.preference.ListPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.misc.AppIcons;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * The App icon row (Discussion #114): a list of the home screen icons the patch put in, each
 * with its picture, and a tap switches to it. Nothing is saved here. The icon that's on is the
 * answer, read back from Android each time the row is drawn.
 */
@SuppressWarnings("deprecation")
public final class AppIconPreference extends ListPreference {
    static final String TITLE = "App icon";
    static final String NOTE = "Some home screens drop the icon when it changes. If yours does, "
            + "add TikTok back from the app list.";

    private static final int ICON_DP = 36;

    public AppIconPreference(Context context) {
        super(context);
        setKey("hushfeed_app_icon");
        setPersistent(false);
        super.setTitle(L10n.t(context, TITLE));
        super.setDialogTitle(L10n.t(context, TITLE));
        setNegativeButtonText(L10n.t(context, "Cancel"));
        showCurrent(AppIcons.list(context));
    }

    /** The icon that's on, then the note, so the row says what a tap would change. */
    private void showCurrent(List<AppIcons.Icon> icons) {
        Context context = getContext();
        List<CharSequence> labels = new ArrayList<>();
        List<CharSequence> keys = new ArrayList<>();
        for (AppIcons.Icon icon : icons) {
            labels.add(L10n.t(context, icon.label));
            keys.add(icon.key);
        }
        setEntries(labels.toArray(new CharSequence[0]));
        setEntryValues(keys.toArray(new CharSequence[0]));
        AppIcons.Icon current = AppIcons.current(icons);
        String note = L10n.t(context, NOTE);
        // A percent sign in a label would be read as a format by ListPreference's summary.
        super.setSummary(current == null ? note
                : L10n.t(context, current.label).replace("%", "%%") + "\n" + note);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        Context context = getContext();
        List<AppIcons.Icon> icons = AppIcons.list(context);
        PackageManager packages = context.getPackageManager();
        int size = SettingsUi.dp(context, ICON_DP);
        List<Drawable> pictures = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int checked = -1;
        for (int i = 0; i < icons.size(); i++) {
            AppIcons.Icon icon = icons.get(i);
            Drawable picture = null;
            try {
                picture = icon.load(packages);
                picture.setBounds(0, 0, size, size);
            } catch (RuntimeException unreadable) {
                Logger.printException(() -> "Could not load the " + icon.key + " icon", unreadable);
            }
            pictures.add(picture);
            labels.add(L10n.t(context, icon.label));
            if (icon.enabled) checked = i;
        }
        ArrayAdapter<String> rows = new ArrayAdapter<String>(context,
                android.R.layout.select_dialog_singlechoice, android.R.id.text1, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                if (row instanceof TextView) {
                    TextView text = (TextView) row;
                    Drawable[] drawables = text.getCompoundDrawablesRelative();
                    text.setCompoundDrawablesRelative(drawables[0], drawables[1], pictures.get(position), drawables[3]);
                }
                return row;
            }
        };
        builder.setSingleChoiceItems(rows, checked, (dialog, which) -> {
            dialog.dismiss();
            pick(icons.get(which));
        });
        builder.setPositiveButton(null, null);
    }

    private void pick(AppIcons.Icon icon) {
        Context context = getContext();
        if (icon.enabled) return;
        try {
            if (!AppIcons.pick(context, icon.key)) throw new IllegalStateException("no icon " + icon.key);
            Utils.showToastShort(L10n.t(context, "Icon changed"));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not switch to the " + icon.key + " icon", failure);
            Utils.showToastLong(L10n.t(context, "Couldn't change the icon. Try again."));
        }
        showCurrent(AppIcons.list(context));
    }

    @Override
    protected void showDialog(android.os.Bundle state) {
        super.showDialog(state);
        SettingsUi.styleStandardAlertDialog((AlertDialog) getDialog());
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        // The pick is made from the list itself; there's no value to save.
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }
}
