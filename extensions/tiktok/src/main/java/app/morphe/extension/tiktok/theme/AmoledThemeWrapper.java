/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import android.content.Context;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;

/**
 * What every {@code new ContextThemeWrapper(context, theme)} in TikTok's code builds once AMOLED
 * dark theme is patched in (#372): the same wrapper, whose theme gets TikTok's own dark grays back
 * when the AMOLED dark mode switch is off. TikTok's sheets, popups and Scene pages theme
 * themselves this way, and Android's own wrapper applies the style where no patch can reach.
 */
public class AmoledThemeWrapper extends ContextThemeWrapper {
    public AmoledThemeWrapper(Context base, int themeResId) {
        super(base, themeResId);
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        super.onApplyThemeResource(theme, resid, first);
        AmoledTheme.restore(theme);
    }
}
