/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Modified for hushfeed (TikTok), 2026.
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.BuildDetails;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * The top of the main page: Hushfeed's icon, its name in TikTok's cyan and red and the version
 * line, centred over the rows. Nothing on it can be tapped. The bar above the list leaves the name
 * off while this one shows it.
 */
@SuppressWarnings("deprecation")
public final class SettingsHeader extends Preference {
    static final String KEY = "hushfeed_settings_header";
    /** Tags of its parts, for the tests. */
    static final String ICON = "hushfeed-header-icon";
    static final String NAME = "hushfeed-header-name";
    static final String VERSION = "hushfeed-header-version";
    /** The resource patch that gives TikTok Hushfeed's launcher icon. */
    static final String LAUNCHER_ICON_PATCH = "customLauncherIcon";
    /** The product name, which reads the same in every language and is not translated. */
    private static final String NAME_TEXT = "Hushfeed";

    SettingsHeader(Context context) {
        super(context);
        setKey(KEY);
        // What it shows, for anything that reads rows by their titles.
        setTitle(NAME_TEXT);
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        ScreenColors colors = ScreenColors.current();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Math.round(24 * density), Math.round(28 * density), Math.round(24 * density),
                Math.round(20 * density));

        ImageView icon = new ImageView(context);
        icon.setTag(ICON);
        icon.setImageDrawable(icon(context, colors, Utils.getBuildMetadata()));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // The name under it says the same.
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(64 * density);
        column.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView name = new TextView(context);
        name.setTag(NAME);
        name.setText(NAME_TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setTextColor(colors.title);
        SettingsUi.markHeading(name);
        ShimmerTitle.wear(name);
        LinearLayout.LayoutParams nameSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameSpace.topMargin = Math.round(12 * density);
        column.addView(name, nameSpace);

        TextView version = new TextView(context);
        version.setTag(VERSION);
        CharSequence line = versionLine(context, Utils.getPatchesReleaseVersion(), Utils.getAppVersionName());
        version.setText(line);
        // An unpatched build has no bundle version to name, and an empty line would only add a gap.
        version.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        version.setTextColor(colors.summary);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionSpace.topMargin = Math.round(4 * density);
        column.addView(version, versionSpace);
        return column;
    }

    /**
     * "Version 1.2.3 for TikTok 45.6.7", each number isolated so a right-to-left sentence keeps it
     * whole. Without the bundle's version it's empty, and without TikTok's it names the bundle alone,
     * the way About does.
     */
    static CharSequence versionLine(Context context, String bundleVersion, String appVersion) {
        if (bundleVersion == null || bundleVersion.isEmpty()) return "";
        // "Unknown" is the English word Utils hands back when the package manager refuses.
        boolean readable = appVersion != null && !appVersion.isEmpty() && !"Unknown".equals(appVersion);
        if (!readable) return L10n.f(context, "Version %1$s", L10n.isolate(bundleVersion));
        return L10n.f(context, "Version %1$s for TikTok %2$s", L10n.isolate(bundleVersion),
                L10n.isolate(appVersion));
    }

    /**
     * The app's own launcher icon when this build has the launcher icon patch, which makes it
     * Hushfeed's, or the patched mark in the heading colour.
     */
    static Drawable icon(Context context, ScreenColors colors, String metadata) {
        if (BuildDetails.resourcePatchApplied(metadata, LAUNCHER_ICON_PATCH)) {
            Drawable own = context.getApplicationInfo().loadIcon(context.getPackageManager());
            if (own != null) return own;
        }
        return SettingsIcons.icon(context, SettingsIcons.PATCHED, colors.heading);
    }
}
