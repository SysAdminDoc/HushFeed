/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Shows a profile picture full screen before it's saved (#467). TikTok only ever draws the
 * picture as a small circle, so this loads the largest copy the profile carries, the same one a
 * save writes, and shows all of it with a Save button under it. A tap anywhere else closes it.
 */
final class ProfilePictureViewer {
    private ProfilePictureViewer() {
    }

    /** The longest side a picture is decoded at; TikTok's largest avatar is 1080 pixels. */
    private static final int MAX_SIDE = 2048;

    /** Fetches and decodes the picture. Tests swap it so nothing reaches the network. */
    interface Loader {
        Bitmap load(Context app, List<String> urls) throws IOException;
    }

    static volatile Loader loaderForTests;

    static Dialog show(View avatar, Object user, List<String> urls) {
        Context context = avatar.getContext();
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        ImageView picture = new ImageView(context);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setContentDescription(L10n.t("Profile picture"));
        root.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ProgressBar spinner = new ProgressBar(context);
        root.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        Button save = new Button(context);
        save.setText(L10n.t("Save"));
        SettingsUi.styleActionButton(save, true);
        FrameLayout.LayoutParams under = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        under.bottomMargin = SettingsUi.dp(context, 32);
        root.addView(save, under);

        root.setOnClickListener(tap -> dialog.dismiss());
        save.setOnClickListener(tap -> {
            dialog.dismiss();
            ProfileAvatarSaver.save(avatar, user);
        });
        dialog.setContentView(root);
        dialog.show();

        Context app = context.getApplicationContext();
        List<String> snapshot = List.copyOf(urls);
        boolean queued = Utils.runOnBackgroundThread(() -> {
            Bitmap loaded = null;
            try {
                loaded = load(app, snapshot);
            } catch (IOException | RuntimeException failure) {
                Logger.printException(() -> "Could not load the profile picture", failure);
            }
            Bitmap shown = loaded;
            Utils.runOnMainThread(() -> {
                if (!dialog.isShowing()) return;
                spinner.setVisibility(View.GONE);
                if (shown == null) {
                    dialog.dismiss();
                    Utils.showToastLong(L10n.t("The profile picture couldn't be loaded. Try again."));
                    return;
                }
                picture.setImageBitmap(shown);
            });
        });
        if (!queued) {
            dialog.dismiss();
            Utils.showToastLong(L10n.t("The profile picture couldn't be loaded. Try again."));
        }
        return dialog;
    }

    static Bitmap load(Context app, List<String> urls) throws IOException {
        Loader loader = loaderForTests;
        if (loader != null) return loader.load(app, urls);
        File temp = MediaCache.createTempFile(app, "profile-picture-view-", ".tmp");
        try {
            RemoteMedia.fetch(urls, temp, RemoteMedia.Kind.IMAGE);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temp.getPath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            Bitmap bitmap = BitmapFactory.decodeFile(temp.getPath(), options);
            if (bitmap == null) throw new IOException("The profile picture didn't decode");
            return bitmap;
        } finally {
            if (!MediaCache.delete(temp)) Logger.printInfo(() -> "Could not remove the viewed profile picture");
        }
    }

    /** The power of two that brings the longest side to {@link #MAX_SIDE} or under. */
    static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width, height) / sample > MAX_SIDE) sample *= 2;
        return sample;
    }
}
