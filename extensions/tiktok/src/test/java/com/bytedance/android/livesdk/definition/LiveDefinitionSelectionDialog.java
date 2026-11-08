package com.bytedance.android.livesdk.definition;

import android.app.Dialog;
import android.view.View;

import com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment;

/** Test stand-in for the host's older Video quality picker: a themed LIVE dialog whose root view is its card. */
public class LiveDefinitionSelectionDialog extends LiveThemeAwareDialogFragment {
    public Dialog dialog;
    public View view;

    public Dialog getDialog() {
        return dialog;
    }

    public View getView() {
        return view;
    }
}
