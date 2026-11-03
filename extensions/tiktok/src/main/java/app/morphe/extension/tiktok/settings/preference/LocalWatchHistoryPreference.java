/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.Preference;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Opens the watch history kept on the phone (#172): a search box over the videos, newest first. A
 * tap opens the video. Thumbnails aren't kept, since TikTok's links to them run out.
 */
@SuppressWarnings("deprecation")
public final class LocalWatchHistoryPreference extends Preference {
    /** A stable key, so the settings search can find the row. */
    public static final String KEY = "action_local_watch_history";

    /** The app's own link to a video. */
    private static final String VIDEO_LINK = "snssdk1233://aweme/detail/";

    public LocalWatchHistoryPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(L10n.t(context, "My watch history"));
        setSummary(L10n.t(context, "Find and reopen the videos you've watched on this phone"));
        setOnPreferenceClickListener(preference -> {
            show(context);
            return true;
        });
    }

    private static void show(Context context) {
        if (SettingsUi.dialogOpen(KEY, context)) return;
        int gap = SettingsUi.dp(context, 20);

        EditText search = new EditText(context);
        search.setHint(L10n.t(context, "Search"));
        search.setSingleLine(true);
        SettingsUi.styleEditText(search);

        TextView empty = SettingsUi.text(context, L10n.t(context, "Videos you watch show up here"),
                SettingsUi.TEXT_BODY_SMALL, SettingsUi.textSecondary(), Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(gap, gap, gap, gap);
        empty.setVisibility(View.GONE);

        Rows rows = new Rows(context);
        ListView list = new ListView(context);
        list.setAdapter(rows);
        list.setDivider(null);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(gap, 0, gap, 0);
        page.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dialogListHeight(context, 420)));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t(context, "My watch history"))
                .setView(page)
                .setPositiveButton(L10n.t(context, "Close"), (target, which) -> target.dismiss())
                .setNeutralButton(L10n.t(context, "Clear"), null)
                .create();
        dialog.show();
        SettingsUi.dialogOpened(KEY, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);

        TextView heading = dialog.findViewById(android.R.id.title);
        if (heading != null) {
            heading.setGravity(Gravity.START);
            SettingsUi.markDialogHeading(heading);
        }

        View.OnClickListener[] clearOrUndo = new View.OnClickListener[1];
        Button clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        Runnable refresh = () -> {
            List<LocalWatchHistory.Entry> shown = LocalWatchHistory.filter(rows.all, search.getText().toString());
            rows.show(shown);
            boolean nothing = shown.isEmpty();
            list.setVisibility(nothing ? View.GONE : View.VISIBLE);
            empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
            empty.setText(L10n.t(context, rows.all.isEmpty() ? "Videos you watch show up here" : "No videos match"));
        };
        // A clear happens at once and stays a tap away from coming back while the list is open.
        clearOrUndo[0] = view -> LocalWatchHistory.clear(removed -> {
            rows.all = Collections.emptyList();
            refresh.run();
            Utils.showToastShort(L10n.t(context, "Watch history cleared"));
            clear.setText(L10n.t(context, "Undo"));
            clear.setOnClickListener(undo -> {
                LocalWatchHistory.restore(removed);
                rows.all = removed;
                refresh.run();
                clear.setText(L10n.t(context, "Clear"));
                clear.setOnClickListener(clearOrUndo[0]);
            });
        });
        clear.setOnClickListener(clearOrUndo[0]);

        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            open(context, rows.shown.get(position).aid);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                refresh.run();
            }
        });
        LocalWatchHistory.load(loaded -> {
            rows.all = loaded;
            refresh.run();
        });
    }

    private static void open(Context context, String aid) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO_LINK + aid))
                    .setPackage(context.getPackageName());
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open a video from the watch history", ex);
            Utils.showToastShort(L10n.t(context, "Couldn't open that video. Try again."));
        }
    }

    private static final class Rows extends BaseAdapter {
        private final Context context;
        List<LocalWatchHistory.Entry> all = Collections.emptyList();
        List<LocalWatchHistory.Entry> shown = Collections.emptyList();

        Rows(Context context) {
            this.context = context;
        }

        void show(List<LocalWatchHistory.Entry> rows) {
            shown = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reused, ViewGroup parent) {
            LinearLayout row = (LinearLayout) reused;
            if (row == null) {
                int gap = SettingsUi.dp(context, 10);
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, gap, 0, gap);
                row.addView(SettingsUi.text(context, "", SettingsUi.TEXT_BODY,
                        SettingsUi.textPrimary(), Typeface.BOLD));
                TextView caption = SettingsUi.text(context, "", SettingsUi.TEXT_BODY_SMALL,
                        SettingsUi.textSecondary(), Typeface.NORMAL);
                caption.setMaxLines(2);
                caption.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(caption);
            }
            LocalWatchHistory.Entry entry = shown.get(position);
            CharSequence when = DateUtils.getRelativeTimeSpanString(
                    entry.watchedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            TextView title = (TextView) row.getChildAt(0);
            title.setText(entry.creator.isEmpty() ? when : entry.creator + " · " + when);
            TextView caption = (TextView) row.getChildAt(1);
            caption.setText(entry.caption);
            caption.setVisibility(entry.caption.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
