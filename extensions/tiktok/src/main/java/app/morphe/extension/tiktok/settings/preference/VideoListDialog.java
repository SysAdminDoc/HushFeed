/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
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

import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;

/**
 * A list of videos kept on the phone, newest first, with a search box over them and a Clear that
 * stays a tap away from coming back while the list is open. A tap opens the video over the list,
 * so Back returns to it with the search still typed. Thumbnails aren't kept, since TikTok's links
 * to them run out. My watch history (#172) and the likes Ghost mode keeps on the phone use it.
 */
final class VideoListDialog {
    /** Where a list's videos come from, and what it says. */
    interface Source {
        /** The list's title, translated. */
        String title(Context context);

        /** What the list says before it has any videos, translated. */
        String empty(Context context);

        /** Tells the reader the list was cleared. */
        void cleared(Context context);

        void load(LocalWatchHistory.Listener listener);

        void clear(LocalWatchHistory.Listener listener);

        void restore(List<LocalWatchHistory.Entry> rows);
    }

    private VideoListDialog() {
    }

    /**
     * Shows the list. Over TikTok's feed it carries a button to these settings, since the press
     * that opened it is otherwise a way into them.
     */
    static void show(Context context, String key, Source source, boolean overFeed) {
        if (SettingsUi.dialogOpen(key, context)) return;
        int gap = SettingsUi.dp(context, 20);
        int height = SettingsUi.dialogListHeight(context, 420);

        EditText search = new EditText(context);
        search.setHint(L10n.t(context, "Search"));
        search.setSingleLine(true);
        SettingsUi.styleEditText(search);

        TextView empty = SettingsUi.text(context, source.empty(context),
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
        // The same height as the list, so the dialog doesn't jump as a search empties it.
        page.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        page.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(source.title(context))
                .setView(page)
                .setPositiveButton(L10n.t(context, "Close"), (target, which) -> target.dismiss())
                .setNeutralButton(L10n.t(context, "Clear"), null);
        if (overFeed) {
            builder.setNegativeButton(L10n.t(context, "Open Hushfeed settings"), (target, which) -> {
                target.dismiss();
                TikTokActivityHook.openSettings();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.show();
        SettingsUi.dialogOpened(key, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);

        TextView heading = dialog.findViewById(android.R.id.title);
        if (heading != null) {
            heading.setGravity(Gravity.START);
            SettingsUi.markDialogHeading(heading);
        }

        View.OnClickListener[] clearOrUndo = new View.OnClickListener[1];
        Button clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        boolean[] undoing = new boolean[1];
        Runnable refresh = () -> {
            List<LocalWatchHistory.Entry> shown = LocalWatchHistory.filter(rows.all, search.getText().toString());
            rows.show(shown);
            boolean nothing = shown.isEmpty();
            list.setVisibility(nothing ? View.GONE : View.VISIBLE);
            empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
            empty.setText(rows.all.isEmpty() ? source.empty(context) : L10n.t(context, "No videos match"));
            clear.setVisibility(undoing[0] || !rows.all.isEmpty() ? View.VISIBLE : View.GONE);
        };
        // A clear happens at once and stays a tap away from coming back while the list is open.
        clearOrUndo[0] = view -> source.clear(removed -> {
            undoing[0] = true;
            rows.all = Collections.emptyList();
            refresh.run();
            source.cleared(context);
            clear.setText(L10n.t(context, "Undo"));
            clear.setOnClickListener(undo -> {
                source.restore(removed);
                undoing[0] = false;
                rows.all = removed;
                refresh.run();
                clear.setText(L10n.t(context, "Clear"));
                clear.setOnClickListener(clearOrUndo[0]);
            });
        });
        clear.setOnClickListener(clearOrUndo[0]);

        list.setOnItemClickListener((parent, view, position, id) ->
                LocalWatchHistoryPreference.open(context, rows.shown.get(position).aid));
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
        source.load(loaded -> {
            rows.all = loaded;
            refresh.run();
        });
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
                // The search box's text sits a little inside its own edge.
                row.setPaddingRelative(SettingsUi.dp(context, 4), gap, 0, gap);
                row.addView(SettingsUi.text(context, "", SettingsUi.TEXT_BODY,
                        SettingsUi.textPrimary(), Typeface.BOLD));
                TextView caption = SettingsUi.text(context, "", SettingsUi.TEXT_BODY_SMALL,
                        SettingsUi.textSecondary(), Typeface.NORMAL);
                caption.setMaxLines(2);
                caption.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(caption);
            }
            LocalWatchHistory.Entry entry = shown.get(position);
            // Never younger than a minute, which would read "0 minutes ago".
            long now = System.currentTimeMillis();
            CharSequence when = DateUtils.getRelativeTimeSpanString(
                    Math.min(entry.watchedMs, now - DateUtils.MINUTE_IN_MILLIS), now,
                    DateUtils.MINUTE_IN_MILLIS);
            TextView title = (TextView) row.getChildAt(0);
            title.setText(entry.creator.isEmpty() ? when : entry.creator + " · " + when);
            TextView caption = (TextView) row.getChildAt(1);
            caption.setText(entry.caption);
            caption.setVisibility(entry.caption.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
