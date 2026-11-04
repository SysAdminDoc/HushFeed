/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.content.Context;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public final class SectionHeadingPreference extends Preference {
    public SectionHeadingPreference(Context context, String title) {
        super(context);
        setTitle(L10n.t(context, title));
        setSelectable(false);
    }

    public static SectionHeadingPreference compact(Context context, String title) {
        return new SectionHeadingPreference(context, title);
    }

    /** Retains the constructor used by existing add-ons; headings now share one compact style. */
    public SectionHeadingPreference(Context context, String title, boolean compact) {
        this(context, title);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setId(android.R.id.title);
        title.setText(getTitle());
        SettingsUi.markHeading(title);
        layout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScreenColors.current().paintHeading(layout);
        return layout;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) title.setText(getTitle());
        // Read on each bind, so a theme change shows on the next one.
        ScreenColors.current().paintHeading(view);
    }
}
