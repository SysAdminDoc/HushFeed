/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.privacy.AccountCleanup;
import app.morphe.extension.tiktok.privacy.AccountCleanupSources.Comment;
import app.morphe.extension.tiktok.privacy.AccountCleanupSources.Comments;
import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Remove selected comments (#442): the comments you've posted, newest first, to tick and delete.
 * Remove closes the list and starts the same countdown with Cancel the other removals use.
 */
@SuppressWarnings("deprecation")
public final class CommentPickerPreference extends Preference {
    public static final String KEY = "action_pick_comments";

    public CommentPickerPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(L10n.t(context, "Remove selected comments"));
        setSummary(L10n.t(context, "Pick comments from your comment history to delete."));
        setOnPreferenceClickListener(preference -> {
            show(context);
            return true;
        });
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    static void show(Context context) {
        if (SettingsUi.dialogOpen(KEY, context)) return;
        if (SignedInUser.id() == null) {
            SettingsActionBanner.showNotice(context, L10n.t(context, "Sign in to TikTok first"));
            return;
        }
        int gap = SettingsUi.dp(context, 20);
        int height = SettingsUi.dialogListHeight(context, 420);

        TextView state = SettingsUi.text(context, L10n.t(context, "Loading your comments"),
                SettingsUi.TEXT_BODY_SMALL, SettingsUi.textSecondary(), Typeface.NORMAL);
        state.setTag("hushfeed_comment_picker_state");
        state.setGravity(Gravity.CENTER);
        state.setPadding(gap, gap, gap, gap);

        Rows rows = new Rows(context);
        ListView list = new ListView(context);
        list.setTag("hushfeed_comment_picker_list");
        list.setAdapter(rows);
        list.setDivider(null);
        list.setVisibility(View.GONE);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(gap, 0, gap, 0);
        // The same height as the list, so the dialog doesn't jump once the comments come in.
        page.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t(context, "Remove selected comments"))
                .setView(page)
                .setPositiveButton(L10n.t(context, "Remove"), null)
                .setNegativeButton(L10n.t(context, "Cancel"), (target, which) -> target.dismiss())
                .setNeutralButton(L10n.t(context, "Load more"), null)
                .create();
        dialog.show();
        SettingsUi.dialogOpened(KEY, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);
        TextView heading = dialog.findViewById(android.R.id.title);
        if (heading != null) {
            heading.setGravity(Gravity.START);
            SettingsUi.markDialogHeading(heading);
        }

        Button remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button more = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        Runnable refresh = () -> {
            boolean nothing = rows.comments.isEmpty();
            list.setVisibility(nothing ? View.GONE : View.VISIBLE);
            state.setVisibility(nothing ? View.VISIBLE : View.GONE);
            remove.setEnabled(!rows.picked.isEmpty());
            more.setVisibility(rows.loading || !rows.more ? View.GONE : View.VISIBLE);
        };
        rows.changed = refresh;
        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            rows.loading = true;
            refresh.run();
            AccountCleanup.loadComments(rows.next, new AccountCleanup.CommentsLoaded() {
                @Override
                public void loaded(Comments page) {
                    rows.loading = false;
                    rows.comments.addAll(page.comments);
                    // A list that doesn't move on has nothing more to give.
                    rows.more = page.more && page.next != rows.next;
                    rows.next = page.next;
                    // A page can hold only comments TikTok took down, which aren't listed here.
                    if (page.comments.isEmpty() && rows.more && ++rows.emptyPages < Rows.EMPTY_PAGES) {
                        load[0].run();
                        return;
                    }
                    rows.emptyPages = 0;
                    if (rows.comments.isEmpty()) state.setText(L10n.t(context, "No comments to show"));
                    rows.notifyDataSetChanged();
                    refresh.run();
                }

                @Override
                public void failed(End end) {
                    rows.loading = false;
                    // Load more stays, so a page that didn't come can be asked for again.
                    rows.more = end == End.NETWORK || end == End.REFUSED;
                    String why = AccountCleanup.loadFailedText(context, end);
                    if (rows.comments.isEmpty()) state.setText(why);
                    else SettingsActionBanner.showNotice(context, why);
                    refresh.run();
                }
            });
        };
        more.setOnClickListener(view -> load[0].run());
        remove.setOnClickListener(view -> {
            List<String> picked = new ArrayList<>(rows.picked);
            if (picked.isEmpty()) return;
            dialog.dismiss();
            AccountCleanup.startPicked(context, picked);
        });
        list.setOnItemClickListener((parent, view, position, id) -> rows.toggle(position));
        load[0].run();
    }

    /** The comments read so far, each a row with a tick. */
    static final class Rows extends BaseAdapter {
        /** Pages in a row with nothing to list before the picker stops asking on its own. */
        static final int EMPTY_PAGES = 5;
        private final Context context;
        final List<Comment> comments = new ArrayList<>();
        /** Picked cids, in the order they were ticked. */
        final Set<String> picked = new LinkedHashSet<>();
        long next;
        boolean more;
        boolean loading;
        int emptyPages;
        Runnable changed = () -> { };

        Rows(Context context) {
            this.context = context;
        }

        void toggle(int position) {
            String cid = comments.get(position).cid;
            if (!picked.remove(cid)) picked.add(cid);
            notifyDataSetChanged();
            changed.run();
        }

        @Override
        public int getCount() {
            return comments.size();
        }

        @Override
        public Object getItem(int position) {
            return comments.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reused, ViewGroup parent) {
            LinearLayout row = (LinearLayout) reused;
            if (row == null) {
                int gap = SettingsUi.dp(context, 8);
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, gap, 0, gap);
                CheckBox check = new CheckBox(context);
                SettingsUi.styleCheckBoxRow(check);
                check.setMaxLines(3);
                check.setEllipsize(TextUtils.TruncateAt.END);
                // The row takes the tap, so the list's own click and the tick never disagree.
                check.setClickable(false);
                check.setFocusable(false);
                row.addView(check);
                TextView when = SettingsUi.text(context, "", SettingsUi.TEXT_CAPTION,
                        SettingsUi.textSecondary(), Typeface.NORMAL);
                when.setPaddingRelative(SettingsUi.dp(context, 32), 0, 0, 0);
                row.addView(when);
            }
            Comment comment = comments.get(position);
            CheckBox check = (CheckBox) row.getChildAt(0);
            check.setText(comment.text.isEmpty() ? L10n.t(context, "No text") : comment.text);
            check.setChecked(picked.contains(comment.cid));
            row.setTag("hushfeed_comment_" + comment.cid);
            TextView when = (TextView) row.getChildAt(1);
            if (comment.postedSeconds > 0) {
                long now = System.currentTimeMillis();
                when.setText(DateUtils.getRelativeTimeSpanString(
                        Math.min(comment.postedSeconds * 1000L, now - DateUtils.MINUTE_IN_MILLIS), now,
                        DateUtils.MINUTE_IN_MILLIS));
                when.setVisibility(View.VISIBLE);
            } else {
                when.setVisibility(View.GONE);
            }
            return row;
        }
    }
}
