package com.bytedance.android.live.liveinteract.multiguestv3.main.media.guest.previewv1;

import android.app.Dialog;
import android.view.View;

import com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment;

/** Test stand-in for the base of the host's guest panels: a themed LIVE dialog whose white sheet sits inside its page. */
public abstract class PreviewDialogFragment extends LiveThemeAwareDialogFragment {
    public Dialog dialog;
    public View view;

    public Dialog getDialog() {
        return dialog;
    }

    public View getView() {
        return view;
    }
}
