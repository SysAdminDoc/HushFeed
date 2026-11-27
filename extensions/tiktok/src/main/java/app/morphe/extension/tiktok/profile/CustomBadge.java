/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * The picture you picked to stand in for the blue check beside your own name (#549). It's copied
 * into TikTok's private files on this phone, drawn only on your own profile while Local verified
 * badge is on, and never sent anywhere.
 */
public final class CustomBadge {
    static final String FILE_NAME = "hushfeed_badge_picture.png";
    /** Pixels a side the picture is kept at, enough for a 16dp badge on a 4x screen. */
    static final int SIDE = 96;
    /** A picked file over this size isn't read. */
    public static final int MAX_BYTES = 20 * 1024 * 1024;

    /** Why a picked file wasn't kept. */
    public static final class Rejected extends IOException {
        public final boolean tooLarge;

        Rejected(boolean tooLarge, String message) {
            super(message);
            this.tooLarge = tooLarge;
        }
    }

    private static File cachedFile;
    private static long cachedModified, cachedLength;
    private static Bitmap cached;

    private CustomBadge() {}

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** Whether a badge picture is kept on this phone. */
    public static boolean present(@Nullable Context context) {
        return context != null && file(context).isFile();
    }

    /** A picked picture made ready to keep, not kept yet. */
    public static final class Prepared {
        final Bitmap square;

        Prepared(Bitmap square) {
            this.square = square;
        }
    }

    /**
     * Reads the picked file whole and makes a {@link #SIDE}-pixel square of it with its shape kept.
     * Nothing is written, so a file that isn't a picture changes nothing.
     */
    public static Prepared prepare(InputStream in) throws IOException {
        byte[] bytes;
        try (InputStream source = in) {
            bytes = readAtMost(source, MAX_BYTES);
        }
        if (bytes == null) throw new Rejected(true, "over " + MAX_BYTES + " bytes");
        Bitmap decoded = decode(bytes);
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            throw new Rejected(false, "not a picture");
        }
        Bitmap square = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        float scale = Math.min((float) SIDE / decoded.getWidth(), (float) SIDE / decoded.getHeight());
        float width = decoded.getWidth() * scale, height = decoded.getHeight() * scale;
        RectF into = new RectF((SIDE - width) / 2f, (SIDE - height) / 2f, (SIDE + width) / 2f, (SIDE + height) / 2f);
        new Canvas(square).drawBitmap(decoded, null, into, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return new Prepared(square);
    }

    /** Keeps the prepared picture in place of any older one. */
    public static void keep(Context context, Prepared picture) throws IOException {
        Bitmap square = picture.square;
        File target = file(context);
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            if (!square.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
            out.getFD().sync();
        } catch (IOException failure) {
            if (!temp.delete()) temp.deleteOnExit();
            throw failure;
        }
        if (!temp.renameTo(target)) {
            if (!temp.delete()) temp.deleteOnExit();
            throw new IOException("Could not keep the badge picture");
        }
        forget();
    }

    /** Deletes the kept picture. True when none is left. */
    public static boolean remove(@Nullable Context context) {
        if (context == null) return false;
        forget();
        File kept = file(context);
        return !kept.exists() || kept.delete();
    }

    /** The span that draws the picture in the blue check's own space, or null with no picture kept. */
    @Nullable static ReplacementSpan span(@Nullable Context context, ReplacementSpan nativeBadge) {
        Bitmap picture = context == null ? null : bitmap(context);
        return picture == null ? null : new PictureSpan(picture, nativeBadge);
    }

    @Nullable static synchronized Bitmap bitmap(Context context) {
        File kept = file(context);
        long modified = kept.lastModified(), length = kept.length();
        if (kept.equals(cachedFile) && modified == cachedModified && length == cachedLength) return cached;
        Bitmap read = null;
        if (kept.isFile()) {
            try {
                read = BitmapFactory.decodeFile(kept.getPath());
            } catch (RuntimeException | OutOfMemoryError failure) {
                Logger.printException(() -> "Could not read the badge picture", failure);
            }
        }
        cachedFile = kept;
        cachedModified = modified;
        cachedLength = length;
        cached = read;
        return read;
    }

    private static synchronized void forget() {
        cachedFile = null;
        cached = null;
    }

    /** The bytes, or null when there are more than {@code limit}. */
    @Nullable private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int total = 0;
        for (int read; (read = in.read(chunk)) != -1; ) {
            total += read;
            if (total > limit) return null;
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    @Nullable private static Bitmap decode(byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder turns a photo upright from its EXIF orientation, which BitmapFactory doesn't.
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                        (decoder, info, source) -> {
                            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                            int shrink = Math.max(1, Math.min(width, height) / (SIDE * 2));
                            if (shrink > 1) decoder.setTargetSize(Math.max(1, width / shrink), Math.max(1, height / shrink));
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= SIDE * 2) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            Logger.printInfo(() -> "The picked badge file isn't a picture: " + failure);
            return null;
        }
    }

    /** The picture, as wide as the blue check would have been and centred on the name's letters. */
    static final class PictureSpan extends ReplacementSpan {
        final Bitmap picture;
        private final ReplacementSpan nativeBadge;
        private final Paint draw = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        PictureSpan(Bitmap picture, ReplacementSpan nativeBadge) {
            this.picture = picture;
            this.nativeBadge = nativeBadge;
        }

        @Override public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                @Nullable Paint.FontMetricsInt metrics) {
            int size = 0;
            try {
                size = nativeBadge == null ? 0 : nativeBadge.getSize(paint, text, start, end, metrics);
            } catch (RuntimeException failure) {
                Logger.printInfo(() -> "The native badge couldn't measure itself: " + failure);
            }
            return size > 0 ? size : Math.round(paint.getTextSize());
        }

        @Override public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, @NonNull Paint paint) {
            float side = getSize(paint, text, start, end, null);
            float middle = y + (paint.ascent() + paint.descent()) / 2f;
            canvas.drawBitmap(picture, null, new RectF(x, middle - side / 2f, x + side, middle + side / 2f), draw);
        }
    }
}
