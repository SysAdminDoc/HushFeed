package com.bytedance.android.livesdk.widgets.giftwidget.presenter;

import android.app.Dialog;
import android.view.View;

import com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment;

/** Test stand-in for the host's quick gift card: a themed LIVE dialog whose root view is the dim around its card. */
public class FastGiftConfirmDialog extends LiveThemeAwareDialogFragment {
    public Dialog dialog;
    public View view;

    public Dialog getDialog() {
        return dialog;
    }

    public View getView() {
        return view;
    }
}
