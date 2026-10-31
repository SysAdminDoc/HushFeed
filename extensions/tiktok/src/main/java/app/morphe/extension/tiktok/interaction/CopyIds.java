/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Copies a profile's bio on a long press, and adds copy buttons to the share sheet: a profile's
 * username and numeric user ID, or a video's ID.
 *
 * <p>The bio comes from the profile header's signature item, which hands its text view the
 * signature it is about to show. The share sheet's ids come from the package TikTok builds the
 * sheet from: a profile's package holds the account's {@code User} in {@code user}, a video's
 * the {@code Aweme} in {@code aweme}. The buttons are a row of ours at the bottom of TikTok's
 * action panel, a FrameLayout that holds just the action list, which is moved up by the row's
 * height. The profile sheet from Settings and privacy has no action panel, only Send to and the
 * apps, so there the row goes at the bottom of the apps panel the same way. A member that's
 * missing leaves the bio and the sheet as TikTok made them.
 */
public final class CopyIds {
    static final String BIO_COPIED = "Bio copied";
    static final String USERNAME_COPIED = "Username copied";
    static final String USER_ID_COPIED = "User ID copied";
    static final String VIDEO_ID_COPIED = "Video ID copied";
    static final String NAME_COPIED = "Name copied";
    static final String COPY_USERNAME = "Copy username";
    static final String COPY_USER_ID = "Copy user ID";
    static final String COPY_VIDEO_ID = "Copy video ID";
    /** Marks the button row this class adds, so a reattached panel reuses it. */
    static final String ROW_TAG = "hushfeed_copy_ids";

    /** The signature each bio view was last bound with. Main thread only. */
    private static final Map<View, String> BIOS = new WeakHashMap<>();
    /** The bottom margin each moved panel child had before the row took its place. */
    private static final Map<View, Integer> ORIGINAL_MARGINS = new WeakHashMap<>();
    private static volatile WeakReference<Object> sharePackage = new WeakReference<>(null);
    /** The panels last attached; a sheet with both shows the row in the action panel. Main thread only. */
    private static WeakReference<FrameLayout> actionPanel = new WeakReference<>(null);
    private static WeakReference<FrameLayout> appsPanel = new WeakReference<>(null);

    private static final View.OnLongClickListener BIO_LISTENER = CopyIds::copyBio;

    private CopyIds() {
    }

    static boolean enabled() {
        return SettingsStatus.copyIdsEnabled && Settings.COPY_IDS.get();
    }

    /**
     * One button on the sheet: its text, and either what goes on the clipboard with its toast or
     * what a tap does instead.
     */
    static final class Target {
        final String label;
        @Nullable final String text;
        @Nullable final String toast;
        @Nullable final Runnable action;

        Target(String label, String text, String toast) {
            this.label = label;
            this.text = text;
            this.toast = toast;
            this.action = null;
        }

        Target(String label, Runnable action) {
            this.label = label;
            this.text = null;
            this.toast = null;
            this.action = action;
        }
    }

    // ---- bio ---------------------------------------------------------------------------

    /** From the bio item's bind, with its text view and the signature it's about to show. */
    public static void onBio(@Nullable View view, @Nullable String bio) {
        try {
            if (!(view instanceof TextView)) return;
            BioMentions.onBio((TextView) view);
            if (!enabled()) return;
            BIOS.put(view, bio);
            view.setOnLongClickListener(BIO_LISTENER);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not make the bio copyable", ex);
        }
    }

    static boolean copyBio(View view) {
        try {
            if (!enabled()) return false;
            String bio = BIOS.get(view);
            if (isBlank(bio) && view instanceof TextView) {
                CharSequence shown = ((TextView) view).getText();
                bio = shown == null ? null : shown.toString();
            }
            if (isBlank(bio)) return false;
            copy("TikTok bio", bio.trim(), BIO_COPIED);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not copy the bio", ex);
            return false;
        }
    }

    // ---- LIVE profile card -------------------------------------------------------------

    /**
     * The viewer or host the LIVE profile card is showing, set as the card binds its user. The
     * card's views are handed over right after, so the texts they get can be told apart.
     */
    private static volatile WeakReference<Object> liveUser = new WeakReference<>(null);
    /** What each LIVE card view copies on a long press. Main thread only. */
    private static final Map<View, String[]> LIVE_COPIES = new WeakHashMap<>();
    private static final View.OnLongClickListener LIVE_LISTENER = CopyIds::copyLive;

    /** Bidi marks the card wraps its texts in, which a copied bio doesn't want. */
    private static String clean(CharSequence text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean mark = c == 0x200e || c == 0x200f || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069);
            if (!mark) out.append(c);
        }
        return out.toString().trim();
    }

    /** From the card's user bind, with the user, before the card puts its name and username on screen. */
    public static void onLiveUser(@Nullable Object user) {
        liveUser = new WeakReference<>(user);
    }

    /** From the card's bio, with the view and the text it is about to show. */
    public static void onLiveBio(@Nullable View view, @Nullable CharSequence text) {
        try {
            if (!(view instanceof TextView) || !enabled()) return;
            String bio = text == null ? null : clean(text);
            if (bio == null || bio.isEmpty()) {
                LIVE_COPIES.remove(view);
                return;
            }
            LIVE_COPIES.put(view, new String[]{"TikTok bio", bio, BIO_COPIED});
            view.setOnLongClickListener(LIVE_LISTENER);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not make the LIVE profile bio copyable", ex);
        }
    }

    /**
     * From the card's user bind, once for the display name and once for the username, with the view
     * and the text it is about to show. The username is the text that equals the user's own; its
     * long press copies the user ID and a copy mark beside it copies the username with one tap.
     * The name's long press copies the name. TikTok's own taps stay as they were.
     */
    public static void onLiveText(@Nullable View view, @Nullable CharSequence text) {
        try {
            if (!(view instanceof TextView) || !enabled() || text == null) return;
            Object user = liveUser.get();
            String shown = clean(text);
            if (user == null || shown.isEmpty()) return;
            String handle = Reflect.string(user, "getUsername", "username");
            String name = Reflect.string(user, "getNickName", "nickName");
            if (shown.equals(handle == null ? null : handle.trim())) {
                String uid = liveUserId(user);
                if (uid != null) {
                    LIVE_COPIES.put(view, new String[]{"TikTok ID", uid, USER_ID_COPIED});
                    view.setOnLongClickListener(LIVE_LISTENER);
                }
                addCopyMark((TextView) view, shown);
            } else if (shown.equals(name == null ? null : name.trim())) {
                LIVE_COPIES.put(view, new String[]{"TikTok name", shown, NAME_COPIED});
                view.setOnLongClickListener(LIVE_LISTENER);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not make the LIVE profile card copyable", ex);
        }
    }

    @Nullable
    private static String liveUserId(Object user) {
        String id = Reflect.string(user, "getIdStr", "idStr");
        if (!isBlank(id) && !"0".equals(id.trim())) return id.trim();
        Object number = Reflect.readField(user, "id");
        return number instanceof Long && (Long) number > 0 ? String.valueOf(number) : null;
    }

    static boolean copyLive(View view) {
        try {
            if (!enabled()) return false;
            String[] copy = LIVE_COPIES.get(view);
            if (copy == null) return false;
            copy(copy[0], copy[1], copy[2]);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not copy from the LIVE profile card", ex);
            return false;
        }
    }

    /**
     * A copy mark after the username that copies it on a tap. A tap anywhere else on the line goes
     * on to TikTok's own handling. A line that already ends in a drawable of its own keeps it, and
     * keeps the long press alone.
     */
    private static void addCopyMark(TextView line, String handle) {
        Drawable[] drawables = line.getCompoundDrawablesRelative();
        if (drawables[2] != null && !(drawables[2] instanceof CopyMark)) return;
        CopyMark mark = drawables[2] instanceof CopyMark
                ? (CopyMark) drawables[2]
                : new CopyMark(dp(line.getContext(), 14));
        mark.setColor(line.getCurrentTextColor());
        line.setCompoundDrawablesRelativeWithIntrinsicBounds(drawables[0], drawables[1], mark, drawables[3]);
        line.setCompoundDrawablePadding(dp(line.getContext(), 6));
        line.setContentDescription(L10n.t(line.getContext(), COPY_USERNAME));
        line.setOnTouchListener((touched, event) -> {
            if (!enabled() || !(touched instanceof TextView) || !markHit((TextView) touched, event)) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                copy("TikTok ID", handle, USERNAME_COPIED);
            }
            return true;
        });
    }

    /** True when the touch is on the copy mark, at the end of the line. */
    static boolean markHit(TextView line, MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return false;
        Drawable end = line.getCompoundDrawablesRelative()[2];
        if (!(end instanceof CopyMark)) return false;
        int reach = end.getIntrinsicWidth() + line.getCompoundDrawablePadding() + dp(line.getContext(), 10);
        boolean rtl = line.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float x = event.getX();
        return rtl ? x <= line.getPaddingLeft() + reach : x >= line.getWidth() - line.getPaddingRight() - reach;
    }

    /** Two overlapping outlined squares, drawn in the text's own color. */
    static final class CopyMark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        CopyMark(int size) {
            this.size = size;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size / 10f));
        }

        void setColor(int color) {
            paint.setColor(Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)));
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float side = bounds.width() * 0.62f;
            float inset = paint.getStrokeWidth();
            float left = bounds.left + inset;
            float top = bounds.top + inset;
            float back = left + (bounds.width() - side) - 2 * inset;
            float down = top + (bounds.height() - side) - 2 * inset;
            float radius = side / 6f;
            canvas.drawRoundRect(back, top, back + side, top + side, radius, radius, paint);
            canvas.drawRoundRect(left, down, left + side, down + side, radius, radius, paint);
        }

        @Override public int getIntrinsicWidth() {
            return size;
        }

        @Override public int getIntrinsicHeight() {
            return size;
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---- share sheet -------------------------------------------------------------------

    /** From the share model's constructor, with the package the sheet is being built from. */
    public static void onSharePackage(@Nullable Object sharePackage) {
        CopyIds.sharePackage = new WeakReference<>(sharePackage);
    }

    /**
     * The buttons the sheet built from {@code sharePackage} gets, in order: the copy buttons with
     * Copy bio and IDs on, and a profile's Account facts with that switch on.
     */
    static List<Target> targetsOf(@Nullable Object sharePackage) {
        if (sharePackage == null) return Collections.emptyList();
        boolean copies = enabled();
        List<Target> targets = new ArrayList<>(3);
        String itemType = Reflect.string(sharePackage, "getItemType", "itemType");
        if ("user".equals(itemType == null ? null : itemType.toLowerCase(Locale.ROOT))) {
            Object user = Reflect.readField(sharePackage, "user");
            if (copies) {
                String handle = Reflect.string(user, "getUniqueId", "uniqueId");
                String uid = Reflect.string(user, "getUid", "uid");
                if (handle != null) targets.add(new Target(COPY_USERNAME, handle, USERNAME_COPIED));
                if (uid != null) targets.add(new Target(COPY_USER_ID, uid, USER_ID_COPIED));
            }
            if (user != null && AccountFacts.enabled()) {
                targets.add(new Target(AccountFacts.SHOW_FACTS, () -> AccountFacts.show(user)));
            }
            return targets;
        }
        if (!copies) return targets;
        Object aweme = Reflect.readField(sharePackage, "aweme");
        String aid = Reflect.string(aweme, "getAid", "aid");
        if (aid != null) targets.add(new Target(COPY_VIDEO_ID, aid, VIDEO_ID_COPIED));
        return targets;
    }

    /** From the action panel's onAttachedToWindow; the row goes in once the panel has its list. */
    public static void onSharePanel(@Nullable View panel) {
        try {
            if (!(panel instanceof FrameLayout)) return;
            FrameLayout frame = (FrameLayout) panel;
            actionPanel = new WeakReference<>(frame);
            frame.post(() -> {
                try {
                    // An apps panel that drew the row before this panel came up hands it over.
                    FrameLayout apps = inSheetOf(frame, appsPanel.get());
                    if (apps != null) showRow(apps, Collections.emptyList());
                    showRow(frame, targetsOf(sharePackage.get()));
                } catch (Throwable ex) {
                    Logger.printException(() -> "Could not add the copy buttons to the share sheet", ex);
                }
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not watch the share sheet for copy buttons", ex);
        }
    }

    /**
     * From the apps panel's onAttachedToWindow, the FrameLayout that holds the Copy link, SMS and
     * other apps. The row goes in only when the sheet has no action panel.
     */
    public static void onAppsPanel(@Nullable View panel) {
        try {
            if (!(panel instanceof FrameLayout)) return;
            FrameLayout frame = (FrameLayout) panel;
            appsPanel = new WeakReference<>(frame);
            frame.post(() -> {
                try {
                    boolean hasActions = inSheetOf(frame, actionPanel.get()) != null;
                    showRow(frame, hasActions ? Collections.emptyList() : targetsOf(sharePackage.get()));
                } catch (Throwable ex) {
                    Logger.printException(() -> "Could not add the copy buttons under the share apps", ex);
                }
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not watch the share apps for copy buttons", ex);
        }
    }

    /** {@code other} when it's attached in the same sheet as {@code panel}. */
    @Nullable
    private static FrameLayout inSheetOf(View panel, @Nullable FrameLayout other) {
        if (other == null || other == panel || !other.isAttachedToWindow()) return null;
        return other.getRootView() == panel.getRootView() ? other : null;
    }

    static void showRow(FrameLayout panel, List<Target> targets) {
        LinearLayout row = findRow(panel);
        if (targets.isEmpty()) {
            if (row != null) row.setVisibility(View.GONE);
            reserve(panel, row, 0);
            return;
        }
        Context context = panel.getContext();
        if (row == null) {
            row = new LinearLayout(context);
            row.setTag(ROW_TAG);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 10));
            panel.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        row.removeAllViews();
        int color = textColor(panel);
        for (Target target : targets) {
            row.addView(button(context, target, color));
        }
        row.setVisibility(View.VISIBLE);
        int width = panel.getWidth() > 0 ? panel.getWidth() : context.getResources().getDisplayMetrics().widthPixels;
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        reserve(panel, row, row.getMeasuredHeight());
    }

    /** Moves TikTok's own children up by {@code height}, from the margin each had before. */
    private static void reserve(FrameLayout panel, @Nullable View row, int height) {
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (child == row || !(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            Integer original = ORIGINAL_MARGINS.get(child);
            if (original == null) {
                if (height == 0) continue;
                original = params.bottomMargin;
                ORIGINAL_MARGINS.put(child, original);
            }
            int wanted = original + height;
            if (params.bottomMargin != wanted) {
                params.bottomMargin = wanted;
                child.setLayoutParams(params);
            }
        }
    }

    @Nullable
    private static LinearLayout findRow(ViewGroup panel) {
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (child instanceof LinearLayout && ROW_TAG.equals(child.getTag())) return (LinearLayout) child;
        }
        return null;
    }

    private static TextView button(Context context, Target target, int color) {
        TextView button = new TextView(context);
        button.setText(L10n.t(context, target.label));
        button.setTextColor(color);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setSingleLine(true);
        button.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(context, 16));
        background.setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)));
        button.setBackground(background);
        button.setClickable(true);
        button.setOnClickListener(view -> {
            try {
                if (target.action != null) {
                    target.action.run();
                    return;
                }
                copy("TikTok ID", target.text, target.toast);
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not copy from the share sheet", ex);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(context, 8));
        button.setLayoutParams(params);
        return button;
    }

    /** The panel's own text color when it shows any text, the theme's otherwise. */
    private static int textColor(ViewGroup panel) {
        TextView shown = firstText(panel);
        if (shown != null) return shown.getCurrentTextColor();
        TypedArray attributes = panel.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            return attributes.getColor(0, Color.GRAY);
        } finally {
            attributes.recycle();
        }
    }

    @Nullable
    private static TextView firstText(View view) {
        if (ROW_TAG.equals(view.getTag())) return null;
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE
                && ((TextView) view).getText() != null && ((TextView) view).getText().length() > 0) {
            return (TextView) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = firstText(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    // ---- shared ------------------------------------------------------------------------

    /**
     * Copies through the shared helper, which marks the clip sensitive. Android 13 and newer show
     * their own copy notice with the text hidden, so the toast says which button it was.
     */
    private static void copy(String clipLabel, String text, String toast) {
        if (GestureActions.copyToClipboard(clipLabel, text)) Utils.showToastShort(L10n.t(toast));
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
